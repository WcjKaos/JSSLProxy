# Admin CLI

> `jsslproxyctl` is a thin client for the existing management API. It does not run a daemon and does not duplicate server-side proxy logic.

## Run

After building the fat JAR:

```bash
./gradlew jar
./scripts/jsslproxyctl.sh --url http://localhost:9090 --token "$MANAGEMENT_TOKEN" status
```

Windows:

```powershell
.\gradlew.bat jar
powershell -ExecutionPolicy Bypass -File scripts\jsslproxyctl.ps1 --url http://localhost:9090 --token $env:MANAGEMENT_TOKEN status
```

Equivalent direct JAR form:

```bash
java -jar build/libs/JSSLProxy-1.0-SNAPSHOT.jar ctl --url http://localhost:9090 --token "$MANAGEMENT_TOKEN" status
```

## Configuration

- `JSSLPROXY_ADMIN_URL`: default management URL. Falls back to `http://localhost:9090`.
- `JSSLPROXY_ADMIN_TOKEN`: admin token.
- `--url` and `--token` override the environment.
- `--timeout` controls the HTTP request timeout.
- `--drain-timeout` maps to `/admin/drain?timeout=...`.
- `--json` prints machine-readable output.
- `--yes` is required for writes and dangerous operations.

The CLI never stores tokens and never sends tokens in query parameters.
Successful write operations print the management API response and, in human-readable mode, an audit reminder. `--json` mode emits only machine-readable response data.

Prefer `JSSLPROXY_ADMIN_TOKEN` for local shells and CI secret injection. Use `--token` only for short-lived manual commands where shell history is controlled. Do not encode tokens into URLs, scripts committed to the repository, wrapper defaults, or process supervisor files.

## Commands

```bash
jsslproxyctl status
jsslproxyctl stats
jsslproxyctl certs
jsslproxyctl rejected-certs --limit 50
jsslproxyctl metrics

jsslproxyctl blacklist list
jsslproxyctl blacklist add <fingerprint> --yes
jsslproxyctl blacklist remove <fingerprint> --yes
jsslproxyctl whitelist list
jsslproxyctl whitelist add <fingerprint> --yes
jsslproxyctl whitelist remove <fingerprint> --yes

jsslproxyctl reload config --yes
jsslproxyctl reload certs --yes
jsslproxyctl reload all --yes
jsslproxyctl drain start --drain-timeout 30 --yes
jsslproxyctl drain cancel --yes
jsslproxyctl connections list
jsslproxyctl connections terminate <fingerprint> --yes
```

## Exit Codes

| Code | Meaning |
|------|---------|
| `0` | Success |
| `2` | Invalid arguments or missing confirmation |
| `3` | Authentication or authorization failure |
| `4` | Management service unavailable |
| `5` | Server-side management API error |

## When To Use

Use the HTML UI for interactive triage and visual checks. Use the CLI for SSH sessions, scripts, CI/CD gates, and repeatable operations.

Common automation cases:

- Fail a deployment if `jsslproxyctl status --json` does not show a ready service.
- Capture `certs --json` before and after certificate rotation.
- Apply a known blacklist or whitelist fingerprint from an incident runbook.
- Start drain before maintenance and cancel it after validation.

Keep the management endpoint on localhost, a private operations network, or a temporary tunnel. The CLI is an admin API client, not a separate daemon or public control plane.
