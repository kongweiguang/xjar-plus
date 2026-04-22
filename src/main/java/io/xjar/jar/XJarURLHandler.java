package io.xjar.jar;

import io.xjar.XDecryptor;
import io.xjar.XEncryptedIndex;
import io.xjar.XEncryptor;
import io.xjar.key.XKey;

import java.io.IOException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;

/**
 * 加密的URL处理器
 *
 * @author Payne 646742615@qq.com
 * 2018/11/24 13:19
 */
public class XJarURLHandler extends URLStreamHandler {
    private final XDecryptor xDecryptor;
    private final XEncryptor xEncryptor;
    private final XKey xKey;
    private final XEncryptedIndex index;

    public XJarURLHandler(XDecryptor xDecryptor, XEncryptor xEncryptor, XKey xKey, ClassLoader classLoader) throws Exception {
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

    @Override
    protected URLConnection openConnection(URL url) throws IOException {
        URLConnection urlConnection = new URL(url.toString()).openConnection();
        return isEncrypted(url)
                && urlConnection instanceof JarURLConnection
                ? new XJarURLConnection((JarURLConnection) urlConnection, xDecryptor, xEncryptor, xKey)
                : urlConnection;
    }

}
