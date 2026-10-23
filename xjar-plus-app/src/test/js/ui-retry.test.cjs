/* @author kongweiguang */
"use strict";

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const test = require("node:test");
const vm = require("node:vm");

const appSource = fs.readFileSync(path.resolve(__dirname, "../../main/resources/ui/app.js"), "utf8");
const uiSource = fs.readFileSync(path.resolve(__dirname, "../../main/resources/ui/index.html"), "utf8");

/** 仅模拟 app.js 使用的浏览器接口，使恢复和秘密保护边界保持确定。 */
class FakeElement {
    /** 初始化中性元素状态，使每个测试只设置自身涉及的字段。 */
    constructor(id) {
        this.id = id;
        this.value = "";
        this.checked = false;
        this.disabled = false;
        this.dataset = {};
        this.textContent = "";
        this.files = [];
        this.listeners = new Map();
        this.classList = new FakeClassList();
        this.attributes = new Map();
        this.focused = false;
        this.clicked = false;
    }

    /** 每个事件保留全部回调，因为浏览器允许一个控件注册多个监听器。 */
    addEventListener(name, callback) {
        const listeners = this.listeners.get(name);
        if (!listeners) this.listeners.set(name, callback);
        else if (Array.isArray(listeners)) listeners.push(callback);
        else this.listeners.set(name, [listeners, callback]);
    }

    /** 提供表单控件以及生产脚本会访问的唯一嵌套标签节点。 */
    querySelectorAll() {
        return this.id === "taskForm" ? this.controls : [];
    }

    /** 提供提交按钮标签；有效请求用例不会包含校验错误字段。 */
    querySelector(selector) {
        if (this.id === "startButton" && selector === "span:first-child") return this.label;
        return null;
    }

    /** 记录无障碍属性，无需实现完整浏览器 DOM。 */
    setAttribute(name, value) {
        this.attributes.set(name, value);
    }

    /** 跟踪显式焦点恢复，不模拟或抢占桌面窗口焦点。 */
    focus() {
        this.focused = true;
    }

    /** 记录生成链接的激活情况，使导出测试可以检查序列化配置。 */
    click() {
        this.clicked = true;
    }

    /** 返回无所属字段的状态，因为有效请求路径无需查找错误标记。 */
    closest() {
        return null;
    }
}

/** 实现任务状态和校验状态显示所需的最小 classList 契约。 */
class FakeClassList {
    /** 初始不带任何 class，便于从已知基线观察生产代码的状态变更。 */
    constructor() {
        this.values = new Set();
    }

    /** 幂等添加状态 class，与浏览器 DOMTokenList 行为一致。 */
    add(value) {
        this.values.add(value);
    }

    /** 幂等移除状态 class，与浏览器 DOMTokenList 行为一致。 */
    remove(value) {
        this.values.delete(value);
    }

    /** 添加或切换单个状态 class，并返回切换后的存在状态。 */
    toggle(value, force) {
        const shouldAdd = force === undefined ? !this.values.has(value) : Boolean(force);
        if (shouldAdd) this.values.add(value);
        else this.values.delete(value);
        return shouldAdd;
    }

    /** 检查 class 是否存在，供断言和类 DOM 调用方使用。 */
    contains(value) {
        return this.values.has(value);
    }
}

