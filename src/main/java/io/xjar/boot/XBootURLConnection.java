package io.xjar.boot;

import io.xjar.XDecryptor;
import io.xjar.XEncryptor;
import io.xjar.key.XKey;

import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.Permission;
import java.security.cert.Certificate;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.CRC32;

/**
 * 加密的URL连接
 *
 * @author Payne 646742615@qq.com
 * 2018/11/24 13:57
 */
public class XBootURLConnection extends JarURLConnection {
    private final JarURLConnection jarURLConnection;
    private final XDecryptor xDecryptor;
    private final XEncryptor xEncryptor;
    private final XKey xKey;
    private PlaintextMetadata plaintextMetadata;

    public XBootURLConnection(JarURLConnection jarURLConnection, XDecryptor xDecryptor, XEncryptor xEncryptor, XKey xKey) throws MalformedURLException {
        super(jarURLConnection.getURL());
        this.jarURLConnection = jarURLConnection;
        this.xDecryptor = xDecryptor;
        this.xEncryptor = xEncryptor;
        this.xKey = xKey;
    }

    @Override
    public void connect() throws IOException {
        jarURLConnection.connect();
    }

    @Override
    public int getConnectTimeout() {
        return jarURLConnection.getConnectTimeout();
    }

    @Override
    public void setConnectTimeout(int timeout) {
        jarURLConnection.setConnectTimeout(timeout);
    }

    @Override
    public int getReadTimeout() {
        return jarURLConnection.getReadTimeout();
    }

    @Override
    public void setReadTimeout(int timeout) {
        jarURLConnection.setReadTimeout(timeout);
    }

    @Override
    public URL getURL() {
        return jarURLConnection.getURL();
    }

    /** URLConnection uses -1 when an entry cannot be measured or its length exceeds the int API's range. */
    @Override
    public int getContentLength() {
        long length = getContentLengthLong();
        return length <= Integer.MAX_VALUE ? (int) length : -1;
    }

    /** Older encrypted archives carry no plaintext length, so measure once without retaining their decrypted contents. */
    @Override
    public long getContentLengthLong() {
        try {
            return plaintextMetadata().length();
        } catch (IOException failure) {
            return -1;
        }
    }

    @Override
    public String getContentType() {
        return jarURLConnection.getContentType();
    }

    @Override
    public String getContentEncoding() {
        return jarURLConnection.getContentEncoding();
    }

    @Override
    public long getExpiration() {
        return jarURLConnection.getExpiration();
    }

    @Override
    public long getDate() {
        return jarURLConnection.getDate();
    }

    @Override
    public long getLastModified() {
        return jarURLConnection.getLastModified();
    }

    /** Header consumers must see the same length as the decrypted stream rather than AES padding in the ZIP entry. */
    @Override
    public String getHeaderField(String name) {
        if ("content-length".equalsIgnoreCase(name)) {
            long length = getContentLengthLong();
            return length >= 0 ? Long.toString(length) : null;
        }
        return jarURLConnection.getHeaderField(name);
    }

    /** Copy the delegate's headers so ciphertext lengths and shared mutable header lists cannot escape this view. */
    @Override
    public Map<String, List<String>> getHeaderFields() {
        Map<String, List<String>> original = jarURLConnection.getHeaderFields();
        Map<String, List<String>> headers = new LinkedHashMap<>();
        if (original.containsKey(null)) {
            headers.put(null, List.copyOf(original.get(null)));
        }
        for (Map.Entry<String, List<String>> header : original.entrySet()) {
            if (header.getKey() != null && !"content-length".equalsIgnoreCase(header.getKey())) {
                headers.put(header.getKey(), List.copyOf(header.getValue()));
            }
        }
        String length = getHeaderField("content-length");
        if (length != null) {
            headers.put("content-length", List.of(length));
        }
        return Collections.unmodifiableMap(headers);
    }

    /** Parse the corrected header through URLConnection so unknown or overflowing values honor the caller's default. */
    @Override
    public int getHeaderFieldInt(String name, int Default) {
        return super.getHeaderFieldInt(name, Default);
    }

