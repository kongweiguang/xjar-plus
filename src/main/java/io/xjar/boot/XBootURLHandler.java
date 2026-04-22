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

    @Override
    protected URLConnection openConnection(URL url) throws IOException {
        URLConnection urlConnection = super.openConnection(url);
        return isEncrypted(url)
               && urlConnection instanceof JarURLConnection
                ? new XBootURLConnection((JarURLConnection) urlConnection, xDecryptor, xEncryptor, xKey)
                : urlConnection;
    }

}
