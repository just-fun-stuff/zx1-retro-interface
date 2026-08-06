/*
 * ZX1 Retro Interface
 * Library data layer (C1): reads Sony's Genie media database into an
 * immutable in-memory snapshot, cached on disk so the browser opens at
 * iPod speed. Licensed under GPLv2; see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The launcher's own library data, built from Sony's Genie table and kept
 * two ways: a process-wide in-memory {@link Library} snapshot (home
 * pre-warms it, so opening the browser is instant) and a small versioned
 * binary cache in app-internal storage that survives process restarts.
 *
 * Freshness: Sony rescans its database on every power-on and USB-storage
 * disconnect, and the provider answers directory queries with nothing
 * while a scan runs (proven live 2026-07-19). So the cache is validated
 * against a cheap id-only fingerprint - and an unreadable or empty
 * fingerprint never invalidates anything: a rebuild happens only when the
 * provider answers again with different content.
 *
 * All provider access walks the query-shape ladder (full projection,
 * proven columns, null projection, appended-id row walk) with each rung
 * logged, because the working shape could only be named on the device.
 * The 2026-07-19 run answered: the full projection works, playlists are
 * readable as {@code [_id, name]}, and {@code title_sortstr} is populated
 * on every row.
 */
final class LibraryStore {

    private static final String TAG = "ZX1Library";
    private static final Charset UTF8 = Charset.forName("UTF-8");
    private static final String CACHE_FILE = "library.cache";
    private static final int CACHE_MAGIC = 0x5A583143; // "ZX1C"
    private static final int CACHE_VERSION = 1;

    /** One Genie media row, lean projection. Unreadable fields null / -1. */
    static final class Track {
        final long id;
        final String title;
        final String artist;
        final String album;
        final long artistId;
        final long albumId;
        final int trackNo;
        final int durationMs;
        final String mimeType;
        final String titleSort;
        final String artistSort;
        final String albumSort;
        final int sampleRate;
        final int bitsPerSample;
        /** BINARY-collation key on the chosen songs sort column. */
        byte[] songKey;

        Track(long id, String title, String artist, String album,
              long artistId, long albumId, int trackNo, int durationMs,
              String mimeType, String titleSort, String artistSort, String albumSort,
              int sampleRate, int bitsPerSample) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.artistId = artistId;
            this.albumId = albumId;
            this.trackNo = trackNo;
            this.durationMs = durationMs;
            this.mimeType = mimeType;
            this.titleSort = titleSort;
            this.artistSort = artistSort;
            this.albumSort = albumSort;
            this.sampleRate = sampleRate;
            this.bitsPerSample = bitsPerSample;
        }

