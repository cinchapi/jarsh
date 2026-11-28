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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.ZipFile;

import javax.tools.ToolProvider;

import org.gradle.testkit.runner.BuildResult;
import org.gradle.testkit.runner.GradleRunner;
import org.gradle.testkit.runner.TaskOutcome;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Functional tests for the {@link JarshPlugin}. Each test builds a sample
 * application with Gradle TestKit and then runs the binary it produces with a
 * controlled environment.
 *
 * @author Jeff Nelson
 */
public class JarshPluginFunctionalTest {

    /**
     * The feature version of the JDK that runs the tests. The sample builds
     * compile with it, so the sample binaries need at least this version.
     */
    private static final int JAVA = Runtime.version().feature();

    /**
     * A build script that names the sample's main class through the
     * {@code application} plugin.
     */
    private static final String APPLICATION = """
            plugins {
                id 'application'
                id 'com.cinchapi.jarsh'
            }

            application {
                mainClass = 'sample.Main'
            }
            """;

    /**
     * The sample application. It prints the Java version it runs on, its
     * working directory, the {@code sample.prop} system property, and each
     * argument. Given {@code greet}, it calls a class from a signed jar. Given
     * {@code exit N}, it exits with status {@code N}.
     */
    private static final String MAIN = """
            package sample;

            import java.io.File;

            public class Main {
                public static void main(String[] args) throws Exception {
                    System.out.println("java=" + Runtime.version().feature());
                    System.out.println("cwd=" + new File("").getAbsolutePath());
                    System.out.println("prop="
                            + System.getProperty("sample.prop"));
                    for (String arg : args) {
                        System.out.println("arg=<" + arg + ">");
                    }
                    if(args.length > 0 && args[0].equals("greet")) {
                        System.out.println(Class.forName("signed.Greeter")
                                .getMethod("greet").invoke(null));
                    }
                    if(args.length > 1 && args[0].equals("exit")) {
                        System.exit(Integer.parseInt(args[1]));
                    }
                }
            }
            """;

    /**
     * A stand-in for a Java 8 {@code java}: it reports version 1.8.0_452 to
     * {@code -version} and otherwise prints {@code fake java ran} and exits
     * with status 3.
     */
    private static final String OLD_JAVA = """
            #!/bin/sh
            if [ "$1" = -version ]; then
                echo 'openjdk version "1.8.0_452"' >&2
                exit 0
            fi
            echo "fake java ran"
            exit 3
            """;

    /**
     * The folder that holds each test's sample project, fake home, and other
     * files.
     */
    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    /**
     * The sample project's folder.
     */
    private File project;

    /**
     * The folder that serves as {@code HOME} when a binary runs, so the user's
     * own SDKMAN and Gradle JDKs stay out of each test.
     */
    private File home;

    /**
     * Create the sample project, without a build script, and the empty home
     * folder.
     *
     * @throws IOException if a file cannot be written
     */
    @Before
    public void setUp() throws IOException {
        project = temp.newFolder("sample");
        home = temp.newFolder("home");
        write("settings.gradle", "rootProject.name = 'sample'\n");
        write("src/main/java/sample/Main.java", MAIN);
    }

    /**
     * <strong>Goal:</strong> Verify that the binary hands every argument to the
     * application exactly as the caller passed it.
     * <p>
     * <strong>Start state:</strong> A sample project that names its main class
     * with the {@code application} plugin.
     * <p>
     * <strong>Workflow:</strong>
     * <ul>
     * <li>Run the {@code jarsh} task.</li>
     * <li>Run the binary with arguments that hold spaces, an empty string,
     * glob and variable characters, quotes, a backslash, and a newline.</li>
     * </ul>
     * <p>
     * <strong>Expected:</strong> The binary exits {@code 0}, prints nothing to
     * stderr, and the application prints each argument unchanged and in order.
     */
    @Test
    public void testBinaryPassesArgumentsUnchanged() throws Exception {
        write("build.gradle", APPLICATION);
        jarsh();
        String[] args = { "plain", "two words", "", "*", "$HOME", "--flag=x",
                "line1\nline2", "quote\"d", "back\\slash", "'single'" };
        File cwd = temp.newFolder("cwd");
        Run run = run(binary("sample.sh"), cwd, environment(), args);
        StringBuilder expected = new StringBuilder(header(cwd));
        for (String arg : args) {
            expected.append("arg=<").append(arg).append(">\n");
        }
        assertEquals("", run.stderr());
        assertEquals(0, run.status());
        assertEquals(expected.toString(), run.stdout());
    }

