/*
 * ZX1 Retro Interface
 * Hand-written ALAC decoder (B7b phase 2): the ZX1's Android stack cannot
 * decode ALAC at all (the extractor exposes application/octet-stream), so
 * the spectrum analyzer decodes it here - original code written from the
 * publicly documented bitstream format, not ported from Apple's reference
 * implementation. Licensed under GPLv2; see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * Spectrum-grade ALAC: a minimal M4A walk (moov/trak/mdia/minf/stbl) finds
 * the alac magic cookie and the sample tables, then each packet decodes
 * through the documented pipeline - adaptive Rice entropy coding, the
 * adaptive LPC predictor, stereo decorrelation, shifted-out low bytes,
 * and the uncompressed escape. 16/20/24-bit at any rate; mono and stereo
 * (this library is CD rips and hi-res stereo - multichannel ALAC element
 * layouts are refused honestly, as is 32-bit). Decoded output is verified
 * bit-exact against a reference decoder off-device; on any stream
 * inconsistency the decode stops rather than guess.
 */
final class AlacDecoder extends PcmDecoder {

    private static final int MAX_PACKET_BYTES = 1 << 24;

    private final RandomAccessFile file;

    // ---- alac magic cookie
    private int frameLength;
    private int riceHistoryMult;   // pb
    private int riceInitialHistory; // mb
    private int riceLimit;          // kb

    // ---- sample tables (stsz/stsc/stco)
    private int sampleCount;
    private int[] sampleSizes;      // null when fixedSampleSize applies
    private int fixedSampleSize;
    private long[] chunkOffsets;
    private int[] stscFirstChunk;   // 1-based, ascending
    private int[] stscSamplesPerChunk;

    // ---- iteration state
    private int sampleIndex;
    private int chunkIndex;
    private int sampleInChunk;
    private int stscIndex;
    private long readOffset;

    // ---- decode scratch
    private final Bits bits = new Bits();
    private byte[] packet;
    private int[][] planes;
    private int[] residual;
    private int[][] shiftBits;
    private final int[][] coefs = new int[2][32];
    private final int[] mode = new int[2];
    private final int[] lpcQuant = new int[2];
    private final int[] pbFactor = new int[2];
    private final int[] lpcOrder = new int[2];

    AlacDecoder(String path) throws IOException {
        RandomAccessFile opened = new RandomAccessFile(path, "r");
        boolean ready = false;
        try {
            file = opened;
            parseContainer();
            planes = new int[channels][frameLength];
            residual = new int[frameLength];
            chunkIndex = 0;
            stscIndex = 0;
            readOffset = chunkOffsets[0];
            ready = true;
        } finally {
            if (!ready) {
                try {
                    opened.close();
                } catch (IOException ignored) {
                    // The parse failure is the story.
                }
            }
        }
    }

    @Override
    void close() {
        try {
            file.close();
        } catch (IOException ignored) {
            // Nothing left to release.
        }
    }

    @Override
    boolean nextBlock() throws IOException {
        if (sampleIndex >= sampleCount) {
            return false;
        }
        int size = sampleSizes != null ? sampleSizes[sampleIndex] : fixedSampleSize;
        if (size <= 0 || size > MAX_PACKET_BYTES) {
            throw new IOException("alac packet size " + size);
        }
        if (packet == null || packet.length < size) {
            packet = new byte[Math.max(size, 1 << 14)];
        }
        file.seek(readOffset);
        file.readFully(packet, 0, size);
        int frames = decodePacket(size);
        emitBlock(planes, frames, bitDepth - 16);
        // Advance the chunk walk to the next packet's file offset.
        sampleIndex++;
        sampleInChunk++;
        readOffset += size;
        if (sampleIndex < sampleCount && sampleInChunk >= samplesPerChunk(chunkIndex)) {
            chunkIndex++;
            if (chunkIndex >= chunkOffsets.length) {
                throw new IOException("alac sample tables ran out of chunks");
            }
            sampleInChunk = 0;
            readOffset = chunkOffsets[chunkIndex];
        }
        return true;
    }

