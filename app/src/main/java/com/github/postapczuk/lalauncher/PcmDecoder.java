/*
 * ZX1 Retro Interface
 * Shared seam for the hand-written spectrum decoders (B7b phase 2): block
 * PCM output plus the MSB-first bit reader ALAC and FLAC both parse with.
 * Licensed under GPLv2; see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import java.io.IOException;

/**
 * One hand-written decoder feeding the spectrum analyzer: the caller pulls
 * {@link #nextBlock()} until it returns false at end of stream and feeds
 * {@link #pcm} to the band analyzer. Output is interleaved 16-bit
 * little-endian PCM whatever the source depth (24-bit is fed {@code >> 8},
 * matching the platform extractor's own measured truncation - a 16-step
 * meter cannot tell). Spectrum-grade honesty rule: any parse or decode
 * error throws, the pass stops, and the envelope keeps only the frames
 * real decode produced - a wrong guess is never fed onward.
 *
 * Pure Java 7 / API 16, no Android imports, so the same source compiles
 * on a desktop JVM against a reference decoder.
 */
abstract class PcmDecoder {

    /** From the stream's own header, set before the constructor returns. */
    int sampleRate;
    int channels;
    int bitDepth;

    /** The current block: interleaved 16-bit little-endian frames. */
    byte[] pcm;
    int pcmBytes;
    /** Presentation stamp of the block's first frame. */
    long pcmPtsUs;

    /** Sample frames decoded so far - the pts clock. */
    long samplePos;

    /**
     * Decodes the next source block (one ALAC packet / one FLAC frame)
     * into {@link #pcm}. False at clean end of stream; any error throws.
     */
    abstract boolean nextBlock() throws IOException;

    abstract void close();

    /** Interleaves per-channel planes into {@link #pcm} and stamps pts. */
    final void emitBlock(int[][] planes, int frames, int shift) {
        int bytes = frames * channels * 2;
        if (pcm == null || pcm.length < bytes) {
            pcm = new byte[bytes];
        }
        byte[] out = pcm;
        if (channels == 2) {                       // the library's shape
            int[] left = planes[0];
            int[] right = planes[1];
            int at = 0;
            for (int i = 0; i < frames; i++) {
                int value = shift >= 0 ? left[i] >> shift : left[i] << -shift;
                if (value > 32767) {
                    value = 32767;
                } else if (value < -32768) {
                    value = -32768;
                }
                out[at++] = (byte) value;
                out[at++] = (byte) (value >> 8);
                value = shift >= 0 ? right[i] >> shift : right[i] << -shift;
                if (value > 32767) {
                    value = 32767;
                } else if (value < -32768) {
                    value = -32768;
                }
                out[at++] = (byte) value;
                out[at++] = (byte) (value >> 8);
            }
        } else {
            int at = 0;
            for (int i = 0; i < frames; i++) {
                for (int channel = 0; channel < channels; channel++) {
                    int value = planes[channel][i];
                    value = shift >= 0 ? value >> shift : value << -shift;
                    if (value > 32767) {
                        value = 32767;
                    } else if (value < -32768) {
                        value = -32768;
                    }
                    out[at++] = (byte) value;
                    out[at++] = (byte) (value >> 8);
                }
            }
        }
        pcmBytes = bytes;
        pcmPtsUs = samplePos * 1000000L / sampleRate;
        samplePos += frames;
    }

    /**
     * MSB-first bit reader over a byte window. Refills past the window
     * inject zero pad bytes and are counted in {@link #padBits}; a stream
     * whose consumption stays within real bytes always ends holding at
     * least its pad ({@code count >= padBits}), so {@link #overran()}
     * detects true overruns while forgiving the table lookahead the
     * entropy loops peek with. Block decoders check it before an overrun
     * block's samples can be emitted.
     *
     * The state fields are package-visible on purpose: Dalvik's JIT
     * neither inlines these small virtual methods nor keeps fields in
     * registers across them, and the entropy loops pay a call per sample
     * (measured 2026-07-20: the difference between ~2x and ~5x realtime
     * on hi-res). The per-sample decoders copy the state into locals,
     * run inlined, and store it back; everything cold uses the methods.
     */
    static final class Bits {
        byte[] data;
        int pos;
        int end;
        /** Valid bits live in the top of the cache. */
        int cache;
        int count;
        int padBits;

