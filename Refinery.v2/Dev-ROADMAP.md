# Developer Roadmap — AI Registry
### A Guide for Junior Engineers: From Idea to Production

> Written as if your senior dev is sitting next to you.  
> Version 1.0 | Start date: whenever you're ready.

---

## Before Anything Else — The Right Mental Model

Before writing a single line of code, understand this:

**You are not building a feature. You are building a platform.**

The difference matters. A feature serves one use case. A platform serves many use cases you haven't thought of yet. This means your first instinct to "just make it work" needs to be balanced with "make it work in a way that won't paint you into a corner."

The mental model I want you to carry through this entire project:

```
1. Make it work   (correctness first)
2. Make it right  (clean structure, proper abstractions)
3. Make it fast   (optimise only what is actually slow)
```

Never skip step 1 to get to step 3. Never skip step 2 because step 1 works.

One more thing before we start — **resist the urge to build everything at once.** The architecture doc we have is the target state. Your job is to reach it incrementally, with each step being a working, testable system. A system that works for 10 artefacts and 1 user is infinitely more valuable than a half-built system designed for 10,000 artefacts.

---

## Part 1 — How to Think About This Project

### The Core Loop

Strip everything away. What does the registry actually do?

```
Someone pushes a prompt  →  It gets stored  →  Someone else pulls it
```

That is the entire product. Everything else — versioning, scanning, auth, the CLI, the SDK — is infrastructure around that one loop. Build that loop first, then layer everything else on top.

### Layers of the System

Think in layers. Each layer has one job and talks to the layers next to it:

```
┌────────────────────────────────────────────┐
│  Interface Layer                           │  ← CLI, SDK, Web UI
│  "How humans and machines talk to us"      │
├────────────────────────────────────────────┤
│  API Layer                                 │  ← Bun + Hono routes
│  "HTTP in, HTTP out"                        │
├────────────────────────────────────────────┤
│  Business Logic Layer                      │  ← Services: versioning,
│  "The rules of the registry"               │     scanning, signing
├────────────────────────────────────────────┤
│  Data Layer                                │  ← PostgreSQL, Redis, S3
│  "Where things live"                       │
└────────────────────────────────────────────┘
```

A route handler should never talk directly to the database. A service should never parse an HTTP request. Keep layers clean. When you violate this, you create code that is impossible to test and painful to change.

### How to Approach Every Feature

For any new feature, ask these questions in order:

```
1. What data does this need?        → design the DB schema / storage structure first
2. What does this return?           → design the API response shape
3. What can go wrong?               → list every failure mode before writing happy path
4. How do I know it works?          → write the test before or immediately after the code
5. How does a user invoke this?     → CLI command or SDK method last, once API is solid
```

---

## Part 2 — Monorepo Setup

### Why a Monorepo

You have at least four distinct packages that share types and utilities:
- `api` — the Bun server
- `cli` — the command-line tool
- `sdk-ts` — TypeScript SDK
- `sdk-python` — Python SDK (later)

Without a monorepo, sharing a type like `Artefact` between the API and the SDK means either duplicating it or publishing a separate package just for types. With a monorepo, all packages share from a single `@registry/types` package. Changes to a type immediately surface as TypeScript errors everywhere it is used.

### Directory Structure

Set this up on day one. Do not change it later — restructuring a codebase mid-development is expensive.

```
ai-registry/
│
├── packages/
│   ├── api/               ← Bun + Hono API server
│   ├── cli/               ← Command-line tool
│   ├── sdk-ts/            ← TypeScript/JS SDK
│   └── types/             ← Shared TypeScript types (no runtime code)
│
├── docs/                  ← Your markdown docs live here
├── scripts/               ← Dev scripts (seed, reset, etc.)
├── docker-compose.dev.yml ← Local PostgreSQL + Redis
│
├── bunfig.toml            ← Bun workspace config
├── package.json           ← Root workspace package.json
└── tsconfig.base.json     ← Shared TypeScript config
```

### Initialise the Workspace

