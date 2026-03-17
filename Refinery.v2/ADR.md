# Architecture Decision Records (ADR)

> Decisions are immutable once accepted. Superseded decisions are marked but not deleted.

---

## ADR-001 — Use S3 as Primary Artefact Store

**Date:** 2026-03-01  
**Status:** Accepted

### Context
We need a durable, scalable store for YAML artefact files. Options considered: S3, Git repository, PostgreSQL JSONB, dedicated document store (MongoDB).

### Decision
Use S3 (or equivalent object store: GCS, Azure Blob) as the primary store for artefact content files.

### Rationale
- Artefact files are immutable after signing — object storage is the natural fit
- S3 supports native versioning, cross-region replication, and lifecycle policies without custom code
- Decouples content storage from metadata; each can scale independently
- Cost: S3 GET is $0.0004 per 1,000 requests — negligible at registry scale
- Swappable: abstracting behind a `StorageAdapter` interface allows GCS or Azure Blob with no business logic changes

### Consequences
- Metadata (versions, status, search index) must live in PostgreSQL — S3 alone is not queryable
- All access to S3 must be via the API; no direct client access to the bucket

---

## ADR-002 — Use Bun as the API Runtime

**Date:** 2026-03-01  
**Status:** Accepted

### Context
Choosing a Node.js-compatible runtime for the API server. Options: Node.js, Deno, Bun.

### Decision
Use Bun with the Hono router framework.

### Rationale
- Bun starts ~4x faster than Node.js — important for serverless or container cold starts
- Native TypeScript execution without a separate transpile step simplifies the build pipeline
- Hono is lightweight (< 15KB), framework-agnostic, and runs identically on Bun, Node, and edge runtimes
- Bun's built-in test runner and bundler reduces toolchain complexity
- Bun can compile to a single executable binary — used for the CLI tool

### Consequences
- Some Node.js native modules may not be compatible with Bun; validate dependencies at upgrade time
- Team must be comfortable with Bun's API surface (largely mirrors Node.js)

---

## ADR-003 — Semver with Enforced Bump Semantics

**Date:** 2026-03-01  
**Status:** Accepted

### Context
Version control for prompts is novel. Options: sequential integers (v1, v2), dates (2026-03-01), semver.

### Decision
Use strict semantic versioning (MAJOR.MINOR.PATCH) with registry-enforced semantics for what constitutes each bump type.

### Rationale
- Agents can express compatibility ranges (`@2`, `@2.1`) that survive non-breaking updates without code changes
- The semantics of breaking vs. non-breaking changes in prompts mirror software library semver well
- Tooling (diff, changelog generation) is straightforward with semver
- Familiar to engineers who already use semver for code dependencies

### Consequences
- We must enforce bump type at publish time — the API rejects a `patch` bump if breaking changes are detected
- Contributors must understand what constitutes a breaking change for prompts (documented in CONTRIBUTING.md)

---

## ADR-004 — HMAC-SHA256 for Artefact Signing

**Date:** 2026-03-01  
**Status:** Accepted

### Context
We need a tamper-detection mechanism for artefacts stored in S3. Options: asymmetric signing (RSA/ECDSA), HMAC-SHA256, checksums only.

### Decision
Use HMAC-SHA256 for artefact signatures, with the signing key stored in Secrets Manager.

### Rationale
- HMAC-SHA256 is sufficient for the threat model: we control both signing (server at publish) and verification (SDK at fetch). Asymmetric signing would be needed only if third parties needed to verify without trusting our infrastructure.
- Simpler key management than RSA/ECDSA key pairs
- SHA-256 checksums alone (without HMAC) detect accidental corruption but not deliberate tampering — HMAC is necessary

### Consequences
- The signing key must be treated as a critical secret; compromise allows forging of signatures
- Key rotation requires re-signing all verified artefacts (background job, documented in RUNBOOK.md)
- Verification in the SDK requires the public verify key to be distributed — done via the SDK configuration

