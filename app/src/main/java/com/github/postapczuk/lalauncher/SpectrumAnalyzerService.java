/*
 * ZX1 Retro Interface
 * Offline spectrum analyzer (B7b): decodes a track once, at the lowest
 * thread priority, into its stored 8-band envelope. Licensed under GPLv2;
 * see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import android.app.Service;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * Analyzes one track at a time into a {@link SpectrumStore} envelope: the
 * same store-once/replay-at-the-playhead architecture Sony's own visualizer
 * uses, because no Android-side point can observe this device's playback
 * PCM (findings "Live spectrum capture is impossible"). Nothing here taps
 * audio - the analyzer opens the track's file like any reader and the
 * window later replays only what was really measured.
 *
 * Triggered by home's ~1 s poll when the playing track has no complete
 * envelope (the poll is the proven, zero-cost trigger; a standing
 * addListener client in a background service would be a heavier protocol
 * commitment for the same information). Once started, a pass runs to the
 * track's end even while home is paused. Settings &gt; Info Screen set to
 * CLOCK turns analysis off wholesale: home stops asking, a running pass
 * yields ({@link #yieldRunningPass}), and {@link #analyze} refuses
 * stragglers - nothing marked failed, so re-enabling simply asks again. There is no bulk pre-analysis -
 * only played tracks are analyzed, so the ~20 MB cap fills with the real
 * rotation and the battery cost stays one decode per first listen
 * (measured 9-43x realtime; a typical track costs well under a minute of
 * lowest-priority CPU, once).
 *
 * Decode paths, measured on device 2026-07-20: FLAC at 48 kHz and below
 * arrives as raw PCM straight from Sony's stock FLACExtractor; MP3 and AAC
 * decode through MediaCodec. FLAC above 48 kHz does not open there (the
 * JB-era extractor exposes zero tracks) and ALAC is invisible to the
 * platform stack entirely - those two route by their Genie row to the
 * hand-written decoders ({@link AlacDecoder}, {@link FlacDecoder}; phase 2),
 * verified bit-exact off-device and fed through the same analyzer. A newer
 * request supersedes the running pass, which flushes its partial envelope
 * and yields; the next play of that track resumes from the stored frame
 * count - via seekTo on the platform path, by re-decoding and skipping the
 * already-analyzed prefix on the hand-written path.
 */
public class SpectrumAnalyzerService extends Service {

    private static final String TAG = "ZX1Spectrum";
    static final String EXTRA_GENIE_ID = "genieId";
    static final String EXTRA_DURATION_MS = "durationMs";

    private static final int MSG_ANALYZE = 1;
    private static final long CODEC_TIMEOUT_US = 10000L;
    /** Meter law: 16 steps of 3 dB from -48 dBFS to full scale. */
    private static final float DB_FLOOR = -48f;
    private static final float DB_PER_STEP = 3f;
    /** Band plan: 8 log-spaced bands, 40 Hz to 16 kHz. */
    private static final double BAND_LOW_HZ = 40d;
    private static final double BAND_HIGH_HZ = 16000d;

    /** The newest requested key; a running pass yields when it changes. */
    private static volatile String latestRequest;

    private HandlerThread workerThread;
    private Handler worker;

    /** Home's request path; the poll-side dedup lives in the caller. */
    static void requestAnalysis(Context context, long genieId, int durationMs) {
        Intent intent = new Intent(context, SpectrumAnalyzerService.class);
        intent.putExtra(EXTRA_GENIE_ID, genieId);
        intent.putExtra(EXTRA_DURATION_MS, durationMs);
        context.startService(intent);
    }