```bash
mkdir ai-registry && cd ai-registry
git init

# Root package.json — this makes it a Bun workspace
cat > package.json << 'EOF'
{
  "name": "ai-registry",
  "private": true,
  "workspaces": ["packages/*"],
  "scripts": {
    "dev": "bun run --filter @registry/api dev",
    "test": "bun test packages/",
    "lint": "bunx @biomejs/biome check packages/"
  }
}
EOF

# Create package directories
mkdir -p packages/{api,cli,sdk-ts,types}/src

# Initialise each package
cd packages/types   && bun init -y && cd ../..
cd packages/api     && bun init -y && cd ../..
cd packages/cli     && bun init -y && cd ../..
cd packages/sdk-ts  && bun init -y && cd ../..
```

### Shared TypeScript Config

```json
// tsconfig.base.json — at root
{
  "compilerOptions": {
    "target": "ES2022",
    "module": "ESNext",
    "moduleResolution": "bundler",
    "strict": true,
    "exactOptionalPropertyTypes": true,
    "noUncheckedIndexedAccess": true,
    "verbatimModuleSyntax": true
  }
}
```

Each package `tsconfig.json` extends this:

```json
// packages/api/tsconfig.json
{
  "extends": "../../tsconfig.base.json",
  "compilerOptions": {
    "outDir": "./dist",
    "paths": {
      "@registry/types": ["../types/src/index.ts"]
    }
  }
}
```

### The `types` Package — Start Here

Define your shared types before writing any API or SDK code. These types are your contract between all packages.

```typescript
// packages/types/src/artefact.ts

export type ArtefactType = 'prompt' | 'skill' | 'chain' | 'tool';
export type ArtefactStatus = 'draft' | 'review' | 'verified' | 'deprecated';
export type BumpType = 'patch' | 'minor' | 'major';
export type RiskLevel = 'low' | 'medium' | 'high' | 'critical';

export interface Variable {
  name: string;
  type: 'string' | 'array' | 'object' | 'enum' | 'number' | 'boolean';
  required: boolean;
  default?: unknown;
  description?: string;
  maxLength?: number;
}

export interface ArtefactContent {
  system?: string;
  user_template?: string;
  output_schema?: Record<string, unknown>;
}

export interface SecurityMetadata {
  injectionScanned: boolean;
  scanDate: string;
  scanVersion: string;
  riskLevel: RiskLevel;
  riskScore: number;
  findings: ScanFinding[];
}

export interface ScanFinding {
  id: string;
  severity: RiskLevel;
  pattern: string;
  location: string;
  line?: number;
}

export interface Artefact {
  id: string;                    // "namespace/name"
  version: string;               // semver
  status: ArtefactStatus;
  type: ArtefactType;
  content: ArtefactContent;
  variables: Variable[];
  metadata: {
    tags: string[];
    description: string;
    compatibleModels: string[];
    compatibleFrameworks: string[];
  };
  security: SecurityMetadata;
  checksum: string;
  signature: string;
  publishedAt: string | null;
  changelog?: string;
}

// What the API returns for list endpoints (no content, lightweight)
export interface ArtefactSummary {
  id: string;
  version: string;
  status: ArtefactStatus;
  type: ArtefactType;
  description: string;
  tags: string[];
  updatedAt: string;
}
```

```typescript
// packages/types/src/api.ts — request/response shapes

export interface PublishRequest {
  namespace: string;
  name: string;
  type: ArtefactType;
  bumpType: BumpType;
  changelog?: string;
  content: ArtefactContent;
  metadata: {
    tags: string[];
    description: string;
    compatibleModels: string[];
    compatibleFrameworks: string[];
  };
  variables: Variable[];
}

export interface PublishResponse {
  id: string;
  version: string;
  status: ArtefactStatus;
  checksum: string;
  signature: string;
  security: Pick<SecurityMetadata, 'riskLevel' | 'riskScore' | 'findings'> & { passed: boolean };
  message: string;
}

export interface ApiError {
  error: string;
  message: string;
  requestId: string;
  details?: unknown[];
}
```

```typescript
// packages/types/src/index.ts — barrel export
export * from './artefact';
export * from './api';
```

**Stop and think about types for half a day before moving on.** Getting them right now saves you from painful refactors later. Every time you think "I'll just use `any` for now," future-you will suffer.

