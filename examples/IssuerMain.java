/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.examples;

import io.github.kongweiguang.xjar.plus.api.XJarPlus;

import java.nio.file.Path;

/**
 * 将发布者凭据的一次性初始化与可重复执行的应用构建分开。
 */
public final class IssuerMain {
    /**
     * 禁止构造示例对象，避免将凭据保留在实例状态中。
     */
    private IssuerMain() {
    }

    /**
     * 通过统一接口拒绝已存在的输出，避免重试时静默替换应用的发布者凭据。
     */
    public static void main(final String[] args) throws Exception {
        if (args.length != 2) {
            throw new IllegalArgumentException("Usage: IssuerMain <app-id> <new-private-issuer-file>");
        }
        System.out.println(XJarPlus.createIssuer(Path.of(args[1]), args[0]).output());
    }
}