/** 为失败路径回归测试创建隔离的存储、密码学、HTTP 和计时器边界。 */
function createHarness(fetchHandler, options = {}) {
    const ids = [
        "taskForm", "feedback", "jobPanel", "startButton", "cancelButton", "retryButton",
        "modeStandalone", "modeLauncher", "launcherFields", "outputLabel", "outputPath", "outputPicker",
        "inputPath", "password", "includes", "excludes", "encryptDependencies", "appId", "issuerFile",
        "target", "jdkZip", "goExecutable", "jvmArgs", "appArgs", "notBefore", "notAfter",
        "jobStage", "jobMessage", "jobPercent", "progressBar", "exportConfig", "importConfig", "configFile",
        "archiveAdvancedSettings", "launcherAdvancedSettings"
    ];
    const elements = Object.fromEntries(ids.map(id => [id, new FakeElement(id)]));
    const controls = Object.values(elements).filter(element =>
        !["taskForm", "modeStandalone", "modeLauncher"].includes(element.id));
    elements.taskForm.controls = controls;
    elements.startButton.label = new FakeElement("startButtonLabel");
    elements.outputPicker.dataset.pick = "save";
    elements.outputPicker.dataset.target = "outputPath";
    const timers = [];
    const storage = options.storage || new Map();
    const sessionStorage = {
        getItem: key => storage.has(key) ? storage.get(key) : null,
        setItem: (key, value) => storage.set(key, String(value)),
        removeItem: key => storage.delete(key)
    };
    let jobSequence = 0;
    const secureCrypto = options.crypto || {
        randomUUID: () => `00000000-0000-4000-8000-${String(++jobSequence).padStart(12, "0")}`
    };
    const downloads = [];
    let downloadedBlob = null;
    const document = {
        getElementById: id => elements[id] || null,
        querySelector: selector => selector === 'meta[name="xjp-session"]' ? {content: "test-session"} : null,
        querySelectorAll: selector => selector === "[data-pick]" ? [elements.outputPicker] : [],
        createElement: () => {
            const element = new FakeElement("created");
            const activate = element.click.bind(element);
            element.click = () => {
                activate();
                downloads.push(element);
            };
            return element;
        }
    };
    const window = {setTimeout: callback => timers.push(callback), crypto: secureCrypto};
    if (options.sessionStorageUnavailable) {
        Object.defineProperty(window, "sessionStorage", {
            get: () => {
                throw new Error("storage blocked");
            }
        });
    } else {
        window.sessionStorage = sessionStorage;
    }
    const context = vm.createContext({
        document,
        fetch: fetchHandler,
        Headers,
        window,
        Blob,
        URL: {
            createObjectURL: blob => {
                downloadedBlob = blob;
                return "blob:configuration";
            },
            revokeObjectURL: () => {
            }
        },
        console
    });
    vm.runInContext(appSource, context, {filename: "app.js"});
    elements.inputPath.value = "original.jar";
    elements.outputPath.value = "first-output.jar";
    elements.password.value = "first-secret";
    return {
        context, elements, timers, storage, downloads, get downloadedBlob() {
            return downloadedBlob;
        }
    };
}

/** 创建类似 JSON 的 fetch 响应，无需启动 HTTP 服务。 */
function jsonResponse(ok, payload, status = ok ? 200 : 400) {
    return {ok, status, json: async () => payload};
}

/** 调用已注册的全部回调，保持与浏览器可叠加事件监听的契约一致。 */
async function dispatch(element, eventName, event) {
    const registered = element.listeners.get(eventName);
    const listeners = Array.isArray(registered) ? registered : [registered];
    for (const listener of listeners) await listener(event);
}

