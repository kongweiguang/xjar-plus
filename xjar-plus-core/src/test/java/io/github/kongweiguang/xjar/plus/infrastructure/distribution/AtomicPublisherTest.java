/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.infrastructure.distribution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.FileSystems;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 测试真实文件系统操作和不遵守协作约定的外部创建进程，而非进程内发布锁。
 */
final class AtomicPublisherTest {
    @TempDir
    private Path temporary;

    /**
     * 区分发布被拒绝和发布成功的情况，不删除任何一方的数据。
     */
    private static boolean compete(final Path source, final Path destination, final CountDownLatch ready,
                                   final CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(10, TimeUnit.SECONDS)) {
            throw new IllegalStateException("publication race start gate timed out");
        }
        try {
            AtomicPublisher.publish(source, destination);
            return true;
        } catch (IOException refused) {
            return false;
        }
    }

    /**
     * 直接使用当前 JDK 和参数数组，避免启动 shell 或显示应用窗口。
     */
    private static Process startActor(final boolean directory, final Path destination, final Path ready,
                                      final Path start) throws IOException {
        final List<String> arguments = javaCommand(ExternalCreator.class);
        arguments.addAll(List.of(Boolean.toString(directory), destination.toString(),
                ready.toString(), start.toString()));
        return new ProcessBuilder(arguments).redirectErrorStream(true).start();
    }

    /**
     * 子进程只共享测试夹具类和 JVM 依赖，不继承生产凭据。
     */
    private static List<String> javaCommand(final Class<?> mainClass) {
        final String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        final Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        final String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        return new ArrayList<>(List.of(java.toString(), "-cp", classpath, mainClass.getName()));
    }

    /**
     * 读取简短且确定的确认信息后关闭已完成子进程的输出流。
     */
    private static String actorOutput(final Process actor) throws IOException {
        try (var output = actor.getInputStream()) {
            return new String(output.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * 限制进程间同步等待时间，避免子进程失败时卡住 Maven 测试。
     */
    private static void awaitFile(final Path path) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!Files.exists(path)) {
            if (System.nanoTime() >= deadline) {
                throw new IOException("External creator rendezvous timed out");
            }
            Thread.sleep(1);
        }
    }

    /**
     * 为不同参与者创建不同载荷，使替换空目录的行为也能被观察到。
     */
    private static void createEntry(final Path path, final boolean directory, final String contents)
            throws IOException {
        if (directory) {
            Files.createDirectory(path);
            Files.writeString(path.resolve("payload"), contents, StandardOpenOption.CREATE_NEW);
        } else {
            Files.writeString(path, contents, StandardOpenOption.CREATE_NEW);
        }
    }

    /**
     * 读取最终胜出名称下的实际载荷，不仅依赖本机调用的返回值。
     */
    private static String entryContents(final Path path, final boolean directory) throws IOException {
        return Files.readString(directory ? path.resolve("payload") : path);
    }

    /**
     * Unicode 名称和非空目录必须完整保留其字节，同时暂存名称应消失。
     */
    @Test
    void publishesFilesAndPopulatedDirectories() throws Exception {
        for (final boolean directory : new boolean[]{false, true}) {
            final Path source = temporary.resolve("暂存-🔐-" + directory);
            final Path destination = temporary.resolve("产物-🔐-" + directory);
            createEntry(source, directory, "complete");
            AtomicPublisher.publish(source, destination);
            assertFalse(Files.exists(source, LinkOption.NOFOLLOW_LINKS));
            assertEquals("complete", entryContents(destination, directory));
        }
    }

    /**
     * 所有已存在的文件和目录均属于用户数据，包括空目标目录。
     */
    @Test
    void neverReplacesExistingFilesOrDirectories() throws Exception {
        for (final boolean sourceDirectory : new boolean[]{false, true}) {
            for (int destinationType = 0; destinationType < 3; destinationType++) {
                final String suffix = sourceDirectory + "-" + destinationType;
                final Path source = temporary.resolve("source-" + suffix);
                final Path destination = temporary.resolve("destination-" + suffix);
                createEntry(source, sourceDirectory, "staged");
                if (destinationType == 2) {
                    Files.createDirectory(destination);
                } else {
                    createEntry(destination, destinationType == 1, "existing");
                }
                assertThrows(FileAlreadyExistsException.class, () -> AtomicPublisher.publish(source, destination));
                assertEquals("staged", entryContents(source, sourceDirectory));
                if (destinationType == 2) {
                    try (var entries = Files.list(destination)) {
                        assertEquals(0, entries.count());
                    }
                } else {
                    assertEquals("existing", entryContents(destination, destinationType == 1));
                }
            }
        }
    }

    /**
     * 本机重命名失败时不得创建父目录、复制部分产物或移走暂存源。
     */
    @Test
    void missingDestinationParentPreservesSource() throws Exception {
        final Path source = Files.writeString(temporary.resolve("source"), "preserved");
        final Path destination = temporary.resolve("absent/result");
        assertThrows(IOException.class, () -> AtomicPublisher.publish(source, destination));
        assertEquals("preserved", Files.readString(source));
        assertFalse(Files.exists(destination.getParent()));
    }

    /**
     * 将虚拟路径当成本机路径可能重命名错误文件，因此拒绝非默认文件系统提供方。
     */
    @Test
    void virtualFileSystemsAreRejectedWithoutMutation() throws Exception {
        final Path source = Files.writeString(temporary.resolve("source"), "preserved");
        final Path archive = temporary.resolve("virtual.zip");
        try (var zip = FileSystems.newFileSystem(archive, Map.of("create", "true"))) {
            final Path destination = zip.getPath("/result");
            assertThrows(IOException.class, () -> AtomicPublisher.publish(source, destination));
            assertFalse(Files.exists(destination));
        }
        assertEquals("preserved", Files.readString(source));
    }

    /**
     * 并发发布必须只留下一个完整胜者，并保留所有失败者的源文件以便清理或重试。
     */
    @Test
    void concurrentPublishersNeverReplaceTheWinner() throws Exception {
        for (final boolean directory : new boolean[]{false, true}) {
            racePublishers(directory);
        }
    }

    /**
     * 在独立 JVM 中使用 CREATE_NEW 和 mkdir，刻意绕过辅助锁或预先检查文件存在性的约定。
     */
    @Test
    void externalCreatorCannotBeOverwritten() throws Exception {
        for (final boolean directory : new boolean[]{false, true}) {
            for (int attempt = 0; attempt < 4; attempt++) {
                raceExternalCreator(directory, attempt);
            }
        }
    }

    /**
     * 本机操作不支持时必须安全失败，不得改用更弱的 Java 移动操作。
     */
    @Test
    void unavailableNativeBridgeDoesNotFallback() throws Exception {
        final Path source = Files.writeString(temporary.resolve("source"), "preserved");
        final Path destination = temporary.resolve("result");
        final List<String> arguments = javaCommand(UnavailableBridge.class);
        arguments.add(1, "-Djna.noclasspath=true");
        arguments.add(1, "-Djna.nosys=true");
        arguments.add(source.toString());
        arguments.add(destination.toString());
        final Process actor = new ProcessBuilder(arguments).redirectErrorStream(true).start();
        try {
            assertTrue(actor.waitFor(10, TimeUnit.SECONDS), "unavailable-bridge probe timed out");
            final String outcome = actorOutput(actor);
            assertEquals(0, actor.exitValue(), outcome);
            assertEquals("UNAVAILABLE", outcome.trim());
            assertEquals("preserved", Files.readString(source));
            assertFalse(Files.exists(destination));
        } finally {
            if (actor.isAlive()) {
                actor.destroyForcibly();
                actor.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * 跨设备重命名必须失败，且不得将文件或非空目录复制到第二个文件系统。
     */
    @Test
    @EnabledOnOs(OS.LINUX)
    void crossVolumePublicationIsRefusedWithoutCopy() throws Exception {
        final Path secondVolume = Path.of("/dev/shm");
        assumeTrue(Files.isDirectory(secondVolume) && Files.isWritable(secondVolume));
        assumeFalse(Files.getFileStore(temporary).equals(Files.getFileStore(secondVolume)));
        final Path secondRoot = Files.createTempDirectory(secondVolume, "xjar-atomic-test-");
        try {
            for (final boolean directory : new boolean[]{false, true}) {
                final Path source = temporary.resolve("cross-volume-" + directory);
                final Path destination = secondRoot.resolve("result-" + directory);
                createEntry(source, directory, "preserved");
                assertThrows(IOException.class, () -> AtomicPublisher.publish(source, destination));
                assertEquals("preserved", entryContents(source, directory));
                assertFalse(Files.exists(destination));
            }
        } finally {
            for (final boolean directory : new boolean[]{false, true}) {
                final Path destination = secondRoot.resolve("result-" + directory);
                if (directory) {
                    Files.deleteIfExists(destination.resolve("payload"));
                }
                Files.deleteIfExists(destination);
            }
            Files.delete(secondRoot);
        }
    }

    /**
     * 对两种条目类型使用统一启动闸门和可区分载荷，验证内核层面的并发竞争。
     */
    private void racePublishers(final boolean directory) throws Exception {
        final int count = 8;
        final Path destination = temporary.resolve("winner-" + directory);
        final CountDownLatch ready = new CountDownLatch(count);
        final CountDownLatch start = new CountDownLatch(1);
        final List<Path> sources = new ArrayList<>();
        final List<Future<Boolean>> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(count)) {
            for (int index = 0; index < count; index++) {
                final Path source = temporary.resolve("contender-" + directory + "-" + index);
                createEntry(source, directory, Integer.toString(index));
                sources.add(source);
                results.add(executor.submit(() -> compete(source, destination, ready, start)));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS), "publishers did not reach the start gate");
            start.countDown();
            int winners = 0;
            for (int index = 0; index < count; index++) {
                if (results.get(index).get(10, TimeUnit.SECONDS)) {
                    winners++;
                    assertFalse(Files.exists(sources.get(index)));
                    assertEquals(Integer.toString(index), entryContents(destination, directory));
                } else {
                    assertEquals(Integer.toString(index), entryContents(sources.get(index), directory));
                }
            }
            assertEquals(1, winners);
        } finally {
            start.countDown();
        }
    }

    /**
     * 交替改变调度优势，同时确保最终创建与重命名确实发生在不同进程中。
     */
    private void raceExternalCreator(final boolean directory, final int attempt) throws Exception {
        final Path root = Files.createDirectory(temporary.resolve("external-" + directory + "-" + attempt));
        final Path source = root.resolve("source");
        final Path destination = root.resolve("result");
        final Path ready = root.resolve("ready");
        final Path start = root.resolve("start");
        createEntry(source, directory, "publisher");
        final Process actor = startActor(directory, destination, ready, start);
        try {
            awaitFile(ready);
            Files.createFile(start);
            if (attempt % 2 == 1) {
                Thread.sleep(20);
            }
            boolean published;
            try {
                AtomicPublisher.publish(source, destination);
                published = true;
            } catch (IOException refused) {
                published = false;
            }
            assertTrue(actor.waitFor(10, TimeUnit.SECONDS), "external creator timed out");
            final String outcome = actorOutput(actor);
            assertEquals(0, actor.exitValue(), outcome);
            assertEquals(published ? "EXISTS" : "CREATED", outcome.trim());
            assertEquals(published ? "publisher" : "external", entryContents(destination, directory));
            assertEquals(!published, Files.exists(source));
            if (!published) {
                assertEquals("publisher", entryContents(source, directory));
            }
        } finally {
            if (actor.isAlive()) {
                actor.destroyForcibly();
                actor.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * 独立 JVM 夹具只使用普通排他创建，刻意不调用 AtomicPublisher。
     */
    public static final class ExternalCreator {
        /**
     * 夹具仅由子进程 main 方法调用，因此禁止意外实例化。
         */
        private ExternalCreator() {
        }

        /**
     * 不使用发布辅助逻辑，直接报告排他创建后目标归属的进程。
         */
        public static void main(final String[] arguments) throws Exception {
            final boolean directory = Boolean.parseBoolean(arguments[0]);
            final Path destination = Path.of(arguments[1]);
            Files.createFile(Path.of(arguments[2]));
            awaitFile(Path.of(arguments[3]));
            try {
                createEntry(destination, directory, "external");
                System.out.println("CREATED");
            } catch (FileAlreadyExistsException occupied) {
                System.out.println("EXISTS");
            }
        }
    }

    /**
     * 必须使用全新 JVM，确保 JNA 本机初始化在任何库加载前失败。
     */
    public static final class UnavailableBridge {
        /**
     * 夹具仅由子进程 main 调用，因此禁止意外实例化。
         */
        private UnavailableBridge() {
        }

        /**
     * 只接受明确的本机能力不可用错误，不允许复制成功或覆盖回退。
         */
        public static void main(final String[] arguments) throws Exception {
            try {
                AtomicPublisher.publish(Path.of(arguments[0]), Path.of(arguments[1]));
                throw new AssertionError("Missing native support did not prevent publication");
            } catch (AtomicMoveNotSupportedException expected) {
                System.out.println("UNAVAILABLE");
            }
        }
    }
}
