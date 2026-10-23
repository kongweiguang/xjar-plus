package verification;

import io.xjar.XJdkDecryptor;
import io.xjar.XJdkEncryptor;
import io.xjar.XKit;
import io.xjar.boot.XBootClassLoader;
import io.xjar.key.XKey;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.springframework.boot.loader.net.protocol.Handlers;

import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Enumeration;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.Assert.*;

/** Handwritten MR archives isolate actual-entry decryption from archive injection and Chinese class-directory resource scanning. */
public class BootMultiReleaseClassLoaderTest {
    private static final String VERSION_PREFIX = "META-INF/versions/9/";
    private static String originalProtocolPackages;
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    /** Direct loader tests must reproduce Boot's launcher registration before JarUrlConnection initializes its nested-protocol sentinel. */
    @BeforeClass public static void registerBootProtocols() {
        originalProtocolPackages = System.getProperty("java.protocol.handler.pkgs");
        Handlers.register();
    }

    /** Restore the suite's previous protocol configuration and reset ordinary URL caches without replacing an external factory. */
    @AfterClass public static void restoreBootProtocols() {
        if (originalProtocolPackages == null) System.clearProperty("java.protocol.handler.pkgs");
        else System.setProperty("java.protocol.handler.pkgs", originalProtocolPackages);
        try {
            URL.setURLStreamHandlerFactory(null);
        } catch (Error externalFactoryAlreadyInstalled) {
            // Match Boot's reset behavior: a caller-owned factory remains authoritative and must not be replaced reflectively.
        }
    }

    /** A class existing only in the selected version must not be rejected just because the logical base path is absent from the index. */
    @Test public void versionOnlyEncryptedClassIsDefinedFromTheSelectedEntry() throws Exception {
        String name = "mr.probe.VersionOnly";
        String logical = classEntry(name);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(VERSION_PREFIX + logical, compileVariant(name, "version-only"));
        try (Fixture fixture = fixture(entries, Set.of(VERSION_PREFIX + logical))) {
            assertLoadedValue(fixture, name, "version-only");
        }
    }

    /** An unencrypted base entry must not hide the selected encrypted implementation behind a logical-name fast path. */
    @Test public void plaintextBaseAndEncryptedVersionLoadsTheVersion() throws Exception {
        String name = "mr.probe.PlainBase";
        String logical = classEntry(name);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(logical, compileVariant(name, "base-must-not-load"));
        entries.put(VERSION_PREFIX + logical, compileVariant(name, "encrypted-version"));
        try (Fixture fixture = fixture(entries, Set.of(VERSION_PREFIX + logical))) {
            assertLoadedValue(fixture, name, "encrypted-version");
        }
    }

    /** Encrypting the base entry must not cause a selected plaintext override to be decrypted a second time. */
    @Test public void encryptedBaseAndPlaintextVersionDoesNotDecryptTheVersion() throws Exception {
        String name = "mr.probe.EncryptedBase";
        String logical = classEntry(name);
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(logical, compileVariant(name, "encrypted-base-must-not-load"));
        entries.put(VERSION_PREFIX + logical, compileVariant(name, "plaintext-version"));
        try (Fixture fixture = fixture(entries, Set.of(logical))) {
            assertLoadedValue(fixture, name, "plaintext-version");
        }
    }

