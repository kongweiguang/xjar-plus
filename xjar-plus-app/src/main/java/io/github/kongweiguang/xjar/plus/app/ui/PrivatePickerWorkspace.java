/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.ui;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;

/**
 * 仅在新建的当前用户私有 Windows 目录中保存固定选择器辅助程序源码。
 */
final class PrivatePickerWorkspace implements AutoCloseable {
    private static final int MAX_SOURCE_BYTES = 32 * 1024;
    private static final int MAX_SCRIPT_BYTES = 8 * 1024;
    private static final String SOURCE_RESOURCE = "/windows-picker/NativeFileDialog.cs";
    private static final String SCRIPT_RESOURCE = "/windows-picker/picker.ps1";
    private final Path directory;
    private final Path source;
    private final Path script;
    private boolean closed;

    /**
     * 在当前账户 ACL 保护下原子创建辅助文件，避免其他账户读取。
     */
    private PrivatePickerWorkspace(Path directory) {
        this.directory = directory;
        source = directory.resolve("NativeFileDialog.cs");
        script = directory.resolve("picker.ps1");
    }

    /**
     * 创建临时工作区；ACL 或资源校验失败时清除不完整产物。
     */
    static PrivatePickerWorkspace create() throws IOException {
        final Path temporaryRoot = Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize();
        final AclFileAttributeView rootAcl = Files.getFileAttributeView(temporaryRoot, AclFileAttributeView.class);
        if (rootAcl == null) {
            throw new IOException("Windows 临时目录不支持 ACL，已拒绝以不安全权限写入系统选择器 helper。");
        }
        final UserPrincipal owner = temporaryRoot.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(System.getProperty("user.name"));
        final List<AclEntry> ownerAcl = ownerOnlyAcl(owner);
        final Path directory = Files.createTempDirectory(temporaryRoot, "xjp-picker-", aclAttribute(ownerAcl));
        final PrivatePickerWorkspace workspace = new PrivatePickerWorkspace(directory);
        try {
            workspace.writeResource(SOURCE_RESOURCE, workspace.source, MAX_SOURCE_BYTES);
            workspace.writeResource(SCRIPT_RESOURCE, workspace.script, MAX_SCRIPT_BYTES);
            workspace.verifyOwnerOnly(owner);
            return workspace;
        } catch (IOException | RuntimeException exception) {
            try {
                workspace.close();
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    /**
     * 仅向私有选择器工作区所属账户授予继承的完全访问权限。
     */
    private static List<AclEntry> ownerOnlyAcl(UserPrincipal owner) {
        final AclEntry ownerEntry = AclEntry.newBuilder()
                .setType(AclEntryType.ALLOW)
                .setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                .setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT)
                .build();
        return List.of(ownerEntry);
    }

    /**
     * 将 ACL 作为原子创建属性应用，避免目录短暂处于开放权限的竞态。
     */
    private static FileAttribute<List<AclEntry>> aclAttribute(List<AclEntry> entries) {
        return new FileAttribute<>() {
            @Override
            public String name() {
                return "acl:acl";
            }

            @Override
            public List<AclEntry> value() {
                return entries;
            }
        };
    }

    /**
     * 仅暴露 ProcessBuilder 参数数组所需的生成路径。
     */
    Path script() {
        return script;
    }

    /**
     * 暴露当前随机工作区路径，使生命周期测试无需扫描临时目录即可验证清理。
     */
    Path directory() {
        return directory;
    }

    /**
     * 私有目录 ACL 原子生效后，再复制一个有界 classpath 资源。
     */
    private void writeResource(String resourceName, Path destination, int maxBytes) throws IOException {
        try (InputStream input = PrivatePickerWorkspace.class.getResourceAsStream(resourceName)) {
            if (input == null) {
                throw new IOException("系统选择器 helper 资源缺失：" + resourceName);
            }
            final byte[] bytes = input.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) {
                throw new IOException("系统选择器 helper 资源超过大小限制：" + resourceName);
            }
            Files.write(destination, bytes);
        }
    }

    /**
     * 检查目录和文件的实际 ACL，不假设文件系统提供程序遵循了创建属性。
     */
    private void verifyOwnerOnly(UserPrincipal owner) throws IOException {
        verifyAcl(directory, owner);
        verifyAcl(source, owner);
        verifyAcl(script, owner);
    }

    /**
     * 拒绝任何会向其他主体暴露辅助程序内容的继承或显式 ACE。
     */
    private void verifyAcl(Path path, UserPrincipal owner) throws IOException {
        final AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
        if (view == null) {
            throw new IOException("Windows 临时 helper 不支持 ACL：" + path.getFileName());
        }
        final List<AclEntry> entries = view.getAcl();
        if (entries.isEmpty() || entries.stream().anyMatch(entry -> entry.type() != AclEntryType.ALLOW
                                                                    || !entry.principal().equals(owner))) {
            throw new IOException("Windows 临时 helper ACL 不是当前用户专用：" + path.getFileName());
        }
    }

    /**
     * 进程完成后仅删除两个已知辅助文件及其私有随机目录。
     */
    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        Files.deleteIfExists(script);
        Files.deleteIfExists(source);
        Files.deleteIfExists(directory);
    }
}
