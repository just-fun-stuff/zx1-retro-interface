/*
 * ZX1 Retro Interface
 * The disc numbers behind one Genie album, for the albums that are really
 * several discs. Licensed under GPLv2; see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import android.content.Context;
import android.database.Cursor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/**
 * Which disc each track of one Genie album sits on - resolved from stored
 * numbers, or not resolved at all.
 *
 * Genie buckets albums by the album-name string and, for most rips, keeps
 * the bare tag track number. A box set whose discs spell the album name
 * identically therefore arrives as one album numbered 01, 01, 02, 02 …, and
 * sorting that by the bare number interleaves the discs - an order that is
 * neither the tags' nor the one the player will run (the queue is the
 * platform album, {@code ORDER BY track} over the scanner's
 * {@code disc*1000+track}, which is disc-major; findings "Album queues").
 *
 * The disc number is a column Genie usually does not have, so it is read
 * from whoever really stored it:
 *
 * <ol>
 *   <li>Genie's own {@code track}, when that rip encoded the disc into it
 *       ({@code >= 1000}) - free, no provider read at all;</li>
 *   <li>otherwise the platform album, but only for an album that actually
 *       repeats a bare track number - the one symptom a merge can show.
 *       Two queries for the whole album, never one per row: the shared
 *       Genie→platform join resolves a representative row to its
 *       {@code album_id}, then one indexed sweep of that album is joined
 *       in memory against the rows on screen.</li>
 * </ol>
 *
 * Anything short of "every row resolved, and more than one disc among
 * them" returns null, and the album level renders exactly as it did
 * before. Discs are never inferred from anything the providers did not
 * store - a rowid ordering that happens to group the files is a guess, and
 * the browser does not guess.
 */
final class AlbumDiscMap {

    private static final String TAG = "ZX1Library";

    /** Genie's track column carries the disc above this, bare below it. */
    private static final int DISC_MULTIPLIER = 1000;
    /** Representative rows tried before giving up on the platform album. */
    private static final int PLATFORM_PROBES = 3;
    /**
     * Rows above this are not a box set but a catch-all bucket (untagged
     * files collapse into one "UNKNOWN ALBUM"); never sweep for those.
     */
    private static final int MAX_ALBUM_ROWS = 300;

    private final HashMap<Long, Integer> discByTrackId;
    private final int discCount;

    private AlbumDiscMap(HashMap<Long, Integer> discByTrackId, int discCount) {
        this.discByTrackId = discByTrackId;
        this.discCount = discCount;
    }

    /**
     * The map for one Genie album's rows, or null when the album is a
     * single disc, has nothing to resolve from, or does not resolve
     * whole. Reaches the provider only for an album whose bare track
     * numbers repeat; call it on the thread that builds the level.
     */
    static AlbumDiscMap resolve(Context context, String albumName,
                                List<LibraryStore.Track> rows) {
        if (rows == null || rows.size() < 2) {
            return null;
        }
        AlbumDiscMap fromGenie = fromGenieTrackNumbers(rows);
        if (fromGenie != null) {
            return fromGenie;
        }
        if (rows.size() > MAX_ALBUM_ROWS || !repeatsATrackNumber(rows)) {
            return null;
        }
        StringBuilder why = new StringBuilder();
        long started = SystemClock.uptimeMillis();
        AlbumDiscMap fromPlatform = fromPlatformAlbum(context, rows, why);
        long took = SystemClock.uptimeMillis() - started;
        // Logged for every album that reaches the provider, both ways: this
        // is the only path here that costs a query, so its cost on the
        // device is a measured number rather than an assumption, and a
        // refusal names which rung refused.
        Log.i(TAG, "disc map " + (fromPlatform == null
                ? "unresolved (" + why + ")" : fromPlatform.discCount + " discs")
                + " for " + albumName + " (" + rows.size() + " rows) in " + took + "ms");
        return fromPlatform;
    }

    /** The disc this track sits on; 0 when the map does not know it. */
    int discOf(LibraryStore.Track track) {
        Integer disc = track == null ? null : discByTrackId.get(track.id);
        return disc == null ? 0 : disc;
    }

    /** Disc, then the bare track number, then the provider's rowid tie. */
    Comparator<LibraryStore.Track> order() {
        return new Comparator<LibraryStore.Track>() {
            @Override
            public int compare(LibraryStore.Track a, LibraryStore.Track b) {
                int discA = discOf(a);
                int discB = discOf(b);
                if (discA != discB) {
                    return discA < discB ? -1 : 1;
                }
                int trackA = bareTrackNo(a.trackNo);
                int trackB = bareTrackNo(b.trackNo);
                if (trackA != trackB) {
                    return trackA < trackB ? -1 : 1;
                }
                return a.id < b.id ? -1 : (a.id > b.id ? 1 : 0);
            }
        };
    }

    /** The track number without the disc a scanner may have folded in. */
    static int bareTrackNo(int trackNo) {
        return trackNo >= DISC_MULTIPLIER ? trackNo % DISC_MULTIPLIER : trackNo;
    }

    // ------------------------------------------------------------- resolving