---

## Part 3 — Development Roadmap

### Phase 0 — Foundation (Week 1)
> Goal: Infrastructure running locally. You can store and retrieve a file.

```
[ ] Monorepo initialised with workspace structure
[ ] Shared types package with core types
[ ] docker-compose.dev.yml with PostgreSQL and Redis
[ ] Database schema migrations (artefacts, artefact_versions, namespaces, users tables)
[ ] S3 local adapter (reads/writes to local filesystem, same interface as real S3)
[ ] Health check endpoint: GET /health returns 200 with db + cache status
[ ] .env.example with all required variables documented
```

**What you learn this week:** Bun workspace setup, PostgreSQL basics, Docker Compose, how to write a database migration.

**How you know you're done:** `curl localhost:3000/health` returns `{"status":"ok"}` with no errors.

---

### Phase 1 — The Core Loop (Weeks 2–3)
> Goal: You can publish a prompt and fetch it back. No auth, no scanning, no versioning edge cases.

```
[ ] POST /registry/publish
      - Accept JSON body
      - Validate required fields (namespace, name, type, content)
      - Store YAML file in local storage adapter
      - Write metadata row to artefact_versions table
      - Return 201 with id, version, status

[ ] GET /registry/:namespace/:name
      - Read from storage adapter
      - Return artefact JSON
      - Return 404 if not found

[ ] GET /registry (list endpoint)
      - Query artefact_versions from DB
      - Support ?namespace= filter
      - Return paginated list of ArtefactSummary

[ ] Basic YAML validation (required fields only)
[ ] Tests for each route: happy path + 404 case
```

**What you learn:** Hono routing, request validation, YAML parsing, SQL queries with Bun's pg driver, writing route-level tests.

**How you know you're done:** You can push a YAML file with the CLI (even if it's just `curl` for now) and fetch it back with the full content.

---

### Phase 2 — Versioning (Week 4)
> Goal: The registry understands semver. Multiple versions of the same artefact can coexist.

```
[ ] Auto-increment version on publish based on bumpType
[ ] GET /registry/:namespace/:name defaults to latest verified
      (or latest draft if no verified version exists yet)
[ ] GET /registry/:namespace/:name@:version fetches exact version
[ ] GET /registry/:namespace/:name@2 resolves to latest 2.x.x
[ ] Version conflict check: reject publish if version already exists
[ ] meta.json written to storage on every publish (version index)
[ ] GET /registry/:namespace/:name/history endpoint
[ ] PATCH /registry/:namespace/:name@:version/status (promote/demote)
      - Enforce valid transitions (draft→review→verified, etc.)
      - Write to audit_log table
[ ] Tests covering version resolution logic thoroughly
```

