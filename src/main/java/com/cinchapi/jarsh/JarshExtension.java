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

import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;

/**
 * The {@link JarshExtension} is the {@code jarsh} block of a build script. It
 * holds the settings of the binary that the {@code jarsh} task builds.
 *
 * @author Jeff Nelson
 */
public abstract class JarshExtension {

    /**
     * Return the file name of the binary, which the {@code jarsh} task writes
     * to the project's libs folder, {@code build/libs} by default. The name
     * defaults to the project's name followed by {@code .sh}.
     *
     * @return the binary's file name
     */
    public abstract Property<String> getName();

    /**
     * Return the fully qualified name of the application's main class. It
     * defaults to the {@code mainClass} of the {@code application} plugin when
     * the project applies that plugin. The default stub runs this class, so the
     * {@code jarsh} task fails without it unless
     * {@link #getLauncher() launcher} is set.
     *
     * @return the main class
     */
    public abstract Property<String> getMainClass();

    /**
     * Return the minimum Java feature version that the application needs, such
     * as {@code 17}. When it is set, the binary runs the application only on a
     * Java of at least that version, and it exits with status {@code 127} and
     * says how to install one when it finds none. When it is absent, the binary
     * runs the application on the first Java it finds.
     *
     * @return the minimum Java version
     */
    public abstract Property<Integer> getJavaVersion();

    /**
     * Return the shell script that starts the binary in place of the default
     * stub. Such a launcher runs any setup the application needs before Java
     * starts and then runs Java itself. The binary keeps the launcher's
     * {@code #!} line and opening comments and then defines the {@code jarsh}
     * helpers, which the rest of the launcher can call, as the README
     * describes.
     *
     * @return the launcher script
     */
    public abstract RegularFileProperty getLauncher();

}
