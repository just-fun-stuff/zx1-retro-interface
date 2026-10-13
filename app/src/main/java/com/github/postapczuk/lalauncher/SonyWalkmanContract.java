/*
 * ZX1 Retro Interface
 * Sony NW-ZX1 application and intent contract discovered in firmware 1.13.
 * Licensed under GPLv2; see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;

import java.io.File;
import java.util.Locale;

/**
 * Centralizes device-specific names so the launcher can keep generic Android
 * fallbacks without scattering Sony firmware details through the UI code.
 */
final class SonyWalkmanContract {

    static final String PACKAGE_NAME = "com.sony.walkman.mediaplayers.wm2";
    static final String MUSIC_ACTIVITY =
            "com.sony.walkman.mediaapp.music.activity.MusicTopActivity";
    static final String MUSIC_CONTROL_SERVICE =
            "com.sony.walkman.mediaapp.music.ctrl.MusicControlService";

    static final String ACTION_START_TOP =
            "com.sony.walkman.mediaapp.music.ACTION_START_TOP";
    static final String ACTION_START_DASHBOARD =
            "com.sony.walkman.mediaapp.music.ACTION_START_DASHBOARD";
    static final String ACTION_START_PLAYING_VIEW =
            "com.sony.walkman.mediaapp.music.ACTION_START_PLAYING_VIEW";

    static final String ACTION_PREVIOUS =
            "com.sony.walkman.mediaapp.music.appwidget.PREV";
    static final String ACTION_PLAY_PAUSE =
            "com.sony.walkman.mediaapp.music.appwidget.PLAY_PAUSE";
    static final String ACTION_NEXT =
            "com.sony.walkman.mediaapp.music.appwidget.NEXT";

    static final String SOUND_ENHANCEMENT_PACKAGE =
            "com.sonyericsson.soundenhancement";

    /*
     * Sony's own Genie indexer (inside the player package) brackets a
     * library rescan with these two actions - the strings come from the
     * player package itself. Device-verified: both are broadcast
     * system-wide with the volume's uri ({@code file:///storage/sdcard0})
     * as data, STARTED a few seconds after the remount, FINISHED before
     * the platform scan's own. The post-USB refresh still only waits on
     * FINISHED once it has actually heard STARTED.
     */
    static final String ACTION_GENIE_SCANNER_STARTED =
            "com.sony.walkman.database.media.GenieMediaScannerService"
                    + ".ACTION_GENIE_MEDIA_SCANNER_STARTED";
    static final String ACTION_GENIE_SCANNER_FINISHED =
            "com.sony.walkman.database.media.GenieMediaScannerService"
                    + ".ACTION_GENIE_MEDIA_SCANNER_FINISHED";

    /*
     * Now-playing binder protocol. Firmware 1.13 emits no metadata broadcast
     * and registers no RemoteControlClient, so the current track must be
     * pulled from the player's own media service. The descriptors,
     * transaction codes, and PlayState ordinals below were reverse-engineered
     * from /system/framework/com.sony.walkman.media.client.dex.jar and are
     * documented in docs/firmware-findings.md. Binding needs no permission.
     */

    static final String MEDIA_SERVICE_ACTION = "com.sony.walkman.media.IMediaService";
    static final String MEDIA_SERVICE_DESCRIPTOR = "com.sony.walkman.media.IMediaServiceRemote";
    static final String PLAYER_DESCRIPTOR = "com.sony.walkman.media.IPlayerRemote";

    /** IMediaServiceRemote.getPlayerInterface(String, Bundle). */
    static final int TRANSACTION_GET_PLAYER_INTERFACE = 1;
    /** IMediaServiceRemote.getPlayers(). */
    static final int TRANSACTION_GET_PLAYERS = 3;
    /** IPlayerRemote.getCurrentInfo(). */
    static final int TRANSACTION_GET_CURRENT_INFO = 27;

    /*
     * The binder's write side (C1 playback handoff), from the same decompiled
     * client jar. open() replaces the player's media list with the result of
     * a content query it runs itself - in its own Sony-privileged process, so
     * WHERE/ORDER BY work there even though they are void for us - and play()
     * starts it. Parcel layout of the PlayListSelectionInfo argument is in
     * docs/firmware-findings.md; selectionArgs must always be written absent
     * (the service-side reader discards or crashes on them).
     */