**What you learn:** Semver parsing (use the `semver` npm package, don't write this yourself), state machine patterns, immutability in data design.

**The semver resolution logic is subtle — test it exhaustively.** Edge cases like `@2` when only `2.0.0-draft` exists, or `@latest` when all versions are deprecated, will bite you if not handled.

---

### Phase 3 — Auth (Week 5)
> Goal: API keys work. Namespaces are access-controlled.

```
[ ] users table + api_keys table in DB
[ ] POST /auth/login → returns JWT
[ ] API key validation middleware (reads Authorization header)
[ ] JWT validation middleware
[ ] db:seed creates a dev admin user + dev API key
[ ] Namespace membership check on publish and fetch
[ ] Role check on status promotion (contributor cannot promote to verified)
[ ] GET /admin/api-keys, POST /admin/api-keys, DELETE /admin/api-keys/:id
[ ] Tests: unauthenticated requests return 401, wrong role returns 403
```

**What you learn:** JWT (use `jose` library, not `jsonwebtoken`), middleware patterns in Hono, hashing with Argon2 (use `argon2` library), timing-safe comparison for API key validation.

**Important:** Never store API keys in plaintext. Hash them with Argon2 before writing to the DB. Compare using `crypto.timingSafeEqual` — never `===`. This is not optional.

---

### Phase 4 — Security Scanner (Week 6)
> Goal: Prompts are scanned before publish. Injection patterns are caught.

```
[ ] Scanner service: takes artefact content, returns ScanResult
[ ] Layer 1: Pattern matching (regex rules for CRITICAL + HIGH severity)
[ ] Layer 2: Structural validation (balanced templates, no code in vars)
[ ] Scanner runs synchronously in publish route before storage write
[ ] Publish blocked on CRITICAL/HIGH findings by default
[ ] SCANNER_FAIL_ON env var controls threshold
[ ] Scan result written to artefact_versions.security JSONB column
[ ] Tests: one test per injection pattern, clean prompt test, edge cases
```

**What you learn:** Regex patterns, JSONB in PostgreSQL, how to design a service that is independently testable (no HTTP, no DB — pure function in, result out).

**Design the scanner as a pure function:** `scan(content: ArtefactContent): ScanResult`. It takes content, returns a result. No database calls, no HTTP calls. Pure. This makes it trivially testable and reusable.

---

### Phase 5 — Content Signing (Week 7)
> Goal: Every artefact has a cryptographic signature. The SDK verifies it.

```
[ ] SigningService: sign(content) → signature string
[ ] VerifyService: verify(content, signature) → boolean
[ ] Signature generated at publish time, stored in artefact_versions
[ ] Checksum (SHA-256) generated and stored at publish time
[ ] GET /registry response includes signature and checksum
[ ] SDK verify() method checks signature before returning artefact
[ ] Test: tamper with content after fetch, verify() should throw
```

**What you learn:** Web Crypto API (`crypto.subtle`), HMAC-SHA256, what "signing" actually means and why checksums alone are not enough.

---

### Phase 6 — CLI (Weeks 8–9)
> Goal: A real CLI binary that an engineer can use without touching curl.

```
[ ] ai-reg push <file>
[ ] ai-reg pull <id[@version]>
[ ] ai-reg search <query>
[ ] ai-reg validate <file>  (local scan, no publish)
[ ] ai-reg promote <id@version> --to <status>
[ ] ai-reg config set/get
[ ] ai-reg auth login (prompts for URL + API key, saves to ~/.ai-reg/config.json)
[ ] Readable output with colours (use chalk or kleur)
[ ] --json flag on all commands for machine-readable output
[ ] Bun compile to single binary
[ ] .registry-lock file written by pull --pin
```

**What you learn:** CLI design with commander.js or citty, reading/writing JSON config files, process.exit() codes, stdin/stdout/stderr distinction, binary compilation with Bun.

**Good CLI design principle:** Every command should work non-interactively (via flags) for CI, and interactively (via prompts) for humans. Never require a TTY.

---

### Phase 7 — TypeScript SDK (Week 10)
> Goal: Any agent can pull a prompt in two lines of code.

```
[ ] RegistryClient class with constructor options
[ ] get(id, options?) method
[ ] list(filters?) method
[ ] search(query, filters?) method
[ ] publish(payload) method
[ ] verify(artefact) method — throws RegistrySignatureError if invalid
[ ] Artefact.render(variables) — template substitution
[ ] In-memory L1 cache with TTL
[ ] Proper TypeScript types exported (consumers get full autocomplete)
[ ] README with copy-pasteable examples for each framework
[ ] Published to internal npm registry or GitHub Packages
```

**What you learn:** Package design (what to export, what to keep private), template literal parsing, cache-aside pattern, writing a library vs. writing an application (different mindsets).

---

### Phase 8 — Redis Caching (Week 11)
> Goal: The API does not hit S3 or the DB for every fetch request.

```
[ ] Redis client setup in API server
[ ] Cache-aside pattern in fetch route:
      1. Check Redis → if hit, return cached
      2. If miss → fetch from S3 + DB → write to Redis → return
[ ] Cache invalidation on status change (promote/demote writes delete cache key)
[ ] Cache TTL: 1h for verified, 60s for draft/review
[ ] Cache key format: registry:{namespace}:{name}:{version}
[ ] Rate limiting middleware using Redis INCR (per API key, per minute)
[ ] Test: fetch same artefact twice, assert DB is only called once
```

**What you learn:** Cache-aside vs. write-through patterns, Redis key design, TTL-based expiry, the cache invalidation problem (one of the hardest problems in CS — you will understand why).

---

### Phase 9 — Real S3 + Production Readiness (Week 12)
> Goal: Deploy to a real cloud environment. Real S3. Real users.

```
[ ] S3 adapter implementation (replace local adapter, same interface)
[ ] Structured JSON logging (every request logs requestId, latency, status)
[ ] /health endpoint includes detailed dependency checks
[ ] Graceful shutdown (drain in-flight requests before process exit)
[ ] Docker image for API (multi-stage build, non-root user)
[ ] Environment variable validation at startup (fail fast if required vars missing)
[ ] Database connection pool tuning
[ ] Load test: simulate 100 concurrent fetches, verify p99 < 200ms
[ ] README for production deployment (AWS or GCP)
```

**What you learn:** Multi-stage Docker builds, 12-factor app principles, graceful shutdown patterns, load testing with k6 or autocannon.

---

## Part 4 — Day-to-Day Development Practices

### Commit Discipline

Write commits as if someone else will read them (they will — and that someone is future you).

```bash
# Bad
git commit -m "fix"
git commit -m "wip"
git commit -m "stuff"

# Good
git commit -m "feat(scanner): add CRITICAL pattern INJ-C001 for instruction override"
git commit -m "fix(versioning): resolve @major alias to latest verified in semver range"
git commit -m "test(auth): add 403 case for contributor promoting to verified"
```

Format: `type(scope): description`  
Types: `feat`, `fix`, `test`, `refactor`, `docs`, `chore`

Commit after every logical unit of work — not after every file save, not after every feature. A commit should represent one coherent, working change.

### Testing Philosophy

Test the behaviour, not the implementation. You should be able to rewrite the internals of a function and have the tests still pass, as long as the behaviour is the same.

```typescript
// Bad — tests implementation
test('calls db.query with correct SQL string', () => { ... });

// Good — tests behaviour  
test('returns 404 when artefact does not exist', async () => {
  const res = await app.request('/api/v1/registry/legal/nonexistent');
  expect(res.status).toBe(404);
  const body = await res.json();
  expect(body.error).toBe('NOT_FOUND');
});
```

**What to test:**
- Every error path (404, 401, 403, 422) — these are where bugs live
- Business logic in services (scanner, versioning, signing) — pure functions are easy to test
- The happy path of every endpoint — at minimum

**What not to test obsessively:**
- Framework internals (Hono's routing works — you don't need to test it)
- Third-party libraries
- Trivial getters/setters

### Code Review Checklist (for yourself)

Before every PR or significant commit, ask:

```
[ ] Does this change have tests?
[ ] Have I handled every error case, or am I silently swallowing errors?
[ ] Am I repeating code I've already written somewhere? (extract it)
[ ] Would a new engineer understand what this code does without asking me?
[ ] Am I touching the right layer? (routes in routes, logic in services, queries in db)
[ ] Have I added any secrets or credentials to the code? (must never happen)
```

---

## Part 5 — What to Learn in Parallel

These are skills that will make you a meaningfully better engineer on this project and in general. Work through these alongside the phases above.

### Must Learn (will directly block progress)

**PostgreSQL beyond the basics**  
You know SELECT and INSERT. Now learn: `EXPLAIN ANALYZE` (understand your query performance), `JSONB` operators, transactions and `BEGIN/COMMIT/ROLLBACK`, `ON CONFLICT` (upsert), window functions. Resource: *PostgreSQL official docs* + *pgexercises.com*.

**TypeScript strictly**  
Turn on `strict: true` and keep it there. Learn: discriminated unions, generic constraints, conditional types, the difference between `type` and `interface` and when to use each. Resource: *Total TypeScript* (Matt Pocock's free workshops).

**HTTP deeply**  
You know GET and POST. Now learn: status codes and when each is semantically correct (204 vs 200, 409 vs 422), headers (Cache-Control, ETag, Authorization), CORS and why it exists, idempotency. Resource: *MDN HTTP documentation*, *"HTTP: The Definitive Guide"*.

**Environment and process management**  
12-factor app principles. How environment variables work at the OS level. How `process.env` is populated. Why you should never commit `.env` files. Resource: *12factor.net*.

### Should Learn (will make the project significantly better)

**Redis beyond caching**  
Learn: sorted sets (useful for leaderboards/rankings), pub/sub (needed for cache invalidation across pods), Redis transactions (MULTI/EXEC), key expiry patterns. Resource: *Redis University (free)*.

**Docker and containerisation**  
You will need to containerise the API. Learn: multi-stage builds (keep image size small), non-root user in containers, `.dockerignore`, `docker compose` for local dev, the difference between `CMD` and `ENTRYPOINT`. Resource: *Docker's official getting started guide*.

**Cryptography fundamentals (practical)**  
Not the math — the concepts. Learn: what a hash is and why SHA-256 is not a password hasher, what HMAC adds over a plain hash, why timing attacks exist and how `timingSafeEqual` prevents them, the difference between signing and encrypting. Resource: *Crypto 101* (free ebook, practical focus).

**Observability: Logs, Metrics, Traces**  
Learn structured logging (JSON logs, not console.log strings), what metrics you should emit (request latency, error rate, cache hit rate), and how to use them to debug production issues without being there. Resource: *"Observability Engineering"* (O'Reilly, first few chapters free).

### Good to Learn (longer term growth)

**System design thinking**  
This project is already a system design exercise. Study how other registries and package managers are designed: npm, Docker Hub, GitHub Packages. What problems did they solve? What trade-offs did they make? Read engineering blogs from Cloudflare, GitHub, Stripe — they publish detailed write-ups of real systems.

**Database internals (high level)**  
Why indexes make reads fast but writes slower. What a B-tree index is. What `VACUUM` does in PostgreSQL. Why connection pools exist. You don't need to implement these — you need to know they exist and roughly why. Resource: *"Database Internals"* by Alex Petrov (read chapters 1–4).

**API design**  
Read Stripe's API documentation as if it were a textbook. It is the gold standard of API design. Notice: consistent naming, clear error messages, idempotency keys, pagination patterns. Then read your own API responses and ask if they are as clear.

**Security mindset**  
Learn OWASP Top 10 — not to memorise them, but to understand the class of vulnerability. SQL injection (even though you will use parameterised queries — understand why they protect you), broken authentication, security misconfigurations. Resource: *OWASP Top 10* (free, official site).

---

## Part 6 — Common Mistakes to Avoid

These are things every junior engineer does. You will probably do some of them too. The goal is to catch them quickly.

**Building in the wrong order**  
Do not build the CLI before the API is working. Do not build the SDK before the API is stable. Do not add Redis caching before the core read path works without cache. Downstream tools depend on upstream stability. Go in order.

**Over-engineering early**  
You do not need a plugin system, a GraphQL layer, gRPC, event sourcing, or a message queue for the MVP. These are solutions to problems you do not have yet. When you find yourself thinking "this might be useful later," stop and ask if it solves a problem you have today.

**Silent error swallowing**  
```typescript
// This is the worst thing you can do
try {
  await storageAdapter.put(key, content);
} catch (e) {
  // TODO: handle this
}
```
Either handle the error or let it propagate. A silently failing operation is harder to debug than a crash.

**Inconsistent naming**  
Pick a convention and stick to it. `camelCase` for TypeScript variables and functions. `snake_case` for PostgreSQL columns. `kebab-case` for CLI flags and artefact IDs. If you mix them, the codebase becomes confusing. Define this in a one-page style guide in your repo.

**Not reading error messages**  
Read the entire error message, including the stack trace. Google the specific error code, not just the general concept. The answer is almost always in the first three lines of the error output. Juniors often skim errors — seniors read every word.

**Skipping schema validation**  
Every piece of data entering your system from outside (API request body, YAML file content, environment variables) must be validated before you use it. Use `zod` for this — define a schema, parse the input, handle the error. This one habit prevents an entire class of bugs.

```typescript
import { z } from 'zod';

const PublishRequestSchema = z.object({
  namespace: z.string().min(1).max(64).regex(/^[a-z0-9-]+$/),
  name: z.string().min(1).max(128).regex(/^[a-z0-9-]+$/),
  type: z.enum(['prompt', 'skill', 'chain', 'tool']),
  bumpType: z.enum(['patch', 'minor', 'major']),
  content: z.object({
    system: z.string().optional(),
    user_template: z.string().optional(),
  }).refine(d => d.system || d.user_template, {
    message: 'At least one of system or user_template is required'
  }),
});

// In your route handler:
const result = PublishRequestSchema.safeParse(await req.json());
if (!result.success) {
  return c.json({ error: 'VALIDATION_FAILED', details: result.error.issues }, 400);
}
const data = result.data; // fully typed, guaranteed valid
```

---

## Part 7 — The Weekly Habit Loop

**Every Monday morning (15 minutes):**  
Read the phase checklist. Write down on paper the three things you are building this week. Not ten things — three. If you finish early, pull from the next phase.

**Every day before you start coding (5 minutes):**  
Read the code you wrote yesterday. Does it still make sense? Is there anything you now see that could be cleaner? Refactor before adding new code.

**Every Friday (30 minutes):**  
Write a paragraph summarising what you built, what you learned, and what confused you. Keep this in a `DEVLOG.md` in the repo. In six months, this document will be more valuable to you than any course you could take.

**When you are stuck for more than 45 minutes:**  
Take a break and write down the specific question you are trying to answer. Not "my code doesn't work" — that is not a question. "Why does my route handler return 404 when the record exists in the database?" is a question. Writing the specific question often surfaces the answer, and if it does not, you can Google or ask for help precisely.

---

## Part 8 — First Day Checklist

Do only these things on day one. Nothing else.

```bash
# 1. Create the repo
mkdir ai-registry && cd ai-registry && git init

# 2. Set up the monorepo structure (directories only)
mkdir -p packages/{api,cli,sdk-ts,types}/src
mkdir -p docs scripts

# 3. Create root package.json with workspace config
# 4. Create tsconfig.base.json
# 5. Create docker-compose.dev.yml with postgres + redis
# 6. Start the types package — write Artefact, ArtefactStatus, Variable types only
# 7. Create a minimal Hono server in packages/api that responds to GET /health
# 8. Confirm it starts: bun run dev → curl localhost:3000/health → {"status":"ok"}
# 9. Commit everything: git commit -m "chore: initialise monorepo with types and health endpoint"
# 10. Stop. That is enough for day one.
```

If you do these ten things on day one, you will have a working, committable foundation. That feeling of "it does something real" is important. Build on it.

---

## Appendix — Recommended Tools and Libraries

| Purpose | Library | Why |
|---|---|---|
| HTTP framework | `hono` | Lightweight, runs on Bun natively, excellent TypeScript support |
| Input validation | `zod` | Best-in-class TypeScript-first schema validation |
| Semver | `semver` | Battle-tested, do not write your own semver logic |
| JWT | `jose` | Modern, standards-compliant, works with Web Crypto API |
| Password/key hashing | `argon2` | Correct choice for API key hashing in 2026 |
| Postgres client | `postgres` (porsager) | Lightweight, excellent Bun compatibility |
| Redis client | `ioredis` | Full-featured, well-maintained |
| YAML parsing | `js-yaml` | Most widely used, stable |
| CLI framework | `citty` | Lightweight, built by Nuxt team, works well with Bun |
| CLI output | `kleur` | Tiny, fast, no dependencies |
| CLI prompts | `@inquirer/prompts` | Modern replacement for inquirer.js |
| Testing | Bun's built-in test runner | No extra dependency needed |
| Linting/formatting | `@biomejs/biome` | Single tool for both, much faster than eslint + prettier |
| Schema validation for YAML | `zod` + custom YAML parser | Reuse the same zod schemas for API and file validation |

---

## Final Note

This project is genuinely hard. Not because any single piece is beyond you — every individual piece is learnable. It is hard because you have to hold many moving parts in your head simultaneously and make decisions about them under uncertainty.

The engineers who build good systems are not smarter than you. They have built more bad systems and learned from them. You are about to build your first serious system. It will have rough edges. Parts of it will be embarrassing to look at in a year. That is completely normal and is precisely how it is supposed to go.

Ship the simplest thing that works. Then make it better.

Good luck.