    /**
     * Rips whose Genie row already reads {@code disc*1000+track} need no
     * provider at all. All or nothing: an album where only some rows carry
     * the disc would mix two meanings of the same column.
     */
    private static AlbumDiscMap fromGenieTrackNumbers(List<LibraryStore.Track> rows) {
        HashMap<Long, Integer> discs = new HashMap<Long, Integer>(rows.size());
        HashSet<Integer> seen = new HashSet<Integer>(4);
        for (LibraryStore.Track row : rows) {
            int disc = row.trackNo / DISC_MULTIPLIER;
            if (disc <= 0 || row.trackNo % DISC_MULTIPLIER <= 0) {
                return null;
            }
            discs.put(row.id, disc);
            seen.add(disc);
        }
        return seen.size() > 1 ? new AlbumDiscMap(discs, seen.size()) : null;
    }

    /**
     * Two discs collapsed into one Genie album repeat their bare track
     * numbers; one disc never does. Rows without a track number are not
     * evidence either way - they are what makes the album unresolvable
     * later, not what makes it worth looking at.
     */
    private static boolean repeatsATrackNumber(List<LibraryStore.Track> rows) {
        HashSet<Integer> seen = new HashSet<Integer>(rows.size());
        for (LibraryStore.Track row : rows) {
            if (row.trackNo > 0 && !seen.add(row.trackNo)) {
                return true;
            }
        }
        return false;
    }

    private static AlbumDiscMap fromPlatformAlbum(Context context,
                                                  List<LibraryStore.Track> rows,
                                                  StringBuilder why) {
        long albumId = platformAlbumId(context, rows);
        if (albumId <= 0) {
            why.append("no platform album");
            return null;
        }
        List<PlatformTrack> platform = readPlatformAlbum(context, albumId);
        if (platform.isEmpty()) {
            why.append("album ").append(albumId).append(" unreadable");
            return null;
        }
        HashMap<Long, Integer> discs = new HashMap<Long, Integer>(rows.size());
        HashSet<Integer> seen = new HashSet<Integer>(4);
        for (LibraryStore.Track row : rows) {
            int disc = discOf(row, platform);
            if (disc <= 0) {
                why.append("no single disc for ").append(row.title)
                        .append(" in album ").append(albumId);
                return null;
            }
            discs.put(row.id, disc);
            seen.add(disc);
        }
        if (seen.size() < 2) {
            why.append("one disc");
            return null;
        }
        return new AlbumDiscMap(discs, seen.size());
    }

    /**
     * The platform album holding this Genie album, through the shared
     * strict join. A few rows are tried because any single one may be the
     * unmatchable file; the first that resolves names the album.
     */
    private static long platformAlbumId(Context context, List<LibraryStore.Track> rows) {
        int probes = 0;
        for (LibraryStore.Track row : rows) {
            if (row.album == null || row.title == null || row.trackNo <= 0) {
                continue;
            }
            AlbumArtResolver.PlatformRow match = AlbumArtResolver.findPlatformRow(
                    context, row.title, row.album, row.durationMs, row.trackNo);
            if (match != null && match.albumId > 0) {
                return match.albumId;
            }
            if (++probes >= PLATFORM_PROBES) {
                break;
            }
        }
        return 0;
    }

    /**
     * The disc of the one platform row that is this track: same title,
     * same duration within the shared tolerance when both know one, same
     * position on its disc, and a track number that really carries a disc.
     * Zero survivors or more than one is 0 - unknown, which refuses the
     * whole map.
     */
    private static int discOf(LibraryStore.Track row, List<PlatformTrack> platform) {
        if (row.title == null || row.trackNo <= 0) {
            return 0;
        }
        int disc = 0;
        for (PlatformTrack candidate : platform) {
            if (candidate.trackNo < DISC_MULTIPLIER
                    || !row.title.equals(candidate.title)
                    || !AlbumArtResolver.trackNumbersAgree(row.trackNo, candidate.trackNo)) {
                continue;
            }
            if (row.durationMs > 0 && candidate.durationMs > 0
                    && Math.abs(row.durationMs - candidate.durationMs)
                            > AlbumArtResolver.PLATFORM_DURATION_TOLERANCE_MS) {
                continue;
            }
            if (disc != 0) {
                return 0;
            }
            disc = candidate.trackNo / DISC_MULTIPLIER;
        }
        return disc;
    }

    /** One indexed sweep of the platform album; empty when unreadable. */
    private static List<PlatformTrack> readPlatformAlbum(Context context, long albumId) {
        List<PlatformTrack> rows = new ArrayList<PlatformTrack>();
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[]{
                            MediaStore.Audio.Media.TITLE,
                            MediaStore.Audio.Media.TRACK,
                            "duration"
                    },
                    MediaStore.Audio.Media.ALBUM_ID + "=" + albumId, null, null);
            if (cursor == null) {
                return rows;
            }
            while (cursor.moveToNext()) {
                rows.add(new PlatformTrack(
                        cursor.isNull(0) ? null : cursor.getString(0),
                        cursor.isNull(1) ? 0 : cursor.getInt(1),
                        cursor.isNull(2) ? 0 : cursor.getInt(2)));
            }
        } catch (RuntimeException exception) {
            rows.clear();
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return rows;
    }

    /** One platform row, only the columns the join needs. */
    private static final class PlatformTrack {
        final String title;
        final int trackNo;
        final int durationMs;

        PlatformTrack(String title, int trackNo, int durationMs) {
            this.title = title;
            this.trackNo = trackNo;
            this.durationMs = durationMs;
        }
    }
}
