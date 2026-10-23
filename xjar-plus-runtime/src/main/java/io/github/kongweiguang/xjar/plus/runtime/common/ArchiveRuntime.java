/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.runtime.common;

import io.github.kongweiguang.xjar.plus.format.ArchiveIndex;
import io.github.kongweiguang.xjar.plus.format.ChunkCipher;
import io.github.kongweiguang.xjar.plus.format.EntryMetadata;
import io.github.kongweiguang.xjar.plus.format.IndexCodec;
import io.github.kongweiguang.xjar.plus.format.KeyEnvelope;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.JarURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.CRC32;

/**
 * 在公开任何受保护类或资源前认证归档控制数据。
 *
 * @author kongweiguang
 */
public final class ArchiveRuntime { // NOPMD - 聚合复杂度属于此内聚的归档安全边界。
    public static final String ENVELOPE_PATH = "META-INF/xjar-plus/envelope.bin";
    public static final String INDEX_PATH = "META-INF/xjar-plus/index.bin";
    public static final String ORIGINAL_MANIFEST_PATH = "META-INF/xjar-plus/original-manifest.mf";
    private static final String CURRENT_MANIFEST_PATH = "META-INF/MANIFEST.MF";
    private static final int MAX_ENVELOPE_BYTES = 256;
    private static final int MAX_INDEX_BYTES = 64 * 1024 * 1024 + 64;
    private static final int MAX_MANIFEST_BYTES = 8 * 1024 * 1024;
    private static final int MAX_CLASS_BYTES = 256 * 1024 * 1024;
    private static final String VERSION_ATTRIBUTE = "XJar-Plus-Version";
    private static final String START_CLASS_ATTRIBUTE = "Start-Class";

    private final String artifactId;
    private final byte[] dataKey;
    private final ArchiveIndex index;
    private final Manifest originalManifest;
    private final URL rootArchiveUrl;

    /**
     * 将解包后的密钥与已校验元数据绑定保存，避免 URL handler 使用不同归档的数据。
     */
    private ArchiveRuntime(String artifactId, byte[] dataKey, ArchiveIndex index,
                           Manifest originalManifest, URL rootArchiveUrl) {
        this.artifactId = artifactId;
        this.dataKey = dataKey;
        this.index = index;
        this.originalManifest = originalManifest;
        this.rootArchiveUrl = rootArchiveUrl;
    }

    /**
     * 框架启动前必须取得密钥信封、完整认证索引和受保护的原始 manifest。
     */
    public static ArchiveRuntime open(Class<?> launcherClass, String expectedLauncherClass)
            throws IOException {
        if (launcherClass == null || expectedLauncherClass == null || expectedLauncherClass.isEmpty()) {
            throw new IllegalArgumentException("launcher class and expected name are required");
        }
        final URL envelopeUrl = envelopeUrl(launcherClass);
        final URL rootArchiveUrl = rootArchiveUrl(envelopeUrl);
        try (KeyEnvelope envelope = parseEnvelope(readBounded(envelopeUrl, MAX_ENVELOPE_BYTES))) {
            final byte[] dataKey = RuntimeKeySource.resolve(envelope);
            return authenticateArchive(launcherClass, expectedLauncherClass, envelopeUrl, rootArchiveUrl,
                    envelope, dataKey);
        }
    }

    /**
     * 通过启动器加载器查找密钥信封，并在派生密钥前拒绝缺失的控制元数据。
     */
    private static URL envelopeUrl(Class<?> launcherClass) throws IOException {
        final URL envelopeUrl = launcherClass.getResource("/" + ENVELOPE_PATH);
        if (envelopeUrl == null) {
            throw new IOException("XJar Plus envelope is missing from the application archive");
        }
        return envelopeUrl;
    }

    /**
     * 先解析有长度上限的密钥信封再解包，避免损坏的控制字节触发无界 KDF 计算。
     */
    private static KeyEnvelope parseEnvelope(byte[] envelopeBytes) throws IOException {
        final KeyEnvelope envelope;
        try {
            envelope = KeyEnvelope.parse(envelopeBytes);
        } catch (IllegalArgumentException failure) {
            throw new IOException("XJar Plus envelope is invalid", failure);
        }
        return envelope;
    }

    /**
     * 先认证全部控制记录再公开归档状态，并在所有失败路径清除数据密钥。
     */
    private static ArchiveRuntime authenticateArchive(Class<?> launcherClass, String expectedLauncherClass,
                                                      URL envelopeUrl, URL rootArchiveUrl, KeyEnvelope envelope,
                                                      byte[] dataKey) throws IOException {
        try {
            final URL indexUrl = relative(envelopeUrl, "index.bin");
            final byte[] indexBytes = readBounded(indexUrl, MAX_INDEX_BYTES);
            final ArchiveIndex index = IndexCodec.open(indexBytes, dataKey, envelope.getArtifactId());
            final EntryMetadata manifestMetadata = index.get(ORIGINAL_MANIFEST_PATH);
            if (manifestMetadata == null || !manifestMetadata.isEncrypted()) {
                throw new IOException("Authenticated index does not protect the original manifest");
            }
            final URL originalManifestUrl = relative(envelopeUrl, "original-manifest.mf");
            final byte[] originalManifestBytes = decryptSmallEntry(originalManifestUrl, manifestMetadata,
                    envelope.getArtifactId(), dataKey, MAX_MANIFEST_BYTES);
            final Manifest originalManifest = new Manifest(new ByteArrayInputStream(originalManifestBytes));
            validateMainClasses(launcherClass, expectedLauncherClass, originalManifest);
            final ArchiveRuntime archive = new ArchiveRuntime(envelope.getArtifactId(), dataKey, index,
                    originalManifest, rootArchiveUrl);
            archive.verifyBootstrapEntries(launcherClass);
            return archive;
        } catch (GeneralSecurityException | RuntimeException | IOException failure) {
            Arrays.fill(dataKey, (byte) 0);
            if (failure instanceof IOException) {
                throw (IOException) failure;
            }
            throw new IOException("XJar Plus archive authentication failed", failure);
        }
    }

