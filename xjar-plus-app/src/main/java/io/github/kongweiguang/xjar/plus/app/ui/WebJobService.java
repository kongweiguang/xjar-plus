/* @author kongweiguang */

package io.github.kongweiguang.xjar.plus.app.ui;

import io.github.kongweiguang.xjar.plus.api.BuildResult;
import io.github.kongweiguang.xjar.plus.api.CancellationToken;
import io.github.kongweiguang.xjar.plus.api.EncryptionRequest;
import io.github.kongweiguang.xjar.plus.api.LauncherRequest;
import io.github.kongweiguang.xjar.plus.api.ProgressListener;
import io.github.kongweiguang.xjar.plus.api.XJarPlus;
import io.github.kongweiguang.xjar.plus.app.json.JsonSupport;
import tools.jackson.databind.JsonNode;

import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * 以有界异步任务执行核心操作，避免浏览器轮询线程承担归档工作。
 */
final class WebJobService implements AutoCloseable {
    private static final int MAX_RETAINED = 128;
    private static final long RETAIN_MILLIS = TimeUnit.MINUTES.toMillis(30);
    private static final Pattern REQUEST_ID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers;
    private final JobOperation operation;
    private boolean closed;

    /**
     * 同时限制运行中和排队中的任务，避免本机被意外请求洪泛耗尽资源。
     */
    WebJobService() {
        this(createWorkers());
    }

    /**
     * 允许确定性测试任务队列，同时将生产线程池限制集中在一个工厂中。
     */
    WebJobService(ThreadPoolExecutor workers) {
        this(workers, WebJobService::runCoreOperation);
    }

    /**
     * 注入核心调用边界，使队列和严重错误处理无需真实归档即可测试。
     */
    WebJobService(ThreadPoolExecutor workers, JobOperation operation) {
        this.workers = Objects.requireNonNull(workers, "workers");
        this.operation = Objects.requireNonNull(operation, "operation");
    }

