/*
 * ZX1 Retro Interface
 * Queue-next over a launcher-owned platform playlist: materialize the
 * player's current queue, append one track, reopen. Licensed under GPLv2;
 * see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * ADD TO NOW PLAYING: appends one track to the end of the queue the
 * firmware player is on (several adds line up first-added-first - the
 * playlist-building order). The engine's queue model is declarative (a
 * content query it re-runs itself), so an arbitrary queue order needs a
 * backing table that can hold one - and that is the launcher's reserved
 * platform playlist ({@link PlaylistStore#QUEUE_PLAYLIST_NAME}), whose
 * members uri orders by the single column {@code play_order} - the
 * engine-accepted descriptor shape - and whose {@code _data} the platform
 * provider serves to this uid (Genie's never would; findings "ROOT
 * CAUSE"). Sony's own UI reads only Genie playlists, so this one never
 * clutters their screens.
 *
 * The flow, all logged under {@code ZX1Queue}:
 * <pre>
 * 1. resolve the added track's platform id (strict join, refuses ambiguity)
 * 2. one read-only look at the player through {@link QueueOracle}: current
 *    descriptor plus state/track/playhead
 * 3. if the current queue already is the reserved playlist: the HITCHLESS
 *    path - append one member row past the tail and verify by read-only
 *    polling that the engine integrated it; playback is never touched
 * 4. otherwise (or when the engine refuses the insert): materialize the
 *    current queue's platform ids in queue order, rewrite the playlist
 *    whole with the track appended at the end, and reopen through the
 *    proven registered-client handoff cued at the current track, seeking
 *    back to the playhead (and re-pausing when paused) - the reopen
 *    stalls the pipeline for a moment, one audible hitch
 * </pre>
 *
 * Honesty rules: any failure before the reopen leaves the player
 * completely untouched (the in-place row is swept by the fallback's
 * normalizing rewrite, never duplicated); the reopen never falls back to
 * the intent tiers (PlayerHandoff direct-queue mode). A queue whose order
 * cannot be reproduced exactly is refused, never approximated - except
 * that rows the strict Genie→platform join cannot carry are dropped and
 * counted in the log (the platform queue physically cannot contain them).
 *
 * Live-run answers (2026-07-21, firmware-findings "Queue-next"): the
 * engine accepts the members uri + play_order descriptor; PlayInfo's
 * trackId reports the platform AUDIO id from it; seek txn 9 lands exactly;
 * the engine watches the backing table - a full member rewrite made it
 * drop its list descriptor mid-play, but it INTEGRATES a pure single-row
 * insert live, which is what makes the in-place path hitchless (all
 * verified on the device).
 */
final class NowPlayingQueue {

    interface Listener {
        /** Main thread, exactly once. True when the queue really changed. */
        void onResult(boolean queued);
    }

    private static final String TAG = "ZX1Queue";

    /**
     * A Sony-started Genie queue is rebuilt with one strict per-track
     * provider join each; beyond this many rows that is no longer an
     * instant action, so the request refuses honestly instead.
     */
    private static final int GENIE_REBUILD_CAP = 200;

    private final Context context;
    /** The library row, when the caller had one; null for a platform id. */
    private final LibraryStore.Track track;
    /** The added track's platform audio id; 0 until the track resolves. */
    private long addedAudioId;
    private final String title;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    /**
     * The read-only player link, held for the whole flow (released in
     * finish) so the in-place verify keeps reading the player the look saw.
     */
    private final QueueOracle oracle;

    private HandlerThread workerThread;
    private Handler worker;
    private boolean resultDelivered;

    private NowPlayingQueue(Context context, LibraryStore.Track track,
                            long audioId, String title, Listener listener) {
        this.context = context.getApplicationContext();
        this.track = track;
        this.addedAudioId = audioId;
        this.title = title;
        this.listener = listener;
        this.oracle = new QueueOracle(context);
    }

    /** Main thread. Fire-and-report; the instance cleans itself up. */
    static void queueNext(Context context, LibraryStore.Track track, Listener listener) {
        new NowPlayingQueue(context, track, 0, track.title, listener).start();
    }

    /**
     * Main thread. The same action for a row that already knows its
     * platform audio id - a launcher playlist's member, which lives in the
     * platform table to begin with and needs no join at all.
     */
    static void queueNextPlatform(Context context, long audioId, String title,
                                  Listener listener) {
        new NowPlayingQueue(context, null, audioId, title, listener).start();
    }

