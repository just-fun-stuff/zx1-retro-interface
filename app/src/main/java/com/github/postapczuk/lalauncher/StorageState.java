/*
 * ZX1 Retro Interface
 * Process-wide storage phase: USB storage, Sony's reindex after it, and
 * the launcher's own library rebuild. Licensed under GPLv2; see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.util.Log;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

/**
 * The one answer to "can the music be reached right now, and if not, why"
 * - shared by every screen, so home, the library and the playlists can
 * never disagree about it.
 *
 * The ZX1's music lives on one FAT volume ({@code /storage/sdcard0}).
 * Plugging the cable in makes Sony's Automount app share that volume over
 * USB mass storage: Android unmounts it and the player cannot play. When
 * sharing ends (the USB STORAGE OFF key, the stock notification, or the
 * cable pulled), the volume remounts in ~2 s and Sony reindexes it - the
 * Genie provider reads empty or stale while that runs - and only then can
 * the launcher's own cache be checked and rebuilt. Each of those stretches
 * is a {@link Phase}; screens show it instead of a now-playing glass or a
 * browser that cannot be trusted yet.
 *
 * Inputs, all real reads: {@link Environment#getExternalStorageState()},
 * the storage broadcasts ({@link #onStorageBroadcast}), the cable presence
 * MountService reports, and what {@link LibraryRefreshService} says its
 * post-USB pass is doing. The phase is re-derived from the environment on
 * every {@link #current} read, so a missed broadcast or a fresh process
 * can never strand a screen. Only a session that went through USB storage
 * produces READING/UPDATING; power-on rescans stay silent as before.
 *
 * Threading: listeners and every method except {@link #musicAvailable}
 * and the refresh hooks run on the main thread; the refresh hooks may be
 * called from any thread and are marshalled to the main thread.
 */
final class StorageState {

    private static final String TAG = "ZX1Storage";

    /**
     * Sharing ended. Hidden in the SDK's Intent, but MountService sends it
     * on this firmware ahead of the remount (device-verified).
     */
    static final String ACTION_MEDIA_UNSHARED = "android.intent.action.MEDIA_UNSHARED";

    enum Phase {
        /** Storage mounted and nothing pending: the normal state. */
        READY,
        /** The computer holds the storage; music is unavailable. */
        USB_STORAGE,
        /** Storage remounting, or Sony reindexing after a USB session. */
        READING,
        /** The launcher's own post-USB library rebuild, with real progress. */
        UPDATING
    }

    /** What a screen renders. Immutable. */
    static final class Snapshot {
        final Phase phase;
        /** Rows read so far; meaningful in UPDATING only. */
        final int done;
        /** Rows to read; 0 when not yet known. UPDATING only. */
        final int total;
        /** A USB STORAGE OFF request is in flight (key disabled). */
        final boolean usbOffPending;
        /** The firmware refused USB STORAGE OFF (key reads NOT AVAILABLE). */
        final boolean usbOffRefused;

        Snapshot(Phase phase, int done, int total, boolean usbOffPending,
                 boolean usbOffRefused) {
            this.phase = phase;
            this.done = done;
            this.total = total;
            this.usbOffPending = usbOffPending;
            this.usbOffRefused = usbOffRefused;
        }

        /** A local UPDATING view, for a screen running its own rebuild. */
        static Snapshot updating(int done, int total) {
            return new Snapshot(Phase.UPDATING, done, total, false, false);
        }

        boolean sameAs(Snapshot other) {
            return other != null && phase == other.phase && done == other.done
                    && total == other.total && usbOffPending == other.usbOffPending
                    && usbOffRefused == other.usbOffRefused;
        }
    }

    /** Main thread. Called only when the snapshot actually changed. */
    interface Listener {
        void onStorageChanged(Snapshot snapshot);
    }

    /** The refresh service's post-USB pass, as it last reported. */
    private enum Refresh { NONE, READING, UPDATING }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final List<Listener> LISTENERS = new ArrayList<Listener>();

