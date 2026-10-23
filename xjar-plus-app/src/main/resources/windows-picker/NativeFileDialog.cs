/* @author kongweiguang */

using System;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

/// <summary>直接调用 Windows 通用项目对话框 COM API，避免 GUI 工具包的隐藏 owner。</summary>
public static class XjpNativeFileDialog
{
    private const uint ContextInProcessServer = 1;
    private const uint ForceFileSystem = 0x40;
    private const uint NoChangeDirectory = 0x8;
    private const uint PickFolders = 0x20;
    private const uint PathMustExist = 0x800;
    private const uint FileMustExist = 0x1000;
    private const uint OverwritePrompt = 0x2;
    private const uint FileSystemPath = 0x80058000;
    private const int Cancelled = unchecked((int)0x800704C7);
    private const int UserObjectName = 2;
    private const int ShowWindowNormal = 1;
    private const uint DwmCloakedAttribute = 14;
    private static readonly Guid ShellItemInterface = new Guid("43826D1E-E718-42EE-BC55-A1E261C37BFE");
    private static readonly Guid FileOpenDialogClass = new Guid("DC1C5A9C-E88A-4DDE-A5A1-60F82A20AEF7");
    private static readonly Guid FileSaveDialogClass = new Guid("C0B4E2F3-BA21-4773-8DBA-335EC946EB8B");
    private static readonly Guid FileDialogInterface = new Guid("42F85136-DB7E-439C-85F1-E4075D135FC8");

    /// <summary>直接创建 shell 对话框，不委托 GUI 工具包封装控制窗口可见性。</summary>
    [DllImport("ole32.dll", PreserveSig = true)]
    private static extern int CoCreateInstance(ref Guid classId, IntPtr outer, uint context,
        ref Guid interfaceId, out IFileDialog instance);

    /// <summary>设置本地 shell 起始目录，避免陈旧的网络对话框历史阻塞首次显示。</summary>
    [DllImport("shell32.dll", CharSet = CharSet.Unicode, PreserveSig = true)]
    private static extern int SHCreateItemFromParsingName(string path, IntPtr bindContext,
        ref Guid interfaceId, out IShellItem item);

    /// <summary>宿主存在可见控制台窗口时，获取该控制台作为实际 owner。</summary>
    [DllImport("kernel32.dll", ExactSpelling = true)]
    private static extern IntPtr GetConsoleWindow();

    /// <summary>读取继承的窗口启动标记，不改变子进程启动契约。</summary>
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
    private static extern void GetStartupInfoW(out StartupInfo info);

    /// <summary>识别当前 GUI 线程，供安全的桌面环境诊断使用。</summary>
    [DllImport("kernel32.dll", ExactSpelling = true)]
    private static extern uint GetCurrentThreadId();

    /// <summary>读取 STA 线程实际关联的桌面，不根据进程元数据猜测。</summary>
    [DllImport("user32.dll", ExactSpelling = true)]
    private static extern IntPtr GetThreadDesktop(uint threadId);

    /// <summary>识别辅助程序连接的是交互式还是服务窗口站。</summary>
    [DllImport("user32.dll", ExactSpelling = true)]
    private static extern IntPtr GetProcessWindowStation();

