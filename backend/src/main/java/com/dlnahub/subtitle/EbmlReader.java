package com.dlnahub.subtitle;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Minimal EBML primitive reader — the encoding Matroska is built from.
 *
 * <p>EBML stores both element IDs and sizes as variable-length integers whose length is given
 * by the number of leading zero bits in the first byte. IDs keep their marker bit (it is part
 * of the identity); sizes have it stripped (it is only a length prefix).
 *
 * <p>Sequential only, by design: extracting subtitles means a forward scan of the whole file,
 * and random access over HTTP would mean one range request per block.
 */
final class EbmlReader {

    /** A size field with every value bit set means "runs until the parent ends". */
    static final long UNKNOWN_SIZE = -1L;

    private static final long END_OF_STREAM = -2L;

    private final InputStream in;
    private long position;

    EbmlReader(InputStream in) {
        this.in = in;
    }

    long position() {
        return position;
    }

    /** @return the byte, or -1 at end of stream */
    private int readUnsignedByte() throws IOException {
        int b = in.read();
        if (b >= 0) {
            position++;
        }
        return b;
    }

    /** @return the element ID with its marker bit intact, or {@link #END_OF_STREAM} */
    long readId() throws IOException {
        int first = readUnsignedByte();
        if (first < 0) {
            return END_OF_STREAM;
        }
        int length;
        if ((first & 0x80) != 0) length = 1;
        else if ((first & 0x40) != 0) length = 2;
        else if ((first & 0x20) != 0) length = 3;
        else if ((first & 0x10) != 0) length = 4;
        else throw new IOException("Invalid EBML element ID at " + (position - 1));

        long value = first;
        for (int i = 1; i < length; i++) {
            int b = readUnsignedByte();
            if (b < 0) return END_OF_STREAM;
            value = (value << 8) | b;
        }
        return value;
    }

    /** @return the size, {@link #UNKNOWN_SIZE}, or {@link #END_OF_STREAM} */
    long readSize() throws IOException {
        int first = readUnsignedByte();
        if (first < 0) {
            return END_OF_STREAM;
        }
        int length = 1;
        int mask = 0x80;
        while (length <= 8 && (first & mask) == 0) {
            mask >>= 1;
            length++;
        }
        if (length > 8) {
            throw new IOException("Invalid EBML size at " + (position - 1));
        }

        long value = first & (mask - 1);
        for (int i = 1; i < length; i++) {
            int b = readUnsignedByte();
            if (b < 0) return END_OF_STREAM;
            value = (value << 8) | b;
        }
        long allOnes = (1L << (7L * length)) - 1;
        return value == allOnes ? UNKNOWN_SIZE : value;
    }

    static boolean isEndOfStream(long value) {
        return value == END_OF_STREAM;
    }

    byte[] readBytes(int length) throws IOException {
        byte[] buffer = new byte[length];
        int read = 0;
        while (read < length) {
            int n = in.read(buffer, read, length - read);
            if (n < 0) {
                throw new EOFException("Truncated element: wanted " + length + ", got " + read);
            }
            read += n;
        }
        position += length;
        return buffer;
    }

    /** Reads an unsigned integer of the given width, as Matroska stores them. */
    long readUnsignedInteger(int length) throws IOException {
        byte[] bytes = readBytes(length);
        long value = 0;
        for (byte b : bytes) {
            value = (value << 8) | (b & 0xFF);
        }
        return value;
    }

    String readString(int length) throws IOException {
        return new String(readBytes(length), java.nio.charset.StandardCharsets.UTF_8)
                .replace("\0", "")
                .trim();
    }

    /** Discards {@code count} bytes. This is the hot path — most of the file is skipped. */
    void skip(long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                // skip() is allowed to do nothing; fall back to reading so we always progress.
                int b = in.read();
                if (b < 0) {
                    position += (count - remaining);
                    throw new EOFException("Truncated stream while skipping");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
        position += count;
    }
}
