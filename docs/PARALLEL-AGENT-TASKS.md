# Parallel Agent Workstreams

> Purpose: split the 1.4.0 Admin UI + CLI completion work into independent agent-sized tasks. Each agent should stay inside its write scope unless the main agent approves a handoff.

## Agent A: Admin UI Runtime Polish

Write scope: `src/main/resources/admin-ui/**`, `src/test/java/com/sslproxy/management/handler/AdminUiHandlerTest.java`.

Tasks:
- Fix token error handling so 401/403 pauses or reduces automatic protected API refreshes.
- Add panel-level failure states for admin info, stats, certs, rejected certs, metrics, and lists.
- Add loading/disabled states for list writes, reload, drain, and connection termination.
- Keep the UI static: no Node, React, Vue, CDN, external fonts, or external images.

Exit evidence: focused UI handler tests, static search for no external assets, and browser or screenshot verification at desktop and narrow mobile widths.

## Agent B: CLI Completeness And Tests

Write scope: `src/main/java/com/sslproxy/cli/**`, `src/test/java/com/sslproxy/cli/**`, `scripts/jsslproxyctl.*`.

Tasks:
- Add missing mock-server tests for `certs`, `rejected-certs`, `metrics`, `whitelist`, `reload`, `drain`, and `connections`.
- Verify method, path, query string, request body, Authorization header, exit codes, and token redaction.
- Ensure write commands print backend results plus an audit reminder.
- Preserve the actual JAR path: `build/libs/JSSLProxy-1.0-SNAPSHOT.jar`.

Exit evidence: `gradle --no-daemon test --tests "*Cli*"` on JDK 17 and one wrapper help command on Windows PowerShell.

## Agent C: Management API Contract And Security

Write scope: `src/main/java/com/sslproxy/management/**`, `src/test/java/com/sslproxy/management/**`.

Tasks:
- Confirm `/admin/ui` is the only unauthenticated `/admin/*` path.
- Confirm whitelist/blacklist writes use request bodies, while connection termination keeps the existing query fingerprint contract.
- Add or update security tests for CSP headers, protected JSON APIs, unauthorized audit events, and sensitive error redaction.
- Avoid adding RBAC, sessions, cookies, user management, or new proxy features.

Exit evidence: focused management tests on JDK 17 and a short endpoint-contract checklist.

## Agent D: Docker Smoke And Release Gates

Write scope: `scripts/smoke-test.*`, `docker-compose.test.yml`, `docs/RELEASE-CHECKLIST.md`, `docs/TEST-*.md`.

Tasks:
- Extend Docker smoke coverage for `/admin/ui`, CLI `status`, `certs`, `rejected-certs`, blacklist add/remove, reload certs, and drain start/cancel.
- Keep smoke tests deterministic and self-contained with temporary certs.
- Record environment requirements for Docker, JDK 17, openssl, keytool, curl, and Gradle dependency cache/network.
- Do not mark release gates passed unless command output proves it.

Exit evidence: Docker smoke command output and release checklist updates.

## Agent E: Documentation And TODO Closure

Write scope: `docs/**`, `README.md`, `README_EN.md`, `PRODUCT.md`, `.dev/TODO.md`, `AGENTS.md` only if needed.

Tasks:
- Explain when to use UI, CLI, or curl.
- Explain safe management-port exposure: localhost, SSH tunnel, controlled operations network, or port-forward.
- State that tokens must not enter URLs, logs, persistent browser storage, committed scripts, or process supervisor files.
- Update T-31 through T-42 with implementation records, checked acceptance items only where implementation evidence exists, and checked test items only where command evidence exists.

Exit evidence: documentation diff, `git diff --check`, and a list of items still awaiting main-agent verification.
