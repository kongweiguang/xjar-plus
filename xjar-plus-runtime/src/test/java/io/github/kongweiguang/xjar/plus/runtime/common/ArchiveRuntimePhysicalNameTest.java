/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.runtime.common;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.jar.JarEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证即使条目实现类不可公开访问，仍可通过 JDK 公共 API 查找物理 MR 条目。
 *
 * @author kongweiguang
 */
class ArchiveRuntimePhysicalNameTest {

    /**
     * 使用私有 JarEntry 子类模拟 Boot 的 package-private NestedJarEntry 访问边界。
     */
    @Test
    void resolvesPhysicalNameViaPublicJarEntryMethod() throws Exception {
        final String physicalPath = "META-INF/versions/17/demo/Probe.class";
        final JarEntry entry = new HiddenJarEntry(physicalPath);
        final Method physicalName = ArchiveRuntime.class.getDeclaredMethod("physicalName", JarEntry.class);
        physicalName.setAccessible(true);

        assertEquals(physicalPath, physicalName.invoke(null, entry));
    }

    /**
     * 模拟由加载器持有的 package-private 条目；通过其声明类无法调用其 public 覆盖方法。
     */
    private static final class HiddenJarEntry extends JarEntry {
        private final String realName;

        /**
     * 使选中的物理路径与包装器逻辑名称保持区分。
         */
        private HiddenJarEntry(String realName) {
            super(realName);
            this.realName = realName;
        }

        /**
     * 提供 Java 9+ 物理名称 API 的测试实现，同时不要求测试源码高于 Java 8 基线。
         */
        public String getRealName() {
            return realName;
        }
    }
}