    /**
     * <strong>Goal:</strong> Verify that the application runs in the caller's
     * working directory and that the binary writes nothing there.
     * <p>
     * <strong>Start state:</strong> A sample project that names its main class
     * with the {@code application} plugin, and an empty folder.
     * <p>
     * <strong>Workflow:</strong>
     * <ul>
     * <li>Run the {@code jarsh} task.</li>
     * <li>Run the binary, by its absolute path, from the empty folder.</li>
     * <li>List the folder.</li>
     * </ul>
     * <p>
     * <strong>Expected:</strong> The application reports the empty folder as
     * its working directory, and the folder is still empty.
     */
    @Test
    public void testBinaryRunsInCallersWorkingDirectory() throws Exception {
        write("build.gradle", APPLICATION);
        jarsh();
        File cwd = temp.newFolder("elsewhere");
        Run run = run(binary("sample.sh"), cwd, environment());
        assertEquals(run.stderr(), 0, run.status());
        assertEquals(header(cwd), run.stdout());
        assertEquals(Collections.emptyList(), Arrays.asList(cwd.list()));
    }

    /**
     * <strong>Goal:</strong> Verify that the binary runs when its own path
     * holds a space.
     * <p>
     * <strong>Start state:</strong> A sample project that names its main class
     * with the {@code application} plugin.
     * <p>
     * <strong>Workflow:</strong>
     * <ul>
     * <li>Run the {@code jarsh} task.</li>
     * <li>Copy the binary into a folder named {@code with space}.</li>
     * <li>Run the copy with {@code x}.</li>
     * </ul>
     * <p>
     * <strong>Expected:</strong> The binary exits {@code 0} and the application
     * prints {@code x}.
     */
    @Test
    public void testBinaryRunsFromPathWithSpace() throws Exception {
        write("build.gradle", APPLICATION);
        jarsh();
        File copy = new File(temp.newFolder("with space"), "sample.sh");
        Files.copy(binary("sample.sh").toPath(), copy.toPath(),
                StandardCopyOption.COPY_ATTRIBUTES);
        Run run = run(copy, temp.newFolder("cwd"), environment(), "x");
        assertEquals(run.stderr(), 0, run.status());
        assertTrue(run.stdout(), run.stdout().endsWith("arg=<x>\n"));
    }

    /**
     * <strong>Goal:</strong> Verify that the binary exits with the status the
     * application exits with.
     * <p>
     * <strong>Start state:</strong> A sample project that names its main class
     * with the {@code application} plugin.
     * <p>
     * <strong>Workflow:</strong>
     * <ul>
     * <li>Run the {@code jarsh} task.</li>
     * <li>Run the binary with {@code exit 42}.</li>
     * </ul>
     * <p>
     * <strong>Expected:</strong> The binary exits {@code 42}.
     */
    @Test
    public void testBinaryExitsWithApplicationStatus() throws Exception {
        write("build.gradle", APPLICATION);
        jarsh();
        Run run = run(binary("sample.sh"), temp.newFolder("cwd"),
                environment(), "exit", "42");
        assertEquals(run.stderr(), 42, run.status());
    }

    /**
     * <strong>Goal:</strong> Verify that the binary passes the words of the
     * {@code java_args} environment variable to {@code java} as JVM options.
     * <p>
     * <strong>Start state:</strong> A sample project that names its main class
     * with the {@code application} plugin.
     * <p>
     * <strong>Workflow:</strong>
     * <ul>
     * <li>Run the {@code jarsh} task.</li>
     * <li>Run the binary with {@code java_args} set to
     * {@code -Dsample.prop=set -Dother=x}.</li>
     * </ul>
     * <p>
     * <strong>Expected:</strong> The application sees {@code sample.prop} set
     * to {@code set}.
     */
    @Test
    public void testBinaryPassesJavaArgsToJava() throws Exception {
        write("build.gradle", APPLICATION);
        jarsh();
        Map<String, String> environment = environment();
        environment.put("java_args", "-Dsample.prop=set -Dother=x");
        Run run = run(binary("sample.sh"), temp.newFolder("cwd"), environment);
        assertEquals(run.stderr(), 0, run.status());
        assertTrue(run.stdout(), run.stdout().contains("\nprop=set\n"));
    }

