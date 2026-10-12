/*
 * ZX1 Retro Interface
 * Background library refresh: keeps the C1 cache fresh from storage and
 * scan events so the browser opens on current data. Licensed under
 * GPLv2; see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

/**
 * A short-lived refresh worker, started by {@link StorageEventReceiver}
 * whenever storage or a media scan changed under the library. One pass:
 * read the cheap id fingerprint; if it differs from the cached snapshot's,
 * {@link LibraryStore#rebuild} and persist - so by the time the browser
 * opens, the cache is already current and "UPDATING LIBRARY" never shows.
 *
 * Sony's own indexer reruns at every power-on and USB disconnect and
 * leaves the provider answering empty while it works, so an unreadable or
 * empty read is retried on a doubling backoff for a bounded window, then
 * given up quietly - the browser's in-activity checks remain the safety
 * net. Battery contract: strictly event-driven, one pass per trigger on a
 * background-priority thread, no alarms, no wakelocks, and the service
 * stops itself the moment the last pass ends.
 *
 * After a USB storage session ({@link StorageState#usbRefreshPending}) the
 * pass is no longer silent: it reports READING, then UPDATING with real
 * row counts, then finished, so every screen can show the reindex instead
 * of a library it cannot trust. It also stops trusting the first readable
 * answer - after a remount Genie can still hold the old rows - and reads
 * nothing until a scan has finished since the latest MEDIA_UNSHARED: the
 * platform MEDIA_SCANNER_FINISHED, plus Sony's own Genie FINISHED whenever
 * a Genie STARTED was heard since the unshare. That wait is bounded too
 * ({@link #POST_USB_WINDOW_MS}) and ends the pass quietly when it runs
 * out. Those signals arrive as start intents; each one wakes a waiting
 * pass, so it never sleeps through the moment it was waiting for.
 *
 * This is the IntentService pattern hand-rolled (that class is deprecated
 * at this compileSdk): a serial worker Handler plus {@link #stopSelf(int)}
 * with the pass's startId, so the service only dies when no newer trigger
 * has arrived. A queued-but-unstarted pass is collapsed into the newest
 * request; a rare overlap with the browser's own rebuild is benign - both
 * read the same provider, snapshots are immutable, and the cache file is
 * written via atomic rename.
 */
public class LibraryRefreshService extends Service {

    private static final String TAG = "ZX1Library";
    static final String EXTRA_TRIGGER = "trigger";

    private static final int MSG_REFRESH = 1;
    /** First retry while the provider reads empty (Sony mid-scan). */
    private static final long INITIAL_BACKOFF_MS = 5000L;
    private static final long MAX_BACKOFF_MS = 60000L;
    /** The bounded scan window; past it the pass gives up quietly. */
    private static final long GIVE_UP_AFTER_MS = 4 * 60 * 1000L;
    /**
     * The post-USB window, measured from the latest MEDIA_UNSHARED: remount
     * plus the platform scan of a 1309-track card measured ~2 min, so this
     * leaves Sony's indexer generous room behind it.
     */
    private static final long POST_USB_WINDOW_MS = 10 * 60 * 1000L;

    /*
     * The signals a waiting pass needs, recorded by onStartCommand on the
     * main thread and read by the worker, all under SIGNAL_LOCK. Every
     * start intent bumps signalCount and wakes the worker; the stamps are
     * that count rather than clock readings, so "since the unshare" stays
     * exact for broadcasts delivered within the same millisecond.
     */
    private static final Object SIGNAL_LOCK = new Object();
    private static int signalCount;
    private static int unsharedStamp;
    private static long unsharedUptimeMs;
    private static int platformScanFinishedStamp;
    private static int genieScanStartedStamp;
    private static int genieScanFinishedStamp;

    /** The post-USB rebuild's real row counts, for the UPDATING phase. */
    private static final LibraryStore.Progress REPORT_PROGRESS = new LibraryStore.Progress() {
        @Override
        public void onProgress(int done, int total) {
            StorageState.onRefreshProgress(done, total);
        }
    };

    private HandlerThread workerThread;
    private Handler worker;