    /**
     * SETTINGS switched the info window to the clock: nothing is wanted
     * any more, so the running pass yields at its next block (the partial
     * envelope is kept, honest as ever). The settings check in
     * {@link #analyze} refuses queued stragglers the same turn.
     */
    static void yieldRunningPass() {
        latestRequest = null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        workerThread = new HandlerThread("zx1-spectrum-analyze",
                Process.THREAD_PRIORITY_LOWEST);
        workerThread.start();
        worker = new Handler(workerThread.getLooper(), new Handler.Callback() {
            @Override
            public boolean handleMessage(Message message) {
                long[] key = (long[]) message.obj;
                analyze(key[0], (int) key[1]);
                stopSelf(message.arg1);
                return true;
            }
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        long genieId = intent.getLongExtra(EXTRA_GENIE_ID, 0);
        int durationMs = intent.getIntExtra(EXTRA_DURATION_MS, 0);
        if (genieId <= 0 || durationMs <= 0) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        latestRequest = SpectrumStore.key(genieId, durationMs);
        // A queued-but-unstarted pass is superseded; the running pass sees
        // latestRequest change and yields on its own.
        worker.removeMessages(MSG_ANALYZE);
        worker.sendMessage(Message.obtain(worker, MSG_ANALYZE, startId, 0,
                new long[]{genieId, durationMs}));
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

    // ------------------------------------------------------------- analysis

    /** Worker thread. One full (or resumed, or yielded) analysis pass. */
    private void analyze(long genieId, int durationMs) {
        String key = SpectrumStore.key(genieId, durationMs);
        if (LauncherSettings.getInfoScreen(this) != LauncherSettings.INFO_SPECTRUM) {
            // The info window is the clock: analysis is off wholesale.
            // Not a failure - nothing is marked, so re-enabling the
            // spectrum simply asks again.
            Log.i(TAG, "spectrum off; skipping " + key);
            return;
        }
        if (SpectrumStore.isFailed(genieId, durationMs)) {
            return;
        }
        SpectrumStore.Envelope envelope = SpectrumStore.obtain(this, genieId, durationMs);
        if (envelope.complete) {
            return;
        }
        GenieRow row = queryGenieRow(genieId);
        if (row == null || row.title == null) {
            giveUp(key, genieId, durationMs, "genie row unreadable");
            return;
        }
        AlbumArtResolver.PlatformRow platformRow = AlbumArtResolver.findPlatformRow(
                this, row.title, row.album, row.durationMs, row.trackNo);
        if (platformRow == null || platformRow.dataPath == null) {
            giveUp(key, genieId, durationMs, "no platform row for the file path");
            return;
        }
        // Phase-2 router, decided from the Genie row before any extractor
        // opens: ALAC and hi-res FLAC are exactly what the platform stack
        // cannot decode (measured), so they go to the hand-written path.
        boolean handWritten = row.codec == SonyWalkmanContract.CODEC_ALAC
                || (row.codec == SonyWalkmanContract.CODEC_FLAC
                && row.sampleRate > 48000);
        int resumeFrame = envelope.frameCount;
        Log.i(TAG, (resumeFrame > 0 ? "resume" : "start") + " " + key
                + " \"" + row.title + "\" from frame " + resumeFrame
                + "/" + envelope.expectedFrames());
        try {
            if (handWritten) {
                runOwnDecode(envelope, platformRow.dataPath, key,
                        row.codec == SonyWalkmanContract.CODEC_ALAC);
            } else {
                runDecode(envelope, platformRow.dataPath, key);
            }
        } catch (IOException exception) {
            giveUp(key, genieId, durationMs, "decode failed: " + exception);
        } catch (RuntimeException exception) {
            // A codec or extractor tantrum must never take the process down.
            giveUp(key, genieId, durationMs, "decode failed: " + exception);
        }
    }

    private void giveUp(String key, long genieId, int durationMs, String reason) {
        SpectrumStore.markFailed(genieId, durationMs);
        Log.i(TAG, "baseline holds for " + key + ": " + reason);
    }

    /**
     * Opens the file, seeks to the resume point, and streams PCM into
     * {@link BandAnalyzer} until EOF or a newer request supersedes this
     * pass. Raw PCM (Sony's FLACExtractor) drains straight from the
     * extractor; compressed mimes run the API 16 MediaCodec loop.
     */
    private void runDecode(SpectrumStore.Envelope envelope, String path, String key)
            throws IOException {
        long wallStart = SystemClock.uptimeMillis();
        int startFrame = envelope.frameCount;
        MediaExtractor extractor = new MediaExtractor();
        SpectrumStore.Writer writer = null;
        boolean complete = false;
        try {
            extractor.setDataSource(path);
            int audioTrack = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    format = candidate;
                    break;
                }
            }
            if (audioTrack < 0) {
                // Hi-res FLAC used to land here (zero tracks exposed,
                // measured 2026-07-20); the router now catches it first,
                // so this is a genuinely trackless or unreadable file.
                giveUp(key, envelope.genieId, envelope.durationMs,
                        "no decodable audio track");
                return;
            }
            extractor.selectTrack(audioTrack);
            String mime = format.getString(MediaFormat.KEY_MIME);
            int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 0;
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                    ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 0;
            if (sampleRate <= 0 || channels <= 0) {
                giveUp(key, envelope.genieId, envelope.durationMs,
                        "format lacks rate/channels: " + format);
                return;
            }
            BandAnalyzer analyzer = new BandAnalyzer(sampleRate, channels, envelope);
            long resumeSample = (long) startFrame * analyzer.hopSamples;
            if (resumeSample > 0) {
                long resumeUs = resumeSample * 1000000L / sampleRate;
                extractor.seekTo(resumeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC);
            }
            analyzer.expectResumeAt(resumeSample);
            writer = new SpectrumStore.Writer(this, envelope, sampleRate);
            analyzer.writer = writer;
            boolean rawPcm = "audio/raw".equals(mime);
            boolean finished = rawPcm
                    ? drainRawPcm(extractor, analyzer, key)
                    : decodeViaCodec(extractor, format, mime, analyzer, key);
            if (analyzer.abandoned) {
                // The resume seek landed past the stored frame count; frames
                // in between would have to be invented, so this pass keeps
                // the honest partial envelope and retries next boot.
                SpectrumStore.markFailed(envelope.genieId, envelope.durationMs);
                Log.i(TAG, "abandon " + key + ": resume seek overshot frame "
                        + envelope.frameCount + "; partial envelope holds");
            } else if (finished) {
                analyzer.flushTail();
                complete = true;
                long wallMs = SystemClock.uptimeMillis() - wallStart;
                double analyzedSec = (envelope.frameCount - startFrame)
                        / (double) SpectrumStore.FPS;
                Log.i(TAG, String.format(Locale.US,
                        "finish %s: %d frames (%.1f s audio) in %.1f s = %.1fx realtime, %s",
                        key, envelope.frameCount, analyzedSec,
                        wallMs / 1000.0, wallMs > 0 ? analyzedSec * 1000.0 / wallMs : 0d,
                        rawPcm ? "extractor pcm" : mime));
            } else {
                Log.i(TAG, "yield " + key + " at frame " + envelope.frameCount
                        + (latestRequest == null ? " (spectrum off)" : " (newer request)"));
            }
        } finally {
            if (writer != null) {
                writer.finish(complete);
                if (complete) {
                    envelope.complete = true;
                    SpectrumStore.enforceCap(this, SpectrumStore.fileFor(
                            this, envelope.genieId, envelope.durationMs));
                }
            }
            extractor.release();
        }
    }

    /**
     * The phase-2 path: ALAC and hi-res FLAC through the hand-written
     * decoders, streamed into the same {@link BandAnalyzer}. A resumed
     * pass decodes from the top and lets the PTS anchor drop the
     * already-analyzed prefix - sample-exact, at the cost of re-decoding
     * what was already heard.
     */
    private void runOwnDecode(SpectrumStore.Envelope envelope, String path,
                              String key, boolean alac) throws IOException {
        long wallStart = SystemClock.uptimeMillis();
        int startFrame = envelope.frameCount;
        PcmDecoder decoder = alac ? new AlacDecoder(path) : new FlacDecoder(path);
        SpectrumStore.Writer writer = null;
        boolean complete = false;
        try {
            BandAnalyzer analyzer = new BandAnalyzer(decoder.sampleRate,
                    decoder.channels, envelope);
            analyzer.expectResumeAt((long) startFrame * analyzer.hopSamples);
            writer = new SpectrumStore.Writer(this, envelope, decoder.sampleRate);
            analyzer.writer = writer;
            boolean finished = drainDecoder(decoder, analyzer, key);
            if (analyzer.abandoned) {
                SpectrumStore.markFailed(envelope.genieId, envelope.durationMs);
                Log.i(TAG, "abandon " + key + ": decoder lost the resume anchor at frame "
                        + envelope.frameCount + "; partial envelope holds");
            } else if (finished) {
                analyzer.flushTail();
                complete = true;
                long wallMs = SystemClock.uptimeMillis() - wallStart;
                double analyzedSec = (envelope.frameCount - startFrame)
                        / (double) SpectrumStore.FPS;
                Log.i(TAG, String.format(Locale.US,
                        "finish %s: %d frames (%.1f s audio) in %.1f s = %.1fx realtime, %s",
                        key, envelope.frameCount, analyzedSec,
                        wallMs / 1000.0, wallMs > 0 ? analyzedSec * 1000.0 / wallMs : 0d,
                        alac ? "hand-written alac" : "hand-written flac"));
            } else {
                Log.i(TAG, "yield " + key + " at frame " + envelope.frameCount
                        + (latestRequest == null ? " (spectrum off)" : " (newer request)"));
            }
        } finally {
            if (writer != null) {
                writer.finish(complete);
                if (complete) {
                    envelope.complete = true;
                    SpectrumStore.enforceCap(this, SpectrumStore.fileFor(
                            this, envelope.genieId, envelope.durationMs));
                }
            }
            decoder.close();
        }
    }

    /** True on EOF; false when a newer request superseded this pass. */
    private boolean drainDecoder(PcmDecoder decoder, BandAnalyzer analyzer,
                                 String key) throws IOException {
        ByteBuffer wrapped = null;
        byte[] backing = null;
        while (decoder.nextBlock()) {
            if (backing != decoder.pcm) {
                backing = decoder.pcm;             // grown; re-wrap once
                wrapped = ByteBuffer.wrap(backing).order(ByteOrder.LITTLE_ENDIAN);
            }
            wrapped.position(0);
            wrapped.limit(decoder.pcmBytes);
            analyzer.feed(wrapped, decoder.pcmBytes, decoder.pcmPtsUs);
            if (!key.equals(latestRequest)) {
                return false;
            }
        }
        return true;
    }

    /** True on EOF; false when a newer request superseded this pass. */
    private boolean drainRawPcm(MediaExtractor extractor, BandAnalyzer analyzer,
                                String key) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocateDirect(1 << 20)
                .order(ByteOrder.nativeOrder());
        while (true) {
            buffer.clear();
            int size = extractor.readSampleData(buffer, 0);
            if (size < 0) {
                return true;
            }
            buffer.limit(size);
            buffer.position(0);
            analyzer.feed(buffer, size, extractor.getSampleTime());
            if (!extractor.advance()) {
                return true;
            }
            if (!key.equals(latestRequest)) {
                return false;
            }
        }
    }