        /** Sony's own hi-res rule, validated against the real library. */
        boolean isHiRes() {
            return bitsPerSample > 16 || sampleRate > 48000;
        }
    }

    /** An artist or album bucket grouped from the rows. */
    static final class Group {
        final String key;
        final long id;
        final String name;
        String sub;
        byte[] sortKey;
        int trackCount;
        int albumCount;
        /** Albums: true while every member track is hi-res. */
        boolean hiRes;

        Group(String key, long id, String name) {
            this.key = key;
            this.id = id;
            this.name = name;
        }
    }

    static final class Playlist {
        final long id;
        final String name;

        Playlist(long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    /** One immutable library snapshot; everything the browser renders. */
    static final class Library {
        final List<Track> tracks;
        final List<Track> songs;
        final List<Group> artists;
        final List<Group> albums;
        final HashMap<String, Group> albumsByKey;
        final String songSortColumn;
        final long fingerprint;

        Library(List<Track> tracks, List<Track> songs, List<Group> artists,
                List<Group> albums, HashMap<String, Group> albumsByKey,
                String songSortColumn, long fingerprint) {
            this.tracks = tracks;
            this.songs = songs;
            this.artists = artists;
            this.albums = albums;
            this.albumsByKey = albumsByKey;
            this.songSortColumn = songSortColumn;
            this.fingerprint = fingerprint;
        }
    }

    /** Worker-thread callback while a rebuild reads the provider. */
    interface Progress {
        void onProgress(int done, int total);
    }

    private static Library inMemory;

    private LibraryStore() { }

    static synchronized Library peek() {
        return inMemory;
    }

    private static synchronized void keep(Library library) {
        inMemory = library;
    }

    // ------------------------------------------------------------ lifecycle

    /**
     * Worker thread. Loads the disk cache into memory; null when absent or
     * unreadable. Also called by home to pre-warm the snapshot.
     */
    static Library loadCached(Context context) {
        Library existing = peek();
        if (existing != null) {
            return existing;
        }
        long started = SystemClock.uptimeMillis();
        List<Track> rows = readCacheFile(context);
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        long fingerprint = fold(rows);
        Library library = build(rows, fingerprint);
        keep(library);
        Log.i(TAG, "cache: " + rows.size() + " tracks in "
                + (SystemClock.uptimeMillis() - started) + " ms");
        return library;
    }

    /**
     * Worker thread. Full provider read, grouped and sorted; persists the
     * cache and replaces the in-memory snapshot. null when the provider is
     * unreadable (for example mid-scan) - the caller keeps whatever it has.
     */
    static Library rebuild(Context context, Progress progress) {
        long started = SystemClock.uptimeMillis();
        List<Track> rows = readTracksLadder(context,
                SonyWalkmanContract.GENIE_AUDIO_URI, progress);
        if (rows == null || rows.isEmpty()) {
            Log.i(TAG, "rebuild: provider unreadable or empty; keeping current data");
            return null;
        }
        Library library = build(rows, fold(rows));
        keep(library);
        writeCacheFile(context, rows);
        Log.i(TAG, "rebuild: " + rows.size() + " tracks in "
                + (SystemClock.uptimeMillis() - started) + " ms");
        return library;
    }

    /**
     * Worker thread. Cheap freshness probe: an id-only directory read
     * folded to a fingerprint. Returns 0 when the provider is unreadable
     * or empty - Sony's rescan window - which callers must treat as
     * "unknown", never as "changed".
     */
    static long readFingerprint(Context context) {
        List<Track> rows = readDirectory(context, SonyWalkmanContract.GENIE_AUDIO_URI,
                new String[]{SonyWalkmanContract.COLUMN_ID}, "fingerprint", null);
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        return fold(rows);
    }

    /** Order-insensitive fold over the row ids, plus the count. */
    private static long fold(List<Track> rows) {
        long fingerprint = rows.size();
        for (Track track : rows) {
            fingerprint ^= track.id * 0x9E3779B97F4A7C15L;
        }
        return fingerprint == 0 ? 1 : fingerprint;
    }

    // ------------------------------------------------------------- assembly

    private static Library build(List<Track> tracks, long fingerprint) {
        boolean allHaveSortstr = true;
        for (Track track : tracks) {
            if (track.titleSort == null) {
                allHaveSortstr = false;
                break;
            }
        }
        String songSortColumn = allHaveSortstr
                ? SonyWalkmanContract.COLUMN_TITLE_SORT : SonyWalkmanContract.COLUMN_TITLE;
        for (Track track : tracks) {
            String key = allHaveSortstr ? track.titleSort : track.title;
            track.songKey = key == null ? null : key.getBytes(UTF8);
        }
        List<Track> songs = new ArrayList<Track>(tracks);
        Collections.sort(songs, SONG_ORDER);

        HashMap<String, Group> artistsByKey = new HashMap<String, Group>();
        HashMap<String, HashSet<String>> artistAlbumKeys =
                new HashMap<String, HashSet<String>>();
        HashMap<String, Group> albumsByKey = new HashMap<String, Group>();
        for (Track track : tracks) {
            String artistKey = artistKeyOf(track);
            Group artist = artistsByKey.get(artistKey);
            if (artist == null) {
                artist = new Group(artistKey, track.artistId,
                        track.artist == null ? "UNKNOWN ARTIST" : track.artist);
                artist.sortKey = sortKey(track.artistSort, artist.name);
                artistsByKey.put(artistKey, artist);
                artistAlbumKeys.put(artistKey, new HashSet<String>());
            }
            artist.trackCount++;
            artistAlbumKeys.get(artistKey).add(albumKeyOf(track));

            String albumKey = albumKeyOf(track);
            Group album = albumsByKey.get(albumKey);
            if (album == null) {
                album = new Group(albumKey, track.albumId,
                        track.album == null ? "UNKNOWN ALBUM" : track.album);
                album.sortKey = sortKey(track.albumSort, album.name);
                album.sub = track.artist;
                album.hiRes = true;
                albumsByKey.put(albumKey, album);
            } else if (album.sub != null && track.artist != null
                    && !album.sub.equals(track.artist)) {
                album.sub = "VARIOUS ARTISTS";
            }
            album.trackCount++;
            album.hiRes = album.hiRes && track.isHiRes();
        }
        List<Group> artists = new ArrayList<Group>(artistsByKey.size());
        for (Map.Entry<String, Group> entry : artistsByKey.entrySet()) {
            entry.getValue().albumCount = artistAlbumKeys.get(entry.getKey()).size();
            artists.add(entry.getValue());
        }
        List<Group> albums = new ArrayList<Group>(albumsByKey.values());
        sortGroups(artists);
        sortGroups(albums);
        return new Library(tracks, songs, artists, albums, albumsByKey,
                songSortColumn, fingerprint);
    }

    static String artistKeyOf(Track track) {
        return track.artistId > 0 ? "i" + track.artistId
                : "n" + (track.artist == null ? "" : track.artist.toLowerCase(Locale.US));
    }

    static String albumKeyOf(Track track) {
        return track.albumId > 0 ? "i" + track.albumId
                : "n" + (track.album == null ? "" : track.album.toLowerCase(Locale.US));
    }

    private static byte[] sortKey(String sortstr, String fallback) {
        String key = sortstr != null ? sortstr : fallback;
        return key == null ? null : key.getBytes(UTF8);
    }

    static void sortGroups(List<Group> groups) {
        Collections.sort(groups, new Comparator<Group>() {
            @Override
            public int compare(Group a, Group b) {
                int order = compareNullableKeys(a.sortKey, b.sortKey);
                return order != 0 ? order : a.name.compareTo(b.name);
            }
        });
    }

    static final Comparator<Track> SONG_ORDER = new Comparator<Track>() {
        @Override
        public int compare(Track a, Track b) {
            int order = compareNullableKeys(a.songKey, b.songKey);
            if (order != 0) {
                return order;
            }
            return a.id < b.id ? -1 : (a.id > b.id ? 1 : 0);
        }
    };

    /** SQLite "ORDER BY track": NULL (read as -1) first, then ascending. */
    static final Comparator<Track> ALBUM_ORDER = new Comparator<Track>() {
        @Override
        public int compare(Track a, Track b) {
            if (a.trackNo != b.trackNo) {
                return a.trackNo < b.trackNo ? -1 : 1;
            }
            return a.id < b.id ? -1 : (a.id > b.id ? 1 : 0);
        }
    };

    /** SQLite BINARY collation with NULL-first, over UTF-8 bytes. */
    private static int compareNullableKeys(byte[] a, byte[] b) {
        if (a == null || b == null) {
            if (a != null) {
                return 1;
            }
            return b != null ? -1 : 0;
        }
        int limit = Math.min(a.length, b.length);
        for (int i = 0; i < limit; i++) {
            int difference = (a[i] & 0xFF) - (b[i] & 0xFF);
            if (difference != 0) {
                return difference;
            }
        }
        return a.length - b.length;
    }

    // ------------------------------------------------------------ disk cache

    private static File cacheFile(Context context) {
        return new File(context.getFilesDir(), CACHE_FILE);
    }

    private static List<Track> readCacheFile(Context context) {
        File file = cacheFile(context);
        if (!file.isFile()) {
            return null;
        }
        DataInputStream in = null;
        try {
            in = new DataInputStream(new BufferedInputStream(
                    new FileInputStream(file), 64 * 1024));
            if (in.readInt() != CACHE_MAGIC || in.readInt() != CACHE_VERSION) {
                return null;
            }
            int count = in.readInt();
            if (count < 0 || count > 200000) {
                return null;
            }
            List<Track> rows = new ArrayList<Track>(count);
            for (int i = 0; i < count; i++) {
                long id = in.readLong();
                String title = readNullable(in);
                String artist = readNullable(in);
                String album = readNullable(in);
                String mime = readNullable(in);
                String titleSort = readNullable(in);
                String artistSort = readNullable(in);
                String albumSort = readNullable(in);
                long artistId = in.readLong();
                long albumId = in.readLong();
                int trackNo = in.readInt();
                int durationMs = in.readInt();
                int sampleRate = in.readInt();
                int bits = in.readInt();
                rows.add(new Track(id, title, artist, album, artistId, albumId,
                        trackNo, durationMs, mime, titleSort, artistSort, albumSort,
                        sampleRate, bits));
            }
            return rows;
        } catch (IOException exception) {
            Log.w(TAG, "cache unreadable: " + exception);
            return null;
        } catch (RuntimeException exception) {
            Log.w(TAG, "cache corrupt: " + exception);
            return null;
        } finally {
            closeQuietly(in);
        }
    }

    private static void writeCacheFile(Context context, List<Track> rows) {
        File file = cacheFile(context);
        File temporary = new File(file.getPath() + ".tmp");
        DataOutputStream out = null;
        try {
            out = new DataOutputStream(new BufferedOutputStream(
                    new FileOutputStream(temporary), 64 * 1024));
            out.writeInt(CACHE_MAGIC);
            out.writeInt(CACHE_VERSION);
            out.writeInt(rows.size());
            for (Track track : rows) {
                out.writeLong(track.id);
                writeNullable(out, track.title);
                writeNullable(out, track.artist);
                writeNullable(out, track.album);
                writeNullable(out, track.mimeType);
                writeNullable(out, track.titleSort);
                writeNullable(out, track.artistSort);
                writeNullable(out, track.albumSort);
                out.writeLong(track.artistId);
                out.writeLong(track.albumId);
                out.writeInt(track.trackNo);
                out.writeInt(track.durationMs);
                out.writeInt(track.sampleRate);
                out.writeInt(track.bitsPerSample);
            }
            out.flush();
            closeQuietly(out);
            out = null;
            if (!temporary.renameTo(file)) {
                Log.w(TAG, "cache rename failed");
            }
        } catch (IOException exception) {
            Log.w(TAG, "cache write failed: " + exception);
            temporary.delete();
        } finally {
            closeQuietly(out);
        }
    }

    private static String readNullable(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readUTF() : null;
    }

    private static void writeNullable(DataOutputStream out, String value) throws IOException {
        if (value == null) {
            out.writeBoolean(false);
        } else {
            out.writeBoolean(true);
            out.writeUTF(value);
        }
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // Nothing left to release.
            }
        }
    }

    // ------------------------------------------------- provider query ladder

    /*
     * The v2.7.1 device run proved the full projection works on directory
     * URIs once Sony's scan is idle; the ladder stays because a scan window
     * or another firmware could still need the fallbacks. The winner is
     * remembered per process.
     */
    private static final int MODE_UNKNOWN = 0;
    private static final int MODE_FULL = 1;
    private static final int MODE_PROVEN = 2;
    private static final int MODE_NULL_PROJECTION = 3;
    private static int directoryReadMode = MODE_UNKNOWN;
    /** Projection proven to work on appended-id row reads, probed once. */
    private static String[] walkProjection;

    /** Worker thread. null = unreadable; empty = readable but no rows. */
    static List<Track> readTracksLadder(Context context, Uri uri, Progress progress) {
        int start = directoryReadMode == MODE_UNKNOWN ? MODE_FULL : directoryReadMode;
        List<Track> rows;
        if (start <= MODE_FULL) {
            rows = readDirectory(context, uri,
                    SonyWalkmanContract.GENIE_LIBRARY_PROJECTION, "full", progress);
            if (usable(rows)) {
                directoryReadMode = MODE_FULL;
                return rows;
            }
        }
        if (start <= MODE_PROVEN) {
            rows = readDirectory(context, uri,
                    SonyWalkmanContract.GENIE_LIBRARY_FALLBACK_PROJECTION, "proven", progress);
            if (usable(rows)) {
                directoryReadMode = MODE_PROVEN;
                return rows;
            }
        }
        List<Track> idSource = null;
        if (start <= MODE_NULL_PROJECTION) {
            rows = readDirectory(context, uri, null, "null-projection", null);
            if (usable(rows)) {
                directoryReadMode = MODE_NULL_PROJECTION;
                return rows;
            }
            idSource = rows;
        }
        if (idSource == null || idSource.isEmpty()) {
            idSource = readDirectory(context, uri,
                    new String[]{SonyWalkmanContract.COLUMN_ID}, "id-only", null);
        }
        if (idSource == null || idSource.isEmpty()) {
            return idSource;
        }
        return walkRowsById(context, idSource, progress);
    }

    /** One directory query; null on a null cursor or any exception. */
    private static List<Track> readDirectory(Context context, Uri uri, String[] projection,
                                             String label, Progress progress) {
        try {
            Cursor cursor = context.getContentResolver().query(uri, projection,
                    null, null, null);
            if (cursor == null) {
                Log.i(TAG, "read " + label + " " + uri.getLastPathSegment() + ": null cursor");
                return null;
            }
            List<Track> rows;
            try {
                rows = extractRows(cursor, progress);
            } finally {
                cursor.close();
            }
            Log.i(TAG, "read " + label + " " + uri.getLastPathSegment() + ": "
                    + rows.size() + " rows, " + titledCount(rows) + " with titles");
            return rows;
        } catch (RuntimeException exception) {
            Log.i(TAG, "read " + label + " " + uri.getLastPathSegment()
                    + " failed: " + exception);
            return null;
        }
    }

    /**
     * The last rung: fetch every enumerated id through the appended-id URI
     * (single indexed lookups, the same query the ~1 s now-playing poll
     * runs) - a few seconds for a full library, once, off the main thread,
     * behind the loading overlay.
     */
    private static List<Track> walkRowsById(Context context, List<Track> idSource,
                                            Progress progress) {
        long started = SystemClock.uptimeMillis();
        if (walkProjection == null) {
            Track probe = readRowById(context, idSource.get(0).id,
                    SonyWalkmanContract.GENIE_LIBRARY_PROJECTION);
            if (probe != null && probe.title != null) {
                walkProjection = SonyWalkmanContract.GENIE_LIBRARY_PROJECTION;
            } else {
                probe = readRowById(context, idSource.get(0).id,
                        SonyWalkmanContract.GENIE_LIBRARY_FALLBACK_PROJECTION);
                if (probe != null && probe.title != null) {
                    walkProjection = SonyWalkmanContract.GENIE_LIBRARY_FALLBACK_PROJECTION;
                } else {
                    Log.w(TAG, "id-walk probe failed on row " + idSource.get(0).id);
                    return null;
                }
            }
        }
        List<Track> rows = new ArrayList<Track>(idSource.size());
        int misses = 0;
        for (int i = 0; i < idSource.size(); i++) {
            Track row = readRowById(context, idSource.get(i).id, walkProjection);
            if (row != null) {
                rows.add(row);
            } else {
                misses++;
            }
            if (progress != null && (i % 50 == 0 || i == idSource.size() - 1)) {
                progress.onProgress(i + 1, idSource.size());
            }
        }
        Log.i(TAG, "id-walk: " + rows.size() + " rows (" + misses + " misses) in "
                + (SystemClock.uptimeMillis() - started) + " ms");
        return rows;
    }

    private static Track readRowById(Context context, long trackId, String[] projection) {
        try {
            Cursor cursor = context.getContentResolver().query(
                    ContentUris.withAppendedId(SonyWalkmanContract.GENIE_AUDIO_URI, trackId),
                    projection, null, null, null);
            if (cursor == null) {
                return null;
            }
            List<Track> rows;
            try {
                rows = extractRows(cursor, null);
            } finally {
                cursor.close();
            }
            return rows.isEmpty() ? null : rows.get(0);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private static boolean usable(List<Track> rows) {
        return rows != null && !rows.isEmpty() && titledCount(rows) > 0;
    }

    private static int titledCount(List<Track> rows) {
        int titled = 0;
        for (Track row : rows) {
            if (row.title != null) {
                titled++;
            }
        }
        return titled;
    }

    /** Reads every row the cursor holds; does not close the cursor. */
    private static List<Track> extractRows(Cursor cursor, Progress progress) {
        int idColumn = cursor.getColumnIndex(SonyWalkmanContract.COLUMN_ID);
        int titleColumn = cursor.getColumnIndex(SonyWalkmanContract.COLUMN_TITLE);
        int artistColumn = cursor.getColumnIndex(SonyWalkmanContract.COLUMN_ARTIST);
        int albumColumn = cursor.getColumnIndex(SonyWalkmanContract.COLUMN_ALBUM);
        int artistIdColumn = cursor.getColumnIndex(SonyWalkmanContract.COLUMN_ARTIST_ID);
        int albumIdColumn = cursor.getColumnIndex(SonyWalkmanContract.COLUMN_ALBUM_ID);
        int trackNoColumn = cursor.getColumnIndex(SonyWalkmanContract.COLUMN_TRACK_NO);
        int durationColumn = cursor.getColumnIndex(SonyWalkmanContract.COLUMN_DURATION);
        int mimeColumn = cursor.getColumnIndex(SonyWalkmanContract.COLUMN_MIME_TYPE);
        int titleSortColumn = cursor.getColumnIndex(SonyWalkmanContract.COLUMN_TITLE_SORT);
        int artistSortColumn = cursor.getColumnIndex(SonyWalkmanContract.COLUMN_ARTIST_SORT);
        int albumSortColumn = cursor.getColumnIndex(SonyWalkmanContract.COLUMN_ALBUM_SORT);
        int rateColumn = cursor.getColumnIndex(SonyWalkmanContract.COLUMN_SAMPLE_RATE);
        int bitsColumn = cursor.getColumnIndex(SonyWalkmanContract.COLUMN_BITS_PER_SAMPLE);
        int total = cursor.getCount();
        List<Track> rows = new ArrayList<Track>(total);
        while (cursor.moveToNext()) {
            long id = longAt(cursor, idColumn);
            if (id <= 0) {
                continue;
            }
            rows.add(new Track(id,
                    stringAt(cursor, titleColumn),
                    stringAt(cursor, artistColumn),
                    stringAt(cursor, albumColumn),
                    longAt(cursor, artistIdColumn),
                    longAt(cursor, albumIdColumn),
                    intAt(cursor, trackNoColumn),
                    intAt(cursor, durationColumn),
                    stringAt(cursor, mimeColumn),
                    stringAt(cursor, titleSortColumn),
                    stringAt(cursor, artistSortColumn),
                    stringAt(cursor, albumSortColumn),
                    intAt(cursor, rateColumn),
                    intAt(cursor, bitsColumn)));
            if (progress != null && (rows.size() % 200 == 0 || rows.size() == total)) {
                progress.onProgress(rows.size(), total);
            }
        }
        return rows;
    }

    // -------------------------------------------------------------- playlists

    /** Worker thread. null = the playlist table refused an unprivileged read. */
    static List<Playlist> readPlaylists(Context context) {
        List<Playlist> rows = readPlaylistDirectory(context,
                SonyWalkmanContract.GENIE_PLAYLISTS_PROJECTION, "named");
        if (rows == null) {
            rows = readPlaylistDirectory(context, null, "null-projection");
        }
        return rows;
    }

    private static List<Playlist> readPlaylistDirectory(Context context,
                                                        String[] projection, String label) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    SonyWalkmanContract.GENIE_PLAYLISTS_URI, projection, null, null, null);
            if (cursor == null) {
                Log.i(TAG, "playlists " + label + ": null cursor");
                return null;
            }
            String[] names = cursor.getColumnNames();
            int idColumn = cursor.getColumnIndex(SonyWalkmanContract.COLUMN_ID);
            int nameColumn = cursor.getColumnIndex(SonyWalkmanContract.COLUMN_PLAYLIST_NAME);
            if (nameColumn < 0) {
                // The name column is only a guess; take any name-ish column.
                for (int i = 0; i < names.length; i++) {
                    String lower = names[i].toLowerCase(Locale.US);
                    if (lower.contains("name") || lower.equals("title")) {
                        nameColumn = i;
                        break;
                    }
                }
            }
            Log.i(TAG, "playlists " + label + ": " + cursor.getCount() + " rows, columns "
                    + Arrays.toString(names));
            List<Playlist> rows = new ArrayList<Playlist>();
            while (cursor.moveToNext()) {
                long id = longAt(cursor, idColumn);
                if (id > 0) {
                    rows.add(new Playlist(id, stringAt(cursor, nameColumn)));
                }
            }
            return rows;
        } catch (RuntimeException exception) {
            Log.i(TAG, "playlists " + label + " unreadable: " + exception);
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /** Worker thread. Member rows in provider order; null when unreadable. */
    static List<Track> readPlaylistMembers(Context context, long playlistId) {
        return readTracksLadder(context,
                SonyWalkmanContract.geniePlaylistTracksUri(playlistId), null);
    }

    // ------------------------------------------------------------ cursor bits

    private static String stringAt(Cursor cursor, int index) {
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

    private static int intAt(Cursor cursor, int index) {
        return index < 0 || cursor.isNull(index) ? -1 : cursor.getInt(index);
    }

    private static long longAt(Cursor cursor, int index) {
        return index < 0 || cursor.isNull(index) ? -1 : cursor.getLong(index);
    }
}
