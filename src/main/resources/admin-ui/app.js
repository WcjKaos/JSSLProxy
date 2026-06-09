(() => {
  const LANGUAGE_KEY = "jsslproxy.adminLanguage";
  const DEFAULT_LANGUAGE = "en";
  const messages = {
    en: {
      "app.title": "JSSLProxy Admin",
      "aria.refreshControls": "Refresh controls",
      "aria.runtimeSummary": "Runtime summary",
      "label.language": "Language",
      "label.adminUrl": "Admin URL",
      "label.adminToken": "Admin token",
      "label.health": "Health",
      "label.ready": "Ready",
      "label.activeConnections": "Active connections",
      "label.configHash": "Config hash",
      "label.limit": "Limit",
      "label.reloadTarget": "Reload target",
      "label.drainTimeout": "Drain timeout",
      "label.terminateByFingerprint": "Terminate by fingerprint",
      "placeholder.sameOrigin": "same origin",
      "placeholder.fingerprint": "SHA-256 fingerprint",
      "placeholder.seconds": "seconds",
      "section.connection": "Connection",
      "section.runtime": "Runtime",
      "section.certificates": "Certificates",
      "section.certificate": "Certificate",
      "section.metrics": "Metrics",
      "section.rejectedCertificates": "Rejected certificates",
      "section.rejectedCertificate": "Rejected certificate",
      "section.blacklist": "Blacklist",
      "section.whitelist": "Whitelist",
      "section.controlledOperations": "Controlled operations",
      "help.tokenSession": "Token is kept in this browser session only.",
      "help.auditedOperations": "These operations are audited by the management API.",
      "helpPanel.title": "How to use this page",
      "helpPanel.adminUrl": "Admin URL is usually left as same origin. Only change it for local debugging against another localhost management endpoint.",
      "helpPanel.adminToken": "Admin token is the value configured for the management API, normally MANAGEMENT_TOKEN or management.authToken.",
      "helpPanel.useToken": "Enter the token and click Use token. The page stores it only in this browser session and then refreshes protected panels.",
      "helpPanel.clearToken": "Click Clear token before sharing the machine or handing the browser to another operator.",
      "action.refresh": "Refresh",
      "action.pause": "Pause",
      "action.resume": "Resume",
      "action.useToken": "Use token",
      "action.clearToken": "Clear token",
      "action.reloadCerts": "Reload certs",
      "action.openRaw": "Open raw",
      "action.add": "Add",
      "action.copy": "Copy",
      "action.copyFingerprint": "Copy fingerprint",
      "action.remove": "Remove",
      "action.addBlacklist": "Add blacklist",
      "action.addWhitelist": "Add whitelist",
      "action.reloadTarget": "Reload target",
      "action.startDrain": "Start drain",
      "action.cancelDrain": "Cancel drain",
      "action.terminateConnections": "Terminate connections",
      "busy.reloading": "Reloading",
      "busy.starting": "Starting",
      "busy.cancelling": "Cancelling",
      "busy.terminating": "Terminating",
      "busy.adding": "Adding",
      "target.config": "config",
      "target.certs": "certs",
      "target.all": "all",
      "entity.blacklist": "blacklist",
      "entity.whitelist": "whitelist",
      "state.unknown": "Unknown",
      "state.waiting": "Waiting",
      "state.noData": "No data",
      "state.versionUnknown": "Version unknown",
      "state.version": "Version {version}",
      "state.neverUpdated": "Never updated",
      "state.updated": "Updated {time}",
      "state.tokenRequired": "Token required",
      "state.tokenLoaded": "Token loaded for this session",
      "state.refreshPaused": "Refresh paused",
      "state.unavailable": "Unavailable",
      "state.noChecks": "No checks",
      "state.noCertificates": "No certificates",
      "state.noRejectedCertificates": "No rejected certificates",
      "state.noEntries": "No entries",
      "state.loaded": "loaded",
      "state.rejected": "rejected",
      "field.state": "state",
      "field.server": "server",
      "field.target": "target",
      "field.protocol": "protocol",
      "field.java": "java",
      "field.processors": "processors",
      "field.maxMemoryMB": "maxMemoryMB",
      "field.uptimeSeconds": "uptimeSeconds",
      "field.bytesReceived": "bytesReceived",
      "field.bytesSent": "bytesSent",
      "field.issuer": "issuer",
      "field.serialNumber": "serialNumber",
      "field.notAfter": "notAfter",
      "field.reloadCount": "reloadCount",
      "field.fingerprint": "fingerprint",
      "field.time": "time",
      "field.clientIp": "clientIp",
      "field.requests": "requests",
      "field.requestErrors": "requestErrors",
      "field.activeConnections": "activeConnections",
      "field.connectionErrors": "connectionErrors",
      "field.sslFailures": "sslFailures",
      "field.durationCount": "durationCount",
      "field.metrics": "metrics",
      "error.adminUrlInvalid": "Admin URL is invalid",
      "error.adminUrlScheme": "Admin URL must use http or https",
      "error.adminUrlUnsafe": "Admin URL must not include credentials, query, or fragment",
      "error.adminUrlDebug": "Admin URL override is limited to local debug hosts",
      "error.adminUrlOriginOnly": "Admin URL override must be an origin only",
      "error.tokenRequired": "Token required",
      "error.invalidJson": "Invalid JSON from {path}",
      "error.requestTimeout": "Request timed out: {path}",
      "error.unauthorized": "Unauthorized: check admin token",
      "error.forbidden": "Forbidden: admin token missing or disabled",
      "error.cannotReach": "Cannot reach admin service",
      "error.requestFailed": "Request failed",
      "error.authRetry": "Unauthorized: re-enter admin token to retry",
      "error.fingerprintInvalid": "Fingerprint must be a SHA-256 hex value",
      "error.rejectedNoFingerprint": "Rejected record has no valid fingerprint",
      "error.nothingToCopy": "Nothing to copy",
      "error.copyFailed": "Copy failed",
      "toast.tokenCleared": "Token cleared",
      "toast.copied": "Copied",
      "toast.listUpdated": "{list} updated",
      "toast.reloadRequested": "Reload {target} requested",
      "toast.drainStarted": "Drain started",
      "toast.drainCancelled": "Drain cancelled",
      "toast.terminated": "Terminated {count} connection(s)",
      "confirm.removeFingerprint": "Remove this fingerprint from {list}?",
      "confirm.addFingerprint": "Add this fingerprint to {list}?",
      "confirm.reload": "Reload {target}?",
      "confirm.startDrain": "Start drain mode? New connections will be rejected.",
      "confirm.cancelDrain": "Cancel drain mode?",
      "confirm.terminateConnections": "Terminate active connections for this fingerprint?"
    },
    "zh-CN": {
      "app.title": "JSSLProxy 管理",
      "aria.refreshControls": "刷新控制",
      "aria.runtimeSummary": "运行摘要",
      "label.language": "语言",
      "label.adminUrl": "管理地址",
      "label.adminToken": "管理令牌",
      "label.health": "存活状态",
      "label.ready": "就绪状态",
      "label.activeConnections": "活跃连接",
      "label.configHash": "配置哈希",
      "label.limit": "条数",
      "label.reloadTarget": "重载目标",
      "label.drainTimeout": "Drain 超时",
      "label.terminateByFingerprint": "按指纹终止",
      "placeholder.sameOrigin": "同源",
      "placeholder.fingerprint": "SHA-256 指纹",
      "placeholder.seconds": "秒",
      "section.connection": "连接",
      "section.runtime": "运行信息",
      "section.certificates": "证书",
      "section.certificate": "证书",
      "section.metrics": "指标",
      "section.rejectedCertificates": "拒绝证书",
      "section.rejectedCertificate": "拒绝证书",
      "section.blacklist": "黑名单",
      "section.whitelist": "白名单",
      "section.controlledOperations": "受控操作",
      "help.tokenSession": "令牌只保存在当前浏览器会话中。",
      "help.auditedOperations": "这些操作会由管理 API 记录审计日志。",
      "helpPanel.title": "页面使用说明",
      "helpPanel.adminUrl": "管理地址通常保持“同源”即可。只有本地调试另一个 localhost 管理端点时才需要修改。",
      "helpPanel.adminToken": "管理令牌就是管理 API 配置的 token，通常来自 MANAGEMENT_TOKEN 或 management.authToken。",
      "helpPanel.useToken": "输入令牌后点击“使用令牌”。页面只把它保存在当前浏览器会话中，然后刷新受保护面板。",
      "helpPanel.clearToken": "共享机器或把浏览器交给其他运维人员前，点击“清除令牌”。",
      "action.refresh": "刷新",
      "action.pause": "暂停",
      "action.resume": "继续",
      "action.useToken": "使用令牌",
      "action.clearToken": "清除令牌",
      "action.reloadCerts": "重载证书",
      "action.openRaw": "打开原始数据",
      "action.add": "添加",
      "action.copy": "复制",
      "action.copyFingerprint": "复制指纹",
      "action.remove": "移除",
      "action.addBlacklist": "加入黑名单",
      "action.addWhitelist": "加入白名单",
      "action.reloadTarget": "重载目标",
      "action.startDrain": "开始 drain",
      "action.cancelDrain": "取消 drain",
      "action.terminateConnections": "终止连接",
      "busy.reloading": "重载中",
      "busy.starting": "启动中",
      "busy.cancelling": "取消中",
      "busy.terminating": "终止中",
      "busy.adding": "添加中",
      "target.config": "配置",
      "target.certs": "证书",
      "target.all": "全部",
      "entity.blacklist": "黑名单",
      "entity.whitelist": "白名单",
      "state.unknown": "未知",
      "state.waiting": "等待中",
      "state.noData": "无数据",
      "state.versionUnknown": "版本未知",
      "state.version": "版本 {version}",
      "state.neverUpdated": "尚未更新",
      "state.updated": "已更新 {time}",
      "state.tokenRequired": "需要令牌",
      "state.tokenLoaded": "已加载本会话令牌",
      "state.refreshPaused": "刷新已暂停",
      "state.unavailable": "不可用",
      "state.noChecks": "无检查项",
      "state.noCertificates": "无证书",
      "state.noRejectedCertificates": "无拒绝证书",
      "state.noEntries": "无条目",
      "state.loaded": "已加载",
      "state.rejected": "已拒绝",
      "field.state": "状态",
      "field.server": "服务端",
      "field.target": "后端目标",
      "field.protocol": "协议",
      "field.java": "Java",
      "field.processors": "处理器",
      "field.maxMemoryMB": "最大内存 MB",
      "field.uptimeSeconds": "运行秒数",
      "field.bytesReceived": "接收字节",
      "field.bytesSent": "发送字节",
      "field.issuer": "签发者",
      "field.serialNumber": "序列号",
      "field.notAfter": "过期时间",
      "field.reloadCount": "重载次数",
      "field.fingerprint": "指纹",
      "field.time": "时间",
      "field.clientIp": "客户端 IP",
      "field.requests": "请求数",
      "field.requestErrors": "请求错误",
      "field.activeConnections": "活跃连接",
      "field.connectionErrors": "连接错误",
      "field.sslFailures": "TLS 失败",
      "field.durationCount": "耗时样本数",
      "field.metrics": "指标",
      "error.adminUrlInvalid": "管理地址无效",
      "error.adminUrlScheme": "管理地址必须使用 http 或 https",
      "error.adminUrlUnsafe": "管理地址不能包含凭据、查询参数或片段",
      "error.adminUrlDebug": "管理地址覆盖仅限本地调试主机",
      "error.adminUrlOriginOnly": "管理地址覆盖只能填写 origin",
      "error.tokenRequired": "需要令牌",
      "error.invalidJson": "{path} 返回的 JSON 无效",
      "error.requestTimeout": "请求超时：{path}",
      "error.unauthorized": "未授权：请检查管理令牌",
      "error.forbidden": "禁止访问：管理令牌缺失或管理 API 已禁用",
      "error.cannotReach": "无法连接管理服务",
      "error.requestFailed": "请求失败",
      "error.authRetry": "未授权：请重新输入管理令牌后重试",
      "error.fingerprintInvalid": "指纹必须是 SHA-256 十六进制值",
      "error.rejectedNoFingerprint": "拒绝记录中没有有效指纹",
      "error.nothingToCopy": "没有可复制内容",
      "error.copyFailed": "复制失败",
      "toast.tokenCleared": "令牌已清除",
      "toast.copied": "已复制",
      "toast.listUpdated": "{list}已更新",
      "toast.reloadRequested": "已请求重载{target}",
      "toast.drainStarted": "Drain 已开始",
      "toast.drainCancelled": "Drain 已取消",
      "toast.terminated": "已终止 {count} 个连接",
      "confirm.removeFingerprint": "从{list}移除此指纹？",
      "confirm.addFingerprint": "将此指纹加入{list}？",
      "confirm.reload": "重载{target}？",
      "confirm.startDrain": "开始 drain 模式？新连接将被拒绝。",
      "confirm.cancelDrain": "取消 drain 模式？",
      "confirm.terminateConnections": "终止此指纹对应的活跃连接？"
    }
  };

  const state = {
    token: sessionStorage.getItem("jsslproxy.adminToken") || "",
    baseUrl: sessionStorage.getItem("jsslproxy.adminUrl") || "",
    language: initialLanguage(),
    paused: false,
    authBlocked: false,
    refreshing: false,
    timer: null,
    toastTimer: null
  };

  const $ = (id) => document.getElementById(id);
  let pretty = createDateFormatter();

  function initialLanguage() {
    const stored = sessionStorage.getItem(LANGUAGE_KEY);
    if (messages[stored]) {
      return stored;
    }
    const preferred = Array.from(navigator.languages || [navigator.language || DEFAULT_LANGUAGE])
      .find((language) => String(language).toLowerCase().startsWith("zh"));
    return preferred ? "zh-CN" : DEFAULT_LANGUAGE;
  }

  function createDateFormatter() {
    return new Intl.DateTimeFormat(state?.language || DEFAULT_LANGUAGE, {
      hour: "2-digit",
      minute: "2-digit",
      second: "2-digit"
    });
  }

  function t(key, params = {}) {
    const template = messages[state.language]?.[key] || messages[DEFAULT_LANGUAGE][key] || key;
    return template.replace(/\{([a-zA-Z0-9_]+)}/g, (_, name) => String(params[name] ?? ""));
  }

  function targetLabel(target) {
    return t(`target.${target}`);
  }

  function listLabel(type) {
    return t(`entity.${type}`);
  }

  function applyTranslations() {
    document.documentElement.lang = state.language;
    document.title = t("app.title");
    els.languageSelect.value = state.language;

    document.querySelectorAll("[data-i18n]").forEach((node) => {
      node.textContent = t(node.dataset.i18n);
    });
    document.querySelectorAll("[data-i18n-placeholder]").forEach((node) => {
      node.setAttribute("placeholder", t(node.dataset.i18nPlaceholder));
    });
    document.querySelectorAll("[data-i18n-aria-label]").forEach((node) => {
      node.setAttribute("aria-label", t(node.dataset.i18nAriaLabel));
    });

    els.pauseBtn.textContent = state.paused ? t("action.resume") : t("action.pause");
    updateTokenStatus(state.paused ? t("state.refreshPaused") : undefined);
  }

  function changeLanguage() {
    state.language = els.languageSelect.value;
    sessionStorage.setItem(LANGUAGE_KEY, state.language);
    pretty = createDateFormatter();
    applyTranslations();
    refreshAll();
  }

  const els = {
    statusLine: $("statusLine"),
    refreshBtn: $("refreshBtn"),
    pauseBtn: $("pauseBtn"),
    languageSelect: $("languageSelect"),
    baseUrlInput: $("baseUrlInput"),
    tokenInput: $("tokenInput"),
    saveTokenBtn: $("saveTokenBtn"),
    clearTokenBtn: $("clearTokenBtn"),
    healthValue: $("healthValue"),
    healthDetail: $("healthDetail"),
    readyValue: $("readyValue"),
    readyDetail: $("readyDetail"),
    connectionsValue: $("connectionsValue"),
    connectionsDetail: $("connectionsDetail"),
    configHashValue: $("configHashValue"),
    versionValue: $("versionValue"),
    lastUpdated: $("lastUpdated"),
    runtimeList: $("runtimeList"),
    certList: $("certList"),
    metricsList: $("metricsList"),
    rejectedLimitInput: $("rejectedLimitInput"),
    rejectedList: $("rejectedList"),
    blacklistForm: $("blacklistForm"),
    blacklistInput: $("blacklistInput"),
    blacklistItems: $("blacklistItems"),
    whitelistForm: $("whitelistForm"),
    whitelistInput: $("whitelistInput"),
    whitelistItems: $("whitelistItems"),
    reloadCertsBtn: $("reloadCertsBtn"),
    reloadTargetSelect: $("reloadTargetSelect"),
    reloadBtn: $("reloadBtn"),
    drainTimeoutInput: $("drainTimeoutInput"),
    startDrainBtn: $("startDrainBtn"),
    cancelDrainBtn: $("cancelDrainBtn"),
    terminateInput: $("terminateInput"),
    terminateBtn: $("terminateBtn"),
    toast: $("toast")
  };

  function init() {
    const storedBaseUrl = normalizeBaseUrl(state.baseUrl);
    if (storedBaseUrl.error) {
      state.baseUrl = "";
      sessionStorage.removeItem("jsslproxy.adminUrl");
    } else {
      state.baseUrl = storedBaseUrl.value;
    }

    els.baseUrlInput.value = state.baseUrl;
    els.tokenInput.value = state.token;

    els.languageSelect.addEventListener("change", changeLanguage);
    els.refreshBtn.addEventListener("click", () => refreshAll());
    els.pauseBtn.addEventListener("click", togglePause);
    els.saveTokenBtn.addEventListener("click", saveConnection);
    els.clearTokenBtn.addEventListener("click", clearToken);
    els.reloadCertsBtn.addEventListener("click",
      () => withBusy(els.reloadCertsBtn, () => reloadTarget("certs"), t("busy.reloading")));
    els.reloadBtn.addEventListener("click",
      () => withBusy(els.reloadBtn, () => reloadTarget(els.reloadTargetSelect.value), t("busy.reloading")));
    els.startDrainBtn.addEventListener("click",
      () => withBusy(els.startDrainBtn, startDrain, t("busy.starting")));
    els.cancelDrainBtn.addEventListener("click",
      () => withBusy(els.cancelDrainBtn, cancelDrain, t("busy.cancelling")));
    els.terminateBtn.addEventListener("click",
      () => withBusy(els.terminateBtn, terminateConnections, t("busy.terminating")));
    els.blacklistForm.addEventListener("submit", (event) => submitList(event, "blacklist"));
    els.whitelistForm.addEventListener("submit", (event) => submitList(event, "whitelist"));
    els.rejectedLimitInput.addEventListener("change", () => refreshRejected());
    document.addEventListener("visibilitychange", handleVisibility);

    applyTranslations();
    refreshAll();
    scheduleRefresh();
  }

  function saveConnection() {
    const nextBaseUrl = normalizeBaseUrl(els.baseUrlInput.value.trim());
    if (nextBaseUrl.error) {
      showToast(nextBaseUrl.error);
      els.baseUrlInput.focus();
      return;
    }

    state.token = els.tokenInput.value.trim();
    state.baseUrl = nextBaseUrl.value;
    state.authBlocked = false;
    els.baseUrlInput.value = state.baseUrl;
    if (state.token) {
      sessionStorage.setItem("jsslproxy.adminToken", state.token);
    } else {
      sessionStorage.removeItem("jsslproxy.adminToken");
    }
    if (state.baseUrl) {
      sessionStorage.setItem("jsslproxy.adminUrl", state.baseUrl);
    } else {
      sessionStorage.removeItem("jsslproxy.adminUrl");
    }
    updateTokenStatus();
    refreshAll();
  }

  function clearToken() {
    state.token = "";
    state.authBlocked = false;
    els.tokenInput.value = "";
    sessionStorage.removeItem("jsslproxy.adminToken");
    updateTokenStatus();
    showToast(t("toast.tokenCleared"));
    clearAdminPanels(t("state.tokenRequired"));
  }

  function normalizeBaseUrl(value) {
    if (!value) {
      return { value: "" };
    }

    const hasScheme = /^[a-z][a-z0-9+.-]*:/i.test(value);
    const candidate = hasScheme || value.startsWith("/") ? value : `http://${value}`;
    let url;
    try {
      url = new URL(candidate, window.location.origin);
    } catch (error) {
      return { error: t("error.adminUrlInvalid") };
    }

    if (url.protocol !== "http:" && url.protocol !== "https:") {
      return { error: t("error.adminUrlScheme") };
    }
    if (url.username || url.password || url.search || url.hash) {
      return { error: t("error.adminUrlUnsafe") };
    }
    if (url.origin !== window.location.origin && !isLoopbackHost(url.hostname)) {
      return { error: t("error.adminUrlDebug") };
    }
    if (url.origin !== window.location.origin && url.pathname !== "/") {
      return { error: t("error.adminUrlOriginOnly") };
    }

    return { value: url.origin === window.location.origin ? "" : url.origin };
  }

  function isLoopbackHost(hostname) {
    const normalized = hostname.toLowerCase().replace(/^\[|\]$/g, "");
    return normalized === "localhost" || normalized === "127.0.0.1" || normalized === "::1";
  }

  function updateTokenStatus(message) {
    if (message) {
      els.statusLine.textContent = message;
      return;
    }
    els.statusLine.textContent = state.token ? t("state.tokenLoaded") : t("state.tokenRequired");
  }

  function scheduleRefresh() {
    clearInterval(state.timer);
    state.timer = setInterval(() => {
      if (!state.paused && !document.hidden) {
        refreshAll();
      }
    }, 5000);
  }

  function handleVisibility() {
    if (!document.hidden && !state.paused) {
      refreshAll();
    }
  }

  function togglePause() {
    state.paused = !state.paused;
    els.pauseBtn.textContent = state.paused ? t("action.resume") : t("action.pause");
    updateTokenStatus(state.paused ? t("state.refreshPaused") : undefined);
    if (!state.paused) {
      refreshAll();
    }
  }

  async function refreshAll() {
    if (state.refreshing) {
      return;
    }
    state.refreshing = true;
    els.refreshBtn.disabled = true;
    try {
      await Promise.allSettled([
        refreshPublicStatus(),
        refreshAdminStatus(),
        refreshMetrics(),
        refreshLists(),
        refreshRejected()
      ]);
      els.lastUpdated.textContent = t("state.updated", { time: pretty.format(new Date()) });
    } finally {
      state.refreshing = false;
      els.refreshBtn.disabled = false;
    }
  }

  async function refreshPublicStatus() {
    const [health, ready] = await Promise.allSettled([
      request("/health", { admin: false, allowStatus: true }),
      request("/ready", { admin: false, allowStatus: true })
    ]);
    if (health.status === "fulfilled") {
      renderStatus("health", health.value);
    } else {
      renderStatusError("health", health.reason);
    }
    if (ready.status === "fulfilled") {
      renderStatus("ready", ready.value);
    } else {
      renderStatusError("ready", ready.reason);
    }
  }

  async function refreshAdminStatus() {
    if (!state.token) {
      clearAdminPanels(t("state.tokenRequired"));
      return;
    }
    if (state.authBlocked) {
      clearAdminPanels(authBlockedMessage());
      updateTokenStatus(authBlockedMessage());
      return;
    }

    const results = await Promise.allSettled([
      request("/admin/info"),
      request("/admin/stats"),
      request("/admin/certs"),
      request("/admin/connections")
    ]);
    const [infoResult, statsResult, certsResult, connectionsResult] = results;
    const info = resultValue(infoResult);
    const stats = resultValue(statsResult);
    const certs = resultValue(certsResult);
    const connections = resultValue(connectionsResult);
    const error = firstRejected(results);

    els.configHashValue.textContent = info?.configHash || "-";
    els.versionValue.textContent = info
      ? t("state.version", { version: info.version || t("state.unknown").toLowerCase() })
      : friendlyError(infoResult.reason);
    els.connectionsValue.textContent = String(connections?.activeConnections ?? stats?.activeConnections ?? 0);
    els.connectionsDetail.textContent = connections || stats
      ? t("state.updated", { time: formatTimestamp(connections?.timestamp || stats?.timestamp) })
      : friendlyError(connectionsResult.reason || statsResult.reason);

    if (info || stats) {
      renderRuntime(info || {}, stats || {});
    } else {
      renderKv(els.runtimeList, { [t("field.state")]: friendlyError(infoResult.reason || statsResult.reason) });
    }
    if (certs) {
      renderCertificates(certs);
    } else {
      renderList(els.certList, [], friendlyError(certsResult.reason));
    }
    updateTokenStatus(error ? friendlyError(error) : undefined);
  }

  async function refreshMetrics() {
    try {
      const text = await requestText("/metrics", { admin: false });
      renderMetrics(parseMetrics(text));
    } catch (error) {
      renderKv(els.metricsList, { [t("field.metrics")]: error.message });
    }
  }

  async function refreshLists() {
    if (!state.token) {
      renderList(els.blacklistItems, [], t("state.tokenRequired"));
      renderList(els.whitelistItems, [], t("state.tokenRequired"));
      return;
    }
    if (state.authBlocked) {
      renderList(els.blacklistItems, [], authBlockedMessage());
      renderList(els.whitelistItems, [], authBlockedMessage());
      return;
    }
    const [blacklistResult, whitelistResult] = await Promise.allSettled([
      request("/admin/blacklist"),
      request("/admin/whitelist")
    ]);
    const blacklist = resultValue(blacklistResult);
    const whitelist = resultValue(whitelistResult);
    if (blacklist) {
      renderFingerprintList(els.blacklistItems, blacklist.blacklist || [], "blacklist");
    } else {
      renderList(els.blacklistItems, [], friendlyError(blacklistResult.reason));
    }
    if (whitelist) {
      renderFingerprintList(els.whitelistItems, whitelist.whitelist || [], "whitelist");
    } else {
      renderList(els.whitelistItems, [], friendlyError(whitelistResult.reason));
    }
  }

  async function refreshRejected() {
    if (!state.token) {
      renderList(els.rejectedList, [], t("state.tokenRequired"));
      return;
    }
    if (state.authBlocked) {
      renderList(els.rejectedList, [], authBlockedMessage());
      return;
    }
    try {
      const limit = Math.max(1, Math.min(500, Number(els.rejectedLimitInput.value) || 50));
      els.rejectedLimitInput.value = String(limit);
      const data = await request(`/admin/rejected-certs?limit=${limit}`);
      renderRejected(data.rejectedCerts || []);
    } catch (error) {
      renderList(els.rejectedList, [], friendlyError(error));
    }
  }

  function resultValue(result) {
    return result.status === "fulfilled" ? result.value : null;
  }

  function firstRejected(results) {
    const rejected = results.find((result) => result.status === "rejected");
    return rejected ? rejected.reason : null;
  }

  function clearAdminPanels(message) {
    els.configHashValue.textContent = "-";
    els.versionValue.textContent = message;
    els.connectionsValue.textContent = "0";
    els.connectionsDetail.textContent = message;
    renderKv(els.runtimeList, { [t("field.state")]: message });
    renderList(els.certList, [], message);
    renderList(els.rejectedList, [], message);
    renderList(els.blacklistItems, [], message);
    renderList(els.whitelistItems, [], message);
  }

  async function request(path, options = {}) {
    const text = await requestText(path, options);
    if (!text) {
      return {};
    }
    try {
      return JSON.parse(text);
    } catch (error) {
      throw new Error(t("error.invalidJson", { path }));
    }
  }

  async function requestText(path, options = {}) {
    const admin = options.admin !== false;
    if (admin && !state.token) {
      throw new Error(t("error.tokenRequired"));
    }

    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), 8000);
    const headers = {};
    if (admin) {
      headers.Authorization = `Bearer ${state.token}`;
    }
    if (options.body !== undefined) {
      headers["Content-Type"] = "text/plain; charset=UTF-8";
    }

    try {
      const response = await fetch(`${state.baseUrl}${path}`, {
        method: options.method || "GET",
        headers,
        body: options.body,
        signal: controller.signal
      });
      const body = await response.text();
      if (!response.ok && !options.allowStatus) {
        if (admin && (response.status === 401 || response.status === 403)) {
          state.authBlocked = true;
        }
        const detail = body ? `: ${body.slice(0, 160)}` : "";
        throw new Error(`${response.status} ${response.statusText}${detail}`);
      }
      return body;
    } catch (error) {
      if (error.name === "AbortError") {
        throw new Error(t("error.requestTimeout", { path }));
      }
      throw error;
    } finally {
      clearTimeout(timeout);
    }
  }

  function renderStatus(type, data) {
    const target = type === "health" ? els.healthValue : els.readyValue;
    const detail = type === "health" ? els.healthDetail : els.readyDetail;
    const status = data.status || "UNKNOWN";
    target.textContent = status;
    target.className = status === "UP" ? "ok" : status === "DRAINING" ? "warn" : "bad";
    detail.textContent = data.checks ? summarizeChecks(data.checks) : formatTimestamp(data.timestamp);
  }

  function renderStatusError(type, error) {
    const target = type === "health" ? els.healthValue : els.readyValue;
    const detail = type === "health" ? els.healthDetail : els.readyDetail;
    target.textContent = t("state.unavailable");
    target.className = "bad";
    detail.textContent = friendlyError(error);
  }

  function friendlyError(error) {
    const message = error?.message || String(error || t("error.requestFailed"));
    if (message.startsWith("401 ")) {
      return t("error.unauthorized");
    }
    if (message.startsWith("403 ")) {
      return t("error.forbidden");
    }
    if (message.includes("Failed to fetch") || message.includes("NetworkError")) {
      return t("error.cannotReach");
    }
    return message.length > 180 ? `${message.slice(0, 177)}...` : message;
  }

  function authBlockedMessage() {
    return t("error.authRetry");
  }

  function summarizeChecks(checks) {
    if (!checks || typeof checks !== "object") {
      return t("state.noChecks");
    }
    return Object.keys(checks).slice(0, 3).join(", ") || t("state.noChecks");
  }

  function renderRuntime(info, stats) {
    const runtime = info.runtime || {};
    renderKv(els.runtimeList, {
      [t("field.server")]: formatEndpoint(info.server),
      [t("field.target")]: formatEndpoint(info.target),
      [t("field.protocol")]: info.target?.protocol || "-",
      [t("field.java")]: runtime.javaVersion || "-",
      [t("field.processors")]: runtime.availableProcessors ?? "-",
      [t("field.maxMemoryMB")]: runtime.maxMemoryMB ?? "-",
      [t("field.uptimeSeconds")]: stats.uptimeSeconds ?? runtime.uptimeSeconds ?? "-",
      [t("field.bytesReceived")]: stats.bytesReceived ?? "-",
      [t("field.bytesSent")]: stats.bytesSent ?? "-"
    });
  }

  function renderCertificates(data) {
    const certs = data.certificates || [];
    if (!certs.length) {
      renderList(els.certList, [], data.status || t("state.noCertificates"));
      return;
    }
    els.certList.replaceChildren(...certs.map((cert) => {
      const item = div("item");
      const notAfter = cert.notAfter || cert.validTo || "-";
      const expiresClass = isExpiringSoon(notAfter) ? "warn" : "ok";
      item.append(
        row(text("strong", cert.subject || t("section.certificate")), pill(data.status || t("state.loaded"))),
        kvBlock({
          [t("field.issuer")]: cert.issuer || "-",
          [t("field.serialNumber")]: cert.serialNumber || "-",
          [t("field.notAfter")]: notAfter,
          [t("field.reloadCount")]: data.reloadCount ?? cert.reloadCount ?? "-",
          [t("field.fingerprint")]: cert.fingerprint || "-"
        }),
        actions([
          [t("action.copyFingerprint"), () => copyText(cert.fingerprint || "")],
          [t("action.terminateConnections"), () => setTerminate(cert.fingerprint || "")]
        ])
      );
      item.querySelector("dd:nth-of-type(3)")?.classList.add(expiresClass);
      return item;
    }));
  }

  function renderMetrics(metrics) {
    renderKv(els.metricsList, {
      [t("field.requests")]: metrics.proxy_requests_total ?? "-",
      [t("field.requestErrors")]: metrics.proxy_requests_errors_total ?? "-",
      [t("field.activeConnections")]: metrics.proxy_connections_active ?? "-",
      [t("field.connectionErrors")]: metrics.proxy_connections_errors_total ?? "-",
      [t("field.sslFailures")]: metrics.proxy_ssl_handshakes_failures_total ?? "-",
      [t("field.durationCount")]: metrics.proxy_request_duration_seconds_count ?? "-"
    });
  }

  function parseMetrics(text) {
    const values = {};
    for (const line of text.split("\n")) {
      if (!line || line.startsWith("#")) {
        continue;
      }
      const match = line.match(/^([a-zA-Z_:][a-zA-Z0-9_:]*)(?:\{[^}]*\})?\s+([-+0-9.eE]+)$/);
      if (!match) {
        continue;
      }
      values[match[1]] = String(Number(values[match[1]] || 0) + Number(match[2]));
    }
    return values;
  }

  function renderRejected(records) {
    if (!records.length) {
      renderList(els.rejectedList, [], t("state.noRejectedCertificates"));
      return;
    }
    els.rejectedList.replaceChildren(...records.map((record) => {
      const fingerprint = record.fingerprint || "";
      const item = div("item");
      item.append(
        row(text("strong", record.subject || t("section.rejectedCertificate")), pill(record.reason || t("state.rejected"))),
        kvBlock({
          [t("field.time")]: formatTimestamp(record.timestamp),
          [t("field.clientIp")]: record.clientIp || "-",
          [t("field.fingerprint")]: fingerprint
        }),
        actions([
          [t("action.copyFingerprint"), () => copyText(fingerprint)],
          [t("action.addBlacklist"), () => addFromRejected("blacklist", fingerprint)],
          [t("action.addWhitelist"), () => addFromRejected("whitelist", fingerprint)]
        ])
      );
      return item;
    }));
  }

  function renderFingerprintList(container, entries, type) {
    if (!entries.length) {
      renderList(container, [], t("state.noEntries"));
      return;
    }
    container.replaceChildren(...entries.map((fingerprint) => {
      const item = div("item");
      const fp = text("span", fingerprint, "fingerprint");
      item.append(
        fp,
        actions([
          [t("action.copy"), () => copyText(fingerprint)],
          [t("action.remove"), () => removeFingerprint(type, fingerprint)]
        ])
      );
      return item;
    }));
  }

  function renderList(container, items, emptyText) {
    if (items.length) {
      container.replaceChildren(...items);
      return;
    }
    const empty = div("item");
    empty.append(text("span", emptyText, "muted"));
    container.replaceChildren(empty);
  }

  function renderKv(container, values) {
    container.replaceChildren();
    for (const [key, value] of Object.entries(values)) {
      const dt = document.createElement("dt");
      dt.textContent = key;
      const dd = document.createElement("dd");
      dd.textContent = String(value ?? "-");
      if (key.toLowerCase().includes("fingerprint") || key === t("field.fingerprint")) {
        dd.classList.add("fingerprint");
      }
      container.append(dt, dd);
    }
  }

  function kvBlock(values) {
    const dl = document.createElement("dl");
    dl.className = "kv-list";
    renderKv(dl, values);
    return dl;
  }

  function row(left, right) {
    const node = div("item-row");
    node.append(left, right);
    return node;
  }

  function actions(items) {
    const node = div("item-actions");
    for (const [label, handler] of items) {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "secondary";
      button.textContent = label;
      button.addEventListener("click", () => withBusy(button, () => handler(button)));
      node.append(button);
    }
    return node;
  }

  async function withBusy(button, task, busyLabel) {
    if (!button || button.disabled) {
      return task();
    }
    const original = button.textContent;
    button.disabled = true;
    button.setAttribute("aria-busy", "true");
    if (busyLabel) {
      button.textContent = busyLabel;
    }
    try {
      return await task();
    } finally {
      button.disabled = false;
      button.removeAttribute("aria-busy");
      button.textContent = original;
    }
  }

  function div(className) {
    const node = document.createElement("div");
    node.className = className;
    return node;
  }

  function text(tag, value, className) {
    const node = document.createElement(tag);
    node.textContent = String(value ?? "-");
    if (className) {
      node.className = className;
    }
    return node;
  }

  function pill(value) {
    return text("span", value, "pill");
  }

  async function submitList(event, type) {
    event.preventDefault();
    const submitButton = event.submitter || event.currentTarget.querySelector("button[type='submit']");
    await withBusy(submitButton, async () => {
      const input = type === "blacklist" ? els.blacklistInput : els.whitelistInput;
      const fingerprint = normalizeFingerprint(input.value);
      if (!fingerprint) {
        showToast(t("error.fingerprintInvalid"));
        return;
      }
      await writeList(type, "POST", fingerprint);
      input.value = "";
      await refreshLists();
    }, t("busy.adding"));
  }

  async function removeFingerprint(type, fingerprint) {
    if (!window.confirm(t("confirm.removeFingerprint", { list: listLabel(type) }))) {
      return;
    }
    await writeList(type, "DELETE", normalizeFingerprint(fingerprint) || String(fingerprint || "").trim());
    await refreshLists();
  }

  async function addFromRejected(type, fingerprint) {
    const normalized = normalizeFingerprint(fingerprint);
    if (!normalized) {
      showToast(t("error.rejectedNoFingerprint"));
      return;
    }
    if (!window.confirm(t("confirm.addFingerprint", { list: listLabel(type) }))) {
      return;
    }
    await writeList(type, "POST", normalized);
    await refreshLists();
  }

  async function writeList(type, method, fingerprint) {
    await request(`/admin/${type}`, { method, body: fingerprint });
    showToast(t("toast.listUpdated", { list: listLabel(type) }));
  }

  async function reloadTarget(target) {
    if (!window.confirm(t("confirm.reload", { target: targetLabel(target) }))) {
      return;
    }
    await request(`/admin/reload?target=${encodeURIComponent(target)}`, { method: "POST" });
    showToast(t("toast.reloadRequested", { target: targetLabel(target) }));
    await refreshAll();
  }

  async function startDrain() {
    const timeout = els.drainTimeoutInput.value.trim();
    const suffix = timeout ? `?timeout=${encodeURIComponent(timeout)}` : "";
    if (!window.confirm(t("confirm.startDrain"))) {
      return;
    }
    await request(`/admin/drain${suffix}`, { method: "POST" });
    showToast(t("toast.drainStarted"));
    await refreshAll();
  }

  async function cancelDrain() {
    if (!window.confirm(t("confirm.cancelDrain"))) {
      return;
    }
    await request("/admin/drain", { method: "DELETE" });
    showToast(t("toast.drainCancelled"));
    await refreshAll();
  }

  async function terminateConnections() {
    const fingerprint = normalizeFingerprint(els.terminateInput.value);
    if (!fingerprint) {
      showToast(t("error.fingerprintInvalid"));
      return;
    }
    if (!window.confirm(t("confirm.terminateConnections"))) {
      return;
    }
    const data = await request(`/admin/connections?fingerprint=${encodeURIComponent(fingerprint)}`, {
      method: "DELETE"
    });
    showToast(t("toast.terminated", { count: data.terminated ?? 0 }));
    await refreshAll();
  }

  function setTerminate(fingerprint) {
    els.terminateInput.value = fingerprint;
    els.terminateInput.focus();
  }

  function isFingerprint(value) {
    return normalizeFingerprint(value) !== null;
  }

  function normalizeFingerprint(value) {
    const stripped = String(value || "").trim().replace(/[:-]/g, "");
    if (!/^[a-fA-F0-9]{64}$/.test(stripped)) {
      return null;
    }
    return stripped.toUpperCase().match(/.{2}/g).join(":");
  }

  async function copyText(value) {
    if (!value) {
      showToast(t("error.nothingToCopy"));
      return;
    }
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(value);
      } else {
        fallbackCopy(value);
      }
      showToast(t("toast.copied"));
    } catch (error) {
      showToast(t("error.copyFailed"));
    }
  }

  function fallbackCopy(value) {
    const input = document.createElement("textarea");
    input.value = value;
    input.setAttribute("readonly", "true");
    input.style.position = "fixed";
    input.style.left = "-9999px";
    document.body.append(input);
    input.select();
    document.execCommand("copy");
    input.remove();
  }

  function formatEndpoint(value) {
    if (!value) {
      return "-";
    }
    return `${value.host || "-"}:${value.port || "-"}${value.protocol ? ` (${value.protocol})` : ""}`;
  }

  function formatTimestamp(value) {
    if (!value) {
      return "-";
    }
    const date = typeof value === "number" ? new Date(value) : new Date(String(value));
    if (Number.isNaN(date.getTime())) {
      return String(value);
    }
    return pretty.format(date);
  }

  function isExpiringSoon(value) {
    const date = new Date(String(value));
    if (Number.isNaN(date.getTime())) {
      return false;
    }
    return date.getTime() - Date.now() < 30 * 24 * 60 * 60 * 1000;
  }

  function showToast(message) {
    els.toast.textContent = message;
    els.toast.classList.add("show");
    clearTimeout(state.toastTimer);
    state.toastTimer = setTimeout(() => els.toast.classList.remove("show"), 2800);
  }

  window.addEventListener("unhandledrejection", (event) => {
    showToast(friendlyError(event.reason));
  });

  init();
})();