    /**
     * <strong>Goal:</strong> Verify that with a minimum Java version, the
     * binary passes over a {@code JAVA_HOME} and a {@code java} on the
     * {@code PATH} that are too old, and also any Java that {@code java_home}
     * names, and runs the SDKMAN candidate that meets the minimum.
     * <p>
     * <strong>Start state:</strong> A sample project with
     * {@code javaVersion = 99}; a fake Java 8 home with a {@code release} file;
     * a fake Java 8 {@code java} alone in a folder; and, as an SDKMAN candidate
     * in the fake home, a fake Java 99 that prints its own path. No real JDK is
     * version 99, so only the fake candidate qualifies.
     * <p>
     * <strong>Workflow:</strong>
     * <ul>
     * <li>Run the {@code jarsh} task.</li>
     * <li>Run the binary with {@code JAVA_HOME} set to the fake home and
     * {@code PATH} set to the fake {@code java}'s folder.</li>
     * </ul>
     * <p>
     * <strong>Expected:</strong> The binary exits {@code 0}, and only the
     * SDKMAN candidate ran.
     */
    @Test
    public void testBinarySkipsJavaOlderThanMinimum() throws Exception {
        write("build.gradle", APPLICATION + javaVersion(99));
        jarsh();
        Map<String, String> environment = oldJavaEnvironment();
        File candidate = newJava(
                new File(home, ".sdkman/candidates/java/future"));
        Run run = run(binary("sample.sh"), temp.newFolder("cwd"),
                environment);
        assertEquals(run.stderr(), 0, run.status());
        assertEquals(candidate + "/bin/java ran\n", run.stdout());
    }

    /**
     * <strong>Goal:</strong> Verify that when no Java meets the minimum, the
     * binary does not run the application, names each Java it rejected, and
     * says how to install a suitable one.
     * <p>
     * <strong>Start state:</strong> A sample project with
     * {@code javaVersion = 99}; a fake Java 8 home with a {@code release} file;
     * a fake Java 8 {@code java} alone in a folder; and the test JDK as an
     * SDKMAN candidate in the fake home.
     * <p>
     * <strong>Workflow:</strong>
     * <ul>
     * <li>Run the {@code jarsh} task.</li>
     * <li>Run the binary with {@code JAVA_HOME} set to the fake home and
     * {@code PATH} set to the fake {@code java}'s folder.</li>
     * </ul>
     * <p>
     * <strong>Expected:</strong> The binary exits {@code 127} and prints
     * nothing to stdout. Its stderr says that it needs Java 99 or newer,
     * reports both fakes as Java 8 and the SDKMAN candidate as the test JDK's
     * version, and points to an installer.
     */
    @Test
    public void testBinaryFailsWhenNoJavaMeetsMinimum() throws Exception {
        write("build.gradle", APPLICATION + javaVersion(99));
        jarsh();
        Map<String, String> environment = oldJavaEnvironment();
        File candidate = new File(home, ".sdkman/candidates/java/test");
        link(candidate, javaHome());
        Run run = run(binary("sample.sh"), temp.newFolder("cwd"),
                environment);
        assertEquals(127, run.status());
        assertEquals("", run.stdout());
        assertTrue(run.stderr(),
                run.stderr().contains("sample.sh needs Java 99 or newer"));
        assertTrue(run.stderr(), run.stderr().contains(environment
                .get("JAVA_HOME") + "/bin/java is Java 8"));
        assertTrue(run.stderr(), run.stderr().contains(
                environment.get("PATH") + "/java is Java 8"));
        assertTrue(run.stderr(), run.stderr()
                .contains(candidate + "/bin/java is Java " + JAVA));
        assertTrue(run.stderr(), run.stderr().contains("https://adoptium.net"));
    }

    /**
     * <strong>Goal:</strong> Verify that the binary finds a JDK that Gradle
     * provisioned under {@code ~/.gradle/jdks} when nothing earlier in the
     * search qualifies, and that a {@code JAVA_HOME} without {@code bin/java}
     * does not stop the search.
     * <p>
     * <strong>Start state:</strong> A sample project with
     * {@code javaVersion = 99}, and a fake Java 99 that prints its own path,
     * nested in the fake home's {@code .gradle/jdks} the way Gradle nests a
     * provisioned JDK. No real JDK is version 99, so only the fake JDK
     * qualifies.
     * <p>
     * <strong>Workflow:</strong>
     * <ul>
     * <li>Run the {@code jarsh} task.</li>
     * <li>Run the binary with {@code JAVA_HOME} set to an empty folder and a
     * {@code PATH} with no {@code java}.</li>
     * </ul>
     * <p>
     * <strong>Expected:</strong> The binary exits {@code 0}, and only the fake
     * provisioned JDK ran.
     */
    @Test
    public void testBinaryFindsGradleProvisionedJdk() throws Exception {
        write("build.gradle", APPLICATION + javaVersion(99));
        jarsh();
        File jdk = newJava(new File(home, ".gradle/jdks/provisioned/jdk"));
        Map<String, String> environment = environment();
        environment.put("JAVA_HOME", temp.newFolder("empty-home").getPath());
        environment.put("PATH", temp.newFolder("empty-path").getPath());
        Run run = run(binary("sample.sh"), temp.newFolder("cwd"),
                environment);
        assertEquals(run.stderr(), 0, run.status());
        assertEquals(jdk + "/bin/java ran\n", run.stdout());
    }