    private int samplesPerChunk(int chunk) {
        while (stscIndex + 1 < stscFirstChunk.length
                && stscFirstChunk[stscIndex + 1] <= chunk + 1) {
            stscIndex++;
        }
        return stscSamplesPerChunk[stscIndex];
    }

    // ------------------------------------------------------------ bitstream

    /** One packet: elements until the cookie's channels are filled. */
    private int decodePacket(int size) throws IOException {
        bits.reset(packet, 0, size);
        int filled = 0;
        int frames = -1;
        while (filled < channels) {
            int tag = bits.read(3);
            int elementChannels;
            if (tag == 0 || tag == 3) {           // SCE / LFE
                elementChannels = 1;
            } else if (tag == 1) {                // CPE
                elementChannels = 2;
            } else if (tag == 4) {                // DSE
                skipDataElement();
                continue;
            } else if (tag == 6) {                // FIL
                skipFillElement();
                continue;
            } else if (tag == 7) {                // END
                break;
            } else {                              // CCE / PCE
                throw new IOException("alac element type " + tag);
            }
            if (filled + elementChannels > channels) {
                throw new IOException("alac elements exceed cookie channels");
            }
            int n = decodeElement(filled, elementChannels);
            if (frames >= 0 && n != frames) {
                throw new IOException("alac elements disagree on frame count");
            }
            frames = n;
            filled += elementChannels;
        }
        if (filled < channels || frames < 0) {
            throw new IOException("alac packet ended at " + filled + "/" + channels
                    + " channels");
        }
        if (bits.overran()) {
            throw new IOException("alac packet overran its byte size");
        }
        return frames;
    }

    private void skipDataElement() {
        bits.read(4);
        boolean align = bits.read(1) != 0;
        int count = bits.read(8);
        if (count == 255) {
            count += bits.read(8);
        }
        if (align) {
            bits.alignByte();
        }
        bits.skipBytes(count);
    }

    private void skipFillElement() {
        int count = bits.read(4);
        if (count == 15) {
            count += bits.read(8) - 1;
        }
        bits.skipBytes(count);
    }

    /** One SCE/CPE into {@code planes[firstChannel..]}; returns frames. */
    private int decodeElement(int firstChannel, int elementChannels) throws IOException {
        bits.read(4);                              // element instance tag
        if (bits.read(12) != 0) {
            throw new IOException("alac reserved header bits set");
        }
        boolean partial = bits.read(1) != 0;
        int shiftedBytes = bits.read(2);
        boolean uncompressed = bits.read(1) != 0;
        if (shiftedBytes == 3) {
            throw new IOException("alac shift of 3 bytes");
        }
        int frames = partial ? bits.read32() : frameLength;
        if (frames <= 0 || frames > frameLength) {
            throw new IOException("alac frame count " + frames);
        }

        if (uncompressed) {
            for (int i = 0; i < frames; i++) {
                for (int channel = 0; channel < elementChannels; channel++) {
                    planes[firstChannel + channel][i] = bits.readSigned(bitDepth);
                }
            }
            return frames;
        }

        int extraBits = shiftedBytes * 8;
        int chanBits = bitDepth - extraBits + elementChannels - 1;
        if (chanBits > 25) {
            throw new IOException("alac " + chanBits + " channel bits");
        }
        int mixShift = 0;
        int mixWeight = 0;
        if (elementChannels == 2) {
            mixShift = bits.read(8);
            mixWeight = (byte) bits.read(8);       // signed
        }
        for (int channel = 0; channel < elementChannels; channel++) {
            mode[channel] = bits.read(4);
            lpcQuant[channel] = bits.read(4);
            pbFactor[channel] = bits.read(3);
            int order = bits.read(5);
            lpcOrder[channel] = order;
            // The stream's first coefficient applies to the newest sample.
            for (int i = order - 1; i >= 0; i--) {
                coefs[channel][i] = bits.readSigned(16);
            }
        }
        if (extraBits != 0) {
            if (shiftBits == null || shiftBits[0].length < frameLength) {
                shiftBits = new int[2][frameLength];
            }
            bits.readBulk(extraBits, shiftBits[0],
                    elementChannels == 2 ? shiftBits[1] : null, frames);
        }
        for (int channel = 0; channel < elementChannels; channel++) {
            int[] out = planes[firstChannel + channel];
            int ricePb = (riceHistoryMult * pbFactor[channel]) >> 2;
            riceDecode(residual, frames, ricePb, chanBits);
            if (mode[channel] != 0) {
                // Documented double pass: a first-order sweep in place,
                // then the regular predictor.
                predict(residual, residual, frames, null, 31, 0, chanBits);
            }
            predict(residual, out, frames, coefs[channel], lpcOrder[channel],
                    lpcQuant[channel], chanBits);
        }
        if (elementChannels == 2 && mixWeight != 0) {
            int[] left = planes[firstChannel];
            int[] right = planes[firstChannel + 1];
            for (int i = 0; i < frames; i++) {
                int u = left[i];
                int v = right[i];
                u -= (v * mixWeight) >> mixShift;
                left[i] = v + u;
                right[i] = u;
            }
        }
        if (extraBits != 0) {
            for (int channel = 0; channel < elementChannels; channel++) {
                int[] out = planes[firstChannel + channel];
                int[] low = shiftBits[channel];
                for (int i = 0; i < frames; i++) {
                    out[i] = (out[i] << extraBits) | low[i];
                }
            }
        }
        return frames;
    }

