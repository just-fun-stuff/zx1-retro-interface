/*
 * ZX1 Retro Interface
 * The PLAYLISTS screen (C2): the launcher's own lists, editable and
 * playable as real ordered queues, with Sony's read-only lists alongside.
 * Licensed under GPLv2; see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Two levels, in the library browser's mechanism: the index of lists, and
 * one list's tracks. The launcher's own playlists come first and are fully
 * editable - create, rename, delete, add from the library, remove by
 * long-press - because they are stored as <b>platform</b> MediaStore
 * playlists ({@link PlaylistStore}), which is also what makes them real
 * queues: tapping any row hands the player that list's members uri cued at
 * that row, through the proven registered-client handoff. Sony's own Genie
 * playlists are listed below them, read-only (their database is readable
 * but its rows can never be a queue descriptor - findings "ROOT CAUSE"), so
 * playing one mirrors it into the launcher's reserved queue list first.
 *
 * The screen doubles as the ADD TO PLAYLIST picker: started with
 * {@link #EXTRA_PICK_TRACK_ID} it shows only the launcher's lists, adds the
 * track to the one tapped, and returns to where it came from.
 *
 * Honesty rules, unchanged from the rest of the launcher: a member is shown
 * with the values its own row really carries (the platform row the engine
 * will read, enriched with the Genie row when the strict join places it),
 * a member whose row no longer answers is shown dark rather than dropped
 * silently, and any action that cannot be performed exactly says so instead
 * of approximating - a Sony list whose tapped track has no platform match
 * refuses rather than playing something else.
 */
public class PlaylistsActivity extends Activity {

    /** Picker mode: the Genie id of the track to add. */
    static final String EXTRA_PICK_TRACK_ID =
            "com.github.postapczuk.lalauncher.extra.PICK_TRACK_ID";
    /** Set when the library opened this screen, so ▼ says BACK, not HOME. */
    static final String EXTRA_FROM_LIBRARY =
            "com.github.postapczuk.lalauncher.extra.FROM_LIBRARY";

    private static final int ROW_HEADER = 0;
    private static final int ROW_NOTE = 1;
    private static final int ROW_PLAYLIST = 2;
    private static final int ROW_SONY = 3;
    private static final int ROW_TRACK = 4;

    /** Lists longer than this get the platform fast-scroll thumb. */
    private static final int FAST_SCROLL_THRESHOLD = 40;

    /** One rendered row; the reference fields say what it stands for. */
    private static final class Row {
        final int type;
        final String num;
        final String title;
        final String sub;
        final String right;
        final PlaylistStore.Entry entry;
        final LibraryStore.Playlist sony;
        final PlaylistStore.Member member;
        final LibraryStore.Track track;
        final int index;

        Row(int type, String num, String title, String sub, String right,
            PlaylistStore.Entry entry, LibraryStore.Playlist sony,
            PlaylistStore.Member member, LibraryStore.Track track, int index) {
            this.type = type;
            this.num = num;
            this.title = title;
            this.sub = sub;
            this.right = right;
            this.entry = entry;
            this.sony = sony;
            this.member = member;
            this.track = track;
            this.index = index;
        }

        static Row header(String title) {
            return new Row(ROW_HEADER, null, title, null, null, null, null, null, null, -1);
        }

        static Row note(String title) {
            return new Row(ROW_NOTE, null, title, null, null, null, null, null, null, -1);
        }
    }

    // ---------------------------------------------------------------- state

    /** null while unread or when the platform playlists table refused. */
    private List<PlaylistStore.Entry> entries;
    /** null while unread or when Sony's playlist table refused. */
    private List<LibraryStore.Playlist> sonyLists;
    /** The open list, if any: exactly one of these two is non-null. */
    private PlaylistStore.Entry openList;
    private LibraryStore.Playlist openSony;
    private List<PlaylistStore.Member> members;
    private List<LibraryStore.Track> sonyMembers;

    private boolean pickMode;
    private LibraryStore.Track pickTrack;
    /** True when the pick started inside this screen (from a Sony list). */
    private boolean pickReturnsToList;
    /** The Sony list to return to when that pick ends. */
    private LibraryStore.Playlist pickOriginSony;
    private boolean fromLibrary;
    /** False until the first index read has landed (unreadable vs pending). */
    private boolean indexSettled;
    /** onCreate does the first read; onResume refreshes from the second on. */
    private boolean firstResumeDone;
    /** True while an action is in flight; taps are ignored until it lands. */
    private boolean busy;
    /** Invalidates async reads whose level is no longer on screen. */
    private int navigationToken;

    private final List<Row> visibleRows = new ArrayList<Row>();
    private RowAdapter adapter;
    private Skin skin;

