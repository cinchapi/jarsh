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

import static org.junit.Assert.assertEquals;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit tests for {@link ZipOffsets}. They use the {@code unzip} program as an
 * independent reader: it warns and exits {@code 1} when an archive's offsets do
 * not count from the start of the file.
 *
 * @author Jeff Nelson
 */
public class ZipOffsetsTest {

    /**
     * The script that each test archive starts with.
     */
    private static final String PREFIX = "#!/bin/sh\necho prefix\nexit\n";

    /**
     * The folder that holds each test's archive.
     */
    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    /**
     * <strong>Goal:</strong> Verify that after a shift, {@code unzip} and
     * {@link ZipFile} both read an archive that follows a script, and
     * {@code unzip} reports nothing amiss.
     * <p>
     * <strong>Start state:</strong> A file that holds {@link #PREFIX} and then
     * a zip archive of two entries, whose offsets count from the start of the
     * archive.
     * <p>
     * <strong>Workflow:</strong>
     * <ul>
     * <li>Shift the archive's offsets by the length of the prefix.</li>
     * <li>Run {@code unzip -p} on the second entry.</li>
     * <li>Read the second entry with {@link ZipFile}.</li>
     * </ul>
     * <p>
     * <strong>Expected:</strong> {@code unzip} exits {@code 0}, prints the
     * entry's content, and prints nothing to stderr; {@link ZipFile} reads the
     * same content; the file still starts with the prefix.
     */
    @Test
    public void testShiftLetsZipToolsReadArchiveAfterScript()
            throws IOException, InterruptedException {
        File file = archive(2);
        ZipOffsets.shift(file.toPath(), PREFIX.length());
        assertReadable(file, "entry-1.txt", "content 1");
    }

    /**
     * <strong>Goal:</strong> Verify that the shift also handles a Zip64
     * archive, which {@link ZipOutputStream} writes when an archive holds more
     * than 65,535 entries.
     * <p>
     * <strong>Start state:</strong> A file that holds {@link #PREFIX} and then
     * a zip archive of 70,000 entries.
     * <p>
     * <strong>Workflow:</strong>
     * <ul>
     * <li>Shift the archive's offsets by the length of the prefix.</li>
     * <li>Run {@code unzip -p} on the last entry.</li>
     * <li>Read the last entry with {@link ZipFile}.</li>
     * </ul>
     * <p>
     * <strong>Expected:</strong> {@code unzip} exits {@code 0}, prints the
     * entry's content, and prints nothing to stderr; {@link ZipFile} reads the
     * same content.
     */
    @Test
    public void testShiftHandlesZip64Archive()
            throws IOException, InterruptedException {
        File file = archive(70_000);
        ZipOffsets.shift(file.toPath(), PREFIX.length());
        assertReadable(file, "entry-69999.txt", "content 69999");
    }

    /**
     * Write {@link #PREFIX} and then a zip archive of {@code count} entries,
     * named {@code entry-<i>.txt} and holding {@code content <i>}, to a new
     * file.
     *
     * @param count the number of entries
     * @return the file
     * @throws IOException if the file cannot be written
     */
    private File archive(int count) throws IOException {
        File file = temp.newFile("archive.sh");
        try (OutputStream out = new BufferedOutputStream(
                new FileOutputStream(file))) {
            out.write(PREFIX.getBytes(StandardCharsets.UTF_8));
            ZipOutputStream zip = new ZipOutputStream(out);
            for (int i = 0; i < count; ++i) {
                zip.putNextEntry(new ZipEntry("entry-" + i + ".txt"));
                zip.write(("content " + i).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.finish();
        }
        return file;
    }

    /**
     * Assert that {@code unzip} and {@link ZipFile} both read {@code content}
     * from the entry {@code name} of the {@code file}, that {@code unzip}
     * prints no warning, and that the file still starts with {@link #PREFIX}.
     *
     * @param file the file that holds the archive
     * @param name the entry to read
     * @param content the entry's expected content
     * @throws IOException if the file cannot be read
     * @throws InterruptedException if the test is interrupted while
     *             {@code unzip} runs
     */
    private void assertReadable(File file, String name, String content)
            throws IOException, InterruptedException {
        File stdout = temp.newFile("unzip.out");
        File stderr = temp.newFile("unzip.err");
        Process unzip = new ProcessBuilder("unzip", "-p",
                file.getAbsolutePath(), name).redirectOutput(stdout)
                        .redirectError(stderr).start();
        unzip.waitFor(60, TimeUnit.SECONDS);
        assertEquals("", text(stderr));
        assertEquals(0, unzip.exitValue());
        assertEquals(content, text(stdout));
        try (ZipFile zip = new ZipFile(file);
                InputStream in = zip.getInputStream(zip.getEntry(name))) {
            assertEquals(content,
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        byte[] start = new byte[PREFIX.length()];
        System.arraycopy(Files.readAllBytes(file.toPath()), 0, start, 0,
                start.length);
        assertEquals(PREFIX, new String(start, StandardCharsets.UTF_8));
    }

    /**
     * Return the content of the {@code file} as UTF-8 text.
     *
     * @param file the file
     * @return its content
     * @throws IOException if the file cannot be read
     */
    private static String text(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()),
                StandardCharsets.UTF_8);
    }

}
