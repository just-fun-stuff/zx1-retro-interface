/*
 * ZX1 Retro Interface
 * Hand-written FLAC decoder (B7b phase 2): the firmware's 2012 extractor
 * refuses FLAC above 48 kHz (opens the file to zero tracks), so the
 * spectrum analyzer decodes those here - original code written from the
 * published FLAC format specification. Licensed under GPLv2; see LICENSE.
 */
package com.github.postapczuk.lalauncher;

import java.io.FileInputStream;
import java.io.IOException;

/**
 * Spectrum-grade FLAC for the hi-res library: STREAMINFO plus a
 * sequential frame walk - constant, verbatim, fixed and LPC subframes,
 * Rice-partitioned residuals with raw escapes, wasted bits, and the
 * left/right/mid side stereo modes; independent channels up to eight
 * (this library holds 6-channel 96/24 rips). Frames decode from a
 * refilled window between byte-aligned frame boundaries, so files far
 * larger than memory stream through. CRCs are not verified - the sync
 * code and the strict field validation are the guard, and on any
 * inconsistency the decode stops honestly rather than resync and guess.
 */
final class FlacDecoder extends PcmDecoder {

    private static final int BUFFER_CAP = 1 << 22;

    private final FileInputStream in;
    private final Bits bits = new Bits();
    private byte[] window;
    private int windowStart;
    private int windowEnd;
    private boolean sourceDrained;

    private int streamBits;
    private long totalSamples;
    private int maxBlockSize;
    private int[][] planes;
    private final int[] coefs = new int[32];

    FlacDecoder(String path) throws IOException {
        FileInputStream opened = new FileInputStream(path);
        boolean ready = false;
        try {
            in = opened;
            parseHeader();
            planes = new int[channels][maxBlockSize];
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
            in.close();
        } catch (IOException ignored) {
            // Nothing left to release.
        }
    }

    @Override
    boolean nextBlock() throws IOException {
        if (totalSamples > 0 && samplePos >= totalSamples) {
            return false;                          // stream complete; ignore trailer bytes
        }
        refill();
        if (windowStart >= windowEnd) {
            return false;                          // clean end of file
        }
        bits.reset(window, windowStart, windowEnd);
        int frames = decodeFrame();
        if (bits.overran()) {
            throw new IOException("flac frame overran the " + (windowEnd - windowStart)
                    + " buffered bytes");
        }
        windowStart = bits.bytePos();
        emitBlock(planes, frames, streamBits - 16);
        return true;
    }

    /** Slides the unread tail to the front and fills from the file. */
    private void refill() throws IOException {
        if (sourceDrained || windowEnd - windowStart >= window.length / 2) {
            return;
        }
        int kept = windowEnd - windowStart;
        System.arraycopy(window, windowStart, window, 0, kept);
        windowStart = 0;
        windowEnd = kept;
        while (windowEnd < window.length) {
            int got = in.read(window, windowEnd, window.length - windowEnd);
            if (got < 0) {
                sourceDrained = true;
                break;
            }
            windowEnd += got;
        }
    }

    // ---------------------------------------------------------------- frame

