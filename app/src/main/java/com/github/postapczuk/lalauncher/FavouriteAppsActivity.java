/*
 * Light Android Launcher
 * Modified 2026-07-17: rebuilt as a late-1990s Sony-inspired NW-ZX1 shell.
 * Modified 2026-07-19: minimal chassis - hardware keys own transport, the
 * glass owns everything else, with a dithered idle art panel; now-playing
 * read from the firmware player's binder (no broadcast exists); the panel
 * becomes the current track's cover - LCD-tone greyscale - when real art is
 * readable (B6).
 * Copyright for the original project remains with its contributors.
 * Licensed under GPLv2; see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.media.AudioManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The HOME activity. It deliberately uses platform views only so the launcher
 * remains small and runs on the NW-ZX1's Android 4.1 / API 16 firmware.
 * Transport and volume are the ZX1's physical side keys; the screen keeps
 * only what touch must do.
 */
public class FavouriteAppsActivity extends Activity implements View.OnClickListener {

    // Plasma raster: ~2dp cells at the panel's on-device size (298dp wide,
    // ~173dp tall in the 480x782 app window), stretched by the ImageView with
    // filtering off so the dither stays square-pixel sharp. Since v2.6.3 the
    // cover renders at the panel's native pixel size instead (the smooth
    // greyscale has no dither cell to preserve); these constants are the
    // plasma's, and the resolver's pre-layout fallback.
    static final int PANEL_W = 146;
    static final int PANEL_H = 85;
    private static final long PANEL_FRAME_MS = 40L;
    static final int PANEL_INK = 0xFF17231C;
    static final int PANEL_GLASS = 0xFFAAB49D;
    static final int[] BAYER4 = {
            0, 8, 2, 10,
            12, 4, 14, 6,
            3, 11, 1, 9,
            15, 7, 13, 5
    };
    private static final int SIN_SIZE = 1024;
    private static final float SIN_SCALE = (float) (SIN_SIZE / (2d * Math.PI));
    private static final float[] SIN = new float[SIN_SIZE];

    static {
        for (int i = 0; i < SIN_SIZE; i++) {
            SIN[i] = (float) Math.sin(i * 2d * Math.PI / SIN_SIZE);
        }
    }

    private final Handler clockHandler = new Handler();
    private TextView clockView;
    private TextView batteryView;
    private TextView lcdStateView;
    private ScrollingLcdText lcdTitleView;
    private TextView lcdArtistView;
    private TextView lcdQualityView;
    private TextView lcdYearView;
    private TextView lcdHrView;
    private TextView lcdTimeView;
    private TransportGlyphView lcdGlyphView;
    private TextView footerOutputView;
    private ImageView panelView;
    private View infoWindowView;
    private View spectrumBarsView;
    private TextView windowClockView;
    // The glass that gives way while the music volume is out of reach, and
    // the storage panel that takes its place.
    private View trackLinesView;
    private View libraryKeyView;
    private View playlistsKeyView;
    private View storagePanelView;
    private StoragePanel storagePanel;
    /** The storage phase last applied is not READY (the panel holds the glass). */
    private boolean storageBlocked;
    /** StorageState.outagesEnded() as of the covers on the glass. */
    private int storageOutagesSeen;
    // The offline-envelope spectrum (B7b): the renderer replaying stored
    // analysis at the playhead, and the per-track analysis-request dedup.
    private SpectrumController spectrum;
    private long spectrumRequestedId;
    private int spectrumRequestedDuration;
    // The applied Settings choices; onResume reconciles them against the
    // store (the SETTINGS screen may have changed them while home was paused).
    private Skin skin;
    private int artStyle;
    private int infoMode;
    // The plasma's two tones come from the active style (ink on glass, or
    // amber on black under TUBE AMP).
    private int plasmaInk;
    private int plasmaGlass;
    private MediaServiceClient mediaClient;
    private MediaServiceClient.Snapshot nowPlaying;
    // The last real track lines shown - they hold on the glass (a 1999 deck
    // keeps its display when you stop the disc). heldTrackId marks which
    // track they belong to so a new-but-unreadable track never wears them.
    private long heldTrackId;
    private String heldTitle;
    private String heldArtist;
    private String heldQuality;
    private String heldYear;
    private String heldTime;
    private boolean heldHiRes;
    // The dithered cover belonging to heldTrackId (B6), or null when that
    // track has no readable art - then the plasma owns the panel. Covers are
    // resolved on a private worker thread and cached for the current and
    // previous track so skipping back swaps instantly.
    private Bitmap heldCover;
    private HandlerThread artThread;
    private Handler artHandler;
    private AlbumArtResolver artResolver;
    private long artPendingTrackId;
    /** Bumped when the artwork style changes so in-flight renders are stale. */
    private int artGeneration;
    private final long[] artCacheIds = new long[2];
    private final Bitmap[] artCacheFrames = new Bitmap[2];
    private Bitmap panelBitmap;
    private Bitmap panelShowing;
    private boolean plasmaRunning;
    private boolean resumed;
    private int[] panelPixels;
    private float[] panelPhaseA;
    private float[] panelPhaseB;
    private float[] panelPhaseC;
    private float[] panelPhaseD;
    private float[] panelLimit;
    private long panelStart;
    private boolean batteryReceiverRegistered;
    // Guarded because the style-change recreate() leaves onResume before
    // registration, yet onPause still runs on the way down.
    private boolean headsetReceiverRegistered;