    /** IPlayerRemote.open(PlayListSelectionInfo, boolean). */
    static final int TRANSACTION_OPEN = 3;
    /**
     * IPlayerRemote.play() - documented, do not use: the bare call is
     * accepted, then its task fails against a freshly opened queue and
     * that failure closes the list (proven live 2026-07-20). The stock
     * controller's play is always playWithParam.
     */
    static final int TRANSACTION_PLAY = 6;
    /** IPlayerRemote.playWithParam(PlaySpeed, muted, fadeInTimeMs). */
    static final int TRANSACTION_PLAY_WITH_PARAM = 7;
    /** IPlayerRemote.getMediaListInfo() - read-only oracle for the list format. */
    static final int TRANSACTION_GET_MEDIA_LIST_INFO = 22;
    /**
     * IPlayerRemote.pause() - the stock controller's own pause is the bare
     * call (PlaybackController.pause() forwards it unchanged, unlike play).
     * Issued only to keep a PAUSE state: after a queue-next reopen, and
     * after a home scrub's seek if that seek set a paused player playing.
     */
    static final int TRANSACTION_PAUSE = 8;
    /**
     * IPlayerRemote.seek(int positionMs, int origin) - the origin int is
     * IPlaybackController.SeekOrigin.ordinal(): BEGIN=0, CURRENT=1, END=2
     * (decompile-verified). Issued once after a queue-next reopen to put
     * the playhead back where it was, and once per home scrub on whatever
     * list is playing (the launcher's or Sony's own), confirmed by reading
     * the position back.
     */
    static final int TRANSACTION_SEEK = 9;
    /** SeekOrigin.BEGIN - absolute position from the track start. */
    static final int SEEK_ORIGIN_BEGIN = 0;
    /**
     * IPlayerRemote.playlistUpdated(int) - documented, deliberately never
     * issued: it exists all the way up Sony's public IPlaybackController
     * (presumably "the current list's backing rows changed, re-read
     * them"), but its int argument has no caller in the decompiled client
     * jar - the semantics live only in the un-decompiled wm2 odex, and
     * guessing arguments against the live service risks the open() wedge.
     * If its meaning is ever recovered, it would make queue-next inserts
     * seamless (no reopen) when the current list is already the
     * launcher's playlist.
     */
    static final int TRANSACTION_PLAYLIST_UPDATED = 52;

    /** RequestResult's MediaError code for success; anything else failed. */
    static final int MEDIA_ERROR_SUCCESS = 0;
    /** MediaError Error_EmptyPlaylist - the opened query matched nothing. */
    static final int MEDIA_ERROR_EMPTY_PLAYLIST = 305;

    /**
     * PlayListSelectionInfo's default id/data/mime column-name mapping - the
     * firmware library's own default, echoed verbatim into the parcel.
     */
    static final String[] PLAY_LIST_INFO_COLUMNS = {"_id", "_data", "mime_type"};

    /**
     * The list tag the stock music app itself puts in its
     * PlayListSelectionInfo, captured live from getMediaListInfo() on the
     * device (2026-07-19: uri=.../albums/122/media, selection=null,
     * sort=null, tag=this). Offered as an open() variant because the
     * service may key cursor plumbing or list state on it.
     */
    static final String MUSIC_LIST_TAG = "com.sony.walkman.mediaapp.music";

    /*
     * Client registration - the handshake Sony's Player constructor
     * performs (decompile-verified). The service's task engine completes
     * work only for registered clients: an anonymous open() is accepted,
     * then dies silently and closes the list (proven live 2026-07-20).
     * getPlayerInterface(name, Bundle) returns the client id inside the
     * bundle; addListener attaches a callback binder under that id;
     * teardown order is removeListener, then releasePlayerInterface.
     * Listener callbacks are NOT oneway - the service blocks on every
     * reply - so a hosted stub must answer instantly and never block.
     */

