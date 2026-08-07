/*
 * ZX1 Retro Interface
 * Envelope store for the offline spectrum (B7b): per-track 8-band level
 * files in internal storage, shared between the analyzer service and the
 * chassis-window renderer. Licensed under GPLv2; see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * The spectrum envelopes: one small file per analyzed track - 8 log-spaced
 * bands, 4-bit levels, 12 frames a second, 4 bytes a frame (~48 B per
 * track-second, 12-17 KB per typical track) - keyed by the track's Genie
 * {@code _id} plus its stored duration, so a re-imported file with the same
 * id but different length can never wear another cut's envelope.
 *
 * One canonical in-memory {@link Envelope} per key: the analyzer appends
 * frames (data first, then the volatile frame count - the renderer on the
 * main thread only ever reads below the count) and mirrors them into the
 * file; the renderer obtains the same object. Files live in
 * {@code files/spectrum/}, are LRU-capped by last-touch time (~20 MB, far
 * beyond a real listening rotation), and carry a version so a future layout
 * change simply re-analyzes. The store never invents data: an envelope
 * holds exactly the frames real decode produced, and {@link #frameFor}
 * answers -1 for any position analysis has not reached.
 */
final class SpectrumStore {

    private static final String TAG = "ZX1Spectrum";
    static final int BANDS = 8;
    static final int FPS = 12;
    /** 4 bits a band: 16 steps, one per ~3 dB - a period peak meter. */
    static final int LEVEL_MAX = 15;
    static final int BYTES_PER_FRAME = BANDS / 2;

    private static final String DIR_NAME = "spectrum";
    private static final int FILE_MAGIC = 0x5A583153; // "ZX1S"
    private static final short FILE_VERSION = 1;
    private static final int HEADER_BYTES = 20;
    private static final long CACHE_CAP_BYTES = 20L * 1024 * 1024;
    /** In-memory envelopes: current track, its neighbours, the analyzer's. */
    private static final int MEMORY_CAP = 8;

    /**
     * One track's envelope. Frames only ever grow; {@code frameCount} is
     * published after the nibbles it covers are written, so any reader
     * staying below it sees consistent data without locking.
     */
    static final class Envelope {
        final long genieId;
        final int durationMs;
        private final byte[] nibbles;
        volatile int frameCount;
        volatile boolean complete;

        Envelope(long genieId, int durationMs, int frameCount, byte[] data,
                 boolean complete) {
            this.genieId = genieId;
            this.durationMs = durationMs;
            int expected = expectedFrames(durationMs);
            int capacity = Math.max(expected, frameCount) * BYTES_PER_FRAME;
            this.nibbles = new byte[capacity];
            if (data != null) {
                System.arraycopy(data, 0, nibbles, 0,
                        Math.min(data.length, capacity));
            }
            this.frameCount = frameCount;
            this.complete = complete;
        }

        /** The whole-track frame count this envelope is aiming for. */
        int expectedFrames() {
            return expectedFrames(durationMs);
        }

        static int expectedFrames(int durationMs) {
            return durationMs > 0 ? (int) ((long) durationMs * FPS / 1000) : 0;
        }

        /** 0..15, only for {@code frame < frameCount}. */
        int level(int frame, int band) {
            byte pair = nibbles[frame * BYTES_PER_FRAME + (band >> 1)];
            return (band & 1) == 0 ? (pair >> 4) & 0xF : pair & 0xF;
        }

        /**
         * Analyzer thread only. Appends one frame of 0..15 levels; returns
         * the 4 packed bytes so the caller can mirror them to disk.
         */
        byte[] append(int[] levels) {
            byte[] packed = new byte[BYTES_PER_FRAME];
            for (int band = 0; band < BANDS; band++) {
                int clamped = levels[band] < 0 ? 0
                        : levels[band] > LEVEL_MAX ? LEVEL_MAX : levels[band];
                int index = band >> 1;
                packed[index] = (band & 1) == 0
                        ? (byte) (clamped << 4)
                        : (byte) (packed[index] | clamped);
            }
            int at = frameCount * BYTES_PER_FRAME;
            if (at + BYTES_PER_FRAME > nibbles.length) {
                return null; // duration lied; stop growing, stay honest
            }
            System.arraycopy(packed, 0, nibbles, at, BYTES_PER_FRAME);
            frameCount++; // publish after the data is in place
            return packed;
        }
    }