/** 验证终态重试会重新读取输入，且不复用上次请求中的秘密。 */
test("terminal retry submits edited output with a newly entered password and a fresh request ID", async function () {
    const calls = [];
    const harness = createHarness(async (url, options) => {
        calls.push({url, options});
        if (url === "/api/jobs") return jsonResponse(true, {id: JSON.parse(options.body).requestId});
        return jsonResponse(true, {state: "FAILED", stage: "失败", message: "output exists", percent: 0});
    });

    await harness.elements.taskForm.listeners.get("submit")({
        preventDefault() {
        }
    });
    assert.equal(harness.elements.password.value, "");
    harness.elements.outputPath.value = "second-output.jar";
    harness.elements.password.value = "second-secret";
    await harness.elements.retryButton.listeners.get("click")();

    const posts = calls.filter(call => call.url === "/api/jobs");
    assert.equal(posts.length, 2);
    const firstId = JSON.parse(posts[0].options.body).requestId;
    const secondId = JSON.parse(posts[1].options.body).requestId;
    assert.match(firstId, /^[0-9a-f-]{36}$/i);
    assert.notEqual(firstId, secondId);
    assert.deepEqual(JSON.parse(posts[0].options.body), {
        requestId: firstId,
        mode: "standalone",
        input: "original.jar",
        output: "first-output.jar",
        password: "first-secret",
        includes: [],
        excludes: [],
        encryptDependencies: false,
        appId: "",
        issuerFile: "",
        target: "",
        jdkZip: null,
        goExecutable: null,
        jvmArgs: [],
        appArgs: [],
        notBefore: null,
        notAfter: null
    });
    assert.equal(JSON.parse(posts[1].options.body).output, "second-output.jar");
    assert.equal(JSON.parse(posts[1].options.body).password, "second-secret");
    assert.equal(harness.elements.password.value, "");
    assert.equal(vm.runInContext("typeof lastRequest", harness.context), "undefined");
    assert.equal(harness.storage.size, 0);
});

/** 验证重新执行校验前必须由用户重新输入必填秘密，否则不会启动任务。 */
test("terminal retry without a fresh password does not create another job", async function () {
    const calls = [];
    const harness = createHarness(async (url, options) => {
        calls.push({url, options});
        if (url === "/api/jobs") return jsonResponse(true, {id: JSON.parse(options.body).requestId});
        return jsonResponse(true, {state: "FAILED", stage: "失败", message: "failed", percent: 0});
    });

    await harness.elements.taskForm.listeners.get("submit")({
        preventDefault() {
        }
    });
    await harness.elements.retryButton.listeners.get("click")();

    assert.equal(calls.filter(call => call.url === "/api/jobs").length, 1);
    assert.equal(harness.elements.feedback.textContent, "请先修正标记出的字段。");
    assert.equal(harness.elements.retryButton.classList.contains("hidden"), false);
});

/** 验证恢复活动任务时只发送状态 GET，不重复提交 POST。 */
test("retry while job identity is known resumes polling without posting", async function () {
    const calls = [];
    let statusReads = 0;
    let activeId;
    const harness = createHarness(async (url, options) => {
        calls.push({url, options});
        if (url === "/api/jobs") {
            activeId = JSON.parse(options.body).requestId;
            return jsonResponse(true, {id: activeId});
        }
        statusReads += 1;
        if (statusReads === 1) throw new Error("temporary connection failure");
        return jsonResponse(true, {state: "RUNNING", stage: "处理中", message: "still running", percent: 20});
    });

    await harness.elements.taskForm.listeners.get("submit")({
        preventDefault() {
        }
    });
    await harness.elements.retryButton.listeners.get("click")();

    assert.equal(calls.filter(call => call.url === "/api/jobs").length, 1);
    assert.equal(calls.filter(call => call.url === `/api/jobs/${activeId}`).length, 2);
    assert.equal(harness.timers.length, 1);
    assert.equal(harness.elements.retryButton.classList.contains("hidden"), true);
    assert.equal(harness.storage.get("xjp.pending-job.v1"), activeId);
});