    @Override
    public void onCreate() {
        super.onCreate();
        workerThread = new HandlerThread("zx1-library-refresh",
                Process.THREAD_PRIORITY_BACKGROUND);
        workerThread.start();
        worker = new Handler(workerThread.getLooper(), new Handler.Callback() {
            @Override
            public boolean handleMessage(Message message) {
                runRefreshPass((String) message.obj);
                stopSelf(message.arg1);
                return true;
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String trigger = intent == null ? null : intent.getStringExtra(EXTRA_TRIGGER);
        if (trigger == null) {
            trigger = "unknown";
        }
        noteSignal(intent == null ? null : intent.getAction());
        // Collapse a still-queued pass into this newer request; a running
        // pass keeps probing and already covers it.
        worker.removeMessages(MSG_REFRESH);
        worker.sendMessage(Message.obtain(worker, MSG_REFRESH, startId, 0, trigger));
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        workerThread.quit();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Worker thread. Fingerprint check, rebuild when stale, bounded retry -
     * silent, unless a USB session is owed its post-USB pass (class doc).
     */
    private void runRefreshPass(String trigger) {
        boolean postUsb = false;
        long postUsbSinceMs = 0;
        String lastWait = null;
        try {
            long deadline = SystemClock.uptimeMillis() + GIVE_UP_AFTER_MS;
            long backoffMs = INITIAL_BACKOFF_MS;
            while (true) {
                int seen = signalCount();
                if (!postUsb && StorageState.usbRefreshPending()) {
                    postUsb = true;
                    postUsbSinceMs = SystemClock.uptimeMillis();
                    backoffMs = INITIAL_BACKOFF_MS;
                    StorageState.onRefreshReading();
                    Log.i(TAG, "refresh (" + trigger + "): after USB storage;"
                            + " reporting the reindex on screen");
                }
                if (postUsb) {
                    // The window follows the latest unshare; a pass that only
                    // just noticed one counts from now, never from the past.
                    deadline = Math.max(unsharedUptimeMs(), postUsbSinceMs)
                            + POST_USB_WINDOW_MS;
                    String wait = postUsbWait();
                    if (wait != null) {
                        long leftMs = deadline - SystemClock.uptimeMillis();
                        if (leftMs <= 0) {
                            Log.i(TAG, "refresh (" + trigger + "): still waiting for "
                                    + wait + " after USB storage; giving up"
                                    + " (the browser's check remains)");
                            return;
                        }
                        if (!wait.equals(lastWait)) {
                            Log.i(TAG, "refresh (" + trigger + "): waiting for " + wait);
                            lastWait = wait;
                        }
                        awaitSignal(seen, leftMs);
                        continue;
                    }
                    if (lastWait != null) {
                        Log.i(TAG, "refresh (" + trigger + "): rescan finished; reading");
                        lastWait = null;
                    }
                }
                long fingerprint = LibraryStore.readFingerprint(this);
                if (fingerprint != 0) {
                    LibraryStore.Library cached = LibraryStore.loadCached(this);
                    if (cached != null && cached.fingerprint == fingerprint) {
                        Log.i(TAG, "refresh (" + trigger + "): cache already fresh, "
                                + cached.tracks.size() + " tracks");
                        return;
                    }
                    Log.i(TAG, "refresh (" + trigger + "): "
                            + (cached == null ? "no usable cache" : "library changed")
                            + "; rebuilding");
                    if (postUsb) {
                        StorageState.onRefreshProgress(0, 0);
                    }
                    if (LibraryStore.rebuild(this, postUsb ? REPORT_PROGRESS : null) != null) {
                        // rebuild() logged the row count and timing.
                        return;
                    }
                    // The probe answered but the full read did not - Sony's
                    // scan caught up with us; fall through to the backoff.
                    if (postUsb) {
                        StorageState.onRefreshReading();
                    }
                }
                if (SystemClock.uptimeMillis() + backoffMs > deadline) {
                    Log.i(TAG, "refresh (" + trigger + "): provider still unreadable"
                            + " or empty; giving up (the browser's check remains)");
                    return;
                }
                Log.i(TAG, "refresh (" + trigger + "): provider unreadable or empty;"
                        + " retrying in " + (backoffMs / 1000) + " s");
                awaitSignal(seen, backoffMs);
                backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
            }
        } finally {
            if (postUsb) {
                StorageState.onRefreshFinished();
            }
        }
    }

    // ------------------------------------------------------------- signals

    /** Main thread. Records what this start intent brought; wakes the worker. */
    private static void noteSignal(String action) {
        synchronized (SIGNAL_LOCK) {
            int stamp = ++signalCount;
            if (StorageState.ACTION_MEDIA_UNSHARED.equals(action)) {
                unsharedStamp = stamp;
                unsharedUptimeMs = SystemClock.uptimeMillis();
            } else if (Intent.ACTION_MEDIA_SCANNER_FINISHED.equals(action)) {
                platformScanFinishedStamp = stamp;
            } else if (SonyWalkmanContract.ACTION_GENIE_SCANNER_STARTED.equals(action)) {
                genieScanStartedStamp = stamp;
            } else if (SonyWalkmanContract.ACTION_GENIE_SCANNER_FINISHED.equals(action)) {
                genieScanFinishedStamp = stamp;
            }
            SIGNAL_LOCK.notifyAll();
        }
    }

    private static int signalCount() {
        synchronized (SIGNAL_LOCK) {
            return signalCount;
        }
    }

    private static long unsharedUptimeMs() {
        synchronized (SIGNAL_LOCK) {
            return unsharedUptimeMs;
        }
    }

    /**
     * Worker thread. What the post-USB pass still waits for before a read
     * can be trusted; null once it may read. The storage must be mounted
     * again, the platform scan must have finished since the latest
     * unshare, and a Genie scan heard starting since then must have
     * finished too (if Sony's broadcasts never reach us, only the platform
     * scan is waited for).
     */
    private static String postUsbWait() {
        if (!StorageState.musicAvailable()) {
            return "the storage to mount";
        }
        synchronized (SIGNAL_LOCK) {
            if (platformScanFinishedStamp < unsharedStamp) {
                return "the media scan";
            }
            if (genieScanStartedStamp > unsharedStamp
                    && genieScanFinishedStamp < genieScanStartedStamp) {
                return "Sony's library scan";
            }
            return null;
        }
    }

    /**
     * Worker thread. Sleeps up to {@code timeoutMs}, or until a start
     * intent arrives after {@code seen} was read - a signal that landed
     * while the pass was still deciding is never slept through.
     */
    private static void awaitSignal(int seen, long timeoutMs) {
        long until = SystemClock.uptimeMillis() + timeoutMs;
        boolean interrupted = false;
        synchronized (SIGNAL_LOCK) {
            long leftMs = timeoutMs;
            while (signalCount == seen && leftMs > 0) {
                try {
                    SIGNAL_LOCK.wait(leftMs);
                } catch (InterruptedException exception) {
                    // Keep the wait, as SystemClock.sleep does; the flag is
                    // restored below.
                    interrupted = true;
                }
                leftMs = until - SystemClock.uptimeMillis();
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
