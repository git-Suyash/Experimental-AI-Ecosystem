# Company-Wide AI Enablement Framework
### Secure · Compliant · Adoptable · Innovation-Forward

**Version:** 1.0  
**Applies To:** All employees — Intern through CTO  
**Scope:** Software service companies and product companies  
**Standards Alignment:** ISO 27001, ISO 27017, ISO 27018, ISO 42001, SOC 2 Type II, GDPR, EU AI Act, NIST AI RMF

---

## Table of Contents

1. [Philosophy & Design Principles](#1-philosophy--design-principles)
2. [Threat Model: Why This Framework Exists](#2-threat-model-why-this-framework-exists)
3. [The Five Tiers of AI Enablement](#3-the-five-tiers-of-ai-enablement)
4. [The Central AI Registry (CAIR)](#4-the-central-ai-registry-cair)
5. [The AI Development Lifecycle (AI-DLC)](#5-the-ai-development-lifecycle-ai-dlc)
6. [Role-Based Access & Responsibility Matrix](#6-role-based-access--responsibility-matrix)
7. [Compliance Guardrails by Work Type](#7-compliance-guardrails-by-work-type)
8. [Security Controls for Agentic Development](#8-security-controls-for-agentic-development)
9. [Logging, Audit & Observability](#9-logging-audit--observability)
10. [Cost Governance](#10-cost-governance)
11. [Adoption Roadmap (90-Day Rollout)](#11-adoption-roadmap-90-day-rollout)
12. [Tooling Reference Architecture](#12-tooling-reference-architecture)
13. [Incident Response for AI-Related Events](#13-incident-response-for-ai-related-events)
14. [Appendix: Glossary](#14-appendix-glossary)

---

## 1. Philosophy & Design Principles

This framework treats AI as a **first-class engineering colleague** — one that is extraordinarily capable but requires structured oversight, clear boundaries, and institutional memory. It is not a set of restrictions. It is the scaffolding that lets the company move *fast with confidence*.

### Core Principles

**1. Human Accountability is Non-Negotiable**  
Every line of AI-generated code that reaches production has a named human owner who is accountable for it. AI proposes; humans decide.

**2. Security is a First-Class Citizen, Not an Afterthought**  
Security gates are mandatory for AI-generated artifacts — not optional add-ons. The default posture is: treat AI output like untrusted third-party input until it passes automated and human review.

**3. Compliance Context is Injected at the Source**  
AI tools are not allowed to operate in a compliance vacuum. Tools are configured with awareness of the regulatory environment (GDPR, SOC 2, HIPAA, etc.) relevant to each project.

**4. Centralized Trust, Decentralized Execution**  
Prompts, agents, skills, and MCP configurations are managed centrally and auditably, but developers work freely within approved tooling. Innovation happens inside guardrails, not outside them.

**5. Cost is a System Design Constraint**  
AI spend is tracked at team, project, and individual levels. Runaway usage is flagged proactively — not discovered at month-end billing.

**6. Adoption Over Perfection**  
A framework that nobody follows provides zero security. This framework is designed to have near-zero friction for compliant behavior and meaningful friction for non-compliant behavior.

---

## 2. Threat Model: Why This Framework Exists

Before prescribing solutions, understand the risks that agentic AI development introduces:

### Risk Surface Map

| Threat | Description | Compliance Impact |
|--------|-------------|-------------------|
| **AI-Introduced Vulnerabilities** | LLMs produce insecure code ~45% of the time in unconstrained settings (Veracode 2025) | ISO 27001 A.8, SOC 2 CC6 |
| **PII/Secret Leakage via Prompt** | Developers inadvertently paste client data, secrets, or credentials into AI prompts that leave the organizational boundary | ISO 27018, GDPR Art. 32, SOC 2 C1 |
| **Prompt Injection via Codebase** | Malicious content in codebases, documents, or third-party code manipulates AI agents into executing unintended actions | ISO 27001 A.12, NIST AI RMF |
| **Accountability Diffusion** | AI-written code has no clear author; reviewers assume AI checked for security; nobody actually did | SOC 2 CC1, ISO 27001 A.7 |
| **Shadow AI Usage** | Developers use unapproved AI tools that exfiltrate data to unknown third parties | ISO 27017, GDPR Art. 28 |
| **Agent Overprivilege** | Coding agents given broad permissions (repo access, cloud credentials, DB access) cause blast radius explosion on compromise | ISO 27001 A.9, SOC 2 CC6 |
| **Compliance Drift** | AI writes code without knowledge of project-specific compliance requirements, accumulating technical debt silently | GDPR Art. 25, ISO 27018 |
| **Model/Tool Supply Chain Risk** | Using unvetted LLMs or MCPs that may contain backdoors or exfiltrate prompts | ISO 27036, NIST AI RMF |
| **Audit Trail Gaps** | No record of what was prompted, generated, reviewed, or deployed — making incident investigation impossible | SOC 2 CC7, ISO 27001 A.12 |

---

## 3. The Five Tiers of AI Enablement

Rather than a binary "allowed/not allowed," this framework operates on five tiers. Every tool, agent, and workflow is classified into a tier. Access is role-gated.

### Tier Definitions

```
┌─────────────────────────────────────────────────────────────────┐
│  TIER 5 │ Autonomous Agents (Multi-step, Production Access)     │
│         │ Requires: VP/CTO approval + full audit logging        │
├─────────┼───────────────────────────────────────────────────────┤
│  TIER 4 │ Semi-Autonomous Agents (Repo + Staging Access)        │
│         │ Requires: Tech Lead approval + PR review mandatory    │
├─────────┼───────────────────────────────────────────────────────┤
│  TIER 3 │ AI-Assisted Development (IDE, Code Gen, Test Gen)     │
│         │ Requires: Team-approved tooling from registry         │
├─────────┼───────────────────────────────────────────────────────┤
│  TIER 2 │ AI Research & Exploration (Internal, Sandboxed)       │
│         │ Requires: Use of company-provisioned sandboxes only   │
├─────────┼───────────────────────────────────────────────────────┤
│  TIER 1 │ AI Productivity Tools (Chat, Summarization, Drafting) │
│         │ Requires: Approved tool list + data classification    │
└─────────┴───────────────────────────────────────────────────────┘
```

### Tier Escalation Policy

- Tier 3 access is default for all developers upon onboarding + completion of the AI Security Baseline training.
- Tier 4 requires a written justification, team lead sign-off, and project-specific compliance review.
- Tier 5 is reserved for platform engineering teams building internal AI pipelines, with mandatory dual approval.
- Escalation requests are logged in the Central AI Registry (see Section 4).

---

## 4. The Central AI Registry (CAIR)

The Central AI Registry is the operational heart of this framework. It is a governed, version-controlled, internally-hosted catalog of everything AI-related that the company uses or produces.

### What CAIR Contains

#### 4.1 Approved Tool Registry

A curated list of AI tools approved for use at each tier:

| Category | Examples | Tier | Data Classification Allowed |
|----------|----------|------|-----------------------------|
| IDE Assistants | GitHub Copilot (Enterprise), Cursor (Enterprise), Amazon Q Developer | 3 | Internal, Client (with DPA) |
| Coding Agents | Claude Code, Devin (sandboxed), Amazon Q Agent | 4 | Internal only by default |
| Chat/Research | Claude.ai (Team), ChatGPT Enterprise | 1-2 | Public, Internal |
| Autonomous Pipelines | Internal-hosted agents (see Section 8) | 5 | Internal, Client (with approval) |
| MCP Servers | Registry-approved MCPs only (see 4.4) | 3-5 | Varies by MCP |

Any tool not in this registry is **not approved**. Developers found using unapproved tools on client or production work are subject to the Shadow AI Policy (see Section 8.5).

#### 4.2 Prompt Library

A versioned, searchable library of approved prompts organized by use case:

```
/prompts
  /code-generation
    secure-api-endpoint.md          # Includes OWASP Top 10 context
    database-query-builder.md       # SQL injection prevention guidance baked in
    auth-flow.md                    # OAuth/JWT secure pattern prompts
  /code-review
    security-review.md              # Prompts for AI-assisted security review
    gdpr-data-handling-review.md    # Privacy-aware review prompts
  /testing
    unit-test-generator.md
    penetration-test-scenarios.md
  /documentation
    api-docs.md
    runbook.md
  /client-work
    requirement-analysis.md         # Sanitized — no client names in prompts
    architecture-review.md
```

Each prompt in the library has:
- **Owner** (team or individual who authored it)
- **Compliance Tags** (e.g., `gdpr-safe`, `no-pii`, `soc2-aware`)
- **Tested Against** (which models, which contexts)
- **Version History**
- **Usage Count** (helps identify high-leverage prompts)

Prompts are submitted via PR to the CAIR repository. The Security team reviews prompts before they are published. This process takes a maximum of 2 business days for standard prompts.

#### 4.3 Agent & Skill Registry

For agentic workflows (Tier 4 and 5), all agents and skills must be registered before deployment:

**Registration requires:**
- Agent name, owner, and purpose
- Complete list of tools/permissions the agent requires (principle of least privilege review)
- Data classification of inputs/outputs
- Compliance context the agent operates in
- Rollback procedure
- Human escalation trigger conditions

**Stripe's "Minions" Pattern Implemented Safely:**  
Stripe's model of one-shot end-to-end coding agents producing 1000+ PRs per week is achievable, but only within a controlled pipeline:

```
Approved Task Template → CAIR Validation → Agent Execution (Sandboxed)
→ Automated Security Scan → Human PR Review → Merge Gate → Audit Log
```

Agents never skip the human PR review step, regardless of velocity targets.

#### 4.4 MCP (Model Context Protocol) Server Registry

MCPs are treated with the same rigor as third-party software libraries. Before any MCP is usable by developers:

- Source code or vendor security documentation must be reviewed
- Data flow must be documented (what data leaves the organization and where it goes)
- A Data Processing Agreement (DPA) must be in place if the MCP transmits client data
- The MCP must be hosted or proxied through company infrastructure for Tier 4+ work
- MCP usage is logged via a centralized proxy (not direct developer-to-MCP connections)

```
Developer Workstation
       │
       ▼
  AI Gateway Proxy (CAIR-managed)
  ├── Logging & Rate Limiting
  ├── PII Detection & Redaction
  ├── Compliance Context Injection
  └── Tool Authorization Check
       │
       ▼
  Approved MCP Server (GitHub, Jira, etc.)
```

#### 4.5 AI-SBOM (Software Bill of Materials for AI)

Every repository maintains an `AI-SBOM.json` at the root that is automatically updated by the CI/CD pipeline:

```json
{
  "repository": "payments-service",
  "generated_at": "2026-03-15T09:00:00Z",
  "ai_usage": {
    "tool": "GitHub Copilot Enterprise",
    "tier": 3,
    "percentage_ai_assisted_lines": 34,
    "human_reviewed": true,
    "prompts_referenced": ["secure-api-endpoint", "unit-test-generator"],
    "security_scans_passed": ["semgrep", "snyk", "gitleaks"],
    "reviewer": "jane.doe@company.com",
    "compliance_context": ["soc2", "pci-dss"]
  }
}
```

This file is required for SOC 2 audit evidence packages and GDPR accountability documentation.

---

## 5. The AI Development Lifecycle (AI-DLC)

Inspired by AWS's AI-DLC methodology, Stripe's agentic pipeline model, and StrongDM's software factory approach, this section defines how AI is embedded into each phase of development — with human checkpoints that are non-negotiable.

### Phase 0: Project Kickoff — Compliance Context Setup

Before any AI tool is used on a project, a **Compliance Context File** (`.ai-context.yaml`) must be created in the repository root:

```yaml
project: payments-portal
client: "Acme Corp"
compliance:
  - soc2-type2
  - pci-dss-4
  - gdpr
data_classification: confidential
ai_tier_allowed: 3          # Max tier allowed on this project
pii_present: true
secret_scanning: mandatory
approved_ai_tools:
  - github-copilot-enterprise
  - claude-code-sandboxed
prompt_context_tags:
  - no-pii-in-prompts
  - gdpr-privacy-by-design
  - owasp-top10-awareness
human_review_required: true
audit_log_destination: "splunk://audit.internal/ai-events"
```

This file is read by the AI Gateway Proxy and injected as system context into every AI interaction on that project. Developers do not need to manually specify compliance context in every prompt — it is automatic.

### Phase 1: Inception (Requirements & Architecture)

**Human-Led, AI-Accelerated**

Recommended workflow (adapted from AWS AI-DLC "Mob Elaboration"):

1. Product Owner / Tech Lead writes initial intent in plain language
2. AI generates structured requirements, user stories, and acceptance criteria from intent
3. Team reviews AI output in a collaborative session (maximum 2 hours)
4. Humans make all final decisions on scope, architecture, and risk acceptance
5. Architecture Decision Records (ADRs) are human-authored, even if AI-drafted initially

**What AI can do in this phase:**
- Draft requirements from briefs
- Identify missing edge cases and non-functional requirements
- Suggest architectural patterns based on stated constraints
- Generate initial threat model from requirements

**What AI cannot do in this phase:**
- Make final decisions on data handling architecture
- Sign off on third-party integrations involving client data
- Select security patterns for regulated data (human architect must approve)

### Phase 2: Construction (Code, Tests, Documentation)

**AI-Primary, Human-Gated**

This is where most AI productivity gains occur. The following workflow applies:

```
1. Developer creates a task from the approved backlog
2. Developer selects from approved prompt templates in CAIR (or writes a new one)
3. AI generates code within the IDE assistant (Tier 3) or via a coding agent (Tier 4)
4. Automated gate runs immediately on generated code:
   ├── SAST scan (Semgrep / Snyk Code)
   ├── Secret detection (GitLeaks / TruffleHog)
   ├── Dependency vulnerability check (Snyk / OWASP Dependency-Check)
   ├── PII pattern detection (custom regex + AI classifier)
   └── Compliance lint (custom rules per .ai-context.yaml)
5. If any gate fails → developer must resolve before committing
6. PR opened → mandatory human code review (see Section 6)
7. Second automated scan on full PR diff
8. Human reviewer approves → merge allowed
9. AI-SBOM updated automatically by CI
```

**The "No Rubber Stamp" Rule:**  
Reviewers must attest: *"I have read and understood this code"* — not *"I have reviewed the AI summary of this code."* AI-generated PR summaries are allowed as aids, but the reviewer is accountable for the actual code.

### Phase 3: Operations (Deploy, Monitor, Maintain)

**Human-Authorized, AI-Assisted**

- Infrastructure as Code (IaC) changes generated by AI require a human architect or senior developer sign-off before deployment to any non-sandbox environment.
- AI-generated runbooks must be reviewed by the on-call engineer before they are promoted to the incident response playbook.
- AI may generate alerts, anomaly reports, and suggested remediations in production — but all remediative actions require human authorization (no autonomous production changes).
- Deployment pipelines log AI involvement in the deployment artifact metadata.

### Phase 4: Continuous Improvement

- AI-generated code is tracked over time for vulnerability recurrence patterns.
- Monthly AI Quality Report: aggregate view of how much AI-generated code passed/failed security gates, per team and per project.
- CAIR prompt library is updated based on recurring failure patterns (if certain prompts produce insecure patterns, they are revised or retired).

---

## 6. Role-Based Access & Responsibility Matrix

### Who Can Do What

| Role | Tier Access | Can Use Agents | Can Submit Prompts to CAIR | Code Review Required | Can Approve AI-IaC |
|------|-------------|----------------|---------------------------|---------------------|---------------------|
| Intern | 1, 2 | No | No (can suggest) | Yes, by buddy | No |
| Junior Developer | 1, 2, 3 | No | Yes (pending review) | Yes | No |
| Developer | 1, 2, 3 | Tier 4 with TL approval | Yes | Yes | No |
| Senior Developer | 1–4 | Yes (Tier 4) | Yes | Yes + can review AI PRs | Staging only |
| Tech Lead | 1–4 | Yes (Tier 4) | Yes (self-approve minor) | Yes + final authority on team PRs | Staging only |
| Staff / Principal Engineer | 1–5 | Yes | Yes (self-approve) | Yes + platform decisions | Yes (staging + prod) |
| Architect | 1–5 | Yes | Yes (self-approve) | Final approval on architecture PRs | Yes |
| Engineering Manager | 1–3 | No | No | Code review not required | No |
| CTO / VP Eng | 1–5 | Yes | Yes | Framework governance | Yes (all environments) |
| Security Team | 1–5 | Yes (security scope) | Yes (security prompts) | Security gate owners | Yes (review only) |

### Responsibility at Each Level

**Every developer (intern to senior) is responsible for:**
- Not pasting client PII, secrets, or confidential data into AI prompts
- Ensuring AI-generated code passes all automated gates before submitting for review
- Accurately completing the AI-SBOM fields for their commits
- Completing annual AI Security Training

**Tech Leads are responsible for:**
- Setting the `.ai-context.yaml` for projects they own
- Reviewing and approving team members' Tier 4 agent usage requests
- Monitoring the AI Quality Report for their team monthly

**The Security Team is responsible for:**
- Maintaining and updating the approved tool registry
- Running quarterly red-team exercises against AI pipelines
- Reviewing MCP additions to the registry
- Maintaining the PII detection and compliance lint rules

**The CTO/VP of Engineering is responsible for:**
- Approving Tier 5 agent deployments
- Quarterly review of AI compliance posture for client accounts
- AI strategy alignment with legal and procurement

---

## 7. Compliance Guardrails by Work Type

### 7.1 Client Work (Service Companies)

Client work carries the highest compliance burden, as client data, client infrastructure, and contractual obligations are involved.

**Mandatory controls:**
- `.ai-context.yaml` must reflect the client's compliance requirements (not just your company's)
- AI tools must only be used with clients who have been disclosed in the Master Services Agreement (MSA) or SOW via an AI Usage Disclosure clause
- No client PII, schema names, database structures, or API keys may appear in prompts sent to external LLM providers
- For clients under GDPR: AI tools that process client data must have a sub-processor DPA in place
- For clients under SOC 2: All AI tool usage on their project is logged and available for their audit requests
- Client data must never be used to fine-tune or improve external AI models (verify via vendor DPA that training opt-out is active)

**Recommended MSA Language Addition:**
> *"[Company] may utilize AI-assisted development tools in the delivery of Services. Such tools are selected from a pre-approved registry subject to security and compliance review. No Client Confidential Information is transmitted to external AI providers without prior written consent and a valid Data Processing Agreement. All AI-generated deliverables are reviewed and accepted by qualified human personnel before delivery."*

### 7.2 Internal Product Development

For companies building their own products, the controls are the same in principle but with more flexibility in implementation speed:

- Internal projects may use Tier 4 agents with standard Tech Lead approval
- Staging and development environments may use AI-generated IaC with senior engineer sign-off
- The AI-SBOM is still required for all repositories — it forms part of your internal compliance posture for SOC 2 audits
- GDPR still applies to any user data in your product; privacy-by-design prompts must be active

### 7.3 Research & Experimentation (R&D Projects)

- R&D work may use Tier 2 sandboxes with synthetic or fully anonymized data only
- No real user data, client data, or production secrets in R&D environments
- R&D agents may operate with more autonomy but must be isolated from production networks
- Findings from R&D (e.g., new agent architectures, new prompts) should be contributed to CAIR after internal review

---

## 8. Security Controls for Agentic Development

### 8.1 Least Privilege for AI Agents

Every agent in the registry must have a documented, minimal permission set:

```yaml
agent: code-refactor-agent
permissions:
  repo_access: read-write (feature branches only)
  production_access: NONE
  database_access: NONE
  secrets_access: NONE
  external_network: NONE (sandbox only)
  can_create_prs: true
  can_merge_prs: false        # Human merges only
  can_deploy: false
  can_modify_iac: false
```

Agents must not be granted permissions they haven't justified. The Security team reviews agent permission requests within 3 business days.

### 8.2 Sandboxed Execution Environments

All Tier 4 and Tier 5 agents execute in isolated environments:

- Dedicated virtual network with no production access
- Ephemeral containers that are destroyed after each task
- Time-limited execution (configurable max runtime per task)
- Network egress logging — all external calls from agents are logged
- No persistent credential storage in agent environments; credentials are injected via secrets manager at runtime and scoped to the task

### 8.3 Prompt Injection Defense

Prompt injection — where malicious content in data manipulates AI agents — is treated as a first-class security threat:

- Agents that process external content (files, web pages, emails, tickets) must run through a sanitization layer before content reaches the LLM
- Agent instructions and user/data inputs are structurally separated (system prompt vs. user message are never concatenated as plain strings)
- Agents operating on codebases should treat any comment, docstring, or string literal as potentially adversarial
- Red team exercises include prompt injection scenarios quarterly

### 8.4 AI Gateway Proxy (Technical Architecture)

All AI API calls from developer tooling and agents route through an internally managed proxy:

```
┌──────────────────────────────────────────────┐
│             AI GATEWAY PROXY                 │
│                                              │
│  ┌────────────────────────────────────────┐  │
│  │ Request Pipeline                        │  │
│  │  1. Authentication (SSO-linked token)  │  │
│  │  2. Tool Authorization Check (CAIR)    │  │
│  │  3. PII Scan & Redaction               │  │
│  │  4. Secret Detection                   │  │
│  │  5. Compliance Context Injection       │  │
│  │  6. Rate Limit Enforcement             │  │
│  │  7. Cost Tracking (project/user tag)   │  │
│  └────────────────────────────────────────┘  │
│                     │                        │
│                     ▼                        │
│  ┌────────────────────────────────────────┐  │
│  │ Response Pipeline                      │  │
│  │  1. Output Scanning (malicious code)   │  │
│  │  2. PII in Response Detection          │  │
│  │  3. Audit Log (full prompt + response) │  │
│  │  4. Token Usage Recording              │  │
│  └────────────────────────────────────────┘  │
└──────────────────────────────────────────────┘
```

Recommended open-source starting point: LiteLLM Proxy (self-hosted) or an internal API gateway (Kong, AWS API Gateway) with custom middleware.

### 8.5 Shadow AI Policy

**Detection:**
- Endpoint DLP policies flag uploads to known AI provider domains (openai.com, anthropic.com, etc.) from unregistered tools
- Network monitoring alerts on unusual API traffic patterns to AI endpoints
- Quarterly developer surveys + awareness campaigns

**Response:**
- First occurrence: Conversation with team lead, mandatory re-training, documented
- Second occurrence: Formal warning, temporary AI access revocation, security review of any output produced
- Third occurrence or intentional data exfiltration: Disciplinary procedure per HR policy

The goal is education, not punishment. The friction of compliant tools must be lower than the friction of workarounds. If developers are seeking shadow tools, it is a signal that the approved tooling is inadequate — which must be investigated.

---

## 9. Logging, Audit & Observability

### What Must Be Logged

Every AI interaction that touches company or client work must produce a log entry containing:

| Field | Description | Retention |
|-------|-------------|-----------|
| `timestamp` | ISO 8601 | 2 years |
| `user_id` | SSO-linked identifier | 2 years |
| `project_id` | From .ai-context.yaml | 2 years |
| `tool_name` | From approved registry | 2 years |
| `tier` | 1–5 | 2 years |
| `prompt_hash` | SHA-256 of prompt (not plaintext for privacy) | 2 years |
| `prompt_template_ref` | CAIR prompt library reference, if used | 2 years |
| `tokens_used` | Input + output | 2 years |
| `security_gates_passed` | Boolean list | 2 years |
| `reviewer_id` | Who reviewed the output | 2 years |
| `compliance_tags` | From project context | 7 years |
| `pii_detected` | Boolean + category if true | 7 years |
| `merged_to_production` | Boolean | 7 years |

> **Note on privacy:** Full prompt content is NOT stored in standard logs to protect developer privacy and avoid storing client data in logs. Only the hash is stored. Full prompt content may be stored in a separate, higher-classification store for Tier 5 agents and only accessible to Security with documented cause.

### Audit Dashboards

The following dashboards must be maintained and reviewed on the specified cadences:

- **Daily:** Active agent task counts, failed security gate counts, anomalous API usage
- **Weekly:** AI-generated code merge rate, top failing compliance rules, cost by project
- **Monthly:** AI Quality Report (per team), prompt library usage, new shadow AI detection events
- **Quarterly:** Full AI compliance posture review, AI tool vendor review, red team report

---

## 10. Cost Governance

AI tooling costs can scale non-linearly with agentic usage. The following controls prevent bill shock while ensuring developers are not throttled on legitimate work.

### Budget Architecture

```
Company AI Budget (Set by Finance + CTO quarterly)
        │
        ├── Internal Projects Pool (e.g., 30%)
        │       └── Per-project monthly cap set by Engineering Manager
        │
        ├── Client Projects Pool (e.g., 60%)
        │       └── Per-client monthly cap (typically passed through with markup)
        │
        └── R&D / Innovation Pool (e.g., 10%)
                └── Applied for by team leads, time-boxed
```

### Cost Controls

- Each developer has a soft monthly limit (configurable by role). Soft limit = alert, not block.
- Projects have a hard monthly cap. At 80% of cap, the Tech Lead is alerted. At 100%, new AI requests require Tech Lead approval.
- Agents operating in batch mode (Tier 4/5) have per-run token budgets defined in their registry entry.
- All token usage is tagged with `project_id` and `user_id` at the AI Gateway Proxy layer — no untagged spend.
- Wasteful patterns (e.g., agents in infinite loops, excessively large context windows for simple tasks) trigger automated alerts.

### Cost Optimization Guidance (Non-Mandatory)

- Use the prompt library: well-engineered prompts consume fewer tokens for better output.
- For repetitive batch tasks, use smaller models where quality is sufficient (validated by the team).
- Cache AI responses for identical tasks within the same sprint (especially useful for documentation generation).
- Agentic tasks should define a maximum step count to prevent runaway loops.

---

## 11. Adoption Roadmap (90-Day Rollout)

This roadmap is designed for a company of 50–500 engineers. Adjust timescale for smaller or larger teams.

### Days 1–30: Foundation

**Week 1–2: Infrastructure**
- Deploy AI Gateway Proxy (LiteLLM or equivalent)
- Set up CAIR as a Git repository (internal GitLab/GitHub)
- Establish SSO-linked API key issuance for approved AI tools
- Configure basic logging pipeline to SIEM

**Week 3–4: Policy & People**
- Publish this framework (v1.0) internally
- Run a 60-minute "AI Enablement 101" session for all developers — not a lecture, a live demo of approved tooling with the framework explained
- Identify AI Champions in each team (typically enthusiastic senior devs) — they become the first-line adoption support
- Tech Leads complete `.ai-context.yaml` setup for all active projects

**Deliverable:** All developers have Tier 1–3 access. All projects have a compliance context file.

### Days 31–60: Workflows & Tooling

**Week 5–6: Security Gates**
- Integrate SAST, secret detection, and PII scanning into CI/CD for all repositories
- Seed CAIR prompt library with 20–30 high-value prompts (cover the most common coding tasks)
- Deploy AI-SBOM automation in CI pipeline

**Week 7–8: Agent Onboarding**
- Onboard first Tier 4 agents (start with 2–3 well-understood use cases: test generation, PR description, documentation)
- Run tabletop exercise: simulate a prompt injection attack and an accidental PII disclosure — test detection and response
- First AI Quality Report published to all teams

**Deliverable:** Tier 4 agents available for approved use cases. Security gates active in all repos.

### Days 61–90: Scale & Optimize

**Week 9–10: Client Work Integration**
- Add AI Usage Disclosure language to MSA template (with Legal)
- Complete DPA review for all Tier 3+ tools used on client projects
- Publish client-specific `.ai-context.yaml` templates for the most common regulatory environments (SOC 2, GDPR, HIPAA)

**Week 11–12: Continuous Improvement Loop**
- Hold first retrospective with AI Champions: what's working, what's friction
- Refine prompt library based on first 60 days of usage data
- Publish v1.1 of this framework based on learnings

**Deliverable:** Full framework operational. First internal compliance audit completed.

---

## 12. Tooling Reference Architecture

A recommended (not mandated) tooling stack. Companies may substitute equivalent tools.

### Category → Recommended Tool → Open Source Alternative

| Category | Commercial | Open Source / Self-Hosted |
|----------|------------|---------------------------|
| AI Gateway Proxy | AWS Bedrock Gateway, Azure AI Gateway | LiteLLM Proxy, Portkey |
| IDE AI Assistant | GitHub Copilot Enterprise, Cursor Business | Continue.dev (self-hosted) |
| Coding Agents | Claude Code, Amazon Q Developer Agent | SWE-agent (self-hosted) |
| CAIR Registry | Internal GitLab / GitHub repo | Gitea (self-hosted) |
| SAST | Snyk Code, Checkmarx | Semgrep (OSS), SonarQube CE |
| Secret Detection | GitGuardian, Nightfall | TruffleHog, GitLeaks |
| Dependency Scanning | Snyk, Black Duck | OWASP Dependency-Check |
| PII Detection | Nightfall, Presidio (Microsoft) | Presidio (OSS), scrubadub |
| Logging / SIEM | Splunk, Datadog | ELK Stack, Grafana + Loki |
| Cost Tracking | Vantage, CloudZero | LiteLLM Proxy (built-in) |
| AI-SBOM | Anchore, custom CI step | Custom CI step (see templates) |

---

## 13. Incident Response for AI-Related Events

### Incident Classification

| Type | Example | Severity | Response Time |
|------|---------|----------|---------------|
| **AI-P1** | Client PII sent to external LLM | Critical | 1 hour |
| **AI-P1** | AI agent gained unauthorized production access | Critical | 1 hour |
| **AI-P2** | Security vulnerability in merged AI-generated code | High | 4 hours |
| **AI-P2** | Secret/credential in AI prompt log | High | 4 hours |
| **AI-P3** | Shadow AI tool used on client work | Medium | 24 hours |
| **AI-P3** | AI agent loop causing unexpected API spend | Medium | 24 hours |
| **AI-P4** | Prompt library entry flagged as insecure | Low | 72 hours |

### Response Steps for AI-P1 (Data Exfiltration)

1. **Contain (0–30 min):** Revoke the API key used. Disable the tool involved in the AI Gateway Proxy. Preserve all logs.
2. **Assess (30–60 min):** Determine what data was transmitted. Identify the prompt(s) involved via log hash. Identify the LLM provider's data retention policy.
3. **Notify (1–4 hours):** If client data involved → notify client within contractual SLA. If GDPR applies → initiate 72-hour breach notification clock. Engage legal and DPO.
4. **Remediate (4–24 hours):** Contact LLM vendor to request data deletion (per their DPA). Document the root cause. Patch the workflow.
5. **Post-Incident (72 hours):** Root cause analysis published internally. Framework updated to prevent recurrence.

---

## 14. Appendix: Glossary

| Term | Definition |
|------|------------|
| **CAIR** | Central AI Registry — the company's governed catalog of AI tools, prompts, agents, and MCPs |
| **AI-SBOM** | AI Software Bill of Materials — a manifest declaring AI involvement in a codebase, analogous to a software SBOM |
| **AI Gateway Proxy** | An internally managed reverse proxy that intercepts all AI API calls for logging, scanning, and compliance injection |
| **Compliance Context File** | `.ai-context.yaml` — a per-project file that injects regulatory and data handling context into all AI interactions on that project |
| **MCP** | Model Context Protocol — a standard for connecting AI agents to external tools and data sources |
| **Prompt Injection** | An attack where malicious content in data manipulates an AI agent into performing unintended actions |
| **Shadow AI** | Use of AI tools not listed in the approved registry, typically by developers seeking more capable or less friction tools |
| **Tier** | One of five access levels (1–5) classifying the autonomy and risk level of AI tool usage |
| **AI-DLC** | AI-Driven Development Lifecycle — the structured methodology for embedding AI into each phase of software development with human checkpoints |
| **Mob Elaboration** | A collaborative session where a full team reviews and validates AI-generated requirements or architecture proposals |
| **AI Champion** | A team-level advocate and first-line support person for AI tooling adoption and compliance questions |
| **Least Privilege (AI)** | The principle that AI agents are granted only the minimum permissions required to complete their defined tasks |
| **AI Quality Report** | Monthly report tracking the security and quality metrics of AI-generated code across teams |

---

*This framework is a living document. Suggested revisions should be submitted to the Engineering Leadership group via the internal CAIR repository. All changes are version-controlled and communicated to the company before taking effect.*

*Last Reviewed: March 2026 | Next Review: June 2026 | Owner: CTO Office + Security Team*
