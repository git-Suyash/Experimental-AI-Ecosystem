/** Publish request body (§4.2) */
export interface PublishRequest {
  namespace: string;
  name: string;
  type: ArtefactType;
  bumpType?: BumpType;
  changelog?: string;
  content: ArtefactContent;
  metadata?: ArtefactMetadata;
}

/** Publish response 201 */
export interface PublishResponse {
  id: string;
  version: string;
  status: ArtefactStatus;
  s3Key: string;
  checksum: string;
  signature: string;
  security: {
    passed: boolean;
    riskLevel: string;
    riskScore: number;
    findings: unknown[];
  };
  publishedAt: string | null;
  message?: string;
}

/** List query params */
export interface ListQuery {
  namespace?: string;
  type?: ArtefactType;
  status?: ArtefactStatus;
  tags?: string;
  model?: string;
  framework?: string;
  page?: number;
  limit?: number;
}

/** List response with pagination */
export interface ListResponse<T> {
  items: T[];
  pagination: {
    page: number;
    limit: number;
    total: number;
    pages: number;
  };
}

/** Promote status request */
export interface PromoteStatusRequest {
  status: ArtefactStatus;
  comment?: string;
  successor?: string;
}

/** Search query */
export interface SearchQuery {
  q: string;
  type?: ArtefactType;
  tags?: string;
  model?: string;
  status?: ArtefactStatus;
  semantic?: boolean;
  limit?: number;
}

/** Search result item */
export interface SearchResultItem {
  id: string;
  version: string;
  score?: number;
  type: ArtefactType;
  description?: string;
  tags?: string[];
  highlight?: string;
}

/** Search response */
export interface SearchResponse {
  query: string;
  results: SearchResultItem[];
  total: number;
}
/** Artefact type: prompt | skill | chain | tool */
export type ArtefactType = "prompt" | "skill" | "chain" | "tool";

/** Lifecycle status */
export type ArtefactStatus = "draft" | "review" | "verified" | "deprecated";

/** Version bump type for publish */
export type BumpType = "patch" | "minor" | "major";

/** Severity for scan findings */
export type Severity = "low" | "medium" | "high" | "critical";

/** Variable definition in artefact schema */
export interface Variable {
  name: string;
  type: "string" | "array" | "object" | "enum" | "number" | "boolean";
  required: boolean;
  default?: unknown;
  description?: string;
  values?: string[];
  max_length?: number;
}

/** Compatible model entry */
export interface CompatibleModel {
  model: string;
  min_context?: number;
  recommended?: boolean;
}

/** Content block for prompts */
export interface ArtefactContent {
  system?: string;
  user_template?: string;
  output_schema?: Record<string, unknown>;
}

/** Author info */
export interface Author {
  name?: string;
  email?: string;
  team?: string;
}

/** Security metadata (scan results) */
export interface SecurityMetadata {
  injection_scanned?: boolean;
  scan_date?: string;
  scan_version?: string;
  risk_level?: Severity;
  risk_score?: number;
  findings?: Finding[];
  pii_risk?: string;
  compliance_flags?: string[];
}

/** Single scan finding */
export interface Finding {
  id: string;
  severity: Severity;
  pattern?: string;
  location?: string;
  line?: number;
  message?: string;
}

/** Performance metadata */
export interface PerformanceMetadata {
  avg_input_tokens?: number;
  avg_output_tokens?: number;
  avg_latency_ms?: number;
  p95_latency_ms?: number;
  eval_score?: number;
  last_eval_date?: string;
  estimated_cost_per_call_usd?: number;
}

/** Artefact metadata (discovery, tags, description) */
export interface ArtefactMetadata {
  description?: string;
  tags?: string[];
  use_cases?: string[];
  compatible_models?: CompatibleModel[] | string[];
  compatible_frameworks?: string[];
  required_capabilities?: string[];
}

/** Full registry artefact (canonical schema) */
export interface RegistryArtefact {
  id: string;
  type: ArtefactType;
  version: string;
  status: ArtefactStatus;
  checksum?: string;
  signature?: string;
  author?: Author;
  organisation?: string;
  namespace?: string;
  created_at?: string;
  updated_at?: string;
  published_at?: string;
  changelog?: string;
  tags?: string[];
  description?: string;
  use_cases?: string[];
  compatible_models?: CompatibleModel[];
  compatible_frameworks?: string[];
  required_capabilities?: string[];
  content: ArtefactContent;
  variables?: Variable[];
  security?: SecurityMetadata;
  performance?: PerformanceMetadata;
  deprecation?: {
    reason?: string;
    successor?: string;
    sunset_date?: string;
  };
}
/** User role in org/namespace */
export type UserRole = "viewer" | "contributor" | "reviewer" | "admin";

/** Organisation plan */
export type OrganisationPlan = "free" | "team" | "enterprise";

export interface Organisation {
  id: string;
  slug: string;
  name: string;
  plan?: OrganisationPlan;
  settings?: Record<string, unknown>;
  created_at?: string;
}

export interface Namespace {
  id: string;
  org_id: string;
  slug: string;
  description?: string;
  is_public?: boolean;
  created_at?: string;
}

export interface ApiKeyMeta {
  id: string;
  label: string;
  role: UserRole;
  namespaces?: string[];
  created_at: string;
  expires_at?: string;
}

export interface User {
  id: string;
  org_id: string;
  email: string;
  name?: string;
  role: UserRole;
  api_keys?: ApiKeyMeta[];
  created_at?: string;
  last_seen_at?: string;
}