    /** One frame into {@link #planes}; returns its block size. */
    private int decodeFrame() throws IOException {
        if (bits.read(14) != 0x3FFE) {
            throw new IOException("flac sync lost at sample " + samplePos);
        }
        bits.read(1);                              // reserved
        bits.read(1);                              // blocking strategy
        int blockCode = bits.read(4);
        int rateCode = bits.read(4);
        int channelCode = bits.read(4);
        int sizeCode = bits.read(3);
        bits.read(1);                              // reserved
        readCodedNumber();                         // frame/sample number

        int blockSize;
        if (blockCode == 0) {
            throw new IOException("flac reserved block size code");
        } else if (blockCode == 1) {
            blockSize = 192;
        } else if (blockCode <= 5) {
            blockSize = 576 << (blockCode - 2);
        } else if (blockCode == 6) {
            blockSize = bits.read(8) + 1;
        } else if (blockCode == 7) {
            blockSize = bits.read(16) + 1;
        } else {
            blockSize = 256 << (blockCode - 8);
        }
        if (rateCode == 12) {
            bits.read(8);
        } else if (rateCode == 13 || rateCode == 14) {
            bits.read(16);
        } else if (rateCode == 15) {
            throw new IOException("flac invalid rate code");
        }
        int frameChannels = channelCode <= 7 ? channelCode + 1 : 2;
        if (channelCode > 10 || frameChannels != channels) {
            throw new IOException("flac channel assignment " + channelCode);
        }
        int frameBits;
        switch (sizeCode) {
            case 0: frameBits = streamBits; break;
            case 1: frameBits = 8; break;
            case 2: frameBits = 12; break;
            case 4: frameBits = 16; break;
            case 5: frameBits = 20; break;
            case 6: frameBits = 24; break;
            default: throw new IOException("flac sample size code " + sizeCode);
        }
        if (frameBits != streamBits) {
            throw new IOException("flac frame depth " + frameBits + " vs stream "
                    + streamBits);
        }
        bits.read(8);                              // header CRC
        if (blockSize > planes[0].length) {
            throw new IOException("flac block of " + blockSize + " exceeds "
                    + planes[0].length);
        }

        for (int channel = 0; channel < channels; channel++) {
            int effective = frameBits + sideBit(channelCode, channel);
            decodeSubframe(planes[channel], blockSize, effective);
        }
        // Undo the stereo decorrelation modes.
        int[] left = planes[0];
        int[] right = channels > 1 ? planes[1] : null;
        if (channelCode == 8) {                    // left/side
            for (int i = 0; i < blockSize; i++) {
                right[i] = left[i] - right[i];
            }
        } else if (channelCode == 9) {             // side/right
            for (int i = 0; i < blockSize; i++) {
                left[i] = right[i] + left[i];
            }
        } else if (channelCode == 10) {            // mid/side
            for (int i = 0; i < blockSize; i++) {
                int side = right[i];
                int mid = (left[i] << 1) | (side & 1);
                left[i] = (mid + side) >> 1;
                right[i] = (mid - side) >> 1;
            }
        }
        bits.alignByte();
        bits.read(16);                             // frame CRC
        return blockSize;
    }

    /** The extra precision bit carried by the side channel. */
    private static int sideBit(int channelCode, int channel) {
        if (channelCode == 8 || channelCode == 10) {
            return channel == 1 ? 1 : 0;
        }
        if (channelCode == 9) {
            return channel == 0 ? 1 : 0;
        }
        return 0;
    }

    private void decodeSubframe(int[] out, int blockSize, int sampleBits)
            throws IOException {
        if (bits.read(1) != 0) {
            throw new IOException("flac subframe pad bit set");
        }
        int type = bits.read(6);
        int wasted = 0;
        if (bits.read(1) != 0) {
            wasted = 1;
            while (bits.readBit() == 0) {
                if (++wasted > 30) {
                    throw new IOException("flac wasted-bits runaway");
                }
            }
        }
        int effective = sampleBits - wasted;
        if (effective <= 0 || effective > 25) {
            throw new IOException("flac " + effective + " effective bits");
        }

        if (type == 0) {                           // constant
            int value = bits.readSigned(effective);
            for (int i = 0; i < blockSize; i++) {
                out[i] = value;
            }
        } else if (type == 1) {                    // verbatim
            for (int i = 0; i < blockSize; i++) {
                out[i] = bits.readSigned(effective);
            }
        } else if (type >= 8 && type <= 12) {      // fixed predictor
            int order = type - 8;
            for (int i = 0; i < order; i++) {
                out[i] = bits.readSigned(effective);
            }
            decodeResidual(out, blockSize, order);
            fixedPredict(out, blockSize, order);
        } else if (type >= 32) {                   // LPC
            int order = (type & 31) + 1;
            for (int i = 0; i < order; i++) {
                out[i] = bits.readSigned(effective);
            }
            int precision = bits.read(4) + 1;
            if (precision > 15) {
                throw new IOException("flac invalid coefficient precision");
            }
            int shift = bits.readSigned(5);
            if (shift < 0) {
                throw new IOException("flac negative predictor shift");
            }
            int[] taps = coefs;                    // keep the field out of the loop
            for (int i = 0; i < order; i++) {
                taps[i] = bits.readSigned(precision);
            }
            decodeResidual(out, blockSize, order);
            for (int i = order; i < blockSize; i++) {
                long sum = 0;
                int at = i - 1;
                for (int j = 0; j < order; j++) {
                    sum += (long) taps[j] * out[at - j];
                }
                out[i] += (int) (sum >> shift);
            }
        } else {
            throw new IOException("flac subframe type " + type);
        }
        if (wasted > 0) {
            for (int i = 0; i < blockSize; i++) {
                out[i] <<= wasted;
            }
        }
    }

    private static void fixedPredict(int[] out, int blockSize, int order) {
        switch (order) {
            case 0:
                break;
            case 1:
                for (int i = 1; i < blockSize; i++) {
                    out[i] += out[i - 1];
                }
                break;
            case 2:
                for (int i = 2; i < blockSize; i++) {
                    out[i] += 2 * out[i - 1] - out[i - 2];
                }
                break;
            case 3:
                for (int i = 3; i < blockSize; i++) {
                    out[i] += 3 * out[i - 1] - 3 * out[i - 2] + out[i - 3];
                }
                break;
            default:
                for (int i = 4; i < blockSize; i++) {
                    out[i] += 4 * out[i - 1] - 6 * out[i - 2] + 4 * out[i - 3]
                            - out[i - 4];
                }
                break;
        }
    }

