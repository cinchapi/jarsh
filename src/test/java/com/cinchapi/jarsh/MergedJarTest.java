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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarInputStream;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import javax.annotation.Nullable;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * Unit tests for {@link MergedJar}.
 *
 * @author Jeff Nelson
 */
public class MergedJarTest {

    /**
     * The folder that holds the input jars of each test.
     */
    @Rule
    public final TemporaryFolder temp = new TemporaryFolder();

    /**
     * <strong>Goal:</strong> Verify that when two classpath entries hold a file
     * with the same name, the merged jar keeps the one from the earlier entry,
     * as a class loader would.
     * <p>
     * <strong>Start state:</strong> Two jars that both hold {@code dup.txt} and
     * {@code a/B.class}, with different contents.
     * <p>
     * <strong>Workflow:</strong>
     * <ul>
     * <li>Merge the jars, first then second.</li>
     * <li>Read {@code dup.txt} and {@code a/B.class} from the merged
     * jar.</li>
     * </ul>
     * <p>
     * <strong>Expected:</strong> Both files hold the first jar's content, and
     * each name appears once.
     */
    @Test
    public void testWriteKeepsFirstEntryWhenNamesRepeat() throws IOException {
        File first = jar("first.jar", null, entries("dup.txt", "first",
                "a/B.class", "first class"));
        File second = jar("second.jar", null, entries("dup.txt", "second",
                "a/B.class", "second class"));
        Map<String, String> merged = read(merge(null, first, second));
        assertEquals("first", merged.get("dup.txt"));
        assertEquals("first class", merged.get("a/B.class"));
    }

    /**
     * <strong>Goal:</strong> Verify that the merged jar joins the service files
     * of every classpath entry, so a {@link java.util.ServiceLoader} finds
     * every provider it would find on the classpath.
     * <p>
     * <strong>Start state:</strong> Two jars that both hold
     * {@code META-INF/services/sample.Plugin}, the first without a final
     * newline.
     * <p>
     * <strong>Workflow:</strong>
     * <ul>
     * <li>Merge the jars, first then second.</li>
     * <li>Read {@code META-INF/services/sample.Plugin} from the merged
     * jar.</li>
     * </ul>
     * <p>
     * <strong>Expected:</strong> The file lists the first jar's providers, then
     * the second jar's, one per line.
     */
    @Test
    public void testWriteJoinsServiceFilesInClasspathOrder()
            throws IOException {
        File first = jar("first.jar", null,
                entries("META-INF/services/sample.Plugin", "sample.One"));
        File second = jar("second.jar", null,
                entries("META-INF/services/sample.Plugin",
                        "sample.Two\nsample.Three\n"));
        Map<String, String> merged = read(merge(null, first, second));
        assertEquals("sample.One\nsample.Two\nsample.Three\n",
                merged.get("META-INF/services/sample.Plugin"));
    }

    /**
     * <strong>Goal:</strong> Verify that the merged jar leaves out every jar
     * signature file and jar index, so the JVM does not reject the merged jar
     * against a signature that no longer matches.
     * <p>
     * <strong>Start state:</strong> A jar that holds signature files with each
     * extension the jar specification names, in upper and lower case, a
     * {@code META-INF/INDEX.LIST}, a license file, and a class.
     * <p>
     * <strong>Workflow:</strong>
     * <ul>
     * <li>Merge the jar.</li>
     * <li>List the merged jar's entries.</li>
     * </ul>
     * <p>
     * <strong>Expected:</strong> No signature file or index remains; the
     * license file and the class remain.
     */
    @Test
    public void testWriteOmitsSignatureFilesAndIndex() throws IOException {
        File signed = jar("signed.jar", null,
                entries("META-INF/SIGNER.SF", "sf", "META-INF/SIGNER.RSA",
                        "rsa", "META-INF/other.dsa", "dsa", "META-INF/KEY.EC",
                        "ec", "META-INF/SIG-PGP", "sig", "META-INF/INDEX.LIST",
                        "index", "META-INF/LICENSE", "license",
                        "signed/Greeter.class", "class"));
        Map<String, String> merged = read(merge(null, signed));
        for (String name : Arrays.asList("META-INF/SIGNER.SF",
                "META-INF/SIGNER.RSA", "META-INF/other.dsa",
                "META-INF/KEY.EC", "META-INF/SIG-PGP",
                "META-INF/INDEX.LIST")) {
            assertFalse(name, merged.containsKey(name));
        }
        assertEquals("license", merged.get("META-INF/LICENSE"));
        assertEquals("class", merged.get("signed/Greeter.class"));
    }