    /** IPlayerRemote.addListener(IPlayerEventListenerRemote, String). */
    static final int TRANSACTION_ADD_LISTENER = 1;
    /** IPlayerRemote.removeListener(String). */
    static final int TRANSACTION_REMOVE_LISTENER = 2;
    /** IPlayerRemote.setServerKeepAlive(String, boolean). */
    static final int TRANSACTION_SET_SERVER_KEEP_ALIVE = 46;
    /** IMediaServiceRemote.releasePlayerInterface(String). */
    static final int TRANSACTION_RELEASE_PLAYER_INTERFACE = 2;
    /** The callback binder descriptor; the launcher hosts the stub. */
    static final String PLAYER_LISTENER_DESCRIPTOR =
            "com.sony.walkman.media.IPlayerEventListenerRemote";
    /** IPlayerEventListenerRemote.onOpen(int, int, PlayListSelectionInfo). */
    static final int LISTENER_TRANSACTION_ON_OPEN = 2;
    /** IPlayerEventListenerRemote.onError(PlayInfo?, MediaError-as-int?). */
    static final int LISTENER_TRANSACTION_ON_ERROR = 1;
    /** Bundle key getPlayerInterface fills with the caller's client id. */
    static final String PLAYER_CLIENT_ID_KEY = "id";

    /**
     * IPlayerEventListenerRemote transaction map, index = transaction code
     * (decompile-verified). The service narrates every task through these;
     * the launcher's hosted stub logs them by name.
     */
    static final String[] LISTENER_EVENT_NAMES = {null,
            "onError", "onOpen", "onClosed", "onPlay", "onPause", "onSeek",
            "onPreviousTrack", "onNextTrack", "onSeekTrack", "onUpdated",
            "onNextChapter", "onPrevChapter", "onSeekChapter", "onFlash",
            "onSequenceBegin", "onSequenceEnd", "onTrackBegin", "onTrackEnd",
            "onTrackChanged", "onShuffleModeChanged", "onInqured",
            "onBufferingUpdate", "onAudioOutputEffectChanged", "onMetaData",
            "onPlayAudioStreamChanged", "onPlaySubtitleStreamChanged",
            "onTerminate", "onVideoFrameChanged", "onPlaylistChanged",
            "onApplyPlayRange", "onClearPlayRange", "onStartFade", "onFadeEnd"};

    /** PlayState enum ordinals as serialized inside the PlayInfo parcel. */
    static final int PLAY_STATE_CLOSE = 0;
    static final int PLAY_STATE_PAUSE = 1;
    static final int PLAY_STATE_PLAY = 2;

    /**
     * Sony's own library database. Its provider declares a write permission
     * but no read permission, so queries work with zero permissions held.
     * Quirk (proven on device 2026-07-19): the provider returns nothing for
     * any caller-supplied WHERE selection - rows must be addressed by
     * appending the id to this URI (ContentUris.withAppendedId).
     */
    static final Uri GENIE_AUDIO_URI = Uri.parse(
            "content://com.sony.walkman.providers.GenieMediaStore/external/audio/media");

    static final String COLUMN_ID = "_id";
    static final String COLUMN_TITLE = "title";
    static final String COLUMN_ARTIST = "artist";
    static final String COLUMN_ALBUM = "album";
    static final String COLUMN_ALBUM_ID = "album_id";
    static final String COLUMN_YEAR = "year";
    static final String COLUMN_DURATION = "duration";
    static final String COLUMN_SAMPLE_RATE = "sample_rate";
    static final String COLUMN_BITS_PER_SAMPLE = "bits_per_sample";
    static final String COLUMN_AUDIO_BITRATE = "audio_bitrate";
    static final String COLUMN_AUDIO_CODEC = "audio_codec";
    static final String COLUMN_MIME_TYPE = "mime_type";
    static final String COLUMN_NUMBER_OF_CHANNELS = "number_of_channels";
    static final String COLUMN_ARTIST_ID = "artist_id";
    static final String COLUMN_TRACK_NO = "track";
    static final String COLUMN_TITLE_SORT = "title_sortstr";
    static final String COLUMN_ARTIST_SORT = "artist_sortstr";
    static final String COLUMN_ALBUM_SORT = "album_sortstr";
    static final String COLUMN_PLAYLIST_NAME = "name";

    static final String[] GENIE_AUDIO_PROJECTION = {
            COLUMN_TITLE,
            COLUMN_ARTIST,
            COLUMN_ALBUM,
            COLUMN_ALBUM_ID,
            COLUMN_TRACK_NO,
            COLUMN_YEAR,
            COLUMN_DURATION,
            COLUMN_SAMPLE_RATE,
            COLUMN_BITS_PER_SAMPLE,
            COLUMN_AUDIO_BITRATE,
            COLUMN_AUDIO_CODEC,
            COLUMN_MIME_TYPE,
            COLUMN_NUMBER_OF_CHANNELS
    };

