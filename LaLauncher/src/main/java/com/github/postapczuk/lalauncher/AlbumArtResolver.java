/*
 * ZX1 Retro Interface
 * Album-art resolver for the LCD art panel: finds the current track's cover
 * through the firmware's data sources and renders it into the panel raster (B6).
 * Licensed under GPLv2; see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Resolves the playing track's cover into the LCD art panel raster. Sources
 * are tried in a fixed order and the first whose bytes decode wins - the
 * winner is logged per track so the firmware research record can name the
 * working source on a real device:
 *
 * <pre>
 * 1. art columns on the track's own Genie media row,
 * 2. the Genie album row / a Genie albumart path,
 * 3. platform MediaStore albumart for the matching platform row
 *    (joined by title, album, and duration - never guessed),
 * 4. the picture embedded in the audio file itself.
 * </pre>
 *
 * Everything runs on the caller's worker thread and needs zero permissions
 * (Genie and platform MediaStore are world-readable on this firmware, and
 * plain file reads are unenforced on 4.1). The result is rendered per the
 * Settings "Artwork Style" choice, in the active style's two tones:
 *
 * <ul>
 * <li><b>GREYSCALE</b> - the panel's native pixel size, luma mapped onto a
 * smooth 256-step dark-to-light ramp (photo-quality LCD-tone
 * greyscale);</li>
 * <li><b>GAMEBOY</b> - the chunky 146 x 85 raster, four tones, Bayer 4x4
 * ordered dither - the 2-bit renderer the smooth ramp replaced, kept as a
 * choice;</li>
 * <li><b>FULL COLOR</b> - the source image untouched beyond the center
 * crop and scale.</li>
 * </ul>
 *
 * Null when no art for exactly this track is readable - the caller then
 * keeps the plasma, never a placeholder and never another track's art.
 * Instances are confined to a single worker thread.
 */
final class AlbumArtResolver {

    private static final String TAG = "ZX1AlbumArt";
    /** Streams larger than this are not cover art; abandon, never OOM. */
    private static final int MAX_ART_STREAM_BYTES = 6 * 1024 * 1024;
    /** Genie and the platform scanner read the same tags; allow scan jitter. */
    static final long PLATFORM_DURATION_TOLERANCE_MS = 2000L;

    private static final Uri PLATFORM_ALBUM_ART_URI =
            Uri.parse("content://media/external/audio/albumart");
    /**
     * Platform audio columns as literals: both have existed since API 1, but
     * the modern SDK constants trip lint on this API 16 target (DURATION
     * moved to MediaColumns in 29; DATA is deprecated for scoped storage,
     * which does not exist on 4.1 - reading it there is the supported path).
     */
    private static final String PLATFORM_COLUMN_DURATION = "duration";
    private static final String PLATFORM_COLUMN_DATA = "_data";
    private static final String PLATFORM_COLUMN_TRACK = "track";

    private final Context context;
    /** The render target for the current resolve, set on every call. */
    private int targetW;
    private int targetH;
    /** The artwork style and tone endpoints for the current resolve. */
    private int artStyle;
    private int toneDark;
    private int toneLight;
    /**
     * The greyscale tone ramp (256 steps, dark to light) and the GAMEBOY
     * four-tone set, rebuilt only when the endpoints change (they follow
     * the interface style: ink-on-glass, or amber-on-black).
     */
    private final int[] greyRamp = new int[256];
    private final int[] gameboyTones = new int[4];
    private boolean tonesBuilt;
    private int builtDark;
    private int builtLight;
    private boolean peekedTemporaryProvider;
    private boolean loggedMediaRowColumns;
    private boolean loggedAlbumRowColumns;
    private long mediaRowAlbumId;

    AlbumArtResolver(Context context) {
        this.context = context;
    }