/** 验证 POST 响应丢失后可在刷新时按标识恢复已受理任务，且不保存请求数据。 */
test("lost submission response survives refresh as a read-only recovery", async function () {
    const storage = new Map();
    const calls = [];
    let acceptedId;
    const first = createHarness(async (url, options) => {
        calls.push({url, options});
        if (url === "/api/jobs") {
            acceptedId = JSON.parse(options.body).requestId;
            return Promise.reject(new Error("connection lost after server acceptance"));
        }
        if (url === `/api/jobs/${acceptedId}`) {
            return jsonResponse(true, {state: "RUNNING", stage: "处理中", message: "still running", percent: 35});
        }
        throw new Error(`unexpected request: ${url}`);
    }, {storage});

    await first.elements.taskForm.listeners.get("submit")({
        preventDefault() {
        }
    });
    assert.equal(calls.filter(call => call.url === "/api/jobs").length, 1);
    assert.doesNotMatch(first.elements.feedback.textContent, /已恢复上次后台任务进度/);
    assert.deepEqual([...storage.values()], [acceptedId]);
    assert.equal(first.elements.cancelButton.classList.contains("hidden"), false);
    assert.equal(first.elements.modeLauncher.disabled, true);
    first.elements.modeLauncher.listeners.get("click")();
    assert.equal(vm.runInContext("currentMode", first.context), "standalone");

    const restoredCalls = [];
    const restored = createHarness(async (url, options) => {
        restoredCalls.push({url, options});
        if (url === `/api/jobs/${acceptedId}`) {
            return jsonResponse(true, {state: "RUNNING", stage: "处理中", message: "restored", percent: 40});
        }
        if (url.endsWith("/cancel")) return jsonResponse(true, {accepted: true}, 202);
        throw new Error(`unexpected request: ${url}`);
    }, {storage});
    await new Promise(resolve => setImmediate(resolve));

    assert.equal(restoredCalls.filter(call => call.url === "/api/jobs").length, 0);
    assert.deepEqual(restoredCalls.filter(call => call.url.startsWith("/api/jobs/"))
        .map(call => call.url), [`/api/jobs/${acceptedId}`]);
    assert.equal(restored.elements.cancelButton.classList.contains("hidden"), false);
    assert.equal(restored.elements.modeLauncher.disabled, true);
    assert.match(restored.elements.feedback.textContent, /已恢复上次后台任务进度/);
    assert.match(restored.elements.feedback.textContent, /刷新不会恢复原表单或密码/);
    assert.match(restored.elements.feedback.textContent, /重新填写或导入配置/);
    await restored.elements.cancelButton.listeners.get("click")();
    assert.equal(restoredCalls.some(call => call.url === `/api/jobs/${acceptedId}/cancel`), true);
    assert.equal(storage.get("xjp.pending-job.v1"), acceptedId);
});

/** 确保恢复提示不会覆盖记录缺失、网络失败或任务终态反馈。 */
test("restored task notice does not mask status errors or terminal results", async function () {
    const scenarios = [
        {
            name: "missing record",
            fetch: async () => jsonResponse(false, {error: "task record missing"}, 404),
            expected: /未自动重新提交/
        },
        {
            name: "network failure",
            fetch: async () => {
                throw new Error("status connection unavailable");
            },
            expected: /status connection unavailable/
        },
        {
            name: "terminal failure",
            fetch: async () => jsonResponse(true, {
                state: "FAILED", stage: "失败", message: "任务已失败", percent: 20
            }),
            expected: /任务已失败/
        }
    ];
    for (const scenario of scenarios) {
        const storage = new Map([[
            "xjp.pending-job.v1", "11111111-1111-4111-8111-111111111111"
        ]]);
        const harness = createHarness(scenario.fetch, {storage});
        await new Promise(resolve => setImmediate(resolve));

        assert.match(harness.elements.feedback.textContent, scenario.expected, scenario.name);
        assert.doesNotMatch(harness.elements.feedback.textContent, /已恢复上次后台任务进度/, scenario.name);
    }
});

/** 非法成功响应仍走 GET 状态恢复，避免静默重放结果不明的任务。 */
test("malformed submission response is queried once instead of being reposted", async function () {
    const calls = [];
    const harness = createHarness(async (url, options) => {
        calls.push({url, options});
        if (url === "/api/jobs") {
            return {
                ok: true, status: 202, json: async () => {
                    throw new SyntaxError("invalid JSON");
                }
            };
        }
        return jsonResponse(true, {state: "RUNNING", stage: "处理中", message: "accepted", percent: 10});
    });

    await harness.elements.taskForm.listeners.get("submit")({
        preventDefault() {
        }
    });

    assert.equal(calls.filter(call => call.url === "/api/jobs").length, 1);
    assert.equal(calls.filter(call => call.url.startsWith("/api/jobs/")).length, 1);
    assert.match(harness.storage.get("xjp.pending-job.v1"), /^[0-9a-f-]{36}$/i);
});

