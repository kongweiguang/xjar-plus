package verification;

import io.xjar.XJdkDecryptor;
import io.xjar.XJdkEncryptor;
import io.xjar.XKit;
import io.xjar.boot.XBootURLConnection;
import io.xjar.key.XKey;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.zip.CRC32;

import static org.junit.Assert.*;

/** Real encrypted JAR entries expose the HTTP resource contract without requiring a web server or global URL handler. */
public class BootResourceConnectionTest {
    private static final String ENTRY_NAME = "static/payload.bin";
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    /** AES padding changes at block boundaries, so every public length path must describe the bytes actually served. */
    @Test public void plaintextLengthsAndCrcMatchEmptyBlockBoundariesAndLargeBinary() throws Exception {
        for (int size : new int[]{0, 15, 16, 17, 1024 * 1024 + 37}) {
            byte[] plaintext = binary(size);
            try (Fixture fixture = fixture(plaintext, "resource-test-key", false)) {
                XBootURLConnection connection = fixture.connection;
                assertEquals("int length for " + size, size, connection.getContentLength());
                assertEquals("long length for " + size, size, connection.getContentLengthLong());
                assertLengthHeaders(connection, size);
                JarEntry exposed = connection.getJarEntry();
                assertEquals(size, exposed.getSize());
                assertEquals(crc(plaintext), exposed.getCrc());
                assertArrayEquals(plaintext, read(connection.getInputStream()));
                assertEquals("metadata must not alter the stored ciphertext entry", fixture.ciphertext.length,
                        fixture.original.getJarEntry(ENTRY_NAME).getSize());
                assertEquals(crc(fixture.ciphertext), fixture.original.getJarEntry(ENTRY_NAME).getCrc());
                assertArrayEquals(fixture.ciphertext,
                        read(fixture.original.getInputStream(fixture.original.getJarEntry(ENTRY_NAME))));
                assertEquals("the wrapped connection must retain ciphertext metadata", fixture.ciphertext.length,
                        fixture.wrapped.getJarEntry().getSize());
            }
        }
    }

    /** Frameworks may inspect metadata and request content more than once during range or conditional request handling. */
    @Test public void metadataDoesNotConsumeLaterStreamsOrContentAccessors() throws Exception {
        byte[] plaintext = binary(8193);
        try (Fixture fixture = fixture(plaintext, "resource-test-key", false)) {
            XBootURLConnection connection = fixture.connection;
            assertLengthHeaders(connection, plaintext.length);
            assertEquals(plaintext.length, connection.getJarEntry().getSize());
            assertArrayEquals(plaintext, read(connection.getInputStream()));
            assertArrayEquals(plaintext, read(connection.getInputStream()));
            assertArrayEquals(plaintext, read(asStream(connection.getContent())));
            assertArrayEquals(plaintext, read(asStream(connection.getContent(new Class[]{InputStream.class}))));
            assertEquals(plaintext.length, connection.getContentLengthLong());
            assertLengthHeaders(connection, plaintext.length);
        }
    }

    /** An uncached JDK connection closes its JarFile with the response stream, so metadata must use a separate entry stream. */
    @Test public void metadataLeavesUncachedConnectionAvailableForTheResponse() throws Exception {
        byte[] plaintext = binary(17);
        try (Fixture fixture = fixture(plaintext, "resource-test-key", false)) {
            fixture.connection.setUseCaches(false);
            assertEquals(plaintext.length, fixture.connection.getContentLengthLong());
            assertArrayEquals(plaintext, read(fixture.connection.getInputStream()));
        }
    }

    /** HTTP suffix ranges seek before reading; cipher buffers must not turn a requested offset into a shorter skip. */
    @Test public void skipBeforeAnyReadServesTheExactSuffix() throws Exception {
        byte[] plaintext = binary(8193);
        try (Fixture fixture = fixture(plaintext, "resource-test-key", false);
             InputStream input = fixture.connection.getInputStream()) {
            assertEquals(13L, input.skip(13));
            assertArrayEquals(Arrays.copyOfRange(plaintext, 13, plaintext.length), input.readAllBytes());
        }
    }

    /** Invalid or oversized ranges must preserve normal InputStream semantics while reporting the bytes really skipped. */
    @Test public void negativeAndPastEndSkipsPreserveTheVisiblePosition() throws Exception {
        byte[] plaintext = binary(8193);
        try (Fixture fixture = fixture(plaintext, "resource-test-key", false);
             InputStream input = fixture.connection.getInputStream()) {
            assertEquals(0L, input.skip(-13));
            assertEquals(plaintext[0] & 0xff, input.read());
            assertEquals(plaintext.length - 1L, input.skip(plaintext.length * 2L));
            assertEquals(-1, input.read());
            assertEquals(0L, input.skip(1));
        }
    }