    /**
     * Worker thread. Returns the rendered cover for this track or null when
     * none of the sources yields a decodable image. Never throws - a bad
     * image or a hostile provider must not take the home screen down.
     * The target is the panel's real content size in pixels; before the
     * first layout pass the caller may pass zeros and the plasma raster
     * stands in. GAMEBOY always renders at the plasma raster - the chunky
     * cell is the point. The tone endpoints come from the active style.
     */
    Bitmap resolve(long trackId, MediaServiceClient.Track track,
                   int panelWidth, int panelHeight,
                   int artworkStyle, int darkTone, int lightTone) {
        artStyle = artworkStyle;
        toneDark = darkTone;
        toneLight = lightTone;
        if (artStyle == LauncherSettings.ART_GAMEBOY) {
            targetW = FavouriteAppsActivity.PANEL_W;
            targetH = FavouriteAppsActivity.PANEL_H;
        } else {
            targetW = panelWidth > 0 ? panelWidth : FavouriteAppsActivity.PANEL_W;
            targetH = panelHeight > 0 ? panelHeight : FavouriteAppsActivity.PANEL_H;
        }
        ensureTones();
        try {
            return resolveInner(trackId, track);
        } catch (RuntimeException exception) {
            Log.w(TAG, "art resolve failed for track " + trackId, exception);
            return null;
        } catch (OutOfMemoryError error) {
            Log.w(TAG, "art resolve out of memory for track " + trackId);
            return null;
        }
    }

    private Bitmap resolveInner(long trackId, MediaServiceClient.Track track) {
        peekTemporaryProviderOnce();
        StringBuilder trail = new StringBuilder();
        mediaRowAlbumId = -1;

        Bitmap cover = fromGenieMediaRow(trackId, trail);
        String winner = cover != null ? "genie-media-row" : null;

        if (cover == null) {
            long albumId = track.albumId > 0 ? track.albumId : mediaRowAlbumId;
            cover = fromGenieAlbum(albumId, trail);
            if (cover != null) {
                winner = "genie-album";
            }
        }
        if (cover == null) {
            PlatformRow row = mapToPlatformRow(track, trail);
            if (row != null) {
                cover = fromPlatformAlbumArt(row.albumId, trail);
                if (cover != null) {
                    winner = "mediastore-albumart";
                } else {
                    cover = fromEmbeddedPicture(row.dataPath, trail);
                    if (cover != null) {
                        winner = "embedded-picture";
                    }
                }
            }
        }
        Log.i(TAG, "track " + trackId + " art source: "
                + (winner == null ? "none" : winner) + " [" + trail + "]");
        return cover;
    }

    // ---------------------------------------------------------------- source 1