/** 保留未知协议状态的恢复能力，不把阶段文本误显示为失败。 */
test("unknown task state stays unknown and retains the ID for recovery", async function () {
    const calls = [];
    const harness = createHarness(async (url, options) => {
        calls.push({url, options});
        if (url === "/api/jobs") return jsonResponse(true, {id: JSON.parse(options.body).requestId}, 202);
        return jsonResponse(true, {state: "FUTURE_STATE", stage: "失败", message: "future protocol", percent: 20});
    });

    await harness.elements.taskForm.listeners.get("submit")({
        preventDefault() {
        }
    });

    assert.equal(harness.elements.jobStage.textContent, "状态暂不可确认");
    assert.match(harness.elements.jobMessage.textContent, /可能仍在运行/);
    assert.equal(harness.elements.retryButton.classList.contains("hidden"), false);
    assert.equal(harness.storage.size, 1);
    assert.equal(calls.filter(call => call.url === "/api/jobs").length, 1);
});

/** 保留明确的 HTTP 拒绝原因，且不查询服务端未受理的任务。 */
test("HTTP 429 rejection is shown as not accepted without status lookup", async function () {
    const calls = [];
    const harness = createHarness(async (url, options) => {
        calls.push({url, options});
        return jsonResponse(false, {error: "任务队列已满，请稍后重试。"}, 429);
    });

    await harness.elements.taskForm.listeners.get("submit")({
        preventDefault() {
        }
    });

    assert.equal(calls.length, 1);
    assert.equal(calls[0].url, "/api/jobs");
    assert.match(harness.elements.feedback.textContent, /任务队列已满/);
    assert.match(harness.elements.jobStage.textContent, /未受理/);
    assert.equal(harness.storage.size, 0);
    assert.equal(vm.runInContext("currentJob", harness.context), null);
});

/** 将任务记录缺失视为完成状态未知，并要求创建新任务前先检查输出。 */
test("status 404 after restart never reports failure or automatically resubmits", async function () {
    const calls = [];
    const harness = createHarness(async (url, options) => {
        calls.push({url, options});
        if (url === "/api/jobs") return jsonResponse(false, {error: "temporarily unavailable"}, 503);
        return jsonResponse(false, {error: "task record missing"}, 404);
    });

    await harness.elements.taskForm.listeners.get("submit")({
        preventDefault() {
        }
    });

    assert.equal(calls.filter(call => call.url === "/api/jobs").length, 1);
    assert.equal(calls.filter(call => call.url.startsWith("/api/jobs/")).length, 1);
    assert.match(harness.elements.jobStage.textContent, /任务记录不存在/);
    assert.match(harness.elements.jobMessage.textContent, /结果无法确认/);
    assert.match(harness.elements.feedback.textContent, /未自动重新提交/);
    assert.equal(harness.elements.retryButton.classList.contains("hidden"), false);
    assert.equal(harness.storage.size, 0);
});

/** 当前标签无法持久化不透明任务标识时，阻止任务提交。 */
test("unavailable session storage is explicit and prevents a non-recoverable submission", async function () {
    const calls = [];
    const harness = createHarness(async (url, options) => {
        calls.push({url, options});
        return jsonResponse(true, {});
    }, {sessionStorageUnavailable: true});

    await harness.elements.taskForm.listeners.get("submit")({
        preventDefault() {
        }
    });

    assert.equal(calls.length, 0);
    assert.match(harness.elements.feedback.textContent, /会话存储/);
    assert.match(harness.elements.feedback.textContent, /CLI/);
    assert.equal(harness.elements.password.value, "");
});

