package fixture.plain;

/**
 * 提供 Java 8 基础实现，仅允许兼容的较新运行时通过版本条目替换它。
 *
 * @author kongweiguang
 */
public final class MultiReleaseProbe {
    /**
     * 通过静态类加载观察版本选择，无需创建工具类实例。
     */
    private MultiReleaseProbe() {
    }

    /**
     * 通过返回值识别基础实现是否被选中，不依赖对归档条目的静态检查。
     */
    public static String version() {
        return "base-java8";
    }
}