    /**
     * <strong>Goal:</strong> Verify that an application that depends on a
     * signed jar runs from the binary and calls into that jar, and that the
     * binary carries none of the jar's signature files.
     * <p>
     * <strong>Start state:</strong> A sample project that names its main class
     * with the {@code application} plugin and depends on a jar signed with
     * {@code jarsigner}.
     * <p>
     * <strong>Workflow:</strong>
     * <ul>
     * <li>Run the {@code jarsh} task.</li>
     * <li>Run the binary with {@code greet}, which loads and calls a class
     * from the signed jar.</li>
     * <li>List the binary's entries.</li>
     * </ul>
     * <p>
     * <strong>Expected:</strong> The binary exits {@code 0}, the application
     * prints the signed class's greeting, and no entry of the binary is a
     * signature file.
     */
    @Test
    public void testBinaryRunsApplicationWithSignedDependency()
            throws Exception {
        signedJar(new File(project, "libs/greeter.jar"));
        write("build.gradle", APPLICATION + """

                dependencies {
                    implementation files('libs/greeter.jar')
                }
                """);
        jarsh();
        File binary = binary("sample.sh");
        Run run = run(binary, temp.newFolder("cwd"), environment(), "greet");
        assertEquals(run.stderr(), 0, run.status());
        assertTrue(run.stdout(),
                run.stdout().endsWith("hello from a signed jar\n"));
        try (ZipFile zip = new ZipFile(binary)) {
            zip.stream().forEach(entry -> assertFalse(entry.getName(),
                    entry.getName().matches("META-INF/[^/]+\\.(SF|RSA)")));
        }
    }

    /**
     * <strong>Goal:</strong> Verify that a project's own launcher replaces the
     * default stub, keeps its interpreter and opening comments, can read a file
     * bundled in the binary, and can run the application with the helper
     * functions under {@code set -euo pipefail}.
     * <p>
     * <strong>Start state:</strong> A sample project that bundles
     * {@code sample/setup.txt} as a resource and sets {@code launcher} to a
     * bash script. The script reads that file with {@code jarsh_read}, prints
     * it, and runs the application with {@code jarsh_exec_java}.
     * <p>
     * <strong>Workflow:</strong>
     * <ul>
     * <li>Run the {@code jarsh} task.</li>
     * <li>Run the binary with {@code exit 3}.</li>
     * <li>Read the start of the binary.</li>
     * </ul>
     * <p>
     * <strong>Expected:</strong> The launcher prints the bundled file's content
     * and that bash runs it, the application then runs with the arguments, the
     * binary exits {@code 3}, and the binary starts with the launcher's
     * {@code #!} line and comment, followed by a blank line that ends the
     * comment block.
     */
    @Test
    public void testCustomLauncherReadsBundledFileAndRunsApplication()
            throws Exception {
        write("src/main/resources/sample/setup.txt", "setup data\n");
        String launcher = """
                #!/usr/bin/env bash
                # Print the bundled setup file, then run the sample.
                set -euo pipefail
                setup=$(mktemp)
                jarsh_read sample/setup.txt > "$setup"
                echo "setup=$(cat "$setup") shell=${BASH_VERSION:+bash}"
                rm -f "$setup"
                jarsh_exec_java -cp "$JARSH_BINARY" sample.Main "$@"
                """;
        write("src/main/sh/launcher.sh", launcher);
        write("build.gradle", APPLICATION + """

                jarsh {
                    launcher = file('src/main/sh/launcher.sh')
                }
                """);
        jarsh();
        File binary = binary("sample.sh");
        File cwd = temp.newFolder("cwd");
        Run run = run(binary, cwd, environment(), "exit", "3");
        assertEquals("", run.stderr());
        assertEquals(3, run.status());
        assertEquals("setup=setup data shell=bash\n" + header(cwd)
                + "arg=<exit>\narg=<3>\n", run.stdout());
        String start = new String(Files.readAllBytes(binary.toPath()), 0, 100,
                StandardCharsets.UTF_8);
        assertTrue(start, start.startsWith("#!/usr/bin/env bash\n"
                + "# Print the bundled setup file, then run the sample.\n\n"));
    }