    /**
     * The adaptive Rice entropy decoder: parameter k follows a decaying
     * history of recent magnitudes, values escape to raw after a prefix of
     * nine ones, and a low history opens a run-length mode for silence.
     * The bit reader runs inlined on locals with a table-driven unary
     * prefix - this loop is per sample and set the device's decode speed
     * (see {@link Bits}).
     */
    private void riceDecode(int[] out, int count, int ricePb, int chanBits)
            throws IOException {
        byte[] data = bits.data;
        int pos = bits.pos;
        int end = bits.end;
        int cache = bits.cache;
        int have = bits.count;
        int pad = bits.padBits;
        byte[] leadOnes = LEAD_ONES_10;
        byte[] log2 = LOG2;
        int history = riceInitialHistory;
        int limit = riceLimit;
        int signModifier = 0;
        int i = 0;
        while (i < count) {
            int histTerm = (history >> 9) + 3;
            int k = histTerm < LOG2_SPAN ? log2[histTerm]
                    : 31 - Integer.numberOfLeadingZeros(histTerm);
            if (k > limit) {
                k = limit;
            }
            if (have < 10) {
                do {
                    int next;
                    if (pos < end) {
                        next = data[pos++] & 0xFF;
                    } else {
                        next = 0;
                        pad += 8;
                    }
                    cache |= next << (24 - have);
                    have += 8;
                } while (have < 10);
            }
            int prefix = leadOnes[cache >>> 22];
            int x;
            if (prefix >= 9) {
                cache <<= 9;
                have -= 9;
                if (have < chanBits) {
                    do {
                        int next;
                        if (pos < end) {
                            next = data[pos++] & 0xFF;
                        } else {
                            next = 0;
                            pad += 8;
                        }
                        cache |= next << (24 - have);
                        have += 8;
                    } while (have < chanBits);
                }
                x = cache >>> (32 - chanBits);
                cache <<= chanBits;
                have -= chanBits;
            } else if (k > 1) {
                cache <<= prefix + 1;
                have -= prefix + 1;
                if (have < k) {
                    do {
                        int next;
                        if (pos < end) {
                            next = data[pos++] & 0xFF;
                        } else {
                            next = 0;
                            pad += 8;
                        }
                        cache |= next << (24 - have);
                        have += 8;
                    } while (have < k);
                }
                int extra = cache >>> (32 - k);
                x = (prefix << k) - prefix;        // prefix * (2^k - 1)
                if (extra > 1) {
                    x += extra - 1;
                    cache <<= k;
                    have -= k;
                } else {
                    cache <<= k - 1;
                    have -= k - 1;
                }
            } else {
                cache <<= prefix + 1;
                have -= prefix + 1;
                x = prefix;
            }
            x += signModifier;
            signModifier = 0;
            out[i++] = (x >>> 1) ^ -(x & 1);
            if (x > 0xFFFF) {
                history = 0xFFFF;
            } else {
                history += x * ricePb - ((history * ricePb) >> 9);
            }
            if (history < 128 && i < count) {
                int k2 = 7 - log2[history] + ((history + 16) >> 6);
                if (k2 > limit) {
                    k2 = limit;
                }
                if (have < 10) {
                    do {
                        int next;
                        if (pos < end) {
                            next = data[pos++] & 0xFF;
                        } else {
                            next = 0;
                            pad += 8;
                        }
                        cache |= next << (24 - have);
                        have += 8;
                    } while (have < 10);
                }
                int prefix2 = leadOnes[cache >>> 22];
                int run;
                if (prefix2 >= 9) {
                    cache <<= 9;
                    have -= 9;
                    if (have < 16) {
                        do {
                            int next;
                            if (pos < end) {
                                next = data[pos++] & 0xFF;
                            } else {
                                next = 0;
                                pad += 8;
                            }
                            cache |= next << (24 - have);
                            have += 8;
                        } while (have < 16);
                    }
                    run = cache >>> 16;
                    cache <<= 16;
                    have -= 16;
                } else if (k2 > 1) {
                    cache <<= prefix2 + 1;
                    have -= prefix2 + 1;
                    if (have < k2) {
                        do {
                            int next;
                            if (pos < end) {
                                next = data[pos++] & 0xFF;
                            } else {
                                next = 0;
                                pad += 8;
                            }
                            cache |= next << (24 - have);
                            have += 8;
                        } while (have < k2);
                    }
                    int extra = cache >>> (32 - k2);
                    run = (prefix2 << k2) - prefix2;
                    if (extra > 1) {
                        run += extra - 1;
                        cache <<= k2;
                        have -= k2;
                    } else {
                        cache <<= k2 - 1;
                        have -= k2 - 1;
                    }
                } else {
                    cache <<= prefix2 + 1;
                    have -= prefix2 + 1;
                    run = prefix2;
                }
                if (run > 0) {
                    if (run > count - i) {
                        throw new IOException("alac zero run " + run + " of "
                                + (count - i));
                    }
                    for (int stop = i + run; i < stop; i++) {
                        out[i] = 0;
                    }
                }
                if (run <= 0xFFFF) {
                    signModifier = 1;
                }
                history = 0;
            }
        }
        bits.pos = pos;
        bits.cache = cache;
        bits.count = have;
        bits.padBits = pad;
    }

