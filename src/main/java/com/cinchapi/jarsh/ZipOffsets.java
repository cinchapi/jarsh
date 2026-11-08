/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2018 Cinchapi Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package com.cinchapi.jarsh;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * {@link ZipOffsets} fixes the offsets of a zip archive that follows other data
 * in a file, such as a script.
 * <p>
 * A zip archive records where its parts start. A writer such as
 * {@link java.util.zip.ZipOutputStream} counts those offsets from the start of
 * the archive, but zip tools count them from the start of the file. Java
 * accepts either, while a tool such as {@code unzip} warns and exits with an
 * error status when the two differ. Once the offsets count from the start of
 * the file, every zip reader reads the file as an ordinary archive, as the
 * {@code -A} option of the {@code zip} program arranges for a self-extracting
 * archive.
 *
 * @author Jeff Nelson
 */
final class ZipOffsets {

    /**
     * The signature of the end of central directory record.
     */
    private static final int END_SIGNATURE = 0x06054b50;

    /**
     * The length of the end of central directory record without its comment.
     */
    private static final int END_LENGTH = 22;

    /**
     * The longest comment that the end of central directory record holds.
     */
    private static final int MAX_COMMENT_LENGTH = 0xFFFF;

    /**
     * The signature of the Zip64 end of central directory locator.
     */
    private static final int ZIP64_LOCATOR_SIGNATURE = 0x07064b50;

    /**
     * The length of the Zip64 end of central directory locator.
     */
    private static final int ZIP64_LOCATOR_LENGTH = 20;

    /**
     * The signature of the Zip64 end of central directory record.
     */
    private static final int ZIP64_END_SIGNATURE = 0x06064b50;

    /**
     * The length of the fixed fields of the Zip64 end of central directory
     * record.
     */
    private static final int ZIP64_END_LENGTH = 56;

    /**
     * The signature of a central directory file header.
     */
    private static final int HEADER_SIGNATURE = 0x02014b50;

    /**
     * The length of the fixed fields of a central directory file header.
     */
    private static final int HEADER_LENGTH = 46;

    /**
     * The value of a 32-bit field whose real value sits in a Zip64 field.
     */
    private static final long ZIP64_MARKER = 0xFFFFFFFFL;

