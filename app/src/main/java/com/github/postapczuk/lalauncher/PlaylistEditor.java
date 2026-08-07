/*
 * ZX1 Retro Interface
 * One edit to a launcher playlist, kept consistent with the firmware
 * player when that playlist is the queue it is playing. Licensed under
 * GPLv2; see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Log;

/**
 * Adding a track to a playlist, or removing one, is two things at once: a
 * row written to the platform playlists table, and - when that playlist is
 * exactly what the engine is playing right now - a change under an open
 * queue. The engine watches its list's backing table (findings "Queue-next
 * over a platform playlist"): it <b>integrates a pure single-row insert</b>
 * live, but a rewrite makes it drop the descriptor, after which the current
 * track finishes and playback stops.
 *
 * So a member edit runs the same three steps:
 * <pre>
 * 1. write the row (provider only; a failure here leaves everything alone)
 * 2. if the edited list is not the engine's current list - the common case
 *    - the edit is simply done
 * 3. otherwise ask the engine, read-only, whether it took the change
 *    ({@link QueueOracle#awaitTrackCount}); if it did, playback never
 *    blinked. If it did not, reopen the same members uri cued at whatever
 *    is playing now, with the playhead and paused state restored - one
 *    audible hitch, the same one ADD TO NOW PLAYING pays for a queue swap
 * </pre>
 *
 * A single-row <i>delete</i> under an open list has not been observed on
 * the device yet (only the insert has); this class treats that as the open
 * question it is - it asks, logs the answer under {@code ZX1Playlists}, and
 * repairs the queue when the answer is no.
 *
 * Deleting a whole playlist is the one edit with no repair: the list the
 * engine is holding would simply cease to exist. That is refused while it
 * plays ({@link #DELETE_REFUSED_PLAYING}) rather than silently ending
 * playback at the end of the current track.
 */
final class PlaylistEditor {

    /** The edit landed (and the queue is consistent). */
    static final int EDIT_DONE = 0;
    /** Nothing was changed; the player was not touched either. */
    static final int EDIT_FAILED = 1;
    /** A whole-list delete refused because that list is playing now. */
    static final int DELETE_REFUSED_PLAYING = 2;

    interface Listener {
        /** Main thread, exactly once. */
        void onResult(int result);
    }

    private static final String TAG = "ZX1Playlists";

    private static final int OP_ADD = 0;
    private static final int OP_REMOVE = 1;
    private static final int OP_DELETE_LIST = 2;

    private final Context context;
    private final int operation;
    private final long playlistId;
    /** OP_ADD: the platform audio id. OP_REMOVE: the member row id. */
    private final long rowId;
    private final String label;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final QueueOracle oracle;

    private HandlerThread workerThread;
    private boolean resultDelivered;

    private PlaylistEditor(Context context, int operation, long playlistId,
                           long rowId, String label, Listener listener) {
        this.context = context.getApplicationContext();
        this.operation = operation;
        this.playlistId = playlistId;
        this.rowId = rowId;
        this.label = label;
        this.listener = listener;
        this.oracle = new QueueOracle(context);
    }

    /** Main thread. Appends one platform audio row to the end of the list. */
    static void add(Context context, long playlistId, long audioId, String name,
                    Listener listener) {
        new PlaylistEditor(context, OP_ADD, playlistId, audioId,
                "add audio " + audioId + " to playlist " + playlistId
                        + " (" + name + ")", listener).start();
    }

    /** Main thread. Removes one member row from the list. */
    static void remove(Context context, long playlistId, long memberId, String name,
                       Listener listener) {
        new PlaylistEditor(context, OP_REMOVE, playlistId, memberId,
                "remove member " + memberId + " from playlist " + playlistId
                        + " (" + name + ")", listener).start();
    }

    /** Main thread. Deletes the whole list unless it is the playing queue. */
    static void deleteList(Context context, long playlistId, String name,
                           Listener listener) {
        new PlaylistEditor(context, OP_DELETE_LIST, playlistId, 0,
                "delete playlist " + playlistId + " (" + name + ")",
                listener).start();
    }

