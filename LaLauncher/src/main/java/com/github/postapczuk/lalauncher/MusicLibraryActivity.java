/*
 * ZX1 Retro Interface
 * In-launcher music library browser (C1): early-iPod information
 * architecture over Sony's Genie media database, rendered in the LCD
 * design language. Licensed under GPLv2; see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import android.app.Activity;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

/**
 * The MUSIC LIBRARY screen: PLAYLISTS / ARTISTS / ALBUMS / SONGS as shallow
 * alphabetical metadata lists, one level at a time - Android back and the
 * header control both go up exactly one level, so there is never a dead end.
 * The library is a picker: choosing a track hands the queue to the firmware
 * player ({@link PlayerHandoff}) and returns to the home glass, which is the
 * launcher's now-playing view. The browser remembers its place: the whole
 * navigation stack (level, scroll, search filter) is snapshotted on every
 * pause and re-resolved against the current library on the next open, so
 * hopping between the player and the library never loses the position.
 * Long-pressing a track opens the task menu - ADD TO NOW PLAYING appends it
 * to the end of the playing queue ({@link NowPlayingQueue}), ADD TO PLAYLIST
 * opens {@link PlaylistsActivity} as a picker and comes back here.
 *
 * PLAYLISTS is the one root row that leaves the browser: since C2 that
 * whole subject - the launcher's own editable lists and Sony's read-only
 * ones - belongs to {@link PlaylistsActivity}, so this screen no longer
 * carries playlist levels of its own. When that screen reports that it
 * started playback, the browser closes behind it (the picker rule).
 *
 * Data comes from {@link LibraryStore}: an in-memory snapshot (pre-warmed
 * by home) or the disk cache renders instantly; the provider is then only
 * asked for a cheap id fingerprint to decide whether a background rebuild
 * is needed. A full rebuild shows an LCD-styled overlay with a real
 * progress bar only when there is nothing cached to display. Sony's scan
 * windows (power-on, USB disconnect) leave the provider unreadable and
 * never invalidate the cache - the rebuild happens after their scan has
 * finished and the fingerprint actually differs.
 */
public class MusicLibraryActivity extends Activity {

    private static final String TAG = "ZX1Library";

    private static final int LEVEL_ROOT = 0;
    private static final int LEVEL_PLAYLISTS = 1;
    private static final int LEVEL_ARTISTS = 2;
    private static final int LEVEL_ARTIST_ALBUMS = 3;
    private static final int LEVEL_ALBUMS = 4;
    private static final int LEVEL_ALBUM_TRACKS = 5;
    private static final int LEVEL_SONGS = 6;

    /** Any playlist screen result meaning "playback started; go home". */
    private static final int REQUEST_PLAYLISTS = 1;

    /** Lists longer than this get the platform fast-scroll thumb. */
    private static final int FAST_SCROLL_THRESHOLD = 40;
    /** Provider-change debounce before re-checking the fingerprint. */
    private static final long OBSERVER_DEBOUNCE_MS = 2000L;
    /** Retry cadence while the provider is unreadable (Sony mid-scan). */
    private static final long UNREADABLE_RETRY_MS = 5000L;

    /** One rendered list row; at most one of the ref fields is set. */
    private static final class DisplayRow {
        final String num;
        final String title;
        final String sub;
        final String right;
        final LibraryStore.Track track;
        final LibraryStore.Group group;
        final int rootTarget;

        DisplayRow(String num, String title, String sub, String right,
                   LibraryStore.Track track, LibraryStore.Group group,
                   int rootTarget) {
            this.num = num;
            this.title = title;
            this.sub = sub;
            this.right = right;
            this.track = track;
            this.group = group;
            this.rootTarget = rootTarget;
        }
    }

    /** One navigation level; frames own their rows so back is instant. */
    private static final class Frame {
        final int level;
        final String crumb;
        final List<LibraryStore.Track> queue;
        final Uri queueUri;
        final String queueSort;
        int scrollPosition;
        int scrollTop;
        String filter = "";
        /** Position-memory identity: how to find this level again. */
        String navKey;

        Frame(int level, String crumb, List<LibraryStore.Track> queue,
              Uri queueUri, String queueSort) {
            this.level = level;
            this.crumb = crumb;
            this.queue = queue;
            this.queueUri = queueUri;
            this.queueSort = queueSort;
        }
    }

    /**
     * One saved navigation level. The whole stack is snapshotted on every
     * pause into {@link #savedNav} - process-lifetime, like the library
     * snapshot itself - so reopening the browser lands exactly where the
     * user left it, every level re-resolved against the current library
     * (a vanished album truncates the restore at its parent rather than
     * inventing a location).
     */
    private static final class SavedFrame {
        final int level;
        final String navKey;
        final String filter;
        final int scrollPosition;
        final int scrollTop;

