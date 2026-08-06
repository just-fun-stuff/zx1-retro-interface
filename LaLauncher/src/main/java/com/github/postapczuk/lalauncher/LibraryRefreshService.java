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
 * A short-lived refresh worker, started by {@link LibraryRefreshReceiver}
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

    /** Worker thread. Fingerprint check, rebuild when stale, bounded retry. */
    private void runRefreshPass(String trigger) {
        long deadline = SystemClock.uptimeMillis() + GIVE_UP_AFTER_MS;
        long backoffMs = INITIAL_BACKOFF_MS;
        while (true) {
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
                if (LibraryStore.rebuild(this, null) != null) {
                    // rebuild() logged the row count and timing.
                    return;
                }
                // The probe answered but the full read did not - Sony's
                // scan caught up with us; fall through to the backoff.
            }
            if (SystemClock.uptimeMillis() + backoffMs > deadline) {
                Log.i(TAG, "refresh (" + trigger + "): provider still unreadable"
                        + " or empty; giving up (the browser's check remains)");
                return;
            }
            Log.i(TAG, "refresh (" + trigger + "): provider unreadable or empty;"
                    + " retrying in " + (backoffMs / 1000) + " s");
            SystemClock.sleep(backoffMs);
            backoffMs = Math.min(backoffMs * 2, MAX_BACKOFF_MS);
        }
    }
}