    private HandlerThread workerThread;
    private Handler worker;

    private ListView listView;
    private TextView statusView;
    private TextView hintView;
    private TextView backView;
    private TextView newKeyView;
    private View menuView;
    private TextView menuTitle;
    private TextView menuSub;
    private TextView menuKey1;
    private TextView menuKey2;
    private View inputView;
    private TextView inputTitle;
    private EditText inputField;

    /** What the open task menu / input panel acts on. */
    private Runnable menuAction1;
    private Runnable menuAction2;
    private int inputMode;
    private static final int INPUT_CREATE = 0;
    private static final int INPUT_RENAME = 1;
    private PlaylistStore.Entry inputEntry;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_playlists);
        skin = Skin.of(this);

        listView = (ListView) findViewById(R.id.pl_list);
        statusView = (TextView) findViewById(R.id.pl_status);
        hintView = (TextView) findViewById(R.id.pl_hint);
        backView = (TextView) findViewById(R.id.pl_back);
        newKeyView = (TextView) findViewById(R.id.pl_new);
        menuView = findViewById(R.id.pl_menu);
        menuTitle = (TextView) findViewById(R.id.pl_menu_title);
        menuSub = (TextView) findViewById(R.id.pl_menu_sub);
        menuKey1 = (TextView) findViewById(R.id.pl_menu_key1);
        menuKey2 = (TextView) findViewById(R.id.pl_menu_key2);
        inputView = findViewById(R.id.pl_input);
        inputTitle = (TextView) findViewById(R.id.pl_input_title);
        inputField = (EditText) findViewById(R.id.pl_input_field);

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
            public boolean onItemLongClick(AdapterView<?> parent, View view,
                                           int position, long id) {
                return onRowLongClick(visibleRows.get(position));
            }
        });
        backView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { goBack(); }
        });
        newKeyView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { showCreateInput(); }
        });
        menuView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { hideMenu(); }
        });
        menuKey1.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { runMenuAction(menuAction1); }
        });
        menuKey2.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { runMenuAction(menuAction2); }
        });
        inputView.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { hideInput(); }
        });
        findViewById(R.id.pl_input_save).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { commitInput(); }
        });
        findViewById(R.id.pl_input_cancel).setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { hideInput(); }
        });

        applySkin();

        workerThread = new HandlerThread("zx1-playlists");
        workerThread.start();
        worker = new Handler(workerThread.getLooper());

        fromLibrary = getIntent().getBooleanExtra(EXTRA_FROM_LIBRARY, false);
        long pickId = getIntent().getLongExtra(EXTRA_PICK_TRACK_ID, 0);
        if (pickId > 0) {
            pickTrack = trackById(pickId);
            if (pickTrack == null) {
                // The library snapshot no longer holds the track the menu
                // was opened on; adding it would be an invention.
                Toast.makeText(this, R.string.track_unmatched, Toast.LENGTH_SHORT).show();
                finish();
                return;
            }
            pickMode = true;
        }
        renderLevel();
        loadIndex();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (LauncherSettings.getStyle(this) != skin.style) {
            // The style changed while this screen was paused; re-inflate.
            recreate();
            return;
        }
        if (firstResumeDone && workerThread != null) {
            // Counts and members can change from the library picker or the
            // media scanner while this screen sits paused; the first resume
            // is already covered by the read onCreate started.
            refreshCurrentLevel();
        }
        firstResumeDone = true;
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
        if (inputView.getVisibility() == View.VISIBLE) {
            hideInput();
            return;
        }
        if (menuView.getVisibility() == View.VISIBLE) {
            hideMenu();
            return;
        }
        goBack();
    }

    private void goBack() {
        if (openList != null || openSony != null) {
            closeList();
            return;
        }
        if (pickMode && pickReturnsToList) {
            // An ADD TO PLAYLIST started from a Sony member: cancelling it
            // returns to that list rather than leaving the screen.
            endInternalPick();
            return;
        }
        finish();
        overridePendingTransition(android.R.anim.fade_in, R.anim.slide_down);
    }

    /** TUBE AMP re-tint; on LCD the inflated resources already match. */
    private void applySkin() {
        if (skin.isLcd()) {
            return;
        }
        findViewById(R.id.pl_root).setBackgroundColor(skin.chassis);
        skin.tintDrawerControl(backView);
        ((TextView) findViewById(R.id.pl_title_label)).setTextColor(skin.panelText);
        statusView.setTextColor(skin.panelDim);
        findViewById(R.id.pl_glass).setBackground(skin.frameBackground());
        skin.tintList(listView);
        hintView.setTextColor(skin.ink);
        newKeyView.setBackground(skin.softKeyBackground());
        newKeyView.setTextColor(skin.softKeyText());
        menuView.setBackgroundColor(skin.chassis);
        findViewById(R.id.pl_menu_panel).setBackground(skin.frameBackground());
        menuTitle.setTextColor(skin.ink);
        menuSub.setTextColor(skin.inkDim);
        tintKey(menuKey1);
        tintKey(menuKey2);
        ((TextView) findViewById(R.id.pl_menu_hint)).setTextColor(skin.ink);
        inputView.setBackgroundColor(skin.chassis);
        findViewById(R.id.pl_input_panel).setBackground(skin.frameBackground());
        inputTitle.setTextColor(skin.ink);
        skin.tintSearch(inputField);
        tintKey((TextView) findViewById(R.id.pl_input_save));
        tintKey((TextView) findViewById(R.id.pl_input_cancel));
    }

    private void tintKey(TextView key) {
        key.setBackground(skin.softKeyBackground());
        key.setTextColor(skin.softKeyText());
    }

    // ------------------------------------------------------------- the reads

    /** The index level: the launcher's lists, plus Sony's when browsing. */
    private void loadIndex() {
        if (worker == null) {
            return;
        }
        final int token = navigationToken;
        final boolean withSony = !pickMode;
        worker.post(new Runnable() {
            @Override
            public void run() {
                final List<PlaylistStore.Entry> mine =
                        PlaylistStore.readAll(PlaylistsActivity.this);
                final List<LibraryStore.Playlist> theirs = withSony
                        ? LibraryStore.readPlaylists(PlaylistsActivity.this) : null;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || navigationToken != token) {
                            return;
                        }
                        entries = mine;
                        sonyLists = theirs;
                        indexSettled = true;
                        rebuildRows();
                    }
                });
            }
        });
    }

    /** Re-reads whatever level is on screen, keeping the scroll position. */
    private void refreshCurrentLevel() {
        if (worker == null) {
            return;
        }
        if (openList != null) {
            final int token = navigationToken;
            final long playlistId = openList.id;
            final int scrollPosition = listView.getFirstVisiblePosition();
            worker.post(new Runnable() {
                @Override
                public void run() {
                    final List<PlaylistStore.Member> rows =
                            PlaylistStore.readMembers(PlaylistsActivity.this, playlistId);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing() || navigationToken != token
                                    || openList == null || openList.id != playlistId) {
                                return;
                            }
                            members = rows;
                            rebuildRows();
                            listView.setSelection(Math.min(scrollPosition,
                                    Math.max(0, visibleRows.size() - 1)));
                        }
                    });
                }
            });
            return;
        }
        if (openSony == null) {
            loadIndex();
        }
    }

    private void openLauncherList(final PlaylistStore.Entry entry) {
        if (worker == null) {
            return;
        }
        final int token = ++navigationToken;
        statusView.setText("READING PLAYLIST...");
        worker.post(new Runnable() {
            @Override
            public void run() {
                final List<PlaylistStore.Member> rows =
                        PlaylistStore.readMembers(PlaylistsActivity.this, entry.id);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || navigationToken != token) {
                            return;
                        }
                        openList = entry;
                        openSony = null;
                        members = rows;
                        sonyMembers = null;
                        renderLevel();
                        listView.setSelection(0);
                    }
                });
            }
        });
    }

    private void openSonyList(final LibraryStore.Playlist playlist) {
        if (worker == null) {
            return;
        }
        final int token = ++navigationToken;
        statusView.setText("READING PLAYLIST...");
        worker.post(new Runnable() {
            @Override
            public void run() {
                final List<LibraryStore.Track> rows = LibraryStore.readPlaylistMembers(
                        PlaylistsActivity.this, playlist.id);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing() || navigationToken != token) {
                            return;
                        }
                        openSony = playlist;
                        openList = null;
                        sonyMembers = rows;
                        members = null;
                        renderLevel();
                        listView.setSelection(0);
                    }
                });
            }
        });
    }

    private void closeList() {
        navigationToken++;
        openList = null;
        openSony = null;
        members = null;
        sonyMembers = null;
        renderLevel();
        loadIndex();
    }

    private LibraryStore.Track trackById(long trackId) {
        LibraryStore.Library library = LibraryStore.peek();
        if (library == null) {
            return null;
        }
        for (LibraryStore.Track track : library.tracks) {
            if (track.id == trackId) {
                return track;
            }
        }
        return null;
    }

    // ------------------------------------------------------------- rendering

    /** Applies everything a level owns: header control, key, hint, rows. */
    private void renderLevel() {
        boolean inList = openList != null || openSony != null;
        boolean atRoot = !inList && !(pickMode && pickReturnsToList);
        backView.setText(atRoot && !fromLibrary ? "▼ HOME" : "▼ BACK");
        newKeyView.setVisibility(inList ? View.GONE : View.VISIBLE);
        if (inList) {
            hintView.setText("TAP: PLAY  HOLD: MENU  ▼: BACK");
        } else if (pickMode) {
            hintView.setText("TAP: ADD TO LIST    ▼: CANCEL");
        } else {
            hintView.setText("TAP: OPEN  HOLD: MENU  ▼: BACK");
        }
        rebuildRows();
    }

    private void rebuildRows() {
        visibleRows.clear();
        if (openList != null) {
            buildLauncherTrackRows();
        } else if (openSony != null) {
            buildSonyTrackRows();
        } else {
            buildIndexRows();
        }
        adapter.notifyDataSetChanged();
        listView.setFastScrollEnabled(visibleRows.size() > FAST_SCROLL_THRESHOLD);
        statusView.setText(statusLine());
    }

    private String statusLine() {
        if (busy) {
            return "WORKING...";
        }
        if (openList != null) {
            return String.format(Locale.US, "%s · %d",
                    upper(openList.name, "UNTITLED"),
                    members == null ? 0 : members.size());
        }
        if (openSony != null) {
            return String.format(Locale.US, "%s · %d",
                    upper(openSony.name, "UNTITLED"),
                    sonyMembers == null ? 0 : sonyMembers.size());
        }
        if (pickMode) {
            return "ADD TO PLAYLIST";
        }
        if (!indexSettled) {
            return "READING PLAYLISTS...";
        }
        int count = (entries == null ? 0 : entries.size())
                + (sonyLists == null ? 0 : sonyLists.size());
        return String.format(Locale.US, "%d LISTS", count);
    }

    private void buildIndexRows() {
        boolean haveSony = sonyLists != null && !sonyLists.isEmpty();
        if (haveSony) {
            visibleRows.add(Row.header("MY LISTS"));
        }
        if (entries == null) {
            visibleRows.add(Row.note("PLAYLISTS UNREADABLE"));
        } else if (entries.isEmpty()) {
            visibleRows.add(Row.note(pickMode ? "NO PLAYLISTS YET \u00b7 CREATE ONE"
                    : "NO PLAYLISTS YET"));
        } else {
            int number = 0;
            for (PlaylistStore.Entry entry : entries) {
                number++;
                visibleRows.add(new Row(ROW_PLAYLIST,
                        String.format(Locale.US, "%02d", number),
                        upper(entry.name, "UNTITLED"), null,
                        entry.trackCount < 0 ? "▶"
                                : String.format(Locale.US, "%d TR", entry.trackCount),
                        entry, null, null, null, -1));
            }
        }
        if (!haveSony) {
            return;
        }
        visibleRows.add(Row.header("WALKMAN LISTS \u00b7 READ ONLY"));
        int number = 0;
        for (LibraryStore.Playlist playlist : sonyLists) {
            number++;
            visibleRows.add(new Row(ROW_SONY,
                    String.format(Locale.US, "%02d", number),
                    upper(playlist.name, "UNTITLED"), null, "▶",
                    null, playlist, null, null, -1));
        }
    }

    private void buildLauncherTrackRows() {
        if (members == null) {
            visibleRows.add(Row.note("PLAYLIST UNREADABLE"));
            return;
        }
        if (members.isEmpty()) {
            visibleRows.add(Row.note("EMPTY LIST \u00b7 ADD FROM MUSIC LIBRARY"));
            return;
        }
        for (int i = 0; i < members.size(); i++) {
            PlaylistStore.Member member = members.get(i);
            // A row whose platform values no longer read is kept in place -
            // it still occupies its queue index - but never given a title
            // it does not have.
            visibleRows.add(new Row(ROW_TRACK,
                    String.format(Locale.US, "%02d", i + 1),
                    member.isDark() ? "TRACK UNAVAILABLE"
                            : upper(member.displayTitle(), "UNKNOWN"),
                    upper(member.displayArtist(), null),
                    duration(member.durationMs),
                    null, null, member, member.libraryTrack, i));
        }
    }

    private void buildSonyTrackRows() {
        if (sonyMembers == null || sonyMembers.isEmpty()) {
            // Sony's provider serves the list but sometimes not its members
            // (their rescan window, or a members read this uid cannot make).
            visibleRows.add(Row.note("MEMBERS UNREADABLE"));
            return;
        }
        for (int i = 0; i < sonyMembers.size(); i++) {
            LibraryStore.Track track = sonyMembers.get(i);
            visibleRows.add(new Row(ROW_TRACK,
                    String.format(Locale.US, "%02d", i + 1),
                    upper(track.title, "UNKNOWN"), upper(track.artist, null),
                    duration(track.durationMs), null, null, null, track, i));
        }
    }

    // ---------------------------------------------------------------- clicks

    private void onRowClick(Row row) {
        if (busy) {
            return;
        }
        switch (row.type) {
            case ROW_PLAYLIST:
                if (pickMode) {
                    addPickTo(row.entry);
                } else {
                    openLauncherList(row.entry);
                }
                break;
            case ROW_SONY:
                openSonyList(row.sony);
                break;
            case ROW_TRACK:
                playFrom(row);
                break;
            default:
                break;
        }
    }

    private boolean onRowLongClick(Row row) {
        if (busy || pickMode) {
            return false;
        }
        if (row.type == ROW_PLAYLIST) {
            showPlaylistMenu(row.entry);
            return true;
        }
        if (row.type == ROW_TRACK) {
            showTrackMenu(row);
            return true;
        }
        return false;
    }

    // --------------------------------------------------------------- playing

    /**
     * Plays the open list from the tapped row. A launcher list is already a
     * queue descriptor - its members uri, ordered by play_order, cued at
     * this index. A Sony list has to be mirrored into the launcher's
     * reserved list first: Genie rows can never be a descriptor the engine
     * can resolve.
     */
    private void playFrom(final Row row) {
        if (row.member != null && row.member.isDark()) {
            Toast.makeText(this, R.string.track_unmatched, Toast.LENGTH_SHORT).show();
            return;
        }
        setBusy(true, "STARTING...");
        if (openList != null) {
            startQueue(PlaylistStore.membersUri(openList.id), row.index,
                    "playlist " + openList.id + " index " + row.index, row.track);
            return;
        }
        final int tapped = row.index;
        final List<LibraryStore.Track> source = sonyMembers;
        final LibraryStore.Track fallbackTrack = row.track;
        final String name = openSony == null ? "" : String.valueOf(openSony.name);
        worker.post(new Runnable() {
            @Override
            public void run() {
                final PlaylistStore.Mirror mirror = PlaylistStore.mirrorOf(
                        PlaylistsActivity.this, source, tapped);
                final long queueId = mirror == null ? 0
                        : PlaylistStore.ensureQueuePlaylist(PlaylistsActivity.this);
                final boolean written = queueId > 0 && PlaylistStore.writeMembers(
                        PlaylistsActivity.this, queueId, mirror.audioIds);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        if (!written) {
                            onPlayFailed(fallbackTrack);
                            return;
                        }
                        startQueue(PlaylistStore.membersUri(queueId), mirror.startIndex,
                                "walkman list " + name + " index " + mirror.startIndex
                                        + " (dropped " + mirror.dropped + ")",
                                fallbackTrack);
                    }
                });
            }
        });
    }

    /** Main thread. One direct-descriptor open, cued at {@code index}. */
    private void startQueue(Uri membersUri, int index, String label,
                            final LibraryStore.Track fallbackTrack) {
        PlayerHandoff.Request request = PlayerHandoff.Request.directQueue(
                membersUri, null, PlaylistStore.PLAY_ORDER, index, 0, false,
                fallbackTrack == null ? 0 : fallbackTrack.id, label);
        PlayerHandoff.play(this, request, new PlayerHandoff.Listener() {
            @Override
            public void onHandoff(int tier) {
                if (isFinishing()) {
                    return;
                }
                if (tier == PlayerHandoff.TIER_BINDER_QUEUE) {
                    leaveForNowPlaying();
                    return;
                }
                onPlayFailed(fallbackTrack);
            }
        });
    }

    /**
     * The queue could not be opened - on this firmware that means no player
     * is registered yet (the binder tier is empty until the player app has
     * run once since boot; a queue request deliberately takes no intent
     * fallback of its own). One track can still be started the cold way,
     * through the launcher's normal handoff, when the row resolves to a
     * library track.
     */
    private void onPlayFailed(LibraryStore.Track track) {
        if (track == null) {
            setBusy(false, null);
            Toast.makeText(this, R.string.action_unavailable, Toast.LENGTH_SHORT).show();
            return;
        }
        PlayerHandoff.Request single = new PlayerHandoff.Request(
                null, null, 0, track.id, track.title, track.album,
                track.durationMs, track.trackNo, track.mimeType,
                "playlist single " + track.id);
        PlayerHandoff.play(this, single, new PlayerHandoff.Listener() {
            @Override
            public void onHandoff(int tier) {
                if (isFinishing()) {
                    return;
                }
                if (tier == PlayerHandoff.TIER_FAILED) {
                    setBusy(false, null);
                    Toast.makeText(PlaylistsActivity.this, R.string.action_unavailable,
                            Toast.LENGTH_SHORT).show();
                    return;
                }
                leaveForNowPlaying();
            }
        });
    }

    /** Playback started: drop back to the home glass, the now-playing view. */
    private void leaveForNowPlaying() {
        setResult(RESULT_OK);
        finish();
        overridePendingTransition(android.R.anim.fade_in, R.anim.slide_down);
    }

    // ------------------------------------------------------------- task menus

    private void showPlaylistMenu(final PlaylistStore.Entry entry) {
        menuTitle.setText(upper(entry.name, "UNTITLED"));
        menuSub.setText(entry.trackCount < 0 ? ""
                : String.format(Locale.US, "%d TRACKS", entry.trackCount));
        menuKey1.setText("1  RENAME");
        menuKey2.setText("2  DELETE");
        menuAction1 = new Runnable() {
            @Override public void run() { showRenameInput(entry); }
        };
        menuAction2 = new Runnable() {
            @Override public void run() { confirmDelete(entry); }
        };
        menuView.setVisibility(View.VISIBLE);
    }

    private void showTrackMenu(final Row row) {
        menuTitle.setText(row.title == null ? "" : row.title);
        menuSub.setText(row.sub == null ? "" : row.sub);
        if (openList != null) {
            final PlaylistStore.Member member = row.member;
            menuKey1.setText("1  REMOVE FROM LIST");
            menuKey2.setText("2  ADD TO NOW PLAYING");
            menuAction1 = new Runnable() {
                @Override public void run() { removeMember(member); }
            };
            menuAction2 = new Runnable() {
                @Override
                public void run() {
                    queueMemberNext(member);
                }
            };
        } else {
            final LibraryStore.Track track = row.track;
            menuKey1.setText("1  ADD TO NOW PLAYING");
            menuKey2.setText("2  ADD TO PLAYLIST");
            menuAction1 = new Runnable() {
                @Override public void run() { queueTrackNext(track); }
            };
            menuAction2 = new Runnable() {
                @Override public void run() { startPickFor(track); }
            };
        }
        menuView.setVisibility(View.VISIBLE);
    }

    private void runMenuAction(Runnable action) {
        hideMenu();
        if (action != null && !busy) {
            action.run();
        }
    }

    private void hideMenu() {
        menuAction1 = null;
        menuAction2 = null;
        menuView.setVisibility(View.GONE);
    }

    // ------------------------------------------------------------ the actions

    /** ADD TO NOW PLAYING for a member: it already knows its platform id. */
    private void queueMemberNext(PlaylistStore.Member member) {
        if (member == null || member.isDark()) {
            Toast.makeText(this, R.string.track_unmatched, Toast.LENGTH_SHORT).show();
            return;
        }
        setBusy(true, "QUEUEING...");
        NowPlayingQueue.queueNextPlatform(this, member.audioId, member.displayTitle(),
                new NowPlayingQueue.Listener() {
                    @Override
                    public void onResult(boolean queued) {
                        if (isFinishing()) {
                            return;
                        }
                        setBusy(false, null);
                        Toast.makeText(PlaylistsActivity.this,
                                queued ? R.string.queued_next : R.string.action_unavailable,
                                Toast.LENGTH_SHORT).show();
                    }
                });
    }

    /** ADD TO NOW PLAYING for a Sony member: a Genie row, joined on the way. */
    private void queueTrackNext(LibraryStore.Track track) {
        if (track == null) {
            return;
        }
        setBusy(true, "QUEUEING...");
        NowPlayingQueue.queueNext(this, track, new NowPlayingQueue.Listener() {
            @Override
            public void onResult(boolean queued) {
                if (isFinishing()) {
                    return;
                }
                setBusy(false, null);
                Toast.makeText(PlaylistsActivity.this,
                        queued ? R.string.queued_next : R.string.action_unavailable,
                        Toast.LENGTH_SHORT).show();
            }
        });
    }

    private void removeMember(PlaylistStore.Member member) {
        if (member == null || openList == null) {
            return;
        }
        setBusy(true, "REMOVING...");
        final long playlistId = openList.id;
        PlaylistEditor.remove(this, playlistId, member.memberId,
                String.valueOf(openList.name), new PlaylistEditor.Listener() {
                    @Override
                    public void onResult(int result) {
                        if (isFinishing()) {
                            return;
                        }
                        setBusy(false, null);
                        if (result != PlaylistEditor.EDIT_DONE) {
                            Toast.makeText(PlaylistsActivity.this,
                                    R.string.action_unavailable, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Toast.makeText(PlaylistsActivity.this, R.string.playlist_removed,
                                Toast.LENGTH_SHORT).show();
                        refreshCurrentLevel();
                    }
                });
    }

    /** The picker's own add, from the library or from a Sony member. */
    private void addPickTo(final PlaylistStore.Entry entry) {
        if (entry == null || pickTrack == null || worker == null) {
            return;
        }
        final LibraryStore.Track track = pickTrack;
        setBusy(true, "ADDING...");
        worker.post(new Runnable() {
            @Override
            public void run() {
                AlbumArtResolver.PlatformRow row = track.title == null ? null
                        : AlbumArtResolver.findPlatformRow(PlaylistsActivity.this,
                                track.title, track.album, track.durationMs, track.trackNo);
                final long audioId = row == null ? 0 : row.trackId;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        if (audioId <= 0) {
                            // The strict join could not place the track in
                            // the platform table, so the playlist - which is
                            // a platform table - cannot hold it.
                            setBusy(false, null);
                            Toast.makeText(PlaylistsActivity.this,
                                    R.string.track_unmatched, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        commitAdd(entry, audioId);
                    }
                });
            }
        });
    }

    private void commitAdd(final PlaylistStore.Entry entry, long audioId) {
        PlaylistEditor.add(this, entry.id, audioId, String.valueOf(entry.name),
                new PlaylistEditor.Listener() {
                    @Override
                    public void onResult(int result) {
                        if (isFinishing()) {
                            return;
                        }
                        setBusy(false, null);
                        if (result != PlaylistEditor.EDIT_DONE) {
                            Toast.makeText(PlaylistsActivity.this,
                                    R.string.action_unavailable, Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Toast.makeText(PlaylistsActivity.this,
                                R.string.playlist_added, Toast.LENGTH_SHORT).show();
                        finishPick();
                    }
                });
    }

    /** After a successful add: back to the list, or out of the screen. */
    private void finishPick() {
        if (pickReturnsToList) {
            endInternalPick();
            return;
        }
        finish();
        overridePendingTransition(android.R.anim.fade_in, R.anim.slide_down);
    }

    /** ADD TO PLAYLIST from a Sony member: the picker, without leaving. */
    private void startPickFor(LibraryStore.Track track) {
        if (track == null) {
            return;
        }
        navigationToken++;
        pickTrack = track;
        pickMode = true;
        pickReturnsToList = true;
        pickOriginSony = openSony;
        openList = null;
        openSony = null;
        members = null;
        sonyMembers = null;
        renderLevel();
        loadIndex();
    }

    /** Leaves the in-screen picker, landing back on the list it started in. */
    private void endInternalPick() {
        LibraryStore.Playlist origin = pickOriginSony;
        pickMode = false;
        pickTrack = null;
        pickReturnsToList = false;
        pickOriginSony = null;
        if (origin != null) {
            openSonyList(origin);
            return;
        }
        renderLevel();
        loadIndex();
    }

    private void confirmDelete(final PlaylistStore.Entry entry) {
        menuTitle.setText(upper(entry.name, "UNTITLED"));
        menuSub.setText("DELETE THIS PLAYLIST?");
        menuKey1.setText("1  DELETE");
        menuKey2.setText("2  CANCEL");
        menuAction1 = new Runnable() {
            @Override public void run() { deletePlaylist(entry); }
        };
        menuAction2 = null;
        menuView.setVisibility(View.VISIBLE);
    }

    private void deletePlaylist(PlaylistStore.Entry entry) {
        setBusy(true, "DELETING...");
        PlaylistEditor.deleteList(this, entry.id, String.valueOf(entry.name),
                new PlaylistEditor.Listener() {
                    @Override
                    public void onResult(int result) {
                        if (isFinishing()) {
                            return;
                        }
                        setBusy(false, null);
                        if (result == PlaylistEditor.DELETE_REFUSED_PLAYING) {
                            Toast.makeText(PlaylistsActivity.this,
                                    R.string.playlist_playing_now,
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (result != PlaylistEditor.EDIT_DONE) {
                            Toast.makeText(PlaylistsActivity.this,
                                    R.string.action_unavailable,
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        Toast.makeText(PlaylistsActivity.this, R.string.playlist_deleted,
                                Toast.LENGTH_SHORT).show();
                        loadIndex();
                    }
                });
    }

    // ------------------------------------------------------------ name input

    private void showCreateInput() {
        if (busy) {
            return;
        }
        inputMode = INPUT_CREATE;
        inputEntry = null;
        inputTitle.setText("NEW PLAYLIST");
        inputField.setText("");
        showInput();
    }

    private void showRenameInput(PlaylistStore.Entry entry) {
        inputMode = INPUT_RENAME;
        inputEntry = entry;
        inputTitle.setText("RENAME PLAYLIST");
        inputField.setText(entry.name == null ? "" : entry.name);
        inputField.setSelection(inputField.getText().length());
        showInput();
    }

    private void showInput() {
        inputView.setVisibility(View.VISIBLE);
        inputField.requestFocus();
        InputMethodManager keyboard = (InputMethodManager)
                getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) {
            keyboard.showSoftInput(inputField, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void hideInput() {
        InputMethodManager keyboard = (InputMethodManager)
                getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) {
            keyboard.hideSoftInputFromWindow(inputField.getWindowToken(), 0);
        }
        inputView.setVisibility(View.GONE);
        inputEntry = null;
    }

    private void commitInput() {
        final String name = inputField.getText().toString().trim();
        if (worker == null) {
            return;
        }
        if (!PlaylistStore.isUsableName(name)) {
            Toast.makeText(this, R.string.playlist_name_needed, Toast.LENGTH_SHORT).show();
            return;
        }
        final int mode = inputMode;
        final PlaylistStore.Entry entry = inputEntry;
        hideInput();
        setBusy(true, mode == INPUT_CREATE ? "CREATING..." : "RENAMING...");
        worker.post(new Runnable() {
            @Override
            public void run() {
                // Both writes report the same three ways: the new id (or 1),
                // NAME_REFUSED for a name the user cannot have, WRITE_FAILED
                // when the provider itself said no.
                final long result = mode == INPUT_CREATE
                        ? PlaylistStore.create(PlaylistsActivity.this, name)
                        : PlaylistStore.rename(PlaylistsActivity.this,
                                entry == null ? 0 : entry.id, name);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) {
                            return;
                        }
                        setBusy(false, null);
                        if (result == PlaylistStore.NAME_REFUSED) {
                            Toast.makeText(PlaylistsActivity.this,
                                    R.string.playlist_name_taken,
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (result == PlaylistStore.WRITE_FAILED) {
                            Toast.makeText(PlaylistsActivity.this,
                                    R.string.action_unavailable,
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        if (mode == INPUT_CREATE && pickMode) {
                            // Created to receive this track: add it now.
                            addPickTo(new PlaylistStore.Entry(result, name, 0));
                            return;
                        }
                        loadIndex();
                    }
                });
            }
        });
    }

    // ----------------------------------------------------------------- pieces

    private void setBusy(boolean value, String status) {
        busy = value;
        statusView.setText(status == null ? statusLine() : status);
    }

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

    private final class RowAdapter extends ArrayAdapter<Row> {
        RowAdapter() {
            super(PlaylistsActivity.this, R.layout.library_row, visibleRows);
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            int type = visibleRows.get(position).type;
            return type != ROW_HEADER && type != ROW_NOTE;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View row = convertView;
            if (row == null) {
                row = getLayoutInflater().inflate(R.layout.library_row, parent, false);
            }
            Row data = visibleRows.get(position);
            TextView index = (TextView) row.findViewById(R.id.lib_row_index);
            TextView title = (TextView) row.findViewById(R.id.lib_row_title);
            TextView sub = (TextView) row.findViewById(R.id.lib_row_sub);
            TextView right = (TextView) row.findViewById(R.id.lib_row_right);
            bind(index, data.num);
            title.setText(data.title == null ? "" : data.title);
            bind(sub, data.sub);
            row.findViewById(R.id.lib_row_hr).setVisibility(View.GONE);
            bind(right, data.right);
            boolean quiet = data.type == ROW_HEADER || data.type == ROW_NOTE
                    || (data.member != null && data.member.isDark());
            // Chassis-printed labels are small and dim; real rows are ink.
            title.setTextSize(quiet ? 11 : 17);
            title.setTextColor(quiet ? dimInk() : fullInk());
            index.setTextColor(dimInk());
            sub.setTextColor(dimInk());
            right.setTextColor(dimInk());
            return row;
        }

        private int fullInk() {
            return skin.isLcd() ? getResources().getColor(R.color.lcd_ink) : skin.ink;
        }

        private int dimInk() {
            return skin.isLcd() ? getResources().getColor(R.color.lcd_ink_dim) : skin.inkDim;
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

    /** The intent that opens this screen from home or from the library. */
    static Intent createIntent(Context context, boolean fromLibrary) {
        Intent intent = new Intent(context, PlaylistsActivity.class);
        if (fromLibrary) {
            intent.putExtra(EXTRA_FROM_LIBRARY, true);
        }
        return intent;
    }

    /** The picker intent: choose a list for this library track. */
    static Intent createPickIntent(Context context, long trackId) {
        Intent intent = createIntent(context, true);
        intent.putExtra(EXTRA_PICK_TRACK_ID, trackId);
        return intent;
    }
}
