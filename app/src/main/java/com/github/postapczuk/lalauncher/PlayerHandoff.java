/*
 * ZX1 Retro Interface
 * Playback handoff to the Sony firmware player (C1): the binder's write side
 * with graceful intent fallbacks. Licensed under GPLv2; see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Starts playback of a library selection on the stock player. Three tiers,
 * best first, each honestly logged (tag {@code ZX1Handoff}) so the on-device
 * run names the working mechanism:
 *
 * <pre>
 * 1. binder-queue - IPlayerRemote.open(PlayListSelectionInfo) + play():
 *    hands the player a content query (Genie URI + ORDER BY + start index)
 *    that it runs in its own privileged process. Whole list becomes the
 *    queue, no UI switch. Transaction codes and the parcel layout come from
 *    the decompiled client jar only (docs/firmware-findings.md); nothing is
 *    ever guessed against the live player. A query shape the service
 *    dislikes does not error - it blocks forever and wedges the player's
 *    whole write path until reboot (proven live 2026-07-20) - so exactly
 *    one shape is sent, the player's own, and an unanswered call latches
 *    every later request to fail fast rather than pile onto the wedge.
 *    A reply is only half the story: RequestResult's MediaError is the
 *    task SUBMISSION status, and the task engine only completes work for
 *    registered clients - an anonymous open() is accepted, then dies
 *    silently and closes the list (proven live) - so the handoff performs
 *    Sony's own client handshake first (getPlayerInterface bundle id,
 *    addListener with a hosted callback stub, setServerKeepAlive), waits
 *    for the service's onOpen announcement, confirms the current list is
 *    ours, then sends the controller's own play call (playWithParam) once
 *    and watches until the player actually reports PLAY.
 * 2. view-file - ACTION_VIEW file://path at the player's exported
 *    ScanAndPlayFileActivity (file scheme + audio mime is its own manifest
 *    filter). Plays the one chosen file and opens the Sony UI. Needs the
 *    platform MediaStore join for the readable path.
 * 3. app-only - opens the Sony music app's top screen; the user finishes
 *    the selection there. The last resort, still never a dead end.
 * </pre>
 *
 * One instance serves one request on a private short-lived worker thread,
 * then unbinds and quits. All failures degrade to the next tier - except an
 * unmounted music volume (USB storage), which is refused before any tier
 * runs; the listener always hears exactly one outcome, on the main thread.
 *
 * A {@link Request#directQueue} request (queue-next, v2.13.0) rides the
 * same registered-client machinery but opens the caller's exact
 * descriptor, restores the playhead/pause state afterwards, and never
 * falls into the intent tiers - a failed queue edit reports failure
 * rather than replacing the queue with one file in the Sony UI.
 */
final class PlayerHandoff {

    /** Outcomes, in descending order of fidelity. */
    static final int TIER_BINDER_QUEUE = 0;
    static final int TIER_VIEW_FILE = 1;
    static final int TIER_APP_ONLY = 2;
    static final int TIER_FAILED = 3;

    interface Listener {
        /** Main thread, exactly once per request. */
        void onHandoff(int tier);
    }

    /** One playback selection: a queue query plus one-file fallback data. */
    static final class Request {
        /** The Genie URI whose rows become the player's queue. */
        final Uri queueUri;
        /**
         * ORDER BY for the player's query - a single bare column name (the
         * service rejects multi-column sort strings); null keeps the
         * provider order.
         */
        final String sortOrder;
        /**
         * 0-based index of the chosen track in the list the browser
         * displayed. The binder tier never submits it: the queue it opens
         * is a platform query, and the index is recounted there against
         * that query. Kept for the log line that compares the two.
         */
        final int startIndex;
        /** Genie id of the chosen track, for verification logging; 0 = none. */
        final long trackId;
        /** Chosen track's metadata for the view-file fallback join. */
        final String title;
        final String album;
        final int durationMs;
        final int trackNo;
        final String mimeType;
        /** Short human label for the log lines. */
        final String label;

        Request(Uri queueUri, String sortOrder, int startIndex, long trackId,
                String title, String album, int durationMs, int trackNo,
                String mimeType, String label) {
            this.queueUri = queueUri;
            this.sortOrder = sortOrder;
            this.startIndex = startIndex;
            this.trackId = trackId;
            this.title = title;
            this.album = album;
            this.durationMs = durationMs;
            this.trackNo = trackNo;
            this.mimeType = mimeType;
            this.label = label;
        }

        /*
         * Direct-descriptor mode (queue-next): the caller already holds the
         * exact platform descriptor to open - no Genie join, no descriptor
         * derivation, and on failure NO intent tiers (a failed queue edit
         * must never degrade into replacing the queue with one file in the
         * Sony UI). Null/absent unless {@link #directQueue} built this.
         */
        Uri directUri;
        String directSelection;
        String directSortOrder;
        int directStartIndex;
        /** Playhead restore after the reopen; <= 0 skips the seek. */
        int seekToMs;
        /** True re-issues PAUSE once PLAY was observed (state restore). */
        boolean restorePause;

        boolean isDirectQueue() {
            return directUri != null;
        }

        /**
         * A queue-next request: open exactly this descriptor at this index,
         * then restore the playhead ({@code seekToMs} > 0) and the paused
         * state ({@code restorePause}). {@code trackId} is the added
         * track's Genie id, for log correlation only.
         */
        static Request directQueue(Uri uri, String selection, String sortOrder,
                                   int startIndex, int seekToMs, boolean restorePause,
                                   long trackId, String label) {
            Request request = new Request(null, null, 0, trackId,
                    null, null, -1, -1, null, label);
            request.directUri = uri;
            request.directSelection = selection;
            request.directSortOrder = sortOrder;
            request.directStartIndex = startIndex;
            request.seekToMs = seekToMs;
            request.restorePause = restorePause;
            return request;
        }
    }