---

## ADR-005 — Redis for Caching over Memcached

**Date:** 2026-03-01  
**Status:** Accepted

### Context
The registry read path is cache-heavy. A caching layer is needed for the API server. Options: Redis, Memcached.

### Decision
Use Redis as the caching and session layer.

### Rationale
- Redis covers multiple needs in one system: L2 cache, rate-limit counters (atomic INCR), cache invalidation pub/sub across API pods, and CLI session storage
- Memcached would require a separate solution for rate limiting and inter-pod cache invalidation
- At registry data volumes (thousands of artefacts, each < 20KB), Redis memory overhead is negligible
- Redis persistence (RDB snapshots) provides some resilience to cache cold-start storms after restarts

### Rejection of Memcached
Memcached has marginally better raw throughput for pure string key-value lookups. However, the registry's caching needs extend beyond simple string reads to include pub/sub invalidation and atomic counters. Running both Memcached and Redis would add operational complexity with no meaningful benefit.

### Consequences
- Redis is a stateful dependency; its availability affects cache hit rate but not service availability (graceful degradation to S3/DB on cache miss)

---

## ADR-006 — PostgreSQL for Metadata over DynamoDB

**Date:** 2026-03-01  
**Status:** Accepted

### Context
Metadata storage for artefact versions, users, namespaces, and audit logs. Options: PostgreSQL, DynamoDB, SQLite.

### Decision
Use PostgreSQL as the metadata store.

### Rationale
- Full-text search (`tsvector`, `GIN` indexes) is built in — no Elasticsearch needed at MVP scale
- JSONB support allows flexible metadata fields without schema migrations for every new field
- ACID transactions for status promotions (must be atomic: update status + write audit log)
- DynamoDB requires careful access pattern planning upfront and is harder to query ad hoc — poor fit for an admin/analytics-heavy workload
- SQLite is suitable for local dev and single-node deployments only; does not support the concurrent write pattern of multi-pod deployments

### Consequences
- Requires connection pooling (PgBouncer) at scale to avoid connection exhaustion
- Schema migrations must be managed and backward compatible

---

## ADR-007 — Three-Tier Injection Scanner Architecture

**Date:** 2026-03-01  
**Status:** Accepted

### Context
Security scanning for prompt injection must be fast enough to not block publishes, yet thorough enough to catch sophisticated attacks.

### Decision
Implement a three-tier scanner: (1) synchronous pattern matching, (2) synchronous structural validation, (3) async LLM judge for review-stage promotion.

### Rationale
- Tier 1 runs in < 10ms — no latency impact on publish
- Tier 2 catches structural issues (unbalanced templates, embedded code) that patterns miss
- Tier 3 (LLM judge) catches novel attacks that patterns cannot anticipate, but runs only at review time, not at every publish — keeping the publish path fast
- Separating tiers allows disabling the LLM judge in an emergency without disabling the scanner entirely

### Consequences
- Tier 3 introduces a dependency on an external LLM API for the review promotion step
- LLM judge results are advisory, not blocking — final security decision remains with human reviewer
- Scanner rules must be versioned so findings can be reproduced and audited

---

## ADR-008 — Namespace-Scoped RBAC over Global Roles

**Date:** 2026-03-01  
**Status:** Accepted

### Context
Access control design. Options: global roles only, namespace-scoped roles, attribute-based access control (ABAC).

### Decision
Implement namespace-scoped RBAC: users have roles within specific namespaces, not globally (except org-level admins).

### Rationale
- The `legal` team should own their prompts independently of the `engineering` team
- A compromised CI key for `engineering` should not be able to read or modify `legal` prompts
- Global roles were too coarse for enterprise multi-team use
- ABAC would be more flexible but significantly more complex to implement and reason about

### Consequences
- Users who work across namespaces need explicit membership in each
- API keys must declare their namespace scope at creation time
- Org-level admin role still exists for cross-namespace operations (deprecation, member management)