/** 在本地拒绝格式错误的持久化标识，避免路径或任意值进入状态 URL。 */
test("invalid stored task ID is removed without issuing an API request", async function () {
    const storage = new Map([["xjp.pending-job.v1", "C:\\private\\output.jar"]]);
    const calls = [];
    const harness = createHarness(async (url, options) => {
        calls.push({url, options});
        return jsonResponse(true, {});
    }, {storage});

    assert.equal(calls.length, 0);
    assert.equal(storage.size, 0);
    assert.match(harness.elements.feedback.textContent, /编号无效/);
});

/** 验证可复用配置不含合成秘密，而实际启动器请求仍保留其参数。 */
test("configuration export omits secret-bearing arguments but leaves task settings untouched", async function () {
    const calls = [];
    const marker = "SYNTHETIC_SECRET_MARKER_73";
    const harness = createHarness(async (url, options) => {
        calls.push({url, options});
        if (url === "/api/jobs") return jsonResponse(true, {id: JSON.parse(options.body).requestId}, 202);
        return jsonResponse(true, {state: "SUCCEEDED", stage: "SUCCEEDED", message: "done", percent: 100});
    });
    harness.elements.modeLauncher.listeners.get("click")();
    harness.elements.appId.value = "com.example.test";
    harness.elements.issuerFile.value = "C:\\private\\issuer.issuer";
    harness.elements.target.value = "windows-amd64";
    harness.elements.inputPath.value = "C:\\private\\app.jar";
    harness.elements.outputPath.value = "C:\\private\\dist";
    harness.elements.password.value = marker;
    harness.elements.jvmArgs.value = `-Dapi.token=${marker}`;
    harness.elements.appArgs.value = `--token=${marker}`;

    harness.elements.exportConfig.listeners.get("click")();
    const exportedText = await harness.downloadedBlob.text();
    const exported = JSON.parse(exportedText);
    assert.equal(exportedText.includes(marker), false);
    assert.equal(exported.password, undefined);
    assert.equal(exported.issuerFile, undefined);
    assert.equal(exported.jvmArgs, undefined);
    assert.equal(exported.appArgs, undefined);
    assert.equal(exported.input, "C:\\private\\app.jar");
    assert.equal(exported.output, "C:\\private\\dist");
    assert.match(uiSource, /会保留本机路径，请确认后再分享/);
    assert.match(harness.elements.feedback.textContent, /保留本机路径/);
    assert.equal(harness.elements.password.value, marker);
    assert.equal(harness.elements.jvmArgs.value, `-Dapi.token=${marker}`);
    assert.equal(harness.elements.appArgs.value, `--token=${marker}`);

    await harness.elements.taskForm.listeners.get("submit")({
        preventDefault() {
        }
    });
    const submitted = JSON.parse(calls.find(call => call.url === "/api/jobs").options.body);
    assert.deepEqual(submitted.jvmArgs, [`-Dapi.token=${marker}`]);
    assert.deepEqual(submitted.appArgs, [`--token=${marker}`]);
});

