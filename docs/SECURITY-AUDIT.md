# Security Audit

> Date: 2026-06-09
> Scope: T-23, security configuration and certificate paths for the 1.3.0 quality pass.

## Scope Reviewed

- Configuration validation: `ProxyConfig`, `application.yml`.
- Client certificate validation: `ClientAuthenticator`, `SSLAuthHandler`.
- OCSP/CRL revocation checks: `CertificateRevocationChecker`.
- Management authentication and admin APIs: `ManagementAuthHandler`, `AdminApiHandler`.
- Certificate introspection and rejected-certificate tracking: `SslContextHolder`, `RejectedCertTracker`.

## Current Security Posture

Management endpoints default to `127.0.0.1:9090` with `/admin/*` token authentication enabled. If management is exposed externally, startup validation rejects configurations with disabled auth or a missing token. Admin auth accepts `Authorization: Bearer <token>` and `X-Admin-Token`, using constant-time comparison.

Client certificates are checked for blacklist, whitelist, expiry, weak RSA/EC keys, weak signatures, revocation, and trust-chain validity. OCSP/CRL URLs are parsed through structured ASN.1 extraction and are checked against SSRF rules before remote access.

OCSP/CRL remote reads are bounded to 1 MB. OCSP responses and CRLs are signature-verified when issuer material is available. Revocation checks default to hard-fail when enabled.

## Fix Applied

Admin certificate reload failures no longer return raw exception messages in the HTTP response. The server still logs the detailed exception for operators, but the response body now uses a generic `Certificate reload failed` message. This avoids leaking filesystem paths, password-related strings, private key names, or library internals through the admin API.

Changed files:

- `src/main/java/com/sslproxy/management/handler/AdminApiHandler.java`
- `src/test/java/com/sslproxy/management/handler/AdminApiHandlerSecurityTest.java`

## Evidence

Existing tests cover the main T-23 requirements:

| Requirement | Evidence |
|-------------|----------|
| Unsafe external management exposure fails startup | `ManagementSecurityValidationTest` |
| Missing or invalid admin token returns 401/403 | `ManagementAuthHandlerTest` |
| Admin fingerprint format validation | `AdminApiConnectionTerminateTest` |
| Sensitive reload errors are not reflected to HTTP clients | `AdminApiHandlerSecurityTest` |
| Weak RSA, MD5, SHA-1 client certs are rejected | `ClientAuthenticatorTest` |
| OCSP/CRL SSRF blocks localhost, private ranges, CGNAT, ULA, and cloud metadata | `CertificateRevocationCheckerSecurityTest` |
| Certificate utilities avoid raw private key/keystore exposure | `CertificateUtilTest`, `SslContextHolderTest` |

## Residual Notes

- The default `application.yml` is a development sample and still contains `clientAuth: NONE` plus `changeit` placeholder passwords. Production docs must keep saying these are not production values.
- `/health`, `/ready`, and `/metrics` remain unauthenticated by design. `/admin/info` and all other `/admin/*` routes are protected by `ManagementAuthHandler`.
- Admin list endpoints return certificate fingerprints to authenticated operators. This is operationally useful but should stay behind admin auth and local/network controls.
- Docker is the authoritative local verification environment for now because host JDK 25 cannot run the Gradle build.

## Verification Commands

```powershell
docker run --rm -v "${PWD}:/workspace" -w /workspace gradle:8.14-jdk17 ./gradlew test --tests "com.sslproxy.management.handler.AdminApiHandlerSecurityTest"
docker run --rm -v "${PWD}:/workspace" -w /workspace gradle:8.14-jdk17 ./gradlew test --tests "*Security*" --tests "*Certificate*" --tests "*Management*"
```