    /**
     * 创建本地界面任务使用的固定大小执行器，避免线程无界增长。
     */
    private static ThreadPoolExecutor createWorkers() {
        final ThreadFactory factory = task -> {
            final Thread thread = new Thread(task, "xjar-plus-ui-job");
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(16), factory, new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 执行一个核心入口，同时不通过测试接缝暴露内部请求存储。
     */
    private static BuildResult runCoreOperation(EncryptionRequest encryption, LauncherRequest launcher)
            throws Exception {
        return launcher == null ? XJarPlus.encrypt(encryption) : XJarPlus.packageLauncher(launcher);
    }

    /**
     * 限制返回浏览器的任务文本长度，并移除可能干扰状态显示的控制字符。
     */
    private static String safeText(String value, int limit) {
        if (value == null || value.isBlank()) {
            return "处理中";
        }
        final String normalized = value.replaceAll("[\\p{Cntrl}&&[^\\t]]", " ").strip();
        return normalized.substring(0, Math.min(limit, normalized.length()));
    }

    /**
     * 入队前校验有界 JSON 请求，并返回用于进度轮询的稳定标识。
     */
    synchronized String submit(byte[] body) throws RejectedJobException {
        final Request request = Request.parse(body);
        if (closed) {
            request.clearPassword();
            throw new RejectedJobException("本地界面服务已关闭。");
        }
        prune();
        if (request.requestId != null) {
            final Job existing = jobs.get(request.requestId);
            if (existing != null) {
                request.clearPassword();
                return existing.id;
            }
        }
        makeRoomIfFull();
        if (jobs.size() >= MAX_RETAINED) {
            request.clearPassword();
            throw new RejectedJobException("任务记录已满，请稍后再试。");
        }
        final String id = request.requestId == null ? UUID.randomUUID().toString() : request.requestId;
        final Job job = new Job(id, System.currentTimeMillis(), request);
        jobs.put(job.id, job);
        try {
            job.future = workers.submit(() -> execute(job));
            if (!"QUEUED".equals(job.state.get())) {
                job.future = null;
            }
        } catch (java.util.concurrent.RejectedExecutionException e) {
            jobs.remove(job.id);
            request.clearPassword();
            throw new RejectedJobException("当前已有任务排队，请等待完成后重试。");
        }
        return job.id;
    }

    /**
     * 仅读取用户可见的任务状态；凭据和原始请求字段不会离开此服务。
     */
    JobView get(String id) {
        final Job job = jobs.get(id);
        return job == null ? null : job.view();
    }

    /**
     * 请求协作式取消，并在任务尚未开始时移除排队工作。
     */
    boolean cancel(String id) {
        final Job job = jobs.get(id);
        if (job == null) {
            return false;
        }
        job.cancelled.set(true);
        final Future<?> future = job.future;
        if ("QUEUED".equals(job.state.get()) && future != null && future.cancel(false)) {
            workers.purge();
            job.request.clearPassword();
            job.finish("CANCELLED", "任务已取消");
        } else if ("RUNNING".equals(job.state.get())) {
            job.message.set("正在请求取消");
        }
        return true;
    }

    /**
     * 调用共享核心 API，并将进度、取消和失败映射到封闭的任务状态机。
     */
    private void execute(Job job) {
        char[] password = null;
        try {
            if (job.cancelled.get()) {
                job.finish("CANCELLED", "任务已取消");
                return;
            }
            job.state.set("RUNNING");
            final ProgressListener progress = (stage, percent) -> {
                job.stage.set(safeText(stage, 100));
                job.percent.set(Math.max(0, Math.min(100, percent)));
                job.message.set(job.cancelled.get() ? "正在请求取消" : "处理中");
            };
            final CancellationToken cancellation = new CancellationToken() {
                /** 允许核心在事务边界检查取消状态，避免不安全地中断归档读写。 */
                @Override
                public boolean isCancelled() {
                    return job.cancelled.get();
                }

                /** 将取消转换为独立结果，使界面能够安全地重新发起任务。 */
                @Override
                public void check() {
                    if (job.cancelled.get()) {
                        throw new CancellationException("任务已取消");
                    }
                }
            };
            password = job.request.password.toCharArray();
            if ("standalone".equals(job.request.mode)) {
                try (EncryptionRequest request =
                             job.request.encryptionRequest(password, progress, cancellation, true)) {
                    final BuildResult result = operation.execute(request, null);
                    job.finish("SUCCEEDED", "加密完成：" + result.output());
                }
            } else {
                executeLauncher(job, password, progress, cancellation);
            }
        } catch (CancellationException e) {
            job.finish("CANCELLED", "任务已取消");
        } catch (Exception e) {
            String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            if (!job.request.password.isEmpty()) {
                message = message.replace(job.request.password, "[已隐藏]");
            }
            job.finish("FAILED", safeText(message, 500));
        } finally {
            try {
                if (!job.isTerminal()) {
                    job.finish("FAILED", "任务发生内部错误，请检查输入和运行环境后重试。");
                }
            } finally {
                job.future = null;
                if (password != null) {
                    Arrays.fill(password, '\0');
                }
                job.request.clearPassword();
            }
        }
    }

    /**
     * 负责组合启动器请求，使打包选项不渗入共享任务状态机。
     */
    private void executeLauncher(Job job, char[] password, ProgressListener progress,
                                 CancellationToken cancellation) throws Exception {
        final EncryptionRequest encryption =
                job.request.encryptionRequest(password, progress, cancellation, false);
        try (encryption) {
            final LauncherRequest.Builder builder = LauncherRequest.builder()
                    .encryption(encryption)
                    .output(Path.of(job.request.output))
                    .target(job.request.target)
                    .issuerFile(Path.of(job.request.issuerFile))
                    .appId(job.request.appId)
                    .notAfter(job.request.notAfter == null ? null : Instant.parse(job.request.notAfter));
            if (job.request.notBefore != null) {
                builder.notBefore(Instant.parse(job.request.notBefore));
            }
            if (job.request.jdkZip != null && !job.request.jdkZip.isBlank()) {
                builder.jdkZip(Path.of(job.request.jdkZip));
            }
            if (job.request.goExecutable != null && !job.request.goExecutable.isBlank()) {
                builder.goExecutable(Path.of(job.request.goExecutable));
            }
            job.request.jvmArgs.forEach(builder::jvmArg);
            job.request.appArgs.forEach(builder::appArg);
            try (LauncherRequest launcherRequest = builder.build()) {
                final BuildResult result = operation.execute(null, launcherRequest);
                job.finish("SUCCEEDED", "启动器打包完成：" + result.output());
            }
        }
    }

    /**
     * 幂等查找前移除过期终态记录，避免过早驱逐仍可复用的任务标识。
     */
    private void prune() {
        final long cutoff = System.currentTimeMillis() - RETAIN_MILLIS;
        jobs.values().removeIf(job -> job.isTerminal() && job.createdAt < cutoff);
    }

    /**
     * 幂等查找完成后才驱逐最旧的一条终态记录，确保请求可复用已有标识。
     */
    private void makeRoomIfFull() {
        if (jobs.size() < MAX_RETAINED) {
            return;
        }
        jobs.values().stream().filter(Job::isTerminal).min(java.util.Comparator.comparingLong(job -> job.createdAt))
                .ifPresent(job -> jobs.remove(job.id, job));
    }

    /**
     * 停止排队任务，并请求运行中的核心操作到达协作式取消检查点。
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        jobs.values().forEach(job -> {
            job.cancelled.set(true);
            final Future<?> future = job.future;
            if ("QUEUED".equals(job.state.get()) && future != null && future.cancel(false)) {
                job.request.clearPassword();
                job.finish("CANCELLED", "本地界面服务已关闭");
            }
        });
        workers.shutdownNow();
        try {
            workers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 运行单次核心调用，使队列行为测试无需真正处理归档或启动器。
     */
    @FunctionalInterface
    interface JobOperation {
        /**
         * 根据非空请求执行独立加密或启动器打包。
         */
        BuildResult execute(EncryptionRequest encryption, LauncherRequest launcher) throws Exception;
    }

    private static final class Job {
        private final String id;
        private final long createdAt;
        private final Request request;
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicReference<String> state = new AtomicReference<>("QUEUED");
        private final AtomicReference<String> stage = new AtomicReference<>("排队中");
        private final AtomicReference<String> message = new AtomicReference<>("任务已提交");
        private final java.util.concurrent.atomic.AtomicInteger percent =
                new java.util.concurrent.atomic.AtomicInteger();
        private volatile Future<?> future;

        /**
         * 将私有请求与进度状态分开保存，确保浏览器轮询不包含输入值。
         */
        private Job(String id, long createdAt, Request request) {
            this.id = id;
            this.createdAt = createdAt;
            this.request = request;
        }

        /**
         * 将当前字段复制到轮询记录，并在结果详情更新后发布终态。
         */
        private JobView view() {
            return new JobView(id, state.get(), stage.get(), percent.get(), message.get());
        }

        /**
         * 先发布详情再发布终态，避免轮询观察到不完整结果。
         */
        private void finish(String finalState, String finalMessage) {
            stage.set(finalState);
            percent.set("SUCCEEDED".equals(finalState) ? 100 : percent.get());
            message.set(safeText(finalMessage, 500));
            state.set(finalState);
        }

        /**
         * 区分已保留的结果记录和仍占用队列或线程容量的工作。
         */
        private boolean isTerminal() {
            final String current = state.get();
            return "SUCCEEDED".equals(current) || "FAILED".equals(current) || "CANCELLED".equals(current);
        }
    }

    record JobView(String id, String state, String stage, int percent, String message) {
    }

    private static final class Request {
        private final String mode;
        private final String input;
        private final String output;
        private final List<String> includes;
        private final List<String> excludes;
        private final boolean encryptDependencies;
        private final String appId;
        private final String issuerFile;
        private final String target;
        private final String jdkZip;
        private final String goExecutable;
        private final List<String> jvmArgs;
        private final List<String> appArgs;
        private final String notBefore;
        private final String notAfter;
        private String requestId;
        private String password;

        /**
         * 仅保存核心所需字段，并确保密码不进入响应或日志模型。
         */
        private Request(JsonNode json) {
            mode = text(json, "mode");
            input = text(json, "input");
            output = text(json, "output");
            includes = strings(json, "includes");
            excludes = strings(json, "excludes");
            encryptDependencies = booleanValue(json, "encryptDependencies");
            appId = text(json, "appId");
            issuerFile = text(json, "issuerFile");
            target = text(json, "target");
            jdkZip = optionalText(json, "jdkZip");
            goExecutable = optionalText(json, "goExecutable");
            jvmArgs = strings(json, "jvmArgs");
            appArgs = strings(json, "appArgs");
            notBefore = optionalText(json, "notBefore");
            notAfter = optionalText(json, "notAfter");
            requestId = optionalText(json, "requestId");
            password = text(json, "password");
        }

        /**
         * 仅解析已知界面字段，并在接触文件或进程前拒绝缺少必要内容的请求。
         */
        private static Request parse(byte[] body) {
            final JsonNode json = JsonSupport.readObject(body);
            final Request request = new Request(json);
            try {
                return validate(request);
            } catch (RuntimeException e) {
                request.clearPassword();
                throw e;
            }
        }

        /**
         * 请求占用有界线程容量前执行针对性校验。
         */
        private static Request validate(Request request) {
            validateRequestId(request);
            validateMode(request);
            validateRequiredFields(request);
            validatePassword(request);
            validateLauncherFields(request);
            validateDates(request);
            return request;
        }

        /**
         * 将规范 UUID 用作缓存键，避免宽松解析别名和过长标识。
         */
        private static void validateRequestId(Request request) {
            if (request.requestId == null) {
                return;
            }
            if (request.requestId.length() != 36 || !REQUEST_ID_PATTERN.matcher(request.requestId).matches()) {
                throw new IllegalArgumentException("requestId 必须是标准 UUID 格式。");
            }
            request.requestId = UUID.fromString(request.requestId).toString();
        }

        /**
         * 在解释模式专属字段之前拒绝未知模式。
         */
        private static void validateMode(Request request) {
            if (!("standalone".equals(request.mode) || "launcher".equals(request.mode))) {
                throw new IllegalArgumentException("请选择独立 JAR 或 Go 启动器模式。");
            }
        }

        /**
         * 后台任务入队前要求输入、输出路径和非空密码齐全。
         */
        private static void validateRequiredFields(Request request) {
            if (request.input.isBlank() || request.output.isBlank() || request.password.isEmpty()) {
                throw new IllegalArgumentException("输入、输出和密码均为必填项。");
            }
        }

        /**
         * 将密码转换为请求持有的字符数组前先限制其长度。
         */
        private static void validatePassword(Request request) {
            if (request.password.length() > 65_536) {
                throw new IllegalArgumentException("密码最多允许 64 Ki 个字符。");
            }
        }

        /**
         * 仅启动器模式要求授权配置，独立加密不受其影响。
         */
        private static void validateLauncherFields(Request request) {
            if ("launcher".equals(request.mode)
                && (request.appId.isBlank() || request.issuerFile.isBlank() || request.target.isBlank())) {
                throw new IllegalArgumentException("启动器模式需要 appId、发行方凭据和目标平台。");
            }
        }

        /**
         * 一次性解析可选许可证时间，并拒绝空值或倒置的有效期。
         */
        private static void validateDates(Request request) {
            final Instant notBefore = parseOptionalInstant(request.notBefore);
            final Instant notAfter = parseOptionalInstant(request.notAfter);
            if (notBefore != null && notAfter != null && !notAfter.isAfter(notBefore)) {
                throw new IllegalArgumentException("许可证结束时间必须晚于开始时间。");
            }
        }

        /**
         * 解析可选许可证时间且不保留解析异常，防止用户输入经异常消息泄漏。
         */
        private static Instant parseOptionalInstant(String value) {
            if (value == null) {
                return null;
            }
            try {
                return Instant.parse(value);
            } catch (DateTimeParseException e) {
                throw new IllegalArgumentException("许可证时间格式无效，请使用 ISO-8601 时间。");
            }
        }

        /**
         * 转换可选 JSON 字段，避免把缺失或 null 值变成字面文本“null”。
         */
        private static String optionalText(JsonNode json, String name) {
            return JsonSupport.optionalText(json, name);
        }

        /**
         * 将必填 JSON 文本转换为空字符串形式，供统一校验使用。
         */
        private static String text(JsonNode json, String name) {
            final String value = optionalText(json, name);
            return value == null ? "" : value;
        }

        /**
         * 仅接受 JSON 布尔值，避免字符串或数字被悄悄解释成加密依赖开关。
         */
        private static boolean booleanValue(JsonNode json, String name) {
            if (!json.has(name)) {
                return false;
            }
            final JsonNode value = json.get(name);
            if (!value.isBoolean()) {
                throw new IllegalArgumentException(name + " 必须是布尔值。");
            }
            return value.booleanValue();
        }

        /**
         * 接受有界文本数组并拒绝对象或数字，避免 CLI 设置被意外强制转换。
         */
        private static List<String> strings(JsonNode json, String name) {
            if (!json.has(name) || json.get(name).isNull()) {
                return List.of();
            }
            final JsonNode array = json.get(name);
            if (!array.isArray()) {
                throw new IllegalArgumentException(name + " 必须是文本数组。");
            }
            if (array.size() > 128) {
                throw new IllegalArgumentException(name + " 最多允许 128 项。");
            }
            final List<String> values = new ArrayList<>(array.size());
            for (JsonNode element : array) {
                if (!element.isTextual()) {
                    throw new IllegalArgumentException(name + " 只能包含文本。");
                }
                values.add(element.stringValue());
            }
            return List.copyOf(values);
        }

        /**
         * 创建不可变核心请求，不将输出路径等输入状态复制到界面任务响应中。
         */
        private EncryptionRequest encryptionRequest(char[] secret, ProgressListener progress,
                                                    CancellationToken cancellation, boolean needsOutput) {
            final EncryptionRequest.Builder builder = EncryptionRequest.builder()
                    .input(Path.of(input))
                    .password(secret)
                    .encryptDependencies(encryptDependencies)
                    .progress(progress)
                    .cancellation(cancellation);
            if (needsOutput) {
                builder.output(Path.of(output));
            }
            includes.forEach(builder::include);
            excludes.forEach(builder::exclude);
            return builder.build();
        }

        /**
         * 使用后释放界面侧密码引用；不可变字符串无法从 JVM 内存中物理擦除。
         */
        private void clearPassword() {
            password = "";
        }
    }

    static final class RejectedJobException extends Exception {
        /**
         * 将确定的容量拒绝与结果不明的服务端错误区分，以便映射为 HTTP 429。
         */
        RejectedJobException(String message) {
            super(message);
        }
    }
}
