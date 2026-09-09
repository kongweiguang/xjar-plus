package verification;

import io.xjar.XCryptos;
import io.xjar.XFilters;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.jar.*;
import java.util.zip.CRC32;

import static org.junit.Assert.*;

/** Synthetic archives isolate layout/routing regressions without requiring a locally installed demo or server. */
public class ArchiveRoutingTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    /** Solon must retain its own launcher while sharing the BOOT-INF storage contract. */
    @Test public void solonRoundTrip() throws Exception {
        roundTrip("org.noear.solon.loader.JarLauncher", "io.xjar.solon.XSolonLauncher", true, false);
    }

    /** New framework detection must not change the established Spring Boot branch. */
    @Test public void springBootRoundTrip() throws Exception {
        roundTrip("org.springframework.boot.loader.launch.JarLauncher", "io.xjar.boot.XJarLauncher", true, true);
    }

    /** Ordinary executable archives must still use root-relative encryption and their original main class. */
    @Test public void ordinaryJarRoundTrip() throws Exception {
        roundTrip("sample.Main", "io.xjar.jar.XJarLauncher", false, false);
    }

    /** Check ciphertext, untouched resources, stored dependencies and manifest restoration instead of only file creation. */
    private void roundTrip(String launcher, String encryptedLauncher, boolean nested, boolean spring) throws Exception {
        Path original = temp.newFile("original.jar").toPath();
        Path encrypted = temp.getRoot().toPath().resolve("encrypted.jar");
        Path restored = temp.getRoot().toPath().resolve("restored.jar");
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class", launcher);
        if (nested) manifest.getMainAttributes().putValue("Start-Class", "sample.Main");
        if (spring) manifest.getMainAttributes().putValue("Spring-Boot-Version", "3.4.2");
        String prefix = nested ? "BOOT-INF/classes/" : "";
        byte[] business = "business-bytecode-fixture".getBytes();
        byte[] ignored = "unchanged-resource".getBytes();
        byte[] library;
        try (ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            try (JarOutputStream jar = new JarOutputStream(bytes)) { entry(jar, "other/data.txt", ignored, false); }
            library = bytes.toByteArray();
        }
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(original), manifest)) {
            entry(jar, prefix + "sample/Main.class", business, false);
            entry(jar, prefix + "untouched.txt", ignored, false);
            if (nested) entry(jar, "BOOT-INF/lib/dependency.jar", library, true);
        }
        var filter = XFilters.ant("sample/**");
        XCryptos.encrypt(original.toFile(), encrypted.toFile(), "test-only", filter);
        try (JarFile jar = new JarFile(encrypted.toFile())) {
            assertEquals(encryptedLauncher, jar.getManifest().getMainAttributes().getValue("Main-Class"));
            assertNotNull(jar.getJarEntry(prefix + "XJAR-INF/INDEXES.IDX"));
            assertFalse(Arrays.equals(business, jar.getInputStream(jar.getJarEntry(prefix + "sample/Main.class")).readAllBytes()));
            assertArrayEquals(ignored, jar.getInputStream(jar.getJarEntry(prefix + "untouched.txt")).readAllBytes());
            if (nested) {
                assertEquals(JarEntry.STORED, jar.getJarEntry("BOOT-INF/lib/dependency.jar").getMethod());
                assertArrayEquals(library, jar.getInputStream(jar.getJarEntry("BOOT-INF/lib/dependency.jar")).readAllBytes());
            }
        }
        XCryptos.decrypt(encrypted.toFile(), restored.toFile(), "test-only", filter);
        try (JarFile jar = new JarFile(restored.toFile())) {
            assertEquals(manifest.getMainAttributes(), jar.getManifest().getMainAttributes());
            assertArrayEquals(business, jar.getInputStream(jar.getJarEntry(prefix + "sample/Main.class")).readAllBytes());
            assertNull(jar.getJarEntry(prefix + "XJAR-INF/INDEXES.IDX"));
            if (nested) assertEquals(JarEntry.STORED, jar.getJarEntry("BOOT-INF/lib/dependency.jar").getMethod());
        }
    }

    /** Explicit CRC and size model the uncompressed nested archives required by framework loaders. */
    private static void entry(JarOutputStream jar, String name, byte[] bytes, boolean stored) throws Exception {
        JarEntry entry = new JarEntry(name);
        if (stored) {
            CRC32 crc = new CRC32();
            crc.update(bytes);
            entry.setMethod(JarEntry.STORED);
            entry.setSize(bytes.length);
            entry.setCrc(crc.getValue());
        }
        jar.putNextEntry(entry);
        jar.write(bytes);
        jar.closeEntry();
    }
}
