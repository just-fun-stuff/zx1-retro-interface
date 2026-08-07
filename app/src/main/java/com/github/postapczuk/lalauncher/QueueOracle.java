/*
 * ZX1 Retro Interface
 * The read-only look at the firmware player's queue: what is playing, from
 * which descriptor, and whether the engine took an edit. Licensed under
 * GPLv2; see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.net.Uri;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * One anonymous, strictly read-only visit to the firmware player, held open
 * for the length of a queue operation. It issues only calls that cannot
 * change anything: {@code getPlayers} / {@code getPlayerInterface(name,
 * null)} (the now-playing poll's own handshake, no client registration) plus
 * {@code getCurrentInfo} (txn 27) and {@code getMediaListInfo} (txn 22) -
 * the descriptor oracle from the findings.
 *
 * Two callers need exactly this: {@link NowPlayingQueue} before it decides
 * how to add a track, and {@link PlaylistEditor} after it edits a playlist
 * that may be the list the engine is playing. Writes stay where they
 * belong - in {@link PlayerHandoff}, which is the only class that registers
 * as a client and submits tasks.
 *
 * {@link #awaitTrackCount} is the verification the whole hitchless path
 * rests on: the engine watches its open list's backing table, drops the
 * descriptor when the rows are rewritten under it, but integrates a pure
 * single-row insert (proven live 2026-07-21). Reading the live track count
 * back over ~1.5 s is how the launcher learns which of the two happened -
 * without touching playback.
 */
final class QueueOracle {

    private static final String TAG = "ZX1Queue";
    private static final long BIND_WAIT_MS = 3000L;
    private static final long VERIFY_WINDOW_MS = 1500L;
    private static final long VERIFY_STEP_MS = 300L;

    /** What a queue operation needs to know about the live player. */
    static final class Look {
        MediaServiceClient.Snapshot snapshot;
        Uri listUri;
        String listSelection;
        String listSortOrder;

        /** True when something is really loaded (not CLOSE, real track id). */
        boolean isLive() {
            return snapshot != null && snapshot.trackId > 0
                    && snapshot.state != SonyWalkmanContract.PLAY_STATE_CLOSE;
        }
    }

