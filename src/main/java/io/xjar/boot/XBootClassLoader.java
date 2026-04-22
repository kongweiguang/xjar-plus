package io.xjar.boot;

import io.xjar.XDecryptor;
import io.xjar.XEncryptor;
import io.xjar.XKit;
import io.xjar.key.XKey;
import org.springframework.boot.loader.launch.LaunchedClassLoader;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.security.CodeSigner;
import java.security.CodeSource;
import java.util.Enumeration;
import java.util.jar.Manifest;


/**
 * X类加载器
 *
 * @author Payne 646742615@qq.com
 * 2018/11/23 23:04
 */
public class XBootClassLoader extends LaunchedClassLoader {
    private final XBootURLHandler xBootURLHandler;

    static {
        ClassLoader.registerAsParallelCapable();
    }

    public XBootClassLoader(URL[] urls, ClassLoader parent, XDecryptor xDecryptor, XEncryptor xEncryptor, XKey xKey) throws Exception {
        super(true, urls, parent);
        this.xBootURLHandler = new XBootURLHandler(xDecryptor, xEncryptor, xKey, this);
    }

    @Override
    public URL findResource(String name) {
        URL url = super.findResource(name);
        if (url == null) {
            return null;
        }
        try {
            return new URL(url.getProtocol(), url.getHost(), url.getPort(), url.getFile(), xBootURLHandler);
        } catch (MalformedURLException e) {
            return url;
        }
    }

    @Override
    public Enumeration<URL> findResources(String name) throws IOException {
        Enumeration<URL> enumeration = super.findResources(name);
        if (enumeration == null) {
            return null;
        }
        return new XBootEnumeration(enumeration);
    }

    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String path = name.replace('.', '/').concat(".class");
        if (!xBootURLHandler.isEncrypted(path)) {
            return super.findClass(name);
        }
        URL url = findResource(path);
        if (xBootURLHandler.isEncrypted(url)) {
            try {
                byte[] bytes = read(url);
                definePackageIfNecessary(name, url);
                CodeSource codeSource = new CodeSource(url, (CodeSigner[]) null);
                return defineClass(name, bytes, 0, bytes.length, codeSource);
            } catch (Throwable t) {
                throw new ClassNotFoundException(name, t);
            }
        }
        return super.findClass(name);
    }

    private byte[] read(URL url) throws IOException {
        try (InputStream in = url.openStream()) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            XKit.transfer(in, bos);
            return bos.toByteArray();
        }
    }

    private void definePackageIfNecessary(String className, URL url) throws IOException {
        int index = className.lastIndexOf('.');
        if (index < 0) {
            return;
        }
        String packageName = className.substring(0, index);
        if (getDefinedPackage(packageName) != null) {
            return;
        }
        try {
            URLConnection connection = url.openConnection();
            if (connection instanceof JarURLConnection) {
                JarURLConnection jarConnection = (JarURLConnection) connection;
                Manifest manifest = jarConnection.getManifest();
                URL sourceUrl = jarConnection.getJarFileURL();
                definePackage(packageName, manifest, sourceUrl);
            } else {
                definePackage(packageName, null, null, null, null, null, null, null);
            }
        } catch (IllegalArgumentException ignored) {
            // Another parallel class load may have defined the package first.
        }
    }

    private class XBootEnumeration implements Enumeration<URL> {
        private final Enumeration<URL> enumeration;

        XBootEnumeration(Enumeration<URL> enumeration) {
            this.enumeration = enumeration;
        }

        @Override
        public boolean hasMoreElements() {
            return enumeration.hasMoreElements();
        }

        @Override
        public URL nextElement() {
            URL url = enumeration.nextElement();
            if (url == null) {
                return null;
            }
            try {
                return new URL(url.getProtocol(), url.getHost(), url.getPort(), url.getFile(), xBootURLHandler);
            } catch (MalformedURLException e) {
                return url;
            }
        }
    }
}
