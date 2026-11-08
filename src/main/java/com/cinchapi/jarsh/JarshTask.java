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

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import javax.annotation.Nullable;

import org.gradle.api.DefaultTask;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.FileCollection;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Classpath;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.gradle.work.DisableCachingByDefault;

/**
 * A {@link JarshTask} builds a Java application into a binary: one executable
 * file that is both a shell script and a jar.
 * <p>
 * The binary starts with a launcher script, then holds a jar that merges the
 * application's classpath. The jar runs the application the way that classpath
 * does: it joins the service files under {@code META-INF/services} and leaves
 * out jar signature files and jar indexes, so a signed dependency runs
 * unsigned. The launcher is the default stub unless
 * {@link #getLauncher() launcher} names a script of the project's own. Either
 * way, the binary keeps the launcher's {@code #!} line and opening comments,
 * then defines the {@code jarsh} helper variables and functions, then runs the
 * rest of the launcher. The shell never reads past the launcher, and zip tools
 * and Java read the binary as an ordinary jar.
 * <p>
 * The default stub runs the jar's main class with a suitable {@code java}. It
 * passes on every argument unchanged, keeps the caller's working directory,
 * writes no file, and exits with the application's status.
 *
 * @author Jeff Nelson
 */
@DisableCachingByDefault(because = "Not worth caching")
public abstract class JarshTask extends DefaultTask {

    /**
     * The interpreter line of a binary whose launcher has none.
     */
    private static final String DEFAULT_INTERPRETER = "#!/bin/sh\n";

    /**
     * The text in the helper functions that the minimum Java version replaces.
     */
    private static final String JAVA_VERSION_PLACEHOLDER = "@JAVA_VERSION@";

    /**
     * Return the application's classpath, the application's own jar first.
     * Where two entries hold a file with the same name, the binary keeps the
     * one from the earlier entry, and the first entry's manifest is the base of
     * the binary's manifest.
     *
     * @return the classpath
     */
    @Classpath
    public abstract ConfigurableFileCollection getClasspath();

    /**
     * Return the files of the {@link #getClasspath() classpath} as plain
     * inputs. The binary copies every byte of them, so a change to any of them,
     * including a file that the build's runtime classpath normalization
     * ignores, makes the task write the binary again.
     *
     * @return the classpath's files
     */
    @InputFiles
    @PathSensitive(PathSensitivity.RELATIVE)
    public FileCollection getClasspathContent() {
        return getClasspath();
    }

    /**
     * Return the fully qualified name of the application's main class, which
     * the binary's manifest names. The task fails without it unless
     * {@link #getLauncher() launcher} is set.
     *
     * @return the main class
     */
    @Input
    @Optional
    public abstract Property<String> getMainClass();

    /**
     * Return the minimum Java feature version that the binary looks for, such
     * as {@code 17}, or nothing for any version. It must be positive.
     *
     * @return the minimum Java version
     */
    @Input
    @Optional
    public abstract Property<Integer> getJavaVersion();

    /**
     * Return the shell script that starts the binary in place of the default
     * stub.
     *
     * @return the launcher script
     */
    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public abstract RegularFileProperty getLauncher();

    /**
     * Return the binary that the task writes. The task replaces any file there
     * and makes the binary executable by every user.
     *
     * @return the binary
     */
    @OutputFile
    public abstract RegularFileProperty getBinary();

    /**
     * Write the binary.
     *
     * @throws InvalidUserDataException if neither
     *             {@link #getMainClass() mainClass} nor
     *             {@link #getLauncher() launcher} is set, or
     *             {@link #getJavaVersion() javaVersion} is not positive
     * @throws IOException if the launcher or a classpath entry cannot be read,
     *             or the binary cannot be written
     */
    @TaskAction
    public void build() throws IOException {
        String mainClass = getMainClass().getOrNull();
        Integer javaVersion = getJavaVersion().getOrNull();
        if(mainClass == null && !getLauncher().isPresent()) {
            throw new InvalidUserDataException("jarsh needs the application's "
                    + "main class. Apply the application plugin and set "
                    + "application.mainClass, or set jarsh.mainClass.");
        }
        else if(javaVersion != null && javaVersion < 1) {
            throw new InvalidUserDataException("jarsh.javaVersion must be a "
                    + "Java feature version such as 17, not " + javaVersion
                    + ".");
        }
        else {
            byte[] script = script(javaVersion)
                    .getBytes(StandardCharsets.UTF_8);
            File binary = getBinary().get().getAsFile();
            try (OutputStream out = new BufferedOutputStream(
                    new FileOutputStream(binary))) {
                out.write(script);
                MergedJar.write(getClasspath(), mainClass, out);
            }
            ZipOffsets.shift(binary.toPath(), script.length);
            if(!binary.setExecutable(true, false)) {
                throw new IOException("Cannot make " + binary + " executable");
            }
        }
    }

    /**
     * Return the script that starts the binary: the launcher's {@code #!} line
     * and opening comments, the helpers, the rest of the launcher, and an
     * {@code exit} that keeps the shell from reading the jar after it. A
     * launcher without a {@code #!} line runs with {@code /bin/sh}.
     *
     * @param javaVersion the minimum Java version, or {@code null} for any
     * @return the script, ending with a newline
     * @throws IOException if the launcher cannot be read
     */
    private String script(@Nullable Integer javaVersion) throws IOException {
        String launcher;
        if(getLauncher().isPresent()) {
            launcher = new String(Files.readAllBytes(
                    getLauncher().get().getAsFile().toPath()),
                    StandardCharsets.UTF_8);
        }
        else {
            launcher = resource("stub.sh");
        }
        StringBuilder script = new StringBuilder();
        if(!launcher.startsWith("#!")) {
            script.append(DEFAULT_INTERPRETER);
        }
        int body = 0;
        while (body < launcher.length() && launcher.charAt(body) == '#') {
            int next = launcher.indexOf('\n', body);
            body = next < 0 ? launcher.length() : next + 1;
        }
        script.append(launcher, 0, body);
        if(script.charAt(script.length() - 1) != '\n') {
            script.append('\n');
        }
        script.append(resource("functions.sh").replace(
                JAVA_VERSION_PLACEHOLDER,
                javaVersion == null ? "" : javaVersion.toString()));
        script.append(launcher, body, launcher.length());
        if(script.charAt(script.length() - 1) != '\n') {
            script.append('\n');
        }
        script.append("exit\n");
        return script.toString();
    }

    /**
     * Return the text of the script resource named {@code name} that ships with
     * this plugin.
     *
     * @param name the resource's name in this class's package
     * @return the resource's text
     * @throws IOException if the resource cannot be read
     */
    private static String resource(String name) throws IOException {
        try (InputStream in = JarshTask.class.getResourceAsStream(name)) {
            ByteArrayOutputStream text = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                text.write(buffer, 0, read);
            }
            return new String(text.toByteArray(), StandardCharsets.UTF_8);
        }
    }

}