    /**
     * <strong>Goal:</strong> Verify that the helpers work in each POSIX shell
     * on the machine under {@code set -eu}: {@code jarsh_read} reads a bundled
     * file and fails for a missing one, and {@code jarsh_find_java} passes over
     * a {@code JAVA_HOME} too old by its {@code -version} output.
     * <p>
     * <strong>Start state:</strong> A sample project with {@code javaVersion}
     * set to the test JDK's version that bundles {@code sample/setup.txt} and
     * has a launcher that uses the helpers; a fake Java 8 home without a
     * {@code release} file; and the test JDK as an SDKMAN candidate in the fake
     * home.
     * <p>
     * <strong>Workflow:</strong>
     * <ul>
     * <li>Run the {@code jarsh} task.</li>
     * <li>For each of {@code /bin/sh}, {@code /bin/dash}, {@code /bin/bash},
     * and {@code /bin/ksh} that exists, run the binary with that shell and
     * {@code exit 5}, with {@code JAVA_HOME} set to the fake home.</li>
     * </ul>
     * <p>
     * <strong>Expected:</strong> Under every shell, the launcher prints the
     * bundled file's content, reports that reading the missing file failed, and
     * runs the application with the arguments; the binary exits {@code 5} and
     * prints nothing to stderr.
     */
    @Test
    public void testHelpersWorkInPosixShells() throws Exception {
        write("src/main/resources/sample/setup.txt", "setup data\n");
        write("src/main/sh/launcher.sh", """
                # Run the sample with the helpers under set -eu.
                set -eu
                echo "setup=$(jarsh_read sample/setup.txt)"
                if jarsh_read sample/missing.txt >/dev/null 2>&1; then
                    echo "missing=read"
                else
                    echo "missing=failed"
                fi
                jarsh_find_java
                "$JARSH_JAVA" -cp "$JARSH_BINARY" sample.Main "$@"
                """);
        write("build.gradle", APPLICATION + javaVersion(JAVA) + """

                jarsh {
                    launcher = file('src/main/sh/launcher.sh')
                }
                """);
        jarsh();
        File oldHome = temp.newFolder("old-home-without-release");
        script(new File(oldHome, "bin/java"), OLD_JAVA);
        link(new File(home, ".sdkman/candidates/java/test"), javaHome());
        Map<String, String> environment = environment();
        environment.put("JAVA_HOME", oldHome.getPath());
        int shells = 0;
        for (String shell : Arrays.asList("/bin/sh", "/bin/dash", "/bin/bash",
                "/bin/ksh")) {
            if(new File(shell).canExecute()) {
                Run run = run(new File(shell), temp.newFolder(),
                        environment, binary("sample.sh").getAbsolutePath(),
                        "exit", "5");
                assertEquals(shell + ": " + run.stderr(), "", run.stderr());
                assertEquals(shell, 5, run.status());
                assertTrue(shell + ": " + run.stdout(), run.stdout()
                        .startsWith("setup=setup data\nmissing=failed\njava="));
                assertTrue(shell + ": " + run.stdout(),
                        run.stdout().endsWith("arg=<exit>\narg=<5>\n"));
                ++shells;
            }
        }
        assertTrue("no shell ran the binary", shells > 0);
    }

    /**
     * <strong>Goal:</strong> Verify that a launcher without a {@code #!} line
     * runs with {@code /bin/sh}.
     * <p>
     * <strong>Start state:</strong> A sample project whose launcher starts with
     * a comment and has no {@code #!} line.
     * <p>
     * <strong>Workflow:</strong>
     * <ul>
     * <li>Run the {@code jarsh} task.</li>
     * <li>Read the start of the binary.</li>
     * <li>Run the binary with {@code x}.</li>
     * </ul>
     * <p>
     * <strong>Expected:</strong> The binary starts with {@code #!/bin/sh}, then
     * the launcher's comment and a blank line; it exits {@code 0} and the
     * application prints {@code x}.
     */
    @Test
    public void testLauncherWithoutInterpreterRunsWithSh() throws Exception {
        write("src/main/sh/launcher.sh", """
                # Run the sample.
                jarsh_exec_java -cp "$JARSH_BINARY" sample.Main "$@"
                """);
        write("build.gradle", APPLICATION + """

                jarsh {
                    launcher = file('src/main/sh/launcher.sh')
                }
                """);
        jarsh();
        File binary = binary("sample.sh");
        String start = new String(Files.readAllBytes(binary.toPath()), 0, 40,
                StandardCharsets.UTF_8);
        assertTrue(start,
                start.startsWith("#!/bin/sh\n# Run the sample.\n\n"));
        Run run = run(binary, temp.newFolder("cwd"), environment(), "x");
        assertEquals(run.stderr(), 0, run.status());
        assertTrue(run.stdout(), run.stdout().endsWith("arg=<x>\n"));
    }

