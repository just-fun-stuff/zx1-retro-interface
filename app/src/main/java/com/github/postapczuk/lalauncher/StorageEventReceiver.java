/*
 * ZX1 Retro Interface
 * Storage, cable and scan broadcasts: the storage phase, the background
 * library refresh, and the analyzer's eject yield. Licensed under GPLv2;
 * see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * The one manifest receiver for everything that moves the music volume or
 * its index: boot, the USB cable ({@code UMS_CONNECTED}/{@code
 * UMS_DISCONNECTED}), the volume's own life cycle (eject, unmount, check,
 * mount, share, unshare), the platform media scan and Sony's Genie scan.
 *
 * Every recognized action first goes to {@link StorageState}, so the phase
 * the screens show moves with the broadcast itself. The ones after which
 * the library can have changed - and the Genie STARTED the post-USB pass
 * needs to hear - then start {@link LibraryRefreshService}, which collapses
 * a burst of them into one pass. MEDIA_EJECT also tells a running spectrum
 * pass to close its file: the volume is about to unmount, and the platform
 * kills any process still holding a file open on it.
 *
 * On this 4.1 firmware implicit manifest receivers are dependable; the
 * receiver stays exported-default because the scanner broadcast is sent by
 * the media provider's own uid, which a non-exported receiver would refuse.
 * Anything but the expected actions is ignored.
 */
public class StorageEventReceiver extends BroadcastReceiver {

    private static final String TAG = "ZX1Library";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        boolean refresh = isRefreshTrigger(action);
        if (!refresh && !isPhaseOnly(action)) {
            return;
        }
        StorageState.onStorageBroadcast(context, action);
        if (Intent.ACTION_MEDIA_EJECT.equals(action)) {
            SpectrumAnalyzerService.yieldForStorage();
        }
        if (SonyWalkmanContract.ACTION_GENIE_SCANNER_STARTED.equals(action)
                || SonyWalkmanContract.ACTION_GENIE_SCANNER_FINISHED.equals(action)) {
            // The device-side proof that Sony's indexer broadcasts globally,
            // and whether it carries a data uri.
            Log.i(TAG, "genie scanner "
                    + (SonyWalkmanContract.ACTION_GENIE_SCANNER_STARTED.equals(action)
                    ? "STARTED" : "FINISHED") + " (data " + intent.getData() + ")");
        }
        if (!refresh) {
            return;
        }
        String trigger = action.substring(action.lastIndexOf('.') + 1);
        Log.i(TAG, "storage event " + trigger + "; requesting refresh");
        Intent request = new Intent(context, LibraryRefreshService.class);
        // The full action is the signal the post-USB pass reads.
        request.setAction(action);
        request.putExtra(LibraryRefreshService.EXTRA_TRIGGER, trigger);
        context.startService(request);
    }

    /**
     * After these the library can have changed, so a refresh pass runs.
     * Genie STARTED changes nothing yet; it rides along because the
     * post-USB pass must hear that a Genie FINISHED is now owed, and start
     * intents are how signals reach it (the extra pass it queues collapses
     * into the waiting one).
     */
    private static boolean isRefreshTrigger(String action) {
        return Intent.ACTION_BOOT_COMPLETED.equals(action)
                || StorageState.ACTION_MEDIA_UNSHARED.equals(action)
                || Intent.ACTION_MEDIA_MOUNTED.equals(action)
                || Intent.ACTION_MEDIA_EJECT.equals(action)
                || Intent.ACTION_MEDIA_UNMOUNTED.equals(action)
                || Intent.ACTION_MEDIA_SCANNER_FINISHED.equals(action)
                || SonyWalkmanContract.ACTION_GENIE_SCANNER_STARTED.equals(action)
                || SonyWalkmanContract.ACTION_GENIE_SCANNER_FINISHED.equals(action);
    }

    /** These only move the storage phase. */
    private static boolean isPhaseOnly(String action) {
        return Intent.ACTION_UMS_CONNECTED.equals(action)
                || Intent.ACTION_UMS_DISCONNECTED.equals(action)
                || Intent.ACTION_MEDIA_CHECKING.equals(action)
                || Intent.ACTION_MEDIA_SHARED.equals(action)
                || Intent.ACTION_MEDIA_SCANNER_STARTED.equals(action);
    }
}