    private static final String TAG = "ZX1Handoff";
    /**
     * Watchdog over the whole binder tier (connect + open variants + play
     * retry; a healthy pass ends well under half of this). It lives on the
     * main handler because a live device run (2026-07-20) showed open()'s
     * transact can block forever - a watchdog posted to the worker would
     * sit queued behind the very call it guards.
     */
    private static final long BINDER_WATCHDOG_MS = 8000L;
    /** How long the submitted list may take to become current after open(). */
    private static final long OPEN_SETTLE_WINDOW_MS = 4000L;
    /** How long play() submissions may take to produce the PLAY state. */
    private static final long PLAY_SETTLE_WINDOW_MS = 5000L;
    private static final long SETTLE_STEP_MS = 300L;

    private final Context context;
    private final Request request;
    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private HandlerThread workerThread;
    private Handler worker;
    private boolean bound;
    /** Claimed once under {@code this}; read from worker, watchdog, main. */
    private volatile boolean delivered;
    /** Ensures the watchdog and a binder failure cannot both run the tiers. */
    private boolean fallbackClaimed;
    /** True exactly while a transact to the player is in flight. */
    private volatile boolean transactInFlight;
    /**
     * Latched process-wide the moment a transact goes unanswered: the
     * service is then holding its state lock, and every write after ours -
     * Sony's own UI, the hardware keys, ScanAndPlayFileActivity - blocks
     * behind it until the device reboots (observed live: the scan-and-play
     * screen just spins). Failing fast is the only honest behavior left.
     */
    private static volatile boolean serviceWedged;

    /** The Genie→platform join for this request; set by the binder tier. */
    private volatile AlbumArtResolver.PlatformRow platformRow;
    /** The index actually submitted by the last open(), for the log line. */
    private volatile int queueIndex = -1;

    /** Registration state, kept for the polite teardown after delivery. */
    private volatile IBinder mediaServiceBinder;
    private volatile IBinder playerBinder;
    private volatile String playerName;
    private volatile String clientId;

    private final Object openEventLock = new Object();
    /** The last onOpen callback's two ints; null until one arrives. */
    private int[] openEvent;
    /** Set by an onClosed event: the service killed the current list. */
    private volatile boolean listClosedEvent;