    /**
     * The five schema-derived art columns on the track's own Genie row,
     * re-tested through the appended-id URI (the adb full-table dump saw
     * only NULLs). The projection must be explicit: the provider answers
     * null-projection queries with only {@code _id} (proven on device
     * 2026-07-19), which would hide the art columns entirely. The
     * null-projection form is only the fallback if a named column is
     * rejected.
     */
    private Bitmap fromGenieMediaRow(long trackId, StringBuilder trail) {
        Cursor cursor = null;
        try {
            Uri uri = ContentUris.withAppendedId(SonyWalkmanContract.GENIE_AUDIO_URI, trackId);
            try {
                cursor = context.getContentResolver().query(uri,
                        SonyWalkmanContract.GENIE_ART_PROJECTION, null, null, null);
            } catch (RuntimeException exception) {
                cursor = null;
            }
            if (cursor == null) {
                cursor = context.getContentResolver().query(uri, null, null, null, null);
            }
            if (cursor == null || !cursor.moveToFirst()) {
                mark(trail, "genie-row", "unreadable");
                return null;
            }
            int albumIndex = cursor.getColumnIndex(SonyWalkmanContract.COLUMN_ALBUM_ID);
            if (albumIndex >= 0 && !cursor.isNull(albumIndex)) {
                mediaRowAlbumId = cursor.getLong(albumIndex);
            }
            logMediaRowOnce(cursor, trackId);
            for (String column : SonyWalkmanContract.GENIE_ART_COLUMNS) {
                int index = cursor.getColumnIndex(column);
                if (index < 0 || cursor.isNull(index)) {
                    continue;
                }
                Bitmap cover = coverFromColumn(cursor, index);
                if (cover != null) {
                    mark(trail, "genie-row", column);
                    return cover;
                }
            }
            mark(trail, "genie-row", "no-art");
            return null;
        } catch (RuntimeException exception) {
            mark(trail, "genie-row", "error");
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    // ---------------------------------------------------------------- source 2

    private Bitmap fromGenieAlbum(long albumId, StringBuilder trail) {
        if (albumId <= 0) {
            mark(trail, "genie-album", "no-album-id");
            return null;
        }
        Bitmap cover = fromGenieAlbumRow(albumId, trail);
        if (cover != null) {
            return cover;
        }
        cover = renderUri(ContentUris.withAppendedId(
                SonyWalkmanContract.GENIE_ALBUM_ART_URI, albumId));
        mark(trail, "genie-albumart", cover != null ? "hit" : "miss");
        return cover;
    }

    private Bitmap fromGenieAlbumRow(long albumId, StringBuilder trail) {
        Cursor cursor = null;
        try {
            Uri uri = ContentUris.withAppendedId(SonyWalkmanContract.GENIE_ALBUMS_URI, albumId);
            // Explicit art columns first (null projections yield only _id);
            // if the albums table lacks them the null form still logs what
            // the table admits to.
            try {
                cursor = context.getContentResolver().query(uri,
                        SonyWalkmanContract.GENIE_ART_COLUMNS, null, null, null);
            } catch (RuntimeException exception) {
                cursor = null;
            }
            if (cursor == null) {
                cursor = context.getContentResolver().query(uri, null, null, null, null);
            }
            if (cursor == null || !cursor.moveToFirst()) {
                mark(trail, "genie-album-row", "unreadable");
                return null;
            }
            String[] names = cursor.getColumnNames();
            if (!loggedAlbumRowColumns) {
                loggedAlbumRowColumns = true;
                StringBuilder line = new StringBuilder("genie album row ").append(albumId).append(':');
                for (int i = 0; i < names.length; i++) {
                    line.append(' ').append(names[i]).append('=').append(describeColumn(cursor, i));
                }
                Log.i(TAG, line.toString());
            }
            for (int index = 0; index < names.length; index++) {
                String lower = names[index].toLowerCase(Locale.US);
                if (!lower.contains("art") && !lower.contains("cover") && !lower.contains("thumb")) {
                    continue;
                }
                if (cursor.isNull(index)) {
                    continue;
                }
                Bitmap cover = coverFromColumn(cursor, index);
                if (cover != null) {
                    mark(trail, "genie-album-row", names[index]);
                    return cover;
                }
            }
            mark(trail, "genie-album-row", "no-art");
            return null;
        } catch (RuntimeException exception) {
            mark(trail, "genie-album-row", "error");
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    // ------------------------------------------------------------ sources 3+4

    /**
     * Joins the Genie row to the platform MediaStore row by title, album,
     * and stored duration. When the surviving candidates disagree on the
     * album, no row is returned - a cover that might belong to a different
     * pressing is worse than the plasma.
     */
    private PlatformRow mapToPlatformRow(MediaServiceClient.Track track, StringBuilder trail) {
        if (track.title == null) {
            mark(trail, "platform", "no-title");
            return null;
        }
        PlatformRow row = findPlatformRow(context, track.title, track.album,
                track.durationMs, track.trackNo);
        mark(trail, "platform", row == null ? "unmatched" : "album " + row.albumId);
        return row;
    }

    /**
     * The strict title+album+duration join, shared with the playback
     * handoff's file fallback (C1) and the spectrum analyzer (B7b) so all
     * three resolve the platform row the same proven way. Returns null
     * rather than guess when nothing - or more than one album - survives
     * the match. A platform duration of 0/NULL is treated as unknown, not
     * as a mismatch: the platform scanner stores exactly that for the
     * >48 kHz FLAC files it cannot parse (measured 2026-07-20), and those
     * are the files phase 2 exists to reach. Among candidates whose
     * durations rank equally - classical box sets repeat movement titles
     * inside one album, and their hi-res rows all read duration=0 - the
     * row whose track number agrees with {@code trackNo} wins: a stored
     * identity signal on both sides, never a guess.
     */
    static PlatformRow findPlatformRow(Context context, String title, String album,
                                       int durationMs, int trackNo) {
        if (title == null) {
            return null;
        }
        PlatformRow row = null;
        if (album != null) {
            row = queryPlatformRow(context, "title = ? AND album = ?",
                    new String[]{title, album}, durationMs, trackNo);
        }
        if (row == null) {
            row = queryPlatformRow(context, "title = ?", new String[]{title},
                    durationMs, trackNo);
        }
        return row;
    }

    /**
     * Whether two track numbers name the same album position. Compared
     * modulo 1000 because the platform scanner encodes multi-disc rips as
     * disc*1000+track while Genie stores the bare tag value; unknown
     * (&lt;= 0) on either side never agrees.
     */
    static boolean trackNumbersAgree(int a, int b) {
        return a > 0 && b > 0 && a % 1000 == b % 1000;
    }

    /**
     * The same join in reverse: a platform row's identity mapped back to
     * its Genie row through the in-memory library snapshot. Used wherever
     * the launcher holds platform ids but must display Genie data - the
     * now-playing poll's id routing (launcher-started queues report
     * platform ids) and the playlist screens (launcher playlists are
     * platform playlists). The discipline is the forward join's: title
     * equality, album equality when both sides know one, duration within
     * the tolerance when both sides know one, the track number breaking
     * ties, and exactly one survivor or null - a dark line, never a guess.
     */
    static LibraryStore.Track findLibraryTrack(LibraryStore.Library library, String title,
                                               String album, int durationMs, int trackNo) {
        if (library == null || title == null) {
            return null;
        }
        List<LibraryStore.Track> matches = new ArrayList<LibraryStore.Track>(2);
        for (LibraryStore.Track candidate : library.tracks) {
            if (!title.equals(candidate.title)) {
                continue;
            }
            if (album != null && candidate.album != null && !album.equals(candidate.album)) {
                continue;
            }
            if (durationMs > 0 && candidate.durationMs > 0
                    && Math.abs(durationMs - candidate.durationMs)
                            > PLATFORM_DURATION_TOLERANCE_MS) {
                continue;
            }
            matches.add(candidate);
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }
        LibraryStore.Track match = null;
        for (LibraryStore.Track candidate : matches) {
            if (!trackNumbersAgree(trackNo, candidate.trackNo)) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = candidate;
        }
        return match;
    }

    private static PlatformRow queryPlatformRow(Context context, String selection,
                                                String[] arguments, int genieDurationMs,
                                                int genieTrackNo) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    new String[]{
                            MediaStore.Audio.Media._ID,
                            MediaStore.Audio.Media.ALBUM_ID,
                            PLATFORM_COLUMN_DURATION,
                            PLATFORM_COLUMN_DATA,
                            PLATFORM_COLUMN_TRACK
                    },
                    selection, arguments, null);
            if (cursor == null) {
                return null;
            }
            long albumId = -1;
            long trackId = -1;
            String dataPath = null;
            long bestDifference = Long.MAX_VALUE;
            boolean bestTrackAgreed = false;
            while (cursor.moveToNext()) {
                long rowDuration = cursor.isNull(2) ? 0 : cursor.getLong(2);
                long difference = 0;
                if (genieDurationMs > 0 && rowDuration > 0) {
                    difference = Math.abs(rowDuration - genieDurationMs);
                    if (difference > PLATFORM_DURATION_TOLERANCE_MS) {
                        continue;
                    }
                } else if (genieDurationMs > 0) {
                    // Unknown platform duration (the scanner's duration=0 on
                    // unparseable hi-res FLAC): keep the candidate, ranked
                    // behind any row whose duration really matched.
                    difference = PLATFORM_DURATION_TOLERANCE_MS;
                }
                long rowAlbumId = cursor.isNull(1) ? 0 : cursor.getLong(1);
                if (albumId < 0) {
                    albumId = rowAlbumId;
                } else if (rowAlbumId != albumId) {
                    return null;
                }
                boolean trackAgreed = trackNumbersAgree(genieTrackNo,
                        cursor.isNull(4) ? 0 : cursor.getInt(4));
                if (difference < bestDifference
                        || (difference == bestDifference && trackAgreed && !bestTrackAgreed)) {
                    bestDifference = difference;
                    bestTrackAgreed = trackAgreed;
                    trackId = cursor.isNull(0) ? -1 : cursor.getLong(0);
                    String data = cursor.isNull(3) ? null : cursor.getString(3);
                    if (data != null) {
                        dataPath = data;
                    }
                }
            }
            return albumId < 0 ? null : new PlatformRow(albumId, trackId, dataPath);
        } catch (RuntimeException exception) {
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private Bitmap fromPlatformAlbumArt(long albumId, StringBuilder trail) {
        if (albumId <= 0) {
            mark(trail, "platform-albumart", "no-album-id");
            return null;
        }
        Bitmap cover = renderUri(ContentUris.withAppendedId(PLATFORM_ALBUM_ART_URI, albumId));
        mark(trail, "platform-albumart", cover != null ? "hit" : "miss");
        return cover;
    }

    private Bitmap fromEmbeddedPicture(String dataPath, StringBuilder trail) {
        if (dataPath == null) {
            mark(trail, "embedded", "no-path");
            return null;
        }
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(dataPath);
            byte[] picture = retriever.getEmbeddedPicture();
            if (picture == null || picture.length == 0) {
                mark(trail, "embedded", "none");
                return null;
            }
            Bitmap cover = renderBytes(picture);
            mark(trail, "embedded", cover != null ? "hit" : "undecodable");
            return cover;
        } catch (RuntimeException exception) {
            mark(trail, "embedded", "error");
            return null;
        } finally {
            retriever.release();
        }
    }

    // ------------------------------------------------------- value extraction

    private Bitmap coverFromColumn(Cursor cursor, int index) {
        switch (cursor.getType(index)) {
            case Cursor.FIELD_TYPE_BLOB:
                byte[] bytes = cursor.getBlob(index);
                return bytes == null || bytes.length == 0 ? null : renderBytes(bytes);
            case Cursor.FIELD_TYPE_STRING:
                return coverFromLocator(cursor.getString(index));
            default:
                return null;
        }
    }

    /** A string art value may be a file path or a content URI; try both. */
    private Bitmap coverFromLocator(String locator) {
        if (locator == null) {
            return null;
        }
        String trimmed = locator.trim();
        if (trimmed.startsWith("/")) {
            File file = new File(trimmed);
            return file.isFile() && file.length() > 0 ? renderFile(trimmed) : null;
        }
        if (trimmed.startsWith("content://")) {
            return renderUri(Uri.parse(trimmed));
        }
        return null;
    }

    private Bitmap renderUri(Uri uri) {
        InputStream input = null;
        try {
            input = context.getContentResolver().openInputStream(uri);
            if (input == null) {
                return null;
            }
            byte[] bytes = readStream(input);
            return bytes == null ? null : renderBytes(bytes);
        } catch (IOException exception) {
            return null;
        } catch (RuntimeException exception) {
            return null;
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                    // Nothing left to release.
                }
            }
        }
    }