    /**
     * <strong>Goal:</strong> Verify that without the {@code application}
     * plugin, the {@code jarsh} extension names the main class and the binary's
     * file name, and that the plugin applies the {@code java} plugin itself.
     * <p>
     * <strong>Start state:</strong> A sample project that applies only
     * {@code com.cinchapi.jarsh} and sets {@code mainClass} and {@code name} in
     * the {@code jarsh} extension.
     * <p>
     * <strong>Workflow:</strong>
     * <ul>
     * <li>Run the {@code jarsh} task.</li>
     * <li>Run {@code build/libs/tool} with {@code x}.</li>
     * </ul>
     * <p>
     * <strong>Expected:</strong> The binary exists under the configured name,
     * exits {@code 0}, and the application prints {@code x}.
     */
    @Test
    public void testExtensionNamesMainClassAndBinary() throws Exception {
        write("build.gradle", """
                plugins {
                    id 'com.cinchapi.jarsh'
                }

                jarsh {
                    mainClass = 'sample.Main'
                    name = 'tool'
                }
                """);
        jarsh();
        Run run = run(binary("tool"), temp.newFolder("cwd"), environment(),
                "x");
        assertEquals(run.stderr(), 0, run.status());
        assertTrue(run.stdout(), run.stdout().endsWith("arg=<x>\n"));
    }

    /**
     * <strong>Goal:</strong> Verify that the {@code jarsh} task fails, and says
     * how to name the main class, when the project names none and has no
     * launcher of its own.
     * <p>
     * <strong>Start state:</strong> A sample project that applies only
     * {@code com.cinchapi.jarsh} and sets nothing.
     * <p>
     * <strong>Workflow:</strong>
     * <ul>
     * <li>Run the {@code jarsh} task.</li>
     * </ul>
     * <p>
     * <strong>Expected:</strong> The build fails, and its output names both
     * {@code application.mainClass} and {@code jarsh.mainClass}.
     */
    @Test
    public void testJarshFailsWithoutMainClass() throws Exception {
        write("build.gradle", """
                plugins {
                    id 'com.cinchapi.jarsh'
                }
                """);
        BuildResult result = runner().buildAndFail();
        assertTrue(result.getOutput(),
                result.getOutput().contains("application.mainClass"));
        assertTrue(result.getOutput(),
                result.getOutput().contains("jarsh.mainClass"));
    }

    /**
     * <strong>Goal:</strong> Verify that the {@code jarsh} task rejects a
     * minimum Java version that is not positive.
     * <p>
     * <strong>Start state:</strong> A sample project with
     * {@code javaVersion = 0}.
     * <p>
     * <strong>Workflow:</strong>
     * <ul>
     * <li>Run the {@code jarsh} task.</li>
     * </ul>
     * <p>
     * <strong>Expected:</strong> The build fails, and its output names
     * {@code jarsh.javaVersion}.
     */
    @Test
    public void testJarshFailsForNonPositiveJavaVersion() throws Exception {
        write("build.gradle", APPLICATION + javaVersion(0));
        BuildResult result = runner().buildAndFail();
        assertTrue(result.getOutput(),
                result.getOutput().contains("jarsh.javaVersion must be"));
    }