    private final Context context;
    private final Object bindLock = new Object();
    private boolean bound;
    private IBinder mediaService;
    private IBinder player;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (bindLock) {
                mediaService = binder;
                bindLock.notifyAll();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (bindLock) {
                mediaService = null;
            }
        }
    };

    QueueOracle(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Worker thread. Binds, resolves the audio player, and reads its state
     * and descriptor once. Null when no player answers - typically a fresh
     * boot where the player app has never run, which is not a failure but a
     * fact the caller must respect (there is no queue to edit).
     */
    Look open(String label) {
        if (!SonyWalkmanContract.isInstalled(context.getPackageManager())) {
            Log.i(TAG, "player package not installed");
            return null;
        }
        try {
            bound = context.bindService(SonyWalkmanContract.createMediaServiceIntent(),
                    connection, Context.BIND_AUTO_CREATE);
        } catch (SecurityException exception) {
            bound = false;
        }
        if (!bound) {
            Log.w(TAG, "media service bind failed");
            return null;
        }
        try {
            IBinder service;
            synchronized (bindLock) {
                long deadline = SystemClock.uptimeMillis() + BIND_WAIT_MS;
                while (mediaService == null) {
                    long remaining = deadline - SystemClock.uptimeMillis();
                    if (remaining <= 0) {
                        break;
                    }
                    try {
                        bindLock.wait(remaining);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                service = mediaService;
            }
            if (service == null) {
                Log.w(TAG, "media service never connected");
                return null;
            }
            List<String> names = MediaServiceClient.getPlayers(service);
            for (String name : orderAudioFirst(names)) {
                IBinder resolved = MediaServiceClient.getPlayerInterface(service, name);
                if (resolved == null) {
                    continue;
                }
                player = resolved;
                Look look = new Look();
                look.snapshot = MediaServiceClient.readCurrentInfo(resolved);
                readDescriptor(look);
                Log.i(TAG, "player " + name + ": state="
                        + (look.snapshot == null ? "?" : look.snapshot.state)
                        + " trackId=" + (look.snapshot == null ? "?" : look.snapshot.trackId)
                        + " playTime=" + (look.snapshot == null ? "?" : look.snapshot.playTimeMs)
                        + " list=" + look.listUri
                        + " selection=" + look.listSelection
                        + " sort=" + look.listSortOrder
                        + " for " + label);
                return look;
            }
            Log.i(TAG, "no active players (" + names + ") for " + label);
            return null;
        } catch (RemoteException exception) {
            Log.w(TAG, "player read failed", exception);
            return null;
        }
    }

    /** Audio-named players first - the now-playing poll's own policy. */
    private static List<String> orderAudioFirst(List<String> names) {
        List<String> ordered = new ArrayList<String>(names.size());
        for (String name : names) {
            if (MediaServiceClient.isAudioName(name)) {
                ordered.add(name);
            }
        }
        for (String name : names) {
            if (!MediaServiceClient.isAudioName(name)) {
                ordered.add(name);
            }
        }
        return ordered;
    }

    /** Worker thread. A fresh PlayInfo read; null when nothing answers. */
    MediaServiceClient.Snapshot currentInfo() {
        IBinder current = player;
        if (current == null) {
            return null;
        }
        try {
            return MediaServiceClient.readCurrentInfo(current);
        } catch (RemoteException exception) {
            return null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /**
     * Worker thread. Watches the engine's own live list state after an edit:
     * the descriptor staying present, and PlayInfo's track count reaching
     * the expected value. False means the engine did not integrate the edit
     * - it dropped the list, or never noticed - and only a reopen repairs
     * that. Read-only throughout; playback is never touched.
     */
    boolean awaitTrackCount(int expectedCount) {
        IBinder current = player;
        if (current == null) {
            return false;
        }
        long deadline = SystemClock.uptimeMillis() + VERIFY_WINDOW_MS;
        while (SystemClock.uptimeMillis() < deadline) {
            SystemClock.sleep(VERIFY_STEP_MS);
            try {
                MediaServiceClient.Snapshot now =
                        MediaServiceClient.readCurrentInfo(current);
                Look descriptor = new Look();
                readDescriptor(descriptor);
                if (descriptor.listUri == null) {
                    Log.i(TAG, "list descriptor gone after the edit");
                    return false;
                }
                if (now != null && now.trackCountInList == expectedCount) {
                    Log.i(TAG, "engine count " + now.trackCountInList
                            + " (state=" + now.state + " trackId=" + now.trackId
                            + " playTime=" + now.playTimeMs + ")");
                    return true;
                }
            } catch (RemoteException exception) {
                Log.w(TAG, "verify read failed", exception);
                return false;
            } catch (RuntimeException exception) {
                Log.w(TAG, "verify read failed", exception);
                return false;
            }
        }
        return false;
    }

    /**
     * getMediaListInfo (txn 22, read-only): uri, index, selection, sort.
     * Failures leave the look's descriptor fields null - the callers all
     * refuse rather than act on a descriptor they could not read.
     */
    private void readDescriptor(Look look) {
        IBinder current = player;
        if (current == null) {
            return;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SonyWalkmanContract.PLAYER_DESCRIPTOR);
            current.transact(SonyWalkmanContract.TRANSACTION_GET_MEDIA_LIST_INFO,
                    data, reply, 0);
            reply.readException();
            if (reply.readInt() == 0) {
                return;
            }
            look.listUri = reply.readInt() != 0
                    ? (Uri) Uri.CREATOR.createFromParcel(reply) : null;
            reply.readInt(); // start index; the live position comes from PlayInfo
            look.listSelection = reply.readInt() != 0 ? reply.readString() : null;
            look.listSortOrder = reply.readInt() != 0 ? reply.readString() : null;
        } catch (RemoteException exception) {
            // Descriptor unreadable; callers refuse live queues without one.
        } catch (RuntimeException exception) {
            // Same: never let a parcel misread take the flow down.
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    // ------------------------------------------------- shared restore policy

    /** Playhead restores below this are not worth a seek transaction. */
    private static final int SEEK_WORTHWHILE_MS = 10000;

    /**
     * The playhead to restore after a reopen: 0 when the engine is not on a
     * track, when the position is too small to be worth a transaction, or
     * when it sits in the last seconds of the track (a seek past the end
     * would land somewhere the user never was).
     */
    static int seekTarget(MediaServiceClient.Snapshot snapshot) {
        if (snapshot == null
                || (snapshot.state != SonyWalkmanContract.PLAY_STATE_PLAY
                        && snapshot.state != SonyWalkmanContract.PLAY_STATE_PAUSE)) {
            return 0;
        }
        int seekTo = snapshot.playTimeMs;
        if (snapshot.durationMs > 0 && seekTo > snapshot.durationMs - 5000) {
            seekTo = Math.max(0, snapshot.durationMs - 5000);
        }
        return seekTo < SEEK_WORTHWHILE_MS ? 0 : seekTo;
    }

    /**
     * Where a reported track id sits in a queue order. PlayInfo's live index
     * is the hint: when the id really is at that index it wins, which keeps
     * a cue index right even when the same track appears twice in the list.
     */
    static int positionOf(List<Long> ids, long id, int indexHint) {
        if (indexHint >= 0 && indexHint < ids.size() && ids.get(indexHint) == id) {
            return indexHint;
        }
        return ids.indexOf(id);
    }

    /** Any thread. Releases the bind; safe to call more than once. */
    void close() {
        player = null;
        if (!bound) {
            return;
        }
        bound = false;
        try {
            context.unbindService(connection);
        } catch (IllegalArgumentException ignored) {
            // Already released with a dead process.
        }
    }
}