    /**
     * The adaptive LPC predictor: warm-up accumulation, then each sample
     * predicted from the window's differences against its leading sample,
     * with the coefficients nudged toward the sign of the residual. Order
     * 31 is the documented plain first-order accumulator.
     */
    private static void predict(int[] error, int[] out, int count, int[] coefs,
                                int order, int quant, int bps) {
        out[0] = error[0];
        if (count <= 1) {
            return;
        }
        if (order == 0) {
            System.arraycopy(error, 1, out, 1, count - 1);
            return;
        }
        if (order == 31) {
            for (int i = 1; i < count; i++) {
                out[i] = signExtend(out[i - 1] + error[i], bps);
            }
            return;
        }
        int i = 1;
        for (; i <= order && i < count; i++) {
            out[i] = signExtend(out[i - 1] + error[i], bps);
        }
        int round = quant > 0 ? 1 << (quant - 1) : 0;
        int base = 0;
        for (; i < count; i++, base++) {
            int lead = out[base];                  // just before the window
            int sum = 0;
            for (int j = 0; j < order; j++) {
                sum += (out[base + 1 + j] - lead) * coefs[j];
            }
            out[i] = signExtend(((sum + round) >> quant) + lead + error[i], bps);
            int residual = error[i];
            int residualSign = residual > 0 ? 1 : residual < 0 ? -1 : 0;
            if (residualSign != 0) {
                for (int j = 0; j < order && residual * residualSign > 0; j++) {
                    int diff = lead - out[base + 1 + j];
                    int sign = (diff > 0 ? 1 : diff < 0 ? -1 : 0) * residualSign;
                    coefs[j] -= sign;
                    residual -= ((diff * sign) >> quant) * (j + 1);
                }
            }
        }
    }

