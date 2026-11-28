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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Calendar;
import java.util.Enumeration;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.annotation.Nullable;

/**
 * A {@link MergedJar} merges an application's classpath into one jar that runs
 * the application the way the classpath does.
 * <p>
 * Where two classpath entries hold a file with the same name, the merged jar
 * keeps the one from the earlier entry, which is the one a class loader finds
 * first. Service files under {@code META-INF/services} are the exception: the
 * merged jar joins them in classpath order, so a
 * {@link java.util.ServiceLoader} still finds every provider. The merged jar
 * leaves out jar signature files and jar indexes, which describe the original
 * jars and do not hold for the merged one. A signed dependency therefore runs
 * unsigned, and the JVM does not reject the merged jar.
 *
 * @author Jeff Nelson
 */
final class MergedJar {

    /**
     * The name of the folder that holds a jar's metadata.
     */
    private static final String META_INF = "META-INF/";

    /**
     * The folder that holds service files.
     */
    private static final String SERVICES = META_INF + "services/";

    /**
     * The manifest attribute that marks a multi-release jar.
     */
    private static final Attributes.Name MULTI_RELEASE = new Attributes.Name(
            "Multi-Release");

    /**
     * The entries that the merged jar leaves out of every classpath jar: the
     * manifest, which the merged jar replaces, and the signature files and jar
     * index, which hold only for the jar they came from. The JVM matches these
     * names without regard to case.
     */
    private static final Pattern OMITTED = Pattern.compile("META-INF/"
            + "(MANIFEST\\.MF|INDEX\\.LIST|SIG-[^/]+|[^/]+\\.(SF|RSA|DSA|EC))",
            Pattern.CASE_INSENSITIVE);

    /**
     * The content of a folder entry.
     */
    private static final byte[] NO_CONTENT = new byte[0];

    /**
     * The modification time of the entries that the merged jar makes rather
     * than copies, fixed so that the same classpath yields the same bytes. Zip
     * times start in 1980, so this is the first day of February 1980.
     */
    private static final long CONSTANT_TIME = new GregorianCalendar(1980,
            Calendar.FEBRUARY, 1).getTimeInMillis();

    /**
     * Write a jar that merges the {@code classpath} to {@code out}.
     * <p>
     * The merged manifest has the main attributes of the first classpath
     * entry's manifest, without {@code Class-Path}. Its {@code Main-Class} is
     * {@code mainClass} when one is given. It declares
     * {@code Multi-Release: true} when any classpath jar does. The manifest is
     * the jar's first file.
     *
     * @param classpath the application's classpath, where an earlier entry
     *            takes precedence over a later one; each entry is a jar or a
     *            folder, and an entry that does not exist adds nothing, as on
     *            the JVM's classpath
     * @param mainClass the application's main class, or {@code null} to keep
     *            the first entry's {@code Main-Class}, if any
     * @param out the stream to write to; it stays open, and this method writes
     *            nothing to it before the jar's first byte
     * @throws IOException if a classpath entry cannot be read or {@code out}
     *             cannot be written
     */
    static void write(Iterable<File> classpath, @Nullable String mainClass,
            OutputStream out) throws IOException {
        Manifest manifest = manifest(classpath, mainClass);
        MergedJar jar = new MergedJar(out);
        jar.add(META_INF, NO_CONTENT, CONSTANT_TIME);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        manifest.write(bytes);
        jar.add(JarFile.MANIFEST_NAME, bytes.toByteArray(), CONSTANT_TIME);
        for (File entry : classpath) {
            if(entry.isDirectory()) {
                jar.addFolder(entry.toPath());
            }
            else if(entry.isFile()) {
                jar.addJar(entry);
            }
        }
        jar.finish();
    }

    /**
     * Return the merged manifest that {@link #write} describes.
     *
     * @param classpath the application's classpath, first entry first
     * @param mainClass the application's main class, or {@code null}
     * @return the {@link Manifest}
     * @throws IOException if a classpath jar cannot be read
     */
    private static Manifest manifest(Iterable<File> classpath,
            @Nullable String mainClass) throws IOException {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        boolean first = true;
        boolean multiRelease = false;
        for (File entry : classpath) {
            Manifest source = entry.isFile() ? manifestOf(entry) : null;
            if(source != null) {
                Attributes main = source.getMainAttributes();
                if(first) {
                    attributes.putAll(main);
                }
                multiRelease = multiRelease || "true"
                        .equalsIgnoreCase(main.getValue(MULTI_RELEASE));
            }
            first = false;
        }
        attributes.putIfAbsent(Attributes.Name.MANIFEST_VERSION, "1.0");
        attributes.remove(Attributes.Name.CLASS_PATH);
        if(mainClass != null) {
            attributes.put(Attributes.Name.MAIN_CLASS, mainClass);
        }
        if(multiRelease) {
            attributes.put(MULTI_RELEASE, "true");
        }
        return manifest;
    }

    /**
     * Return the manifest of the jar at {@code file}.
     *
     * @param file a jar or other zip archive
     * @return the jar's {@link Manifest}, or {@code null} when it has none
     * @throws IOException if {@code file} cannot be read as a zip archive
     */
    @Nullable
    private static Manifest manifestOf(File file) throws IOException {
        try (JarFile jar = new JarFile(file, false)) {
            return jar.getManifest();
        }
    }

    /**
     * Return whether {@code name} is a service file, which names the providers
     * of one service and sits directly in {@code META-INF/services}.
     *
     * @param name an entry name
     * @return {@code true} if {@code name} is a service file
     */
    private static boolean isServiceFile(String name) {
        return name.startsWith(SERVICES) && name.length() > SERVICES.length()
                && name.indexOf('/', SERVICES.length()) < 0;
    }