    private final Runnable clockTick = new Runnable() {
        @Override
        public void run() {
            updateClock();
            clockHandler.postDelayed(this, 30000L);
        }
    };

    private final Runnable panelTick = new Runnable() {
        @Override
        public void run() {
            renderPlasmaFrame();
            clockHandler.postDelayed(this, PANEL_FRAME_MS);
        }
    };

    private final StorageState.Listener storageListener = new StorageState.Listener() {
        @Override
        public void onStorageChanged(StorageState.Snapshot snapshot) {
            applyStorage(snapshot);
        }
    };

    /**
     * ACTION_HEADSET_PLUG is sticky, so registration delivers the current
     * jack state immediately - the footer always reports the real route.
     */
    private final BroadcastReceiver headsetReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean wired = intent.getIntExtra("state", 0) == 1;
            footerOutputView.setText(wired ? "HEADPHONES  •  24 BIT" : "SPEAKER  •  24 BIT");
        }
    };

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
            if (level >= 0 && scale > 0) {
                String label = status == BatteryManager.BATTERY_STATUS_CHARGING ? "CHG" : "BATT";
                batteryView.setText(String.format(Locale.US, "%s %02d%%", label, level * 100 / scale));
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LOW_PROFILE);
        setContentView(R.layout.activity_favourites);
        skin = Skin.of(this);
        artStyle = LauncherSettings.getArtworkStyle(this);
        infoMode = LauncherSettings.getInfoScreen(this);
        plasmaInk = skin.artDark;
        plasmaGlass = skin.artLight;

        clockView = (TextView) findViewById(R.id.lcd_clock);
        batteryView = (TextView) findViewById(R.id.lcd_battery);
        lcdStateView = (TextView) findViewById(R.id.lcd_state);
        lcdTitleView = (ScrollingLcdText) findViewById(R.id.lcd_title);
        lcdArtistView = (TextView) findViewById(R.id.lcd_artist);
        lcdQualityView = (TextView) findViewById(R.id.lcd_quality);
        lcdYearView = (TextView) findViewById(R.id.lcd_year);
        lcdHrView = (TextView) findViewById(R.id.lcd_hr);
        lcdTimeView = (TextView) findViewById(R.id.lcd_time);
        lcdGlyphView = (TransportGlyphView) findViewById(R.id.lcd_glyph);
        footerOutputView = (TextView) findViewById(R.id.footer_output);
        panelView = (ImageView) findViewById(R.id.lcd_panel);
        infoWindowView = findViewById(R.id.info_window);
        spectrumBarsView = findViewById(R.id.spectrum_bars);
        windowClockView = (TextView) findViewById(R.id.window_clock);
        spectrum = new SpectrumController(this, (ViewGroup) spectrumBarsView,
                getResources().getDisplayMetrics().density);
        trackLinesView = findViewById(R.id.lcd_track_lines);
        libraryKeyView = findViewById(R.id.action_library);
        playlistsKeyView = findViewById(R.id.action_playlists);
        storagePanelView = findViewById(R.id.storage_panel);
        storagePanel = new StoragePanel(storagePanelView, skin);

        int[] controls = {R.id.action_library, R.id.action_playlists, R.id.action_apps};
        for (int control : controls) {
            findViewById(control).setOnClickListener(this);
        }

        applySkin();
        applyInfoWindow();

        mediaClient = new MediaServiceClient(this, new MediaServiceClient.Listener() {
            @Override
            public void onNowPlaying(MediaServiceClient.Snapshot snapshot) {
                applyNowPlaying(snapshot);
            }
        });

        // Cover decode/dither work runs off the main thread (B6); the thread
        // lives with the activity, but jobs are only posted while resumed.
        artThread = new HandlerThread("zx1-art");
        artThread.start();
        artHandler = new Handler(artThread.getLooper());
        artResolver = new AlbumArtResolver(this);

        // Pre-warm the library snapshot so the MUSIC LIBRARY soft-key
        // opens instantly (C1); a one-shot thread, nothing kept.
        final android.content.Context applicationContext = getApplicationContext();
        new Thread(new Runnable() {
            @Override
            public void run() {
                LibraryStore.loadCached(applicationContext);
            }
        }, "zx1-library-warm").start();

        initPlasmaPanel();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (Skin.restyleIfChanged(this, skin)) {
            // Style changed on the SETTINGS screen; the re-inflate under it
            // is posted, so nothing below is worth starting.
            return;
        }
        int wantedArt = LauncherSettings.getArtworkStyle(this);
        if (wantedArt != artStyle) {
            artStyle = wantedArt;
            flushArtCache();
        }
        int wantedInfo = LauncherSettings.getInfoScreen(this);
        if (wantedInfo != infoMode) {
            infoMode = wantedInfo;
            applyInfoWindow();
        }
        resumed = true;
        // The storage phase first: it decides whether the now-playing glass
        // (and with it the title glide and the plasma) is there at all.
        StorageState.addListener(storageListener);
        applyStorage(StorageState.current(this));
        spectrum.setActive(infoMode == LauncherSettings.INFO_SPECTRUM);
        clockHandler.removeCallbacks(clockTick);
        clockHandler.post(clockTick);
        // The held track lines persist across pauses; the momentary snapshot
        // does not - render the honest fallback until the first poll lands.
        // renderPlaybackState() also restarts the panel (cover or plasma).
        nowPlaying = null;
        renderPlaybackState();
        mediaClient.start();
        registerReceiver(headsetReceiver, new IntentFilter(Intent.ACTION_HEADSET_PLUG));
        headsetReceiverRegistered = true;
        if (!batteryReceiverRegistered) {
            registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            batteryReceiverRegistered = true;
        }
    }

    @Override
    protected void onPause() {
        resumed = false;
        StorageState.removeListener(storageListener);
        lcdTitleView.setActive(false);
        spectrum.setActive(false);
        clockHandler.removeCallbacks(clockTick);
        stopPlasma();
        mediaClient.stop();
        if (headsetReceiverRegistered) {
            unregisterReceiver(headsetReceiver);
            headsetReceiverRegistered = false;
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (batteryReceiverRegistered) {
            unregisterReceiver(batteryReceiver);
            batteryReceiverRegistered = false;
        }
        if (artThread != null) {
            artHandler.removeCallbacksAndMessages(null);
            artThread.quit();
            artThread = null;
            artHandler = null;
        }
        super.onDestroy();
    }

    /**
     * Home is the bottom of the stack, so back stays put, as on the stock
     * launchers. The default finish() would surface whatever task sits
     * behind - once a system Settings screen, which then took home's taps.
     */
    @Override
    public void onBackPressed() {
    }

    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.action_library:
                // C1: the launcher's own library browser, not the Sony app.
                launch(new Intent(this, MusicLibraryActivity.class));
                overridePendingTransition(R.anim.slide_up, android.R.anim.fade_out);
                break;
            case R.id.action_playlists:
                // C2: the launcher's own playlists - stored as platform
                // playlists, so they are real queues - with Sony's read-only
                // lists alongside. Their dashboard is no longer involved.
                launch(PlaylistsActivity.createIntent(this, false));
                overridePendingTransition(R.anim.slide_up, android.R.anim.fade_out);
                break;
            case R.id.action_apps:
                launch(new Intent(this, InstalledAppsActivity.class));
                overridePendingTransition(R.anim.slide_up, android.R.anim.fade_out);
                break;
            default:
                break;
        }
    }

    private void updateClock() {
        String now = new SimpleDateFormat("HH:mm", Locale.US).format(new Date());
        clockView.setText(now);
        if (infoMode == LauncherSettings.INFO_CLOCK) {
            windowClockView.setText(now);
        }
    }

    /**
     * TUBE AMP re-tint of the inflated home; on LCD the resources already
     * match and nothing is rebuilt in code.
     */
    private void applySkin() {
        if (skin.isLcd()) {
            return;
        }
        Skin.paint(findViewById(R.id.home_root), skin.chassis);
        ((TextView) findViewById(R.id.brand_mark)).setTextColor(skin.panelText);
        ((TextView) findViewById(R.id.brand_line1)).setTextColor(skin.panelText);
        ((TextView) findViewById(R.id.brand_line2)).setTextColor(skin.panelDim);
        ViewGroup bars = (ViewGroup) spectrumBarsView;
        for (int i = 0; i < bars.getChildCount(); i++) {
            Skin.paint(bars.getChildAt(i), skin.ink);
        }
        findViewById(R.id.lcd_zone).setBackground(skin.frameBackground());
        lcdStateView.setTextColor(skin.ink);
        batteryView.setTextColor(skin.ink);
        clockView.setTextColor(skin.ink);
        Skin.paint(findViewById(R.id.lcd_divider), skin.inkDim);
        lcdTitleView.setTextColor(skin.ink);
        lcdGlyphView.setTextColor(skin.ink);
        lcdArtistView.setTextColor(skin.inkDim);
        lcdQualityView.setTextColor(skin.ink);
        lcdYearView.setTextColor(skin.inkDim);
        Skin.paint(lcdHrView, skin.ink);
        lcdHrView.setTextColor(skin.glass);
        lcdTimeView.setTextColor(skin.ink);
        panelView.setBackground(skin.panelFrame());
        TextView library = (TextView) findViewById(R.id.action_library);
        TextView playlists = (TextView) findViewById(R.id.action_playlists);
        library.setBackground(skin.softKeyBackground());
        library.setTextColor(skin.softKeyText());
        playlists.setBackground(skin.softKeyBackground());
        playlists.setTextColor(skin.softKeyText());
        TextView latch = (TextView) findViewById(R.id.action_apps);
        latch.setBackground(skin.latchBackground());
        latch.setTextColor(skin.latchText());
        ((TextView) findViewById(R.id.footer_left)).setTextColor(skin.panelDim);
        footerOutputView.setTextColor(skin.accent);
    }

    /**
     * Settings > Info Screen. SPECTRUM keeps the chassis window with the
     * flat baseline; CLOCK re-cuts the same rectangle in the screen colors
     * and shows the real time (updated by the same clock tick).
     */
    private void applyInfoWindow() {
        if (infoMode == LauncherSettings.INFO_CLOCK) {
            infoWindowView.setBackground(skin.clockWindowBackground());
            spectrumBarsView.setVisibility(View.GONE);
            windowClockView.setTextColor(skin.ink);
            windowClockView.setVisibility(View.VISIBLE);
            updateClock();
        } else {
            if (skin.isLcd()) {
                infoWindowView.setBackgroundResource(R.drawable.spectrum_window);
            } else {
                infoWindowView.setBackground(skin.infoWindowBackground());
            }
            windowClockView.setVisibility(View.GONE);
            spectrumBarsView.setVisibility(View.VISIBLE);
        }
    }

    /**
     * The storage phase ({@link StorageState}). Anything but READY re-cuts
     * the glass in place, the way the info window's CLOCK does: the status
     * row and the PROGRAM INDEX latch stay, and everything between them -
     * the track lines, the art panel, both soft-keys - gives way to the
     * storage panel, since none of it can be honest or useful while the
     * music is out of reach. The title glide and the plasma stop with the
     * glass they animate. Back at READY the now-playing glass returns, and
     * the cover cache is flushed after any outage - including one that
     * happened entirely while home was paused ({@link
     * StorageState#outagesEnded}).
     */
    private void applyStorage(StorageState.Snapshot snapshot) {
        boolean blocked = snapshot.phase != StorageState.Phase.READY;
        boolean wasBlocked = storageBlocked;
        if (blocked) {
            storagePanel.render(snapshot);
        }
        if (blocked != storageBlocked) {
            storageBlocked = blocked;
            int glass = blocked ? View.GONE : View.VISIBLE;
            trackLinesView.setVisibility(glass);
            panelView.setVisibility(glass);
            libraryKeyView.setVisibility(glass);
            playlistsKeyView.setVisibility(glass);
            storagePanelView.setVisibility(blocked ? View.VISIBLE : View.GONE);
            if (blocked) {
                stopPlasma();
                // The analyzer yields a running pass when storage goes away
                // (partial kept, nothing marked failed), so forget the last
                // request - as CLOCK does - and the playing track is asked
                // for again on the first PLAY poll once the music is back.
                spectrumRequestedId = 0;
                spectrumRequestedDuration = 0;
            }
        }
        if (!blocked && storageOutagesSeen != StorageState.outagesEnded()) {
            // Back from a stretch out of reach - heard live, or one that
            // began and ended while home was paused - so a cover that read
            // as absent then may be readable now, or replaced over USB.
            storageOutagesSeen = StorageState.outagesEnded();
            flushArtCache();
        }
        if (!blocked && wasBlocked) {
            renderPlaybackState();
        }
        lcdTitleView.setActive(resumed && !storageBlocked);
    }

    /**
     * Covers rendered so far are stale: the artwork style changed, or the
     * music volume is back after a stretch out of reach.
     */
    private void flushArtCache() {
        artGeneration++;
        for (int slot = 0; slot < artCacheIds.length; slot++) {
            artCacheIds[slot] = 0;
            artCacheFrames[slot] = null;
        }
        artPendingTrackId = 0;
        heldCover = null;
    }

    /**
     * Takes one poll result from the binder client and folds it into the
     * held display state. Track lines update only when the player names a
     * real track; when it names one whose Genie row cannot be read, the
     * lines go dark rather than wear the previous track's metadata.
     */
    private void applyNowPlaying(MediaServiceClient.Snapshot snapshot) {
        nowPlaying = snapshot;
        if (snapshot != null && snapshot.trackId > 0) {
            if (snapshot.track != null) {
                MediaServiceClient.Track track = snapshot.track;
                heldTrackId = snapshot.trackId;
                heldTitle = track.title == null ? null : track.title.toUpperCase(Locale.US);
                heldArtist = track.artist == null ? null : track.artist.toUpperCase(Locale.US);
                heldQuality = buildQualityLine(track);
                heldHiRes = track.bitsPerSample > 16 || track.sampleRate > 48000;
                // Only a plausible four-digit year is shown; anything else in
                // the column is not a year and stays off the glass.
                heldYear = track.year >= 1000 && track.year <= 9999
                        ? String.valueOf(track.year) : null;
                heldCover = coverForTrack(snapshot.trackId, track);
            } else if (heldTrackId != snapshot.trackId) {
                heldTrackId = snapshot.trackId;
                heldTitle = null;
                heldArtist = null;
                heldQuality = null;
                heldYear = null;
                heldHiRes = false;
                heldCover = null;
            }
            heldTime = buildTimeLine(snapshot);
        }
        renderPlaybackState();
    }

    /**
     * Renders the playing/paused/stopped composition. The status word and
     * glyph come from the player's own PlayState when the binder is
     * reachable; without it the platform's music-active flag gives an honest
     * PLAY/STOP (never PAUSE). Track/artist/quality/time lines show only
     * what was actually read, and the last real track holds on the glass -
     * nothing is ever fabricated. The art panel follows the same rule: it
     * shows the held track's cover when its bytes were actually read (B6),
     * and the plasma otherwise.
     */
    private void renderPlaybackState() {
        boolean playing;
        if (nowPlaying != null) {
            playing = nowPlaying.state == SonyWalkmanContract.PLAY_STATE_PLAY;
            if (playing) {
                lcdStateView.setText("PLAY");
                lcdGlyphView.showPlay();
            } else if (nowPlaying.state == SonyWalkmanContract.PLAY_STATE_PAUSE) {
                lcdStateView.setText("PAUSE");
                lcdGlyphView.showPause();
            } else {
                lcdStateView.setText("STOP");
                lcdGlyphView.showStop();
            }
        } else {
            AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
            playing = audio != null && audio.isMusicActive();
            lcdStateView.setText(playing ? "PLAY" : "STOP");
            if (playing) {
                lcdGlyphView.showPlay();
            } else {
                lcdGlyphView.showStop();
            }
        }

        lcdTitleView.setText(heldTitle != null ? heldTitle
                : playing ? "AUDIO ACTIVE" : "DIGITAL AUDIO");
        setLcdLine(lcdArtistView, heldArtist);
        setLcdLine(lcdQualityView, heldQuality);
        setLcdLine(lcdYearView, heldYear);
        lcdHrView.setVisibility(heldHiRes ? View.VISIBLE : View.INVISIBLE);
        setLcdLine(lcdTimeView, heldTime);
        updatePanel();
        updateSpectrum();
    }

    /**
     * B7b: feed the chassis window's renderer the player's clock, and ask
     * the analyzer service for this track's envelope on its first PLAY
     * poll. The renderer holds the flat baseline for every state that is
     * not PLAY-with-a-real-track - including the binder-less
     * isMusicActive() fallback, which has no playhead to index by.
     */
    private void updateSpectrum() {
        MediaServiceClient.Snapshot snapshot = nowPlaying;
        if (snapshot != null && snapshot.track != null && snapshot.track.genieId > 0
                && snapshot.track.durationMs > 0) {
            MediaServiceClient.Track track = snapshot.track;
            boolean playing = snapshot.state == SonyWalkmanContract.PLAY_STATE_PLAY
                    && snapshot.playTimeMs >= 0;
            spectrum.onTrack(track.genieId, track.durationMs, playing, snapshot.playTimeMs);
            if (playing) {
                maybeRequestAnalysis(track);
            }
        } else {
            spectrum.onNoTrack();
        }
    }

    /**
     * One analysis request per played track: skipped entirely while the
     * info window is the CLOCK (the spectrum is off, so no background
     * analysis either) or the music volume is out of reach (the service
     * would refuse it), and for keys that already failed this boot or
     * envelopes known complete. The service re-checks all of it - this
     * gate only keeps the ~1 s poll from re-sending intents. (Every codec
     * is requested since phase 2: ALAC and hi-res FLAC decode through the
     * hand-written path.)
     */
    private void maybeRequestAnalysis(MediaServiceClient.Track track) {
        if (infoMode != LauncherSettings.INFO_SPECTRUM || storageBlocked) {
            // Forget the last request so switching back to SPECTRUM - or
            // the music coming back - re-asks for the track playing then.
            spectrumRequestedId = 0;
            spectrumRequestedDuration = 0;
            return;
        }
        if (track.genieId == spectrumRequestedId
                && track.durationMs == spectrumRequestedDuration) {
            return;
        }
        if (SpectrumStore.isFailed(track.genieId, track.durationMs)) {
            return;
        }
        SpectrumStore.Envelope envelope = SpectrumStore.peek(track.genieId, track.durationMs);
        if (envelope != null && envelope.complete) {
            return;
        }
        spectrumRequestedId = track.genieId;
        spectrumRequestedDuration = track.durationMs;
        SpectrumAnalyzerService.requestAnalysis(this, track.genieId, track.durationMs);
    }

    /**
     * B6 display rule: while the held track has a cover the plasma
     * tick stops and the cover owns the panel; with no readable cover the
     * plasma resumes - never an empty frame, never a placeholder.
     */
    private void updatePanel() {
        if (heldCover != null) {
            stopPlasma();
            if (panelShowing != heldCover) {
                panelShowing = heldCover;
                setPanelBitmap(heldCover);
            }
        } else {
            if (panelShowing != panelBitmap) {
                panelShowing = panelBitmap;
                setPanelBitmap(panelBitmap);
            }
            startPlasma();
        }
    }

    /** Only while resumed and the art panel is on the glass at all. */
    private void startPlasma() {
        if (!resumed || plasmaRunning || storageBlocked) {
            return;
        }
        plasmaRunning = true;
        clockHandler.removeCallbacks(panelTick);
        clockHandler.post(panelTick);
    }

    private void stopPlasma() {
        if (!plasmaRunning) {
            return;
        }
        plasmaRunning = false;
        clockHandler.removeCallbacks(panelTick);
    }

    /** Filtering stays off so both plasma and cover keep square LCD pixels. */
    private void setPanelBitmap(Bitmap bitmap) {
        panelView.setImageBitmap(bitmap);
        ((BitmapDrawable) panelView.getDrawable()).setFilterBitmap(false);
    }

    /**
     * Returns the cached cover for this track (null also covers
     * "resolved: no art"), kicking off one background resolve for a track
     * not seen yet. Until that resolve lands the panel honestly shows the
     * plasma - the previous track's cover is never worn by a new track.
     */
    private Bitmap coverForTrack(long trackId, MediaServiceClient.Track track) {
        for (int slot = 0; slot < artCacheIds.length; slot++) {
            if (artCacheIds[slot] == trackId) {
                return artCacheFrames[slot];
            }
        }
        if (artPendingTrackId != trackId && artHandler != null) {
            artPendingTrackId = trackId;
            final long requestedId = trackId;
            final MediaServiceClient.Track requestedTrack = track;
            // The cover renders at the panel's real content size (v2.6.3);
            // zeros before the first layout pass fall back to the plasma
            // raster inside the resolver.
            final int targetW = panelView.getWidth()
                    - panelView.getPaddingLeft() - panelView.getPaddingRight();
            final int targetH = panelView.getHeight()
                    - panelView.getPaddingTop() - panelView.getPaddingBottom();
            final int requestedStyle = artStyle;
            final int toneDark = skin.artDark;
            final int toneLight = skin.artLight;
            final int generation = artGeneration;
            artHandler.post(new Runnable() {
                @Override
                public void run() {
                    final Bitmap frame = artResolver.resolve(
                            requestedId, requestedTrack, targetW, targetH,
                            requestedStyle, toneDark, toneLight);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            onCoverResolved(requestedId, frame, generation);
                        }
                    });
                }
            });
        }
        return null;
    }

    private void onCoverResolved(long trackId, Bitmap frame, int generation) {
        if (artPendingTrackId == trackId) {
            artPendingTrackId = 0;
        }
        if (generation != artGeneration) {
            // Rendered under an artwork style that is no longer active.
            return;
        }
        artCachePut(trackId, frame);
        if (heldTrackId == trackId) {
            heldCover = frame;
            renderPlaybackState();
        }
    }

    /** Two slots: the current track and the previous one (skip-back case). */
    private void artCachePut(long trackId, Bitmap frame) {
        for (int slot = 0; slot < artCacheIds.length; slot++) {
            if (artCacheIds[slot] == trackId) {
                artCacheFrames[slot] = frame;
                return;
            }
        }
        artCacheIds[1] = artCacheIds[0];
        artCacheFrames[1] = artCacheFrames[0];
        artCacheIds[0] = trackId;
        artCacheFrames[0] = frame;
    }

    private static void setLcdLine(TextView view, String text) {
        if (text == null) {
            view.setVisibility(View.INVISIBLE);
        } else {
            view.setText(text);
            view.setVisibility(View.VISIBLE);
        }
    }

    /**
     * B2: "<CODEC> <sr>/<bits>  <bitrate>KBPS  <n>CH", each token omitted
     * when its datum is unreadable; null when none are.
     */
    private static String buildQualityLine(MediaServiceClient.Track track) {
        StringBuilder line = new StringBuilder();
        appendToken(line, SonyWalkmanContract.codecLabel(track.codec, track.mimeType));
        appendToken(line, rateToken(track.sampleRate, track.bitsPerSample));
        if (track.bitrate > 0) {
            appendToken(line, (track.bitrate + 500) / 1000 + "KBPS");
        }
        if (track.channels > 0) {
            appendToken(line, track.channels + "CH");
        }
        return line.length() == 0 ? null : line.toString();
    }

    private static void appendToken(StringBuilder line, String token) {
        if (token == null) {
            return;
        }
        if (line.length() > 0) {
            line.append("  ");
        }
        line.append(token);
    }

    private static String rateToken(int sampleRate, int bitsPerSample) {
        String rate = null;
        if (sampleRate > 0) {
            // 44100 -> 44.1, 96000 -> 96 (trailing .0 dropped).
            rate = sampleRate % 1000 == 0 ? String.valueOf(sampleRate / 1000)
                    : String.format(Locale.US, "%.1f", sampleRate / 1000f);
        }
        if (rate != null && bitsPerSample > 0) {
            return rate + "/" + bitsPerSample;
        }
        if (rate != null) {
            return rate;
        }
        return bitsPerSample > 0 ? bitsPerSample + "BIT" : null;
    }

    /** B4: "M:SS / M:SS \u00b7 TR x/y", each segment omitted when unreadable. */
    private static String buildTimeLine(MediaServiceClient.Snapshot snapshot) {
        StringBuilder line = new StringBuilder();
        if (snapshot.playTimeMs >= 0) {
            line.append(formatTime(snapshot.playTimeMs));
            if (snapshot.durationMs > 0) {
                line.append(" / ").append(formatTime(snapshot.durationMs));
            }
        }
        if (snapshot.trackCountInList > 0 && snapshot.trackIndexInList >= 0) {
            if (line.length() > 0) {
                line.append(" \u00b7 ");
            }
            // The firmware's index is 0-based (a live capture read "TR 0/13"
            // with a track loaded); shown 1-based the way a deck counts.
            line.append("TR ").append(snapshot.trackIndexInList + 1)
                    .append('/').append(snapshot.trackCountInList);
        }
        return line.length() == 0 ? null : line.toString();
    }

    private static String formatTime(int milliseconds) {
        int totalSeconds = milliseconds / 1000;
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    /**
     * The idle art panel: a two-tone plasma, ordered-dithered with the same
     * Bayer 4x4 matrix the panel raster uses (B6). It is decoration generated
     * on-device, not data, so the honesty rule is untouched. Runs only while
     * this activity is resumed, the art panel is on the glass (not given
     * way to the storage panel), and no real cover holds it.
     */
    private void initPlasmaPanel() {
        panelBitmap = Bitmap.createBitmap(PANEL_W, PANEL_H, Bitmap.Config.ARGB_8888);
        panelPixels = new int[PANEL_W * PANEL_H];
        panelPhaseA = new float[PANEL_W * PANEL_H];
        panelPhaseB = new float[PANEL_W * PANEL_H];
        panelPhaseC = new float[PANEL_W * PANEL_H];
        panelPhaseD = new float[PANEL_W * PANEL_H];
        panelLimit = new float[PANEL_W * PANEL_H];
        for (int y = 0; y < PANEL_H; y++) {
            for (int x = 0; x < PANEL_W; x++) {
                int i = y * PANEL_W + x;
                panelPhaseA[i] = x / 11f;
                panelPhaseB[i] = y / 8f;
                panelPhaseC[i] = (x + y) / 13f;
                panelPhaseD[i] = (float) (Math.hypot(x - PANEL_W / 2d, y - PANEL_H / 2d) / 9d);
                // Threshold folded back to the raw -4..4 sum range.
                panelLimit[i] = (BAYER4[(y % 4) * 4 + (x % 4)] + 0.5f) / 16f * 8f - 4f;
            }
        }
        panelStart = SystemClock.uptimeMillis();
        panelShowing = panelBitmap;
        setPanelBitmap(panelBitmap);
    }

    private void renderPlasmaFrame() {
        float t = (SystemClock.uptimeMillis() - panelStart) / 1000f;
        float tb = -1.3f * t;
        float tc = 0.6f * t;
        float td = -1.1f * t;
        for (int i = 0; i < panelPixels.length; i++) {
            float v = sinLut(panelPhaseA[i] + t)
                    + sinLut(panelPhaseB[i] + tb)
                    + sinLut(panelPhaseC[i] + tc)
                    + sinLut(panelPhaseD[i] + td);
            panelPixels[i] = v > panelLimit[i] ? plasmaGlass : plasmaInk;
        }
        panelBitmap.setPixels(panelPixels, 0, PANEL_W, 0, 0, PANEL_W, PANEL_H);
        panelView.invalidate();
    }

    private static float sinLut(float phase) {
        return SIN[(int) (phase * SIN_SCALE) & (SIN_SIZE - 1)];
    }

    private void launch(Intent intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (ActivityNotFoundException exception) {
            Toast.makeText(this, R.string.action_unavailable, Toast.LENGTH_SHORT).show();
        }
    }
}
