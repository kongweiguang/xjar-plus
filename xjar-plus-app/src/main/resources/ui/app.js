/* @author kongweiguang */
"use strict";

const sessionToken = document.querySelector('meta[name="xjp-session"]').content;
const form = document.getElementById("taskForm");
const feedback = document.getElementById("feedback");
const jobPanel = document.getElementById("jobPanel");
const startButton = document.getElementById("startButton");
const cancelButton = document.getElementById("cancelButton");
const retryButton = document.getElementById("retryButton");
const maxConfigImportBytes = 256 * 1024;
let currentMode = "standalone";
let currentJob = null;
let restoredJobNoticePending = false;
let pollTimer = null;
let pollingJob = null;
let activePicker = null;
const pendingJobStorageKey = "xjp.pending-job.v1";
const terminalStageLabels = Object.freeze({SUCCEEDED: "已完成", FAILED: "失败", CANCELLED: "已取消"});
const knownJobStates = new Set(["QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "CANCELLED"]);

// 模式切换同时更新标签和必填字段，避免表单提交被隐藏的启动器配置。
document.getElementById("modeStandalone").addEventListener("click", () => setMode("standalone"));
document.getElementById("modeLauncher").addEventListener("click", () => setMode("launcher"));

// 只有用户显式操作才请求文件选择；空结果表示取消，并保留当前字段值。
document.querySelectorAll("[data-pick]").forEach(button => {
    button.addEventListener("click", async () => {
        if (activePicker) {
            if (activePicker.button === button) await cancelActivePicker();
            return;
        }
        const target = document.getElementById(button.dataset.target);
        const picker = {button, label: button.textContent, cancelling: false};
        activePicker = picker;
        setModeControlsDisabled(true);
        button.textContent = "取消选择";
        form.querySelectorAll("input,select,textarea,button").forEach(control => {
            control.disabled = control !== button;
        });
        setFeedback("正在打开系统选择窗口。请完成或取消后返回页面。", false);
        try {
            const response = await apiFetch("/api/picker", {
                method: "POST",
                headers: {"Content-Type": "application/json"},
                body: JSON.stringify({kind: button.dataset.pick})
            });
            const result = await response.json();
            if (!response.ok) {
                throw new Error(result.error || "系统选择器不可用，请手动粘贴路径。");
            }
            if (result.path) {
                target.value = result.path;
                clearError(target.id);
                setFeedback("路径已回填；加密需要你再次点击开始。", false);
            } else {
                setFeedback("已取消选择，原路径保持不变。", false);
            }
        } catch (error) {
            setFeedback(error.message || "系统选择器失败，请手动粘贴路径。", true);
        } finally {
            if (activePicker === picker) {
                activePicker = null;
                button.textContent = picker.label;
                form.querySelectorAll("input,select,textarea,button").forEach(control => {
                    control.disabled = false;
                });
                setModeControlsDisabled(Boolean(currentJob));
                button.focus();
            }
        }
    });
});

// 校验后清空可见密码字段；不声称运行时字符串已被物理擦除。
form.addEventListener("submit", async event => {
    event.preventDefault();
    await submitCurrentForm();
});

// 已知任务只恢复轮询；终态重试从当前字段重新构建请求，不复用旧密码。
retryButton.addEventListener("click", async () => {
    if (currentJob) {
        if (pollingJob === currentJob) return;
        retryButton.classList.add("hidden");
        await pollJob(currentJob);
        return;
    }
    await submitCurrentForm();
});

// 取消采用协作式方式，使核心能够到达安全的输出事务边界。
cancelButton.addEventListener("click", async () => {
    const requestedJob = currentJob;
    if (!requestedJob) return;
    cancelButton.disabled = true;
    try {
        const response = await apiFetch(`/api/jobs/${encodeURIComponent(requestedJob)}/cancel`, {method: "POST"});
        const result = await response.json();
        if (currentJob !== requestedJob) return;
        if (!response.ok) throw new Error(result.error || "取消任务请求失败。");
        setFeedback("已请求取消，正在等待任务安全停止。", false);
    } catch (error) {
        if (currentJob !== requestedJob) return;
        setFeedback(error.message || "取消请求失败。", true);
        cancelButton.disabled = false;
    }
});

// 导出排除凭据和启动参数，同时明确保留供本机复用的路径。
document.getElementById("exportConfig").addEventListener("click", () => {
    const request = buildRequest();
    delete request.password;
    delete request.issuerFile;
    delete request.jvmArgs;
    delete request.appArgs;
    const blob = new Blob([JSON.stringify(request, null, 2)], {type: "application/json"});
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = "xjar-plus-config.json";
    link.click();
    URL.revokeObjectURL(link.href);
    setFeedback("已导出模式与非敏感设置；密码、发行方凭据路径及 JVM/应用参数未包含。配置保留本机路径，请确认后再分享。", false);
});

// 导入限制文件读取大小，只应用已知设置，不覆盖密码或发行方凭据。
document.getElementById("importConfig").addEventListener("click", () => document.getElementById("configFile").click());
document.getElementById("configFile").addEventListener("change", async event => {
    const file = event.target.files[0];
    if (!file) return;
    try {
        if (!Number.isFinite(file.size) || file.size > maxConfigImportBytes) {
            throw new Error("配置文件超过 256 KiB 或大小无法确认，请选择较小的 JSON 文件。");
        }
        applyConfig(JSON.parse(await file.text()));
        setFeedback("配置已导入，请检查路径并单独填写密码。", false);
    } catch (error) {
        setFeedback("配置文件无效：" + (error.message || "无法读取 JSON。"), true);
    } finally {
        event.target.value = "";
    }
});

/** 同步可见模式和所选输出类型对应的待校验字段。 */
function setMode(mode) {
    if (currentJob || activePicker) return;
    currentMode = mode;
    const launcher = mode === "launcher";
    document.getElementById("launcherFields").classList.toggle("hidden", !launcher);
    document.getElementById("modeStandalone").classList.toggle("selected", !launcher);
    document.getElementById("modeStandalone").setAttribute("aria-pressed", String(!launcher));
    document.getElementById("modeLauncher").classList.toggle("selected", launcher);
    document.getElementById("modeLauncher").setAttribute("aria-pressed", String(launcher));
    document.getElementById("outputLabel").textContent = launcher ? "启动器输出目录" : "加密 JAR 输出路径";
    document.getElementById("outputPath").placeholder = launcher ? "选择用于生成启动器的目录" : "填写新文件路径";
    document.getElementById("outputPicker").dataset.pick = launcher ? "directory" : "save";
    startButton.querySelector("span:first-child").textContent = launcher ? "打包启动器" : "开始加密";
    clearErrors();
}

/** 构建符合核心请求形状的最小对象，并将本地日期输入转换为明确的 ISO 时间点。 */
function buildRequest() {
    return {
        mode: currentMode,
        input: document.getElementById("inputPath").value,
        output: document.getElementById("outputPath").value,
        password: document.getElementById("password").value,
        includes: lines("includes"),
        excludes: lines("excludes"),
        encryptDependencies: document.getElementById("encryptDependencies").checked,
        appId: document.getElementById("appId").value,
        issuerFile: document.getElementById("issuerFile").value,
        target: document.getElementById("target").value,
        jdkZip: optional("jdkZip"),
        goExecutable: optional("goExecutable"),
        jvmArgs: lines("jvmArgs"),
        appArgs: lines("appArgs"),
        notBefore: toInstant("notBefore"),
        notAfter: toInstant("notAfter")
    };
}

/** 网络提交前校验必要字段，归档语义仍由核心负责。 */
function validate(request) {
    let valid = true;
    ["input", "output", "password"].forEach(name => {
        const value = name === "input" ? request.input : name === "output" ? request.output : request.password;
        if (value.length === 0) valid = fail(name, "此项为必填项。") && valid;
    });
    if (request.mode === "launcher") {
        ["appId", "issuerFile", "target"].forEach(name => {
            if (request[name].length === 0) valid = fail(name, "启动器模式需要填写此项。") && valid;
        });
        if (request.notBefore && request.notAfter
            && (!Number.isFinite(Date.parse(request.notAfter))
                || Date.parse(request.notAfter) <= Date.parse(request.notBefore))) {
            valid = fail("notAfter", "到期时间必须晚于生效时间。") && valid;
        }
    }
    return valid;
}

/** 从可见表单重新构建并校验重试请求，避免修改路径后仍复用旧请求。 */
async function submitCurrentForm() {
    if (currentJob || activePicker) {
        const message = currentJob
            ? "已有任务正在处理或等待确认；请恢复查询，不会重复提交。"
            : "请先完成或取消系统文件选择，再开始任务。";
        setFeedback(message, true);
        return;
    }
    clearErrors();
    const request = buildRequest();
    if (!validate(request)) {
        setFeedback("请先修正标记出的字段。", true);
        return;
    }
    document.getElementById("password").value = "";
    retryButton.classList.add("hidden");
    await submitRequest(request);
}

/** POST 前仅保存高熵任务标识，使刷新恢复无需持久化请求数据。 */
async function submitRequest(request) {
    let requestId;
    try {
        requestId = createJobId();
    } catch (error) {
        request.password = "";
        setFeedback(error.message || "无法安全生成任务编号，任务未提交。", true);
        return;
    }
    if (!savePendingJobId(requestId)) {
        request.password = "";
        setFeedback("浏览器会话存储不可用，任务未提交。请改用支持会话存储的浏览器或 CLI。", true);
        return;
    }
    currentJob = requestId;
    setBusy(true);
    retryButton.classList.add("hidden");
    jobPanel.classList.remove("hidden");
    setJobView("提交中", "正在创建任务；如连接中断，将按任务编号恢复查询。", 0);
    setFeedback("任务正在排队。", false);
    try {
        const result = await postJob(request, requestId);
        if (result.id !== requestId) throw new Error("服务返回的任务编号不匹配，正在确认原任务状态。");
        cancelButton.classList.remove("hidden");
        cancelButton.disabled = false;
        await pollJob(requestId);
    } catch (error) {
        if (error.rejected) {
            currentJob = null;
            clearPendingJobId(requestId);
            setBusy(false);
            cancelButton.classList.add("hidden");
            retryButton.textContent = "重试";
            retryButton.classList.remove("hidden");
            setJobView("任务未受理", error.message, 0);
            setFeedback(error.message, true);
            return;
        }
        setJobView("确认任务状态", "提交结果暂不可确认；正在查询任务编号，不会自动重新提交。", 0);
        setFeedback(error.message || "提交结果未知；正在恢复查询。", true);
        await pollJob(requestId);
    } finally {
        request.password = "";
    }
}

/** 随任务请求发送标识，同时使其与凭据和 API 授权保持分离。 */
async function postJob(request, requestId) {
    const body = JSON.stringify({...request, requestId});
    request.password = "";
    const response = await apiFetch("/api/jobs", {
        method: "POST",
        headers: {"Content-Type": "application/json"},
        body
    });
    if (!response.ok) {
        let result = {};
        try {
            result = await response.json();
        } catch (error) {
            // HTTP 状态仍可区分确定的 4xx 拒绝与结果不明的响应。
        }
        const rejected = response.status >= 400 && response.status < 500;
        const message = typeof result?.error === "string" && result.error.length > 0
            ? result.error : `任务请求被拒绝（HTTP ${response.status}）。`;
        const failure = new Error(message);
        failure.rejected = rejected;
        throw failure;
    }
    const result = await response.json();
    return result;
}

/** 轮询有界任务状态；确认任务仍活动后才提示表单不会恢复，并保留失败或终态反馈。 */
async function pollJob(id) {
    if (currentJob !== id) return;
    if (pollingJob === id) return;
    pollingJob = id;
    try {
        const response = await apiFetch(`/api/jobs/${encodeURIComponent(id)}`, {method: "GET"});
        if (currentJob !== id) return;
        if (response.status === 404) {
            handleMissingJobRecord(id);
            return;
        }
        const job = await response.json();
        if (currentJob !== id) return;
        if (!response.ok) throw new Error(job.error || "读取任务状态失败。");
        if (!job || !knownJobStates.has(job.state)) throw new Error("服务返回未知任务状态，结果暂不可确认。");
        setJobView(visibleJobStage(job), job.message, job.percent);
        if (["SUCCEEDED", "FAILED", "CANCELLED"].includes(job.state)) {
            restoredJobNoticePending = false;
            currentJob = null;
            clearPendingJobId(id);
            setBusy(false);
            cancelButton.classList.add("hidden");
            retryButton.textContent = "重试";
            setFeedback(job.message, job.state === "FAILED");
            if (job.state !== "SUCCEEDED") retryButton.classList.remove("hidden");
            return;
        }
        if (restoredJobNoticePending) {
            restoredJobNoticePending = false;
            setFeedback("已恢复上次后台任务进度；刷新不会恢复原表单或密码，结束后新建任务请重新填写或导入配置。", false);
        }
        cancelButton.classList.remove("hidden");
        cancelButton.disabled = false;
        retryButton.classList.add("hidden");
        pollTimer = window.setTimeout(() => pollJob(id), 900);
    } catch (error) {
        if (currentJob !== id) return;
        setJobView("状态暂不可确认", "任务可能仍在运行；恢复查询只读取状态，不会重新提交。",
            document.getElementById("progressBar").value);
        setFeedback(error.message || "读取任务状态失败。可恢复查询或请求取消；当前任务编号仍保留。", true);
        setBusy(true);
        cancelButton.classList.remove("hidden");
        cancelButton.disabled = false;
        retryButton.textContent = "恢复查询";
        retryButton.classList.remove("hidden");
    } finally {
        if (pollingJob === id) pollingJob = null;
    }
}

/** 将记录缺失视为完成状态未知，清除延迟恢复提示，并要求先检查输出。 */
function handleMissingJobRecord(id) {
    if (currentJob !== id) return;
    restoredJobNoticePending = false;
    currentJob = null;
    clearPendingJobId(id);
    setBusy(false);
    cancelButton.classList.add("hidden");
    retryButton.textContent = "检查后新建";
    retryButton.classList.remove("hidden");
    setJobView("任务记录不存在", "服务可能已重启或记录已过期，任务结果无法确认。请先检查输出位置，再填写表单并显式新建。",
        document.getElementById("progressBar").value);
    setFeedback("未自动重新提交；确认输出状态后可填写表单并新建任务。", true);
}

/** 使用浏览器 CSPRNG 生成 UUIDv4，不从请求或凭据数据派生任务标识。 */
function createJobId() {
    const secureCrypto = window.crypto;
    if (secureCrypto && typeof secureCrypto.randomUUID === "function") {
        const id = secureCrypto.randomUUID();
        if (isValidJobId(id)) return id;
        throw new Error("浏览器生成的任务编号格式无效，任务未提交。");
    }
    if (!secureCrypto || typeof secureCrypto.getRandomValues !== "function") {
        throw new Error("浏览器无法安全生成任务编号，请使用最新版浏览器。任务未提交。");
    }
    const bytes = secureCrypto.getRandomValues(new Uint8Array(16));
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    const hex = Array.from(bytes, value => value.toString(16).padStart(2, "0")).join("");
    const id = `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
    if (!isValidJobId(id)) throw new Error("浏览器生成的任务编号格式无效，任务未提交。");
    return id;
}

/** 验证不透明 UUIDv4 契约，避免未校验的存储值进入任务状态 URL。 */
function isValidJobId(id) {
    return typeof id === "string"
        && /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(id);
}

/** 仅在当前标签页存储不透明任务标识，使刷新后只查询状态、不重放请求。 */
function savePendingJobId(id) {
    try {
        if (!window.sessionStorage) return false;
        window.sessionStorage.setItem(pendingJobStorageKey, id);
        return true;
    } catch (error) {
        return false;
    }
}

/** 移除已完成或不可用的任务标识，且不把存储清理作为任务成功条件。 */
function clearPendingJobId(id) {
    try {
        if (window.sessionStorage && window.sessionStorage.getItem(pendingJobStorageKey) === id) {
            window.sessionStorage.removeItem(pendingJobStorageKey);
        }
    } catch (error) {
        // 刷新后保留的终态标识只能触发只读状态查询。
    }
}

/** 仅通过 GET 恢复；确认记录仍活动后才显示恢复提示，失败和终态反馈保持优先。 */
function restorePendingJob() {
    let id;
    try {
        if (!window.sessionStorage) throw new Error("sessionStorage unavailable");
        id = window.sessionStorage.getItem(pendingJobStorageKey);
    } catch (error) {
        setFeedback("浏览器会话存储不可用；任务不能在刷新后自动恢复。请改用支持会话存储的浏览器或 CLI。", true);
        return;
    }
    if (!id) return;
    if (!isValidJobId(id)) {
        clearPendingJobId(id);
        setFeedback("待恢复任务编号无效，未发起状态请求。请检查输出位置后重新填写任务。", true);
        return;
    }
    currentJob = id;
    restoredJobNoticePending = true;
    jobPanel.classList.remove("hidden");
    setJobView("恢复中", "正在查询上次任务状态；不会重新提交请求。", 0);
    setBusy(true);
    cancelButton.classList.remove("hidden");
    cancelButton.disabled = false;
    retryButton.textContent = "恢复查询";
    retryButton.classList.remove("hidden");
    pollJob(id);
}

/** 将协议终态枚举转换为面向用户的文案，同时保留核心提供的本地化进度阶段。 */
function visibleJobStage(job) {
    return terminalStageLabels[job.state] || job.stage;
}

/** 每个 API 请求都附带会话令牌，避免仅凭 Cookie 授权浏览器状态修改。 */
function apiFetch(url, options) {
    const headers = new Headers(options.headers || {});
    headers.set("X-XJP-Session", sessionToken);
    return fetch(url, {...options, headers, credentials: "same-origin", cache: "no-store"});
}

/** 使用独立同源请求取消当前原生选择器，同时等待原始请求退出。 */
async function cancelActivePicker() {
    const picker = activePicker;
    if (!picker || picker.cancelling) return;
    picker.cancelling = true;
    picker.button.disabled = true;
    setFeedback("正在关闭系统选择窗口。", false);
    try {
        const response = await apiFetch("/api/picker/cancel", {method: "POST"});
        const result = await response.json();
        if (!response.ok) throw new Error(result.error || "取消选择请求失败。");
        setFeedback("已请求取消系统选择窗口。", false);
    } catch (error) {
        if (activePicker === picker) {
            picker.cancelling = false;
            picker.button.disabled = false;
        }
        setFeedback(error.message || "取消选择请求失败，请重试或手动填写路径。", true);
    }
}

/** 更新进度文案，并将进度条限制在核心承诺的范围内。 */
function setJobView(stage, message, percent) {
    const bounded = Math.max(0, Math.min(100, Number(percent) || 0));
    document.getElementById("jobStage").textContent = stage || "处理中";
    document.getElementById("jobMessage").textContent = message || "处理中";
    document.getElementById("jobPercent").textContent = `${bounded}%`;
    document.getElementById("progressBar").value = bounded;
}

/** 任务运行期间锁定请求字段，但保留取消和重新连接操作。 */
function setBusy(busy) {
    form.querySelectorAll("input,select,textarea,button").forEach(control => {
        if (control.id !== "cancelButton" && control.id !== "retryButton") control.disabled = busy;
    });
    setModeControlsDisabled(busy);
}

/** 任务或原生选择器占用当前表单上下文时，禁用表单外的模式控件。 */
function setModeControlsDisabled(disabled) {
    document.getElementById("modeStandalone").disabled = disabled;
    document.getElementById("modeLauncher").disabled = disabled;
}

/** 显示一条简洁消息，不将核心异常详情混入表单字段。 */
function setFeedback(message, error) {
    feedback.textContent = message;
    feedback.classList.toggle("error", Boolean(error));
}

/** 将必填字段错误显示为标签，同时保留用户当前输入值。 */
function fail(name, message) {
    const field = document.querySelector(`[data-error="${name}"]`)?.closest(".field");
    if (field) {
        field.classList.add("invalid");
        field.querySelector(`[data-error="${name}"]`).textContent = message;
    }
    return false;
}

/** 用户编辑后以及每轮新校验前清除表单错误。 */
function clearErrors() {
    document.querySelectorAll(".field.invalid").forEach(field => field.classList.remove("invalid"));
    document.querySelectorAll(".field-error").forEach(field => field.textContent = "");
}

/** 原生选择器或用户输入改变字段值后，清除该字段之前的校验状态。 */
function clearError(id) {
    const control = document.getElementById(id);
    const field = control?.closest(".field");
    if (field) {
        field.classList.remove("invalid");
        const error = field.querySelector(".field-error");
        if (error) error.textContent = "";
    }
}

/** 按换行读取重复参数，不进行 shell 分词或参数拼接。 */
function lines(id) {
    return document.getElementById(id).value.split(/\r?\n/).filter(value => value.trim().length > 0);
}

/** 未填写的可选路径按 null 发送，由服务端和核心沿用默认值。 */
function optional(id) {
    const value = document.getElementById(id).value;
    return value.length > 0 ? value : null;
}

/** 将浏览器本地日期时间转换为明确的 UTC 时间点，符合许可证契约。 */
function toInstant(id) {
    const value = document.getElementById(id).value;
    return value ? new Date(value).toISOString() : null;
}

/** 应用可复用设置但不覆盖秘密；导入值需要检查时展开相应折叠区。 */
function applyConfig(config) {
    if (!config || typeof config !== "object" || Array.isArray(config)) throw new Error("根节点必须是 JSON 对象。");
    if (config.mode === "standalone" || config.mode === "launcher") setMode(config.mode);
    const fields = {
        input: "inputPath", output: "outputPath", appId: "appId", target: "target",
        jdkZip: "jdkZip", goExecutable: "goExecutable", includes: "includes", excludes: "excludes",
        jvmArgs: "jvmArgs", appArgs: "appArgs"
    };
    Object.entries(fields).forEach(([key, id]) => {
        if (typeof config[key] === "string") document.getElementById(id).value = config[key];
        else if (Array.isArray(config[key])) document.getElementById(id).value = config[key].join("\n");
    });
    if (typeof config.encryptDependencies === "boolean") {
        document.getElementById("encryptDependencies").checked = config.encryptDependencies;
    }
    if (typeof config.notBefore === "string") document.getElementById("notBefore").value = toLocalDateTime(config.notBefore);
    if (typeof config.notAfter === "string") document.getElementById("notAfter").value = toLocalDateTime(config.notAfter);
    /** 检测非空的导入高级设置，避免折叠字段被静默隐藏。 */
    const hasText = value => Array.isArray(value) ? value.length > 0 : typeof value === "string" && value.trim().length > 0;
    if (config.encryptDependencies === true || hasText(config.includes) || hasText(config.excludes)) {
        document.getElementById("archiveAdvancedSettings").open = true;
    }
    if (["jdkZip", "goExecutable", "notBefore", "notAfter", "jvmArgs", "appArgs"].some(key => hasText(config[key]))) {
        document.getElementById("launcherAdvancedSettings").open = true;
    }
}

/** 将保存的 UTC 时间点转换为浏览器本地日期时间输入，保持时间点不变。 */
function toLocalDateTime(value) {
    const date = new Date(value);
    const local = new Date(date.getTime() - date.getTimezoneOffset() * 60_000);
    return local.toISOString().slice(0, 16);
}

// 用户编辑只清除对应字段错误；重试和任务状态保持不变。
form.querySelectorAll("input,select,textarea").forEach(control => {
    control.addEventListener("input", () => clearError(control.id));
    control.addEventListener("change", () => clearError(control.id));
});

restorePendingJob();
