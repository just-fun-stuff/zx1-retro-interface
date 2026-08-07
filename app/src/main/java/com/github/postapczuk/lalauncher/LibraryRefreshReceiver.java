/*
 * ZX1 Retro Interface
 * Storage/scan broadcast trigger for the background library refresh.
 * Licensed under GPLv2; see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Manifest-registered trigger for {@link LibraryRefreshService}: the
 * storage/media-scan broadcasts (mount, eject, unmount, scanner finished -
 * the moments Sony's indexer reruns) plus boot, so the first browser open
 * after power-on finds a fresh cache too. On this 4.1 firmware implicit
 * manifest receivers are dependable; the receiver stays exported-default
 * because the scanner broadcast is sent by the media provider's own uid,
 * which a non-exported receiver would refuse. Anything but the expected
 * actions is ignored.
 */
public class LibraryRefreshReceiver extends BroadcastReceiver {

    private static final String TAG = "ZX1Library";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? null : intent.getAction();
        if (!Intent.ACTION_BOOT_COMPLETED.equals(action)
                && !Intent.ACTION_MEDIA_SCANNER_FINISHED.equals(action)
                && !Intent.ACTION_MEDIA_MOUNTED.equals(action)
                && !Intent.ACTION_MEDIA_EJECT.equals(action)
                && !Intent.ACTION_MEDIA_UNMOUNTED.equals(action)) {
            return;
        }
        String trigger = action.substring(action.lastIndexOf('.') + 1);
        Log.i(TAG, "storage event " + trigger + "; requesting refresh");
        Intent request = new Intent(context, LibraryRefreshService.class);
        request.putExtra(LibraryRefreshService.EXTRA_TRIGGER, trigger);
        context.startService(request);
    }
}