    private void start() {
        workerThread = new HandlerThread("zx1-queue-next");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        worker.post(new Runnable() {
            @Override
            public void run() {
                try {
                    runQueueNext();
                } catch (RuntimeException exception) {
                    Log.w(TAG, "queue-next failed for " + label(), exception);
                    finish(false);
                }
            }
        });
    }

    private String label() {
        return "queue-next " + (track != null ? track.id : "audio " + addedAudioId)
                + " (" + title + ")";
    }

    // ------------------------------------------------------------- the flow

    /** Worker thread. */
    private void runQueueNext() {
        long started = SystemClock.uptimeMillis();
        if (!resolveAddedTrack()) {
            finish(false);
            return;
        }

        QueueOracle.Look look = oracle.open(label());
        if (look == null) {
            // No live player at all (typically a fresh boot where the
            // player app has never run): there is nothing to queue behind,
            // and the binder cannot open a list on a player that is not
            // registered. Honest refusal - a normal tap plays the track.
            finish(false);
            return;
        }

        long playlistId = PlaylistStore.ensureQueuePlaylist(context);
        if (playlistId <= 0) {
            finish(false);
            return;
        }
        Uri members = PlaylistStore.membersUri(playlistId);

        NewQueue plan = null;
        if (look.isLive() && PlaylistStore.isMembersUriOf(look.listUri, playlistId)) {
            // The current queue already is our playlist: try the hitchless
            // in-place append first - one member row past the tail, then
            // read-only verification that the engine took it. Reopening
            // (with its audible hitch) is only the fallback.
            InPlaceResult inPlace = queueNextInPlace(playlistId, look.snapshot);
            if (inPlace != null) {
                if (inPlace.done) {
                    Log.i(TAG, "in-place insert integrated in "
                            + (SystemClock.uptimeMillis() - started)
                            + " ms for " + label());
                    finish(true);
                    return;
                }
                plan = inPlace.fallback;
            }
        }
        if (plan == null) {
            plan = planQueue(look);
        }
        if (plan == null) {
            finish(false);
            return;
        }

        if (!PlaylistStore.writeMembers(context, playlistId, plan.order)) {
            finish(false);
            return;
        }
        Log.i(TAG, "playlist " + playlistId + " rewritten: " + plan.order.size()
                + " members (appended platform id " + addedAudioId
                + ", cue index " + plan.startIndex + ", dropped " + plan.dropped
                + ") in " + (SystemClock.uptimeMillis() - started)
                + " ms for " + label());

        final PlayerHandoff.Request request = PlayerHandoff.Request.directQueue(
                members, null, PlaylistStore.PLAY_ORDER, plan.startIndex,
                plan.seekToMs, plan.restorePause,
                track != null ? track.id : 0, label());
        // PlayerHandoff's entry is a main-thread call; it runs on its own
        // worker and reports back on main.
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                PlayerHandoff.play(context, request, new PlayerHandoff.Listener() {
                    @Override
                    public void onHandoff(int tier) {
                        finishOnMain(tier == PlayerHandoff.TIER_BINDER_QUEUE);
                    }
                });
            }
        });
    }

    /**
     * Worker thread. Fills in the added track's platform audio id when the
     * caller handed a library row instead. False refuses honestly: the
     * platform queue can only hold rows the strict join places.
     */
    private boolean resolveAddedTrack() {
        if (addedAudioId > 0) {
            return true;
        }
        if (track == null || track.title == null) {
            Log.i(TAG, "track has no title; the strict platform join cannot carry it");
            return false;
        }
        AlbumArtResolver.PlatformRow added = AlbumArtResolver.findPlatformRow(
                context, track.title, track.album, track.durationMs, track.trackNo);
        if (added == null || added.trackId <= 0) {
            Log.i(TAG, "no platform row for " + label() + "; cannot queue");
            return false;
        }
        addedAudioId = added.trackId;
        return true;
    }

    // --------------------------------------------------- the in-place insert

    /** Outcome of the in-place attempt; null means fall to the generic plan. */
    private static final class InPlaceResult {
        final boolean done;
        final NewQueue fallback;

        InPlaceResult(boolean done, NewQueue fallback) {
            this.done = done;
            this.fallback = fallback;
        }
    }

    /**
     * Worker thread. The hitchless path, proven live 2026-07-21: the engine
     * watches its open list's backing table and integrates a pure row
     * insert without interrupting playback. ADD TO NOW PLAYING appends at
     * the END of the queue (several adds in a row line up first-added-
     * first, the playlist-building order), so the insert touches nothing
     * the engine is holding, and the oracle's own read-only calls confirm
     * the engine's live track count picked it up. Anything else hands a
     * fully prepared fallback plan (fresh playhead values) to the proven
     * rewrite+reopen path. Null only when nothing was written yet, so the
     * generic path is still safe to run.
     */
    private InPlaceResult queueNextInPlace(long playlistId,
                                           MediaServiceClient.Snapshot snapshot) {
        PlaylistStore.Rows current = PlaylistStore.readMemberRows(context, playlistId);
        if (current == null || current.size() == 0) {
            return null;
        }
        int expectedCount = PlaylistStore.append(context, playlistId, current, addedAudioId);
        if (expectedCount < 0) {
            Log.w(TAG, "in-place insert refused; using the rewrite path");
            return null;
        }
        if (oracle.awaitTrackCount(expectedCount)) {
            return new InPlaceResult(true, null);
        }
        // The row is in the table but the engine did not take it live; a
        // fresh look re-anchors the playhead (time passed) and the rewrite
        // normalizes the table - the extra row is swept, never duplicated.
        Log.i(TAG, "engine did not integrate the insert; falling back to reopen");
        return new InPlaceResult(false,
                fallbackPlan(current.audioIds, snapshot));
    }

    /**
     * Worker thread. The reopen plan after a failed in-place attempt: the
     * pre-insert member order with the track appended, cued at wherever the
     * playhead is NOW (it may have advanced during the verify wait), with
     * fresh seek/pause values from the same read.
     */
    private NewQueue fallbackPlan(List<Long> audioIds,
                                  MediaServiceClient.Snapshot stale) {
        MediaServiceClient.Snapshot fresh = oracle.currentInfo();
        MediaServiceClient.Snapshot anchor =
                fresh != null && fresh.trackId > 0 ? fresh : stale;
        int position = QueueOracle.positionOf(audioIds, anchor.trackId,
                anchor.trackIndexInList);
        if (position < 0) {
            position = 0;
        }
        List<Long> order = new ArrayList<Long>(audioIds.size() + 1);
        order.addAll(audioIds);
        order.add(addedAudioId);
        return new NewQueue(order, position, QueueOracle.seekTarget(anchor),
                anchor.state == SonyWalkmanContract.PLAY_STATE_PAUSE, 0);
    }

    // ------------------------------------------------------- queue planning

    /** The rewritten queue: platform ids in order plus the reopen params. */
    private static final class NewQueue {
        final List<Long> order;
        final int startIndex;
        final int seekToMs;
        final boolean restorePause;
        final int dropped;

        NewQueue(List<Long> order, int startIndex, int seekToMs,
                 boolean restorePause, int dropped) {
            this.order = order;
            this.startIndex = startIndex;
            this.seekToMs = seekToMs;
            this.restorePause = restorePause;
            this.dropped = dropped;
        }
    }

    /**
     * Worker thread. Builds the new member order. An idle player (CLOSE, or
     * no readable track) starts a fresh one-track queue; a live one gets its
     * exact current order reproduced with the new track appended at the end
     * - or an honest null when that order cannot be reproduced, so a failure
     * never stomps a playing queue.
     */
    private NewQueue planQueue(QueueOracle.Look look) {
        if (!look.isLive()) {
            List<Long> order = new ArrayList<Long>(1);
            order.add(addedAudioId);
            Log.i(TAG, "player idle; starting a fresh one-track queue");
            return new NewQueue(order, 0, 0, false, 0);
        }
        if (look.listUri == null) {
            Log.w(TAG, "live playback but no readable list descriptor; refusing");
            return null;
        }
        MediaServiceClient.Snapshot snapshot = look.snapshot;
        Materialized current = materialize(look, snapshot.trackId,
                snapshot.trackIndexInList);
        if (current == null) {
            return null;
        }
        List<Long> order = new ArrayList<Long>(current.platformIds.size() + 1);
        order.addAll(current.platformIds);
        order.add(addedAudioId);
        return new NewQueue(order, current.position, QueueOracle.seekTarget(snapshot),
                snapshot.state == SonyWalkmanContract.PLAY_STATE_PAUSE,
                current.dropped);
    }

    /** The current queue as platform ids, with the playing row located. */
    private static final class Materialized {
        final List<Long> platformIds;
        final int position;
        final int dropped;

        Materialized(List<Long> platformIds, int position, int dropped) {
            this.platformIds = platformIds;
            this.position = position;
            this.dropped = dropped;
        }
    }

    /**
     * Worker thread. Reproduces the player's current queue order. The
     * platform forms re-run the descriptor's own query (same provider, same
     * SQL, same tie order); Genie forms read the path-scoped uri in provider
     * order - or ride the snapshot's title_sortstr order for the all-songs
     * table - then join each row to its platform id with the strict
     * discipline. {@code indexHint} is PlayInfo's live queue index: when the
     * reported id sits at that index it wins, which keeps the reopen's cue
     * index right even when the same track appears twice. Null refuses:
     * unknown shapes, a current track that cannot be located or carried, or
     * a Genie rebuild past the cap.
     */
    private Materialized materialize(QueueOracle.Look look, long currentTrackId,
                                     int indexHint) {
        Uri uri = look.listUri;
        String authority = uri.getAuthority();
        String path = uri.getPath() == null ? "" : uri.getPath();
        if (MediaStore.AUTHORITY.equals(authority)) {
            if (path.contains("/audio/playlists/") && path.endsWith("/members")) {
                return materializeMembers(uri, currentTrackId, indexHint);
            }
            if (path.matches(".*/audio/media/\\d+")) {
                // The one-track form (playlist-member taps, scan-and-play).
                List<Long> ids = new ArrayList<Long>(1);
                ids.add(ContentUris.parseId(uri));
                return new Materialized(ids, 0, 0);
            }
            if (path.endsWith("/audio/media")) {
                return materializePlatformQuery(uri, look.listSelection,
                        look.listSortOrder, currentTrackId, indexHint);
            }
            Log.w(TAG, "unrecognized platform list shape " + uri + "; refusing");
            return null;
        }
        if (SonyWalkmanContract.GENIE_AUDIO_URI.getAuthority().equals(authority)) {
            return materializeGenie(look, currentTrackId, indexHint);
        }
        Log.w(TAG, "unrecognized list authority " + uri + "; refusing");
        return null;
    }

    /**
     * A members list as the current queue: audio ids by play_order. The
     * engine reports platform AUDIO ids from a members queue (proven live
     * 2026-07-21), so that space is matched first; the member-row space
     * stays as the safety second.
     */
    private Materialized materializeMembers(Uri membersUri, long currentTrackId,
                                            int indexHint) {
        long playlistId = playlistIdOf(membersUri);
        PlaylistStore.Rows members = playlistId <= 0 ? null
                : PlaylistStore.readMemberRows(context, playlistId);
        if (members == null) {
            Log.w(TAG, "members unreadable: " + membersUri);
            return null;
        }
        int position = QueueOracle.positionOf(members.audioIds, currentTrackId, indexHint);
        if (position < 0) {
            position = QueueOracle.positionOf(members.memberIds, currentTrackId, indexHint);
        }
        if (position < 0) {
            Log.w(TAG, "current track " + currentTrackId
                    + " not in the members list; refusing");
            return null;
        }
        return new Materialized(members.audioIds, position, 0);
    }

    /** The playlist id out of a .../playlists/&lt;id&gt;/members uri; 0 if absent. */
    private static long playlistIdOf(Uri membersUri) {
        List<String> segments = membersUri.getPathSegments();
        if (segments.size() < 2) {
            return 0;
        }
        try {
            return Long.parseLong(segments.get(segments.size() - 2));
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    /** Launcher-started album/songs queues: the descriptor's query, re-run. */
    private Materialized materializePlatformQuery(Uri uri, String selection,
                                                  String sortOrder, long currentTrackId,
                                                  int indexHint) {
        List<Long> ids = new ArrayList<Long>();
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri,
                    new String[]{MediaStore.Audio.Media._ID},
                    selection, null, sortOrder);
            if (cursor == null) {
                Log.w(TAG, "platform queue query unreadable: " + uri);
                return null;
            }
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(0));
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "platform queue query failed: " + uri, exception);
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        int position = QueueOracle.positionOf(ids, currentTrackId, indexHint);
        if (position < 0) {
            Log.w(TAG, "current track " + currentTrackId
                    + " not in the re-run queue (" + ids.size() + " rows); refusing");
            return null;
        }
        return new Materialized(ids, position, 0);
    }

    /**
     * Sony-started queues carry Genie ids. Path-scoped uris (album,
     * playlist) read back in provider order - the descriptors Sony's own UI
     * submits carry no sort for them; the flat all-songs table rides the
     * library snapshot's title_sortstr order (Sony's own all-songs sort).
     * Every row then joins to its platform id; rows the strict join cannot
     * carry are dropped and counted - except the playing one, which must
     * survive or the whole request refuses.
     */
    private Materialized materializeGenie(QueueOracle.Look look, long currentTrackId,
                                          int indexHint) {
        Uri uri = look.listUri;
        String path = uri.getPath() == null ? "" : uri.getPath();
        LibraryStore.Library library = LibraryStore.peek();
        if (library == null) {
            Log.w(TAG, "no library snapshot; cannot rebuild a Genie queue");
            return null;
        }
        List<Long> genieIds;
        if (uri.toString().equals(SonyWalkmanContract.GENIE_AUDIO_URI.toString())) {
            if (look.listSortOrder != null
                    && !SonyWalkmanContract.COLUMN_TITLE_SORT.equals(look.listSortOrder)) {
                Log.w(TAG, "genie all-songs queue with sort " + look.listSortOrder
                        + "; order not reproducible - refusing");
                return null;
            }
            genieIds = new ArrayList<Long>(library.songs.size());
            for (LibraryStore.Track song : library.songs) {
                genieIds.add(song.id);
            }
        } else if ((path.contains("/audio/albums/") || path.contains("/audio/playlists/"))
                && path.endsWith("/media") && look.listSortOrder == null) {
            genieIds = readGenieIds(uri);
            if (genieIds == null) {
                return null;
            }
        } else {
            Log.w(TAG, "unrecognized genie list shape " + uri
                    + " sort=" + look.listSortOrder + "; refusing");
            return null;
        }
        if (genieIds.size() > GENIE_REBUILD_CAP) {
            Log.w(TAG, "genie queue has " + genieIds.size()
                    + " rows (cap " + GENIE_REBUILD_CAP + "); refusing");
            return null;
        }
        int geniePosition = QueueOracle.positionOf(genieIds, currentTrackId, indexHint);
        if (geniePosition < 0) {
            Log.w(TAG, "current genie track " + currentTrackId
                    + " not in the list read (" + genieIds.size() + " rows); refusing");
            return null;
        }
        HashMap<Long, LibraryStore.Track> byId =
                new HashMap<Long, LibraryStore.Track>(library.tracks.size() * 2);
        for (LibraryStore.Track row : library.tracks) {
            byId.put(row.id, row);
        }
        List<Long> platformIds = new ArrayList<Long>(genieIds.size());
        int position = -1;
        int dropped = 0;
        for (int i = 0; i < genieIds.size(); i++) {
            LibraryStore.Track row = byId.get(genieIds.get(i));
            AlbumArtResolver.PlatformRow platform = row == null || row.title == null
                    ? null
                    : AlbumArtResolver.findPlatformRow(context, row.title, row.album,
                            row.durationMs, row.trackNo);
            if (platform == null || platform.trackId <= 0) {
                if (i == geniePosition) {
                    Log.w(TAG, "playing genie row " + currentTrackId
                            + " has no platform match; refusing");
                    return null;
                }
                dropped++;
                continue;
            }
            platformIds.add(platform.trackId);
            if (i == geniePosition) {
                position = platformIds.size() - 1;
            }
        }
        return new Materialized(platformIds, position, dropped);
    }

    /** A Genie directory read: ids only, provider order, null on failure. */
    private List<Long> readGenieIds(Uri uri) {
        List<Long> ids = new ArrayList<Long>();
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri,
                    new String[]{SonyWalkmanContract.COLUMN_ID}, null, null, null);
            if (cursor == null) {
                Log.w(TAG, "genie list unreadable: " + uri);
                return null;
            }
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(0));
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "genie list read failed: " + uri, exception);
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        if (ids.isEmpty()) {
            // Sony's rescan window reads empty; that is unknown, not a queue.
            Log.w(TAG, "genie list read empty (rescan window?): " + uri);
            return null;
        }
        return ids;
    }

    // ------------------------------------------------------------- lifecycle

    /** Worker thread. */
    private void finish(final boolean queued) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                finishOnMain(queued);
            }
        });
    }

    /** Main thread. Reports once, releases the read bind, quits the worker. */
    private void finishOnMain(boolean queued) {
        if (resultDelivered) {
            return;
        }
        resultDelivered = true;
        oracle.close();
        HandlerThread thread = workerThread;
        workerThread = null;
        worker = null;
        if (thread != null) {
            thread.quit();
        }
        Log.i(TAG, (queued ? "queued next: " : "queue-next failed: ") + label());
        listener.onResult(queued);
    }
}