    /**
     * <strong>Goal:</strong> Verify that the merged manifest starts from the
     * first classpath entry's main attributes, names the given main class, and
     * drops the first entry's {@code Class-Path}.
     * <p>
     * <strong>Start state:</strong> A first jar whose manifest has an
     * {@code Implementation-Version}, a {@code Class-Path}, and a
     * {@code Main-Class}; a second jar whose manifest has its own
     * {@code Implementation-Version}.
     * <p>
     * <strong>Workflow:</strong>
     * <ul>
     * <li>Merge the jars with main class {@code sample.Main}.</li>
     * <li>Read the merged manifest with a {@link JarInputStream}, which sees
     * only a manifest at the start of the jar.</li>
     * </ul>
     * <p>
     * <strong>Expected:</strong> The manifest has the first jar's
     * {@code Implementation-Version}, {@code Main-Class} {@code sample.Main},
     * and no {@code Class-Path}.
     */
    @Test
    public void testWriteKeepsFirstManifestAndSetsMainClass()
            throws IOException {
        File first = jar("first.jar",
                manifest("Implementation-Version", "1.2.3", "Class-Path",
                        "lib/dep.jar", "Main-Class", "old.Main"),
                entries("a.txt", "a"));
        File second = jar("second.jar",
                manifest("Implementation-Version", "9.9.9"),
                entries("b.txt", "b"));
        Attributes attributes = manifestOf(merge("sample.Main", first, second))
                .getMainAttributes();
        assertEquals("1.2.3", attributes.getValue("Implementation-Version"));
        assertEquals("sample.Main", attributes.getValue("Main-Class"));
        assertNull(attributes.getValue("Class-Path"));
    }

    /**
     * <strong>Goal:</strong> Verify that without a main class, the merged
     * manifest keeps the first classpath entry's {@code Main-Class}.
     * <p>
     * <strong>Start state:</strong> A jar whose manifest names
     * {@code app.Main}.
     * <p>
     * <strong>Workflow:</strong>
     * <ul>
     * <li>Merge the jar with no main class.</li>
     * <li>Read the merged manifest.</li>
     * </ul>
     * <p>
     * <strong>Expected:</strong> The manifest's {@code Main-Class} is
     * {@code app.Main}.
     */
    @Test
    public void testWriteKeepsFirstMainClassWhenNoneIsGiven()
            throws IOException {
        File app = jar("app.jar", manifest("Main-Class", "app.Main"),
                entries("a.txt", "a"));
        assertEquals("app.Main", manifestOf(merge(null, app))
                .getMainAttributes().getValue("Main-Class"));
    }

    /**
     * <strong>Goal:</strong> Verify that the merged jar is a multi-release jar
     * when any classpath entry is one, so the JVM still loads that entry's
     * classes for the running Java version.
     * <p>
     * <strong>Start state:</strong> A plain first jar and a second jar whose
     * manifest has {@code Multi-Release: true} and which holds a class under
     * {@code META-INF/versions/9}.
     * <p>
     * <strong>Workflow:</strong>
     * <ul>
     * <li>Merge the jars.</li>
     * <li>Read the merged manifest and entries.</li>
     * </ul>
     * <p>
     * <strong>Expected:</strong> The manifest has {@code Multi-Release: true}
     * and the versioned class is present.
     */
    @Test
    public void testWriteMarksMultiReleaseWhenAnyJarIs() throws IOException {
        File first = jar("first.jar", null, entries("a.txt", "a"));
        File second = jar("second.jar", manifest("Multi-Release", "true"),
                entries("lib/V.class", "base",
                        "META-INF/versions/9/lib/V.class", "nine"));
        byte[] merged = merge(null, first, second);
        assertEquals("true", manifestOf(merged).getMainAttributes()
                .getValue("Multi-Release"));
        assertEquals("nine",
                read(merged).get("META-INF/versions/9/lib/V.class"));
    }