    /**
     * 保留先前打开数据流时的异常，同时释放在条目流之前取得的 JarFile。
     */
    private static void closeOwnedJarFile(JarFile jarFile, Throwable failure) {
        if (jarFile == null) {
            return;
        }
        try {
            jarFile.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    /**
     * 仅在有界分块解码器校验全部认证标签和声明长度后读取小型受保护控制文件。
     */
    private static byte[] decryptSmallEntry(URL url, EntryMetadata metadata, String artifactId,
                                            byte[] key, int limit)
            throws IOException, GeneralSecurityException {
        final byte[] encrypted = readBounded(url, limit + 1024);
        try (InputStream decrypted = ChunkCipher.decryptingStream(new ByteArrayInputStream(encrypted),
                key, artifactId, metadata.getPath(), metadata.getLength())) {
            final byte[] result = readBounded(decrypted, limit);
            verifyMetadata(result, metadata);
            return result;
        }
    }

    /**
     * 加载当前路由 manifest，并依据已认证的应用原始主类校验其内容。
     */
    private static void validateMainClasses(Class<?> launcherClass, String expectedLauncherClass,
                                            Manifest originalManifest) throws IOException {
        final Attributes originalAttributes = originalManifest.getMainAttributes();
        final String originalMainClass = originalMainClass(originalAttributes);
        final Manifest current = currentManifest(launcherClass);
        final Attributes currentAttributes = current.getMainAttributes();
        validateCurrentManifest(currentAttributes, expectedLauncherClass);
        validateFrameworkRouting(originalAttributes, currentAttributes, expectedLauncherClass, originalMainClass);
    }

    /**
     * 要求提供可执行的应用入口，避免归档元数据指定空的反射调用目标。
     */
    private static String originalMainClass(Attributes originalAttributes) throws IOException {
        final String originalMainClass = originalAttributes.getValue(Attributes.Name.MAIN_CLASS);
        if (originalMainClass == null || originalMainClass.trim().isEmpty()) {
            throw new IOException("Authenticated original manifest has no Main-Class");
        }
        return originalMainClass;
    }

    /**
     * 使用路由属性前，先从受保护的可执行 JAR 加载重写后的 manifest。
     */
    private static Manifest currentManifest(Class<?> launcherClass) throws IOException {
        final URL currentUrl = launcherClass.getResource("/" + CURRENT_MANIFEST_PATH);
        if (currentUrl == null) {
            throw new IOException("XJar Plus runtime manifest is missing");
        }
        try (InputStream input = currentUrl.openStream()) {
            return new Manifest(input);
        }
    }

    /**
     * 单独校验重写后的控制元数据，确保只有经过认证的框架路由信息进入加载器。
     */
    private static void validateCurrentManifest(Attributes attributes, String expectedLauncherClass)
            throws IOException {
        if (!"2".equals(attributes.getValue(VERSION_ATTRIBUTE))) {
            throw new IOException("Unsupported XJar Plus runtime manifest version");
        }
        if (!expectedLauncherClass.equals(attributes.getValue(Attributes.Name.MAIN_CLASS))) {
            throw new IOException("Runtime launcher does not match this archive family");
        }
    }

    /**
     * 保留框架原有入口契约；普通 JAR 仍使用其原始 Main-Class。
     */
    private static void validateFrameworkRouting(Attributes originalAttributes, Attributes currentAttributes,
                                                 String expectedLauncherClass, String originalMainClass)
            throws IOException {
        final String originalStartClass = originalAttributes.getValue(START_CLASS_ATTRIBUTE);
        final boolean plainLauncher = isPlainLauncher(expectedLauncherClass);
        final String currentStartClass = currentAttributes.getValue(START_CLASS_ATTRIBUTE);
        if (!plainLauncher && (originalStartClass == null || originalStartClass.trim().isEmpty())) {
            throw new IOException("Runtime Start-Class is missing from the framework manifest");
        }
        if (!plainLauncher && !originalStartClass.equals(currentStartClass)) {
            throw new IOException("Runtime Start-Class does not match the authenticated original manifest");
        }
        if (!plainLauncher && !nativeLauncherMatches(expectedLauncherClass, originalMainClass)) {
            throw new IOException("Original launcher does not match the selected archive family");
        }
    }

    /**
     * 精确识别唯一的独立启动器，避免根据类名后缀推断框架 Start-Class 语义。
     */
    private static boolean isPlainLauncher(String launcherClass) {
        return "io.github.kongweiguang.xjar.plus.runtime.XJarPlusLauncher".equals(launcherClass);
    }

    /**
     * 将适配器限制在受支持的框架加载器系列中，不静默接受自定义启动器。
     */
    private static boolean nativeLauncherMatches(String runtimeLauncher, String originalLauncher) {
        if (runtimeLauncher.endsWith(".runtime.boot.XJarPlusBootLauncher")) {
            return "org.springframework.boot.loader.JarLauncher".equals(originalLauncher)
                   || "org.springframework.boot.loader.launch.JarLauncher".equals(originalLauncher);
        }
        if (runtimeLauncher.endsWith(".runtime.boot.modern.XJarPlusBootLauncher")) {
            return "org.springframework.boot.loader.launch.JarLauncher".equals(originalLauncher);
        }
        if (runtimeLauncher.endsWith(".runtime.solon.XJarPlusSolonLauncher")) {
            return "org.noear.solon.loader.JarLauncher".equals(originalLauncher);
        }
        return false;
    }

    /**
     * 仅识别本运行时支持其独立 Start-Class 契约的原生可执行入口。
     */
    private static boolean isSupportedNativeLauncher(String launcherClass) {
        return "org.springframework.boot.loader.JarLauncher".equals(launcherClass)
               || "org.springframework.boot.loader.launch.JarLauncher".equals(launcherClass)
               || "org.noear.solon.loader.JarLauncher".equals(launcherClass);
    }

    /**
     * 仅识别注入后、与受保护应用共享前必须校验的包。
     */
    private static boolean isBootstrapPath(String path) {
        return path.startsWith("io/github/kongweiguang/xjar/plus/runtime/")
               || path.startsWith("io/github/kongweiguang/xjar/plus/format/");
    }

    /**
     * 对 file URL 解码 URI 后再比较，因为 JVM 归档 handler 可能混用原样 Unicode 和转义空格。
     */
    private static String normalizedFilePath(URL url) throws IOException {
        try {
            return url.toURI().normalize().getPath();
        } catch (java.net.URISyntaxException failure) {
            throw new IOException("Unable to normalize archive file URL", failure);
        }
    }

    /**
     * 仅接受已知 file 或 Boot 嵌套前缀，之后才将根路径片段作为归档标识。
     */
    private static boolean hasKnownArchivePrefix(String prefix) {
        return prefix.endsWith("file:") || prefix.endsWith("file:/")
               || prefix.endsWith("nested:") || prefix.endsWith("nested:/");
    }

    /**
     * 将相对于根归档的标识与选中的物理条目组合，不猜测有歧义的后缀。
     */
    private static String indexedPath(String archiveName, String physicalName) {
        if (archiveName.isEmpty()) {
            return physicalName;
        }
        if (archiveName.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
            return archiveName + "!/" + physicalName;
        }
        if (archiveName.endsWith("/classes")) {
            return archiveName + "/" + physicalName;
        }
        if (physicalName.startsWith(archiveName + "/")) {
            return physicalName;
        }
        return archiveName + "/" + physicalName;
    }

    /**
     * 去除加载器特有的尾部分隔符，使嵌套类路径根目录映射到稳定的格式路径。
     */
    private static String trimArchiveSeparators(String archiveName) {
        String result = archiveName;
        while (result.endsWith("!") || result.endsWith("/")) {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }

    /**
     * 在较新的运行时使用 JDK 选中的 multi-release 条目，同时保持 Java 8 源码兼容性。
     */
    private static String physicalName(JarEntry entry) throws IOException {
        final String classicBootPath = BootClassicEntryPath.physicalName(entry);
        if (classicBootPath != null) {
            return classicBootPath;
        }
        try {
        // 使用 JDK 公共 API 解析，因为框架或 JDK 的 JarEntry 实现类可能不可公开访问。
            final Method method = JarEntry.class.getMethod("getRealName");
            final Object result = method.invoke(entry);
            if (result instanceof String && !((String) result).isEmpty()) {
                return (String) result;
            }
        } catch (NoSuchMethodException ignored) {
        // Java 8 没有 getRealName；其 JAR 视图只选择基础物理条目。
        } catch (IllegalAccessException | InvocationTargetException failure) {
            throw new IOException("Unable to resolve the selected physical JAR entry", failure);
        }
        return entry.getName();
    }

    /**
     * 返回资源所属的代码来源 URL，不打开或接管其 JAR 文件。
     */
    private static URL resourceArchiveUrl(URL resource) {
        try {
            final URLConnection connection = resource.openConnection();
            return connection instanceof JarURLConnection
                    ? ((JarURLConnection) connection).getJarFileURL() : resource;
        } catch (IOException failure) {
            return resource;
        }
    }

    /**
     * 相对于精确归档 URL 查找同级根条目，并保留 Boot 和 Solon 的协议 handler。
     */
    private static URL relative(URL context, String path) throws MalformedURLException {
        return new URL(context, path);
    }

    /**
     * 管理每个临时 URL 连接，使控制文件读取能够关闭数据流，同时不改变加载器缓存策略。
     */
    private static byte[] readBounded(URL url, int limit) throws IOException {
        final URLConnection connection = url.openConnection();
        try (InputStream input = connection.getInputStream()) {
            return readBounded(input, limit);
        }
    }

    /**
     * 从控制条目定位根归档 URL，并拒绝在解压目录中运行。
     */
    private static URL rootArchiveUrl(URL envelopeUrl) throws IOException {
        final URLConnection connection = envelopeUrl.openConnection();
        if (!(connection instanceof JarURLConnection)) {
            throw new IOException("XJar Plus runtime requires a JAR archive entry");
        }
        return ((JarURLConnection) connection).getJarFileURL();
    }

    /**
     * 最多读取声明的安全上限，并兼容非空读取时合法返回 0 的数据流。
     */
    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        final ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(limit, 8192));
        final byte[] buffer = new byte[8192];
        int total = 0;
        int count;
        while ((count = input.read(buffer, 0, Math.min(buffer.length, limit - total + 1))) != -1) {
            if (count == 0) {
                final int one = input.read();
                if (one == -1) {
                    break;
                }
                output.write(one);
                total++;
            } else {
                total += count;
                output.write(buffer, 0, count);
            }
            if (total > limit) {
                throw new IOException("XJar Plus archive entry exceeds its size limit");
            }
        }
        return output.toByteArray();
    }

    /**
     * 完整读取后比较明文信息，保护元数据使用方；GCM 分块仍由各自的认证逻辑保护。
     */
    private static void verifyMetadata(byte[] plaintext, EntryMetadata metadata) throws IOException {
        if (plaintext.length != metadata.getLength()) {
            throw new IOException("Authenticated archive entry length does not match its index: " + metadata.getPath());
        }
        final CRC32 crc = new CRC32();
        crc.update(plaintext, 0, plaintext.length);
        if (crc.getValue() != metadata.getCrc32()) {
            throw new IOException("Authenticated archive entry CRC does not match its index: " + metadata.getPath());
        }
        final byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(plaintext);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
        if (!MessageDigest.isEqual(digest, metadata.getSha256())) {
            throw new IOException("Authenticated archive entry digest does not match its index: " + metadata.getPath());
        }
    }

    /**
     * 同时兼容 URL 转义形式和文件系统形式，因为 Boot 嵌套 URL 对同一文件可能使用任一种写法。
     */
    private static String[] filePathCandidates(URL url) {
        if (!"file".equalsIgnoreCase(url.getProtocol())) {
            return new String[0];
        }
        final String encodedPath = url.toExternalForm().substring("file:".length());
        try {
            final java.net.URI uri = url.toURI();
            final String uriPath = uri.getPath();
            final String absolutePath = new java.io.File(uri).getAbsolutePath().replace('\\', '/');
            if (encodedPath.equals(uriPath)) {
                return new String[]{encodedPath, absolutePath};
            }
            if (uriPath.equals(absolutePath)) {
                return new String[]{encodedPath, uriPath};
            }
            return new String[]{encodedPath, uriPath, absolutePath};
        } catch (Exception ignored) {
            return new String[]{encodedPath};
        }
    }

    /**
     * 仅在索引条目通过密文或明文完整性检查后返回其中的类。
     */
    public ClassBytes classBytes(URL resource, String logicalPath) throws IOException {
        final EntryMetadata metadata = metadata(resource, logicalPath);
        if (metadata == null) {
            return null;
        }
        final byte[] bytes = readEntry(resource, metadata, MAX_CLASS_BYTES);
        final URL codeSource = resourceArchiveUrl(resource);
        final Manifest manifest = resourceManifest(resource);
        return new ClassBytes(bytes, codeSource, manifest);
    }

    /**
     * 包装索引条目，使明文资源在被视为读至 EOF 前先完成元数据校验。
     */
    public URL wrap(URL resource, String logicalPath) {
        if (resource == null) {
            return null;
        }
        try {
            final URLConnection raw = resource.openConnection();
            if (raw instanceof DecryptingJarURLConnection) {
                // ClassLoader 公共查找会在 findResource 已包装子级结果后，再包装父级结果。
                return resource;
            }
            final EntryMetadata metadata = metadata(resource, logicalPath, raw);
            if (metadata == null) {
                if (hasIndexedDirectoryContent(resource)) {
                    return new URL(resource.getProtocol(), resource.getHost(), resource.getPort(),
                            resource.getFile(), new DecryptingUrlHandler(this, resource, null));
                }
                return resource;
            }
            return new URL(resource.getProtocol(), resource.getHost(), resource.getPort(),
                    resource.getFile(), new DecryptingUrlHandler(this, resource, metadata));
        } catch (IOException failure) {
            throw new IllegalStateException("Unable to resolve authenticated archive entry " + logicalPath, failure);
        }
    }

    /**
     * 解析精确物理路径；若受保护归档中的条目未编入索引则直接拒绝，不回退处理。
     */
    public EntryMetadata metadata(URL resource, String logicalPath) throws IOException {
        if (resource == null) {
            return null;
        }
        return metadata(resource, logicalPath, resource.openConnection());
    }

    /**
     * 从已打开的连接解析资源，避免幂等性检查重复创建 delegate。
     */
    private EntryMetadata metadata(URL resource, String logicalPath, URLConnection raw) throws IOException {
        if (!(raw instanceof JarURLConnection)) {
            return null;
        }
        final JarURLConnection connection = (JarURLConnection) raw;
        final String archiveName = archivePrefix(connection.getJarFileURL());
        if (archiveName == null) {
            return null;
        }
        return metadataForConnection(connection, archiveName, logicalPath);
    }

    /**
     * 解析一个 JAR 物理条目，只关闭非缓存探测所拥有的临时句柄。
     */
    private EntryMetadata metadataForConnection(JarURLConnection connection, String archiveName, String logicalPath)
            throws IOException {
        final JarFileHandle fileHandle = new JarFileHandle(connection);
        try {
            fileHandle.capture();
            final JarEntry entry = connection.getJarEntry();
            if (entry == null) {
                return missingEntryMetadata(connection, archiveName, logicalPath);
            }
            if (entry.isDirectory()) {
                return null;
            }
            return indexedEntryMetadata(archiveName, physicalName(entry), logicalPath);
        } finally {
            fileHandle.closeIfUncached();
        }
    }

    /**
     * 仅当认证索引证明目录下存在受保护条目时，才将归档中缺失的目录视为扫描根目录。
     */
    private EntryMetadata missingEntryMetadata(JarURLConnection connection, String archiveName, String logicalPath)
            throws IOException {
        final String entryName = connection.getEntryName();
        if (entryName == null || entryName.endsWith("/") || hasIndexedDescendant(archiveName, entryName)) {
            return null;
        }
        throw new IOException("Protected archive resource has no physical entry: " + logicalPath);
    }

    /**
     * 对 MR 和嵌套条目使用已选物理路径，仅对根条目允许逻辑路径后备匹配。
     */
    private EntryMetadata indexedEntryMetadata(String archiveName, String physicalName, String logicalPath)
            throws IOException {
        final String exactName = indexedPath(archiveName, physicalName);
        final EntryMetadata exact = index.get(exactName);
        if (exact != null) {
            return exact;
        }
        if (archiveName.isEmpty()) {
            final EntryMetadata logical = index.get(logicalPath);
            if (logical != null && physicalName.equals(logicalPath)) {
                return logical;
            }
        }
        throw new IOException("Protected archive entry is missing from the authenticated index: " + exactName);
    }

    /**
     * 仅当认证索引证明目录下存在受保护归档条目时才返回目录 URL。
     */
    private boolean hasIndexedDirectoryContent(URL resource) throws IOException {
        final URLConnection raw = resource.openConnection();
        if (!(raw instanceof JarURLConnection)) {
            return false;
        }
        final JarURLConnection connection = (JarURLConnection) raw;
        final String archiveName = archivePrefix(connection.getJarFileURL());
        if (archiveName == null) {
            return false;
        }
        final JarFileHandle fileHandle = new JarFileHandle(connection);
        try {
            fileHandle.capture();
            final JarEntry entry = connection.getJarEntry();
            if (entry != null && !entry.isDirectory()) {
                return false;
            }
            final String directoryName = entry == null
                    ? connection.getEntryName() : physicalName(entry);
            return hasIndexedDescendant(archiveName, directoryName);
        } finally {
            fileHandle.closeIfUncached();
        }
    }

    /**
     * 构建目录对应的精确索引前缀，避免将嵌套 JAR 与普通子目录混为一谈。
     */
    private boolean hasIndexedDescendant(String archiveName, String directoryName) {
        String normalizedDirectory = directoryName == null ? "" : directoryName;
        while (normalizedDirectory.endsWith("/")) {
            normalizedDirectory = normalizedDirectory.substring(0, normalizedDirectory.length() - 1);
        }
        final String prefix;
        if (archiveName.isEmpty()) {
            prefix = normalizedDirectory.isEmpty() ? "" : normalizedDirectory + "/";
        } else if (archiveName.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
            prefix = archiveName + "!/" + (normalizedDirectory.isEmpty() ? "" : normalizedDirectory + "/");
        } else {
            prefix = archiveName + "/" + (normalizedDirectory.isEmpty() ? "" : normalizedDirectory + "/");
        }
        for (EntryMetadata metadata : index.entries()) {
            if (metadata.getPath().startsWith(prefix) && !metadata.getPath().equals(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 通过原生归档 handler 解析继承而来的同协议 URL，并拒绝索引外或外部目标。
     */
    URLConnection relativeResourceConnection(URL exposedUrl, URL baseDelegateUrl) throws IOException {
        if (!baseDelegateUrl.getProtocol().equalsIgnoreCase(exposedUrl.getProtocol())) {
            throw new IOException("Protected archive resource changed URL protocols");
        }
        final URL resourceUrl;
        try {
        // 绝对同协议 URL 会继承归档所有者的 handler，其中也包括 Boot 嵌套 handler。
            resourceUrl = new URL(baseDelegateUrl, exposedUrl.toExternalForm());
        } catch (MalformedURLException failure) {
            throw new IOException("Unable to resolve protected relative resource " + exposedUrl, failure);
        }
        final URLConnection raw = resourceUrl.openConnection();
        if (!(raw instanceof JarURLConnection)) {
            throw new IOException("Protected archive relative resource is not a JAR URL: " + exposedUrl);
        }
        final JarURLConnection jarConnection = (JarURLConnection) raw;
        final String logicalPath = jarConnection.getEntryName() == null ? "" : jarConnection.getEntryName();
        final EntryMetadata metadata = metadata(resourceUrl, logicalPath);
        if (metadata == null && !hasIndexedDirectoryContent(resourceUrl)) {
            throw new IOException("Protected archive relative resource is not covered by the authenticated index: "
                                  + exposedUrl);
        }
        return new DecryptingJarURLConnection(exposedUrl, resourceUrl, this, metadata);
    }

    /**
     * 每次重新打开响应数据流，避免关闭一个非缓存条目后影响同一 URL 的后续读取。
     */
    public InputStream openPlaintext(JarURLConnection delegate, EntryMetadata metadata) throws IOException {
        if (!metadata.isEncrypted()) {
            final JarURLConnection validationConnection = freshDelegateConnection(delegate);
            final JarURLConnection responseConnection = freshDelegateConnection(delegate);
            verifyPlaintextBeforeExposure(validationConnection, metadata);
            return verifiedStream(openEntryStream(responseConnection), metadata);
        }
        final JarURLConnection responseConnection = freshDelegateConnection(delegate);
        return verifiedStream(openEntryStream(responseConnection), metadata);
    }

    /**
     * 重新打开精确归档 URL，使元数据探测和响应数据流分别管理自己的非缓存 JarFile 句柄。
     */
    JarURLConnection freshDelegateConnection(JarURLConnection delegate) throws IOException {
        final URLConnection fresh = delegate.getURL().openConnection();
        if (!(fresh instanceof JarURLConnection)) {
            throw new IOException("Protected archive resource no longer resolves to a JAR connection");
        }
        final JarURLConnection connection = (JarURLConnection) fresh;
        connection.setUseCaches(delegate.getUseCaches());
        connection.setAllowUserInteraction(delegate.getAllowUserInteraction());
        connection.setConnectTimeout(delegate.getConnectTimeout());
        connection.setReadTimeout(delegate.getReadTimeout());
        try {
            for (Map.Entry<String, List<String>> property : delegate.getRequestProperties().entrySet()) {
                for (String value : property.getValue()) {
                    connection.addRequestProperty(property.getKey(), value);
                }
            }
        } catch (IllegalStateException alreadyConnected) {
        // 已连接的只读 JAR URL 已固定对应条目，因此独立数据流无需覆盖请求设置。
            return connection;
        }
        return connection;
    }

    /**
     * 将非缓存 JarFile 的所有权移交给返回的数据流包装器，因为 URLConnection 不负责管理这组资源。
     */
    @SuppressWarnings("PMD.CloseResource")
    private InputStream openEntryStream(JarURLConnection connection) throws IOException {
        final JarFile ownedJarFile = connection.getUseCaches() ? null : connection.getJarFile();
        final InputStream source;
        try {
            source = connection.getInputStream();
        } catch (IOException | RuntimeException failure) {
            closeOwnedJarFile(ownedJarFile, failure);
            throw failure;
        }
        if (ownedJarFile == null) {
            return source;
        }
        return new FilterInputStream(source) {
            private boolean closed;

    /** 仅关闭一次条目流及其非缓存归档句柄，并保留关闭过程中的异常。 */
            @Override
            public void close() throws IOException {
                if (closed) {
                    return;
                }
                closed = true;
                IOException failure = null;
                try {
                    super.close();
                } catch (IOException closeFailure) {
                    failure = closeFailure;
                }
                try {
                    ownedJarFile.close();
                } catch (IOException closeFailure) {
                    if (failure == null) {
                        failure = closeFailure;
                    } else {
                        failure.addSuppressed(closeFailure);
                    }
                }
                if (failure != null) {
                    throw failure;
                }
            }
        };
    }

    /**
     * 以固定内存完整读取并校验明文条目，避免部分读取方在 SHA/CRC 校验前看到数据。
     */
    private void verifyPlaintextBeforeExposure(JarURLConnection delegate, EntryMetadata metadata) throws IOException {
        try (InputStream input = verifiedStream(openEntryStream(delegate), metadata)) {
            final byte[] buffer = new byte[8192];
            long remaining = metadata.getLength();
            while (remaining > 0) {
                final int requested = (int) Math.min((long) buffer.length, remaining);
                final int readCount = input.read(buffer, 0, requested);
                if (readCount < 0) {
                    break;
                }
                if (readCount == 0) {
                    if (input.read() < 0) {
                        break;
                    }
                    remaining--;
                } else {
                    remaining -= readCount;
                }
            }
            if (remaining == 0 && input.read() != -1) {
                throw new IOException("Plaintext archive entry exceeds its authenticated length: "
                                      + metadata.getPath());
            }
        }
    }

    /**
     * 提供已认证的源 manifest，供包元数据和原始包规则使用。
     */
    public Manifest packageManifest(URL resource) throws IOException {
        return resourceManifest(resource);
    }

    /**
     * 仅当加密字节符合认证索引中的所有字段后，才公开解码后的原始 manifest。
     */
    public Manifest originalManifest() {
        return originalManifest;
    }

    /**
     * 选择已认证的应用入口类，而不信任重写后的启动器 manifest。
     */
    public String applicationMainClass() {
        final Attributes attributes = originalManifest.getMainAttributes();
        final String originalLauncher = attributes.getValue(Attributes.Name.MAIN_CLASS);
        if (!isSupportedNativeLauncher(originalLauncher)) {
            return originalLauncher;
        }
        return attributes.getValue(START_CLASS_ATTRIBUTE);
    }

    /**
     * 启动前校验注入的 runtime 和 format 类，因为这些 bootstrap 包会与应用共享。
     */
    private void verifyBootstrapEntries(Class<?> launcherClass) throws IOException {
        verifyBootstrapLauncher(launcherClass);
        final byte[] buffer = new byte[8192];
        for (EntryMetadata metadata : index.entries()) {
            if (isBootstrapPath(metadata.getPath())) {
                verifyBootstrapEntry(launcherClass, metadata, buffer);
            }
        }
    }

    /**
     * 若启动器类被替换且缺少认证索引元数据，则阻止其进入共享 runtime 包。
     */
    private void verifyBootstrapLauncher(Class<?> launcherClass) throws IOException {
        final String launcherPath = launcherClass.getName().replace('.', '/') + ".class";
        final EntryMetadata launcherMetadata = index.get(launcherPath);
        if (launcherMetadata == null || launcherMetadata.isEncrypted()) {
            throw new IOException("Runtime launcher is not present as an indexed plaintext entry");
        }
    }

    /**
     * 完整读取一个索引中的 bootstrap 条目，在启动应用代码前校验其摘要。
     */
    private void verifyBootstrapEntry(Class<?> launcherClass, EntryMetadata metadata, byte[] buffer)
            throws IOException {
        final String path = metadata.getPath();
        if (metadata.isEncrypted()) {
            throw new IOException("Bootstrap runtime entry must remain plaintext: " + path);
        }
        final URL resource = launcherClass.getClassLoader().getResource(path);
        if (resource == null) {
            throw new IOException("Indexed bootstrap runtime entry is missing: " + path);
        }
        final URLConnection connection = resource.openConnection();
        if (!(connection instanceof JarURLConnection)) {
            throw new IOException("Bootstrap runtime entry is not in the executable JAR: " + path);
        }
        final EntryMetadata selected = metadata(resource, path);
        if (selected == null || !selected.getPath().equals(path)) {
            throw new IOException("Indexed bootstrap runtime entry resolves to another archive: " + path);
        }
        try (InputStream input = openPlaintext((JarURLConnection) connection, metadata)) {
            while (input.read(buffer) != -1) {
        // 使用固定大小缓冲区读完整个 bootstrap 负载，并在业务启动前完成校验。
            }
        }
    }

    /**
     * 完整读取条目，在定义类前校验其认证长度、CRC 和 SHA-256。
     */
    private byte[] readEntry(URL resource, EntryMetadata metadata, int limit) throws IOException {
        final URLConnection connection = resource.openConnection();
        if (!(connection instanceof JarURLConnection)) {
            throw new IOException("Encrypted archive entry does not use a JAR URL: " + metadata.getPath());
        }
        try (InputStream input = openPlaintext((JarURLConnection) connection, metadata)) {
            final byte[] bytes = readBounded(input, limit);
            verifyMetadata(bytes, metadata);
            return bytes;
        }
    }

    /**
     * 将 Boot 嵌套类路径 URL 映射回外层归档，同时拒绝名称相似的相邻归档路径。
     */
    private String archivePrefix(URL entryArchiveUrl) throws IOException {
        final String current = entryArchiveUrl.toExternalForm();
        final String root = rootArchiveUrl.toExternalForm();
        if (current.equals(root)) {
            return "";
        }
        if (sameFileArchive(entryArchiveUrl, rootArchiveUrl)) {
            return "";
        }
        final String[] prefixes = new String[]{"jar:" + root + "!/", root + "!/"};
        for (String prefix : prefixes) {
            if (current.startsWith(prefix)) {
                return trimArchiveSeparators(current.substring(prefix.length()));
            }
        }
        for (String rootPath : filePathCandidates(rootArchiveUrl)) {
            final int start = current.indexOf(rootPath);
            if (start >= 0 && hasKnownArchivePrefix(current.substring(0, start))) {
                String suffix = current.substring(start + rootPath.length());
                if (suffix.isEmpty()) {
                    return "";
                }
                if (suffix.startsWith("!/")) {
                    suffix = suffix.substring(2);
                } else if (suffix.startsWith("/!/")) {
                    suffix = suffix.substring(3);
                } else if (suffix.startsWith("/!")) {
                    suffix = suffix.substring(2);
                } else {
                    return null;
                }
                if (!suffix.isEmpty()) {
                    return trimArchiveSeparators(suffix);
                }
            }
        }
        return null;
    }

    /**
     * 识别 URI 等价的根 JAR，兼容 handler 对 Unicode 和空格采用不同转义方式。
     */
    private boolean sameFileArchive(URL candidate, URL root) throws IOException {
        return "file".equalsIgnoreCase(candidate.getProtocol())
               && "file".equalsIgnoreCase(root.getProtocol())
               && normalizedFilePath(candidate).equals(normalizedFilePath(root));
    }

    /**
     * 根类使用已认证的原始 manifest，嵌套类路径使用依赖归档自身的 manifest。
     */
    private Manifest resourceManifest(URL resource) throws IOException {
        final URLConnection connection = resource.openConnection();
        if (connection instanceof JarURLConnection) {
            final JarURLConnection jarConnection = (JarURLConnection) connection;
            final String archiveName = archivePrefix(jarConnection.getJarFileURL());
            if (archiveName != null && (archiveName.isEmpty() || archiveName.endsWith("/classes"))) {
                return originalManifest;
            }
            if (archiveName == null) {
                throw new IOException("Protected class resolved outside the authenticated archive");
            }
            return nestedManifest(jarConnection, archiveName);
        }
        throw new IOException("Protected class has no JAR manifest source");
    }

    /**
     * 通过原生 JarFile 句柄读取嵌套依赖的 manifest，并在用于包处理前完成认证。
     */
    private Manifest nestedManifest(JarURLConnection connection, String archiveName) throws IOException {
        final JarFileHandle fileHandle = new JarFileHandle(connection);
        try {
            fileHandle.capture();
            final JarEntry manifestEntry = fileHandle.jarFile.getJarEntry("META-INF/MANIFEST.MF");
            if (manifestEntry == null) {
                return new Manifest();
            }
            final String path = archiveName + "!/META-INF/MANIFEST.MF";
            final EntryMetadata metadata = index.get(path);
            if (metadata == null) {
                throw new IOException("Nested dependency manifest is missing from the authenticated index: " + path);
            }
            try (InputStream input = verifiedStream(fileHandle.jarFile.getInputStream(manifestEntry), metadata)) {
                return new Manifest(new ByteArrayInputStream(readBounded(input, MAX_MANIFEST_BYTES)));
            }
        } finally {
            fileHandle.closeIfUncached();
        }
    }

    /**
     * 构建分块释放前先认证、且最终摘要必须符合密封索引的数据流。
     */
    private InputStream verifiedStream(InputStream source, EntryMetadata metadata) throws IOException {
        try {
            final InputStream plaintext = metadata.isEncrypted()
                    ? ChunkCipher.decryptingStream(source, dataKey, artifactId,
                    metadata.getPath(), metadata.getLength()) : source;
            return new IntegrityInputStream(plaintext, metadata);
        } catch (IOException | RuntimeException failure) {
            try {
                source.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /**
     * 保存已完整认证的字节和源元数据，供子类使用原生加载器定义类。
     */
    public static final class ClassBytes {
        private final byte[] bytes;
        private final URL codeSource;
        private final Manifest manifest;

        /**
     * 复制已校验的字节，避免调用方在 defineClass 使用缓冲区前修改内容。
         */
        private ClassBytes(byte[] bytes, URL codeSource, Manifest manifest) {
            this.bytes = bytes.clone();
            this.codeSource = codeSource;
            this.manifest = manifest;
        }

        /**
     * 返回字节副本，确保 Java 类定义只使用已完成认证的数据。
         */
        public byte[] bytes() {
            return bytes.clone();
        }

        /**
     * 返回所属原始归档 URL，供 CodeSource 和 package sealing 判断使用。
         */
        public URL codeSource() {
            return codeSource;
        }

        /**
     * 返回源归档 manifest，供包规范和 sealing 属性使用。
         */
        public Manifest manifest() {
            return manifest;
        }
    }

    /**
     * 跟踪临时 URL 查询，以便关闭非缓存归档句柄而不影响加载器拥有的缓存。
     */
    private static final class JarFileHandle {
        private final JarURLConnection connection;
        private java.util.jar.JarFile jarFile;

        /**
     * 延迟到需要查找物理条目时再打开 ZIP，以遵循 URLConnection 的惰性连接契约。
         */
        private JarFileHandle(JarURLConnection connection) {
            this.connection = connection;
        }

        /**
     * 捕获条目查询打开的句柄，避免非缓存元数据探测泄漏文件描述符。
         */
        private void capture() throws IOException {
            jarFile = connection.getJarFile();
        }

        /**
     * 仅关闭临时的非缓存句柄；缓存句柄仍由框架类加载器管理。
         */
        private void closeIfUncached() throws IOException {
            if (!connection.getUseCaches() && jarFile != null) {
                jarFile.close();
            }
        }
    }

    /**
     * 在 EOF 校验条目长度、CRC 和 SHA-256，同时允许 Range 读取提前结束。
     */
    private static final class IntegrityInputStream extends java.io.FilterInputStream {
        private final EntryMetadata metadata;
        private final CRC32 crc = new CRC32();
        private final MessageDigest digest;
        private long count;
        private boolean verified;

        /**
     * 为每条数据流创建独立校验状态，避免并发资源共享可变摘要或 CRC 累加器。
         */
        private IntegrityInputStream(InputStream input, EntryMetadata metadata) {
            super(input);
            this.metadata = metadata;
            try {
                this.digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException impossible) {
                throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
            }
        }

        /**
     * 仅使用已通过 ChunkCipher 认证的字节更新完整性状态。
         */
        @Override
        public int read() throws IOException {
            final int value = super.read();
            if (value < 0) {
                verifyEnd();
            } else {
                count++;
                crc.update(value);
                digest.update((byte) value);
            }
            return value;
        }

        /**
     * 避免调用方交替使用单字节和缓冲区读取时重复计数。
         */
        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            final int readCount = super.read(bytes, offset, length);
            if (readCount < 0) {
                verifyEnd();
            } else if (readCount > 0) {
                update(bytes, offset, readCount);
            }
            return readCount;
        }

        /**
     * 解码并计入跳过的明文，使 Range 请求保留精确偏移并能执行 EOF 校验。
         */
        @Override
        public long skip(long amount) throws IOException {
            if (amount <= 0) {
                return 0;
            }
            final byte[] buffer = new byte[(int) Math.min(8192, amount)];
            long skipped = 0;
            while (skipped < amount) {
                final int read = read(buffer, 0, (int) Math.min(buffer.length, amount - skipped));
                if (read < 0) {
                    break;
                }
                skipped += read;
            }
            return skipped;
        }

        /**
     * 累积元数据时不在调用方请求的缓冲区之外保留明文。
         */
        private void update(byte[] bytes, int offset, int length) {
            count += length;
            crc.update(bytes, offset, length);
            digest.update(bytes, offset, length);
        }

        /**
     * 在 EOF 拒绝与认证信息不符的数据，并避免 EOF 后重复读取时重复校验。
         */
        private void verifyEnd() throws IOException {
            if (verified) {
                return;
            }
            if (count != metadata.getLength() || crc.getValue() != metadata.getCrc32()
                || !MessageDigest.isEqual(digest.digest(), metadata.getSha256())) {
                throw new IOException("Authenticated archive entry metadata does not match: " + metadata.getPath());
            }
            verified = true;
        }
    }
}