    /** Main thread. The fallback joins the provider, so it gets a thread. */
    private final Runnable timeoutRunnable = new Runnable() {
        @Override
        public void run() {
            if (delivered) {
                return;
            }
            if (transactInFlight) {
                // The service took our call and went silent: it is wedged,
                // and the intent tiers would only spin against it.
                serviceWedged = true;
                Log.w(TAG, "open()/play() never answered for " + request.label
                        + "; player service wedged - failing fast until reboot");
                deliver(TIER_FAILED);
                return;
            }
            Log.w(TAG, "binder tier timed out before any transact for "
                    + request.label + "; trying the intent tiers");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    runFallbackTiers();
                }
            }, "zx1-handoff-fallback").start();
        }
    };

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, final IBinder binder) {
            Handler w = worker;
            if (w == null) {
                return;
            }
            w.post(new Runnable() {
                @Override
                public void run() {
                    runBinderTier(binder);
                }
            });
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // A death mid-request is handled by the timeout.
        }
    };

    private PlayerHandoff(Context context, Request request, Listener listener) {
        this.context = context.getApplicationContext();
        this.request = request;
        this.listener = listener;
    }

    /** Main thread. Fire-and-report; the instance cleans itself up. */
    static void play(Context context, Request request, Listener listener) {
        new PlayerHandoff(context, request, listener).start();
    }

    private void start() {
        workerThread = new HandlerThread("zx1-handoff");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());
        if (serviceWedged) {
            Log.w(TAG, "player service wedged earlier this session; failing fast for "
                    + request.label);
            deliver(TIER_FAILED);
            return;
        }
        if (!StorageState.musicAvailable()) {
            // USB storage or a remount: the engine cannot resolve any queue
            // over an unmounted volume, and a shape it cannot serve risks
            // the open() wedge that only a reboot clears; the intent tiers
            // would hand Sony a file that is not there. Nothing is sent.
            Log.w(TAG, "storage not mounted; refusing: " + request.label);
            deliver(TIER_FAILED);
            return;
        }
        if ((request.queueUri == null && !request.isDirectQueue())
                || !SonyWalkmanContract.isInstalled(context.getPackageManager())) {
            Log.i(TAG, request.queueUri == null && !request.isDirectQueue()
                    ? "no queue uri for " + request.label + "; intent tiers only"
                    : "player package not installed; skipping binder tier");
            worker.post(new Runnable() {
                @Override
                public void run() {
                    runFallbackTiers();
                }
            });
            return;
        }
        mainHandler.postDelayed(timeoutRunnable, BINDER_WATCHDOG_MS);
        try {
            bound = context.bindService(SonyWalkmanContract.createMediaServiceIntent(),
                    connection, Context.BIND_AUTO_CREATE);
        } catch (SecurityException exception) {
            bound = false;
        }
        if (!bound) {
            Log.w(TAG, "media service bind failed; falling back");
            mainHandler.removeCallbacks(timeoutRunnable);
            worker.post(new Runnable() {
                @Override
                public void run() {
                    runFallbackTiers();
                }
            });
        }
    }

    // ------------------------------------------------------- tier 1: binder

    /** Worker thread. */
    private void runBinderTier(IBinder mediaService) {
        if (delivered) {
            return;
        }
        try {
            mediaServiceBinder = mediaService;
            IBinder player = resolveRegisteredPlayer(mediaService);
            if (player == null) {
                // Nothing registered - typically a fresh boot where the
                // player app has never run. The view-file tier is the proper
                // cold entry; later requests will find a live player.
                Log.i(TAG, "no active players; using fallback for " + request.label);
                runFallbackTiers();
                return;
            }
            playerBinder = player;
            if (clientId == null) {
                Log.w(TAG, "registration returned no client id for " + request.label
                        + "; the task engine ignores anonymous opens - falling back");
                runFallbackTiers();
                return;
            }
            addListener(player, new PlayerEventListenerStub(), clientId);
            setServerKeepAlive(player, clientId);
            Log.i(TAG, "registered client " + clientId + " on " + playerName);
            readMediaListInfo(player, "before");
            if (request.isDirectQueue()) {
                runDirectQueue(player);
                return;
            }
            // No audio-focus request: the engine takes focus itself at
            // playback start (proven live - our test holder received LOSS
            // the moment PLAY began).
            // Queues must ride the PLATFORM MediaStore: Genie serves _data
            // only to holders of a signature-level permission (it answers
            // null to everyone else), and the engine resolves the queue
            // under the submitting client's identity - so a Genie descriptor
            // from us builds a list whose every path is null and dies with
            // Error_Unknown at play. The platform provider hands _data to
            // this uid (device-proven by the view-file tier).
            platformRow = AlbumArtResolver.findPlatformRow(
                    context, request.title, request.album, request.durationMs,
                    request.trackNo);
            if (platformRow == null) {
                Log.i(TAG, "no platform row for " + request.label
                        + "; binder tier cannot carry it");
                runFallbackTiers();
                return;
            }
            boolean playing = false;
            if (isAlbumQueue() && platformRow.albumId > 0 && platformRow.trackId > 0) {
                playing = attemptAlbumQueue(player);
            } else if (isSongsQueue() && platformRow.trackId > 0) {
                playing = attemptSongsQueue(player);
            }
            if (!playing && !delivered && platformRow.trackId > 0) {
                playing = attemptPlayback(player,
                        ContentUris.withAppendedId(
                                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                                platformRow.trackId),
                        0, null, null, "platform-single");
                if (playing) {
                    Log.i(TAG, "platform single-file played (one-track queue) for "
                            + request.label);
                }
            }
            if (delivered) {
                return;
            }
            readMediaListInfo(player, "after");
            MediaServiceClient.Snapshot after = safeCurrentInfo(player);
            if (playing) {
                // The verification line: the cued track against the tapped
                // one, so a display-order/queue-order divergence shows up
                // in one logcat read.
                Log.i(TAG, "binder queue ok for " + request.label
                        + " (now trackId=" + (after == null ? "?" : after.trackId)
                        + " cued platform trackId=" + platformRow.trackId
                        + " requested genie trackId=" + request.trackId
                        + " index=" + queueIndex + ")");
                deliver(TIER_BINDER_QUEUE);
                return;
            }
            Log.w(TAG, "play() never reached the PLAY state for " + request.label);
            runFallbackTiers();
        } catch (RemoteException exception) {
            Log.w(TAG, "binder tier failed for " + request.label, exception);
            runFallbackTiers();
        } catch (RuntimeException exception) {
            Log.w(TAG, "binder tier failed for " + request.label, exception);
            runFallbackTiers();
        }
    }

    /**
     * Worker thread. The queue-next flow: open the caller's exact
     * descriptor (the same proven parcel writer), verify, play - then
     * restore the playhead with one documented seek and, when asked, the
     * paused state with the stock controller's own pause. Post-PLAY
     * restore failures are logged, never fatal: the queue itself is
     * already correct, which is the part that must not lie.
     */
    private void runDirectQueue(IBinder player) throws RemoteException {
        boolean playing = attemptPlayback(player, request.directUri,
                request.directStartIndex, request.directSortOrder,
                request.directSelection, "direct-queue");
        if (delivered) {
            return;
        }
        if (!playing) {
            Log.w(TAG, "direct queue never reached PLAY for " + request.label);
            deliver(TIER_FAILED);
            return;
        }
        if (request.seekToMs > 0) {
            TaskResult sought = seek(player, request.seekToMs);
            Log.i(TAG, "seek " + request.seekToMs + "ms -> MediaError "
                    + sought.error + " taskId=" + sought.taskId
                    + " for " + request.label);
            puntWatchdog();
        }
        if (request.restorePause) {
            TaskResult paused = pause(player);
            Log.i(TAG, "pause restore -> MediaError " + paused.error
                    + " taskId=" + paused.taskId + " for " + request.label);
            puntWatchdog();
        }
        MediaServiceClient.Snapshot after = safeCurrentInfo(player);
        Log.i(TAG, "direct queue ok for " + request.label
                + " (now trackId=" + (after == null ? "?" : after.trackId)
                + " state=" + (after == null ? "?" : after.state)
                + " playTime=" + (after == null ? "?" : after.playTimeMs)
                + " index=" + request.directStartIndex + ")");
        deliver(TIER_BINDER_QUEUE);
    }

    /** seek(positionMs, SeekOrigin.BEGIN) - txn 9, both args plain ints. */
    private TaskResult seek(IBinder player, int positionMs) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SonyWalkmanContract.PLAYER_DESCRIPTOR);
            data.writeInt(positionMs);
            data.writeInt(SonyWalkmanContract.SEEK_ORIGIN_BEGIN);
            transactInFlight = true;
            player.transact(SonyWalkmanContract.TRANSACTION_SEEK, data, reply, 0);
            transactInFlight = false;
            reply.readException();
            return readRequestResult(reply);
        } finally {
            transactInFlight = false;
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * pause() - txn 8, no arguments. Unlike bare play(), the stock
     * controller's pause IS the bare call, so this is their own shape.
     */
    private TaskResult pause(IBinder player) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SonyWalkmanContract.PLAYER_DESCRIPTOR);
            transactInFlight = true;
            player.transact(SonyWalkmanContract.TRANSACTION_PAUSE, data, reply, 0);
            transactInFlight = false;
            reply.readException();
            return readRequestResult(reply);
        } finally {
            transactInFlight = false;
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * Audio-named players first, same policy as the now-playing poll -
     * resolved WITH the registration bundle, so the service mints and
     * returns this caller's client id alongside the player binder.
     */
    private IBinder resolveRegisteredPlayer(IBinder mediaService) throws RemoteException {
        List<String> names = MediaServiceClient.getPlayers(mediaService);
        Log.i(TAG, "media service players: " + names);
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
        for (String name : ordered) {
            Bundle registration = new Bundle();
            IBinder player = MediaServiceClient.getPlayerInterface(
                    mediaService, name, registration);
            if (player != null) {
                playerName = name;
                clientId = registration.getString(
                        SonyWalkmanContract.PLAYER_CLIENT_ID_KEY);
                return player;
            }
        }
        return null;
    }

    /** addListener(stub, clientId): registration's second half. */
    private void addListener(IBinder player, Binder stub, String id)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SonyWalkmanContract.PLAYER_DESCRIPTOR);
            data.writeStrongBinder(stub);
            data.writeString(id);
            transactInFlight = true;
            player.transact(SonyWalkmanContract.TRANSACTION_ADD_LISTENER,
                    data, reply, 0);
            transactInFlight = false;
            reply.readException();
        } finally {
            transactInFlight = false;
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * setServerKeepAlive(id, true): the player must outlive this short-
     * lived client under every reading of the flag - the launcher only
     * touches the queue, it never owns the player.
     */
    private void setServerKeepAlive(IBinder player, String id) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SonyWalkmanContract.PLAYER_DESCRIPTOR);
            data.writeString(id);
            data.writeInt(1);
            transactInFlight = true;
            player.transact(SonyWalkmanContract.TRANSACTION_SET_SERVER_KEEP_ALIVE,
                    data, reply, 0);
            transactInFlight = false;
            reply.readException();
        } finally {
            transactInFlight = false;
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * The hosted IPlayerEventListenerRemote stub. The service's callbacks
     * are synchronous - it waits on every reply - so this only records,
     * logs, and returns; nothing here may block or call back into the
     * player. Every event is logged by name (the service narrates each
     * task's fate through these); onOpen is consumed by the tier, onError
     * yields its MediaError code, and onClosed/onPlay/onPause log their
     * leading int for task correlation. Any arrival re-arms the wedge
     * watchdog: a talking service is not a wedged one.
     */
    private final class PlayerEventListenerStub extends Binder {
        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            if (code == INTERFACE_TRANSACTION) {
                reply.writeString(SonyWalkmanContract.PLAYER_LISTENER_DESCRIPTOR);
                return true;
            }
            data.enforceInterface(SonyWalkmanContract.PLAYER_LISTENER_DESCRIPTOR);
            try {
                logEvent(code, data);
            } catch (RuntimeException exception) {
                // A layout surprise must never break the callback thread.
                Log.i(TAG, "event " + eventName(code) + " unparsed: " + exception);
            }
            if (reply != null) {
                reply.writeNoException();
            }
            puntWatchdog();
            return true;
        }
    }

    /** Binder thread. Reads only what each event's log line needs. */
    private void logEvent(int code, Parcel data) {
        switch (code) {
            case SonyWalkmanContract.LISTENER_TRANSACTION_ON_OPEN:
                onOpenEvent(data.readInt(), data.readInt());
                break;
            case SonyWalkmanContract.LISTENER_TRANSACTION_ON_ERROR: {
                // Layout: [hasPlayInfo, PlayInfo..., hasError, code].
                if (data.readInt() != 0) {
                    skipPlayInfo(data);
                }
                String error = data.readInt() != 0
                        ? String.valueOf(data.readInt()) : "absent";
                Log.w(TAG, "event onError: MediaError " + error
                        + " for " + request.label);
                break;
            }
            case 3: // onClosed(reason) - the service killed the list
                listClosedEvent = true;
                Log.i(TAG, "event onClosed(" + data.readInt()
                        + ") for " + request.label);
                break;
            case 4: // onPlay(taskId, ...)
            case 5: // onPause(taskId)
                Log.i(TAG, "event " + eventName(code) + "(" + data.readInt()
                        + ") for " + request.label);
                break;
            default:
                Log.i(TAG, "event " + eventName(code) + " for " + request.label);
                break;
        }
    }

    private static String eventName(int code) {
        String[] names = SonyWalkmanContract.LISTENER_EVENT_NAMES;
        return code > 0 && code < names.length ? names[code] : ("#" + code);
    }

    /**
     * Consumes one PlayInfo from an event parcel positionally - the full
     * decompiled layout: 3 ints, 1 long, 7 ints, the PlayState ordinal,
     * 16 ints, an optional 4-int Rect, then 2 ints.
     */
    private static void skipPlayInfo(Parcel data) {
        for (int i = 0; i < 3; i++) {
            data.readInt();
        }
        data.readLong();
        for (int i = 0; i < 8; i++) {
            data.readInt(); // 7 fields + the PlayState ordinal
        }
        for (int i = 0; i < 16; i++) {
            data.readInt();
        }
        if (data.readInt() != 0) {
            for (int i = 0; i < 4; i++) {
                data.readInt(); // Rect
            }
        }
        data.readInt();
        data.readInt();
    }

    /** Binder thread. Records the announcement and wakes the waiting tier. */
    private void onOpenEvent(int first, int second) {
        Log.i(TAG, "onOpen(" + first + ", " + second + ") arrived for " + request.label);
        synchronized (openEventLock) {
            openEvent = new int[]{first, second};
            openEventLock.notifyAll();
        }
    }

    /**
     * Waits for the service to announce open completion. The ints are
     * matched against the submission's taskId when they name it, but any
     * onOpen after our submission counts - the list check and the observed
     * PLAY state that follow are the truth either way.
     */
    private boolean awaitOnOpen(int taskId) {
        long deadline = SystemClock.uptimeMillis() + OPEN_SETTLE_WINDOW_MS;
        synchronized (openEventLock) {
            while (openEvent == null && !delivered) {
                long remaining = deadline - SystemClock.uptimeMillis();
                if (remaining <= 0) {
                    return false;
                }
                try {
                    openEventLock.wait(remaining);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            if (openEvent == null) {
                return false;
            }
            if (openEvent[0] != taskId && openEvent[1] != taskId) {
                Log.i(TAG, "onOpen ints " + openEvent[0] + "/" + openEvent[1]
                        + " do not name our taskId " + taskId + "; trusting arrival");
            }
            return true;
        }
    }

    /**
     * Exactly one open() per tap, shaped like the player's own descriptors
     * (the getMediaListInfo oracle): always Sony's app-module tag, never
     * the empty one; albums and playlists ride the provider's default
     * order (sort=null, byte-for-byte their album shape), songs the single
     * title sort column (their own all-songs shape). The v2.8.0 variant
     * ladder is retired: the 2026-07-20 live runs proved a shape the
     * service dislikes does not reply with a MediaError - it blocks
     * forever and wedges the whole write path - so a ladder can never
     * reach its later rungs, and each extra attempt just stacks another
     * wedged binder thread.
     */
    /**
     * One full playback attempt against one descriptor: open in the
     * player's own shape, wait for the onOpen announcement, confirm the
     * current list became this descriptor, then play. Used for the real
     * queue first and, when that dies, for the proven single-file form.
     */
    private boolean attemptPlayback(IBinder player, Uri uri, int startIndex,
                                    String sortOrder, String selection, String what)
            throws RemoteException {
        synchronized (openEventLock) {
            openEvent = null;
        }
        listClosedEvent = false;
        queueIndex = startIndex;
        TaskResult opened = open(player, uri, startIndex, selection, sortOrder,
                SonyWalkmanContract.MUSIC_LIST_TAG);
        Log.i(TAG, "open " + what + " uri=" + uri + " index=" + startIndex
                + " selection=" + selection + " sort=" + sortOrder
                + " -> MediaError " + opened.error
                + " taskId=" + opened.taskId + " for " + request.label);
        if (delivered || opened.error != SonyWalkmanContract.MEDIA_ERROR_SUCCESS) {
            return false;
        }
        if (!awaitOnOpen(opened.taskId)) {
            if (!delivered) {
                readMediaListInfo(player, "after-no-onopen");
                Log.w(TAG, "onOpen never arrived (" + what + ") for " + request.label);
            }
            return false;
        }
        MediaListInfo current = readMediaListInfo(player, "after-open");
        if (current == null || current.uri == null
                || !current.uri.toString().equals(uri.toString())
                || !equalsNullable(selection, current.selection)) {
            // A failed open task closes the list; pressing play would start
            // whatever older music survived - never do that.
            Log.w(TAG, "open completed but the list is not ours (" + what
                    + ") for " + request.label + "; not pressing play");
            return false;
        }
        return playUntilPlaying(player);
    }

    /** The tapped queue is an album when its Genie uri is albums-scoped. */
    private boolean isAlbumQueue() {
        return request.queueUri != null && request.queueUri.getPath() != null
                && request.queueUri.getPath().contains("/albums/");
    }

    /** The tapped queue is the all-songs list when its uri is the flat table. */
    private boolean isSongsQueue() {
        return SonyWalkmanContract.GENIE_AUDIO_URI.equals(request.queueUri);
    }

    /**
     * ALBUM taps: the platform album ordered by track, cued at this
     * track's position within that same ordering - never at the row's
     * position on screen. The two orders are different lists in general:
     * the browser shows Genie's album (Genie buckets by the album-name
     * string, so two discs whose tags spell the name identically become
     * one album, and a third disc whose tag has a trailing space becomes
     * another) sorted by the bare tag track number, while the platform
     * album is every disc of the set with the scanner's disc*1000+track
     * numbering - disc-major, and 45 rows where the screen showed 30.
     * Counting the index against the queue's own query is what makes the
     * tapped track exact, the same discipline the SONGS queue uses.
     */
    private boolean attemptAlbumQueue(IBinder player) throws RemoteException {
        String selection = MediaStore.Audio.Media.ALBUM_ID + "=" + platformRow.albumId;
        int index = platformAlbumIndex(selection, platformRow.trackId);
        if (index < 0) {
            Log.i(TAG, "album index unresolved for " + request.label
                    + "; using the one-track form");
            return false;
        }
        if (index != request.startIndex) {
            Log.i(TAG, "album index " + request.startIndex + " (displayed) -> "
                    + index + " (platform album " + platformRow.albumId + ") for "
                    + request.label);
        }
        return attemptPlayback(player, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                index, MediaStore.Audio.Media.TRACK, selection, "platform-album");
    }

    /**
     * The track's position under {@code ORDER BY track} within the album
     * the queue selects: SQLite's own order - NULL track first, then
     * ascending, rowid breaking ties. -1 when unresolved.
     */
    private int platformAlbumIndex(String albumSelection, long platformTrackId) {
        int trackNo = -1;
        boolean trackKnown = false;
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, platformTrackId),
                    new String[]{MediaStore.Audio.Media.TRACK}, null, null, null);
            if (cursor == null || !cursor.moveToFirst()) {
                return -1;
            }
            trackKnown = !cursor.isNull(0);
            trackNo = trackKnown ? cursor.getInt(0) : -1;
        } catch (RuntimeException exception) {
            return -1;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        String track = MediaStore.Audio.Media.TRACK;
        String id = MediaStore.Audio.Media._ID;
        String before = trackKnown
                ? track + " IS NULL OR " + track + " < " + trackNo + " OR ("
                        + track + " = " + trackNo + " AND " + id + " < " + platformTrackId + ")"
                : track + " IS NULL AND " + id + " < " + platformTrackId;
        cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Audio.Media._ID},
                    albumSelection + " AND (" + before + ")", null, null);
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
     * SONGS taps: the whole platform music table ordered by title_key,
     * cued at this track's position within that same ordering. The
     * displayed Genie collation can diverge from platform title_key on
     * edge cases, but the tapped track is exact because the index is
     * counted against the queue's own query.
     */
    private boolean attemptSongsQueue(IBinder player) throws RemoteException {
        int index = platformSongsIndex(platformRow.trackId);
        if (index < 0) {
            Log.i(TAG, "songs index unresolved for " + request.label
                    + "; using the one-track form");
            return false;
        }
        return attemptPlayback(player, MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                index, MediaStore.Audio.Media.TITLE_KEY,
                MediaStore.Audio.Media.IS_MUSIC + "=1", "platform-songs");
    }

    /**
     * The track's position under {@code ORDER BY title_key} (rowid ties),
     * counted with the same selection the queue runs. -1 when unresolved.
     */
    private int platformSongsIndex(long platformTrackId) {
        String titleKey = null;
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, platformTrackId),
                    new String[]{MediaStore.Audio.Media.TITLE_KEY}, null, null, null);
            if (cursor == null || !cursor.moveToFirst() || cursor.isNull(0)) {
                return -1;
            }
            titleKey = cursor.getString(0);
        } catch (RuntimeException exception) {
            return -1;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[]{MediaStore.Audio.Media._ID},
                    MediaStore.Audio.Media.IS_MUSIC + "=1 AND ("
                            + MediaStore.Audio.Media.TITLE_KEY + " < ? OR ("
                            + MediaStore.Audio.Media.TITLE_KEY + " = ? AND "
                            + MediaStore.Audio.Media._ID + " < ?))",
                    new String[]{titleKey, titleKey, String.valueOf(platformTrackId)},
                    null);
            return cursor == null ? -1 : cursor.getCount();
        } catch (RuntimeException exception) {
            return -1;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private static boolean equalsNullable(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * IPlayerRemote.open(PlayListSelectionInfo, true). The parcel mirrors the
     * firmware library's writeToParcel field for field; selectionArgs are
     * deliberately absent (the service-side reader cannot carry them) and the
     * column-name triple is the library's own default. Returns the reply's
     * MediaError code.
     */
    private TaskResult open(IBinder player, Uri uri, int startIndex,
                            String selection, String sortOrder, String tag)
            throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SonyWalkmanContract.PLAYER_DESCRIPTOR);
            data.writeInt(1);                                // info present
            data.writeInt(1);                                // uri present
            uri.writeToParcel(data, 0);
            data.writeInt(startIndex);                       // start index
            if (selection != null) {                         // selection
                data.writeInt(1);
                data.writeString(selection);
            } else {
                data.writeInt(0);
            }
            if (sortOrder != null) {                         // sort order
                data.writeInt(1);
                data.writeString(sortOrder);
            } else {
                data.writeInt(0);
            }
            data.writeInt(0);                                // selectionArgs: absent
            data.writeInt(1);                                // tag
            data.writeString(tag);
            data.writeInt(0);                                // proxy host: null
            data.writeInt(0);                                // proxy port
            data.writeInt(1);                                // column names
            data.writeStringArray(SonyWalkmanContract.PLAY_LIST_INFO_COLUMNS);
            data.writeInt(0);                                // startIndex enabled
            data.writeInt(1);                                // open(.., true)
            transactInFlight = true;
            player.transact(SonyWalkmanContract.TRANSACTION_OPEN, data, reply, 0);
            transactInFlight = false;
            reply.readException();
            return readRequestResult(reply);
        } finally {
            transactInFlight = false;
            reply.recycle();
            data.recycle();
        }
    }

    /**
     * The wedge watchdog re-armed: every answered transact is progress, so
     * the 8 s brake measures silence, not total tier time - the settle
     * loops stay free to use their own bounded windows.
     */
    private void puntWatchdog() {
        mainHandler.removeCallbacks(timeoutRunnable);
        if (!delivered) {
            mainHandler.postDelayed(timeoutRunnable, BINDER_WATCHDOG_MS);
        }
    }

    /**
     * One playWithParam submission - the stock controller's own pattern -
     * then watch getCurrentInfo until the player actually reports PLAY.
     * Never resubmitted: v2.9.4 proved that hammering play files failing
     * tasks, and a failing task closes the freshly opened list.
     */
    private boolean playUntilPlaying(IBinder player) throws RemoteException {
        TaskResult submitted = playWithParam(player);
        Log.i(TAG, "playWithParam -> MediaError " + submitted.error
                + " taskId=" + submitted.taskId + " for " + request.label);
        if (submitted.error == SonyWalkmanContract.MEDIA_ERROR_EMPTY_PLAYLIST) {
            return false;
        }
        long deadline = SystemClock.uptimeMillis() + PLAY_SETTLE_WINDOW_MS;
        int lastState = Integer.MIN_VALUE;
        long lastTrack = Long.MIN_VALUE;
        while (SystemClock.uptimeMillis() < deadline && !delivered) {
            MediaServiceClient.Snapshot now = safeCurrentInfo(player);
            puntWatchdog();
            if (now != null && (now.state != lastState || now.trackId != lastTrack)) {
                // The watch narrated: every state/track flip is one line.
                Log.i(TAG, "watch: state " + now.state + " trackId " + now.trackId
                        + " for " + request.label);
                lastState = now.state;
                lastTrack = now.trackId;
            }
            if (now != null && now.state == SonyWalkmanContract.PLAY_STATE_PLAY) {
                Log.i(TAG, "PLAY observed for " + request.label);
                return true;
            }
            if (listClosedEvent) {
                // The service closed the list; nothing left to wait for.
                Log.i(TAG, "list closed by the service; abandoning the watch for "
                        + request.label);
                return false;
            }
            SystemClock.sleep(SETTLE_STEP_MS);
        }
        Log.i(TAG, "PLAY never observed inside the window for " + request.label);
        return false;
    }

    /**
     * The stock controller's play - playWithParam(PlaySpeed(1, 1),
     * muted=false, fadeInTime=-1), txn 7, exactly PlaybackController
     * .play() in the decompile. The bare play() txn 6 is a trap here:
     * accepted, its task then fails against the fresh queue and the
     * failure closes the list.
     */
    private TaskResult playWithParam(IBinder player) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SonyWalkmanContract.PLAYER_DESCRIPTOR);
            data.writeInt(1);   // PlaySpeed present
            data.writeInt(1);   // numerator: normal speed
            data.writeInt(1);   // denominator
            data.writeInt(0);   // muted: false
            data.writeInt(-1);  // fadeInTime: the controller's default
            transactInFlight = true;
            player.transact(SonyWalkmanContract.TRANSACTION_PLAY_WITH_PARAM,
                    data, reply, 0);
            transactInFlight = false;
            reply.readException();
            return readRequestResult(reply);
        } finally {
            transactInFlight = false;
            reply.recycle();
            data.recycle();
        }
    }

    /** A RequestResult reply: the queued task's id plus the submission error. */
    private static final class TaskResult {
        final int taskId;
        final int error;

        TaskResult(int taskId, int error) {
            this.taskId = taskId;
            this.error = error;
        }
    }

    /** RequestResult reply: flag, taskId, MediaError code. */
    private static TaskResult readRequestResult(Parcel reply) {
        if (reply.readInt() == 0) {
            // A null RequestResult is the remote's own illegal-state marker.
            return new TaskResult(-1, -1);
        }
        int taskId = reply.readInt();
        return new TaskResult(taskId, reply.readInt());
    }

    /**
     * open(.., true) may have started playback on its own; a play() that then
     * reports an invalid state is not a failure. getCurrentInfo (txn 27, the
     * poll's own read) settles it and names the cued track.
     */
    private static MediaServiceClient.Snapshot safeCurrentInfo(IBinder player) {
        try {
            return MediaServiceClient.readCurrentInfo(player);
        } catch (RemoteException exception) {
            return null;
        } catch (RuntimeException exception) {
            return null;
        }
    }

    /** The player's current list descriptor, as far as the settle gate needs. */
    private static final class MediaListInfo {
        final Uri uri;
        final int index;
        final String selection;

        MediaListInfo(Uri uri, int index, String selection) {
            this.uri = uri;
            this.index = index;
            this.selection = selection;
        }
    }

    /**
     * One read of the player's own current list descriptor
     * (getMediaListInfo, txn 22, read-only) - the research oracle when
     * {@code moment} is set (logs Sony's native uri/sort format next to
     * ours) and the settle gate's probe when it is null. Never fatal;
     * null when there is no list or the read failed.
     */
    private MediaListInfo readMediaListInfo(IBinder player, String moment) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SonyWalkmanContract.PLAYER_DESCRIPTOR);
            player.transact(SonyWalkmanContract.TRANSACTION_GET_MEDIA_LIST_INFO,
                    data, reply, 0);
            reply.readException();
            if (reply.readInt() == 0) {
                if (moment != null) {
                    Log.i(TAG, "media list " + moment + ": none");
                }
                return null;
            }
            Uri uri = reply.readInt() != 0 ? (Uri) Uri.CREATOR.createFromParcel(reply) : null;
            int index = reply.readInt();
            String selection = reply.readInt() != 0 ? reply.readString() : null;
            String sortOrder = reply.readInt() != 0 ? reply.readString() : null;
            if (reply.readInt() != 0) {
                reply.createStringArray(); // selectionArgs, not reported
            }
            String tag = reply.readInt() != 0 ? reply.readString() : null;
            if (moment != null) {
                Log.i(TAG, "media list " + moment + ": uri=" + uri + " index=" + index
                        + " selection=" + selection + " sort=" + sortOrder + " tag=" + tag);
            }
            return new MediaListInfo(uri, index, selection);
        } catch (RemoteException exception) {
            if (moment != null) {
                Log.i(TAG, "media list " + moment + ": unreadable (" + exception + ")");
            }
            return null;
        } catch (RuntimeException exception) {
            if (moment != null) {
                Log.i(TAG, "media list " + moment + ": unreadable (" + exception + ")");
            }
            return null;
        } finally {
            reply.recycle();
            data.recycle();
        }
    }

    // -------------------------------------------------- tiers 2 and 3: intents

    /** Worker or watchdog thread. View-file tier, then the app-only tier. */
    private void runFallbackTiers() {
        if (delivered) {
            return;
        }
        synchronized (this) {
            // One claimant only: a binder failure and the watchdog racing
            // here must not both fire an intent.
            if (fallbackClaimed) {
                return;
            }
            fallbackClaimed = true;
        }
        if (request.isDirectQueue()) {
            // A queue edit either lands whole on the binder or reports
            // failure; the intent tiers would replace the queue with one
            // file in the Sony UI - worse than doing nothing.
            Log.w(TAG, "binder tier unavailable for " + request.label
                    + "; queue requests take no intent fallback");
            deliver(TIER_FAILED);
            return;
        }
        probeGenieData();
        AlbumArtResolver.PlatformRow row = platformRow != null ? platformRow
                : AlbumArtResolver.findPlatformRow(
                        context, request.title, request.album, request.durationMs,
                        request.trackNo);
        if (row != null && row.dataPath != null) {
            probeFileAccess(row.dataPath);
            Intent view = SonyWalkmanContract.createViewFileIntent(
                    row.dataPath, request.mimeType);
            view.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                context.startActivity(view);
                Log.i(TAG, "view-file handoff for " + request.label
                        + " (" + row.dataPath + ")");
                deliver(TIER_VIEW_FILE);
                return;
            } catch (ActivityNotFoundException exception) {
                Log.w(TAG, "scan-and-play activity missing");
            } catch (SecurityException exception) {
                Log.w(TAG, "scan-and-play activity rejected the intent", exception);
            }
        } else {
            Log.i(TAG, "no platform path for " + request.label
                    + "; cannot use the view-file tier");
        }
        Intent top = SonyWalkmanContract.createMusicIntent(
                SonyWalkmanContract.ACTION_START_TOP);
        top.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (top.resolveActivity(context.getPackageManager()) != null) {
            try {
                context.startActivity(top);
                Log.i(TAG, "app-only handoff for " + request.label);
                deliver(TIER_APP_ONLY);
                return;
            } catch (ActivityNotFoundException exception) {
                // Fall through to the failure report.
            }
        }
        Log.w(TAG, "no handoff mechanism available for " + request.label);
        deliver(TIER_FAILED);
    }

    // ------------------------------------------------------------- lifecycle

    /** Any thread. Reports once, then tears the instance down. */
    private void deliver(final int tier) {
        synchronized (this) {
            if (delivered) {
                return;
            }
            delivered = true;
        }
        mainHandler.removeCallbacks(timeoutRunnable);
        synchronized (openEventLock) {
            // Wake a tier still waiting on onOpen; it re-checks delivered.
            openEventLock.notifyAll();
        }
        // The quit below is lazy for a worker still wedged in a transact:
        // the thread is abandoned and exits if the service ever answers.
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (playerBinder != null && clientId != null) {
                    final IBinder player = playerBinder;
                    final IBinder service = mediaServiceBinder;
                    final String id = clientId;
                    final String name = playerName;
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            teardownRegistration(player, service, id, name);
                        }
                    }, "zx1-handoff-teardown").start();
                }
                if (bound) {
                    try {
                        context.unbindService(connection);
                    } catch (IllegalArgumentException ignored) {
                        // Already released with a dead process.
                    }
                    bound = false;
                }
                HandlerThread thread = workerThread;
                workerThread = null;
                worker = null;
                if (thread != null) {
                    thread.quit();
                }
                listener.onHandoff(tier);
            }
        });
    }

    /**
     * One diagnostic line: what does the engine's own default projection
     * ({_id, _data, mime_type}) return from Genie for this track under our
     * uid? Closes the does-Genie-even-serve-a-path question.
     */
    private void probeGenieData() {
        if (request.trackId <= 0) {
            return;
        }
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    ContentUris.withAppendedId(
                            SonyWalkmanContract.GENIE_AUDIO_URI, request.trackId),
                    SonyWalkmanContract.PLAY_LIST_INFO_COLUMNS, null, null, null);
            if (cursor == null || !cursor.moveToFirst()) {
                Log.i(TAG, "genie _data probe: no row for " + request.trackId);
                return;
            }
            int dataColumn = cursor.getColumnIndex("_data");
            Log.i(TAG, "genie _data probe: columns="
                    + java.util.Arrays.toString(cursor.getColumnNames())
                    + " _data=" + (dataColumn < 0 ? "<no column>"
                    : cursor.getString(dataColumn)));
        } catch (RuntimeException exception) {
            Log.i(TAG, "genie _data probe failed: " + exception);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * One diagnostic line: can THIS uid read the music file directly? The
     * binder-queue play task dies with Error_Unknown while the same file
     * plays from Sony's own process - if the engine opens media under the
     * submitting client's credentials, this line names the culprit
     * (unreadable = storage permission enforced on this firmware).
     */
    private static void probeFileAccess(String path) {
        java.io.FileInputStream in = null;
        try {
            java.io.File file = new java.io.File(path);
            in = new java.io.FileInputStream(file);
            byte[] first = new byte[16];
            int count = in.read(first);
            Log.i(TAG, "file probe: canRead=" + file.canRead() + " read=" + count
                    + " bytes of " + file.length() + " (" + path + ")");
        } catch (java.io.IOException exception) {
            Log.w(TAG, "file probe: unreadable (" + exception + ")");
        } catch (RuntimeException exception) {
            Log.w(TAG, "file probe: unreadable (" + exception + ")");
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (java.io.IOException ignored) {
                    // Probe only.
                }
            }
        }
    }

    /**
     * Sony's own teardown order (Player.close): removeListener, then
     * releasePlayerInterface. Best effort off the main thread - the player
     * itself keeps running (setServerKeepAlive true); a dead service just
     * means there is nothing left to unregister from.
     */
    private void teardownRegistration(IBinder player, IBinder mediaService,
                                      String id, String name) {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SonyWalkmanContract.PLAYER_DESCRIPTOR);
            data.writeString(id);
            player.transact(SonyWalkmanContract.TRANSACTION_REMOVE_LISTENER,
                    data, reply, 0);
            reply.readException();
        } catch (RemoteException ignored) {
            // Service gone; nothing to unregister.
        } catch (RuntimeException ignored) {
            // Never let teardown take the launcher down.
        } finally {
            reply.recycle();
            data.recycle();
        }
        if (mediaService == null) {
            return;
        }
        data = Parcel.obtain();
        reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SonyWalkmanContract.MEDIA_SERVICE_DESCRIPTOR);
            data.writeString(name);
            mediaService.transact(
                    SonyWalkmanContract.TRANSACTION_RELEASE_PLAYER_INTERFACE,
                    data, reply, 0);
            reply.readException();
        } catch (RemoteException ignored) {
            // Service gone; nothing to release.
        } catch (RuntimeException ignored) {
            // Never let teardown take the launcher down.
        } finally {
            reply.recycle();
            data.recycle();
        }
    }
}
