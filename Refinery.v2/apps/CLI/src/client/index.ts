import type {
  PublishRequest,
  PublishResponse,
  ListResponse,
  SearchResponse,
  PromoteStatusRequest,
} from "@refinery/shared";

export interface RegistryClientOptions {
  baseUrl: string;
  apiKey: string;
}

export class RegistryClient {
  constructor(private readonly options: RegistryClientOptions) {}

  private async request<T>(
    method: string,
    path: string,
    body?: unknown,
    query?: Record<string, string>
  ): Promise<T> {
    const url = new URL(path, this.options.baseUrl);
    if (query) {
      for (const [k, v] of Object.entries(query)) {
        if (v !== undefined && v !== "") url.searchParams.set(k, v);
      }
    }
    const headers: Record<string, string> = {
      Authorization: `Bearer ${this.options.apiKey}`,
      "Content-Type": "application/json",
    };
    const res = await fetch(url.toString(), {
      method,
      headers,
      body: body !== undefined ? JSON.stringify(body) : undefined,
    });
    const text = await res.text();
    if (!res.ok) {
      let errBody: { error?: string; message?: string } = {};
      try {
        errBody = JSON.parse(text) as { error?: string; message?: string };
      } catch {
        errBody = { message: text };
      }
      throw new Error(errBody.message ?? errBody.error ?? `HTTP ${res.status}`);
    }
    if (!text) return {} as T;
    return JSON.parse(text) as T;
  }

  async publish(req: PublishRequest): Promise<PublishResponse> {
    return this.request<PublishResponse>("POST", "/api/v1/registry/publish", req);
  }

  async get(namespace: string, name: string, version?: string, contentOnly?: boolean): Promise<unknown> {
    const path = `/api/v1/registry/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}`;
    const query: Record<string, string> = {};
    if (version) query.version = version;
    if (contentOnly) query.contentOnly = "true";
    return this.request("GET", path, undefined, query);
  }

  async list(params?: {
    namespace?: string;
    type?: string;
    status?: string;
    page?: number;
    limit?: number;
  }): Promise<ListResponse<unknown>> {
    const query: Record<string, string> = {};
    if (params?.namespace) query.namespace = params.namespace;
    if (params?.type) query.type = params.type;
    if (params?.status) query.status = params.status;
    if (params?.page) query.page = String(params.page);
    if (params?.limit) query.limit = String(params.limit);
    return this.request("GET", "/api/v1/registry", undefined, query);
  }

  async search(
    q: string,
    params?: { type?: string; tags?: string; limit?: number }
  ): Promise<SearchResponse> {
    const query: Record<string, string> = { q };
    if (params?.type) query.type = params.type;
    if (params?.tags) query.tags = params.tags;
    if (params?.limit) query.limit = String(params.limit);
    return this.request("GET", "/api/v1/registry/search", undefined, query);
  }

  async promote(
    namespace: string,
    name: string,
    version: string,
    body: PromoteStatusRequest
  ): Promise<{ id: string; version: string; status: string }> {
    const path = `/api/v1/registry/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}/status`;
    return this.request("PATCH", path, body, { version });
  }

  async diff(
    namespace: string,
    name: string,
    from: string,
    to: string
  ): Promise<{ from: string; to: string; bumpType: string; changes: unknown }> {
    const path = `/api/v1/registry/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}/diff`;
    return this.request("GET", path, undefined, { from, to });
  }

  async history(namespace: string, name: string): Promise<{ id: string; versions: unknown[] }> {
    const path = `/api/v1/registry/${encodeURIComponent(namespace)}/${encodeURIComponent(name)}/history`;
    return this.request("GET", path);
  }
}
