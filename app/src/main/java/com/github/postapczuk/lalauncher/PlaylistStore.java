/*
 * ZX1 Retro Interface
 * The launcher's own playlists (C2), stored as platform MediaStore
 * playlists. Licensed under GPLv2; see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Every read and write of the launcher's playlists. They live in the
 * <b>platform</b> MediaStore playlists table for one decisive reason: the
 * members uri orders by the single column {@code play_order} - exactly the
 * descriptor shape the firmware player's engine accepts - and platform
 * {@code _data} is readable by this uid, so a launcher playlist is
 * simultaneously a stored list and a real engine queue, cueable at any
 * position (Genie's playlists can be neither: it withholds {@code _data}
 * behind a signature permission, so a Genie descriptor dies at play -
 * findings "ROOT CAUSE"). Sony's own UI reads only Genie playlists, so
 * these never appear on their screens.
 *
 * One list is reserved: {@link #QUEUE_PLAYLIST_NAME} is the launcher's
 * queue scratch - what ADD TO NOW PLAYING materializes into
 * ({@link NowPlayingQueue}) and where a Sony (Genie) playlist is mirrored
 * so it can be played as a real ordered queue. It is hidden from the
 * playlist screens and refused as a user-chosen name.
 *
 * Everything here is provider work only, for a worker thread; the
 * engine-aware half of an edit - a list that is playing right now - lives
 * in {@link PlaylistEditor}. Failures return null/false/0 and are logged
 * under {@code ZX1Playlists}; nothing here ever guesses.
 */
final class PlaylistStore {

    private static final String TAG = "ZX1Playlists";

    /** The launcher's queue scratch list; hidden from the playlist screens. */
    static final String QUEUE_PLAYLIST_NAME = "ZX1 NOW PLAYING";
    /** The members sort column, and the descriptor's ORDER BY. */
    static final String PLAY_ORDER = MediaStore.Audio.Playlists.Members.PLAY_ORDER;

    private static final String PREFS = "now_playing_queue";
    private static final String PREF_PLAYLIST_ID = "playlist_id";
    /** How many rows of a Sony list the launcher will mirror in one tap. */
    private static final int MIRROR_CAP = 500;

    /** One playlist row plus its real member count. */
    static final class Entry {
        final long id;
        final String name;
        final int trackCount;

        Entry(long id, String name, int trackCount) {
            this.id = id;
            this.name = name;
            this.trackCount = trackCount;
        }
    }

    /**
     * One member row in play_order. The display values come from the
     * member's own platform row - the same table the engine will read - and
     * {@link #libraryTrack} is the Genie row when the strict reverse join
     * finds exactly one, which is what lets the task menu offer the
     * library's own actions. A member whose platform row no longer answers
     * with a title is {@link #isDark()}: shown, unplayable, never invented.
     */
    static final class Member {
        final long memberId;
        final long audioId;
        final String title;
        final String artist;
        final int durationMs;
        final LibraryStore.Track libraryTrack;

        Member(long memberId, long audioId, String title, String artist,
               int durationMs, LibraryStore.Track libraryTrack) {
            this.memberId = memberId;
            this.audioId = audioId;
            this.title = title;
            this.artist = artist;
            this.durationMs = durationMs;
            this.libraryTrack = libraryTrack;
        }

        boolean isDark() {
            return title == null;
        }

        String displayTitle() {
            if (libraryTrack != null && libraryTrack.title != null) {
                return libraryTrack.title;
            }
            return title;
        }

        String displayArtist() {
            if (libraryTrack != null && libraryTrack.artist != null) {
                return libraryTrack.artist;
            }
            return artist;
        }
    }

    private PlaylistStore() { }

    static Uri membersUri(long playlistId) {
        return MediaStore.Audio.Playlists.Members.getContentUri("external", playlistId);
    }

    /** True when this uri is exactly that playlist's members list. */
    static boolean isMembersUriOf(Uri uri, long playlistId) {
        if (uri == null || !MediaStore.AUTHORITY.equals(uri.getAuthority())) {
            return false;
        }
        List<String> segments = uri.getPathSegments();
        int count = segments.size();
        return count >= 3
                && "playlists".equals(segments.get(count - 3))
                && "members".equals(segments.get(count - 1))
                && String.valueOf(playlistId).equals(segments.get(count - 2));
    }

