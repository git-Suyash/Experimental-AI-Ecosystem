# Security Policy & Threat Model

> **Version:** 1.0.0 | **Classification:** Internal — Engineering & Security

---

## Table of Contents
1. [Threat Model](#1-threat-model)
2. [Attack Surface](#2-attack-surface)
3. [Injection Scanner Rules](#3-injection-scanner-rules)
4. [Key Management](#4-key-management)
5. [Vulnerability Disclosure](#5-vulnerability-disclosure)
6. [Security Checklist](#6-security-checklist)

---

## 1. Threat Model

### Assets to Protect
| Asset | Sensitivity | Impact if Compromised |
|---|---|---|
| Verified prompt content | High | Poisoned prompts reach all agents organisation-wide |
| API signing keys | Critical | Attacker can forge legitimate-looking artefacts |
| User API keys | High | Unauthorised publish/read of proprietary prompts |
| Audit logs | High | Compliance failure, forensics loss |
| Database credentials | Critical | Full data exfiltration |
| S3 bucket contents | High | Bulk theft of all artefacts |

### Threat Actors

**External attacker** — no credentials, attempting to exploit public-facing API  
**Compromised contributor** — valid API key, limited to draft publishes within one namespace  
**Malicious insider** — admin-level access, attempting to poison verified artefacts  
**Supply chain attacker** — attempting to tamper with artefacts after they are stored  

### STRIDE Analysis

| Threat | Vector | Mitigation |
|---|---|---|
| **Spoofing** | Forged API key | Keys are hashed (Argon2id) at rest; timing-safe comparison on verify |
| **Tampering** | S3 object modified after signing | HMAC-SHA256 signature verified at fetch time by SDK |
| **Repudiation** | Deny publishing a harmful prompt | Immutable audit log with user, IP, timestamp, checksum |
| **Info Disclosure** | Unauthenticated S3 access | Bucket is fully private; no public ACLs; all access via signed API |
| **Denial of Service** | Flooding publish endpoint | Per-key rate limits; WAF rules; scanner CPU caps |
| **Elevation of Privilege** | Contributor tries to promote to verified | Status transitions enforce RBAC server-side; JWT claims re-validated per request |

---

## 2. Attack Surface

```
Internet
   │
   ▼
WAF (OWASP Core Rule Set + custom injection rules)
   │
   ▼
API Gateway (TLS 1.2+ enforced, HTTP/2)
   │
   ├── POST /registry/publish     ← injection entry point; highest risk
   ├── GET  /registry/**          ← read; low risk if signatures verified
   ├── POST /registry/*/test      ← executes prompts against LLM; rate-limited aggressively
   └── POST /admin/**             ← admin only; IP allowlist recommended
```

### Rate Limits by Endpoint

| Endpoint group | Limit | Window |
|---|---|---|
| `GET /registry/**` | 1000 req | 1 minute per key |
| `POST /registry/publish` | 20 req | 1 minute per key |
| `POST /registry/*/test` | 5 req | 1 minute per key |
| `POST /admin/**` | 30 req | 1 minute per key |
| Auth failures (any) | 10 req | 15 minutes per IP — then block |

---

## 3. Injection Scanner Rules

### Severity Classification

**CRITICAL — publish blocked, no override:**
```
INJ-C001  /ignore\s+(all\s+)?(previous|above|prior)\s+instructions/i
INJ-C002  /disregard\s+your\s+(system|original)\s+prompt/i
INJ-C003  /\{\{.*?(exec|eval|__import__|subprocess|os\.system).*?\}\}/i
INJ-C004  /<\s*script\b/i
INJ-C005  /new\s+role\s*:/i
```

**HIGH — publish blocked, admin can override with written justification:**
```
INJ-H001  /you\s+are\s+now\s+(a|an)\s+/i
INJ-H002  /system\s*:\s*override/i
INJ-H003  /\[SYSTEM\]/i
INJ-H004  /<!--[\s\S]*?-->/g
INJ-H005  Unbalanced template delimiters {{ without matching }}
```

**MEDIUM — publish allowed, flags for reviewer attention:**
```
INJ-M001  /pretend\s+(you\s+are|to\s+be)\s+/i
INJ-M002  /as\s+(a|an)\s+AI\s+without\s+restrictions/i
INJ-M003  Unsanitised variable used directly in system prompt
INJ-M004  Variable max_length not defined (open-ended injection surface)
```

### False Positive Management

Legitimate prompts may contain phrases like "ignore the formatting above" in a different context. Contributors can annotate a finding to suppress it with a justification. Suppression requires reviewer approval and is recorded in the audit log permanently.

```yaml
security:
  suppressed_findings:
    - id: INJ-H001
      justification: "Phrase used in output example, not as an instruction"
      suppressed_by: "jane.doe@company.com"
      suppressed_at: "2026-01-15T10:00:00Z"
      approved_by: "security-team@company.com"
```

---

## 4. Key Management

### Signing Key Rotation Schedule

| Key | Algorithm | Rotation | Storage |
|---|---|---|---|
| Artefact signing key | HMAC-SHA256 (256-bit) | Quarterly | Secrets Manager |
| JWT secret | HMAC-SHA256 (512-bit) | 90 days | Secrets Manager |
| Webhook signing key | HMAC-SHA256 (256-bit) | Annually | Secrets Manager |
| API key hash pepper | Argon2id pepper | Annually | Secrets Manager |

**Key rotation procedure:**
1. Generate new key in Secrets Manager
2. Deploy API with both old and new keys active (dual-verify window)
3. Re-sign all `verified` artefacts with new key (background job)
4. After 90 days, retire old key
5. Log rotation event to audit trail

---

## 5. Vulnerability Disclosure

**Reporting:** Email `security@yourcompany.com` with subject `[REGISTRY] Vulnerability Report`  
**Response SLA:** Acknowledge within 24h; triage within 72h; patch critical within 7 days  
**Scope:** Registry API, CLI binary, SDK packages, S3 bucket configuration  
**Out of scope:** Social engineering, physical attacks, third-party LLM providers  

Reporters who responsibly disclose valid vulnerabilities will be credited in release notes (with consent).

---

## 6. Security Checklist

Use this before every production deployment:

```
Infrastructure
[ ] S3 bucket public access block is ON
[ ] S3 bucket versioning is enabled
[ ] S3 SSE-KMS encryption is active
[ ] All API traffic is TLS 1.2+
[ ] WAF is active with OWASP CRS enabled
[ ] Database is not publicly accessible
[ ] Redis is not publicly accessible
[ ] Secrets are in Secrets Manager — no plaintext env vars for credentials

Application
[ ] Signing key is loaded from Secrets Manager at startup
[ ] Rate limiting is active on all write endpoints
[ ] CORS policy restricts origins to known dashboard domain
[ ] Admin endpoints are IP-restricted or behind VPN
[ ] Audit logging is confirmed writing to immutable store
[ ] JWT expiry is ≤ 24 hours

Operational
[ ] API keys for CI pipelines have minimum required scope (contributor, namespace-scoped)
[ ] No wildcard namespace grants exist for non-admin keys
[ ] Signing key rotation is scheduled in calendar
[ ] Last security scan run date is within 90 days
```
