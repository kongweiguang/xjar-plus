/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.runtime.common;

import java.net.URL;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * 提取普通、Boot 和 Solon 类加载器共用的包元数据，但不接管这些加载器的所有权。
 *
 * @author kongweiguang
 */
public final class ClassLoaderSupport {
    /**
     * 避免实例化；包策略应从各自经过认证的归档 manifest 中派生。
     */
    private ClassLoaderSupport() {
    }

    /**
     * 若存在包专属段则读取其中属性，否则使用 manifest 主段的属性。
     */
    public static PackageMetadata packageMetadata(Manifest manifest, String packageName) {
        final Attributes main = manifest.getMainAttributes();
        final Attributes section = manifest.getAttributes(packageName.replace('.', '/') + "/");
        return new PackageMetadata(value(section, main, Attributes.Name.SPECIFICATION_TITLE),
                value(section, main, Attributes.Name.SPECIFICATION_VERSION),
                value(section, main, Attributes.Name.SPECIFICATION_VENDOR),
                value(section, main, Attributes.Name.IMPLEMENTATION_TITLE),
                value(section, main, Attributes.Name.IMPLEMENTATION_VERSION),
                value(section, main, Attributes.Name.IMPLEMENTATION_VENDOR),
                "true".equalsIgnoreCase(value(section, main, Attributes.Name.SEALED)));
    }

    /**
     * 应用原生 URL 加载器在跨归档定义类时执行的 JAR sealing 规则。
     */
    public static void verifyPackageSealing(Package existing, boolean requestedSealed, URL codeSource) {
        if (existing == null) {
            return;
        }
        if (existing.isSealed() && !existing.isSealed(codeSource)) {
            throw new SecurityException("Sealing violation for package " + existing.getName());
        }
        if (!existing.isSealed() && requestedSealed) {
            throw new SecurityException("Cannot seal already loaded package " + existing.getName());
        }
    }

    /**
     * 优先应用包级属性，再应用归档默认值，以保持 sealing 和版本元数据语义一致。
     */
    private static String value(Attributes section, Attributes main, Attributes.Name name) {
        final String value = section == null ? null : section.getValue(name);
        return value == null ? main.getValue(name) : value;
    }

    /**
     * 保存包属性，供加载器子类调用受保护的 definePackage 实现。
     */
    public static final class PackageMetadata {
        private final String specificationTitle;
        private final String specificationVersion;
        private final String specificationVendor;
        private final String implementationTitle;
        private final String implementationVersion;
        private final String implementationVendor;
        private final boolean sealed;

        /**
     * 将 manifest 值保存为不可变数据，因为类定义由框架加载器子类负责。
         */
        private PackageMetadata(String specificationTitle, String specificationVersion, String specificationVendor,
                                String implementationTitle, String implementationVersion,
                                String implementationVendor, boolean sealed) {
            this.specificationTitle = specificationTitle;
            this.specificationVersion = specificationVersion;
            this.specificationVendor = specificationVendor;
            this.implementationTitle = implementationTitle;
            this.implementationVersion = implementationVersion;
            this.implementationVendor = implementationVendor;
            this.sealed = sealed;
        }

        /**
     * 从已认证的 manifest 返回包的 specification title。
         */
        public String specificationTitle() {
            return specificationTitle;
        }

        /**
     * 从已认证的 manifest 返回包的 specification version。
         */
        public String specificationVersion() {
            return specificationVersion;
        }

        /**
     * 从已认证的 manifest 返回包的 specification vendor。
         */
        public String specificationVendor() {
            return specificationVendor;
        }

        /**
     * 从已认证的 manifest 返回包的 implementation title。
         */
        public String implementationTitle() {
            return implementationTitle;
        }

        /**
     * 从已认证的 manifest 返回包的 implementation version。
         */
        public String implementationVersion() {
            return implementationVersion;
        }

        /**
     * 从已认证的 manifest 返回包的 implementation vendor。
         */
        public String implementationVendor() {
            return implementationVendor;
        }

        /**
     * 指示 CodeSource 是否必须将此包 sealing 到其所属归档。
         */
        public boolean sealed() {
            return sealed;
        }
    }
}