    private static byte[] readStream(InputStream input) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(64 * 1024);
        byte[] chunk = new byte[16 * 1024];
        int total = 0;
        int read;
        while ((read = input.read(chunk)) > 0) {
            total += read;
            if (total > MAX_ART_STREAM_BYTES) {
                return null;
            }
            buffer.write(chunk, 0, read);
        }
        return buffer.toByteArray();
    }

    // ------------------------------------------------------------- processing

    private Bitmap renderBytes(byte[] bytes) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            return null;
        }
        options.inJustDecodeBounds = false;
        options.inSampleSize = chooseSampleSize(options.outWidth, options.outHeight);
        Bitmap source = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        return source == null ? null : renderToPanel(source);
    }

    private Bitmap renderFile(String path) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);
        if (options.outWidth <= 0 || options.outHeight <= 0) {
            return null;
        }
        options.inJustDecodeBounds = false;
        options.inSampleSize = chooseSampleSize(options.outWidth, options.outHeight);
        Bitmap source = BitmapFactory.decodeFile(path, options);
        return source == null ? null : renderToPanel(source);
    }

    /** Smallest power-of-two subsampling that still covers the render target. */
    private int chooseSampleSize(int width, int height) {
        int sample = 1;
        while (width / (sample * 2) >= targetW && height / (sample * 2) >= targetH) {
            sample *= 2;
        }
        return sample;
    }

    /**
     * Center-crops to the panel aspect and scales to the render target;
     * FULL COLOR stops there. The two-tone styles then greyscale, stretch
     * contrast past the 2% histogram tails, and map each luma onto the
     * style's dark->light tones: the smooth 256-step ramp (GREYSCALE) or
     * four Bayer-dithered tones (GAMEBOY). Recycles the source.
     */
    private Bitmap renderToPanel(Bitmap source) {
        int panelW = targetW;
        int panelH = targetH;
        int width = source.getWidth();
        int height = source.getHeight();
        int cropW = width;
        int cropH = width * panelH / panelW;
        if (cropH > height) {
            cropH = height;
            cropW = Math.min(width, height * panelW / panelH);
        }
        if (cropW <= 0 || cropH <= 0) {
            source.recycle();
            return null;
        }
        Bitmap panel = Bitmap.createBitmap(panelW, panelH, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(panel);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        int left = (width - cropW) / 2;
        int top = (height - cropH) / 2;
        canvas.drawBitmap(source,
                new Rect(left, top, left + cropW, top + cropH),
                new Rect(0, 0, panelW, panelH),
                paint);
        source.recycle();

        if (artStyle == LauncherSettings.ART_FULL_COLOR) {
            return panel;
        }

        int[] pixels = new int[panelW * panelH];
        panel.getPixels(pixels, 0, panelW, 0, 0, panelW, panelH);
        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            pixels[i] = ((color >> 16 & 0xFF) * 299
                    + (color >> 8 & 0xFF) * 587
                    + (color & 0xFF) * 114) / 1000;
        }

        int[] histogram = new int[256];
        for (int i = 0; i < pixels.length; i++) {
            histogram[pixels[i]]++;
        }
        int clip = pixels.length / 50;
        int low = 0;
        for (int sum = 0; low < 255; low++) {
            sum += histogram[low];
            if (sum > clip) {
                break;
            }
        }
        int high = 255;
        for (int sum = 0; high > 0; high--) {
            sum += histogram[high];
            if (sum > clip) {
                break;
            }
        }
        int range = high - low;

        if (artStyle == LauncherSettings.ART_GAMEBOY) {
            mapGameboy(pixels, panelW, panelH, low, range);
        } else {
            for (int i = 0; i < pixels.length; i++) {
                pixels[i] = greyRamp[stretch(pixels[i], low, range)];
            }
        }
        panel.setPixels(pixels, 0, panelW, 0, 0, panelW, panelH);
        return panel;
    }

    /**
     * The 2-bit renderer: each stretched luma lands between two of the four
     * tones and the Bayer 4x4 threshold (the plasma's matrix) decides which,
     * so flat gradients break into the classic ordered-dither checker.
     */
    private void mapGameboy(int[] pixels, int panelW, int panelH, int low, int range) {
        for (int y = 0, i = 0; y < panelH; y++) {
            int matrixRow = (y % 4) * 4;
            for (int x = 0; x < panelW; x++, i++) {
                int scaled = stretch(pixels[i], low, range) * 3;
                int base = scaled / 255;
                int remainder = scaled % 255;
                int threshold = (FavouriteAppsActivity.BAYER4[matrixRow + (x % 4)] * 255 + 127) / 16;
                int tone = remainder > threshold ? base + 1 : base;
                pixels[i] = gameboyTones[tone > 3 ? 3 : tone];
            }
        }
    }

    private static int stretch(int value, int low, int range) {
        if (range <= 0) {
            return value;
        }
        int stretched = (value - low) * 255 / range;
        if (stretched < 0) {
            return 0;
        }
        return stretched > 255 ? 255 : stretched;
    }

    /** Rebuilds the tone tables when the style's endpoints change. */
    private void ensureTones() {
        if (tonesBuilt && builtDark == toneDark && builtLight == toneLight) {
            return;
        }
        for (int i = 0; i < greyRamp.length; i++) {
            greyRamp[i] = mixTone(i / 255f);
        }
        for (int level = 0; level < gameboyTones.length; level++) {
            gameboyTones[level] = mixTone(level / 3f);
        }
        tonesBuilt = true;
        builtDark = toneDark;
        builtLight = toneLight;
    }

    private int mixTone(float t) {
        int tone = 0xFF000000;
        for (int shift = 16; shift >= 0; shift -= 8) {
            int from = toneDark >> shift & 0xFF;
            int to = toneLight >> shift & 0xFF;
            tone |= Math.round(from + (to - from) * t) << shift;
        }
        return tone;
    }

    // ---------------------------------------------------------------- logging

    private void logMediaRowOnce(Cursor cursor, long trackId) {
        if (loggedMediaRowColumns) {
            return;
        }
        loggedMediaRowColumns = true;
        StringBuilder line = new StringBuilder("genie media row ").append(trackId).append(':');
        for (String column : SonyWalkmanContract.GENIE_ART_COLUMNS) {
            line.append(' ').append(column).append('=')
                    .append(describeColumn(cursor, cursor.getColumnIndex(column)));
        }
        line.append(" album_id=").append(mediaRowAlbumId)
                .append(" (").append(cursor.getColumnCount()).append(" columns)");
        Log.i(TAG, line.toString());
    }

    private static String describeColumn(Cursor cursor, int index) {
        if (index < 0) {
            return "absent";
        }
        if (cursor.isNull(index)) {
            return "null";
        }
        switch (cursor.getType(index)) {
            case Cursor.FIELD_TYPE_BLOB:
                // Size plus a leading hex preview: enough to identify the
                // format (JPEG FFD8, PNG 8950...) - the device run proved
                // Genie stores small non-image blobs here, still unidentified.
                byte[] blob = cursor.getBlob(index);
                StringBuilder head = new StringBuilder();
                for (int i = 0; i < blob.length && i < 12; i++) {
                    head.append(String.format(Locale.US, "%02X", blob[i]));
                }
                return "blob[" + blob.length + ":" + head + "]";
            case Cursor.FIELD_TYPE_STRING:
                String value = cursor.getString(index);
                return value.length() > 60 ? '"' + value.substring(0, 60) + "...\"" : '"' + value + '"';
            case Cursor.FIELD_TYPE_INTEGER:
                return String.valueOf(cursor.getLong(index));
            case Cursor.FIELD_TYPE_FLOAT:
                return String.valueOf(cursor.getDouble(index));
            default:
                return "?";
        }
    }

    /**
     * One-time research peek at the player's transient image cache. Its read
     * guard is a normal-level Sony permission this app deliberately does not
     * declare, so denial is the expected
     * outcome; the log line records the definitive answer either way. The
     * result is never displayed - rows there could not be attributed to the
     * current track anyway.
     */
    private void peekTemporaryProviderOnce() {
        if (peekedTemporaryProvider) {
            return;
        }
        peekedTemporaryProvider = true;
        for (String table : SonyWalkmanContract.TEMPORARY_PROVIDER_TABLES) {
            Cursor cursor = null;
            try {
                cursor = context.getContentResolver().query(
                        SonyWalkmanContract.TEMPORARY_PROVIDER_URI.buildUpon()
                                .appendPath(table).build(),
                        null, null, null, null);
                if (cursor == null) {
                    Log.i(TAG, "temporaryProvider/" + table + ": null cursor");
                    continue;
                }
                StringBuilder line = new StringBuilder("temporaryProvider/").append(table)
                        .append(": ").append(cursor.getCount()).append(" rows, columns ")
                        .append(Arrays.toString(cursor.getColumnNames()));
                if (cursor.moveToFirst()) {
                    line.append(", first row:");
                    for (int i = 0; i < cursor.getColumnCount(); i++) {
                        line.append(' ').append(cursor.getColumnName(i)).append('=')
                                .append(describeColumn(cursor, i));
                    }
                }
                Log.i(TAG, line.toString());
            } catch (SecurityException exception) {
                Log.i(TAG, "temporaryProvider denied at zero permissions: "
                        + exception.getMessage());
                return;
            } catch (RuntimeException exception) {
                Log.i(TAG, "temporaryProvider/" + table + " unreadable: " + exception);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
    }

    private static void mark(StringBuilder trail, String source, String result) {
        if (trail.length() > 0) {
            trail.append(", ");
        }
        trail.append(source).append(':').append(result);
    }

    /** One matched platform MediaStore row; dataPath may still be null. */
    static final class PlatformRow {
        final long albumId;
        /** The platform _ID of the best-matched track; -1 when unmatched. */
        final long trackId;
        final String dataPath;

        PlatformRow(long albumId, long trackId, String dataPath) {
            this.albumId = albumId;
            this.trackId = trackId;
            this.dataPath = dataPath;
        }
    }
}