    /** Repeated offsets crossing cipher blocks must seek through plaintext rather than exposing the cipher's buffer size. */
    @Test public void repeatedBlockCrossingSkipsServeTheExactSuffix() throws Exception {
        byte[] plaintext = binary(8193);
        try (Fixture fixture = fixture(plaintext, "resource-test-key", false);
             InputStream input = fixture.connection.getInputStream()) {
            assertEquals(16L, input.skip(16));
            assertEquals(plaintext[16] & 0xff, input.read());
            assertEquals(17L, input.skip(17));
            assertEquals(plaintext[34] & 0xff, input.read());
            assertArrayEquals(Arrays.copyOfRange(plaintext, 35, plaintext.length), input.readAllBytes());
        }
    }

    /** A large offset exceeds ordinary read buffers and catches range handling that only advances one buffer per call. */
    @Test public void largeSkipServesTheExactSuffix() throws Exception {
        byte[] plaintext = binary(128 * 1024 + 37);
        int offset = 65539;
        try (Fixture fixture = fixture(plaintext, "resource-test-key", false);
             InputStream input = fixture.connection.getInputStream()) {
            assertEquals(offset, input.skip(offset));
            assertArrayEquals(Arrays.copyOfRange(plaintext, offset, plaintext.length), input.readAllBytes());
        }
    }

    /** A shared URL connection must not race its metadata scan against reads from independent decrypted streams. */
    @Test public void concurrentMetadataAndReadsRemainConsistent() throws Exception {
        byte[] plaintext = binary(256 * 1024 + 3);
        try (Fixture fixture = fixture(plaintext, "resource-test-key", false)) {
            ExecutorService workers = Executors.newFixedThreadPool(6);
            try {
                List<Callable<Void>> requests = new ArrayList<>();
                for (int i = 0; i < 18; i++) {
                    requests.add(() -> {
                        assertEquals(plaintext.length, fixture.connection.getContentLengthLong());
                        assertEquals(crc(plaintext), fixture.connection.getJarEntry().getCrc());
                        assertLengthHeaders(fixture.connection, plaintext.length);
                        assertArrayEquals(plaintext, read(fixture.connection.getInputStream()));
                        return null;
                    });
                }
                for (Future<Void> response : workers.invokeAll(requests)) response.get();
            } finally {
                workers.shutdownNow();
            }
        }
    }

    /** Failed decryption must not publish a valid-looking ciphertext length that causes truncated HTTP responses. */
    @Test public void wrongKeyDoesNotExposeCiphertextLength() throws Exception {
        try (Fixture fixture = fixture(binary(17), "incorrect-resource-key", false)) {
            assertUnreadable(fixture.connection);
        }
    }

    /** A partial archive download deterministically fails padding, independently of which wrong password is chosen. */
    @Test public void truncatedCiphertextDoesNotExposeCiphertextLength() throws Exception {
        try (Fixture fixture = fixture(binary(17), "resource-test-key", true)) {
            assertUnreadable(fixture.connection);
        }
    }