    /**
     * <strong>Goal:</strong> Verify that the binary changes when a file it
     * carries changes, even when the build's runtime classpath normalization
     * ignores that file.
     * <p>
     * <strong>Start state:</strong> A sample project that bundles
     * {@code build-info.properties} with {@code version=1} and tells Gradle's
     * runtime classpath normalization to ignore that file.
     * <p>
     * <strong>Workflow:</strong>
     * <ul>
     * <li>Run the {@code jarsh} task.</li>
     * <li>Change the file to {@code version=2}.</li>
     * <li>Run the {@code jarsh} task again.</li>
     * <li>Read the file from the binary.</li>
     * </ul>
     * <p>
     * <strong>Expected:</strong> The binary holds {@code version=2}.
     */
    @Test
    public void testJarshRebuildsWhenNormalizedFileChanges() throws Exception {
        write("src/main/resources/build-info.properties", "version=1\n");
        write("build.gradle", APPLICATION + """

                normalization {
                    runtimeClasspath {
                        ignore 'build-info.properties'
                    }
                }
                """);
        jarsh();
        write("src/main/resources/build-info.properties", "version=2\n");
        jarsh();
        try (ZipFile zip = new ZipFile(binary("sample.sh"));
                InputStream in = zip.getInputStream(
                        zip.getEntry("build-info.properties"))) {
            assertEquals("version=2\n",
                    new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    /**
     * <strong>Goal:</strong> Verify that the plugin works with Gradle's
     * configuration cache and that the {@code jarsh} task is up to date when
     * nothing changed.
     * <p>
     * <strong>Start state:</strong> A sample project that names its main class
     * with the {@code application} plugin.
     * <p>
     * <strong>Workflow:</strong>
     * <ul>
     * <li>Run the {@code jarsh} task with the configuration cache.</li>
     * <li>Run it again.</li>
     * </ul>
     * <p>
     * <strong>Expected:</strong> The second build reuses the configuration
     * cache entry and reports the {@code jarsh} task up to date.
     */
    @Test
    public void testJarshReusesConfigurationCacheAndIsUpToDate()
            throws Exception {
        write("build.gradle", APPLICATION);
        jarsh();
        BuildResult second = jarsh();
        assertTrue(second.getOutput(), second.getOutput()
                .contains("Configuration cache entry reused"));
        assertEquals(TaskOutcome.UP_TO_DATE,
                second.task(":jarsh").getOutcome());
    }

    /**
     * Return a {@link GradleRunner} for the sample project that runs the
     * {@code jarsh} task with the configuration cache on and fails on any
     * deprecation warning.
     *
     * @return the {@link GradleRunner}
     */
    private GradleRunner runner() {
        return GradleRunner.create().withProjectDir(project)
                .withPluginClasspath().withArguments("jarsh",
                        "--configuration-cache", "--warning-mode=fail",
                        "--stacktrace");
    }

    /**
     * Run the {@code jarsh} task, which must succeed.
     *
     * @return the {@link BuildResult}
     */
    private BuildResult jarsh() {
        return runner().build();
    }

    /**
     * Return the sample's binary named {@code name}.
     *
     * @param name the binary's file name
     * @return the binary under {@code build/libs}
     */
    private File binary(String name) {
        return new File(project, "build/libs/" + name);
    }

    /**
     * Return a {@code jarsh} block that sets the minimum Java version.
     *
     * @param version the minimum Java version
     * @return the block, as build script text
     */
    private static String javaVersion(int version) {
        return "\njarsh {\n    javaVersion = " + version + "\n}\n";
    }

    /**
     * Return the lines the sample prints before its arguments when it runs on
     * the test JDK in {@code cwd} with no {@code sample.prop}.
     *
     * @param cwd the working directory
     * @return the lines, each ending in a newline
     * @throws IOException if {@code cwd} cannot be resolved
     */
    private static String header(File cwd) throws IOException {
        return "java=" + JAVA + "\ncwd=" + cwd.toPath().toRealPath()
                + "\nprop=null\n";
    }

    /**
     * Return the home folder of the JDK that runs the tests.
     *
     * @return the JDK's home
     */
    private static Path javaHome() {
        return Paths.get(System.getProperty("java.home"));
    }

    /**
     * Return the environment for a binary: the fake home, the system's
     * {@code PATH} folders, and {@code JAVA_HOME} set to the test JDK.
     *
     * @return a mutable environment
     */
    private Map<String, String> environment() {
        Map<String, String> environment = new HashMap<>();
        environment.put("HOME", home.getPath());
        environment.put("PATH", "/usr/bin:/bin");
        environment.put("JAVA_HOME", javaHome().toString());
        return environment;
    }

    /**
     * Return an environment whose {@code JAVA_HOME} is a fake Java 8 home with
     * a {@code release} file and whose {@code PATH} is a folder that holds only
     * a fake Java 8 {@code java}.
     *
     * @return a mutable environment
     * @throws IOException if a fake cannot be written
     */
    private Map<String, String> oldJavaEnvironment() throws IOException {
        File oldHome = temp.newFolder("old-home");
        script(new File(oldHome, "bin/java"), OLD_JAVA);
        Files.write(new File(oldHome, "release").toPath(),
                "IMPLEMENTOR=\"Fake\"\nJAVA_VERSION=\"1.8.0_452\"\n"
                        .getBytes(StandardCharsets.UTF_8));
        File oldPath = temp.newFolder("old-path");
        script(new File(oldPath, "java"), OLD_JAVA);
        Map<String, String> environment = environment();
        environment.put("JAVA_HOME", oldHome.getPath());
        environment.put("PATH", oldPath.getPath());
        return environment;
    }

    /**
     * Write a fake Java 99 into the folder {@code home}: a {@code release} file
     * that reports version 99 and a {@code bin/java} that prints its own path
     * followed by {@code ran}.
     *
     * @param home the fake Java's home, which must not exist yet
     * @return {@code home}
     * @throws IOException if a file cannot be written
     */
    private static File newJava(File home) throws IOException {
        script(new File(home, "bin/java"), "#!/bin/sh\necho \"$0 ran\"\n");
        Files.write(new File(home, "release").toPath(),
                "JAVA_VERSION=\"99\"\n".getBytes(StandardCharsets.UTF_8));
        return home;
    }

    /**
     * Run the {@code binary}, or a shell given the binary's path as its first
     * argument, by its absolute path and wait for it to exit.
     *
     * @param binary the program to run
     * @param cwd the working directory
     * @param environment the whole environment of the binary
     * @param args the arguments
     * @return the {@link Run}
     * @throws IOException if the binary cannot start
     * @throws InterruptedException if the test is interrupted while the binary
     *             runs
     */
    private Run run(File binary, File cwd, Map<String, String> environment,
            String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>();
        command.add(binary.getAbsolutePath());
        command.addAll(Arrays.asList(args));
        File stdout = File.createTempFile("run", ".out", temp.getRoot());
        File stderr = File.createTempFile("run", ".err", temp.getRoot());
        ProcessBuilder builder = new ProcessBuilder(command).directory(cwd)
                .redirectOutput(stdout).redirectError(stderr);
        builder.environment().clear();
        builder.environment().putAll(environment);
        Process process = builder.start();
        assertTrue("the binary did not exit",
                process.waitFor(2, TimeUnit.MINUTES));
        return new Run(process.exitValue(), read(stdout), read(stderr));
    }

    /**
     * Write a jar with the class {@code signed.Greeter}, whose static
     * {@code greet()} returns {@code hello from a signed jar}, to {@code jar},
     * and sign it with a new key.
     *
     * @param jar the jar to write
     * @throws Exception if the class cannot be compiled or the jar cannot be
     *             written or signed
     */
    private void signedJar(File jar) throws Exception {
        File source = temp.newFile("Greeter.java");
        Files.write(source.toPath(), """
                package signed;

                public class Greeter {
                    public static String greet() {
                        return "hello from a signed jar";
                    }
                }
                """.getBytes(StandardCharsets.UTF_8));
        File classes = temp.newFolder("greeter-classes");
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null,
                null, "-d", classes.getPath(), source.getPath()));
        Files.createDirectories(jar.toPath().getParent());
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION,
                "1.0");
        try (JarOutputStream out = new JarOutputStream(
                new FileOutputStream(jar), manifest)) {
            out.putNextEntry(new JarEntry("signed/Greeter.class"));
            out.write(Files.readAllBytes(
                    new File(classes, "signed/Greeter.class").toPath()));
            out.closeEntry();
        }
        File keystore = new File(temp.getRoot(), "keystore.p12");
        exec(tool("keytool"), "-genkeypair", "-keystore", keystore.getPath(),
                "-storetype", "PKCS12", "-storepass", "jarshtest", "-keypass",
                "jarshtest", "-alias", "signer", "-keyalg", "RSA", "-keysize",
                "2048", "-dname", "CN=jarsh test", "-validity", "2");
        exec(tool("jarsigner"), "-keystore", keystore.getPath(), "-storepass",
                "jarshtest", jar.getPath(), "signer");
        try (ZipFile zip = new ZipFile(jar)) {
            assertNotNull("jarsigner did not sign the jar",
                    zip.getEntry("META-INF/SIGNER.SF"));
        }
    }

