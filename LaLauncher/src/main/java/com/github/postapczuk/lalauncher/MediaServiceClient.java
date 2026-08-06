/*
 * ZX1 Retro Interface
 * Hand-rolled binder client for the Sony NW-ZX1 firmware media service.
 * Licensed under GPLv2; see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the current track from the firmware player's IMediaServiceRemote
 * binder and joins the GenieMediaStore database for the track's stored
 * metadata - the only now-playing source on firmware 1.13, which emits no
 * broadcast and no RemoteControlClient. The whole pipeline needs zero
 * permissions. Protocol (call chain, transaction codes, PlayInfo parcel
 * order) is documented in docs/firmware-findings.md; the codes live in
 * {@link SonyWalkmanContract}.
 *
 * All binder and provider I/O runs on a private worker thread so a wedged
 * service can never stall the LCD; the listener is always called on the
 * main thread. Only the documented transactions are ever issued - unknown
 * codes are never probed against the live player.
 */
final class MediaServiceClient {

    /**
     * One getCurrentInfo() poll. Live values come straight from the player;
     * {@code track} carries the joined Genie row and is null whenever the
     * row cannot be read - the display must then omit, never invent.
     */
    static final class Snapshot {
        final int state;
        final long trackId;
        final int playTimeMs;
        final int durationMs;
        final int trackIndexInList;
        final int trackCountInList;
        final Track track;

        Snapshot(int state, long trackId, int playTimeMs, int durationMs,
                 int trackIndexInList, int trackCountInList, Track track) {
            this.state = state;
            this.trackId = trackId;
            this.playTimeMs = playTimeMs;
            this.durationMs = durationMs;
            this.trackIndexInList = trackIndexInList;
            this.trackCountInList = trackCountInList;
            this.track = track;
        }
    }

    /**
     * Stored metadata for one Genie row. Unreadable fields are null / -1.
     * {@code albumId} and {@code durationMs} (the stored value, which may
     * differ slightly from the player's live duration) exist for the album
     * art resolver's source lookups (B6); {@code trackNo} feeds the same
     * platform join's identical-title tie-break. {@code genieId} is the
     * row's own id - for a launcher-started platform queue it is the
     * mapped Genie row, not the reported platform id - and keys the
     * spectrum envelope (B7b) together with {@code durationMs}.
     */
    static final class Track {
        final long genieId;
        final String title;
        final String artist;
        final String album;
        final long albumId;
        final int trackNo;
        final int year;
        final int durationMs;
        final int sampleRate;
        final int bitsPerSample;
        final int bitrate;
        final int codec;
        final String mimeType;
        final int channels;

        Track(long genieId, String title, String artist, String album, long albumId,
              int trackNo, int year, int durationMs, int sampleRate, int bitsPerSample,
              int bitrate, int codec, String mimeType, int channels) {
            this.genieId = genieId;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.albumId = albumId;
            this.trackNo = trackNo;
            this.year = year;
            this.durationMs = durationMs;
            this.sampleRate = sampleRate;
            this.bitsPerSample = bitsPerSample;
            this.bitrate = bitrate;
            this.codec = codec;
            this.mimeType = mimeType;
            this.channels = channels;
        }
    }

    interface Listener {
        /** Main thread. {@code snapshot} is null while nothing is readable. */
        void onNowPlaying(Snapshot snapshot);
    }

    private static final String TAG = "ZX1MediaService";
    private static final long POLL_MS = 1000L;

    private final Context context;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Main-thread state.
    private HandlerThread workerThread;
    private Handler worker;
    private boolean started;
    private boolean bound;

