(() => {
  const state = {
    token: sessionStorage.getItem("jsslproxy.adminToken") || "",
    baseUrl: sessionStorage.getItem("jsslproxy.adminUrl") || "",
    paused: false,
    authBlocked: false,
    refreshing: false,
    timer: null,
    toastTimer: null
  };

  const $ = (id) => document.getElementById(id);
  const pretty = new Intl.DateTimeFormat(undefined, {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit"
  });

  const els = {
    statusLine: $("statusLine"),
    refreshBtn: $("refreshBtn"),
    pauseBtn: $("pauseBtn"),
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

    els.refreshBtn.addEventListener("click", () => refreshAll());
    els.pauseBtn.addEventListener("click", togglePause);
    els.saveTokenBtn.addEventListener("click", saveConnection);
    els.clearTokenBtn.addEventListener("click", clearToken);
    els.reloadCertsBtn.addEventListener("click",
      () => withBusy(els.reloadCertsBtn, () => reloadTarget("certs"), "Reloading"));
    els.reloadBtn.addEventListener("click",
      () => withBusy(els.reloadBtn, () => reloadTarget(els.reloadTargetSelect.value), "Reloading"));
    els.startDrainBtn.addEventListener("click",
      () => withBusy(els.startDrainBtn, startDrain, "Starting"));
    els.cancelDrainBtn.addEventListener("click",
      () => withBusy(els.cancelDrainBtn, cancelDrain, "Cancelling"));
    els.terminateBtn.addEventListener("click",
      () => withBusy(els.terminateBtn, terminateConnections, "Terminating"));
    els.blacklistForm.addEventListener("submit", (event) => submitList(event, "blacklist"));
    els.whitelistForm.addEventListener("submit", (event) => submitList(event, "whitelist"));
    els.rejectedLimitInput.addEventListener("change", () => refreshRejected());
    document.addEventListener("visibilitychange", handleVisibility);

    updateTokenStatus();
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
    showToast("Token cleared");
    clearAdminPanels("Token required");
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
      return { error: "Admin URL is invalid" };
    }

    if (url.protocol !== "http:" && url.protocol !== "https:") {
      return { error: "Admin URL must use http or https" };
    }
    if (url.username || url.password || url.search || url.hash) {
      return { error: "Admin URL must not include credentials, query, or fragment" };
    }
    if (url.origin !== window.location.origin && !isLoopbackHost(url.hostname)) {
      return { error: "Admin URL override is limited to local debug hosts" };
    }
    if (url.origin !== window.location.origin && url.pathname !== "/") {
      return { error: "Admin URL override must be an origin only" };
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
    els.statusLine.textContent = state.token ? "Token loaded for this session" : "Token required";
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
    els.pauseBtn.textContent = state.paused ? "Resume" : "Pause";
    updateTokenStatus(state.paused ? "Refresh paused" : undefined);
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
      els.lastUpdated.textContent = `Updated ${pretty.format(new Date())}`;
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
      clearAdminPanels("Token required");
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
    els.versionValue.textContent = info ? `Version ${info.version || "unknown"}` : friendlyError(infoResult.reason);
    els.connectionsValue.textContent = String(connections?.activeConnections ?? stats?.activeConnections ?? 0);
    els.connectionsDetail.textContent = connections || stats
      ? `Updated ${formatTimestamp(connections?.timestamp || stats?.timestamp)}`
      : friendlyError(connectionsResult.reason || statsResult.reason);

    if (info || stats) {
      renderRuntime(info || {}, stats || {});
    } else {
      renderKv(els.runtimeList, { state: friendlyError(infoResult.reason || statsResult.reason) });
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
      renderKv(els.metricsList, { metrics: error.message });
    }
  }

  async function refreshLists() {
    if (!state.token) {
      renderList(els.blacklistItems, [], "Token required");
      renderList(els.whitelistItems, [], "Token required");
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
      renderList(els.rejectedList, [], "Token required");
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
    renderKv(els.runtimeList, { state: message });
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
      throw new Error(`Invalid JSON from ${path}`);
    }
  }

  async function requestText(path, options = {}) {
    const admin = options.admin !== false;
    if (admin && !state.token) {
      throw new Error("Token required");
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
        throw new Error(`Request timed out: ${path}`);
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
    target.textContent = "Unavailable";
    target.className = "bad";
    detail.textContent = friendlyError(error);
  }

  function friendlyError(error) {
    const message = error?.message || String(error || "Request failed");
    if (message.startsWith("401 ")) {
      return "Unauthorized: check admin token";
    }
    if (message.startsWith("403 ")) {
      return "Forbidden: admin token missing or disabled";
    }
    if (message.includes("Failed to fetch") || message.includes("NetworkError")) {
      return "Cannot reach admin service";
    }
    return message.length > 180 ? `${message.slice(0, 177)}...` : message;
  }

  function authBlockedMessage() {
    return "Unauthorized: re-enter admin token to retry";
  }

  function summarizeChecks(checks) {
    if (!checks || typeof checks !== "object") {
      return "No checks";
    }
    return Object.keys(checks).slice(0, 3).join(", ") || "No checks";
  }

  function renderRuntime(info, stats) {
    const runtime = info.runtime || {};
    renderKv(els.runtimeList, {
      server: formatEndpoint(info.server),
      target: formatEndpoint(info.target),
      protocol: info.target?.protocol || "-",
      java: runtime.javaVersion || "-",
      processors: runtime.availableProcessors ?? "-",
      maxMemoryMB: runtime.maxMemoryMB ?? "-",
      uptimeSeconds: stats.uptimeSeconds ?? runtime.uptimeSeconds ?? "-",
      bytesReceived: stats.bytesReceived ?? "-",
      bytesSent: stats.bytesSent ?? "-"
    });
  }

  function renderCertificates(data) {
    const certs = data.certificates || [];
    if (!certs.length) {
      renderList(els.certList, [], data.status || "No certificates");
      return;
    }
    els.certList.replaceChildren(...certs.map((cert) => {
      const item = div("item");
      const notAfter = cert.notAfter || cert.validTo || "-";
      const expiresClass = isExpiringSoon(notAfter) ? "warn" : "ok";
      item.append(
        row(text("strong", cert.subject || "Certificate"), pill(data.status || "loaded")),
        kvBlock({
          issuer: cert.issuer || "-",
          serialNumber: cert.serialNumber || "-",
          notAfter,
          reloadCount: data.reloadCount ?? cert.reloadCount ?? "-",
          fingerprint: cert.fingerprint || "-"
        }),
        actions([
          ["Copy fingerprint", () => copyText(cert.fingerprint || "")],
          ["Terminate connections", () => setTerminate(cert.fingerprint || "")]
        ])
      );
      item.querySelector("dd:nth-of-type(3)")?.classList.add(expiresClass);
      return item;
    }));
  }

  function renderMetrics(metrics) {
    renderKv(els.metricsList, {
      requests: metrics.proxy_requests_total ?? "-",
      requestErrors: metrics.proxy_requests_errors_total ?? "-",
      activeConnections: metrics.proxy_connections_active ?? "-",
      connectionErrors: metrics.proxy_connections_errors_total ?? "-",
      sslFailures: metrics.proxy_ssl_handshakes_failures_total ?? "-",
      durationCount: metrics.proxy_request_duration_seconds_count ?? "-"
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
      renderList(els.rejectedList, [], "No rejected certificates");
      return;
    }
    els.rejectedList.replaceChildren(...records.map((record) => {
      const fingerprint = record.fingerprint || "";
      const item = div("item");
      item.append(
        row(text("strong", record.subject || "Rejected certificate"), pill(record.reason || "rejected")),
        kvBlock({
          time: formatTimestamp(record.timestamp),
          clientIp: record.clientIp || "-",
          fingerprint
        }),
        actions([
          ["Copy fingerprint", () => copyText(fingerprint)],
          ["Add blacklist", () => addFromRejected("blacklist", fingerprint)],
          ["Add whitelist", () => addFromRejected("whitelist", fingerprint)]
        ])
      );
      return item;
    }));
  }

  function renderFingerprintList(container, entries, type) {
    if (!entries.length) {
      renderList(container, [], "No entries");
      return;
    }
    container.replaceChildren(...entries.map((fingerprint) => {
      const item = div("item");
      const fp = text("span", fingerprint, "fingerprint");
      item.append(
        fp,
        actions([
          ["Copy", () => copyText(fingerprint)],
          ["Remove", () => removeFingerprint(type, fingerprint)]
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
      if (key.toLowerCase().includes("fingerprint")) {
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
        showToast("Fingerprint must be a SHA-256 hex value");
        return;
      }
      await writeList(type, "POST", fingerprint);
      input.value = "";
      await refreshLists();
    }, "Adding");
  }

  async function removeFingerprint(type, fingerprint) {
    if (!window.confirm(`Remove this fingerprint from ${type}?`)) {
      return;
    }
    await writeList(type, "DELETE", normalizeFingerprint(fingerprint) || String(fingerprint || "").trim());
    await refreshLists();
  }

  async function addFromRejected(type, fingerprint) {
    const normalized = normalizeFingerprint(fingerprint);
    if (!normalized) {
      showToast("Rejected record has no valid fingerprint");
      return;
    }
    if (!window.confirm(`Add this fingerprint to ${type}?`)) {
      return;
    }
    await writeList(type, "POST", normalized);
    await refreshLists();
  }

  async function writeList(type, method, fingerprint) {
    await request(`/admin/${type}`, { method, body: fingerprint });
    showToast(`${type} updated`);
  }

  async function reloadTarget(target) {
    if (!window.confirm(`Reload ${target}?`)) {
      return;
    }
    await request(`/admin/reload?target=${encodeURIComponent(target)}`, { method: "POST" });
    showToast(`Reload ${target} requested`);
    await refreshAll();
  }

  async function startDrain() {
    const timeout = els.drainTimeoutInput.value.trim();
    const suffix = timeout ? `?timeout=${encodeURIComponent(timeout)}` : "";
    if (!window.confirm("Start drain mode? New connections will be rejected.")) {
      return;
    }
    await request(`/admin/drain${suffix}`, { method: "POST" });
    showToast("Drain started");
    await refreshAll();
  }

  async function cancelDrain() {
    if (!window.confirm("Cancel drain mode?")) {
      return;
    }
    await request("/admin/drain", { method: "DELETE" });
    showToast("Drain cancelled");
    await refreshAll();
  }

  async function terminateConnections() {
    const fingerprint = normalizeFingerprint(els.terminateInput.value);
    if (!fingerprint) {
      showToast("Fingerprint must be a SHA-256 hex value");
      return;
    }
    if (!window.confirm("Terminate active connections for this fingerprint?")) {
      return;
    }
    const data = await request(`/admin/connections?fingerprint=${encodeURIComponent(fingerprint)}`, {
      method: "DELETE"
    });
    showToast(`Terminated ${data.terminated ?? 0} connection(s)`);
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
      showToast("Nothing to copy");
      return;
    }
    try {
      if (navigator.clipboard?.writeText) {
        await navigator.clipboard.writeText(value);
      } else {
        fallbackCopy(value);
      }
      showToast("Copied");
    } catch (error) {
      showToast("Copy failed");
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