    /** Header variants model the entry points used by URLConnection clients instead of assuming a single caller API. */
    private static void assertLengthHeaders(XBootURLConnection connection, long expected) {
        for (String name : new String[]{"content-length", "Content-Length", "CONTENT-LENGTH"}) {
            assertEquals(Long.toString(expected), connection.getHeaderField(name));
            assertEquals(expected, connection.getHeaderFieldInt(name, -123));
            assertEquals(expected, connection.getHeaderFieldLong(name, -123));
        }
        boolean foundInMap = false;
        for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
            if ("content-length".equalsIgnoreCase(header.getKey())) {
                foundInMap = true;
                assertEquals(Arrays.asList(Long.toString(expected)), header.getValue());
            }
        }
        assertTrue("header map must expose the served length", foundInMap);
        boolean foundByIndex = false;
        for (int index = 0; index < 64; index++) {
            String name = connection.getHeaderFieldKey(index);
            String value = connection.getHeaderField(index);
            if (name == null && value == null) break;
            if ("content-length".equalsIgnoreCase(name)) {
                foundByIndex = true;
                assertEquals(Long.toString(expected), value);
            }
        }
        assertTrue("indexed headers must expose the served length", foundByIndex);
        assertEquals(-123, connection.getHeaderFieldInt("x-no-such-header", -123));
        assertEquals(-123L, connection.getHeaderFieldLong("x-no-such-header", -123));
    }

    /** URLConnection's non-throwing metadata APIs need an unknown result while throwing APIs preserve the read failure. */
    private static void assertUnreadable(XBootURLConnection connection) throws Exception {
        assertEquals(-1, connection.getContentLength());
        assertEquals(-1L, connection.getContentLengthLong());
        for (String name : new String[]{"content-length", "Content-Length", "CONTENT-LENGTH"}) {
            String value = connection.getHeaderField(name);
            assertTrue("failed metadata must not reveal ciphertext length", value == null || "-1".equals(value));
            long typed = connection.getHeaderFieldLong(name, -123);
            assertTrue(typed == -1 || typed == -123);
        }
        for (Map.Entry<String, List<String>> header : connection.getHeaderFields().entrySet()) {
            if ("content-length".equalsIgnoreCase(header.getKey())) {
                for (String value : header.getValue()) assertTrue(value == null || "-1".equals(value));
            }
        }
        for (int index = 0; index < 64; index++) {
            String name = connection.getHeaderFieldKey(index);
            String value = connection.getHeaderField(index);
            if (name == null && value == null) break;
            if ("content-length".equalsIgnoreCase(name)) assertTrue(value == null || "-1".equals(value));
        }
        assertThrows(IOException.class, connection::getJarEntry);
        assertThrows(IOException.class, () -> read(connection.getInputStream()));
        assertEquals(-1L, connection.getContentLengthLong());
    }

    /** Fixed binary data catches accidental character conversion while keeping failed decryptions reproducible. */
    private static byte[] binary(int size) {
        byte[] bytes = new byte[size];
        new Random(0x584A4152L + size).nextBytes(bytes);
        return bytes;
    }

    /** CRC describes the visible bytes and therefore detects metadata copied directly from an encrypted ZIP entry. */
    private static long crc(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return crc.getValue();
    }

    /** Closing each returned stream also proves that later access does not rely on an already consumed decrypted stream. */
    private static byte[] read(InputStream stream) throws IOException {
        try (InputStream input = stream) {
            return input.readAllBytes();
        }
    }

    /** Binary content has no specialized JDK handler, so every supported accessor must return a decrypted stream. */
    private static InputStream asStream(Object content) {
        assertTrue("binary resource content must be an InputStream", content instanceof InputStream);
        return (InputStream) content;
    }

    /** A local stored JAR preserves exact ciphertext metadata without a server, a custom protocol, or process-wide state. */
    private Fixture fixture(byte[] plaintext, String readingPassword, boolean truncated) throws Exception {
        XKey writingKey = XKit.key("resource-test-key");
        ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
        new XJdkEncryptor().encrypt(writingKey, new ByteArrayInputStream(plaintext), encrypted);
        byte[] ciphertext = encrypted.toByteArray();
        if (truncated) ciphertext = Arrays.copyOf(ciphertext, ciphertext.length - 1);
        Path archive = temp.newFile().toPath();
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(archive))) {
            JarEntry entry = new JarEntry(ENTRY_NAME);
            entry.setMethod(JarEntry.STORED);
            entry.setSize(ciphertext.length);
            entry.setCrc(crc(ciphertext));
            jar.putNextEntry(entry);
            jar.write(ciphertext);
            jar.closeEntry();
        }
        JarURLConnection wrapped = (JarURLConnection) new URL("jar:" + archive.toUri().toURL() + "!/" + ENTRY_NAME).openConnection();
        // JDK jar connections close their own JarFile with the first stream when caching is disabled.
        // This object-local setting permits repeat reads; Fixture.close releases that unique temporary JarFile.
        wrapped.setUseCaches(true);
        JarFile original = new JarFile(archive.toFile());
        return new Fixture(original, wrapped, ciphertext,
                new XBootURLConnection(wrapped, new XJdkDecryptor(), new XJdkEncryptor(), XKit.key(readingPassword)));
    }

    /** Explicit ownership avoids Windows file locks and keeps each archive isolated from the next test. */
    private static final class Fixture implements AutoCloseable {
        private final JarFile original;
        private final JarURLConnection wrapped;
        private final byte[] ciphertext;
        private final XBootURLConnection connection;

        /** Retain both physical and wrapped archive views so tests can detect mutations of the underlying ZIP metadata. */
        private Fixture(JarFile original, JarURLConnection wrapped, byte[] ciphertext, XBootURLConnection connection) {
            this.original = original;
            this.wrapped = wrapped;
            this.ciphertext = ciphertext;
            this.connection = connection;
        }

        /** Even failed decryptions must release both local archive handles before TemporaryFolder removes the files. */
        @Override public void close() throws IOException {
            try {
                wrapped.getJarFile().close();
            } finally {
                original.close();
            }
        }
    }
}
