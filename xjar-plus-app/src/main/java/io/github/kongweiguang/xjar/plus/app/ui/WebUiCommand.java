/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.ui;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.awt.Desktop;
import java.net.URI;
import java.util.concurrent.Callable;

/**
 * 仅在用户显式选择 ui 命令后启动本地界面。
 */
@Command(name = "ui", mixinStandardHelpOptions = true, description = "启动本机 Web 界面。")
public final class WebUiCommand implements Callable<Integer> {
    @Option(names = "--port", defaultValue = "0", description = "监听端口；0 表示由系统分配空闲端口。")
    private int port;

    @Option(names = "--no-browser", description = "只打印地址，不自动打开浏览器。")
    private boolean noBrowser;

    /**
     * 桌面浏览可用时打开用户显式请求的本地页面，然后等待服务关闭。
     */
    @Override
    public Integer call() throws Exception {
        if (port < 0 || port > 65_535) {
            throw new IllegalArgumentException("端口必须在 0 到 65535 之间。");
        }
        try (WebUiServer server = new WebUiServer(port)) {
            server.start();
            final URI uri = server.origin();
            System.out.println("本地界面已启动：" + uri + "/");
            System.out.println("仅监听本机地址；按 Ctrl+C 关闭服务。");
            if (!noBrowser && Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                try {
                    Desktop.getDesktop().browse(uri);
                } catch (java.io.IOException e) {
                    System.err.println("无法自动打开浏览器，请复制上方地址访问。");
                }
            }
            server.await();
        }
        return 0;
    }
}
