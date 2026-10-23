/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.runtime.common;

import io.github.kongweiguang.xjar.plus.format.EntryMetadata;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.security.Permission;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * 保留 JAR URL 行为，同时将密文元数据和数据流替换为索引中的明文信息。
 *
 * @author kongweiguang
 */
final class DecryptingJarURLConnection extends JarURLConnection {
    private final JarURLConnection delegate;
    private final ArchiveRuntime archive;
    private final EntryMetadata metadata;
    private final ThreadLocal<ContentAccess> contentAccess = new ThreadLocal<ContentAccess>();
    private volatile Map<String, List<String>> headerFields;

    /**
     * 使用原协议创建的连接，使 Boot 和 Solon 继续管理嵌套归档及其缓存。
     */
    DecryptingJarURLConnection(URL url, URL delegateUrl, ArchiveRuntime archive,
                               EntryMetadata metadata) throws IOException {
        super(url);
        final URLConnection connection = delegateUrl.openConnection();
        if (!(connection instanceof JarURLConnection)) {
            throw new IOException("Encrypted resource no longer resolves to a JAR connection");
        }
        this.delegate = (JarURLConnection) connection;
        this.archive = archive;
        this.metadata = metadata;
    }

    /**
     * 将条目时间格式化为 HTTP 日期，不依赖 delegate header 解析或共享 formatter 状态。
     */
    private static String formatHttpDate(long epochMillis) {
        return DateTimeFormatter.RFC_1123_DATE_TIME.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneOffset.UTC));
    }

    /**
     * 通过归档自身的 handler 建立连接，以保留协议校验逻辑和缓存所有权。
     */
    @Override
    public void connect() throws IOException {
        delegate.connect();
        connected = true;
    }

    /**
     * 返回 delegate 的原始 URL，使调用方得到归档标识，而非合成 handler 的标识。
     */
    @Override
    public URL getURL() {
        return delegate.getURL();
    }

    /**
     * 返回明文长度；超出 int API 表示范围时保留 URLConnection 的 -1 哨兵值。
     */
    @Override
    public int getContentLength() {
        if (metadata == null) {
            return -1;
        }
        return metadata.getLength() <= Integer.MAX_VALUE ? (int) metadata.getLength() : -1;
    }

    /**
     * 返回已认证的原始长度，而不是密文分块头和 GCM 标签所占长度。
     */
    @Override
    public long getContentLengthLong() {
        return metadata == null ? -1L : metadata.getLength();
    }

    /**
     * 根据条目名推断资源类型，避免查询时打开 delegate 隐藏的文件头数据流。
     */
    @Override
    public String getContentType() {
        return URLConnection.guessContentTypeFromName(delegate.getEntryName());
    }

    /**
     * JAR 条目字节没有 HTTP 传输编码；同时避免 delegate 查询泄漏数据流，因此报告无传输编码。
     */
    @Override
    public String getContentEncoding() {
        return null;
    }

    /**
     * 本地归档条目没有响应过期元数据，因此报告过期时间未知。
     */
    @Override
    public long getExpiration() {
        return 0L;
    }

    /**
     * 本地归档条目不携带 HTTP Date header，因此报告响应日期未知。
     */
    @Override
    public long getDate() {
        return 0L;
    }

    /**
     * 通过可关闭的查询获取条目时间，避免打开 delegate 的文件头信息流。
     */
    @Override
    public long getLastModified() {
        try {
            final JarEntry entry = getJarEntry();
            return entry == null || entry.getTime() < 0L ? 0L : entry.getTime();
        } catch (IOException failure) {
            return 0L;
        }
    }

    /**
     * 仅公开根据认证元数据计算的本地资源 header，避免打开 JDK 隐藏的文件数据流。
     */
    @Override
    public String getHeaderField(String name) {
        if (name == null) {
            return null;
        }
        for (Map.Entry<String, List<String>> field : getHeaderFields().entrySet()) {
            if (name.equalsIgnoreCase(field.getKey())) {
                return field.getValue().isEmpty() ? null : field.getValue().get(0);
            }
        }
        return null;
    }

    /**
     * 返回缓存的不可变资源 header 快照，不调用会泄漏文件句柄的 JAR delegate 代码。
     */
    @Override
    public Map<String, List<String>> getHeaderFields() {
        Map<String, List<String>> snapshot = headerFields;
        if (snapshot == null) {
            synchronized (this) {
                snapshot = headerFields;
                if (snapshot == null) {
                    snapshot = createHeaderFields();
                    headerFields = snapshot;
                }
            }
        }
        return snapshot;
    }

    /**
     * 资源 header 只根据归档条目信息构建；JDK delegate 的文件头查询会在 Windows 泄漏未受管理的数据流，因此快照不包含 JAR URL 本就没有的传输层 header。
     */
    private Map<String, List<String>> createHeaderFields() {
        final Map<String, List<String>> fields = new LinkedHashMap<String, List<String>>();
        final String contentType = getContentType();
        if (contentType != null) {
            fields.put("content-type", Collections.singletonList(contentType));
        }
        final long lastModified = getLastModified();
        if (lastModified > 0L) {
            fields.put("last-modified", Collections.singletonList(formatHttpDate(lastModified)));
        }
        if (metadata != null) {
            fields.put("content-length", Collections.singletonList(Long.toString(metadata.getLength())));
        }
        return Collections.unmodifiableMap(fields);
    }

    /**
     * 从与按名称查询相同的脱离式视图读取索引 header 值，避免泄漏密文长度。
     */
    @Override
    public String getHeaderField(int index) {
        final Map.Entry<String, String> header = headerAt(index);
        return header == null ? null : header.getValue();
    }

    /**
     * 从与按名称查询相同的脱离式视图读取索引 header 名称，避免混用不同归档快照。
     */
    @Override
    public String getHeaderFieldKey(int index) {
        final Map.Entry<String, String> header = headerAt(index);
        return header == null ? null : header.getKey();
    }

    /**
     * 将类型转换交由 URLConnection，使格式错误或溢出时保留调用方指定的默认值。
     */
    @Override
    public int getHeaderFieldInt(String name, int defaultValue) {
        return super.getHeaderFieldInt(name, defaultValue);
    }

    /**
     * 保留 URLConnection 的类型转换行为，同时由虚拟 header 查询提供明文长度。
     */
    @Override
    public long getHeaderFieldLong(String name, long defaultValue) {
        return super.getHeaderFieldLong(name, defaultValue);
    }

    /**
     * 保留 URLConnection 的日期转换行为，同时由虚拟查询仅读取安全的本地 header 快照。
     */
    @Override
    public long getHeaderFieldDate(String name, long defaultValue) {
        return super.getHeaderFieldDate(name, defaultValue);
    }

    /**
     * 将 URLConnection 的初始探测与 content handler 共享，因为每次受保护数据流都是新建的。
     */
    @Override
    public Object getContent() throws IOException {
        return withContentAccess(() -> super.getContent());
    }

    /**
     * 与指定类型的 content handler 共享探测结果，避免选择 handler 后遗留未缓存的归档数据流。
     */
    @Override
    @SuppressWarnings("rawtypes")
    public Object getContent(Class[] classes) throws IOException {
        return withContentAccess(() -> super.getContent(classes));
    }

    /**
     * 保留 URL 安全检查使用的原归档权限。
     */
    @Override
    public Permission getPermission() throws IOException {
        return delegate.getPermission();
    }

    /**
     * 独立打开并认证直接读取；仅复用当前 content 查询作用域内的数据流。
     */
    @Override
    public InputStream getInputStream() throws IOException {
        if (metadata == null) {
            throw new IOException("Protected archive directory has no byte stream");
        }
        final ContentAccess activeAccess = contentAccess.get();
        if (activeAccess == null) {
            return archive.openPlaintext(delegate, metadata);
        }
        if (activeAccess.inputStream == null) {
            activeAccess.inputStream = archive.openPlaintext(delegate, metadata);
        }
        return activeAccess.inputStream;
    }

    /**
     * 仅在 content handler 调用期间共享 URLConnection 数据流，并在 handler 初始化失败时关闭数据流。
     */
    private Object withContentAccess(ContentResolver resolver) throws IOException {
        final ContentAccess previous = contentAccess.get();
        final ContentAccess access = previous == null ? new ContentAccess() : previous;
        contentAccess.set(access);
        try {
            return resolver.resolve();
        } catch (IOException | RuntimeException failure) {
            if (previous == null && access.inputStream != null) {
                try {
                    access.inputStream.close();
                } catch (IOException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        } finally {
            if (previous == null) {
                contentAccess.remove();
            } else {
                contentAccess.set(previous);
            }
        }
    }

    /**
     * 加密资源 URL 只读，不得向调用方暴露输出路径。
     */
    @Override
    public OutputStream getOutputStream() throws IOException {
        throw new IOException("XJar Plus archive resources are read-only");
    }

    /**
     * 为需要检查相邻条目的 JarURLConnection 调用方保留 delegate 的归档句柄。
     */
    @Override
    public JarFile getJarFile() throws IOException {
        if (metadata == null) {
            return delegate.getJarFile();
        }
        return delegate.getJarFile();
    }

    /**
     * 返回归档自身的 URL，因为权限、URL 和相对资源链接仍以它为来源。
     */
    @Override
    public URL getJarFileURL() {
        return delegate.getJarFileURL();
    }

    /**
     * 保留源加载器选中的物理条目路径，包括 multi-release 归档中的版本路径。
     */
    @Override
    public String getEntryName() {
        return delegate.getEntryName();
    }

    /**
     * 返回脱离式元数据，并关闭临时的非缓存查询，不修改共享 ZIP 条目状态。
     */
    @Override
    public JarEntry getJarEntry() throws IOException {
        if (metadata == null) {
            return delegate.getJarEntry();
        }
        final JarURLConnection metadataConnection = archive.freshDelegateConnection(delegate);
        try {
            final JarEntry original = metadataConnection.getJarEntry();
            if (original == null) {
                return null;
            }
            original.getMethod();
            final JarEntry plaintext = new JarEntry(original);
            plaintext.setSize(metadata.getLength());
            plaintext.setCrc(metadata.getCrc32());
            plaintext.setCompressedSize(-1);
            return plaintext;
        } finally {
            if (!metadataConnection.getUseCaches()) {
                metadataConnection.getJarFile().close();
            }
        }
    }

    /**
     * 使资源包元数据继续关联原归档 manifest。
     */
    @Override
    public Attributes getAttributes() throws IOException {
        return delegate.getAttributes();
    }

    /**
     * 保留框架读取的、当前嵌套资源所属归档的 manifest。
     */
    @Override
    public Manifest getManifest() throws IOException {
        return delegate.getManifest();
    }

    /**
     * 从 delegate 返回调用方的缓存策略，而不读取合成连接中未使用的状态。
     */
    @Override
    public boolean getUseCaches() {
        return delegate.getUseCaches();
    }

    /**
     * 使调用方缓存策略与底层自定义 JAR 协议的资源所有权保持一致。
     */
    @Override
    public void setUseCaches(boolean useCaches) {
        delegate.setUseCaches(useCaches);
    }

    /**
     * 返回实际归档连接使用的超时时间。
     */
    @Override
    public int getConnectTimeout() {
        return delegate.getConnectTimeout();
    }

    /**
     * 将调用方设置的连接超时应用于实际嵌套归档协议实现。
     */
    @Override
    public void setConnectTimeout(int timeout) {
        delegate.setConnectTimeout(timeout);
    }

    /**
     * 返回实际加密条目读取使用的超时时间。
     */
    @Override
    public int getReadTimeout() {
        return delegate.getReadTimeout();
    }

    /**
     * 将调用方设置的读取超时应用于实际嵌套归档协议实现。
     */
    @Override
    public void setReadTimeout(int timeout) {
        delegate.setReadTimeout(timeout);
    }

    /**
     * 按 delegate 的迭代顺序构建索引 header 快照，其中状态行允许为空。
     */
    private Map.Entry<String, String> headerAt(int requestedIndex) {
        if (requestedIndex < 0) {
            return null;
        }
        int current = 0;
        for (Map.Entry<String, List<String>> field : getHeaderFields().entrySet()) {
            for (String value : field.getValue()) {
                if (current++ == requestedIndex) {
                    return new java.util.AbstractMap.SimpleImmutableEntry<String, String>(field.getKey(), value);
                }
            }
        }
        return null;
    }

    /**
     * 将 content handler 的受检 I/O 异常继续传递给 URLConnection 调用方。
     */
    @FunctionalInterface
    private interface ContentResolver {
        Object resolve() throws IOException;
    }

    /**
     * 保存由 URLConnection 探测和选定 content handler 共用的响应数据流。
     */
    private static final class ContentAccess {
        private InputStream inputStream;
    }
}