    // Main-thread state.
    private static boolean umsConnected;
    private static boolean umsKnown;
    /** MEDIA_UNSHARED seen since the last MEDIA_SHARED: sharing is ending. */
    private static boolean unsharing;
    private static Refresh refresh = Refresh.NONE;
    private static int refreshDone;
    private static int refreshTotal;
    private static boolean usbOffPending;
    private static boolean usbOffRefused;
    private static Snapshot published;
    /** Returns to READY from any other phase, for {@link #outagesEnded}. */
    private static int outagesEnded;
    /** refresh != NONE, readable from the service's worker thread. */
    private static volatile boolean pendingForService;

    private StorageState() {
    }

    // ---------------------------------------------------------------- reads

    /**
     * Whether the music volume is mounted - the gate for anything that
     * would hand the player a file or open one (queue writes, analysis).
     * Any thread.
     */
    static boolean musicAvailable() {
        return Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState());
    }

    /** Main thread. Re-derives from the environment, then answers. */
    static Snapshot current(Context context) {
        if (!umsKnown) {
            umsConnected = readUmsConnected(context);
            umsKnown = true;
        }
        publish();
        return published;
    }

    /**
     * Main thread. How many times this process has seen the phase come
     * back to READY. A screen paused through a whole USB session never
     * hears it on its listener; comparing this count on resume tells it
     * one happened (home re-reads covers then).
     */
    static int outagesEnded() {
        return outagesEnded;
    }

    static void addListener(Listener listener) {
        if (!LISTENERS.contains(listener)) {
            LISTENERS.add(listener);
        }
    }

    static void removeListener(Listener listener) {
        LISTENERS.remove(listener);
    }

    /**
     * Main thread, from {@code StorageEventReceiver}. Feeds one storage or
     * cable broadcast into the phase. MEDIA_UNSHARED opens the post-USB
     * READING stretch right away, so there is no READY flash between the
     * remount and the refresh service starting its pass.
     */
    static void onStorageBroadcast(Context context, String action) {
        if (Intent.ACTION_UMS_CONNECTED.equals(action)) {
            umsConnected = true;
            umsKnown = true;
        } else if (Intent.ACTION_UMS_DISCONNECTED.equals(action)) {
            umsConnected = false;
            umsKnown = true;
        } else if (Intent.ACTION_MEDIA_SHARED.equals(action)) {
            unsharing = false;
            usbOffRefused = false;
        } else if (ACTION_MEDIA_UNSHARED.equals(action)) {
            unsharing = true;
            if (refresh == Refresh.NONE) {
                refresh = Refresh.READING;
            }
        } else if (Intent.ACTION_MEDIA_MOUNTED.equals(action)) {
            unsharing = false;
        }
        Log.i(TAG, "broadcast " + action.substring(action.lastIndexOf('.') + 1)
                + " (storage " + Environment.getExternalStorageState()
                + ", cable " + (umsConnected ? "in" : "out") + ")");
        publish();
    }

    /**
     * Any thread. Whether a post-USB pass is owed: the refresh service's
     * pass reports its phases (and waits for Sony's reindex) only then.
     */
    static boolean usbRefreshPending() {
        return pendingForService;
    }

    /** Any thread. The post-USB pass is waiting on Sony's reindex. */
    static void onRefreshReading() {
        postRefresh(Refresh.READING, 0, 0);
    }

    /** Any thread. The post-USB pass is rebuilding: {@code done} of {@code total}. */
    static void onRefreshProgress(int done, int total) {
        postRefresh(Refresh.UPDATING, done, total);
    }

    /** Any thread. The post-USB pass ended - fresh, rebuilt, or gave up. */
    static void onRefreshFinished() {
        postRefresh(Refresh.NONE, 0, 0);
    }

    // ----------------------------------------------------------- USB off

    /**
     * Main thread. Ends USB storage the way SystemUI's own (unexported)
     * UsbStorageActivity does: the hidden
     * {@code StorageManager.disableUsbMassStorage()}, which MountService
     * gates on MOUNT_UNMOUNT_FILESYSTEMS - protection level "dangerous" on
     * this firmware, so the launcher holds it from install. Off the main
     * thread: vold takes 1-4 s to unshare and remount. Success needs no
     * reply - the storage broadcasts move the phase on. A refusal is
     * reported as such and the key reads NOT AVAILABLE; no other route.
     */
    static void requestUsbStorageOff(final Context context) {
        if (usbOffPending) {
            return;
        }
        usbOffPending = true;
        usbOffRefused = false;
        publish();
        final Context app = context.getApplicationContext();
        Thread worker = new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean refused = !disableUsbMassStorage(app);
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        usbOffPending = false;
                        usbOffRefused = refused;
                        publish();
                    }
                });
            }
        }, "zx1-usb-off");
        worker.start();
    }

    /** Worker thread. True when MountService accepted the request. */
    private static boolean disableUsbMassStorage(Context context) {
        StorageManager storage =
                (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        if (storage == null) {
            Log.w(TAG, "usb off: no StorageManager");
            return false;
        }
        try {
            storage.getClass().getMethod("disableUsbMassStorage").invoke(storage);
            Log.i(TAG, "usb off: accepted (storage now "
                    + Environment.getExternalStorageState() + ")");
            return true;
        } catch (InvocationTargetException e) {
            Log.w(TAG, "usb off: refused by MountService: " + e.getCause());
        } catch (NoSuchMethodException e) {
            Log.w(TAG, "usb off: StorageManager has no disableUsbMassStorage()");
        } catch (IllegalAccessException e) {
            Log.w(TAG, "usb off: not accessible: " + e);
        } catch (RuntimeException e) {
            Log.w(TAG, "usb off: failed: " + e);
        }
        return false;
    }

    /** Cable presence as MountService knows it (hidden, unguarded read). */
    private static boolean readUmsConnected(Context context) {
        StorageManager storage =
                (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
        if (storage == null) {
            return false;
        }
        try {
            Object connected = storage.getClass()
                    .getMethod("isUsbMassStorageConnected").invoke(storage);
            return Boolean.TRUE.equals(connected);
        } catch (Exception e) {
            Log.w(TAG, "cable state unreadable: " + e);
            return false;
        }
    }

    // ------------------------------------------------------------ derive

    private static void postRefresh(final Refresh state, final int done, final int total) {
        pendingForService = state != Refresh.NONE;
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                refresh = state;
                refreshDone = done;
                refreshTotal = total;
                publish();
            }
        });
    }

    /** Main thread. Derive, and tell listeners only on a real change. */
    private static void publish() {
        pendingForService = refresh != Refresh.NONE;
        Snapshot next = derive();
        if (next.sameAs(published)) {
            return;
        }
        Phase was = published == null ? null : published.phase;
        published = next;
        if (was != next.phase) {
            Log.i(TAG, "phase " + was + " -> " + next.phase);
            if (was != null && next.phase == Phase.READY) {
                outagesEnded++;
            }
        }
        for (Listener listener : new ArrayList<Listener>(LISTENERS)) {
            listener.onStorageChanged(next);
        }
    }

    private static Snapshot derive() {
        String state = Environment.getExternalStorageState();
        Phase phase;
        if (Environment.MEDIA_SHARED.equals(state)) {
            phase = Phase.USB_STORAGE;
        } else if (!Environment.MEDIA_MOUNTED.equals(state)) {
            // Unmounted/checking: on the way into sharing while the cable
            // is in, otherwise on the way back (or a plain boot-time check).
            phase = umsConnected && !unsharing ? Phase.USB_STORAGE : Phase.READING;
        } else if (refresh == Refresh.UPDATING) {
            phase = Phase.UPDATING;
        } else if (refresh == Refresh.READING) {
            phase = Phase.READING;
        } else {
            phase = Phase.READY;
        }
        boolean usb = phase == Phase.USB_STORAGE;
        return new Snapshot(phase,
                phase == Phase.UPDATING ? refreshDone : 0,
                phase == Phase.UPDATING ? refreshTotal : 0,
                usb && usbOffPending,
                usb && usbOffRefused);
    }
}