    private void start() {
        workerThread = new HandlerThread("zx1-playlist-edit");
        workerThread.start();
        new Handler(workerThread.getLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (operation == OP_DELETE_LIST) {
                        runDeleteList();
                    } else {
                        runMemberEdit();
                    }
                } catch (RuntimeException exception) {
                    Log.w(TAG, "edit failed: " + label, exception);
                    finish(EDIT_FAILED);
                }
            }
        });
    }

    // ---------------------------------------------------------- member edits

    /** Worker thread. */
    private void runMemberEdit() {
        PlaylistStore.Rows before = PlaylistStore.readMemberRows(context, playlistId);
        if (before == null) {
            Log.w(TAG, "member rows unreadable; refusing: " + label);
            finish(EDIT_FAILED);
            return;
        }
        int removedIndex = operation == OP_REMOVE
                ? before.memberIds.indexOf(Long.valueOf(rowId)) : -1;
        if (operation == OP_REMOVE && removedIndex < 0) {
            Log.w(TAG, "member is no longer in the list; nothing to remove: " + label);
            finish(EDIT_FAILED);
            return;
        }

        int expectedCount = operation == OP_ADD
                ? PlaylistStore.append(context, playlistId, before, rowId)
                : PlaylistStore.remove(context, playlistId, rowId);
        if (expectedCount < 0) {
            finish(EDIT_FAILED);
            return;
        }

        // The stored list is correct from here on; what remains is keeping
        // the engine's open queue honest when it happens to be this list.
        if (!engineIsOnThisList()) {
            Log.i(TAG, "edit done (the player is not on this list): " + label);
            finish(EDIT_DONE);
            return;
        }
        String edit = operation == OP_ADD ? "insert" : "delete";
        if (oracle.awaitTrackCount(expectedCount)) {
            Log.i(TAG, "the engine integrated the " + edit
                    + " live; playback untouched: " + label);
            finish(EDIT_DONE);
            return;
        }
        Log.i(TAG, "the engine did not integrate the " + edit
                + "; reopening the list: " + label);
        reopen(removedIndex);
    }

    /**
     * Worker thread. The repair: hand the engine the same members uri again,
     * cued where it was. When the removed row was the playing one there is
     * nothing to return to - the list moves on to whatever took its place,
     * from the top of that track, which is the honest reading of "you
     * removed what was playing".
     */
    private void reopen(int removedIndex) {
        PlaylistStore.Rows after = PlaylistStore.readMemberRows(context, playlistId);
        if (after == null || after.size() == 0) {
            // Nothing left to open; the stored edit still stands, and the
            // engine has already let the list go.
            Log.i(TAG, "list is empty after the edit; nothing to reopen: " + label);
            finish(EDIT_DONE);
            return;
        }
        MediaServiceClient.Snapshot now = oracle.currentInfo();
        int startIndex = -1;
        int seekToMs = 0;
        boolean restorePause = now != null
                && now.state == SonyWalkmanContract.PLAY_STATE_PAUSE;
        if (now != null && now.trackId > 0) {
            startIndex = QueueOracle.positionOf(after.audioIds, now.trackId,
                    now.trackIndexInList);
            if (startIndex < 0) {
                startIndex = QueueOracle.positionOf(after.memberIds, now.trackId,
                        now.trackIndexInList);
            }
            if (startIndex >= 0) {
                seekToMs = QueueOracle.seekTarget(now);
            }
        }
        if (startIndex < 0) {
            // The playing row is the one that just left the list: cue the
            // row that inherited its position (the tail when it was last).
            startIndex = Math.min(Math.max(removedIndex, 0), after.size() - 1);
        }
        final PlayerHandoff.Request request = PlayerHandoff.Request.directQueue(
                PlaylistStore.membersUri(playlistId), null, PlaylistStore.PLAY_ORDER,
                startIndex, seekToMs, restorePause, 0, label);
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                PlayerHandoff.play(context, request, new PlayerHandoff.Listener() {
                    @Override
                    public void onHandoff(int tier) {
                        // The stored playlist is already correct either way;
                        // a failed reopen only means the queue was not
                        // re-established, which the log records.
                        if (tier != PlayerHandoff.TIER_BINDER_QUEUE) {
                            Log.w(TAG, "queue reopen failed after the edit: " + label);
                        }
                        finishOnMain(EDIT_DONE);
                    }
                });
            }
        });
    }

    // ------------------------------------------------------ whole-list delete

    /** Worker thread. */
    private void runDeleteList() {
        if (engineIsOnThisList()) {
            Log.i(TAG, "the player is on this list; refusing to delete it: " + label);
            finish(DELETE_REFUSED_PLAYING);
            return;
        }
        finish(PlaylistStore.delete(context, playlistId) ? EDIT_DONE : EDIT_FAILED);
    }

    /** Worker thread. True when the engine's live list is this playlist. */
    private boolean engineIsOnThisList() {
        QueueOracle.Look look = oracle.open(label);
        return look != null && look.isLive()
                && PlaylistStore.isMembersUriOf(look.listUri, playlistId);
    }

    // ------------------------------------------------------------- lifecycle

    /** Worker thread. */
    private void finish(final int result) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                finishOnMain(result);
            }
        });
    }

    /** Main thread. Reports once, releases the read bind, quits the worker. */
    private void finishOnMain(int result) {
        if (resultDelivered) {
            return;
        }
        resultDelivered = true;
        oracle.close();
        HandlerThread thread = workerThread;
        workerThread = null;
        if (thread != null) {
            thread.quit();
        }
        listener.onResult(result);
    }
}