    /**
     * The projection value-proven on device in v2.4.0. Retried when the
     * projection above fails, so the metadata lines can never regress over a
     * schema-derived column (album_id comes from the decompiled DB client
     * jar; its value has not been read live yet).
     */
    static final String[] GENIE_AUDIO_FALLBACK_PROJECTION = {
            COLUMN_TITLE,
            COLUMN_ARTIST,
            COLUMN_ALBUM,
            COLUMN_YEAR,
            COLUMN_SAMPLE_RATE,
            COLUMN_BITS_PER_SAMPLE,
            COLUMN_AUDIO_BITRATE,
            COLUMN_AUDIO_CODEC,
            COLUMN_MIME_TYPE,
            COLUMN_NUMBER_OF_CHANNELS
    };

    /**
     * Album-art candidate columns on the Genie audio media row
     * (schema-derived; all five returned NULL to the adb full-table dump -
     * B6 re-tests them in-app through the appended-id URI before moving on
     * to the album row, platform MediaStore art, and the embedded picture).
     */
    static final String[] GENIE_ART_COLUMNS = {
            "albumcoverart",
            "coverart",
            "album_rep_media_coverart",
            "album_rep_media_thumbnail",
            "image_strict_size"
    };

    /**
     * The art columns plus the album id, as an explicit projection: proven
     * on device 2026-07-19, the Genie provider answers null-projection
     * queries with only {@code _id}, so art columns must be asked for by
     * name.
     */
    static final String[] GENIE_ART_PROJECTION;

    static {
        GENIE_ART_PROJECTION = new String[GENIE_ART_COLUMNS.length + 1];
        System.arraycopy(GENIE_ART_COLUMNS, 0, GENIE_ART_PROJECTION, 0, GENIE_ART_COLUMNS.length);
        GENIE_ART_PROJECTION[GENIE_ART_COLUMNS.length] = COLUMN_ALBUM_ID;
    }

    /**
     * Plausible Genie album-level paths for B6, mirroring the platform
     * MediaStore layout. Neither is documented; both are probed read-only
     * and every failure is swallowed.
     */
    static final Uri GENIE_ALBUMS_URI = Uri.parse(
            "content://com.sony.walkman.providers.GenieMediaStore/external/audio/albums");
    static final Uri GENIE_ALBUM_ART_URI = Uri.parse(
            "content://com.sony.walkman.providers.GenieMediaStore/external/audio/albumart");

    /**
     * Sony's playlist tables. Both paths are registered in the provider's
     * URI matcher (wm2 string dump); whether an unprivileged caller may read
     * them is unproven, so the library browser treats every read as a
     * best-effort probe and falls back to the Sony dashboard.
     */
    static final Uri GENIE_PLAYLISTS_URI = Uri.parse(
            "content://com.sony.walkman.providers.GenieMediaStore/external/audio/playlists");

    /**
     * Lean projection for the library browser's one-per-visit full-table
     * read (C1). The provider voids caller WHERE selections, so all rows are
     * fetched and grouped in memory. The sortstr columns are Sony's own
     * normalized alphabetical keys; artist_id and the sortstrs are
     * schema-derived, hence the proven fallback below.
     */
    static final String[] GENIE_LIBRARY_PROJECTION = {
            COLUMN_ID,
            COLUMN_TITLE,
            COLUMN_ARTIST,
            COLUMN_ALBUM,
            COLUMN_ARTIST_ID,
            COLUMN_ALBUM_ID,
            COLUMN_TRACK_NO,
            COLUMN_DURATION,
            COLUMN_MIME_TYPE,
            COLUMN_SAMPLE_RATE,
            COLUMN_BITS_PER_SAMPLE,
            COLUMN_TITLE_SORT,
            COLUMN_ARTIST_SORT,
            COLUMN_ALBUM_SORT
    };

    /** Every column here is value-proven on device (v2.4.0/v2.5.x runs). */
    static final String[] GENIE_LIBRARY_FALLBACK_PROJECTION = {
            COLUMN_ID,
            COLUMN_TITLE,
            COLUMN_ARTIST,
            COLUMN_ALBUM,
            COLUMN_ALBUM_ID,
            COLUMN_TRACK_NO,
            COLUMN_DURATION,
            COLUMN_MIME_TYPE,
            COLUMN_SAMPLE_RATE,
            COLUMN_BITS_PER_SAMPLE
    };