        void reset(byte[] data, int from, int to) {
            this.data = data;
            pos = from;
            end = to;
            cache = 0;
            count = 0;
            padBits = 0;
        }

        /** True once any consumed bit came from past the window. */
        boolean overran() {
            return padBits > count;
        }

        private void fill(int need) {
            while (count < need) {
                int next;
                if (pos < end) {
                    next = data[pos++] & 0xFF;
                } else {
                    next = 0;
                    padBits += 8;
                }
                cache |= next << (24 - count);
                count += 8;
            }
        }

        /** Unsigned read, 0..25 bits. */
        int read(int n) {
            if (n == 0) {
                return 0;
            }
            if (count < n) {
                fill(n);
            }
            int value = cache >>> (32 - n);
            cache <<= n;
            count -= n;
            return value;
        }

        int readBit() {
            if (count == 0) {
                fill(1);
            }
            int value = cache >>> 31;
            cache <<= 1;
            count--;
            return value;
        }

        /** The next n bits without consuming them, 1..25. */
        int peek(int n) {
            if (count < n) {
                fill(n);
            }
            return cache >>> (32 - n);
        }

        /** Only after a {@link #peek} of at least n bits. */
        void skip(int n) {
            cache <<= n;
            count -= n;
        }

        int read32() {
            return (read(16) << 16) | read(16);
        }

        int readSigned(int n) {
            if (n == 0) {
                return 0;
            }
            int value = read(n);
            return (value << (32 - n)) >> (32 - n);
        }

        void skipBytes(int bytes) {
            for (int left = bytes * 8; left > 0; left -= 24) {
                read(left > 24 ? 24 : left);
            }
        }

        /**
         * Interleaved bulk read - {@code first[i]} then {@code second[i]}
         * ({@code second} null for one channel), n of 1..25 bits each.
         * One call per block instead of one per sample (see the class
         * comment on Dalvik).
         */
        void readBulk(int n, int[] first, int[] second, int frames) {
            byte[] d = data;
            int p = pos;
            int e = end;
            int c = cache;
            int h = count;
            int pad = padBits;
            for (int i = 0; i < frames; i++) {
                if (h < n) {
                    do {
                        int next;
                        if (p < e) {
                            next = d[p++] & 0xFF;
                        } else {
                            next = 0;
                            pad += 8;
                        }
                        c |= next << (24 - h);
                        h += 8;
                    } while (h < n);
                }
                first[i] = c >>> (32 - n);
                c <<= n;
                h -= n;
                if (second != null) {
                    if (h < n) {
                        do {
                            int next;
                            if (p < e) {
                                next = d[p++] & 0xFF;
                            } else {
                                next = 0;
                                pad += 8;
                            }
                            c |= next << (24 - h);
                            h += 8;
                        } while (h < n);
                    }
                    second[i] = c >>> (32 - n);
                    c <<= n;
                    h -= n;
                }
            }
            pos = p;
            cache = c;
            count = h;
            padBits = pad;
        }

        void alignByte() {
            int rest = count & 7;
            if (rest != 0) {
                cache <<= rest;
                count -= rest;
            }
        }

        /** Consumed position; call only byte-aligned. */
        int bytePos() {
            return pos - (count >> 3);
        }
    }

    static int signExtend(int value, int bits) {
        return (value << (32 - bits)) >> (32 - bits);
    }

    /**
     * Unary lookup for the inlined entropy loops: leading ones (capped at
     * 9, the ALAC escape) and leading zeros (capped at 10 = none in the
     * window, keep counting) of a 10-bit window.
     */
    static final byte[] LEAD_ONES_10 = new byte[1024];
    static final byte[] LEAD_ZEROS_10 = new byte[1024];

    static {
        for (int window = 0; window < 1024; window++) {
            int ones = 0;
            while (ones < 9 && (window & (512 >> ones)) != 0) {
                ones++;
            }
            LEAD_ONES_10[window] = (byte) ones;
            int zeros = 0;
            while (zeros < 10 && (window & (512 >> zeros)) == 0) {
                zeros++;
            }
            LEAD_ZEROS_10[window] = (byte) zeros;
        }
    }
}
