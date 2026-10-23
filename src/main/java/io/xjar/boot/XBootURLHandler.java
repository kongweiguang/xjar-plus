package io.xjar.boot;

import io.xjar.XDecryptor;
import io.xjar.XEncryptedIndex;
import io.xjar.XEncryptor;
import io.xjar.key.XKey;
import org.springframework.boot.loader.net.protocol.jar.Handler;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * 加密的URL处理器
 *
 * @author Payne 646742615@qq.com
 * 2018/11/24 13:19
 */
public class XBootURLHandler extends Handler {
    private final XDecryptor xDecryptor;
    private final XEncryptor xEncryptor;
    private final XKey xKey;
    private final XEncryptedIndex index;

    public XBootURLHandler(XDecryptor xDecryptor, XEncryptor xEncryptor, XKey xKey, ClassLoader classLoader) throws Exception {
        this.xDecryptor = xDecryptor;
        this.xEncryptor = xEncryptor;
        this.xKey = xKey;
        this.index = new XEncryptedIndex(classLoader);
    }

    public boolean isEncrypted(URL url) {
        return index.containsUrl(url);
    }

    public boolean isEncrypted(String name) {
        return index.containsEntry(name);
    }

    /** Resolve logical MR entries separately so callers can still configure the returned response connection before reading. */
    @Override
    protected URLConnection openConnection(URL url) throws IOException {
        URLConnection urlConnection = super.openConnection(url);
        if (urlConnection instanceof JarURLConnection) {
            JarURLConnection jarConnection = (JarURLConnection) urlConnection;
            boolean encrypted = isSelectedEntryEncrypted(url, jarConnection);
            JarURLConnection response = (JarURLConnection) super.openConnection(url);
            if (encrypted) {
                return new XBootURLConnection(response, xDecryptor, xEncryptor, xKey);
            }
            return response;
        }
        return urlConnection;
    }

    /**
     * Preserve the selected archive's URL when looking up its physical entry, preventing same-name entries in other
     * dependencies from changing this decision. The archive URL already preserves URL escaping; the index stores entry
     * names verbatim, so the reconstructed URL is used only for identity comparison and never to open the entry again.
     * Only uncached selection archives belong to this temporary lookup; cached archives remain owned by Boot.
     */
    private boolean isSelectedEntryEncrypted(URL url, JarURLConnection connection) throws IOException {
        JarFile archive = connection.getJarFile();
        try {
            JarEntry entry = connection.getJarEntry();
            if (entry == null) {
                return false;
            }
            URL physicalEntry = new URL(url, "jar:" + connection.getJarFileURL().toExternalForm() + "!/" + entry.getRealName());
            return index.containsUrl(physicalEntry);
        } finally {
            if (!connection.getUseCaches()) {
                archive.close();
            }
        }
    }

}