/** 导入的高级设置需要检查时展开对应分组，并在模式切换时保留设置。 */
test("imported advanced settings stay visible and survive mode switches", async function () {
    const harness = createHarness(async () => jsonResponse(true, {}));
    const config = {
        mode: "launcher", input: "imported.jar", output: "dist", appId: "com.example.imported",
        target: "linux-amd64", jdkZip: "jdk.zip", includes: ["com/example/**"],
        encryptDependencies: true, jvmArgs: ["-Xmx1g"], appArgs: ["--profile=test"],
        notBefore: "2026-10-23T00:00:00Z"
    };
    const configText = JSON.stringify(config);
    harness.elements.configFile.files = [{size: Buffer.byteLength(configText), text: async () => configText}];
    await dispatch(harness.elements.configFile, "change", {target: harness.elements.configFile});

    assert.equal(harness.elements.launcherAdvancedSettings.open, true, harness.elements.feedback.textContent);
    assert.equal(harness.elements.archiveAdvancedSettings.open, true);
    assert.match(uiSource, /<details\b(?=[^>]*\bid="launcherAdvancedSettings")[^>]*>/);
    assert.match(uiSource, /<details\b(?=[^>]*\bid="archiveAdvancedSettings")[^>]*>/);
    assert.equal(harness.elements.issuerFile.value, "");
    harness.elements.modeStandalone.listeners.get("click")();
    harness.elements.modeLauncher.listeners.get("click")();
    assert.equal(harness.elements.jdkZip.value, "jdk.zip");
    assert.equal(harness.elements.appArgs.value, "--profile=test");
    assert.equal(harness.elements.launcherAdvancedSettings.open, true);
    assert.equal(harness.elements.includes.value, "com/example/**");
});

/** 根据文件元数据在读取或解析前拒绝超大配置文件。 */
test("configuration import enforces the 256 KiB input bound before reading", async function () {
    let fileRead = false;
    const harness = createHarness(async () => jsonResponse(true, {}));
    harness.elements.configFile.files = [{
        size: 256 * 1024 + 1,
        text: async () => {
            fileRead = true;
            return "{}";
        }
    }];

    await dispatch(harness.elements.configFile, "change", {target: harness.elements.configFile});

    assert.equal(fileRead, false);
    assert.match(harness.elements.feedback.textContent, /超过 256 KiB/, harness.elements.feedback.textContent);
    assert.equal(harness.elements.configFile.value, "");
});

/** 验证取消文件选择后键盘焦点返回触发按钮，且已选路径不变。 */
test("picker cancellation restores trigger focus and preserves the original path", async function () {
    const harness = createHarness(async url => {
        if (url === "/api/picker") return jsonResponse(true, {cancelled: true});
        return jsonResponse(true, {accepted: true}, 202);
    });
    harness.elements.outputPath.value = "C:\\private\\original.jar";

    await harness.elements.outputPicker.listeners.get("click")();

    assert.equal(harness.elements.outputPath.value, "C:\\private\\original.jar");
    assert.equal(harness.elements.outputPicker.focused, true);
    assert.equal(harness.elements.modeLauncher.disabled, false);
    assert.equal(harness.elements.feedback.textContent, "已取消选择，原路径保持不变。");
});

/** 串行处理快速连续的选择器取消点击，确保活动原生对话框最多收到一次取消请求。 */
test("rapid picker clicks cancel the active dialog once and restore focus", async function () {
    let resolveDialog;
    let markDialogStarted;
    let cancelCount = 0;
    const dialogStarted = new Promise(resolve => {
        markDialogStarted = resolve;
    });
    const dialogResult = new Promise(resolve => {
        resolveDialog = resolve;
    });
    const harness = createHarness(async url => {
        if (url === "/api/picker") {
            markDialogStarted();
            return dialogResult;
        }
        if (url === "/api/picker/cancel") {
            cancelCount += 1;
            return jsonResponse(true, {accepted: true}, 202);
        }
        throw new Error(`unexpected request: ${url}`);
    });

    const pendingDialog = harness.elements.outputPicker.listeners.get("click")();
    await dialogStarted;
    await harness.elements.outputPicker.listeners.get("click")();
    await harness.elements.outputPicker.listeners.get("click")();
    assert.equal(cancelCount, 1);
    resolveDialog(jsonResponse(true, {cancelled: true}));
    await pendingDialog;

    assert.equal(harness.elements.outputPicker.focused, true);
    assert.equal(harness.elements.outputPath.value, "first-output.jar");
    assert.equal(harness.elements.modeLauncher.disabled, false);
});