    /// <summary>仅读取桌面对象名称，生成固定格式的安全上下文诊断。</summary>
    [DllImport("user32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern bool GetUserObjectInformation(IntPtr handle, int index, StringBuilder name,
        uint bufferBytes, out uint bytesNeeded);

    /// <summary>拒绝将不可见的控制台句柄用作模态对话框 owner。</summary>
    [DllImport("user32.dll", ExactSpelling = true)]
    private static extern bool IsWindowVisible(IntPtr handle);

    /// <summary>确认事件回调返回有效句柄后，才显示该对话框自身的窗口。</summary>
    [DllImport("user32.dll", ExactSpelling = true)]
    private static extern bool IsWindow(IntPtr handle);

    /// <summary>只显示从当前对话框对象获取的 HWND，不枚举或显示无关窗口。</summary>
    [DllImport("user32.dll", ExactSpelling = true)]
    private static extern bool ShowWindow(IntPtr handle, int command);

    /// <summary>只有用户显式请求系统文件选择时才激活通用对话框。</summary>
    [DllImport("user32.dll", ExactSpelling = true)]
    private static extern bool SetForegroundWindow(IntPtr handle);

    /// <summary>只读取标题长度，诊断不会复制 shell 文本或已选路径。</summary>
    [DllImport("user32.dll", CharSet = CharSet.Unicode, ExactSpelling = true)]
    private static extern int GetWindowTextLengthW(IntPtr handle);

    /// <summary>读取当前对话框的精确窗口边界，不枚举桌面上的其他窗口。</summary>
    [DllImport("user32.dll", ExactSpelling = true)]
    private static extern bool GetWindowRect(IntPtr handle, out WindowRectangle rectangle);

    /// <summary>窗口样式不足以判断时，检查同一对话框 HWND 的 DWM 隐藏状态。</summary>
    [DllImport("dwmapi.dll", PreserveSig = true)]
    private static extern int DwmGetWindowAttribute(IntPtr handle, uint attribute, out uint value, uint valueBytes);

    /// <summary>按用户请求的打开文件、保存文件或选择目录策略创建通用项目对话框。</summary>
    public static string Choose(string kind)
    {
        if (kind != "file" && kind != "save" && kind != "directory")
        {
            throw new ArgumentOutOfRangeException("kind");
        }

        EmitStage("com-create-enter");
        Guid classId = kind == "save" ? FileSaveDialogClass : FileOpenDialogClass;
        Guid interfaceId = FileDialogInterface;
        IFileDialog dialog;
        int result = CoCreateInstance(ref classId, IntPtr.Zero, ContextInProcessServer, ref interfaceId, out dialog);
        ThrowIfFailed(result);
        EmitStage("com-created");

        var visibilityEvents = new DialogVisibilityEvents();
        uint eventCookie = 0;
        bool eventsAdvised = false;
        try
        {
            IntPtr console = GetConsoleWindow();
            IntPtr owner = console != IntPtr.Zero && IsWindowVisible(console) ? console : IntPtr.Zero;
            EmitContext(owner);
            Configure(dialog, kind);
            EmitStage("dialog-configured");
            ThrowIfFailed(dialog.Advise(visibilityEvents, out eventCookie));
            eventsAdvised = true;
            EmitStage("dialog-events-advised");
            EmitStage("dialog-show-enter");
            result = dialog.Show(owner);
            EmitStage("dialog-show-return");
            if (result == Cancelled)
            {
                return null;
            }
            ThrowIfFailed(result);
            return SelectedPath(dialog);
        }
        finally
        {
            if (eventsAdvised && dialog.Unadvise(eventCookie) < 0)
            {
                EmitStage("dialog-unadvise-failed");
            }
            GC.KeepAlive(visibilityEvents);
            Marshal.FinalReleaseComObject(dialog);
        }
    }

    /// <summary>使用本地起始目录和有界文件系统选项，避免陈旧的网络 shell 状态。</summary>
    private static void Configure(IFileDialog dialog, string kind)
    {
        uint options;
        ThrowIfFailed(dialog.GetOptions(out options));
        options |= ForceFileSystem | NoChangeDirectory | PathMustExist;
        if (kind == "file")
        {
            options |= FileMustExist;
        }
        else if (kind == "directory")
        {
            options |= PickFolders;
        }
        else
        {
            options &= ~OverwritePrompt;
        }

        ThrowIfFailed(dialog.SetOptions(options));
        string title = kind == "file" ? "选择文件" : kind == "save" ? "选择新输出文件" : "选择目录";
        ThrowIfFailed(dialog.SetTitle(title));
        IShellItem folder = CreateInitialFolder();
        try
        {
            ThrowIfFailed(dialog.SetFolder(folder));
        }
        finally
        {
            Marshal.FinalReleaseComObject(folder);
        }
        if (kind == "file")
        {
            FilterSpec[] filters = new FilterSpec[]
            {
                new FilterSpec("JAR 文件 (*.jar)", "*.jar"),
                new FilterSpec("所有文件 (*.*)", "*.*")
            };
            ThrowIfFailed(dialog.SetFileTypes((uint)filters.Length, filters));
            ThrowIfFailed(dialog.SetFileTypeIndex(1));
        }
    }

    /// <summary>为本地系统卷根目录创建 shell 项目，不探测重定向的用户目录。</summary>
    private static IShellItem CreateInitialFolder()
    {
        string root = Path.GetPathRoot(Environment.SystemDirectory);
        string folder = String.IsNullOrEmpty(root) ? Environment.SystemDirectory : root;
        Guid interfaceId = ShellItemInterface;
        IShellItem item;
        ThrowIfFailed(SHCreateItemFromParsingName(folder, IntPtr.Zero, ref interfaceId, out item));
        return item;
    }

    /// <summary>读取一个文件系统结果，并在所有执行结果下释放 shell 分配资源。</summary>
    private static string SelectedPath(IFileDialog dialog)
    {
        IShellItem item;
        ThrowIfFailed(dialog.GetResult(out item));
        try
        {
            IntPtr path;
            ThrowIfFailed(item.GetDisplayName(FileSystemPath, out path));
            try
            {
                return Marshal.PtrToStringUni(path);
            }
            finally
            {
                Marshal.FreeCoTaskMem(path);
            }
        }
        finally
        {
            Marshal.FinalReleaseComObject(item);
        }
    }

    /// <summary>输出固定阶段标记，以区分原生 Show 调用阻塞和初始化失败。</summary>
    private static void EmitStage(string stage)
    {
        Console.Error.WriteLine("XJP_PICKER_STAGE=" + stage);
        Console.Error.Flush();
    }

    /// <summary>仅报告桌面关联事实，不记录用户路径或进程参数。</summary>
    private static void EmitContext(IntPtr owner)
    {
        int session = Process.GetCurrentProcess().SessionId;
        string station = SafeWindowStationName();
        string desktop = SafeDesktopName();
        string apartment = Thread.CurrentThread.GetApartmentState().ToString();
        StartupInfo startupInfo;
        GetStartupInfoW(out startupInfo);
        string ownerKind = owner == IntPtr.Zero ? "none" : "console";
        bool ownerVisible = owner != IntPtr.Zero && IsWindowVisible(owner);
        Console.Error.WriteLine("XJP_PICKER_CONTEXT session=" + session.ToString()
            + " station=" + station + " desktop=" + desktop + " apartment=" + apartment
            + " startup-flags=" + startupInfo.Flags.ToString("X8", CultureInfo.InvariantCulture)
            + " startup-show=" + startupInfo.ShowWindow.ToString(CultureInfo.InvariantCulture)
            + " owner=" + ownerKind + " owner-visible=" + ownerVisible.ToString().ToLowerInvariant());
        Console.Error.Flush();
    }

    /// <summary>用户打开选择器后，读取 COM 对象返回的 shell 对话框 HWND。</summary>
    internal static void RevealOwnDialog(IFileDialog dialog)
    {
        EmitStage("dialog-event-folder-change");
        IOleWindow window = null;
        try
        {
            window = (IOleWindow)dialog;
            IntPtr handle;
            int result = window.GetWindow(out handle);
            if (result < 0 || handle == IntPtr.Zero || !IsWindow(handle))
            {
                EmitStage("dialog-window-handle-missing");
                return;
            }

            bool visibleBefore = IsWindowVisible(handle);
            if (!visibleBefore)
            {
                ShowWindow(handle, ShowWindowNormal);
            }
            bool activated = SetForegroundWindow(handle);
            bool visibleAfter = IsWindowVisible(handle);
            Console.Error.WriteLine("XJP_PICKER_WINDOW visible-before=" + visibleBefore.ToString().ToLowerInvariant()
                + " visible-after=" + visibleAfter.ToString().ToLowerInvariant()
                + " activated=" + activated.ToString().ToLowerInvariant());
            Console.Error.Flush();
            EmitWindowState(handle);
            EmitStage(visibleAfter ? "dialog-window-visible" : "dialog-window-still-hidden");
        }
        catch
        {
            EmitStage("dialog-window-query-failed");
        }
    }

    /// <summary>仅报告当前对话框的有界几何信息和 DWM 状态，区分窗口样式可见性与桌面隐藏。</summary>
    private static void EmitWindowState(IntPtr handle)
    {
        int titleLength = Math.Max(0, GetWindowTextLengthW(handle));
        WindowRectangle rectangle;
        bool rectangleAvailable = GetWindowRect(handle, out rectangle);
        bool rectanglePositive = rectangleAvailable && rectangle.Right > rectangle.Left
            && rectangle.Bottom > rectangle.Top;
        uint cloakedFlags = 0;
        int cloakedResult = DwmGetWindowAttribute(handle, DwmCloakedAttribute, out cloakedFlags, sizeof(uint));
        string flags = cloakedResult < 0 ? "unknown" : cloakedFlags.ToString("X8", CultureInfo.InvariantCulture);
        Console.Error.WriteLine("XJP_PICKER_WINDOW_STATE title-length="
            + titleLength.ToString(CultureInfo.InvariantCulture)
            + " rect-positive=" + rectanglePositive.ToString().ToLowerInvariant()
            + " cloak-hr=" + unchecked((uint)cloakedResult).ToString("X8", CultureInfo.InvariantCulture)
            + " cloak-flags=" + flags);
        Console.Error.Flush();
    }

    /// <summary>读取进程窗口站名称，以区分交互式 shell 和服务会话。</summary>
    private static string SafeWindowStationName()
    {
        return SafeUserObjectName(GetProcessWindowStation());
    }

    /// <summary>读取当前线程桌面名称，不暴露未经限制的 Win32 对象名称。</summary>
    private static string SafeDesktopName()
    {
        return SafeUserObjectName(GetThreadDesktop(GetCurrentThreadId()));
    }

    /// <summary>仅返回有界 ASCII 名称，避免诊断泄露任意对象文本或路径。</summary>
    private static string SafeUserObjectName(IntPtr userObject)
    {
        if (userObject == IntPtr.Zero)
        {
            return "unknown";
        }

        StringBuilder name = new StringBuilder(64);
        uint bytesNeeded;
        if (!GetUserObjectInformation(userObject, UserObjectName, name, 128, out bytesNeeded))
        {
            return "unknown";
        }

        string value = name.ToString();
        if (value.Length == 0 || value.Length > 64)
        {
            return "unknown";
        }
        foreach (char character in value)
        {
            if (!Char.IsLetterOrDigit(character) && character != '_' && character != '-' && character != '.')
            {
                return "other";
            }
        }
        return value;
    }

    /// <summary>将 COM 错误转换为 HRESULT 异常，不把用户选择的值拼入错误消息。</summary>
    private static void ThrowIfFailed(int result)
    {
        if (result < 0)
        {
            Marshal.ThrowExceptionForHR(result);
        }
    }
}

/// <summary>仅镜像判断父进程是否指定窗口显示状态所需的启动字段。</summary>
[StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
internal struct StartupInfo
{
    public uint Size;
    public IntPtr Reserved;
    public IntPtr Desktop;
    public IntPtr Title;
    public uint X;
    public uint Y;
    public uint XSize;
    public uint YSize;
    public uint XCountChars;
    public uint YCountChars;
    public uint FillAttribute;
    public uint Flags;
    public ushort ShowWindow;
    public ushort Reserved2Size;
    public IntPtr Reserved2;
    public IntPtr StdInput;
    public IntPtr StdOutput;
    public IntPtr StdError;
}

/// <summary>仅保存判断对话框边界尺寸是否为正数所需的四个坐标。</summary>
[StructLayout(LayoutKind.Sequential)]
internal struct WindowRectangle
{
    public int Left;
    public int Top;
    public int Right;
    public int Bottom;
}

/// <summary>接收原生对话框打开回调，并通过对话框自身 HWND 修复可见性。</summary>
[ComVisible(true)]
[Guid("973510DB-7D7F-452B-8975-74A85828D354")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IFileDialogEvents
{
    /// <summary>允许用户确认的选择继续由原生对话框处理。</summary>
    [PreserveSig] int OnFileOk(IFileDialog dialog);
    /// <summary>允许 shell 对话框导航，同时保留其默认目录策略。</summary>
    [PreserveSig] int OnFolderChanging(IFileDialog dialog, IShellItem folder);
    /// <summary>仅在 shell 打开对话框后获取该对话框自有窗口。</summary>
    [PreserveSig] int OnFolderChange(IFileDialog dialog);
    /// <summary>文件选择行为继续由 Windows 标准对话框处理。</summary>
    [PreserveSig] int OnSelectionChange(IFileDialog dialog);
    /// <summary>共享冲突行为继续由 Windows 标准对话框处理。</summary>
    [PreserveSig] int OnShareViolation(IFileDialog dialog, IShellItem item, out uint response);
    /// <summary>文件类型行为继续由 Windows 标准对话框处理。</summary>
    [PreserveSig] int OnTypeChange(IFileDialog dialog);
    /// <summary>覆盖确认行为继续由 Windows 标准对话框处理。</summary>
    [PreserveSig] int OnOverwrite(IFileDialog dialog, IShellItem item, out uint response);
}

/// <summary>实现 COM 回调，不创建自定义界面，也不保存当前对话框句柄以外的状态。</summary>
[ComVisible(true)]
[ClassInterface(ClassInterfaceType.None)]
internal sealed class DialogVisibilityEvents : IFileDialogEvents
{
    /// <summary>接受 shell 默认的打开或保存确认，不修改已选路径。</summary>
    public int OnFileOk(IFileDialog dialog)
    {
        return 0;
    }

    /// <summary>目录有效性由通用项目对话框配置的选项负责校验。</summary>
    public int OnFolderChanging(IFileDialog dialog, IShellItem folder)
    {
        return 0;
    }

    /// <summary>IFileDialog::Show 会一直阻塞到用户关闭可见选择器，因此通过事件处理其窗口状态。</summary>
    public int OnFolderChange(IFileDialog dialog)
    {
        XjpNativeFileDialog.RevealOwnDialog(dialog);
        return 0;
    }

    /// <summary>原生对话框活动期间不修改用户的选择。</summary>
    public int OnSelectionChange(IFileDialog dialog)
    {
        return 0;
    }

    /// <summary>文件正在使用时采用 shell 默认处理方式。</summary>
    public int OnShareViolation(IFileDialog dialog, IShellItem item, out uint response)
    {
        response = 0;
        return 0;
    }

    /// <summary>不修改已选文件筛选器。</summary>
    public int OnTypeChange(IFileDialog dialog)
    {
        return 0;
    }

    /// <summary>覆盖文件时采用 shell 默认确认方式。</summary>
    public int OnOverwrite(IFileDialog dialog, IShellItem item, out uint response)
    {
        response = 0;
        return 0;
    }
}

/// <summary>在打开事件期间提供通用对话框自身的原生窗口句柄。</summary>
[ComImport]
[Guid("00000114-0000-0000-C000-000000000046")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IOleWindow
{
    /// <summary>返回被查询对话框对象持有的 HWND。</summary>
    [PreserveSig] int GetWindow(out IntPtr handle);
    /// <summary>保留 IOleWindow vtable 第二个方法槽，不误触上下文帮助模式。</summary>
    [PreserveSig] int ContextSensitiveHelp([MarshalAs(UnmanagedType.Bool)] bool enterMode);
}

/// <summary>按原生 vtable 顺序声明 IFileDialog，使 PowerShell 能正确调用 Windows COM 契约。</summary>
[ComImport]
[Guid("42F85136-DB7E-439C-85F1-E4075D135FC8")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IFileDialog
{
    /// <summary>使用真实桌面 HWND 显示通用项目对话框；没有可见 owner 时传入空值。</summary>
    [PreserveSig] int Show(IntPtr owner);
    /// <summary>设置有界文件筛选项，不改变后续 COM vtable 方法槽顺序。</summary>
    [PreserveSig] int SetFileTypes(uint count, [In, MarshalAs(UnmanagedType.LPArray, SizeParamIndex = 0)] FilterSpec[] filters);
    /// <summary>安装筛选项集合后选择可见筛选器。</summary>
    [PreserveSig] int SetFileTypeIndex(uint index);
    /// <summary>即使选择器不查询索引，也保留原生 API 的接口布局。</summary>
    [PreserveSig] int GetFileTypeIndex(out uint index);
    /// <summary>注册打开事件回调，以查询当前对话框自身的原生句柄。</summary>
    [PreserveSig] int Advise([In, MarshalAs(UnmanagedType.Interface)] IFileDialogEvents events, out uint cookie);
    /// <summary>模态对话框结束后注销临时回调。</summary>
    [PreserveSig] int Unadvise(uint cookie);
    /// <summary>应用仅文件系统、路径存在性和目录选择策略。</summary>
    [PreserveSig] int SetOptions(uint options);
    /// <summary>先读取 shell 默认选项，再叠加必需选项，不盲目替换原配置。</summary>
    [PreserveSig] int GetOptions(out uint options);
    /// <summary>无需初始 shell 项目时仍保留原生 API 的接口布局。</summary>
    [PreserveSig] int SetDefaultFolder(IShellItem folder);
    /// <summary>无需强制指定起始目录时仍保留原生 API 的接口布局。</summary>
    [PreserveSig] int SetFolder(IShellItem folder);
    /// <summary>选择项在 Show 结束后读取，但仍保留原生 API 的接口布局。</summary>
    [PreserveSig] int GetFolder(out IShellItem folder);
    /// <summary>由于有意禁用多选，仍保留原生 API 的接口布局。</summary>
    [PreserveSig] int GetCurrentSelection(out IShellItem item);
    /// <summary>不预填用户路径，同时保留原生 API 的接口布局。</summary>
    [PreserveSig] int SetFileName([MarshalAs(UnmanagedType.LPWStr)] string name);
    /// <summary>只返回最终 shell 项目，同时保留原生 API 的接口布局。</summary>
    [PreserveSig] int GetFileName(out IntPtr name);
    /// <summary>设置当前操作在界面上显示的标题。</summary>
    [PreserveSig] int SetTitle([MarshalAs(UnmanagedType.LPWStr)] string title);
    /// <summary>保留操作系统默认按钮文案，同时保持原生 API 的接口布局。</summary>
    [PreserveSig] int SetOkButtonLabel([MarshalAs(UnmanagedType.LPWStr)] string label);
    /// <summary>保留操作系统默认文件名标签，同时保持原生 API 的接口布局。</summary>
    [PreserveSig] int SetFileNameLabel([MarshalAs(UnmanagedType.LPWStr)] string label);
    /// <summary>仅在对话框成功关闭后读取唯一确认结果。</summary>
    [PreserveSig] int GetResult(out IShellItem item);
    /// <summary>无需自定义位置，同时保留原生 API 的接口布局。</summary>
    [PreserveSig] int AddPlace(IShellItem item, uint placement);
    /// <summary>不擅自添加输出扩展名，同时保留原生 API 的接口布局。</summary>
    [PreserveSig] int SetDefaultExtension([MarshalAs(UnmanagedType.LPWStr)] string extension);
    /// <summary>取消由 Show 的 HRESULT 表示，同时保留原生 API 的接口布局。</summary>
    [PreserveSig] int Close(int result);
    /// <summary>不跨构建持久化对话框身份，同时保留原生 API 的接口布局。</summary>
    [PreserveSig] int SetClientGuid(ref Guid guid);
    /// <summary>不持久化 shell 自定义状态，同时保留原生 API 的接口布局。</summary>
    [PreserveSig] int ClearClientData();
    /// <summary>筛选项由 SetFileTypes 接口统一管理，同时保留原生 API 的接口布局。</summary>
    [PreserveSig] int SetFilter(IntPtr filter);
}

/// <summary>声明从对话框结果读取文件系统路径所需的 IShellItem 方法槽。</summary>
[ComImport]
[Guid("43826D1E-E718-42EE-BC55-A1E261C37BFE")]
[InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IShellItem
{
    /// <summary>保留 shell item 的 vtable 顺序，不绑定自定义结果处理器。</summary>
    [PreserveSig] int BindToHandler(IntPtr bindContext, ref Guid handler, ref Guid interfaceId, out IntPtr value);
    /// <summary>所选项目本身就是所需输出，因此保留 shell item 的 vtable 顺序。</summary>
    [PreserveSig] int GetParent(out IShellItem parent);
    /// <summary>返回文件系统路径，而非本地化 shell 显示名称。</summary>
    [PreserveSig] int GetDisplayName(uint nameType, out IntPtr name);
    /// <summary>不从项目属性推断权限，同时保留 shell item 的 vtable 顺序。</summary>
    [PreserveSig] int GetAttributes(uint mask, out uint attributes);
    /// <summary>不比较用户选择的项目，同时保留 shell item 的 vtable 顺序。</summary>
    [PreserveSig] int Compare(IShellItem item, uint hint, out int order);
}

/// <summary>表示 IFileDialog::SetFileTypes 接受的一组显示名称和通配符模式。</summary>
[StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
internal struct FilterSpec
{
    [MarshalAs(UnmanagedType.LPWStr)] public string Name;
    [MarshalAs(UnmanagedType.LPWStr)] public string Pattern;

    /// <summary>保存不可变筛选值，避免对话框持有调用方可变缓冲区。</summary>
    public FilterSpec(string name, string pattern)
    {
        Name = name;
        Pattern = pattern;
    }
}