    // ------------------------------------------------------------- the lists

    /**
     * Worker thread. The launcher's playlists, alphabetical, each with its
     * real member count. The reserved queue list is filtered out. null when
     * the platform playlists table refuses the read - unknown, not empty.
     */
    static List<Entry> readAll(Context context) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Audio.Playlists._ID,
                            MediaStore.Audio.Playlists.NAME},
                    null, null, null);
            if (cursor == null) {
                Log.w(TAG, "playlists table unreadable");
                return null;
            }
            List<Entry> entries = new ArrayList<Entry>();
            while (cursor.moveToNext()) {
                long id = cursor.getLong(0);
                String name = cursor.isNull(1) ? null : cursor.getString(1);
                if (id <= 0 || QUEUE_PLAYLIST_NAME.equals(name)) {
                    continue;
                }
                entries.add(new Entry(id, name, memberCount(context, id)));
            }
            Collections.sort(entries, BY_NAME);
            return entries;
        } catch (RuntimeException exception) {
            Log.w(TAG, "playlists read failed", exception);
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private static final Comparator<Entry> BY_NAME = new Comparator<Entry>() {
        @Override
        public int compare(Entry left, Entry right) {
            String a = left.name == null ? "" : left.name.toUpperCase(Locale.US);
            String b = right.name == null ? "" : right.name.toUpperCase(Locale.US);
            int order = a.compareTo(b);
            return order != 0 ? order : Long.valueOf(left.id).compareTo(right.id);
        }
    };

    /** Worker thread. -1 when the members list is unreadable. */
    static int memberCount(Context context, long playlistId) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(membersUri(playlistId),
                    new String[]{MediaStore.Audio.Playlists.Members._ID},
                    null, null, null);
            return cursor == null ? -1 : cursor.getCount();
        } catch (RuntimeException exception) {
            return -1;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * Worker thread. The members in play_order - the same query, with the
     * same ORDER BY, that the engine runs for its queue, so a row's display
     * position is its queue index. Each row is offered to the shared
     * reverse join so the browser can act on the Genie track behind it;
     * rows the join cannot place keep their own real platform values.
     * null when the list is unreadable.
     */
    static List<Member> readMembers(Context context, long playlistId) {
        LibraryStore.Library library = LibraryStore.peek();
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(membersUri(playlistId),
                    new String[]{MediaStore.Audio.Playlists.Members._ID,
                            MediaStore.Audio.Playlists.Members.AUDIO_ID,
                            MediaStore.Audio.Media.TITLE,
                            MediaStore.Audio.Media.ARTIST,
                            MediaStore.Audio.Media.ALBUM,
                            MediaStore.Audio.Media.DURATION,
                            MediaStore.Audio.Media.TRACK},
                    null, null, PLAY_ORDER);
            if (cursor == null) {
                Log.w(TAG, "members unreadable for playlist " + playlistId);
                return null;
            }
            List<Member> members = new ArrayList<Member>(cursor.getCount());
            int placed = 0;
            while (cursor.moveToNext()) {
                String title = cursor.isNull(2) ? null : cursor.getString(2);
                String artist = cursor.isNull(3) ? null : cursor.getString(3);
                String album = cursor.isNull(4) ? null : cursor.getString(4);
                int durationMs = cursor.isNull(5) ? -1 : cursor.getInt(5);
                int trackNo = cursor.isNull(6) ? -1 : cursor.getInt(6);
                LibraryStore.Track libraryTrack = AlbumArtResolver.findLibraryTrack(
                        library, title, album, durationMs, trackNo);
                if (libraryTrack != null) {
                    placed++;
                }
                members.add(new Member(cursor.getLong(0), cursor.getLong(1),
                        title, artist, durationMs, libraryTrack));
            }
            Log.i(TAG, "playlist " + playlistId + ": " + members.size()
                    + " members, " + placed + " joined to the library snapshot");
            return members;
        } catch (RuntimeException exception) {
            Log.w(TAG, "members read failed for playlist " + playlistId, exception);
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    // ---------------------------------------------------------------- writes

    /** Whether a user may own this name: non-blank, not the reserved one. */
    static boolean isUsableName(String name) {
        String trimmed = name == null ? "" : name.trim();
        return trimmed.length() > 0
                && !QUEUE_PLAYLIST_NAME.equalsIgnoreCase(trimmed);
    }

    /** A name the user cannot have: blank, reserved, or already in use. */
    static final int NAME_REFUSED = 0;
    /** The provider refused the write itself - a different thing to report. */
    static final int WRITE_FAILED = -1;

    /**
     * Worker thread. Creates a playlist and returns its id, or one of
     * {@link #NAME_REFUSED} / {@link #WRITE_FAILED} so the caller can say
     * which of the two happened rather than guessing.
     */
    static long create(Context context, String name) {
        String trimmed = name == null ? "" : name.trim();
        if (!isUsableName(trimmed) || findByName(context, trimmed) > 0) {
            return NAME_REFUSED;
        }
        long id = insertPlaylist(context, trimmed);
        return id > 0 ? id : WRITE_FAILED;
    }

    /** Worker thread. Renames a playlist; the same three-way result. */
    static int rename(Context context, long playlistId, String name) {
        String trimmed = name == null ? "" : name.trim();
        if (playlistId <= 0 || !isUsableName(trimmed)) {
            return NAME_REFUSED;
        }
        long existing = findByName(context, trimmed);
        if (existing > 0 && existing != playlistId) {
            return NAME_REFUSED;
        }
        try {
            ContentValues values = new ContentValues(1);
            values.put(MediaStore.Audio.Playlists.NAME, trimmed);
            int updated = context.getContentResolver().update(
                    ContentUris.withAppendedId(
                            MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, playlistId),
                    values, null, null);
            Log.i(TAG, "rename playlist " + playlistId + " -> " + trimmed
                    + " (" + updated + " row)");
            return updated > 0 ? 1 : WRITE_FAILED;
        } catch (RuntimeException exception) {
            Log.w(TAG, "playlist rename failed", exception);
            return WRITE_FAILED;
        }
    }

    /** Worker thread. Deletes a playlist (its member rows go with it). */
    static boolean delete(Context context, long playlistId) {
        if (playlistId <= 0) {
            return false;
        }
        try {
            int deleted = context.getContentResolver().delete(
                    ContentUris.withAppendedId(
                            MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, playlistId),
                    null, null);
            Log.i(TAG, "delete playlist " + playlistId + " (" + deleted + " row)");
            return deleted > 0;
        } catch (RuntimeException exception) {
            Log.w(TAG, "playlist delete failed", exception);
            return false;
        }
    }

    /**
     * Worker thread. Appends one audio row past the current tail's
     * play_order - a pure insert, which is the edit the engine was proven
     * to absorb live when the list is the one playing (findings "The
     * hitchless in-place insert"). Returns the resulting member count, or
     * -1 when the write failed.
     */
    static int append(Context context, long playlistId, long audioId) {
        Rows rows = readMemberRows(context, playlistId);
        return rows == null ? -1 : append(context, playlistId, rows, audioId);
    }

    /** The same append when the caller already read the member rows. */
    static int append(Context context, long playlistId, Rows rows, long audioId) {
        int next = rows.playOrders.isEmpty() ? 0
                : rows.playOrders.get(rows.playOrders.size() - 1) + 1;
        try {
            ContentValues values = new ContentValues(2);
            values.put(MediaStore.Audio.Playlists.Members.PLAY_ORDER, next);
            values.put(MediaStore.Audio.Playlists.Members.AUDIO_ID, audioId);
            if (context.getContentResolver().insert(membersUri(playlistId), values) == null) {
                Log.w(TAG, "member insert refused: playlist " + playlistId
                        + " audio " + audioId);
                return -1;
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "member insert failed", exception);
            return -1;
        }
        Log.i(TAG, "playlist " + playlistId + ": appended audio " + audioId
                + " at play_order " + next);
        return rows.size() + 1;
    }

    /**
     * Worker thread. Removes one member row by its member id. The gap it
     * leaves in play_order is harmless - the column only has to sort - and
     * the next append still lands past the tail. Returns the resulting
     * member count, or -1 when nothing was removed.
     */
    static int remove(Context context, long playlistId, long memberId) {
        Rows rows = readMemberRows(context, playlistId);
        if (rows == null) {
            return -1;
        }
        try {
            int deleted = context.getContentResolver().delete(
                    ContentUris.withAppendedId(membersUri(playlistId), memberId),
                    null, null);
            if (deleted <= 0) {
                Log.w(TAG, "member delete removed nothing: playlist " + playlistId
                        + " member " + memberId);
                return -1;
            }
        } catch (RuntimeException exception) {
            Log.w(TAG, "member delete failed", exception);
            return -1;
        }
        Log.i(TAG, "playlist " + playlistId + ": removed member " + memberId);
        return rows.size() - 1;
    }

    /**
     * Worker thread. Replaces the whole member list with these audio ids as
     * play_order 0..n-1, verifying the count actually landed - a queue must
     * never be opened against a half-written table. The engine drops its
     * descriptor when the backing rows are rewritten under it (findings),
     * so callers rewrite only when they are about to reopen.
     */
    static boolean writeMembers(Context context, long playlistId, List<Long> audioIds) {
        Uri members = membersUri(playlistId);
        try {
            context.getContentResolver().delete(members, null, null);
            ContentValues[] rows = new ContentValues[audioIds.size()];
            for (int i = 0; i < audioIds.size(); i++) {
                ContentValues values = new ContentValues(2);
                values.put(MediaStore.Audio.Playlists.Members.PLAY_ORDER, i);
                values.put(MediaStore.Audio.Playlists.Members.AUDIO_ID, audioIds.get(i));
                rows[i] = values;
            }
            int inserted = context.getContentResolver().bulkInsert(members, rows);
            if (inserted != audioIds.size()) {
                Log.w(TAG, "member write incomplete: " + inserted + " of "
                        + audioIds.size() + "; refusing to open it");
                return false;
            }
            return true;
        } catch (RuntimeException exception) {
            Log.w(TAG, "member write failed", exception);
            return false;
        }
    }

    /** The lean member read: ids and play_order in list order, no joins. */
    static final class Rows {
        final List<Long> memberIds = new ArrayList<Long>();
        final List<Long> audioIds = new ArrayList<Long>();
        final List<Integer> playOrders = new ArrayList<Integer>();

        int size() {
            return audioIds.size();
        }
    }

    /**
     * Worker thread. The member rows without the library joins - what the
     * queue paths need (order, identity, the tail's play_order). null when
     * the list is unreadable, which callers must treat as unknown.
     */
    static Rows readMemberRows(Context context, long playlistId) {
        Rows rows = new Rows();
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(membersUri(playlistId),
                    new String[]{MediaStore.Audio.Playlists.Members._ID,
                            MediaStore.Audio.Playlists.Members.AUDIO_ID,
                            MediaStore.Audio.Playlists.Members.PLAY_ORDER},
                    null, null, PLAY_ORDER);
            if (cursor == null) {
                Log.w(TAG, "member rows unreadable for playlist " + playlistId);
                return null;
            }
            while (cursor.moveToNext()) {
                rows.memberIds.add(cursor.getLong(0));
                rows.audioIds.add(cursor.getLong(1));
                rows.playOrders.add(cursor.getInt(2));
            }
            return rows;
        } catch (RuntimeException exception) {
            Log.w(TAG, "member rows read failed for playlist " + playlistId, exception);
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    // ------------------------------------------------------ the queue scratch

    /**
     * Worker thread. The reserved queue list's id: the stored one when it
     * still exists (Sony's indexer owns this database and may rebuild it),
     * then by name, then created. 0 when the provider refuses.
     */
    static long ensureQueuePlaylist(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        long stored = prefs.getLong(PREF_PLAYLIST_ID, 0);
        if (stored > 0 && exists(context, stored)) {
            return stored;
        }
        long resolved = findByName(context, QUEUE_PLAYLIST_NAME);
        if (resolved <= 0) {
            resolved = insertPlaylist(context, QUEUE_PLAYLIST_NAME);
        }
        if (resolved > 0 && resolved != stored) {
            prefs.edit().putLong(PREF_PLAYLIST_ID, resolved).commit();
        }
        return resolved;
    }

    static boolean exists(Context context, long playlistId) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    ContentUris.withAppendedId(
                            MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, playlistId),
                    new String[]{MediaStore.Audio.Playlists._ID}, null, null, null);
            return cursor != null && cursor.moveToFirst();
        } catch (RuntimeException exception) {
            return false;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    static long findByName(Context context, String name) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Audio.Playlists._ID},
                    MediaStore.Audio.Playlists.NAME + " = ?",
                    new String[]{name}, null);
            return cursor != null && cursor.moveToFirst() ? cursor.getLong(0) : 0;
        } catch (RuntimeException exception) {
            Log.w(TAG, "playlist lookup failed", exception);
            return 0;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private static long insertPlaylist(Context context, String name) {
        try {
            ContentValues values = new ContentValues(1);
            values.put(MediaStore.Audio.Playlists.NAME, name);
            Uri created = context.getContentResolver().insert(
                    MediaStore.Audio.Playlists.EXTERNAL_CONTENT_URI, values);
            long id = created == null ? 0 : ContentUris.parseId(created);
            Log.i(TAG, "created platform playlist " + id + " (" + name + ")");
            return id;
        } catch (RuntimeException exception) {
            Log.w(TAG, "playlist create failed", exception);
            return 0;
        }
    }

    // --------------------------------------------------------- the Genie mirror

    /** A Sony list rewritten as platform ids, with the tapped row located. */
    static final class Mirror {
        final List<Long> audioIds;
        final int startIndex;
        final int dropped;

        Mirror(List<Long> audioIds, int startIndex, int dropped) {
            this.audioIds = audioIds;
            this.startIndex = startIndex;
            this.dropped = dropped;
        }
    }

    /**
     * Worker thread. Sony's playlists are readable but unplayable as
     * themselves (a Genie descriptor dies at play), so playing one means
     * mirroring it: every member joined to its platform row with the strict
     * discipline, in the provider order the list was read in. Rows that
     * cannot be carried are dropped and counted - the platform queue
     * physically cannot contain them - but the tapped row must survive or
     * the request refuses rather than start something else.
     */
    static Mirror mirrorOf(Context context, List<LibraryStore.Track> members, int tappedIndex) {
        if (members == null || members.isEmpty()) {
            return null;
        }
        if (members.size() > MIRROR_CAP) {
            // Each row costs one strict provider join; past this the tap is
            // no longer an action with a beginning and an end on this
            // hardware, so it refuses instead of freezing on STARTING.
            Log.w(TAG, "walkman list has " + members.size() + " rows (cap "
                    + MIRROR_CAP + "); refusing to mirror it");
            return null;
        }
        List<Long> audioIds = new ArrayList<Long>(members.size());
        int startIndex = -1;
        int dropped = 0;
        for (int i = 0; i < members.size(); i++) {
            LibraryStore.Track track = members.get(i);
            AlbumArtResolver.PlatformRow row = track.title == null ? null
                    : AlbumArtResolver.findPlatformRow(context, track.title, track.album,
                            track.durationMs, track.trackNo);
            if (row == null || row.trackId <= 0) {
                if (i == tappedIndex) {
                    Log.w(TAG, "tapped row " + track.title
                            + " has no platform match; refusing to play the list");
                    return null;
                }
                dropped++;
                continue;
            }
            audioIds.add(row.trackId);
            if (i == tappedIndex) {
                startIndex = audioIds.size() - 1;
            }
        }
        if (audioIds.isEmpty() || startIndex < 0) {
            Log.w(TAG, "genie list mirror carried nothing playable; refusing");
            return null;
        }
        return new Mirror(audioIds, startIndex, dropped);
    }
}