    /** Envelopes by key, tiny LRU (access order). */
    private static final Map<String, Envelope> MEMORY =
            new java.util.LinkedHashMap<String, Envelope>(MEMORY_CAP, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Envelope> eldest) {
                    return size() > MEMORY_CAP;
                }
            };
    /**
     * Keys whose analysis failed this process life (no platform row,
     * unreadable or corrupt file, a format even the phase-2 decoders
     * refuse) - not retried until the next boot, so a bad track never
     * spins the service.
     */
    private static final HashSet<String> FAILED = new HashSet<String>();

    private static Handler loader;

    private SpectrumStore() { }

    static String key(long genieId, int durationMs) {
        return genieId + "-" + durationMs;
    }

    /** Any thread. The in-memory envelope, or null when not loaded. */
    static synchronized Envelope peek(long genieId, int durationMs) {
        return MEMORY.get(key(genieId, durationMs));
    }

    static synchronized boolean isFailed(long genieId, int durationMs) {
        return FAILED.contains(key(genieId, durationMs));
    }

    static synchronized void markFailed(long genieId, int durationMs) {
        FAILED.add(key(genieId, durationMs));
    }

    /**
     * Worker threads. The canonical envelope for a key: in-memory if
     * present, else read from disk (partial files resume where they left
     * off), else a fresh empty envelope. Reading touches the file so the
     * LRU sweep sees it as recently used.
     */
    static synchronized Envelope obtain(Context context, long genieId, int durationMs) {
        String key = key(genieId, durationMs);
        Envelope envelope = MEMORY.get(key);
        if (envelope == null) {
            envelope = readFile(context, genieId, durationMs);
            if (envelope == null) {
                envelope = new Envelope(genieId, durationMs, 0, null, false);
            }
            MEMORY.put(key, envelope);
        }
        return envelope;
    }

    /**
     * Main thread. Materializes the canonical envelope off the main thread
     * (disk-backed when a file exists, fresh and empty otherwise) and calls
     * back on the main thread. The caller then peeks the registry - and
     * because the object is canonical, frames the analyzer appends later
     * become visible to it live.
     */
    static void requestLoad(Context context, final long genieId, final int durationMs,
                            final Runnable onLoaded) {
        final Context application = context.getApplicationContext();
        final Handler main = new Handler(context.getMainLooper());
        loaderHandler().post(new Runnable() {
            @Override
            public void run() {
                obtain(application, genieId, durationMs);
                if (onLoaded != null) {
                    main.post(onLoaded);
                }
            }
        });
    }

    private static synchronized Handler loaderHandler() {
        if (loader == null) {
            HandlerThread thread = new HandlerThread("zx1-spectrum-load",
                    Process.THREAD_PRIORITY_BACKGROUND);
            thread.start();
            loader = new Handler(thread.getLooper());
        }
        return loader;
    }

    /**
     * The frame index for a playback position, or -1 when analysis has not
     * produced that frame yet - the renderer then holds the baseline.
     */
    static int frameFor(Envelope envelope, long positionMs) {
        if (envelope == null || positionMs < 0) {
            return -1;
        }
        int frame = (int) (positionMs * FPS / 1000);
        return frame < envelope.frameCount ? frame : -1;
    }

    // ------------------------------------------------------------------ disk

    static File fileFor(Context context, long genieId, int durationMs) {
        File dir = new File(context.getFilesDir(), DIR_NAME);
        return new File(dir, "e" + genieId + "-" + durationMs + ".bin");
    }

    /** Null when the file is absent, unreadable, or a different layout. */
    private static Envelope readFile(Context context, long genieId, int durationMs) {
        File file = fileFor(context, genieId, durationMs);
        if (!file.isFile()) {
            return null;
        }
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            if (raf.readInt() != FILE_MAGIC || raf.readShort() != FILE_VERSION
                    || raf.readByte() != BANDS || raf.readByte() != FPS) {
                return null;
            }
            int storedDuration = raf.readInt();
            raf.readInt(); // source sample rate, informational
            boolean complete = raf.readByte() != 0;
            raf.seek(HEADER_BYTES);
            if (storedDuration != durationMs) {
                return null;
            }
            int frames = (int) ((file.length() - HEADER_BYTES) / BYTES_PER_FRAME);
            byte[] data = new byte[frames * BYTES_PER_FRAME];
            raf.readFully(data);
            file.setLastModified(System.currentTimeMillis());
            return new Envelope(genieId, durationMs, frames, data, complete);
        } catch (IOException exception) {
            return null;
        } catch (RuntimeException exception) {
            return null;
        } finally {
            if (raf != null) {
                try {
                    raf.close();
                } catch (IOException ignored) {
                    // Nothing left to release.
                }
            }
        }
    }

    /**
     * Analyzer thread. An append-mode writer for one envelope's file,
     * creating the header when new and positioning after the last complete
     * frame otherwise.
     */
    static final class Writer {
        private final RandomAccessFile raf;

        Writer(Context context, Envelope envelope, int sourceSampleRate)
                throws IOException {
            File file = fileFor(context, envelope.genieId, envelope.durationMs);
            File dir = file.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("cannot create " + dir);
            }
            raf = new RandomAccessFile(file, "rw");
            if (raf.length() < HEADER_BYTES) {
                raf.setLength(0);
                raf.writeInt(FILE_MAGIC);
                raf.writeShort(FILE_VERSION);
                raf.writeByte(BANDS);
                raf.writeByte(FPS);
                raf.writeInt(envelope.durationMs);
                raf.writeInt(sourceSampleRate);
                raf.writeByte(0);
                raf.write(new byte[HEADER_BYTES - 17]);
            }
            raf.seek(HEADER_BYTES + (long) envelope.frameCount * BYTES_PER_FRAME);
            raf.setLength(HEADER_BYTES + (long) envelope.frameCount * BYTES_PER_FRAME);
        }

        void append(byte[] packedFrame) throws IOException {
            raf.write(packedFrame);
        }

        void finish(boolean complete) {
            try {
                if (complete) {
                    long end = raf.getFilePointer();
                    raf.seek(16);
                    raf.writeByte(1);
                    raf.seek(end);
                }
                raf.getFD().sync();
            } catch (IOException exception) {
                Log.w(TAG, "envelope flush failed", exception);
            }
            try {
                raf.close();
            } catch (IOException ignored) {
                // Nothing left to release.
            }
        }
    }

    /**
     * Analyzer thread, after a track finishes. Oldest-touched envelope
     * files go first once the directory outgrows the cap; the file just
     * written is never the victim.
     */
    static void enforceCap(Context context, File justWritten) {
        File dir = new File(context.getFilesDir(), DIR_NAME);
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        long total = 0;
        for (File file : files) {
            total += file.length();
        }
        if (total <= CACHE_CAP_BYTES) {
            return;
        }
        Arrays.sort(files, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                long difference = left.lastModified() - right.lastModified();
                return difference < 0 ? -1 : difference > 0 ? 1 : 0;
            }
        });
        for (File file : files) {
            if (total <= CACHE_CAP_BYTES) {
                break;
            }
            if (file.equals(justWritten)) {
                continue;
            }
            total -= file.length();
            Log.i(TAG, "cache cap: evicting " + file.getName());
            file.delete();
        }
    }
}