    /** Resource APIs must share MR selection with class loading while deciding encryption from the selected physical entry. */
    @Test public void versionedResourcesAreSelectedAndDecryptedThroughSingleAndEnumeratedLookups() throws Exception {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(VERSION_PREFIX + "assets/only-version.txt", text("only-version-resource"));
        entries.put("assets/plain-base.txt", text("plain-base-must-not-read"));
        entries.put(VERSION_PREFIX + "assets/plain-base.txt", text("encrypted-version-resource"));
        entries.put("assets/encrypted-base.txt", text("encrypted-base-must-not-read"));
        entries.put(VERSION_PREFIX + "assets/encrypted-base.txt", text("plaintext-version-resource"));
        entries.put("assets/中文 资源.txt", text("unicode-base-must-not-read"));
        entries.put(VERSION_PREFIX + "assets/中文 资源.txt", text("unicode-encrypted-version"));
        entries.put("assets/另一 中文.txt", text("unicode-encrypted-base-must-not-read"));
        entries.put(VERSION_PREFIX + "assets/另一 中文.txt", text("unicode-plaintext-version"));
        Set<String> encrypted = Set.of(VERSION_PREFIX + "assets/only-version.txt",
                VERSION_PREFIX + "assets/plain-base.txt", "assets/encrypted-base.txt",
                VERSION_PREFIX + "assets/中文 资源.txt", "assets/另一 中文.txt");
        Map<String, byte[]> expected = new LinkedHashMap<>();
        expected.put("assets/only-version.txt", text("only-version-resource"));
        expected.put("assets/plain-base.txt", text("encrypted-version-resource"));
        expected.put("assets/encrypted-base.txt", text("plaintext-version-resource"));
        expected.put("assets/中文 资源.txt", text("unicode-encrypted-version"));
        expected.put("assets/另一 中文.txt", text("unicode-plaintext-version"));
        try (Fixture fixture = fixture(entries, encrypted)) {
            for (var resource : expected.entrySet()) {
                String name = resource.getKey();
                assertArrayEquals("getResource " + name, resource.getValue(), fixture.read(fixture.loader.getResource(name)));
                assertArrayEquals("findResource " + name, resource.getValue(), fixture.read(fixture.loader.findResource(name)));
                assertEnumeration(fixture, fixture.loader.getResources(name), resource.getValue(), "getResources " + name);
                assertEnumeration(fixture, fixture.loader.findResources(name), resource.getValue(), "findResources " + name);
            }
        }
    }

    /** The same selected physical name in another archive must not inherit the first archive's encryption decision. */
    @Test public void sameNameResourcesInDifferentArchivesRetainTheirOwnEncryptionRules() throws Exception {
        String name = "assets/共享 资源.txt";
        Map<String, byte[]> firstEntries = new LinkedHashMap<>();
        firstEntries.put(name, text("first-base-must-not-read"));
        firstEntries.put(VERSION_PREFIX + name, text("first-encrypted-version"));
        Map<String, byte[]> secondEntries = new LinkedHashMap<>();
        secondEntries.put(name, text("second-base-must-not-read"));
        secondEntries.put(VERSION_PREFIX + name, text("second-plaintext-version"));
        XKey key = XKit.key("mr-jar-unit-test-only");
        try (Fixture first = fixture(firstEntries, Set.of(VERSION_PREFIX + name));
             Fixture second = fixture(secondEntries, Set.of())) {
            URL[] roots = {first.loader.getURLs()[0], second.loader.getURLs()[0]};
            XBootClassLoader loader = new XBootClassLoader(roots, ClassLoader.getPlatformClassLoader(),
                    new XJdkDecryptor(), new XJdkEncryptor(), key);
            try (Fixture combined = new Fixture(loader, first.archive, second.archive)) {
                assertArrayEquals(text("first-encrypted-version"), combined.read(loader.getResource(name)));
                List<byte[]> expected = List.of(text("first-encrypted-version"), text("second-plaintext-version"));
                Enumeration<URL> results = loader.findResources(name);
                int count = 0;
                while (results.hasMoreElements()) {
                    assertTrue("Unexpected additional same-name resource", count < expected.size());
                    assertArrayEquals("Archive ownership at result " + count, expected.get(count), combined.read(results.nextElement()));
                    count++;
                }
                assertEquals("Both archives must remain visible", expected.size(), count);
            }
        }
    }