    /** Rice-partitioned residuals into {@code out[order..blockSize)}. */
    private void decodeResidual(int[] out, int blockSize, int order)
            throws IOException {
        int method = bits.read(2);
        if (method > 1) {
            throw new IOException("flac residual method " + method);
        }
        int paramBits = method == 0 ? 4 : 5;
        int escape = method == 0 ? 15 : 31;
        int partitionOrder = bits.read(4);
        int partitions = 1 << partitionOrder;
        int perPartition = blockSize >> partitionOrder;
        if (perPartition << partitionOrder != blockSize || perPartition < order
                || (partitionOrder == 0 && blockSize <= order)) {
            throw new IOException("flac partition order " + partitionOrder
                    + " for block " + blockSize);
        }
        int at = order;
        for (int partition = 0; partition < partitions; partition++) {
            int count = perPartition - (partition == 0 ? order : 0);
            int param = bits.read(paramBits);
            if (param == escape) {
                int raw = bits.read(5);
                if (raw == 0) {
                    for (int i = 0; i < count; i++) {
                        out[at++] = 0;
                    }
                } else {
                    for (int i = 0; i < count; i++) {
                        out[at++] = readSignedWide(raw);
                    }
                }
            } else if (param > 25) {
                // Pathological parameter no real encoder emits; the slow
                // composed read keeps it correct.
                for (int i = 0; i < count; i++) {
                    int quotient = 0;
                    while (bits.readBit() == 0) {
                        if (++quotient > 1 << 24) {
                            throw new IOException("flac unary runaway");
                        }
                    }
                    int value = (quotient << param) | (bits.read(param - 16) << 16)
                            | bits.read(16);
                    out[at++] = (value >>> 1) ^ -(value & 1);
                }
            } else {
                // The per-sample loop, bit reader inlined on locals with a
                // table-driven unary (see Bits on why Dalvik needs this).
                byte[] data = bits.data;
                int pos = bits.pos;
                int end = bits.end;
                int cache = bits.cache;
                int have = bits.count;
                int pad = bits.padBits;
                byte[] leadZeros = LEAD_ZEROS_10;
                for (int i = 0; i < count; i++) {
                    int quotient = 0;
                    while (true) {
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
                        int zeros = leadZeros[cache >>> 22];
                        if (zeros == 10) {
                            cache <<= 10;
                            have -= 10;
                            quotient += 10;
                            if (quotient > 1 << 24 || pad != 0) {
                                throw new IOException("flac unary runaway");
                            }
                        } else {
                            cache <<= zeros + 1;
                            have -= zeros + 1;
                            quotient += zeros;
                            break;
                        }
                    }
                    int value;
                    if (param != 0) {
                        if (have < param) {
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
                            } while (have < param);
                        }
                        value = (quotient << param) | (cache >>> (32 - param));
                        cache <<= param;
                        have -= param;
                    } else {
                        value = quotient;
                    }
                    out[at++] = (value >>> 1) ^ -(value & 1);
                }
                bits.pos = pos;
                bits.cache = cache;
                bits.count = have;
                bits.padBits = pad;
            }
        }
    }

    /** Signed read up to 32 bits (escape partitions may exceed 25). */
    private int readSignedWide(int width) {
        if (width <= 25) {
            return bits.readSigned(width);
        }
        int value = (bits.read(width - 16) << 16) | bits.read(16);
        return (value << (32 - width)) >> (32 - width);
    }

    // --------------------------------------------------------------- header

    private void parseHeader() throws IOException {
        window = new byte[BUFFER_CAP];
        windowEnd = 0;
        windowStart = 0;
        refillTo(1 << 16);
        int at = 0;
        // An ID3v2 tag ahead of the stream marker is tolerated.
        if (windowEnd >= 10 && window[0] == 'I' && window[1] == 'D' && window[2] == '3') {
            int size = ((window[6] & 0x7F) << 21) | ((window[7] & 0x7F) << 14)
                    | ((window[8] & 0x7F) << 7) | (window[9] & 0x7F);
            skipInput(10 + size);
            refillTo(1 << 16);
        }
        if (windowEnd < at + 42 || window[at] != 'f' || window[at + 1] != 'L'
                || window[at + 2] != 'a' || window[at + 3] != 'C') {
            throw new IOException("no fLaC marker");
        }
        at += 4;
        boolean last = false;
        boolean haveInfo = false;
        while (!last) {
            at = ensureBuffered(at, 38);           // block header + streaminfo
            if (windowEnd - at < 4) {
                throw new IOException("flac metadata truncated");
            }
            int head = readInt(window, at);
            last = (head & 0x80000000) != 0;
            int type = (head >>> 24) & 0x7F;
            int length = head & 0xFFFFFF;
            at += 4;
            if (type == 0) {
                if (length < 34 || windowEnd - at < 34) {
                    throw new IOException("streaminfo truncated");
                }
                maxBlockSize = ((window[at + 2] & 0xFF) << 8) | (window[at + 3] & 0xFF);
                int rate20 = ((window[at + 10] & 0xFF) << 12)
                        | ((window[at + 11] & 0xFF) << 4)
                        | ((window[at + 12] & 0xFF) >> 4);
                channels = ((window[at + 12] >> 1) & 0x7) + 1;
                streamBits = (((window[at + 12] & 0x1) << 4)
                        | ((window[at + 13] & 0xFF) >> 4)) + 1;
                totalSamples = ((long) (window[at + 13] & 0xF) << 32)
                        | (readInt(window, at + 14) & 0xFFFFFFFFL);
                if (rate20 <= 0 || rate20 > 655350 || maxBlockSize < 16
                        || streamBits < 4 || streamBits > 32) {
                    throw new IOException("streaminfo invalid: rate " + rate20
                            + " block " + maxBlockSize + " bits " + streamBits);
                }
                if (streamBits > 24) {
                    throw new IOException(streamBits + "-bit flac unsupported");
                }
                sampleRate = rate20;
                bitDepth = streamBits;
                haveInfo = true;
            }
            // Skip this block's payload, streaming past what is buffered
            // (cover art can be megabytes).
            if (windowEnd - at >= length) {
                at += length;
            } else {
                int short0 = length - (windowEnd - at);
                at = windowEnd;
                windowStart = at;
                compactTo(0);
                skipInput(short0);
                refillTo(1 << 16);
                at = 0;
            }
        }
        if (!haveInfo) {
            throw new IOException("no streaminfo block");
        }
        windowStart = at;
    }

    /**
     * Guarantees {@code need} readable bytes past {@code at} when the file
     * still has them, compacting the window when necessary; returns the
     * (possibly moved) read position.
     */
    private int ensureBuffered(int at, int need) throws IOException {
        if (windowEnd - at >= need) {
            return at;
        }
        windowStart = at;
        compactTo(0);
        refillTo(need);
        return 0;
    }

    /** Reads until the window holds {@code want} bytes or the file ends. */
    private void refillTo(int want) throws IOException {
        while (!sourceDrained && windowEnd < want && windowEnd < window.length) {
            int got = in.read(window, windowEnd, window.length - windowEnd);
            if (got < 0) {
                sourceDrained = true;
            } else {
                windowEnd += got;
            }
        }
    }

    private void compactTo(int newStart) {
        int kept = windowEnd - windowStart;
        System.arraycopy(window, windowStart, window, newStart, kept);
        windowStart = newStart;
        windowEnd = newStart + kept;
    }

    private void skipInput(long count) throws IOException {
        long left = count;
        while (left > 0) {
            long got = in.skip(left);
            if (got <= 0) {
                if (in.read() < 0) {
                    throw new IOException("flac ended inside a skip");
                }
                got = 1;
            }
            left -= got;
        }
    }

    /** The UTF-8-style coded frame/sample number; value discarded. */
    private void readCodedNumber() throws IOException {
        int first = bits.read(8);
        int extra;
        if ((first & 0x80) == 0) {
            extra = 0;
        } else if ((first & 0xE0) == 0xC0) {
            extra = 1;
        } else if ((first & 0xF0) == 0xE0) {
            extra = 2;
        } else if ((first & 0xF8) == 0xF0) {
            extra = 3;
        } else if ((first & 0xFC) == 0xF8) {
            extra = 4;
        } else if ((first & 0xFE) == 0xFC) {
            extra = 5;
        } else if (first == 0xFE) {
            extra = 6;
        } else {
            throw new IOException("flac coded number malformed");
        }
        for (int i = 0; i < extra; i++) {
            if ((bits.read(8) & 0xC0) != 0x80) {
                throw new IOException("flac coded number continuation");
            }
        }
    }

    private static int readInt(byte[] data, int at) {
        return ((data[at] & 0xFF) << 24) | ((data[at + 1] & 0xFF) << 16)
                | ((data[at + 2] & 0xFF) << 8) | (data[at + 3] & 0xFF);
    }
}