    /** The API 16 buffer-array decode loop for MP3/AAC. */
    private boolean decodeViaCodec(MediaExtractor extractor, MediaFormat format,
                                   String mime, BandAnalyzer analyzer, String key)
            throws IOException {
        MediaCodec codec = MediaCodec.createDecoderByType(mime);
        try {
            codec.configure(format, null, null, 0);
            codec.start();
            ByteBuffer[] inputs = codec.getInputBuffers();
            ByteBuffer[] outputs = codec.getOutputBuffers();
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            while (true) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(CODEC_TIMEOUT_US);
                    if (inIndex >= 0) {
                        ByteBuffer input = inputs[inIndex];
                        input.clear();
                        int size = extractor.readSampleData(input, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size,
                                    extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int outIndex = codec.dequeueOutputBuffer(info, CODEC_TIMEOUT_US);
                if (outIndex >= 0) {
                    if (info.size > 0) {
                        ByteBuffer output = outputs[outIndex];
                        output.order(ByteOrder.nativeOrder());
                        output.limit(info.offset + info.size);
                        output.position(info.offset);
                        analyzer.feed(output, info.size, info.presentationTimeUs);
                    }
                    boolean eos = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(outIndex, false);
                    if (eos) {
                        return true;
                    }
                } else if (outIndex == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED) {
                    outputs = codec.getOutputBuffers();
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat out = codec.getOutputFormat();
                    if (out.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                            && out.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                        analyzer.reconfigure(
                                out.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                                out.getInteger(MediaFormat.KEY_CHANNEL_COUNT));
                    }
                }
                if (!key.equals(latestRequest)) {
                    return false;
                }
            }
        } finally {
            try {
                codec.stop();
            } catch (RuntimeException ignored) {
                // Codec never reached the started state; release still runs.
            }
            codec.release();
        }
    }

    /** The lean Genie read the analyzer needs; null when unreadable. */
    private GenieRow queryGenieRow(long genieId) {
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(
                    ContentUris.withAppendedId(SonyWalkmanContract.GENIE_AUDIO_URI, genieId),
                    new String[]{
                            SonyWalkmanContract.COLUMN_TITLE,
                            SonyWalkmanContract.COLUMN_ALBUM,
                            SonyWalkmanContract.COLUMN_DURATION,
                            SonyWalkmanContract.COLUMN_TRACK_NO,
                            SonyWalkmanContract.COLUMN_AUDIO_CODEC,
                            SonyWalkmanContract.COLUMN_SAMPLE_RATE},
                    null, null, null);
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            GenieRow row = new GenieRow();
            int index = cursor.getColumnIndex(SonyWalkmanContract.COLUMN_TITLE);
            row.title = index < 0 || cursor.isNull(index) ? null : cursor.getString(index);
            index = cursor.getColumnIndex(SonyWalkmanContract.COLUMN_ALBUM);
            row.album = index < 0 || cursor.isNull(index) ? null : cursor.getString(index);
            index = cursor.getColumnIndex(SonyWalkmanContract.COLUMN_DURATION);
            row.durationMs = index < 0 || cursor.isNull(index) ? -1 : cursor.getInt(index);
            index = cursor.getColumnIndex(SonyWalkmanContract.COLUMN_TRACK_NO);
            row.trackNo = index < 0 || cursor.isNull(index) ? -1 : cursor.getInt(index);
            index = cursor.getColumnIndex(SonyWalkmanContract.COLUMN_AUDIO_CODEC);
            row.codec = index < 0 || cursor.isNull(index) ? -1 : cursor.getInt(index);
            index = cursor.getColumnIndex(SonyWalkmanContract.COLUMN_SAMPLE_RATE);
            row.sampleRate = index < 0 || cursor.isNull(index) ? -1 : cursor.getInt(index);
            return row;
        } catch (RuntimeException exception) {
            return null;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    private static final class GenieRow {
        String title;
        String album;
        int durationMs;
        int trackNo;
        int codec;
        int sampleRate;
    }

    /**
     * PCM in, envelope frames out: interleaved 16-bit frames are mixed to
     * mono, gathered into 1/12 s hops (12 divides every rate this library
     * uses exactly), and each completed hop's leading window goes through
     * the FFT into 8 log-spaced band peaks quantized to the 16-step meter
     * law. Resume skipping is sample-exact against the stream's own
     * presentation stamps.
     */
    private static final class BandAnalyzer {
        int hopSamples;
        SpectrumStore.Writer writer;

        private final SpectrumStore.Envelope envelope;
        private int sampleRate;
        private int channels;
        private float[] hop;
        private int hopFill;
        private Fft fft;
        private int[] bandStart;
        private long skipRemaining;
        private long resumeSample;
        private boolean positionLocked;
        private int mixPhase;
        private int mixAccum;
        private boolean full;
        boolean abandoned;

        BandAnalyzer(int sampleRate, int channels, SpectrumStore.Envelope envelope) {
            this.envelope = envelope;
            configure(sampleRate, channels);
        }

        private void configure(int sampleRate, int channels) {
            this.sampleRate = sampleRate;
            this.channels = channels;
            hopSamples = Math.max(1, sampleRate / SpectrumStore.FPS);
            hop = new float[hopSamples];
            hopFill = 0;
            mixPhase = 0;
            mixAccum = 0;
            int fftSize = sampleRate <= 48000 ? 2048 : sampleRate <= 96000 ? 4096 : 8192;
            fft = new Fft(fftSize);
            bandStart = new int[SpectrumStore.BANDS + 1];
            double top = Math.min(BAND_HIGH_HZ, sampleRate / 2d);
            for (int band = 0; band <= SpectrumStore.BANDS; band++) {
                double hz = BAND_LOW_HZ * Math.pow(top / BAND_LOW_HZ,
                        band / (double) SpectrumStore.BANDS);
                int bin = (int) Math.round(hz * fftSize / sampleRate);
                bandStart[band] = Math.max(band == 0 ? 1 : bandStart[band - 1] + 1,
                        Math.min(bin, fftSize / 2));
            }
        }

        /** The decoder's real output format wins over container metadata. */
        void reconfigure(int sampleRate, int channels) {
            if (sampleRate == this.sampleRate && channels == this.channels) {
                return;
            }
            long consumedFrames = envelope.frameCount;
            configure(sampleRate, channels);
            // Re-anchor the resume target in the new rate's sample domain.
            resumeSample = consumedFrames * (long) hopSamples;
        }

        void expectResumeAt(long resumeSample) {
            this.resumeSample = resumeSample;
            positionLocked = false;
        }

        /**
         * One PCM chunk. The first chunk's timestamp anchors the stream
         * position so resumed passes drop exactly the already-analyzed
         * samples. A seek that lands past the target would force invented
         * frames into the gap, so that pass abandons instead (the stored
         * partial stays honest); with SEEK_TO_PREVIOUS_SYNC it should
         * never happen.
         */
        void feed(ByteBuffer chunk, int sizeBytes, long ptsUs) throws IOException {
            if (full || abandoned) {
                return;
            }
            if (!positionLocked) {
                long atSample = (long) Math.floor(ptsUs / 1000000d * sampleRate);
                if (atSample < 0) {
                    atSample = 0;
                }
                if (atSample > resumeSample) {
                    abandoned = true;
                    return;
                }
                skipRemaining = (resumeSample - atSample) * channels;
                positionLocked = true;
            }
            int shorts = sizeBytes / 2;
            for (int i = 0; i < shorts; i++) {
                short sample = chunk.getShort();
                if (skipRemaining > 0) {
                    skipRemaining--;
                    continue;
                }
                mixAccum += sample;
                if (++mixPhase == channels) {
                    hop[hopFill++] = mixAccum / (32768f * channels);
                    mixPhase = 0;
                    mixAccum = 0;
                    if (hopFill == hopSamples) {
                        emitFrame();
                        hopFill = 0;
                        if (full) {
                            return;
                        }
                    }
                }
            }
        }

        /** A trailing partial hop at EOF still yields its honest frame. */
        void flushTail() throws IOException {
            if (hopFill > fft.size / 4 && !full && !abandoned) {
                emitFrame();
            }
            hopFill = 0;
        }

        private void emitFrame() throws IOException {
            int window = Math.min(hopFill, fft.size);
            float[] spectrum = fft.magnitudesSquared(hop, window);
            int[] levels = new int[SpectrumStore.BANDS];
            for (int band = 0; band < SpectrumStore.BANDS; band++) {
                float peak = 0f;
                int end = Math.max(bandStart[band + 1], bandStart[band] + 1);
                for (int bin = bandStart[band]; bin < end && bin < spectrum.length; bin++) {
                    if (spectrum[bin] > peak) {
                        peak = spectrum[bin];
                    }
                }
                float db = peak <= 0f ? DB_FLOOR
                        : (float) (10d * Math.log10(peak / fft.fullScalePower));
                levels[band] = Math.round((db - DB_FLOOR) / DB_PER_STEP);
            }
            byte[] packed = envelope.append(levels);
            if (packed == null) {
                full = true; // duration metadata undersold the audio
                return;
            }
            writer.append(packed);
        }
    }

    /**
     * Plain iterative radix-2 FFT with a Hann window - the classic ~50
     * lines, no dependencies. Input windows shorter than the transform are
     * zero-padded; magnitudes come back as squared power per bin,
     * referenced to {@link #fullScalePower} for a full-scale sine.
     */
    private static final class Fft {
        final int size;
        final float fullScalePower;
        private final float[] window;
        private final float[] re;
        private final float[] im;
        private final float[] cos;
        private final float[] sin;
        private final float[] power;

        Fft(int size) {
            this.size = size;
            window = new float[size];
            re = new float[size];
            im = new float[size];
            cos = new float[size / 2];
            sin = new float[size / 2];
            power = new float[size / 2 + 1];
            for (int i = 0; i < size; i++) {
                window[i] = 0.5f - 0.5f * (float) Math.cos(2d * Math.PI * i / (size - 1));
            }
            for (int i = 0; i < size / 2; i++) {
                cos[i] = (float) Math.cos(2d * Math.PI * i / size);
                sin[i] = -(float) Math.sin(2d * Math.PI * i / size);
            }
            // Hann coherent gain is 1/2: a full-scale sine peaks at N/4.
            float peak = size / 4f;
            fullScalePower = peak * peak;
        }

        /** Squared magnitudes for bins 0..size/2; input is not modified. */
        float[] magnitudesSquared(float[] samples, int count) {
            int n = size;
            for (int i = 0; i < n; i++) {
                re[i] = i < count ? samples[i] * window[i] : 0f;
                im[i] = 0f;
            }
            // Bit-reversal permutation.
            for (int i = 1, j = 0; i < n; i++) {
                int bit = n >> 1;
                for (; (j & bit) != 0; bit >>= 1) {
                    j ^= bit;
                }
                j ^= bit;
                if (i < j) {
                    float swap = re[i];
                    re[i] = re[j];
                    re[j] = swap;
                }
            }
            // Butterflies.
            for (int len = 2; len <= n; len <<= 1) {
                int half = len >> 1;
                int step = n / len;
                for (int base = 0; base < n; base += len) {
                    for (int k = 0; k < half; k++) {
                        int even = base + k;
                        int odd = even + half;
                        float wr = cos[k * step];
                        float wi = sin[k * step];
                        float or = re[odd] * wr - im[odd] * wi;
                        float oi = re[odd] * wi + im[odd] * wr;
                        re[odd] = re[even] - or;
                        im[odd] = im[even] - oi;
                        re[even] += or;
                        im[even] += oi;
                    }
                }
            }
            for (int bin = 0; bin <= n / 2; bin++) {
                power[bin] = re[bin] * re[bin] + im[bin] * im[bin];
            }
            return power;
        }
    }
}