    /**
     * Return the path of the test JDK's tool named {@code name}.
     *
     * @param name the tool's name, such as {@code keytool}
     * @return the tool's path
     */
    private static String tool(String name) {
        return javaHome().resolve("bin").resolve(name).toString();
    }

    /**
     * Run the {@code command}, which must exit {@code 0}.
     *
     * @param command the program and its arguments
     * @throws IOException if the program cannot start
     * @throws InterruptedException if the test is interrupted while the program
     *             runs
     */
    private void exec(String... command)
            throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true)
                .start();
        String output;
        try (InputStream in = process.getInputStream()) {
            output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals(output, 0, process.waitFor());
    }

    /**
     * Create the folder {@code link} sits in and make {@code link} a symbolic
     * link to {@code target}.
     *
     * @param link the link to create
     * @param target the path the link points to
     * @throws IOException if the link cannot be created
     */
    private static void link(File link, Path target) throws IOException {
        Files.createDirectories(link.toPath().getParent());
        Files.createSymbolicLink(link.toPath(), target);
    }

    /**
     * Write an executable script with the {@code content} to {@code file},
     * creating its folder.
     *
     * @param file the script
     * @param content the script's text
     * @throws IOException if the script cannot be written
     */
    private static void script(File file, String content) throws IOException {
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
        assertTrue(file.setExecutable(true));
    }

    /**
     * Write the {@code content} to the file at {@code path} in the sample
     * project, creating its folders.
     *
     * @param path the file's path relative to the project
     * @param content the file's text
     * @throws IOException if the file cannot be written
     */
    private void write(String path, String content) throws IOException {
        File file = new File(project, path);
        file.getParentFile().mkdirs();
        Files.write(file.toPath(), content.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Return the content of the {@code file} as UTF-8 text.
     *
     * @param file the file
     * @return its content
     * @throws IOException if the file cannot be read
     */
    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()),
                StandardCharsets.UTF_8);
    }

    /**
     * The outcome of running a binary.
     *
     * @param status the exit status
     * @param stdout what the binary printed to stdout
     * @param stderr what the binary printed to stderr
     */
    private record Run(int status, String stdout, String stderr) {}

}