    static final String[] GENIE_PLAYLISTS_PROJECTION = {
            COLUMN_ID,
            COLUMN_PLAYLIST_NAME
    };

    /** An album's tracks: {@code external/audio/albums/<id>/media}. */
    static Uri genieAlbumTracksUri(long albumId) {
        return ContentUris.withAppendedId(GENIE_ALBUMS_URI, albumId)
                .buildUpon().appendPath("media").build();
    }

    /** A playlist's members: {@code external/audio/playlists/<id>/media}. */
    static Uri geniePlaylistTracksUri(long playlistId) {
        return ContentUris.withAppendedId(GENIE_PLAYLISTS_URI, playlistId)
                .buildUpon().appendPath("media").build();
    }

    /**
     * The player's transient image cache (probe 2026-07-19: tables below,
     * empty during playback). Its read guard is a normal-level Sony
     * permission this manifest deliberately does not declare, so the
     * one-time B6 peek is expected to be denied - it runs once to log the
     * definitive zero-permission answer.
     */
    static final Uri TEMPORARY_PROVIDER_URI = Uri.parse(
            "content://com.sony.walkman.media.util.temporaryProvider");
    static final String[] TEMPORARY_PROVIDER_TABLES = {"images", "images_backup", "media"};

    static final int CODEC_MP3 = 2;
    static final int CODEC_AAC = 3;
    static final int CODEC_FLAC = 8;
    static final int CODEC_ALAC = 9;

    private SonyWalkmanContract() { }

    /**
     * Genie audio_codec enum, ground-truthed across the whole device library
     * (docs/firmware-findings.md). An unmapped codec falls back to the mime
     * subtype; with neither readable there is no label.
     */
    static String codecLabel(int codec, String mimeType) {
        switch (codec) {
            case CODEC_MP3:
                return "MP3";
            case CODEC_AAC:
                return "AAC";
            case CODEC_FLAC:
                return "FLAC";
            case CODEC_ALAC:
                return "ALAC";
            default:
                if (mimeType == null) {
                    return null;
                }
                int slash = mimeType.indexOf('/');
                String subtype = (slash < 0 ? mimeType : mimeType.substring(slash + 1)).trim();
                return subtype.length() == 0 ? null : subtype.toUpperCase(Locale.US);
        }
    }

    static Intent createMusicIntent(String action) {
        Intent intent = new Intent(action);
        intent.setComponent(new ComponentName(PACKAGE_NAME, MUSIC_ACTIVITY));
        return intent;
    }

    static Intent createTransportIntent(String action) {
        Intent intent = new Intent(action);
        intent.setComponent(new ComponentName(PACKAGE_NAME, MUSIC_CONTROL_SERVICE));
        return intent;
    }

    /**
     * The player's exported scan-and-play entry: an ACTION_VIEW handler for
     * file-scheme audio (its filter covers mpeg/mp4/flac/wav and more, file
     * scheme only - a content: URI never matches it). The explicit component
     * bypasses filter matching entirely, so the mime only informs the
     * activity itself. This is the intent tier of the C1 playback handoff:
     * it plays exactly one file and brings up the Sony UI.
     */
    static final String SCAN_AND_PLAY_ACTIVITY =
            "com.sony.walkman.mediaapp.music.activity.ScanAndPlayFileActivity";

    static Intent createViewFileIntent(String path, String mimeType) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.fromFile(new File(path)),
                mimeType == null ? "audio/*" : mimeType);
        intent.setClassName(PACKAGE_NAME, SCAN_AND_PLAY_ACTIVITY);
        return intent;
    }

    /**
     * Explicitly package-qualified so the bind stays legal on Android 5.0+
     * (implicit service intents are rejected there) even though the target
     * device is 4.1.
     */
    static Intent createMediaServiceIntent() {
        Intent intent = new Intent(MEDIA_SERVICE_ACTION);
        intent.setPackage(PACKAGE_NAME);
        return intent;
    }

    static boolean isInstalled(PackageManager packageManager) {
        try {
            packageManager.getPackageInfo(PACKAGE_NAME, 0);
            return true;
        } catch (PackageManager.NameNotFoundException exception) {
            return false;
        }
    }
}
