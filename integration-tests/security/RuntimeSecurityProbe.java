/* @author kongweiguang */
package io.github.kongweiguang.xjar.plus.runtime.common;

import io.github.kongweiguang.xjar.plus.format.ArchiveIndex;
import io.github.kongweiguang.xjar.plus.format.ChunkCipher;
import io.github.kongweiguang.xjar.plus.format.EntryMetadata;
import io.github.kongweiguang.xjar.plus.format.NonceSequence;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.CRC32;

/**
 * 仅验证归档边界；启动引导与真实框架运行由独立集成用例覆盖。
 */
public final class RuntimeSecurityProbe {
    private static final String ARTIFACT = "00112233-4455-4677-8899-aabbccddeeff";
    private static final byte[] TEST_KEY = new byte[32];
    private static int checks;

    /**
     * 将公开测试密钥及生成归档与业务打包过程隔离。
     */
    private RuntimeSecurityProbe() {
    }

    /**
     * 验证认证条目边界，部分读取也不能绕过明文资源的完整性预检。
     */
    public static void main(String[] args) throws Exception {
        Path directory = Paths.get(args[0]).resolve("integration-tests/security/target/runtime-probe");
        Files.createDirectories(directory);
        Path protectedJar = directory.resolve("protected.jar");
        byte[] expected = "safe".getBytes(StandardCharsets.UTF_8);
        byte[] tampered = "evil".getBytes(StandardCharsets.UTF_8);
        byte[] largeExpected = new byte[16385];
        Arrays.fill(largeExpected, (byte) 's');
        byte[] tailTampered = largeExpected.clone();
        tailTampered[tailTampered.length - 1] = (byte) 'x';
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        entries.put("good.txt", expected);
        entries.put("bad.txt", tampered);
        entries.put("Bad.class", tampered);
        entries.put("unexpected.txt", tampered);
        entries.put("Unexpected.class", tampered);
        entries.put("large-good.txt", largeExpected);
        entries.put("tail-bad.txt", tailTampered);
        entries.put("short.txt", Arrays.copyOf(largeExpected, 8192));
        entries.put("long.txt", "safeevil".getBytes(StandardCharsets.UTF_8));
        entries.put("crc-only.txt", expected);
        entries.put("sha-only.txt", expected);
        ByteArrayOutputStream encrypted = new ByteArrayOutputStream();
        ChunkCipher.encrypt(new ByteArrayInputStream(expected), encrypted, TEST_KEY, ARTIFACT,
                "encrypted.txt", expected.length, new NonceSequence());
        entries.put("encrypted.txt", encrypted.toByteArray());
        List<EntryMetadata> metadata = new ArrayList<EntryMetadata>();
        metadata.add(metadata("good.txt", false, expected));
        metadata.add(metadata("bad.txt", false, expected));
        metadata.add(metadata("Bad.class", false, expected));
        metadata.add(metadata("encrypted.txt", true, expected));
        metadata.add(metadata("large-good.txt", false, largeExpected));
        metadata.add(metadata("tail-bad.txt", false, largeExpected));
        metadata.add(metadata("short.txt", false, largeExpected));
        metadata.add(metadata("long.txt", false, expected));
        CRC32 originalCrc = new CRC32();
        originalCrc.update(expected);
        metadata.add(new EntryMetadata("crc-only.txt", false, expected.length, originalCrc.getValue() ^ 1,
                MessageDigest.getInstance("SHA-256").digest(expected)));
        metadata.add(new EntryMetadata("sha-only.txt", false, expected.length, originalCrc.getValue(), new byte[32]));
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        writeJar(protectedJar, manifest, entries);
        ArchiveRuntime runtime = runtime(protectedJar, manifest, new ArchiveIndex(metadata));
        expectFailure(() -> runtime.wrap(entry(protectedJar, "unexpected.txt"), "unexpected.txt"),
                "unindexed protected resource");
        expectFailure(() -> runtime.classBytes(entry(protectedJar, "Unexpected.class"), "Unexpected.class"),
                "unindexed protected class");
        require(Arrays.equals(read(runtime.wrap(entry(protectedJar, "good.txt"), "good.txt")), expected),
                "indexed plaintext round trip");
        require(Arrays.equals(read(runtime.wrap(entry(protectedJar, "encrypted.txt"), "encrypted.txt")), expected),
                "indexed ciphertext round trip");
        expectFailure(() -> read(runtime.wrap(entry(protectedJar, "bad.txt"), "bad.txt")),
                "indexed plaintext full-read tamper");
        expectFailure(() -> runtime.classBytes(entry(protectedJar, "Bad.class"), "Bad.class"),
                "indexed plaintext class tamper");
        expectFailure(() -> readPrefix(runtime.wrap(entry(protectedJar, "bad.txt"), "bad.txt"), true),
                "indexed plaintext one-byte tamper");
        for (boolean cached : new boolean[]{true, false}) {
            require(readPrefix(runtime.wrap(entry(protectedJar, "large-good.txt"), "large-good.txt"), cached) == 's',
                    "large plaintext partial read with useCaches=" + cached);
            expectFailure(() -> readPrefix(runtime.wrap(entry(protectedJar, "tail-bad.txt"), "tail-bad.txt"), cached),
                    "tail tamper before first byte with useCaches=" + cached);
        }
        for (String invalid : new String[]{"short.txt", "long.txt", "crc-only.txt", "sha-only.txt"}) {
            expectFailure(() -> readPrefix(runtime.wrap(entry(protectedJar, invalid), invalid), false),
                    "partial reader rejects " + invalid);
        }
        Path externalJar = directory.resolve("external.jar");
        writeJar(externalJar, manifest, entries);
        URL external = entry(externalJar, "unexpected.txt");
        require(runtime.wrap(external, "unexpected.txt") == external, "external dependency remains delegated");
        Path similarJar = directory.resolve("protected.jar.other.jar");
        writeJar(similarJar, manifest, entries);
        URL similar = entry(similarJar, "unexpected.txt");
        require(runtime.wrap(similar, "unexpected.txt") == similar, "similar external name is not protected root");
        System.out.println("RUNTIME_SECURITY_PROBE_OK checks=" + checks);
    }

