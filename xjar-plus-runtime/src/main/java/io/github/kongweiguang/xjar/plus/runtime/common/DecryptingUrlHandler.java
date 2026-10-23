/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.runtime.common;

import io.github.kongweiguang.xjar.plus.format.EntryMetadata;

import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;

/**
 * 为现有 JAR URL 添加认证视图，而不替换归档原有的协议实现。
 *
 * @author kongweiguang
 */
final class DecryptingUrlHandler extends URLStreamHandler {
    private final ArchiveRuntime archive;
    private final URL delegateUrl;
    private final EntryMetadata metadata;

    /**
     * 保留原始 URL 和不可变索引条目，使嵌套协议仍交由其所有者处理。
     */
    DecryptingUrlHandler(ArchiveRuntime archive, URL delegateUrl, EntryMetadata metadata) {
        this.archive = archive;
        this.delegateUrl = delegateUrl;
        this.metadata = metadata;
    }

    /**
     * 仅对完全相同的 URL 复用绑定元数据，因为继承的 handler 也可能收到相邻资源 URL。
     */
    @Override
    protected URLConnection openConnection(URL url) throws IOException {
        if (url.toExternalForm().equals(delegateUrl.toExternalForm())) {
        // 只有完全相同的 URL 才能复用已捕获的索引记录；继承而来的相邻 URL 必须重新查询。
            return new DecryptingJarURLConnection(url, delegateUrl, archive, metadata);
        }
        return archive.relativeResourceConnection(url, delegateUrl);
    }
}