    /**
     * Floor log2 for the Rice parameter laws - a table because this runs
     * twice per sample and Dalvik makes numberOfLeadingZeros a real call.
     * The history term is NOT bounded by the 0xFFFF escape clamp (the
     * update arithmetic can push it into the millions on hi-res content),
     * so lookups past the table fall back to computing it.
     */
    private static final int LOG2_SPAN = 4096;
    private static final byte[] LOG2 = new byte[LOG2_SPAN];

    static {
        for (int value = 2; value < LOG2_SPAN; value++) {
            LOG2[value] = (byte) (31 - Integer.numberOfLeadingZeros(value));
        }
    }

    // ------------------------------------------------------------ container

    /** Finds the alac trak and loads cookie plus sample tables. */
    private void parseContainer() throws IOException {
        long[] moov = findBox(0, file.length(), "moov");
        if (moov == null) {
            throw new IOException("no moov box");
        }
        long cursor = moov[0];
        while (cursor < moov[1]) {
            long[] trak = findBox(cursor, moov[1], "trak");
            if (trak == null) {
                break;
            }
            cursor = trak[1];
            long[] mdia = findBox(trak[0], trak[1], "mdia");
            if (mdia == null) {
                continue;
            }
            long[] minf = findBox(mdia[0], mdia[1], "minf");
            if (minf == null) {
                continue;
            }
            long[] stbl = findBox(minf[0], minf[1], "stbl");
            if (stbl == null) {
                continue;
            }
            if (tryTrak(stbl)) {
                return;
            }
        }
        throw new IOException("no alac track");
    }

    /** True when this stbl carries a valid alac cookie and full tables. */
    private boolean tryTrak(long[] stbl) throws IOException {
        long[] stsd = findBox(stbl[0], stbl[1], "stsd");
        if (stsd == null || !parseCookie(readBox(stsd, 1 << 18))) {
            return false;
        }
        long[] stsz = findBox(stbl[0], stbl[1], "stsz");
        long[] stsc = findBox(stbl[0], stbl[1], "stsc");
        long[] stco = findBox(stbl[0], stbl[1], "stco");
        boolean wide = false;
        if (stco == null) {
            stco = findBox(stbl[0], stbl[1], "co64");
            wide = true;
        }
        if (stsz == null || stsc == null || stco == null) {
            throw new IOException("alac track missing sample tables");
        }
        parseSampleSizes(readBox(stsz, 1 << 26));
        parseSampleToChunk(readBox(stsc, 1 << 24));
        parseChunkOffsets(readBox(stco, 1 << 24), wide);
        return true;
    }

    /**
     * Scans the stsd payload for the alac magic cookie box - located by
     * scan rather than fixed offsets because the enclosing sound sample
     * description has version 0/1/2 layouts (hi-res rates force v2) and
     * an optional wave wrapper; the cookie's own strict field validation
     * is what accepts it.
     */
    private boolean parseCookie(byte[] stsd) throws IOException {
        for (int at = 0; at + 36 <= stsd.length; at++) {
            int size = readInt(stsd, at);
            if (size < 36 || size > 256 || !fourCc(stsd, at + 4, 'a', 'l', 'a', 'c')
                    || readInt(stsd, at + 8) != 0) {
                continue;
            }
            int cookie = at + 12;
            if (cookie + 24 > stsd.length) {
                continue;
            }
            int frameLen = readInt(stsd, cookie);
            int compatible = stsd[cookie + 4] & 0xFF;
            int depth = stsd[cookie + 5] & 0xFF;
            int pb = stsd[cookie + 6] & 0xFF;
            int mb = stsd[cookie + 7] & 0xFF;
            int kb = stsd[cookie + 8] & 0xFF;
            int chan = stsd[cookie + 9] & 0xFF;
            int rate = readInt(stsd, cookie + 20);
            if (compatible != 0 || frameLen <= 0 || frameLen > 1 << 16
                    || (depth != 16 && depth != 20 && depth != 24 && depth != 32)
                    || chan < 1 || chan > 8 || pb == 0 || kb == 0 || kb > 31
                    || rate < 8000 || rate > 384000) {
                continue;
            }
            if (depth == 32) {
                throw new IOException("32-bit alac unsupported");
            }
            if (chan > 2) {
                throw new IOException("multichannel alac unsupported");
            }
            frameLength = frameLen;
            bitDepth = depth;
            riceHistoryMult = pb;
            riceInitialHistory = mb;
            riceLimit = kb;
            channels = chan;
            sampleRate = rate;
            return true;
        }
        return false;
    }