    /**
     * 仅为隔离 URL 归属与完整性规则而绕过引导，不把密码或框架发现混入本组断言。
     */
    private static ArchiveRuntime runtime(Path jar, Manifest manifest, ArchiveIndex index) throws Exception {
        Constructor<ArchiveRuntime> constructor = ArchiveRuntime.class.getDeclaredConstructor(String.class,
                byte[].class, ArchiveIndex.class, Manifest.class, URL.class);
        constructor.setAccessible(true);
        return constructor.newInstance(ARTIFACT, TEST_KEY.clone(), index, manifest, jar.toUri().toURL());
    }

    /**
     * 按原始字节计算索引保护的资源事实，避免拿密文元数据替代明文契约。
     */
    private static EntryMetadata metadata(String name, boolean encrypted, byte[] expected) throws Exception {
        CRC32 crc = new CRC32();
        crc.update(expected);
        return new EntryMetadata(name, encrypted, expected.length, crc.getValue(),
                MessageDigest.getInstance("SHA-256").digest(expected));
    }

    /**
     * 只把公开测试归档写入专用 target 目录，不触及用户应用产物。
     */
    private static void writeJar(Path path, Manifest manifest, Map<String, byte[]> entries) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(path), manifest)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                output.putNextEntry(new JarEntry(entry.getKey()));
                output.write(entry.getValue());
                output.closeEntry();
            }
        }
    }

    /**
     * 使用真实 JDK JAR 连接验证受保护与外部资源归属，避免模拟处理器掩盖路径差异。
     */
    private static URL entry(Path archive, String name) throws Exception {
        return new URL("jar:" + archive.toUri().toURL().toExternalForm() + "!/" + name);
    }

    /**
     * 经公开 URL 接口读至 EOF，确保完整执行认证元数据校验。
     */
    private static byte[] read(URL resource) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = resource.openStream()) {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                output.write(buffer, 0, count);
            }
        }
        return output.toByteArray();
    }

    /**
     * 故意在 EOF 前停止读取，确保完整性保护不依赖调用方配合读完。
     */
    private static int readPrefix(URL resource, boolean cached) throws IOException {
        URLConnection connection = resource.openConnection();
        connection.setUseCaches(cached);
        try (InputStream input = connection.getInputStream()) {
            return input.read();
        }
    }

    /**
     * 仅接受预期的运行时完整性异常，不把探针编程错误计为验证通过。
     */
    private static void expectFailure(Checked action, String label) throws Exception {
        try {
            action.run();
        } catch (IOException | IllegalStateException expected) {
            checks++;
            return;
        }
        throw new AssertionError("accepted " + label);
    }

    /**
     * 独立统计约束，首项不符即报告名称，避免后续成功覆盖失败。
     */
    private static void require(boolean condition, String label) {
        if (!condition) {
            throw new AssertionError(label);
        }
        checks++;
    }

    private interface Checked {
        /**
         * 保留生产运行时的受检异常，防止断言吞掉真实失败。
         */
        void run() throws Exception;
    }
}