    /**
     * Shift the offsets of the zip archive that starts {@code delta} bytes into
     * {@code file} so they count from the start of the file.
     *
     * @param file a file that holds {@code delta} bytes of other data and then
     *            a zip archive whose offsets count from the start of that
     *            archive, such as one {@link java.util.zip.ZipOutputStream}
     *            wrote
     * @param delta the number of bytes before the archive; must not be negative
     * @throws IOException if {@code file} does not end with a zip archive, or
     *             if an offset after the shift needs a Zip64 field that the
     *             archive lacks, which happens only when the file passes 4 GiB;
     *             the file may be left partly shifted
     */
    static void shift(Path file, long delta) throws IOException {
        if(delta < 0) {
            throw new IllegalArgumentException(
                    "The delta must not be negative: " + delta);
        }
        else {
            try (FileChannel channel = FileChannel.open(file,
                    StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                long end = findEnd(channel);
                ByteBuffer record = read(channel, end, END_LENGTH);
                long entries = record.getShort(10) & 0xFFFF;
                long directoryLength = record.getInt(12) & ZIP64_MARKER;
                long directoryOffset = record.getInt(16) & ZIP64_MARKER;
                long locator = end - ZIP64_LOCATOR_LENGTH;
                if(locator >= delta && read(channel, locator, 4)
                        .getInt(0) == ZIP64_LOCATOR_SIGNATURE) {
                    long zip64End = read(channel, locator + 8, 8).getLong(0)
                            + delta;
                    ByteBuffer zip64 = read(channel, zip64End,
                            ZIP64_END_LENGTH);
                    if(zip64.getInt(0) != ZIP64_END_SIGNATURE) {
                        throw new IOException("The Zip64 locator of " + file
                                + " does not point to a Zip64 record");
                    }
                    else {
                        entries = zip64.getLong(32);
                        directoryLength = zip64.getLong(40);
                        directoryOffset = zip64.getLong(48);
                        writeLong(channel, zip64End + 48,
                                directoryOffset + delta);
                        writeLong(channel, locator + 8, zip64End);
                    }
                }
                long recordOffset = record.getInt(16) & ZIP64_MARKER;
                if(recordOffset != ZIP64_MARKER) {
                    writeInt(channel, end + 16, recordOffset + delta);
                }
                shiftHeaders(channel, directoryOffset + delta,
                        directoryLength, entries, delta);
            }
        }
    }

    /**
     * Return the position of the end of central directory record in the file
     * that {@code channel} reads.
     *
     * @param channel the file
     * @return the record's position
     * @throws IOException if the file has no such record
     */
    private static long findEnd(FileChannel channel) throws IOException {
        long size = channel.size();
        int tail = (int) Math.min(size, END_LENGTH + MAX_COMMENT_LENGTH);
        ByteBuffer buffer = read(channel, size - tail, tail);
        // The record ends the file except for its comment, whose length it
        // gives, so a match must account for every byte after it.
        long end = -1;
        for (int i = tail - END_LENGTH; i >= 0 && end < 0; --i) {
            if(buffer.getInt(i) == END_SIGNATURE && i + END_LENGTH
                    + (buffer.getShort(i + 20) & 0xFFFF) == tail) {
                end = size - tail + i;
            }
        }
        if(end < 0) {
            throw new IOException("The file does not end with a zip archive");
        }
        else {
            return end;
        }
    }

    /**
     * Add {@code delta} to the local header offset in each of the
     * {@code entries} central directory headers.
     *
     * @param channel the file
     * @param position the position of the central directory in the file
     * @param length the length of the central directory
     * @param entries the number of headers in the central directory
     * @param delta the amount to add
     * @throws IOException if the central directory is malformed or an offset
     *             would need a Zip64 field
     */
    private static void shiftHeaders(FileChannel channel, long position,
            long length, long entries, long delta) throws IOException {
        if(length > Integer.MAX_VALUE) {
            throw new IOException("The central directory is too large");
        }
        else {
            ByteBuffer directory = read(channel, position, (int) length);
            int header = 0;
            for (long i = 0; i < entries; ++i) {
                if(directory.getInt(header) != HEADER_SIGNATURE) {
                    throw new IOException(
                            "The central directory has a malformed header");
                }
                else {
                    long offset = directory.getInt(header + 42) & ZIP64_MARKER;
                    directory.putInt(header + 42, to32Bits(offset + delta));
                    header += HEADER_LENGTH
                            + (directory.getShort(header + 28) & 0xFFFF)
                            + (directory.getShort(header + 30) & 0xFFFF)
                            + (directory.getShort(header + 32) & 0xFFFF);
                }
            }
            write(channel, position, directory);
        }
    }

    /**
     * Return {@code value} as the bits of a 32-bit zip field.
     *
     * @param value an offset
     * @return the field's bits
     * @throws IOException if {@code value} does not fit in a 32-bit field
     *             without a Zip64 field to hold it
     */
    private static int to32Bits(long value) throws IOException {
        if(value >= ZIP64_MARKER) {
            throw new IOException("The archive passes 4 GiB, "
                    + "which needs Zip64 offsets the archive lacks");
        }
        else {
            return (int) value;
        }
    }

    /**
     * Read {@code length} bytes at {@code position}.
     *
     * @param channel the file
     * @param position where to read
     * @param length how many bytes to read
     * @return a little-endian buffer that holds the bytes from index {@code 0}
     * @throws IOException if the file ends first
     */
    private static ByteBuffer read(FileChannel channel, long position,
            int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(length)
                .order(ByteOrder.LITTLE_ENDIAN);
        while (buffer.hasRemaining()) {
            if(channel.read(buffer, position + buffer.position()) < 0) {
                throw new EOFException("The zip archive is truncated");
            }
        }
        buffer.clear();
        return buffer;
    }

    /**
     * Write every byte of {@code buffer}, from index {@code 0}, at
     * {@code position}.
     *
     * @param channel the file
     * @param position where to write
     * @param buffer the bytes
     * @throws IOException if the file cannot be written
     */
    private static void write(FileChannel channel, long position,
            ByteBuffer buffer) throws IOException {
        buffer.clear();
        while (buffer.hasRemaining()) {
            channel.write(buffer, position + buffer.position());
        }
    }

    /**
     * Write {@code value} as a 32-bit little-endian field at {@code position}.
     *
     * @param channel the file
     * @param position where to write
     * @param value the value
     * @throws IOException if {@code value} needs a Zip64 field or the file
     *             cannot be written
     */
    private static void writeInt(FileChannel channel, long position,
            long value) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(0, to32Bits(value));
        write(channel, position, buffer);
    }

    /**
     * Write {@code value} as a 64-bit little-endian field at {@code position}.
     *
     * @param channel the file
     * @param position where to write
     * @param value the value
     * @throws IOException if the file cannot be written
     */
    private static void writeLong(FileChannel channel, long position,
            long value) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(8)
                .order(ByteOrder.LITTLE_ENDIAN);
        buffer.putLong(0, value);
        write(channel, position, buffer);
    }

    /**
     * Prevent instantiation of this utility class.
     */
    private ZipOffsets() {/* no-init */}

}
