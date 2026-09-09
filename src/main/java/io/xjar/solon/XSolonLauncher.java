package io.xjar.solon;

import io.xjar.XLauncher;
import io.xjar.jar.XJarClassLoader;
import org.noear.solon.loader.JarLauncher;

import java.net.URL;

/** Keeps Solon's archive discovery and nested URL protocol while decrypting classes and resources in memory. */
public class XSolonLauncher extends JarLauncher {
    private final XLauncher xLauncher;

    /** Read the existing stdin key protocol so Java and Go launch paths use identical credentials. */
    public XSolonLauncher(String... args) throws Exception {
        this.xLauncher = new XLauncher(args);
    }

    /** Delegate startup to Solon so Start-Class and context-class-loader ownership retain their native semantics. */
    public static void main(String[] args) throws Exception {
        XSolonLauncher launcher = new XSolonLauncher(args);
        launcher.launch(launcher.xLauncher.args);
    }

    /** Solon registers its nested-JAR handler before this hook; reuse the generic decrypting loader over those URLs. */
    @Override
    protected ClassLoader createClassLoader(URL[] urls) throws Exception {
        return new XJarClassLoader(urls, getClass().getClassLoader(),
                xLauncher.xDecryptor, xLauncher.xEncryptor, xLauncher.xKey);
    }
}