        SavedFrame(int level, String navKey, String filter,
                   int scrollPosition, int scrollTop) {
            this.level = level;
            this.navKey = navKey;
            this.filter = filter;
            this.scrollPosition = scrollPosition;
            this.scrollTop = scrollTop;
        }
    }

    /** The last visit's navigation stack; null before the first visit. */
    private static ArrayList<SavedFrame> savedNav;

    // ---------------------------------------------------------------- state

    /** The rendered snapshot; swapped only at the root level. */
    private LibraryStore.Library library;
    /** A fresher snapshot waiting for the user to return to the root. */
    private LibraryStore.Library pendingLibrary;
    private boolean loadSettled;
    private boolean rebuildRunning;

    private final ArrayList<Frame> stack = new ArrayList<Frame>();
    private final List<DisplayRow> visibleRows = new ArrayList<DisplayRow>();
    private RowAdapter adapter;
    private boolean handoffInFlight;
    private int navigationToken;
    private Skin skin;

    private HandlerThread workerThread;
    private Handler worker;
    private final Handler freshnessHandler = new Handler();

    private ListView listView;
    private EditText searchView;
    private TextView statusView;
    private TextView hintView;
    private TextView backView;
    private View overlayView;
    private ProgressBar overlayBar;
    private TextView overlayText;
    private View menuView;
    private TextView menuTitle;
    private TextView menuSub;
    private TextView menuQueueKey;
    private TextView menuPlaylistKey;
    /** The track the open task menu is about; null while the menu is hidden. */
    private LibraryStore.Track menuTrack;
    /** Set when a restore must wait for the cached snapshot to load. */
    private boolean restorePending;

    /** Albums of the artist currently drilled into (rebuilt on push). */
    private List<LibraryStore.Group> frameArtistAlbums = Collections.emptyList();
    /** Whether the album being viewed has more than one artist (set on push). */
    private boolean frameAlbumMixedArtists;

    private final Runnable freshnessRunnable = new Runnable() {
        @Override
        public void run() {
            checkFreshness();
        }
    };

    /**
     * Sony's provider may notify on library changes; treat any change as a
     * debounced request to re-check the fingerprint.
     */
    private final ContentObserver genieObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            freshnessHandler.removeCallbacks(freshnessRunnable);
            freshnessHandler.postDelayed(freshnessRunnable, OBSERVER_DEBOUNCE_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_music_library);
        skin = Skin.of(this);

        listView = (ListView) findViewById(R.id.lib_list);
        searchView = (EditText) findViewById(R.id.lib_search);
        statusView = (TextView) findViewById(R.id.lib_status);
        hintView = (TextView) findViewById(R.id.lib_hint);
        backView = (TextView) findViewById(R.id.lib_back);
        overlayView = findViewById(R.id.lib_overlay);
        overlayBar = (ProgressBar) findViewById(R.id.lib_overlay_bar);
        overlayText = (TextView) findViewById(R.id.lib_overlay_count);