    /** Keep typed header lookup on the same plaintext metadata path as ordinary header lookup. */
    @Override
    public long getHeaderFieldLong(String name, long Default) {
        return super.getHeaderFieldLong(name, Default);
    }

    /** Use the wrapper's header values even when a caller requests an unusual date conversion. */
    @Override
    public long getHeaderFieldDate(String name, long Default) {
        return super.getHeaderFieldDate(name, Default);
    }

    /** Indexed header names and values must enumerate the same corrected snapshot. */
    @Override
    public String getHeaderFieldKey(int n) {
        Map.Entry<String, String> header = headerAt(n);
        return header != null ? header.getKey() : null;
    }

    /** Do not let indexed access bypass the plaintext length used by named header access. */
    @Override
    public String getHeaderField(int n) {
        Map.Entry<String, String> header = headerAt(n);
        return header != null ? header.getValue() : null;
    }

    /** Content handlers must operate on this decrypting connection, never the delegate's ciphertext stream. */
    @Override
    public Object getContent() throws IOException {
        return super.getContent();
    }

    /** Preserve standard content-type negotiation while routing every selected handler through decryption. */
    @Override
    public Object getContent(Class[] classes) throws IOException {
        return super.getContent(classes);
    }

    @Override
    public Permission getPermission() throws IOException {
        return jarURLConnection.getPermission();
    }

    /** Close the raw entry if cipher initialization fails; a successfully returned stream owns that entry thereafter. */
    @Override
    public InputStream getInputStream() throws IOException {
        InputStream in = jarURLConnection.getInputStream();
        try {
            return new PlaintextInputStream(xDecryptor.decrypt(xKey, in));
        } catch (IOException | RuntimeException | Error failure) {
            try {
                in.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /**
     * Read a separate JarFile entry stream so measuring metadata cannot consume the response or close a no-cache
     * URLConnection's JarFile. Cache only length and CRC after verified EOF; memory use stays constant for large assets.
     */
    private synchronized PlaintextMetadata plaintextMetadata() throws IOException {
        if (plaintextMetadata == null) {
            JarEntry entry = jarURLConnection.getJarEntry();
            if (entry == null) {
                throw new IOException("Encrypted URL has no resource entry: " + getURL());
            }
            CRC32 crc = new CRC32();
            long length = 0;
            try (InputStream raw = jarURLConnection.getJarFile().getInputStream(entry);
                 InputStream plaintext = xDecryptor.decrypt(xKey, raw)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = plaintext.read(buffer)) != -1) {
                    length += count;
                    crc.update(buffer, 0, count);
                }
            }
            plaintextMetadata = new PlaintextMetadata(length, crc.getValue());
        }
        return plaintextMetadata;
    }

    /** Preserve repeated header values and a possible status-line key without exposing the delegate's stale indices. */
    private Map.Entry<String, String> headerAt(int index) {
        if (index < 0) {
            return null;
        }
        int current = 0;
        for (Map.Entry<String, List<String>> header : getHeaderFields().entrySet()) {
            for (String value : header.getValue()) {
                if (current++ == index) {
                    return new AbstractMap.SimpleImmutableEntry<>(header.getKey(), value);
                }
            }
        }
        return null;
    }

    /** Publish a complete immutable measurement so concurrent header and JarEntry lookups cannot observe partial results. */
    private record PlaintextMetadata(long length, long crc) { }

    /** Range offsets refer to plaintext; cipher streams may only skip bytes already present in their decoded buffer. */
    private static final class PlaintextInputStream extends FilterInputStream {
        /** Keep normal reads and close ownership on the cipher stream while overriding only its incomplete skip behavior. */
        private PlaintextInputStream(InputStream plaintext) {
            super(plaintext);
        }

        /** Decode and discard in bounded chunks so HTTP suffix and middle ranges reach their exact plaintext offset. */
        @Override
        public long skip(long count) throws IOException {
            if (count <= 0) {
                return 0;
            }
            byte[] buffer = new byte[(int) Math.min(count, 8192)];
            long remaining = count;
            while (remaining > 0) {
                int read = in.read(buffer, 0, (int) Math.min(remaining, buffer.length));
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    if (in.read() < 0) {
                        break;
                    }
                    remaining--;
                } else {
                    remaining -= read;
                }
            }
            return count - remaining;
        }
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        OutputStream out = jarURLConnection.getOutputStream();
        return xEncryptor.encrypt(xKey, out);
    }