    // Worker-thread state.
    private IBinder service;
    private final List<IBinder> players = new ArrayList<IBinder>();
    private long cachedTrackId;
    private Track cachedTrack;
    /** The current list's uri, cached per reported track id. */
    private android.net.Uri listUri;
    private long listUriTrackId;
    /** Per-id guards so mapping outcomes log once, not once per poll. */
    private long lastMappedPlatformId;
    private long lastFailedPlatformId;
    private static final String PLATFORM_AUTHORITY =
            android.provider.MediaStore.AUTHORITY;
    private String lastLoggedPlayers;
    private boolean loggedFailure;
    private boolean genieProjectionFellBack;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, final IBinder binder) {
            Handler w = worker;
            if (w == null) {
                return;
            }
            w.post(new Runnable() {
                @Override
                public void run() {
                    service = binder;
                    players.clear();
                }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            Handler w = worker;
            if (w == null) {
                return;
            }
            w.post(new Runnable() {
                @Override
                public void run() {
                    service = null;
                    players.clear();
                }
            });
        }
    };

    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            Handler w = worker;
            if (w == null) {
                return;
            }
            final Snapshot snapshot = readSnapshot();
            mainHandler.post(new Runnable() {
                @Override
                public void run() {
                    if (started) {
                        listener.onNowPlaying(snapshot);
                    }
                }
            });
            w.postDelayed(this, POLL_MS);
        }
    };

    MediaServiceClient(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    /** Main thread. Binds and begins the ~1s poll; call from onResume. */
    void start() {
        if (started) {
            return;
        }
        started = true;
        workerThread = new HandlerThread("zx1-media-poll");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        worker.post(new Runnable() {
            @Override
            public void run() {
                service = null;
                players.clear();
                cachedTrackId = 0;
                cachedTrack = null;
            }
        });
        bindMediaService();
        worker.post(pollRunnable);
    }

    /** Main thread. Stops polling and unbinds; call from onPause. */
    void stop() {
        if (!started) {
            return;
        }
        started = false;
        if (bound) {
            try {
                context.unbindService(connection);
            } catch (IllegalArgumentException ignored) {
                // Bind raced with a process-side teardown; nothing to release.
            }
            bound = false;
        }
        worker.removeCallbacksAndMessages(null);
        workerThread.quit();
        worker = null;
        workerThread = null;
    }

    private void bindMediaService() {
        if (!SonyWalkmanContract.isInstalled(context.getPackageManager())) {
            return;
        }
        try {
            bound = context.bindService(SonyWalkmanContract.createMediaServiceIntent(),
                    connection, Context.BIND_AUTO_CREATE);
        } catch (SecurityException exception) {
            bound = false;
            Log.w(TAG, "media service bind rejected", exception);
        }
    }

    private Snapshot readSnapshot() {
        IBinder mediaService = service;
        if (mediaService == null) {
            return null;
        }
        try {
            if (players.isEmpty()) {
                resolvePlayers(mediaService);
            }
            Snapshot best = null;
            IBinder bestPlayer = null;
            for (IBinder player : players) {
                Snapshot info = readCurrentInfo(player);
                if (info == null) {
                    continue;
                }
                if (info.state == SonyWalkmanContract.PLAY_STATE_PLAY) {
                    best = info;
                    bestPlayer = player;
                    break;
                }
                if (best == null) {
                    best = info;
                    bestPlayer = player;
                }
            }
            if (best == null) {
                // No player answered. The active list may have changed - the
                // hardware play key can start one without leaving home - so
                // re-resolve on the next poll.
                players.clear();
                return null;
            }
            loggedFailure = false;
            return new Snapshot(best.state, best.trackId, best.playTimeMs, best.durationMs,
                    best.trackIndexInList, best.trackCountInList,
                    trackForId(best.trackId, bestPlayer));
        } catch (DeadObjectException exception) {
            service = null;
            players.clear();
            scheduleRebind();
            return null;
        } catch (RemoteException exception) {
            logOnce("media service call failed", exception);
            return null;
        } catch (RuntimeException exception) {
            // A parcel misread must never take the home screen down.
            logOnce("media service reply unreadable", exception);
            return null;
        }
    }

    private void resolvePlayers(IBinder mediaService) throws RemoteException {
        List<String> names = getPlayers(mediaService);
        String joined = names.toString();
        if (!joined.equals(lastLoggedPlayers)) {
            Log.i(TAG, "media service players: " + joined);
            lastLoggedPlayers = joined;
        }
        // Audio-ish names first so an idle video player cannot shadow music.
        List<String> ordered = new ArrayList<String>(names.size());
        for (String name : names) {
            if (isAudioName(name)) {
                ordered.add(name);
            }
        }
        for (String name : names) {
            if (!isAudioName(name)) {
                ordered.add(name);
            }
        }
        for (String name : ordered) {
            IBinder player = getPlayerInterface(mediaService, name);
            if (player != null) {
                players.add(player);
            }
        }
    }

    /** Shared with PlayerHandoff so both sides pick the same audio player. */
    static boolean isAudioName(String name) {
        String lower = name.toLowerCase(Locale.US);
        return lower.contains("music") || lower.contains("audio");
    }

    static List<String> getPlayers(IBinder mediaService) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SonyWalkmanContract.MEDIA_SERVICE_DESCRIPTOR);
            mediaService.transact(SonyWalkmanContract.TRANSACTION_GET_PLAYERS, data, reply, 0);
            reply.readException();
            List<String> names = reply.createStringArrayList();
            return names == null ? new ArrayList<String>() : names;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    static IBinder getPlayerInterface(IBinder mediaService, String playerName)
            throws RemoteException {
        return getPlayerInterface(mediaService, playerName, null);
    }

    /**
     * getPlayerInterface with the registration Bundle: the service fills it
     * (notably the client id under {@code "id"}) and hands it back in the
     * reply, exactly as Sony's Player constructor consumes it. A null
     * bundle stays the anonymous read-side form.
     */
    static IBinder getPlayerInterface(IBinder mediaService, String playerName,
                                      android.os.Bundle options) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SonyWalkmanContract.MEDIA_SERVICE_DESCRIPTOR);
            data.writeString(playerName);
            if (options != null) {
                data.writeInt(1);
                options.writeToParcel(data, 0);
            } else {
                data.writeInt(0);
            }
            mediaService.transact(SonyWalkmanContract.TRANSACTION_GET_PLAYER_INTERFACE,
                    data, reply, 0);
            reply.readException();
            IBinder player = reply.readStrongBinder();
            if (reply.readInt() != 0 && options != null) {
                options.readFromParcel(reply);
            }
            return player;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * getCurrentInfo(). The reply's PlayInfo fields must be consumed in
     * exactly the firmware's writeToParcel order to stay aligned; reading
     * stops after the PlayState ordinal - nothing later is needed.
     */
    static Snapshot readCurrentInfo(IBinder player) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SonyWalkmanContract.PLAYER_DESCRIPTOR);
            player.transact(SonyWalkmanContract.TRANSACTION_GET_CURRENT_INFO, data, reply, 0);
            reply.readException();
            if (reply.readInt() == 0) {
                return null; // no PlayInfo
            }
            reply.readInt(); // error code
            reply.readInt(); // speed numerator
            reply.readInt(); // speed denominator
            long trackId = reply.readLong();
            int playTime = reply.readInt();
            int duration = reply.readInt();
            reply.readInt(); // chapter index
            reply.readInt(); // chapter count
            reply.readInt(); // play time within chapter
            int trackIndex = reply.readInt();
            int trackCount = reply.readInt();
            int state = reply.readInt(); // PlayState ordinal
            return new Snapshot(state, trackId, playTime, duration, trackIndex, trackCount, null);
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * The player reports a bare row id whose id-space depends on the
     * provider the current queue rides: Sony-initiated queues carry Genie
     * ids, launcher-initiated queues carry platform MediaStore ids (Genie
     * withholds `_data` from unprivileged callers, so our queues must use
     * the platform provider - findings "ROOT CAUSE"). The current list's
     * uri authority, read once per track change, names the id-space; a
     * platform id is then mapped back to its Genie row through the library
     * snapshot so the whole display pipeline stays Genie-based.
     */
    private Track trackForId(long trackId, IBinder player) {
        if (trackId <= 0) {
            return null;
        }
        // A null result is retried every poll: the row may exist but the
        // provider hiccupped, and the queries are single indexed lookups.
        if (trackId != cachedTrackId || cachedTrack == null) {
            android.net.Uri uri = listUriFor(player, trackId);
            String authority = uri == null ? null : uri.getAuthority();
            Track track;
            if (PLATFORM_AUTHORITY.equals(authority)) {
                // A members queue (queue-next, v2.13.0) may report the
                // member-row id rather than the audio id - a device
                // question; the resolver below answers either way.
                long audioId = isMembersUri(uri)
                        ? memberAudioId(uri, trackId) : trackId;
                track = trackViaPlatform(audioId);
            } else {
                track = queryGenie(trackId);
                if (track == null && authority == null) {
                    // Unknown list; the platform mapping is the fallback.
                    track = trackViaPlatform(trackId);
                }
            }
            cachedTrack = track;
            cachedTrackId = trackId;
        }
        return cachedTrack;
    }

    /** The current list's uri, re-read when the track changes. */
    private android.net.Uri listUriFor(IBinder player, long trackId) {
        if (player == null) {
            return null;
        }
        if (trackId != listUriTrackId || listUri == null) {
            listUri = readMediaListUri(player);
            listUriTrackId = trackId;
        }
        return listUri;
    }

    /** True for a platform playlist members path (the queue-next lists). */
    private static boolean isMembersUri(android.net.Uri uri) {
        String path = uri.getPath();
        return path != null && path.contains("/audio/playlists/")
                && path.endsWith("/members");
    }

    /**
     * Resolves a members-queue report to the audio id. Device-proven
     * 2026-07-21: the engine reports the platform AUDIO id from a members
     * queue (the reported id survived a full member delete+reinsert, which
     * no member-row id could), so a report matching any audio_id in the
     * list passes through first - this also makes a member-row id that
     * merely collides numerically with some other row's audio id harmless.
     * The member-row walk stays as the second reading; anything unmatched
     * passes through and the glass goes dark honestly on a miss, logged
     * by the existing guard.
     */
    private long memberAudioId(android.net.Uri membersUri, long reportedId) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(membersUri,
                    new String[]{"_id", "audio_id"}, null, null, "play_order");
            if (cursor == null) {
                return reportedId;
            }
            long mappedFromMemberId = 0;
            boolean isAudioId = false;
            while (cursor.moveToNext()) {
                if (cursor.getLong(1) == reportedId) {
                    isAudioId = true;
                    break;
                }
                if (mappedFromMemberId == 0 && cursor.getLong(0) == reportedId) {
                    mappedFromMemberId = cursor.getLong(1);
                }
            }
            if (!isAudioId && mappedFromMemberId > 0) {
                return mappedFromMemberId;
            }
            return reportedId;
        } catch (RuntimeException exception) {
            logOnce("members map read failed", exception);
            return reportedId;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * Minimal getMediaListInfo (txn 22) read: only the current list's uri.
     * The handoff's fuller reader stays in {@link PlayerHandoff}; this one
     * serves the poll's id-space routing. Null on any failure.
     */
    private static android.net.Uri readMediaListUri(IBinder player) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SonyWalkmanContract.PLAYER_DESCRIPTOR);
            player.transact(SonyWalkmanContract.TRANSACTION_GET_MEDIA_LIST_INFO,
                    data, reply, 0);
            reply.readException();
            if (reply.readInt() == 0) {
                return null;
            }
            return reply.readInt() != 0
                    ? (android.net.Uri) android.net.Uri.CREATOR.createFromParcel(reply)
                    : null;
        } catch (RemoteException exception) {
            return null;
        } catch (RuntimeException exception) {
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * Maps a platform MediaStore id to its Genie row: read the platform
     * row's identity, hand it to the shared reverse join
     * ({@link AlbumArtResolver#findLibraryTrack}, which owns the strict
     * discipline - title + album, duration tolerance, track-number
     * tie-break, exactly one survivor), then run the normal Genie query on
     * the matched id. Null - and therefore a dark line, never a guess -
     * when the snapshot is cold or the match is not unique.
     */
    private Track trackViaPlatform(long platformId) {
        LibraryStore.Library library = LibraryStore.peek();
        if (library == null) {
            return null;
        }
        String title = null;
        String album = null;
        int durationMs = -1;
        int trackNo = -1;
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    ContentUris.withAppendedId(
                            android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            platformId),
                    new String[]{
                            android.provider.MediaStore.Audio.Media.TITLE,
                            android.provider.MediaStore.Audio.Media.ALBUM,
                            android.provider.MediaStore.Audio.Media.DURATION,
                            android.provider.MediaStore.Audio.Media.TRACK},
                    null, null, null);
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            title = stringColumn(cursor, android.provider.MediaStore.Audio.Media.TITLE);
            album = stringColumn(cursor, android.provider.MediaStore.Audio.Media.ALBUM);
            durationMs = intColumn(cursor, android.provider.MediaStore.Audio.Media.DURATION);
            trackNo = intColumn(cursor, android.provider.MediaStore.Audio.Media.TRACK);
        } catch (RuntimeException exception) {
            logOnce("platform row query failed", exception);
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        if (title == null) {
            return null;
        }
        LibraryStore.Track match = AlbumArtResolver.findLibraryTrack(
                library, title, album, durationMs, trackNo);
        if (match == null) {
            if (platformId != lastFailedPlatformId) {
                Log.w(TAG, "platform id " + platformId
                        + " has no unique library row; staying dark");
                lastFailedPlatformId = platformId;
            }
            return null;
        }
        if (platformId != lastMappedPlatformId) {
            Log.i(TAG, "platform id " + platformId + " -> genie id " + match.id
                    + " (" + match.title + ")");
            lastMappedPlatformId = platformId;
        }
        return queryGenie(match.id);
    }

    private Track queryGenie(long trackId) {
        if (!genieProjectionFellBack) {
            try {
                return queryGenie(trackId, SonyWalkmanContract.GENIE_AUDIO_PROJECTION);
            } catch (RuntimeException exception) {
                // Most likely a column this firmware lacks (album_id is
                // schema-derived, never value-read). The proven v2.4.0
                // columns keep the metadata lines alive; the art resolver
                // recovers the album id from its own row query.
                genieProjectionFellBack = true;
                Log.w(TAG, "extended Genie projection failed; using fallback columns", exception);
            }
        }
        try {
            return queryGenie(trackId, SonyWalkmanContract.GENIE_AUDIO_FALLBACK_PROJECTION);
        } catch (RuntimeException exception) {
            logOnce("Genie query failed", exception);
            return null;
        }
    }

    private Track queryGenie(long trackId, String[] projection) {
        Cursor cursor = null;
        try {
            // The Genie provider answers only appended-id URIs; it returns
            // nothing for caller-supplied selections (see the contract).
            cursor = context.getContentResolver().query(
                    ContentUris.withAppendedId(SonyWalkmanContract.GENIE_AUDIO_URI, trackId),
                    projection,
                    null,
                    null,
                    null);
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            return new Track(
                    trackId,
                    stringColumn(cursor, SonyWalkmanContract.COLUMN_TITLE),
                    stringColumn(cursor, SonyWalkmanContract.COLUMN_ARTIST),
                    stringColumn(cursor, SonyWalkmanContract.COLUMN_ALBUM),
                    longColumn(cursor, SonyWalkmanContract.COLUMN_ALBUM_ID),
                    intColumn(cursor, SonyWalkmanContract.COLUMN_TRACK_NO),
                    intColumn(cursor, SonyWalkmanContract.COLUMN_YEAR),
                    intColumn(cursor, SonyWalkmanContract.COLUMN_DURATION),
                    intColumn(cursor, SonyWalkmanContract.COLUMN_SAMPLE_RATE),
                    intColumn(cursor, SonyWalkmanContract.COLUMN_BITS_PER_SAMPLE),
                    intColumn(cursor, SonyWalkmanContract.COLUMN_AUDIO_BITRATE),
                    intColumn(cursor, SonyWalkmanContract.COLUMN_AUDIO_CODEC),
                    stringColumn(cursor, SonyWalkmanContract.COLUMN_MIME_TYPE),
                    intColumn(cursor, SonyWalkmanContract.COLUMN_NUMBER_OF_CHANNELS));
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private static String stringColumn(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) {
            return null;
        }
        String value = cursor.getString(index);
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() == 0 ? null : trimmed;
    }

    private static int intColumn(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? -1 : cursor.getInt(index);
    }

    private static long longColumn(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        return index < 0 || cursor.isNull(index) ? -1 : cursor.getLong(index);
    }

    private void scheduleRebind() {
        Log.w(TAG, "media service died; rebinding");
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!started) {
                    return;
                }
                if (bound) {
                    try {
                        context.unbindService(connection);
                    } catch (IllegalArgumentException ignored) {
                        // Already released with the dead process.
                    }
                    bound = false;
                }
                bindMediaService();
            }
        });
    }

    private void logOnce(String message, Exception exception) {
        if (loggedFailure) {
            return;
        }
        loggedFailure = true;
        Log.w(TAG, message, exception);
    }
}