        adapter = new RowAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                onRowClick(visibleRows.get(position));
            }
        });
        listView.setOnItemLongClickListener(new AdapterView.OnItemLongClickListener() {
            @Override
            public boolean onItemLongClick(AdapterView<?> parent, View view, int position, long id) {
                DisplayRow row = visibleRows.get(position);
                if (row.track == null) {
                    return false;
                }
                showTaskMenu(row.track);
                return true;
            }
        });
        menuView = findViewById(R.id.lib_menu);
        menuTitle = (TextView) findViewById(R.id.lib_menu_title);
        menuSub = (TextView) findViewById(R.id.lib_menu_sub);
        menuQueueKey = (TextView) findViewById(R.id.lib_menu_queue);
        menuPlaylistKey = (TextView) findViewById(R.id.lib_menu_playlist);
        menuView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { hideTaskMenu(); }
        });
        menuQueueKey.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { onQueueNext(); }
        });
        menuPlaylistKey.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) { onAddToPlaylist(); }
        });
        searchView.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                Frame frame = currentFrame();
                if (frame.level == LEVEL_SONGS && !frame.filter.equals(s.toString())) {
                    frame.filter = s.toString();
                    rebuildRows();
                }
            }
            @Override public void afterTextChanged(Editable s) { }
        });
        backView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { popLevel(); }
        });

        applySkin();

        workerThread = new HandlerThread("zx1-library");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());

        // Whatever home pre-warmed renders synchronously - iPod speed.
        library = LibraryStore.peek();
        if (library != null) {
            loadSettled = true;
        }
        stack.add(new Frame(LEVEL_ROOT, null, null, null, null));
        if (!restoreNavState()) {
            rebuildLevel();
        }
        bootstrap();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (LauncherSettings.getStyle(this) != skin.style) {
            // The style changed while this screen was paused (SETTINGS via
            // the program index); re-inflate under the new one.
            recreate();
            return;
        }
        getContentResolver().registerContentObserver(
                SonyWalkmanContract.GENIE_AUDIO_URI, true, genieObserver);
        if (firstResumeDone) {
            // Coming back from the background (home key, then the soft-key
            // again): a USB copy plus Sony's rescan may have happened while
            // paused, and Genie's notifyChange is unverified - ask once.
            freshnessHandler.removeCallbacks(freshnessRunnable);
            freshnessHandler.postDelayed(freshnessRunnable, 500L);
        }
        firstResumeDone = true;
    }

    /** bootstrap() covers the first resume's freshness check. */
    private boolean firstResumeDone;

    @Override
    protected void onPause() {
        saveNavState();
        getContentResolver().unregisterContentObserver(genieObserver);
        freshnessHandler.removeCallbacks(freshnessRunnable);
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (workerThread != null) {
            worker.removeCallbacksAndMessages(null);
            workerThread.quit();
            workerThread = null;
            worker = null;
        }
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        if (menuView.getVisibility() == View.VISIBLE) {
            hideTaskMenu();
            return;
        }
        if (stack.size() > 1) {
            popLevel();
            return;
        }
        super.onBackPressed();
        overridePendingTransition(android.R.anim.fade_in, R.anim.slide_down);
    }

    /** TUBE AMP re-tint; on LCD the inflated resources already match. */
    private void applySkin() {
        if (skin.isLcd()) {
            return;
        }
        findViewById(R.id.lib_root).setBackgroundColor(skin.chassis);
        skin.tintDrawerControl(backView);
        ((TextView) findViewById(R.id.lib_title_label)).setTextColor(skin.panelText);
        statusView.setTextColor(skin.panelDim);
        findViewById(R.id.lib_glass).setBackground(skin.frameBackground());
        skin.tintSearch(searchView);
        skin.tintList(listView);
        hintView.setTextColor(skin.ink);
        overlayView.setBackgroundColor(skin.chassis);
        findViewById(R.id.lib_overlay_panel).setBackground(skin.frameBackground());
        ((TextView) findViewById(R.id.lib_overlay_title)).setTextColor(skin.ink);
        overlayText.setTextColor(skin.inkDim);
        skin.tintProgressBar(overlayBar);
        menuView.setBackgroundColor(skin.chassis);
        findViewById(R.id.lib_menu_panel).setBackground(skin.frameBackground());
        menuTitle.setTextColor(skin.ink);
        menuSub.setTextColor(skin.inkDim);
        menuQueueKey.setBackground(skin.softKeyBackground());
        menuQueueKey.setTextColor(skin.softKeyText());
        menuPlaylistKey.setBackground(skin.softKeyBackground());
        menuPlaylistKey.setTextColor(skin.softKeyText());
        ((TextView) findViewById(R.id.lib_menu_hint)).setTextColor(skin.ink);
    }

    // ----------------------------------------------------- load and freshness

    /** Disk cache if memory was cold, then the fingerprint. */
    private void bootstrap() {
        worker.post(new Runnable() {
            @Override
            public void run() {
                final LibraryStore.Library cached = library != null
                        ? null : LibraryStore.loadCached(MusicLibraryActivity.this);
                final long fingerprint =
                        LibraryStore.readFingerprint(MusicLibraryActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        if (library == null && cached != null) {
                            library = cached;
                            loadSettled = true;
                            boolean restored = false;
                            if (restorePending && stack.size() == 1
                                    && navigationToken == 0) {
                                // The deferred position restore: the cold
                                // open rendered the root while the cache
                                // loaded, and the user has not navigated.
                                restored = restoreNavState();
                            }
                            restorePending = false;
                            if (!restored) {
                                refreshCurrentLevel();
                            }
                        }
                        handleFingerprint(fingerprint);
                    }
                });
            }
        });
    }

    private void checkFreshness() {
        if (worker == null || rebuildRunning) {
            return;
        }
        worker.post(new Runnable() {
            @Override
            public void run() {
                final long fingerprint =
                        LibraryStore.readFingerprint(MusicLibraryActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        handleFingerprint(fingerprint);
                    }
                });
            }
        });
    }

    /**
     * 0 means the provider is unreadable or empty - Sony's rescan window -
     * so the cache is kept and the check retries; a real, different
     * fingerprint triggers the rebuild (with the overlay only when there
     * is nothing on screen to keep using).
     */
    private void handleFingerprint(long fingerprint) {
        if (rebuildRunning) {
            return;
        }
        if (fingerprint == 0) {
            loadSettled = true;
            if (library == null) {
                refreshCurrentLevel();
            }
            Log.i(TAG, "fingerprint unknown (provider unreadable or empty); "
                    + (library == null ? "retrying" : "keeping cache"));
            freshnessHandler.removeCallbacks(freshnessRunnable);
            freshnessHandler.postDelayed(freshnessRunnable, UNREADABLE_RETRY_MS);
            return;
        }
        if (library == null) {
            startRebuild(true);
        } else if (fingerprint != library.fingerprint) {
            Log.i(TAG, "library changed (fingerprint mismatch); rebuilding in background");
            startRebuild(false);
        } else {
            loadSettled = true;
        }
    }

    private void startRebuild(boolean showOverlay) {
        if (rebuildRunning || worker == null) {
            return;
        }
        rebuildRunning = true;
        if (showOverlay) {
            overlayBar.setProgress(0);
            overlayText.setText("");
            overlayView.setVisibility(View.VISIBLE);
        }
        refreshCurrentLevel();
        worker.post(new Runnable() {
            @Override
            public void run() {
                final LibraryStore.Library rebuilt = LibraryStore.rebuild(
                        MusicLibraryActivity.this, overlayProgress);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        rebuildRunning = false;
                        loadSettled = true;
                        overlayView.setVisibility(View.GONE);
                        onRebuilt(rebuilt);
                    }
                });
            }
        });
    }

    private final LibraryStore.Progress overlayProgress = new LibraryStore.Progress() {
        @Override
        public void onProgress(final int done, final int total) {
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    if (overlayView.getVisibility() != View.VISIBLE) {
                        return;
                    }
                    overlayBar.setMax(total);
                    overlayBar.setProgress(done);
                    overlayText.setText(String.format(Locale.US, "%d / %d", done, total));
                }
            });
        }
    };

    private void onRebuilt(LibraryStore.Library rebuilt) {
        if (rebuilt == null) {
            // Provider unreadable mid-rebuild; keep what is shown, retry.
            refreshCurrentLevel();
            if (library == null) {
                freshnessHandler.removeCallbacks(freshnessRunnable);
                freshnessHandler.postDelayed(freshnessRunnable, UNREADABLE_RETRY_MS);
            }
            return;
        }
        if (stack.size() == 1) {
            library = rebuilt;
            pendingLibrary = null;
            rebuildRows();
        } else {
            // Frames hold a consistent older snapshot; swap at the root so
            // the list the user is inside never mutates underfoot.
            pendingLibrary = rebuilt;
            refreshCurrentLevel();
        }
    }

    private void refreshCurrentLevel() {
        statusView.setText(statusLine(currentFrame()));
        if (currentFrame().level == LEVEL_ROOT) {
            rebuildRows();
        }
    }

    // ------------------------------------------------------------ navigation

    private Frame currentFrame() {
        return stack.get(stack.size() - 1);
    }

    private void captureScroll(Frame frame) {
        frame.scrollPosition = listView.getFirstVisiblePosition();
        View top = listView.getChildAt(0);
        frame.scrollTop = top == null ? 0 : top.getTop();
    }

    private void pushLevel(Frame frame) {
        navigationToken++;
        captureScroll(currentFrame());
        stack.add(frame);
        rebuildLevel();
        listView.setSelection(0);
    }

    // ------------------------------------------------------ position memory

    /** Snapshots the stack (with the live scroll) for the next visit. */
    private void saveNavState() {
        if (restorePending && stack.size() == 1) {
            // The deferred restore never got its snapshot (cold cache still
            // loading); keep the deeper saved stack rather than wiping it
            // with the root the user only saw for a moment.
            return;
        }
        captureScroll(currentFrame());
        ArrayList<SavedFrame> saved = new ArrayList<SavedFrame>(stack.size());
        for (Frame frame : stack) {
            saved.add(new SavedFrame(frame.level, frame.navKey, frame.filter,
                    frame.scrollPosition, frame.scrollTop));
        }
        savedNav = saved;
    }

    /**
     * Rebuilds the last visit's stack over the current snapshot. Frames
     * are re-resolved by identity (artist/album key, playlist id) - a
     * level that no longer resolves truncates the restore at its parent,
     * so the user always lands somewhere real. Returns true when it
     * rendered the level itself; false leaves the caller to render (no
     * saved state, or the snapshot has not loaded yet - the bootstrap
     * retries once via {@link #restorePending}).
     */
    private boolean restoreNavState() {
        ArrayList<SavedFrame> saved = savedNav;
        if (saved == null || saved.size() <= 1) {
            return false;
        }
        if (library == null) {
            restorePending = true;
            return false;
        }
        stack.get(0).scrollPosition = saved.get(0).scrollPosition;
        stack.get(0).scrollTop = saved.get(0).scrollTop;
        for (int i = 1; i < saved.size(); i++) {
            SavedFrame savedFrame = saved.get(i);
            Frame frame = buildFrameFor(savedFrame);
            if (frame == null) {
                break;
            }
            frame.filter = savedFrame.filter == null ? "" : savedFrame.filter;
            frame.scrollPosition = savedFrame.scrollPosition;
            frame.scrollTop = savedFrame.scrollTop;
            stack.add(frame);
        }
        rebuildLevel();
        Frame top = currentFrame();
        listView.setSelectionFromTop(top.scrollPosition, top.scrollTop);
        return true;
    }

    /** One restored level, re-resolved; null truncates the restore. */
    private Frame buildFrameFor(SavedFrame saved) {
        switch (saved.level) {
            case LEVEL_ARTISTS:
                return library.artists.isEmpty() ? null
                        : new Frame(LEVEL_ARTISTS, "ARTISTS", null, null, null);
            case LEVEL_ALBUMS:
                return library.albums.isEmpty() ? null
                        : new Frame(LEVEL_ALBUMS, "ALBUMS", null, null, null);
            case LEVEL_SONGS:
                return library.songs.isEmpty() ? null
                        : new Frame(LEVEL_SONGS, "SONGS", library.songs,
                                SonyWalkmanContract.GENIE_AUDIO_URI,
                                library.songSortColumn);
            case LEVEL_ARTIST_ALBUMS: {
                LibraryStore.Group artist = artistByKey(saved.navKey);
                return artist == null ? null : buildArtistAlbumsFrame(artist);
            }
            case LEVEL_ALBUM_TRACKS: {
                LibraryStore.Group album = saved.navKey == null ? null
                        : library.albumsByKey.get(saved.navKey);
                return album == null ? null : buildAlbumTracksFrame(album);
            }
            default:
                return null;
        }
    }

    private LibraryStore.Group artistByKey(String key) {
        if (key == null) {
            return null;
        }
        for (LibraryStore.Group artist : library.artists) {
            if (key.equals(artist.key)) {
                return artist;
            }
        }
        return null;
    }

    private void popLevel() {
        navigationToken++;
        if (stack.size() <= 1) {
            finish();
            overridePendingTransition(android.R.anim.fade_in, R.anim.slide_down);
            return;
        }
        stack.remove(stack.size() - 1);
        if (stack.size() == 1 && pendingLibrary != null) {
            library = pendingLibrary;
            pendingLibrary = null;
        }
        rebuildLevel();
        Frame frame = currentFrame();
        listView.setSelectionFromTop(frame.scrollPosition, frame.scrollTop);
    }

    /** Applies everything a level owns: search, hint, header, rows. */
    private void rebuildLevel() {
        Frame frame = currentFrame();
        backView.setText(stack.size() > 1 ? "\u25bc BACK" : "\u25bc HOME");
        boolean searchable = frame.level == LEVEL_SONGS;
        searchView.setVisibility(searchable ? View.VISIBLE : View.GONE);
        if (searchable && !searchView.getText().toString().equals(frame.filter)) {
            searchView.setText(frame.filter);
        }
        boolean trackLevel = frame.level == LEVEL_ALBUM_TRACKS
                || frame.level == LEVEL_SONGS;
        hintView.setText(trackLevel
                ? "TAP: PLAY  HOLD: MENU  \u25bc: BACK" : "TAP: OPEN    \u25bc: BACK");
        rebuildRows();
    }

    /** Rebuilds only the row list (level switch or search keystroke). */
    private void rebuildRows() {
        Frame frame = currentFrame();
        visibleRows.clear();
        switch (frame.level) {
            case LEVEL_ROOT:
                buildRootRows();
                break;
            case LEVEL_ARTISTS:
                buildGroupRows(library == null
                        ? Collections.<LibraryStore.Group>emptyList() : library.artists, true);
                break;
            case LEVEL_ARTIST_ALBUMS:
                buildGroupRows(frameArtistAlbums, false);
                break;
            case LEVEL_ALBUMS:
                buildGroupRows(library == null
                        ? Collections.<LibraryStore.Group>emptyList() : library.albums, false);
                break;
            case LEVEL_ALBUM_TRACKS:
                buildAlbumTrackRows(frame);
                break;
            case LEVEL_SONGS:
                buildSongRows(frame);
                break;
            default:
                break;
        }
        adapter.notifyDataSetChanged();
        listView.setFastScrollEnabled(visibleRows.size() > FAST_SCROLL_THRESHOLD);
        statusView.setText(statusLine(frame));
    }

    private String statusLine(Frame frame) {
        if (frame.level == LEVEL_ROOT) {
            if (library == null) {
                return loadSettled && !rebuildRunning
                        ? "LIBRARY UNREADABLE" : "READING LIBRARY...";
            }
            if (rebuildRunning) {
                return "UPDATING LIBRARY...";
            }
            return String.format(Locale.US, "%d TRACKS", library.songs.size());
        }
        String crumb = frame.crumb == null ? "" : frame.crumb;
        return String.format(Locale.US, "%s \u00b7 %d", crumb, visibleRows.size());
    }

    private void buildRootRows() {
        boolean loaded = library != null;
        visibleRows.add(new DisplayRow(null, "PLAYLISTS", null, "\u25b6",
                null, null, LEVEL_PLAYLISTS));
        visibleRows.add(new DisplayRow(null, "ARTISTS", null,
                loaded ? String.valueOf(library.artists.size()) : "\u25b6",
                null, null, LEVEL_ARTISTS));
        visibleRows.add(new DisplayRow(null, "ALBUMS", null,
                loaded ? String.valueOf(library.albums.size()) : "\u25b6",
                null, null, LEVEL_ALBUMS));
        visibleRows.add(new DisplayRow(null, "SONGS", null,
                loaded ? String.valueOf(library.songs.size()) : "\u25b6",
                null, null, LEVEL_SONGS));
    }

    private void buildGroupRows(List<LibraryStore.Group> groups, boolean artists) {
        for (LibraryStore.Group group : groups) {
            String right = artists
                    ? String.format(Locale.US, "%d ALB", group.albumCount)
                    : String.format(Locale.US, "%d TR", group.trackCount);
            visibleRows.add(new DisplayRow(null, upper(group.name, ""),
                    artists ? null : upper(group.sub, null), right,
                    null, group, -1));
        }
    }

    private void buildAlbumTrackRows(Frame frame) {
        boolean mixedArtists = frameAlbumMixedArtists;
        for (int i = 0; i < frame.queue.size(); i++) {
            LibraryStore.Track track = frame.queue.get(i);
            int number = track.trackNo > 0
                    ? (track.trackNo > 1000 ? track.trackNo % 1000 : track.trackNo)
                    : i + 1;
            visibleRows.add(new DisplayRow(String.format(Locale.US, "%02d", number),
                    upper(track.title, "UNKNOWN"),
                    mixedArtists ? upper(track.artist, null) : null,
                    duration(track.durationMs), track, null, -1));
        }
    }

    private void buildSongRows(Frame frame) {
        String filter = frame.filter.trim().toLowerCase(Locale.US);
        int shown = 0;
        for (LibraryStore.Track track : frame.queue) {
            if (filter.length() > 0 && !matchesFilter(track, filter)) {
                continue;
            }
            shown++;
            visibleRows.add(new DisplayRow(String.format(Locale.US, "%03d", shown),
                    upper(track.title, "UNKNOWN"), upper(track.artist, null),
                    duration(track.durationMs), track, null, -1));
        }
    }

    private static boolean matchesFilter(LibraryStore.Track track, String filter) {
        return (track.title != null && track.title.toLowerCase(Locale.US).contains(filter))
                || (track.artist != null && track.artist.toLowerCase(Locale.US).contains(filter));
    }

    // ---------------------------------------------------------------- clicks

    private void onRowClick(DisplayRow row) {
        Frame frame = currentFrame();
        if (row.rootTarget >= 0) {
            openRootTarget(row.rootTarget);
            return;
        }
        if (row.group != null) {
            if (frame.level == LEVEL_ARTISTS) {
                pushArtistAlbums(row.group);
            } else {
                pushAlbumTracks(row.group);
            }
            return;
        }
        if (row.track != null) {
            startHandoff(frame, row.track, frame.level + "/" + row.track.id);
        }
    }

    private void openRootTarget(int level) {
        switch (level) {
            case LEVEL_PLAYLISTS:
                // C2 owns playlists whole - the launcher's own editable lists
                // and Sony's read-only ones on one screen - so this row is a
                // door out of the browser rather than a level inside it.
                startActivityForResult(
                        PlaylistsActivity.createIntent(this, true), REQUEST_PLAYLISTS);
                overridePendingTransition(R.anim.slide_up, android.R.anim.fade_out);
                break;
            case LEVEL_ARTISTS:
                if (library != null && !library.artists.isEmpty()) {
                    pushLevel(new Frame(LEVEL_ARTISTS, "ARTISTS", null, null, null));
                }
                break;
            case LEVEL_ALBUMS:
                if (library != null && !library.albums.isEmpty()) {
                    pushLevel(new Frame(LEVEL_ALBUMS, "ALBUMS", null, null, null));
                }
                break;
            case LEVEL_SONGS:
                if (library != null && !library.songs.isEmpty()) {
                    pushLevel(new Frame(LEVEL_SONGS, "SONGS", library.songs,
                            SonyWalkmanContract.GENIE_AUDIO_URI, library.songSortColumn));
                }
                break;
            default:
                break;
        }
    }

    private void pushArtistAlbums(LibraryStore.Group artist) {
        pushLevel(buildArtistAlbumsFrame(artist));
    }

    /** Builds the level (and sets its side data); shared with the restore. */
    private Frame buildArtistAlbumsFrame(LibraryStore.Group artist) {
        List<LibraryStore.Group> albums = new ArrayList<LibraryStore.Group>();
        HashSet<String> seen = new HashSet<String>();
        for (LibraryStore.Track track : library.tracks) {
            if (!LibraryStore.artistKeyOf(track).equals(artist.key)) {
                continue;
            }
            String albumKey = LibraryStore.albumKeyOf(track);
            if (seen.add(albumKey)) {
                LibraryStore.Group album = library.albumsByKey.get(albumKey);
                if (album != null) {
                    albums.add(album);
                }
            }
        }
        LibraryStore.sortGroups(albums);
        frameArtistAlbums = albums;
        Frame frame = new Frame(LEVEL_ARTIST_ALBUMS,
                upper(artist.name, "UNKNOWN ARTIST"), null, null, null);
        frame.navKey = artist.key;
        return frame;
    }

    /**
     * An album opens whole - the queue URI is the entire disc with no
     * ORDER BY, byte-for-byte the player's own album descriptor (the
     * provider's default order for an album path is track order). The
     * local comparator matches it: trackNo, then _id - the provider's
     * rowid tiebreak - so the tapped row's index is the queue index.
     */
    private void pushAlbumTracks(LibraryStore.Group album) {
        pushLevel(buildAlbumTracksFrame(album));
    }

    /** Builds the level (and sets its side data); shared with the restore. */
    private Frame buildAlbumTracksFrame(LibraryStore.Group album) {
        List<LibraryStore.Track> rows = new ArrayList<LibraryStore.Track>();
        HashSet<String> artistsSeen = new HashSet<String>();
        for (LibraryStore.Track track : library.tracks) {
            if (LibraryStore.albumKeyOf(track).equals(album.key)) {
                rows.add(track);
                if (track.artist != null) {
                    artistsSeen.add(track.artist);
                }
            }
        }
        Collections.sort(rows, LibraryStore.ALBUM_ORDER);
        frameAlbumMixedArtists = artistsSeen.size() > 1;
        Frame frame = new Frame(LEVEL_ALBUM_TRACKS, upper(album.name, "UNKNOWN ALBUM"),
                rows,
                album.id > 0 ? SonyWalkmanContract.genieAlbumTracksUri(album.id) : null,
                null);
        frame.navKey = album.key;
        return frame;
    }

    // --------------------------------------------------------------- handoff

    /** track == null plays the frame's whole queue from the top. */
    private void startHandoff(Frame frame, LibraryStore.Track track, String label) {
        if (handoffInFlight) {
            return;
        }
        int startIndex = 0;
        if (track != null && frame.queue != null) {
            for (int i = 0; i < frame.queue.size(); i++) {
                if (frame.queue.get(i).id == track.id) {
                    startIndex = i;
                    break;
                }
            }
        }
        handoffInFlight = true;
        statusView.setText("STARTING...");
        PlayerHandoff.Request request = new PlayerHandoff.Request(
                frame.queueUri, frame.queueSort, startIndex,
                track == null ? 0 : track.id,
                track == null ? null : track.title,
                track == null ? null : track.album,
                track == null ? -1 : track.durationMs,
                track == null ? -1 : track.trackNo,
                track == null ? null : track.mimeType,
                label);
        PlayerHandoff.play(this, request, new PlayerHandoff.Listener() {
            @Override
            public void onHandoff(int tier) {
                handoffInFlight = false;
                if (isFinishing()) {
                    return;
                }
                if (tier == PlayerHandoff.TIER_FAILED) {
                    Toast.makeText(MusicLibraryActivity.this,
                            R.string.action_unavailable, Toast.LENGTH_SHORT).show();
                    statusView.setText(statusLine(currentFrame()));
                    return;
                }
                // Selection made: drop back to home, the now-playing view.
                finish();
                overridePendingTransition(android.R.anim.fade_in, R.anim.slide_down);
            }
        });
    }

    // ------------------------------------------------------------- task menu

    /** Long-press on any track row: the numbered function menu. */
    private void showTaskMenu(LibraryStore.Track track) {
        if (handoffInFlight) {
            return;
        }
        menuTrack = track;
        menuTitle.setText(upper(track.title, "UNKNOWN"));
        String artist = upper(track.artist, null);
        menuSub.setText(artist == null ? "" : artist);
        menuSub.setVisibility(artist == null ? View.GONE : View.VISIBLE);
        menuView.setVisibility(View.VISIBLE);
    }

    private void hideTaskMenu() {
        menuTrack = null;
        menuView.setVisibility(View.GONE);
    }

    /**
     * ADD TO NOW PLAYING: append the track to the end of the playing
     * queue. The heavy lifting - and the honesty rules - live in
     * {@link NowPlayingQueue}; the browser stays open so several tracks
     * can be lined up in a row, first added first.
     */
    private void onQueueNext() {
        final LibraryStore.Track track = menuTrack;
        hideTaskMenu();
        if (track == null || handoffInFlight) {
            return;
        }
        handoffInFlight = true;
        statusView.setText("QUEUEING...");
        NowPlayingQueue.queueNext(this, track, new NowPlayingQueue.Listener() {
            @Override
            public void onResult(boolean queued) {
                handoffInFlight = false;
                if (isFinishing()) {
                    return;
                }
                statusView.setText(statusLine(currentFrame()));
                Toast.makeText(MusicLibraryActivity.this,
                        queued ? R.string.queued_next : R.string.action_unavailable,
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    /**
     * ADD TO PLAYLIST: the playlist screen in picker mode. It performs the
     * add itself and comes back here, so several tracks can be filed in a
     * row without losing the browsing position.
     */
    private void onAddToPlaylist() {
        LibraryStore.Track track = menuTrack;
        hideTaskMenu();
        if (track == null || handoffInFlight) {
            return;
        }
        startActivityForResult(PlaylistsActivity.createPickIntent(this, track.id),
                REQUEST_PLAYLISTS);
        overridePendingTransition(R.anim.slide_up, android.R.anim.fade_out);
    }

    /**
     * The playlist screen reports OK when it started playback; the browser
     * is a picker, so a selection made anywhere in it ends with the home
     * glass - the now-playing view.
     */
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PLAYLISTS && resultCode == RESULT_OK) {
            finish();
            overridePendingTransition(android.R.anim.fade_in, R.anim.slide_down);
        }
    }

    // ------------------------------------------------------------- rendering

    private static String upper(String value, String fallback) {
        return value == null ? fallback : value.toUpperCase(Locale.US);
    }

    private static String duration(int milliseconds) {
        if (milliseconds <= 0) {
            return null;
        }
        int totalSeconds = milliseconds / 1000;
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private final class RowAdapter extends ArrayAdapter<DisplayRow> {
        RowAdapter() {
            super(MusicLibraryActivity.this, R.layout.library_row, visibleRows);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = getLayoutInflater().inflate(R.layout.library_row, parent, false);
            }
            DisplayRow data = visibleRows.get(position);
            bind((TextView) row.findViewById(R.id.lib_row_index), data.num);
            ((TextView) row.findViewById(R.id.lib_row_title)).setText(
                    data.title == null ? "" : data.title);
            bind((TextView) row.findViewById(R.id.lib_row_sub), data.sub);
            // The album-level HR chip: lit only when every track on the
            // disc satisfies the hi-res rule - real data, never a guess.
            row.findViewById(R.id.lib_row_hr).setVisibility(
                    data.group != null && data.group.hiRes ? View.VISIBLE : View.GONE);
            bind((TextView) row.findViewById(R.id.lib_row_right), data.right);
            if (!skin.isLcd()) {
                ((TextView) row.findViewById(R.id.lib_row_index)).setTextColor(skin.inkDim);
                ((TextView) row.findViewById(R.id.lib_row_title)).setTextColor(skin.ink);
                ((TextView) row.findViewById(R.id.lib_row_sub)).setTextColor(skin.inkDim);
                TextView chip = (TextView) row.findViewById(R.id.lib_row_hr);
                chip.setBackgroundColor(skin.ink);
                chip.setTextColor(skin.glass);
                ((TextView) row.findViewById(R.id.lib_row_right)).setTextColor(skin.inkDim);
            }
            return row;
        }

        private void bind(TextView view, String text) {
            if (text == null) {
                view.setVisibility(View.GONE);
            } else {
                view.setText(text);
                view.setVisibility(View.VISIBLE);
            }
        }
    }
}
