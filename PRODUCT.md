# Product

## Register

product

## Users

JSSLProxy is used by platform engineers, backend engineers, and operators who need a small mTLS gateway in front of internal HTTP/HTTPS services. They work from terminals, CI jobs, Docker environments, and occasional browser-based operational triage.

## Product Purpose

JSSLProxy provides lightweight TLS termination, client certificate admission control, runtime certificate lists, health checks, metrics, and admin operations without becoming a full API gateway or service mesh. Success means an operator can deploy it safely, verify it quickly, and diagnose certificate or backend issues without guessing.

## Brand Personality

Restrained, operational, precise.

## Anti-references

Avoid marketing dashboards, decorative observability screens, generic API gateway sprawl, complex user administration, and frontend stacks that require a separate build/deploy lifecycle.

## Design Principles

- Keep operational surfaces dense, readable, and task-focused.
- Prefer explicit safety states over decorative status visuals.
- Make dangerous actions possible but hard to trigger accidentally.
- Do not expose secrets, certificate material, or internal exception details.
- Preserve the project boundary: UI and CLI consume existing admin APIs rather than adding gateway features.

## Accessibility & Inclusion

Use keyboard-accessible controls, visible focus states, readable contrast, non-color-only status indicators, reduced-motion-friendly transitions, and layouts that work on desktop and narrow mobile widths.
