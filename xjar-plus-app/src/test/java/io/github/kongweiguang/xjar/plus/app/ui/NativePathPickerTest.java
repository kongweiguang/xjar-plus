/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.ui;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.UserPrincipal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativePathPickerTest {
    /**
     * 校验直接调用 COM 对话框的契约，但不打开原生窗口或抢占桌面焦点。
     */
    @Test
    void windowsDialogUsesDirectComCommonDialog() throws IOException {
        final NativePathPicker picker = new NativePathPicker();
        final String script = readResource("/windows-picker/picker.ps1");
        final List<String> fileArgs = picker.powerShellArguments("file", Path.of("C:\\xjp-picker\\picker.ps1"));
        final List<String> folderArgs = picker.powerShellArguments("directory", Path.of("C:\\xjp-picker\\picker.ps1"));

        assertTrue(script.contains("Add-Type -Path (Join-Path $PSScriptRoot 'NativeFileDialog.cs')"));
        assertTrue(script.contains("[XjpNativeFileDialog]::Choose($Kind)"));
        assertFalse(script.contains("System.Windows.Forms"));
        assertTrue(script.contains("XJP_PICKER_STAGE=helper-compile-enter"));
        assertTrue(fileArgs.contains("-File"));
        assertEquals("file", fileArgs.get(fileArgs.size() - 1));
        assertEquals("directory", folderArgs.get(folderArgs.size() - 1));
        assertFalse(fileArgs.contains("-EncodedCommand"));
        assertTrue(fileArgs.stream().mapToInt(String::length).sum() < 2_000);
    }

    /**
     * 验证 COM 选择器以本地目录作为起始位置，并只报告安全的窗口站诊断。
     */
    @Test
    void nativeHelperUsesFileDialogComAndSafeContext() throws IOException {
        final String source;
        try (InputStream helper = NativePathPicker.class.getResourceAsStream("/windows-picker/NativeFileDialog.cs")) {
            if (helper == null) {
                throw new IOException("Windows dialog helper is missing from test resources.");
            }
            source = new String(helper.readAllBytes(), StandardCharsets.UTF_8);
        }

        assertTrue(source.contains("42F85136-DB7E-439C-85F1-E4075D135FC8"));
        assertTrue(source.contains("dialog.Show(owner)"));
        assertTrue(source.contains("GetDisplayName(FileSystemPath"));
        assertTrue(source.contains("GetThreadDesktop"));
        assertTrue(source.contains("GetProcessWindowStation"));
        assertTrue(source.contains("GetApartmentState"));
        assertTrue(source.contains("GetStartupInfoW"));
        assertTrue(source.contains("GetWindowTextLengthW"));
        assertTrue(source.contains("GetWindowRect(handle, out rectangle)"));
        assertTrue(source.contains("DwmGetWindowAttribute(handle, DwmCloakedAttribute"));
        assertFalse(source.contains("EnumWindows("));
        assertFalse(source.contains("FindWindow("));
        assertTrue(source.contains("dialog.SetFolder(folder)"));
        assertTrue(source.contains("Path.GetPathRoot(Environment.SystemDirectory)"));
        assertTrue(source.contains("dialog.Advise(visibilityEvents"));
        assertTrue(source.contains("IOleWindow"));
        assertTrue(source.contains("ShowWindow(handle, ShowWindowNormal)"));
    }

    /**
     * 将 COM 接口声明固定为原生 vtable 顺序，避免槽位错序导致静默调用错误。
     */
    @Test
    void nativeComInterfacesFollowVtableOrder() throws IOException {
        final String source = readResource("/windows-picker/NativeFileDialog.cs");

        assertInterfaceMethodOrder(source, "IFileDialogEvents", "int OnFileOk", "int OnFolderChanging",
                "int OnFolderChange", "int OnSelectionChange", "int OnShareViolation", "int OnTypeChange",
                "int OnOverwrite");
        assertInterfaceMethodOrder(source, "IOleWindow", "int GetWindow", "int ContextSensitiveHelp");
        assertInterfaceMethodOrder(source, "IFileDialog", "int Show", "int SetFileTypes", "int SetFileTypeIndex",
                "int GetFileTypeIndex", "int Advise", "int Unadvise", "int SetOptions", "int GetOptions",
                "int SetDefaultFolder", "int SetFolder", "int GetFolder", "int GetCurrentSelection",
                "int SetFileName", "int GetFileName", "int SetTitle", "int SetOkButtonLabel",
                "int SetFileNameLabel", "int GetResult", "int AddPlace", "int SetDefaultExtension",
                "int Close", "int SetClientGuid", "int ClearClientData", "int SetFilter");
        assertInterfaceMethodOrder(source, "IShellItem", "int BindToHandler", "int GetParent", "int GetDisplayName",
                "int GetAttributes", "int Compare");
    }

    /**
     * 仅接受固定阶段和上下文标记，避免将选中路径或任意错误写入日志。
     */
    @Test
    void pickerDiagnosticsAllowlistStagesAndSafeContextOnly() {
        assertEquals("xjar-plus: Windows picker stage=dialog-show-enter",
                NativePathPicker.safeDiagnosticMessage("XJP_PICKER_STAGE=dialog-show-enter"));
        assertEquals("xjar-plus: Windows picker session=1 station=WinSta0 desktop=Default apartment=STA "
                     + "startup-flags=00000100 startup-show=0 owner=console visible=true",
                NativePathPicker.safeDiagnosticMessage(
                        "XJP_PICKER_CONTEXT session=1 station=WinSta0 desktop=Default apartment=STA "
                        + "startup-flags=00000100 startup-show=0 "
                        + "owner=console owner-visible=true"));
        assertEquals("xjar-plus: Windows picker dialog visible-before=false visible-after=true activated=true",
                NativePathPicker.safeDiagnosticMessage(
                        "XJP_PICKER_WINDOW visible-before=false visible-after=true activated=true"));
        assertEquals("xjar-plus: Windows picker dialog title-length=23 rect-positive=true cloak-hr=00000000 "
                     + "cloak-flags=00000000",
                NativePathPicker.safeDiagnosticMessage("XJP_PICKER_WINDOW_STATE title-length=23 "
                                                       + "rect-positive=true cloak-hr=00000000 cloak-flags=00000000"));
        assertNull(NativePathPicker.safeDiagnosticMessage("selected=C:\\Customer\\secret.jar"));
        assertNull(NativePathPicker.safeDiagnosticMessage(
                "XJP_PICKER_CONTEXT session=1 station=WinSta0 desktop=Default apartment=STA "
                + "secret=C:\\Customer\\secret.jar"));
        assertNull(NativePathPicker.safeDiagnosticMessage(
                "XJP_PICKER_WINDOW visible-before=false visible-after=true path=C:\\Customer\\secret.jar"));
        assertNull(NativePathPicker.safeDiagnosticMessage("XJP_PICKER_WINDOW_STATE title=secret.jar "
                + "rect-positive=true cloak-hr=00000000 cloak-flags=00000000"));
    }

    /**
     * 只有显式开启诊断后才允许将底层原生窗口状态写入应用 stderr。
     */
    @Test
    void pickerDebugDiagnosticsDefaultOffAndHonorSystemProperty() {
        final String original = System.getProperty("xjar.plus.debug-picker");
        try {
            System.clearProperty("xjar.plus.debug-picker");
            assertFalse(NativePathPicker.debugPickerEnabled());
            System.setProperty("xjar.plus.debug-picker", "true");
            assertTrue(NativePathPicker.debugPickerEnabled());
        } finally {
            if (original == null) {
                System.clearProperty("xjar.plus.debug-picker");
            } else {
                System.setProperty("xjar.plus.debug-picker", original);
            }
        }
    }

    /**
     * 调试关闭时，内部阶段标记不得泄露到界面失败消息中。
     */
    @Test
    void pickerFailureRemovesInternalMarkersButKeepsActionableText() {
        final String stderr = "XJP_PICKER_STAGE=dialog-show-enter\r\nAccess denied\r\n"
                              + "XJP_PICKER_WINDOW_STATE title-length=0 rect-positive=false "
                              + "cloak-hr=80004005 cloak-flags=unknown";

        assertEquals("Access denied", NativePathPicker.stripPickerDiagnosticLines(stderr));
    }

    /**
     * 使无对应活动窗口的取消请求保持无效，避免影响下一次选择器操作。
     */
    @Test
    void cancelWithoutActivePickerDoesNothing() {
        final NativePathPicker picker = new NativePathPicker();

        assertFalse(picker.cancelCurrent());
    }

    /**
     * 确保自有 Windows 通用对话框不被隐藏，并让 COM 保持单线程单元模式。
     */
    @Test
    void windowsHelperDoesNotRequestHiddenWindowStyle() {
        final NativePathPicker picker = new NativePathPicker();
        final List<String> arguments = picker.powerShellArguments("file", Path.of("C:\\xjp-picker\\picker.ps1"));

        assertTrue(arguments.contains("-STA"));
        assertTrue(arguments.contains("-File"));
        assertFalse(arguments.contains("-WindowStyle"));
        assertFalse(arguments.contains("Hidden"));
    }

    /**
     * 验证 COM 辅助程序阶段诊断顺序，不在测试进程中启动原生界面。
     */
    @Test
    void windowsScriptEmitsSafePhaseMarkersAroundComStartup() throws IOException {
        final NativePathPicker picker = new NativePathPicker();
        final String script = readResource("/windows-picker/picker.ps1");

        assertStageOrder(script, "helper-compile-enter", "helper-compile-return");
        assertStageOrder(script, "helper-call-enter", "helper-call-return");
        assertTrue(script.contains("XjpNativeFileDialog"));
    }

    /**
     * 验证 PowerShell 工作区原子设置为仅所有者可访问，并在辅助程序退出后删除。
     */
    @Test
    void privatePickerWorkspaceUsesOwnerAclAndCleansUp() throws IOException {
        Assumptions.assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        final Path directory;
        try (PrivatePickerWorkspace workspace = PrivatePickerWorkspace.create()) {
            directory = workspace.directory();
            final UserPrincipal owner = Files.getOwner(directory);
            assertOwnerOnlyAcl(directory, owner);
            assertOwnerOnlyAcl(directory.resolve("NativeFileDialog.cs"), owner);
            assertOwnerOnlyAcl(directory.resolve("picker.ps1"), owner);
        }
        assertFalse(Files.exists(directory));
    }

    /**
     * 读取一个打包的固定辅助资源，不依赖当前工作目录。
     */
    private String readResource(String name) throws IOException {
        try (InputStream resource = NativePathPickerTest.class.getResourceAsStream(name)) {
            if (resource == null) {
                throw new IOException("Picker resource is missing: " + name);
            }
            return new String(resource.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 拒绝向当前账户之外的其他主体授予访问权限的 ACL 条目。
     */
    private void assertOwnerOnlyAcl(Path path, UserPrincipal owner) throws IOException {
        final AclFileAttributeView view = Files.getFileAttributeView(path, AclFileAttributeView.class);
        assertTrue(view != null);
        final List<AclEntry> entries = view.getAcl();
        assertFalse(entries.isEmpty());
        assertTrue(entries.stream().allMatch(entry -> entry.principal().equals(owner)));
    }

    /**
     * 要求诊断操作前后的标记均存在且顺序正确。
     */
    private void assertStageOrder(String script, String before, String after) {
        final int beforeIndex = script.indexOf("XJP_PICKER_STAGE=" + before);
        final int afterIndex = script.indexOf("XJP_PICKER_STAGE=" + after);

        assertTrue(beforeIndex >= 0);
        assertTrue(afterIndex > beforeIndex);
    }

    /**
     * 在接口声明体中校验方法签名，避免注释或调用位置掩盖 COM 槽位错序。
     */
    private void assertInterfaceMethodOrder(String source, String interfaceName, String... methodSignatures) {
        final int interfaceIndex = source.indexOf("internal interface " + interfaceName);
        assertTrue(interfaceIndex >= 0, "Missing COM interface " + interfaceName);
        int previousIndex = interfaceIndex;
        for (String signature : methodSignatures) {
            final int methodIndex = source.indexOf(signature + "(", previousIndex + 1);
            assertTrue(methodIndex > previousIndex, "Unexpected vtable slot order at " + signature);
            previousIndex = methodIndex;
        }
    }
}
