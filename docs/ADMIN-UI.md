# Admin UI

> Scope: a lightweight browser surface for the existing management API. It does not add routing, certificate issuance, user management, or new gateway behavior.

## Entry Point

Open the static shell:

```bash
http://localhost:9090/admin/ui
```

The shell page is intentionally allowed without a token so a browser can load it directly. It contains no runtime state or secrets. Every data request still calls the existing JSON APIs and must pass `Authorization: Bearer <token>`.

The Connection panel includes a collapsible usage guide explaining what to enter for Admin URL and Admin token. In normal same-origin deployments, leave Admin URL empty and enter the configured management token.

## When To Use

Use the UI for manual triage: checking readiness, certificate status, rejected certificates, metrics summaries, and the current whitelist/blacklist. It is also useful during incident response when an operator needs to reload certs, start drain, or remove a bad fingerprint with clear confirmation prompts.

Use curl or the CLI instead for repeatable scripts, SSH-only sessions, CI/CD gates, and runbooks that must capture command output.

## Token Handling

- Enter the same token used for `/admin/*` curl calls.
- The browser stores it only in `sessionStorage`.
- The token is never placed in a query string, HTML source, server-side session, or backend file.
- If a protected API returns `401` or `403`, the UI stops retrying protected endpoints until the token is entered again.
- Use **Clear token** before handing over a shared workstation.

## Language

Use the language selector in the top bar to switch between English and Simplified Chinese. The preference is kept in `sessionStorage`; it is not sent to the server and does not affect API requests or token handling.

## Available Panels

- Health and readiness from `/health` and `/ready`.
- Runtime details from `/admin/info` and `/admin/stats`.
- Certificate status from `/admin/certs`.
- Metrics summary from `/metrics`.
- Recent rejected certificates from `/admin/rejected-certs`.
- Dynamic whitelist and blacklist from `/admin/whitelist` and `/admin/blacklist`.
- Controlled operations: reload, drain/cancel drain, and terminate connections by fingerprint.

## Security Decisions

- UI resources are served from classpath under `src/main/resources/admin-ui/`.
- No external CDN, font, script, image, or frontend build chain is used.
- CSP allows scripts and styles from `self`; API calls are same-origin plus localhost debug origins.
- Response headers include `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store`, and `frame-ancestors 'none'`.
- Dangerous operations require browser confirmation and remain audited by the server-side management API.

## Production Exposure

Keep the management port on `127.0.0.1` or a protected operations network. If it must be exposed beyond localhost, require `MANAGEMENT_TOKEN` and enforce network-level access control. The UI is an operations convenience, not an internet-facing console.

Recommended access patterns:

- Local machine: `http://127.0.0.1:9090/admin/ui`.
- Remote host: SSH tunnel to `127.0.0.1:9090`, then open the local tunnel URL.
- Container or Kubernetes: bind the management listener to an internal interface and reach it through a controlled admin network or port-forward.

Do not put the token in a URL, reverse-proxy access log, bookmark, shell history, or persistent browser storage.