    private void parseSampleSizes(byte[] box) throws IOException {
        fixedSampleSize = readInt(box, 4);
        sampleCount = readInt(box, 8);
        if (sampleCount <= 0 || sampleCount > 1 << 22) {
            throw new IOException("alac sample count " + sampleCount);
        }
        if (fixedSampleSize == 0) {
            if (box.length < 12 + 4L * sampleCount) {
                throw new IOException("stsz shorter than its count");
            }
            sampleSizes = new int[sampleCount];
            for (int i = 0; i < sampleCount; i++) {
                sampleSizes[i] = readInt(box, 12 + 4 * i);
            }
        }
    }

    private void parseSampleToChunk(byte[] box) throws IOException {
        int entries = readInt(box, 4);
        if (entries <= 0 || entries > 1 << 20 || box.length < 8 + 12L * entries) {
            throw new IOException("stsc entries " + entries);
        }
        stscFirstChunk = new int[entries];
        stscSamplesPerChunk = new int[entries];
        for (int i = 0; i < entries; i++) {
            stscFirstChunk[i] = readInt(box, 8 + 12 * i);
            stscSamplesPerChunk[i] = readInt(box, 12 + 12 * i);
            if (stscSamplesPerChunk[i] <= 0
                    || (i == 0 ? stscFirstChunk[i] != 1
                    : stscFirstChunk[i] <= stscFirstChunk[i - 1])) {
                throw new IOException("stsc malformed at entry " + i);
            }
        }
    }

    private void parseChunkOffsets(byte[] box, boolean wide) throws IOException {
        int entries = readInt(box, 4);
        int width = wide ? 8 : 4;
        if (entries <= 0 || entries > 1 << 20 || box.length < 8 + (long) width * entries) {
            throw new IOException("chunk offsets " + entries);
        }
        chunkOffsets = new long[entries];
        for (int i = 0; i < entries; i++) {
            chunkOffsets[i] = wide
                    ? ((long) readInt(box, 8 + 8 * i) << 32)
                    | (readInt(box, 12 + 8 * i) & 0xFFFFFFFFL)
                    : readInt(box, 8 + 4 * i) & 0xFFFFFFFFL;
        }
    }

    /** First child box of the given type in [from, to); {payload, end}. */
    private long[] findBox(long from, long to, String type) throws IOException {
        byte[] header = new byte[8];
        long at = from;
        while (at + 8 <= to) {
            file.seek(at);
            file.readFully(header);
            long size = readInt(header, 0) & 0xFFFFFFFFL;
            long payload = at + 8;
            if (size == 1) {
                size = file.readLong();
                payload += 8;
            } else if (size == 0) {
                size = to - at;
            }
            if (size < payload - at || at + size > to) {
                return null;                       // malformed; stop walking
            }
            if (header[4] == type.charAt(0) && header[5] == type.charAt(1)
                    && header[6] == type.charAt(2) && header[7] == type.charAt(3)) {
                return new long[]{payload, at + size};
            }
            at += size;
        }
        return null;
    }

    /** Whole payload of a leaf box, size-capped. */
    private byte[] readBox(long[] box, int cap) throws IOException {
        long size = box[1] - box[0];
        if (size < 8 || size > cap) {
            throw new IOException("box size " + size);
        }
        byte[] data = new byte[(int) size];
        file.seek(box[0]);
        file.readFully(data);
        return data;
    }

    private static int readInt(byte[] data, int at) {
        return ((data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16)
                | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
    }

    private static boolean fourCc(byte[] data, int at, char a, char b, char c, char d) {
        return data[at] == a && data[at + 1] == b && data[at + 2] == c && data[at + 3] == d;
    }
}