    /**
     * Copy the rest of {@code in} to {@code out}.
     *
     * @param in the stream to read
     * @param out the stream to write; it stays open
     * @param buffer the buffer to copy through
     * @throws IOException if either stream fails
     */
    private static void copy(InputStream in, OutputStream out, byte[] buffer)
            throws IOException {
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
    }

    /**
     * The stream that receives the merged jar.
     */
    private final JarOutputStream out;

    /**
     * The names of the entries written so far.
     */
    private final Set<String> names = new HashSet<>();

    /**
     * The joined content of each service file, by name, in the order the
     * classpath first holds them.
     */
    private final Map<String, ByteArrayOutputStream> services =
            new LinkedHashMap<>();

    /**
     * The buffer that entry contents pass through.
     */
    private final byte[] buffer = new byte[64 * 1024];

    /**
     * Start a merged jar on {@code out}.
     *
     * @param out the stream to write to; it stays open
     * @throws IOException if {@code out} cannot be written
     */
    private MergedJar(OutputStream out) throws IOException {
        this.out = new JarOutputStream(out);
    }

    /**
     * Add each entry of the jar at {@code file} that the merged jar keeps.
     *
     * @param file a jar or other zip archive
     * @throws IOException if {@code file} cannot be read
     */
    private void addJar(File file) throws IOException {
        try (ZipFile zip = new ZipFile(file)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if(isServiceFile(name)) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        addService(name, in);
                    }
                }
                else if(isKept(name)) {
                    try (InputStream in = zip.getInputStream(entry)) {
                        add(name, in, entry.getTime());
                    }
                }
            }
        }
    }

    /**
     * Add each file and folder under {@code root} that the merged jar keeps,
     * named by its path relative to {@code root}.
     *
     * @param root a classpath folder
     * @throws IOException if a file under {@code root} cannot be read
     */
    private void addFolder(Path root) throws IOException {
        List<Path> paths;
        try (Stream<Path> walk = Files.walk(root)) {
            paths = walk.filter(path -> !path.equals(root)).sorted()
                    .collect(Collectors.toList());
        }
        for (Path path : paths) {
            String name = root.relativize(path).toString()
                    .replace(File.separatorChar, '/');
            long time = Files.getLastModifiedTime(path).toMillis();
            if(Files.isDirectory(path)) {
                name += "/";
                if(isKept(name)) {
                    add(name, NO_CONTENT, time);
                }
            }
            else if(isServiceFile(name)) {
                try (InputStream in = Files.newInputStream(path)) {
                    addService(name, in);
                }
            }
            else if(isKept(name)) {
                try (InputStream in = Files.newInputStream(path)) {
                    add(name, in, time);
                }
            }
        }
    }

    /**
     * Return whether the merged jar keeps an entry named {@code name} that is
     * not a service file: whether it is neither omitted nor already written.
     *
     * @param name an entry name
     * @return {@code true} if the merged jar keeps the entry
     */
    private boolean isKept(String name) {
        return !OMITTED.matcher(name).matches() && !names.contains(name);
    }

    /**
     * Append the service file {@code name}, read from {@code in}, to the joined
     * service file of that name, ending it with a newline so the next file's
     * first provider starts on its own line.
     *
     * @param name the service file's name
     * @param in the service file's content
     * @throws IOException if {@code in} cannot be read
     */
    private void addService(String name, InputStream in) throws IOException {
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        copy(in, file, buffer);
        byte[] content = file.toByteArray();
        ByteArrayOutputStream joined = services.computeIfAbsent(name,
                key -> new ByteArrayOutputStream());
        joined.write(content);
        if(content.length > 0 && content[content.length - 1] != '\n') {
            joined.write('\n');
        }
    }

    /**
     * Write a file entry named {@code name} with the rest of {@code in}.
     *
     * @param name the entry's name
     * @param in the entry's content
     * @param time the entry's modification time in milliseconds since the
     *            epoch, or {@code -1} for {@link #CONSTANT_TIME}
     * @throws IOException if {@code in} cannot be read or the jar cannot be
     *             written
     */
    private void add(String name, InputStream in, long time)
            throws IOException {
        start(name, time);
        copy(in, out, buffer);
        out.closeEntry();
    }

    /**
     * Write an entry named {@code name} with the {@code content}.
     *
     * @param name the entry's name; a folder's name ends with {@code /}
     * @param content the entry's content, which is {@link #NO_CONTENT} for a
     *            folder
     * @param time the entry's modification time in milliseconds since the
     *            epoch, or {@code -1} for {@link #CONSTANT_TIME}
     * @throws IOException if the jar cannot be written
     */
    private void add(String name, byte[] content, long time)
            throws IOException {
        start(name, time);
        out.write(content);
        out.closeEntry();
    }

    /**
     * Start an entry named {@code name} and record its name.
     *
     * @param name the entry's name
     * @param time the entry's modification time in milliseconds since the
     *            epoch, or {@code -1} for {@link #CONSTANT_TIME}
     * @throws IOException if the jar cannot be written
     */
    private void start(String name, long time) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(time == -1 ? CONSTANT_TIME : time);
        out.putNextEntry(entry);
        names.add(name);
    }

    /**
     * Write the joined service files and the end of the jar, and flush the
     * underlying stream without closing it.
     *
     * @throws IOException if the jar cannot be written
     */
    private void finish() throws IOException {
        for (Map.Entry<String, ByteArrayOutputStream> service : services
                .entrySet()) {
            add(service.getKey(), service.getValue().toByteArray(),
                    CONSTANT_TIME);
        }
        out.finish();
        out.flush();
    }

}
