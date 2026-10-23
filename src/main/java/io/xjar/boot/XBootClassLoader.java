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

    /** Only archived entries use the decrypting JAR handler; exploded classpaths must retain their file protocol handler. */
    @Override
    public URL findResource(String name) {
        URL url = super.findResource(name);
        if (url == null || !"jar".equalsIgnoreCase(url.getProtocol())) {
            return url;
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

    /** Resolve the runtime's selected entry before checking encryption; multi-release classes may have no base entry. */
    @Override
    protected Class<?> findClass(String name) throws ClassNotFoundException {
        String path = name.replace('.', '/').concat(".class");
        URL url = findResource(path);
        if (url != null) {
            try {
                URLConnection connection = url.openConnection();
                if (!(connection instanceof XBootURLConnection)) {
                    return super.findClass(name);
                }
                byte[] bytes = read(connection);
                definePackageIfNecessary(name, url);
                CodeSource codeSource = new CodeSource(url, (CodeSigner[]) null);
                return defineClass(name, bytes, 0, bytes.length, codeSource);
            } catch (IOException failure) {
                throw new ClassNotFoundException(name, failure);
            }
        }
        return super.findClass(name);
    }

    /** Read from the already classified connection so selection and decryption cannot take different resource paths. */
    private byte[] read(URLConnection connection) throws IOException {
        try (InputStream in = connection.getInputStream()) {
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

        /** Enumeration must preserve the same archive-only wrapping as single-resource lookup, including mixed classpaths. */
        @Override
        public URL nextElement() {
            URL url = enumeration.nextElement();
            if (url == null || !"jar".equalsIgnoreCase(url.getProtocol())) {
                return url;
            }
            try {
                return new URL(url.getProtocol(), url.getHost(), url.getPort(), url.getFile(), xBootURLHandler);
            } catch (MalformedURLException e) {
                return url;
            }
        }
    }
}