    /** Resolving selected entries must preserve ordinary file URLs instead of routing exploded classes or resources through a JAR handler. */
    @Test public void explodedDirectoryKeepsPlaintextClassesAndFileResourcesReadable() throws Exception {
        String name = "mr.probe.Exploded";
        Path directory = temp.newFolder("exploded-classes").toPath();
        Path classFile = directory.resolve(classEntry(name));
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, compileVariant(name, "exploded-directory"));
        String resourceName = "assets/普通 资源.txt";
        byte[] expected = text("exploded-file-resource");
        Path resourceFile = directory.resolve(resourceName);
        Files.createDirectories(resourceFile.getParent());
        Files.write(resourceFile, expected);
        XKey key = XKit.key("mr-jar-unit-test-only");
        try (XBootClassLoader loader = new XBootClassLoader(new URL[]{directory.toUri().toURL()},
                ClassLoader.getPlatformClassLoader(), new XJdkDecryptor(), new XJdkEncryptor(), key)) {
            Class<?> loaded = loader.loadClass(name);
            assertSame(loader, loaded.getClassLoader());
            assertEquals("exploded-directory", loaded.getMethod("value").invoke(null));
            assertArrayEquals(expected, readFileResource(loader.getResource(resourceName)));
            assertArrayEquals(expected, readFileResource(loader.findResource(resourceName)));
            for (Enumeration<URL> enumeration : List.of(loader.getResources(resourceName), loader.findResources(resourceName))) {
                int count = 0;
                while (enumeration.hasMoreElements()) {
                    assertArrayEquals(expected, readFileResource(enumeration.nextElement()));
                    count++;
                }
                assertEquals("The sole directory must yield one file resource", 1, count);
            }
        }
    }

    /** File resources must retain their native connection lifecycle, including caller cache and timeout configuration before reads. */
    private static byte[] readFileResource(URL resource) throws IOException {
        assertNotNull("Exploded file resource is missing", resource);
        assertEquals("file", resource.getProtocol());
        var connection = resource.openConnection();
        assertFalse("A file URL must not be converted into a JAR connection", connection instanceof JarURLConnection);
        connection.setUseCaches(false);
        connection.setReadTimeout(1000);
        try (InputStream input = connection.getInputStream()) { return input.readAllBytes(); }
    }

    /** The returned constant distinguishes the selected implementation, and the defining loader proves parent delegation did not bypass the test. */
    private static void assertLoadedValue(Fixture fixture, String name, String expected) throws Exception {
        Class<?> loaded = fixture.loader.loadClass(name);
        assertSame("The application loader must define the MR class", fixture.loader, loaded.getClassLoader());
        assertEquals(expected, loaded.getMethod("value").invoke(null));
        fixture.read(fixture.loader.getResource(classEntry(name)));
    }

    /** Assert one result from the sole archive so enumeration cannot silently duplicate a base entry or skip its versioned override. */
    private static void assertEnumeration(Fixture fixture, Enumeration<URL> resources, byte[] expected, String label) throws IOException {
        int count = 0;
        while (resources.hasMoreElements()) {
            assertArrayEquals(label, expected, fixture.read(resources.nextElement()));
            count++;
        }
        assertEquals(label + " result count", 1, count);
    }

    /** Compile isolated release-9 classes during the test so their same-name variants have valid bytecode without depending on checked-in binary artifacts. */
    private byte[] compileVariant(String name, String value) throws IOException {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull("Tests require the JDK compiler used to build this Java-21 project", compiler);
        Path directory = Files.createTempDirectory(temp.getRoot().toPath(), "compiled-");
        String className = name.substring(name.lastIndexOf('.') + 1);
        String packageName = name.substring(0, name.lastIndexOf('.'));
        Path source = directory.resolve(className + ".java");
        Files.writeString(source, "package " + packageName + ";\npublic class " + className + " {\n"
                + "    /** Distinct constants reveal the MR implementation actually selected by the JVM. */\n"
                + "    public static String value() { return \"" + value + "\"; }\n}\n", StandardCharsets.UTF_8);
        ByteArrayOutputStream diagnostics = new ByteArrayOutputStream();
        int exit = compiler.run(null, diagnostics, diagnostics, "--release", "9", "-encoding", "UTF-8", "-d", directory.toString(), source.toString());
        assertEquals("Fixture compiler output: " + diagnostics.toString(StandardCharsets.UTF_8), 0, exit);
        return Files.readAllBytes(directory.resolve(classEntry(name)));
    }

    /** Write only the declared physical entries and plaintext index, avoiding XInjector and its unrelated class-directory discovery path. */
    private Fixture fixture(Map<String, byte[]> entries, Set<String> encrypted) throws Exception {
        XKey key = XKit.key("mr-jar-unit-test-only");
        Path archive = temp.newFile("multi-release-" + System.nanoTime() + ".jar").toPath();
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Multi-Release", "true");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(archive), manifest)) {
            for (var entry : entries.entrySet()) {
                byte[] data = entry.getValue();
                if (encrypted.contains(entry.getKey())) {
                    ByteArrayOutputStream ciphertext = new ByteArrayOutputStream();
                    new XJdkEncryptor().encrypt(key, new java.io.ByteArrayInputStream(data), ciphertext);
                    data = ciphertext.toByteArray();
                }
                writeEntry(output, entry.getKey(), data);
            }
            writeEntry(output, "XJAR-INF/INDEXES.IDX", text(String.join("\n", encrypted) + "\n"));
        }
        URL root = new URL("jar:" + archive.toUri().toURL() + "!/");
        XBootClassLoader loader = new XBootClassLoader(new URL[]{root}, ClassLoader.getPlatformClassLoader(),
                new XJdkDecryptor(), new XJdkEncryptor(), key);
        return new Fixture(loader, archive);
    }

    /** Centralize stream ownership so handcrafted archives are valid ZIPs even when entries are intentionally encrypted or version-only. */
    private static void writeEntry(JarOutputStream output, String name, byte[] bytes) throws IOException {
        output.putNextEntry(new JarEntry(name));
        output.write(bytes);
        output.closeEntry();
    }

    /** Keep logical-to-physical naming explicit because that distinction is the behavior under regression. */
    private static String classEntry(String name) {
        return name.replace('.', '/') + ".class";
    }

    /** Use fixed encoding for resource values and index entries so platform defaults cannot alter the byte comparisons. */
    private static byte[] text(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static final class Fixture implements AutoCloseable {
        private final XBootClassLoader loader;
        private final Path archive;
        private final Set<JarFile> opened = Collections.newSetFromMap(new IdentityHashMap<>());

        /** Track the JDK index connection as well as Boot connections because each protocol may cache its own Windows file handle. */
        private Fixture(XBootClassLoader loader, Path... archives) throws IOException {
            this.loader = loader;
            this.archive = archives[0];
            for (Path source : archives) {
                URL index = new URL("jar:" + source.toUri().toURL() + "!/XJAR-INF/INDEXES.IDX");
                opened.add(((JarURLConnection) index.openConnection()).getJarFile());
            }
        }

        /** Keep connections configurable until the caller sets cache/timeouts, then compare selected metadata and bytes before closing their stream. */
        private byte[] read(URL resource) throws IOException {
            assertNotNull("Selected MR resource is missing", resource);
            var connection = resource.openConnection();
            connection.setUseCaches(false);
            connection.setReadTimeout(1000);
            assertTrue("Resources must keep JAR URL semantics", connection instanceof JarURLConnection);
            opened.add(((JarURLConnection) connection).getJarFile());
            long length = connection.getContentLengthLong();
            long entrySize = ((JarURLConnection) connection).getJarEntry().getSize();
            try (InputStream input = connection.getInputStream()) {
                byte[] bytes = input.readAllBytes();
                assertEquals("Selected resource content length", bytes.length, length);
                assertEquals("Selected resource entry size", bytes.length, entrySize);
                return bytes;
            }
        }

        /** Close both loader and protocol caches so temporary archives can be deleted on Windows after success or assertion failure. */
        @Override public void close() throws IOException {
            IOException failure = null;
            try { loader.close(); }
            catch (IOException closeFailure) { failure = closeFailure; }
            for (JarFile jar : opened) {
                try { jar.close(); }
                catch (IOException closeFailure) {
                    if (failure == null) failure = closeFailure;
                    else failure.addSuppressed(closeFailure);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
