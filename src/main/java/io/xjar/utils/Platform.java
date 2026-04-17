package io.xjar.utils;

public enum Platform {
    // ===== Windows =====
    WINDOWS_AMD64("windows", "amd64"),
    WINDOWS_386("windows", "386"),
    WINDOWS_ARM64("windows", "arm64"),

    // ===== Linux =====
    LINUX_AMD64("linux", "amd64"),
    LINUX_386("linux", "386"),
    LINUX_ARM("linux", "arm"),
    LINUX_ARM64("linux", "arm64"),
    LINUX_PPC64LE("linux", "ppc64le"),
    LINUX_S390X("linux", "s390x"),
    LINUX_RISCV64("linux", "riscv64"),

    // ===== macOS =====
    DARWIN_AMD64("darwin", "amd64"),
    DARWIN_ARM64("darwin", "arm64"),

    // ===== BSD =====
    FREEBSD_AMD64("freebsd", "amd64"),
    FREEBSD_ARM64("freebsd", "arm64"),

    OPENBSD_AMD64("openbsd", "amd64"),
    NETBSD_AMD64("netbsd", "amd64"),

    // ===== 移动端 =====
    ANDROID_ARM64("android", "arm64"),
    ANDROID_ARM("android", "arm"),

    IOS_ARM64("ios", "arm64"),
    IOS_SIMULATOR_AMD64("ios", "amd64"),

    // ===== 其他（较少用）=====
    PLAN9_AMD64("plan9", "amd64"),
    WASM("js", "wasm");

    private final String goos;
    private final String goarch;

    Platform(String goos, String goarch) {
        this.goos = goos;
        this.goarch = goarch;
    }

    public String goos() {
        return goos;
    }

    public String goarch() {
        return goarch;
    }
}