    @Override
    public String toString() {
        return jarURLConnection.toString();
    }

    @Override
    public boolean getDoInput() {
        return jarURLConnection.getDoInput();
    }

    @Override
    public void setDoInput(boolean doInput) {
        jarURLConnection.setDoInput(doInput);
    }

    @Override
    public boolean getDoOutput() {
        return jarURLConnection.getDoOutput();
    }

    @Override
    public void setDoOutput(boolean doOutput) {
        jarURLConnection.setDoOutput(doOutput);
    }

    @Override
    public boolean getAllowUserInteraction() {
        return jarURLConnection.getAllowUserInteraction();
    }

    @Override
    public void setAllowUserInteraction(boolean allowUserInteraction) {
        jarURLConnection.setAllowUserInteraction(allowUserInteraction);
    }

    @Override
    public boolean getUseCaches() {
        return jarURLConnection.getUseCaches();
    }

    @Override
    public void setUseCaches(boolean useCaches) {
        jarURLConnection.setUseCaches(useCaches);
    }

    @Override
    public long getIfModifiedSince() {
        return jarURLConnection.getIfModifiedSince();
    }

    @Override
    public void setIfModifiedSince(long ifModifiedSince) {
        jarURLConnection.setIfModifiedSince(ifModifiedSince);
    }

    @Override
    public boolean getDefaultUseCaches() {
        return jarURLConnection.getDefaultUseCaches();
    }

    @Override
    public void setDefaultUseCaches(boolean defaultUseCaches) {
        jarURLConnection.setDefaultUseCaches(defaultUseCaches);
    }

    @Override
    public void setRequestProperty(String key, String value) {
        jarURLConnection.setRequestProperty(key, value);
    }

    @Override
    public void addRequestProperty(String key, String value) {
        jarURLConnection.addRequestProperty(key, value);
    }

    @Override
    public String getRequestProperty(String key) {
        return jarURLConnection.getRequestProperty(key);
    }

    @Override
    public Map<String, List<String>> getRequestProperties() {
        return jarURLConnection.getRequestProperties();
    }

    @Override
    public URL getJarFileURL() {
        return jarURLConnection.getJarFileURL();
    }

    @Override
    public String getEntryName() {
        return jarURLConnection.getEntryName();
    }

    @Override
    public JarFile getJarFile() throws IOException {
        return jarURLConnection.getJarFile();
    }

    @Override
    public Manifest getManifest() throws IOException {
        return jarURLConnection.getManifest();
    }

    /** Return a separate plaintext view; mutating the delegate's shared ZIP entry would corrupt later class/resource reads. */
    @Override
    public JarEntry getJarEntry() throws IOException {
        JarEntry original = jarURLConnection.getJarEntry();
        if (original == null) {
            return null;
        }
        PlaintextMetadata metadata = plaintextMetadata();
        // Boot's JarEntry fills ZIP fields lazily; initialize them before the JDK copy constructor reads its fields.
        original.getMethod();
        JarEntry plaintext = new JarEntry(original);
        plaintext.setSize(metadata.length());
        plaintext.setCrc(metadata.crc());
        plaintext.setCompressedSize(-1);
        return plaintext;
    }

    @Override
    public Attributes getAttributes() throws IOException {
        return jarURLConnection.getAttributes();
    }

    @Override
    public Attributes getMainAttributes() throws IOException {
        return jarURLConnection.getMainAttributes();
    }

    @Override
    public Certificate[] getCertificates() throws IOException {
        return jarURLConnection.getCertificates();
    }
}
