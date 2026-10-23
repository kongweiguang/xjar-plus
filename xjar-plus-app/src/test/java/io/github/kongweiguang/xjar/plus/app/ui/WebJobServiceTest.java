/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.ui;

import io.github.kongweiguang.xjar.plus.app.json.JsonSupport;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebJobServiceTest {
    private static final String FIRST_ID = "e2be341c-a8a4-4df0-a349-3445c0ea8301";
    private static final String SECOND_ID = "a737df88-5670-4329-9360-97889014fd29";

    /**
     * 在可访问任务记录的包内验证状态模型可序列化，避免为测试扩大生产可见性。
     */
    @Test
    void serializesJobViewWithoutExposingRequestSecrets() {
        final String serialized = JsonSupport.write(new WebJobService.JobView(
                "job-id", "RUNNING", "encrypt", 37, "处理中"), false);
        final JsonNode parsed = JsonSupport.readObject(serialized.getBytes(StandardCharsets.UTF_8));

        assertEquals("job-id", parsed.get("id").stringValue());
        assertEquals("RUNNING", parsed.get("state").stringValue());
        assertEquals(37, parsed.get("percent").intValue());
        assertFalse(serialized.contains("password"));
    }

    /**
     * 验证取消排队任务会释放有界容量，而不是将已取消任务留在队列中。
     */
    @Test
    void cancellingQueuedJobsReleasesWorkerQueueCapacity() throws Exception {
        final CountDownLatch workersEntered = new CountDownLatch(2);
        final CountDownLatch releaseWorkers = new CountDownLatch(1);
        final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16), task -> {
            final Thread thread = new Thread(task, "xjar-plus-ui-job-test");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        final Runnable blocker = () -> {
            workersEntered.countDown();
            try {
                releaseWorkers.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        workers.execute(blocker);
        workers.execute(blocker);
        final WebJobService service = new WebJobService(workers);
        try {
            assertTrue(workersEntered.await(2, TimeUnit.SECONDS));
            final byte[] request = "{\"mode\":\"standalone\",\"input\":\"source.jar\","
                    .concat("\"output\":\"result.jar\",\"password\":\"secret\"}")
                    .getBytes(StandardCharsets.UTF_8);
            final List<String> ids = new ArrayList<>();
            for (int index = 0; index < 16; index++) {
                ids.add(service.submit(request));
            }
            assertEquals(16, workers.getQueue().size());
            ids.forEach(id -> assertTrue(service.cancel(id)));
            assertEquals(0, workers.getQueue().size());

            final String acceptedId = service.submit(request);
            assertNotNull(acceptedId);
            assertTrue(service.cancel(acceptedId));
        } finally {
            releaseWorkers.countDown();
            service.close();
        }
    }

    /**
     * 验证重复标识复用同一排队操作，不同标识则分别进入核心调用边界。
     */
    @Test
    void requestIdSubmitsOnceAndDistinctIdsExecuteIndependently() throws Exception {
        final CountDownLatch workerEntered = new CountDownLatch(1);
        final CountDownLatch releaseWorker = new CountDownLatch(1);
        final ThreadPoolExecutor workers = blockedWorker(workerEntered, releaseWorker);
        final AtomicInteger executions = new AtomicInteger();
        final ConcurrentLinkedQueue<String> passwords = new ConcurrentLinkedQueue<>();
        final WebJobService service = new WebJobService(workers, (encryption, launcher) -> {
            assertNotNull(encryption);
            assertNull(launcher);
            executions.incrementAndGet();
            final char[] secret = encryption.password();
            try {
                passwords.add(new String(secret));
            } finally {
                java.util.Arrays.fill(secret, '\0');
            }
            throw new IOException("controlled test failure");
        });
        try {
            assertTrue(workerEntered.await(2, TimeUnit.SECONDS));
            final String firstId = service.submit(request(FIRST_ID, "first-secret"));
            final String duplicateId = service.submit(request(FIRST_ID, "duplicate-secret"));
            assertEquals(1, workers.getQueue().size());
            final String secondId = service.submit(request(SECOND_ID, "second-secret"));
            assertEquals(FIRST_ID, firstId);
            assertEquals(firstId, duplicateId);
            assertEquals(SECOND_ID, secondId);
            assertEquals(2, workers.getQueue().size());

            releaseWorker.countDown();
            assertEquals("FAILED", awaitTerminal(service, firstId).state());
            assertEquals("FAILED", awaitTerminal(service, secondId).state());
            assertEquals(2, executions.get());
            assertTrue(passwords.contains("first-secret"));
            assertTrue(passwords.contains("second-secret"));
            assertFalse(passwords.contains("duplicate-secret"));

            final String terminalDuplicate = service.submit(request(FIRST_ID, "replacement-secret"));
            assertEquals(firstId, terminalDuplicate);
            assertEquals("FAILED", service.get(firstId).state());
            assertEquals(2, executions.get());
        } finally {
            releaseWorker.countDown();
            service.close();
        }
    }

    /**
     * 在请求占用队列槽位前拒绝格式错误或过长的请求标识。
     */
    @Test
    void rejectsInvalidRequestIds() throws Exception {
        try (WebJobService service = new WebJobService()) {
            assertThrows(IllegalArgumentException.class,
                    () -> service.submit(request("not-a-uuid", "secret")));
            assertThrows(IllegalArgumentException.class,
                    () -> service.submit(request("00000000-0000-0000-0000-0000000000000", "secret")));
        }
    }

    /**
     * 入队前拒绝重复键、尾随 JSON 文档以及隐式字段类型转换。
     */
    @Test
    void rejectsAmbiguousJsonAndWrongFieldTypes() throws Exception {
        final String prefix = "{\"mode\":\"standalone\",\"input\":\"source.jar\","
                + "\"output\":\"result.jar\",\"password\":\"synthetic-secret\"";
        final List<String> invalidRequests = List.of(
                prefix + ",\"password\":\"another-secret\"}",
                prefix + "} {}",
                "{\"mode\":\"standalone\",\"input\":\"source.jar\","
                        + "\"output\":\"result.jar\",\"password\":17}",
                prefix + ",\"encryptDependencies\":\"true\"}",
                prefix + ",\"includes\":[\"valid\",42]}");
        try (WebJobService service = new WebJobService()) {
            for (String invalid : invalidRequests) {
                final IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                        () -> service.submit(invalid.getBytes(StandardCharsets.UTF_8)));
                assertFalse(error.getMessage().contains("synthetic-secret"));
            }
        }
    }

    /**
     * 再次提交相同请求标识时保留已有终态记录。
     */
    @Test
    void terminalRequestIdReturnsOriginalRecord() throws Exception {
        final CountDownLatch workerEntered = new CountDownLatch(1);
        final CountDownLatch releaseWorker = new CountDownLatch(1);
        final ThreadPoolExecutor workers = blockedWorker(workerEntered, releaseWorker);
        final WebJobService service = new WebJobService(workers, (encryption, launcher) -> {
            throw new AssertionError("a cancelled queued job must never invoke core");
        });
        try {
            assertTrue(workerEntered.await(2, TimeUnit.SECONDS));
            final String id = service.submit(request(FIRST_ID, "secret"));
            assertTrue(service.cancel(id));
            assertEquals("CANCELLED", service.get(id).state());

            assertEquals(id, service.submit(request(FIRST_ID, "replacement-secret")));
            assertEquals("CANCELLED", service.get(id).state());
            assertEquals(0, workers.getQueue().size());
        } finally {
            releaseWorker.countDown();
            service.close();
        }
    }

    /**
     * 将意外 Error 标记为失败并重新抛给 Future，同时确保工作线程之后仍可用。
     */
    @Test
    void marksAssertionErrorFailedAndProcessesNextJob() throws Exception {
        final AtomicInteger executions = new AtomicInteger();
        final WebJobService service = new WebJobService(newWorkers(), (encryption, launcher) -> {
            if (executions.incrementAndGet() == 1) {
                throw new AssertionError("internal sentinel must not reach the browser");
            }
            throw new IOException("controlled follow-up failure");
        });
        try {
            final String firstId = service.submit(request(FIRST_ID, "first-secret"));
            final WebJobService.JobView first = awaitTerminal(service, firstId);
            assertEquals("FAILED", first.state());
            assertTrue(first.message().contains("内部错误"));
            assertFalse(first.message().contains("internal sentinel"));

            final String secondId = service.submit(request(SECOND_ID, "second-secret"));
            assertEquals("FAILED", awaitTerminal(service, secondId).state());
            assertEquals(2, executions.get());
        } finally {
            service.close();
        }
    }

    /**
     * 构造完整的最小独立模式请求，使所有拒绝用例都经过请求标识校验。
     */
    private byte[] request(String requestId, String password) {
        return ("{\"requestId\":\"" + requestId + "\",\"mode\":\"standalone\","
                + "\"input\":\"source.jar\",\"output\":\"result.jar\",\"password\":\""
                + password + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 创建守护工作线程，使服务测试保持确定性且不启动外部进程。
     */
    private ThreadPoolExecutor newWorkers() {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16), task -> {
            final Thread thread = new Thread(task, "xjar-plus-ui-job-test");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 占用唯一工作线程直至释放，使测试能在任务执行前检查队列。
     */
    private ThreadPoolExecutor blockedWorker(CountDownLatch entered, CountDownLatch release) {
        final ThreadPoolExecutor workers = newWorkers();
        workers.execute(() -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
            }
        });
        return workers;
    }

    /**
     * 使用固定时限等待，确保异步生命周期断言及时失败而不是卡住测试。
     */
    private WebJobService.JobView awaitTerminal(WebJobService service, String id) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            final WebJobService.JobView view = service.get(id);
            if (view != null && !"QUEUED".equals(view.state()) && !"RUNNING".equals(view.state())) {
                return view;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("Job did not reach a terminal state: " + id);
    }
}