    /**
     * <strong>Goal:</strong> Verify that a folder on the classpath contributes
     * its files under their paths relative to the folder.
     * <p>
     * <strong>Start state:</strong> A folder that holds {@code top.txt} and
     * {@code nested/deep.txt}, followed by a jar that also holds
     * {@code top.txt}.
     * <p>
     * <strong>Workflow:</strong>
     * <ul>
     * <li>Merge the folder, then the jar.</li>
     * <li>Read the merged jar's entries.</li>
     * </ul>
     * <p>
     * <strong>Expected:</strong> {@code top.txt} holds the folder's content,
     * {@code nested/deep.txt} is present, and {@code nested/} is a folder
     * entry.
     */
    @Test
    public void testWriteIncludesFilesOfClasspathFolders() throws IOException {
        File folder = temp.newFolder("classes");
        Files.write(new File(folder, "top.txt").toPath(),
                "folder".getBytes(StandardCharsets.UTF_8));
        File nested = new File(folder, "nested");
        assertTrue(nested.mkdir());
        Files.write(new File(nested, "deep.txt").toPath(),
                "deep".getBytes(StandardCharsets.UTF_8));
        File jar = jar("after.jar", null, entries("top.txt", "jar"));
        Map<String, String> merged = read(merge(null, folder, jar));
        assertEquals("folder", merged.get("top.txt"));
        assertEquals("deep", merged.get("nested/deep.txt"));
        assertTrue(merged.containsKey("nested/"));
    }

    /**
     * Return an ordered map of entry names to contents from alternating
     * {@code namesAndContents}.
     *
     * @param namesAndContents an entry name, then its content, repeated
     * @return the entries, in the order given
     */
    private static Map<String, String> entries(String... namesAndContents) {
        Map<String, String> entries = new LinkedHashMap<>();
        for (int i = 0; i < namesAndContents.length; i += 2) {
            entries.put(namesAndContents[i], namesAndContents[i + 1]);
        }
        return entries;
    }

    /**
     * Return a {@link Manifest} whose main attributes are the alternating
     * {@code namesAndValues}.
     *
     * @param namesAndValues an attribute name, then its value, repeated
     * @return the {@link Manifest}
     */
    private static Manifest manifest(String... namesAndValues) {
        Manifest manifest = new Manifest();
        Attributes attributes = manifest.getMainAttributes();
        attributes.put(Attributes.Name.MANIFEST_VERSION, "1.0");
        for (int i = 0; i < namesAndValues.length; i += 2) {
            attributes.putValue(namesAndValues[i], namesAndValues[i + 1]);
        }
        return manifest;
    }

    /**
     * Write a jar named {@code name} in the temporary folder.
     *
     * @param name the jar's file name
     * @param manifest the jar's {@link Manifest}, or {@code null} for a jar
     *            without one
     * @param entries the jar's entry names and contents, first entry first
     * @return the jar
     * @throws IOException if the jar cannot be written
     */
    private File jar(String name, @Nullable Manifest manifest,
            Map<String, String> entries) throws IOException {
        File file = new File(temp.getRoot(), name);
        try (OutputStream out = new FileOutputStream(file);
                JarOutputStream jar = manifest == null
                        ? new JarOutputStream(out)
                        : new JarOutputStream(out, manifest)) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                jar.putNextEntry(new JarEntry(entry.getKey()));
                jar.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
        return file;
    }

    /**
     * Merge the {@code classpath} with {@link MergedJar#write}.
     *
     * @param mainClass the main class, or {@code null} for none
     * @param classpath the classpath entries, first entry first
     * @return the bytes of the merged jar
     * @throws IOException if a classpath entry cannot be read
     */
    private static byte[] merge(@Nullable String mainClass, File... classpath)
            throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        MergedJar.write(Arrays.asList(classpath), mainClass, out);
        return out.toByteArray();
    }

    /**
     * Read the manifest of the {@code jar} as a {@link JarInputStream} does,
     * which finds it only when it is among the first entries.
     *
     * @param jar the bytes of a jar
     * @return the jar's {@link Manifest}, never {@code null}
     * @throws IOException if the jar cannot be read
     */
    private static Manifest manifestOf(byte[] jar) throws IOException {
        try (JarInputStream in = new JarInputStream(
                new ByteArrayInputStream(jar))) {
            Manifest manifest = in.getManifest();
            assertNotNull("the manifest is not at the start of the jar",
                    manifest);
            return manifest;
        }
    }

    /**
     * Read every entry of the {@code jar} except its manifest.
     *
     * @param jar the bytes of a jar
     * @return the entry names and their contents as UTF-8 text, in the jar's
     *         order
     * @throws IOException if the jar cannot be read
     */
    private static Map<String, String> read(byte[] jar) throws IOException {
        Map<String, String> entries = new LinkedHashMap<>();
        try (JarInputStream in = new JarInputStream(
                new ByteArrayInputStream(jar))) {
            JarEntry entry;
            while ((entry = in.getNextJarEntry()) != null) {
                assertFalse(entry.getName() + " appears twice",
                        entries.containsKey(entry.getName()));
                entries.put(entry.getName(),
                        new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return entries;
    }

}
