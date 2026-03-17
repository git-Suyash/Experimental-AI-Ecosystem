import { readFileSync, writeFileSync, mkdirSync, existsSync } from "fs";
import { join } from "path";

export interface RefineryConfigData {
  url?: string;
  apiKey?: string;
  defaultNamespace?: string;
  cache?: { enabled?: boolean; ttl?: number; dir?: string };
  verify?: { signatures?: boolean; warnOnDraft?: boolean };
}

const DEFAULT_URL = "http://localhost:3000";
const CONFIG_DIR =
  process.env.REFINERY_CONFIG_PATH ??
  join(process.env.HOME ?? process.env.USERPROFILE ?? "", ".refinery");
const CONFIG_FILE = join(CONFIG_DIR, "config.json");

export class RefineryConfig {
  private data: RefineryConfigData = {};

  constructor() {
    this.load();
  }

  get url(): string {
    return process.env.REFINERY_URL ?? this.data.url ?? DEFAULT_URL;
  }

  get apiKey(): string {
    return process.env.REFINERY_API_KEY ?? this.data.apiKey ?? "";
  }

  get defaultNamespace(): string {
    return process.env.REFINERY_NAMESPACE ?? this.data.defaultNamespace ?? "default";
  }

  get cacheEnabled(): boolean {
    return this.data.cache?.enabled ?? true;
  }

  get cacheTtl(): number {
    return this.data.cache?.ttl ?? 3600;
  }

  get verifySignatures(): boolean {
    return this.data.verify?.signatures ?? true;
  }

  get warnOnDraft(): boolean {
    return this.data.verify?.warnOnDraft ?? true;
  }

  load(): void {
    if (existsSync(CONFIG_FILE)) {
      try {
        const raw = readFileSync(CONFIG_FILE, "utf-8");
        this.data = JSON.parse(raw) as RefineryConfigData;
      } catch {
        this.data = {};
      }
    }
  }

  save(data: Partial<RefineryConfigData>): void {
    this.data = { ...this.data, ...data };
    mkdirSync(CONFIG_DIR, { recursive: true });
    writeFileSync(CONFIG_FILE, JSON.stringify(this.data, null, 2));
  }

  get(key: string): string | undefined {
    const keys = key.split(".");
    let v: unknown = this.data;
    for (const k of keys) {
      v = (v as Record<string, unknown>)?.[k];
    }
    return typeof v === "string" ? v : undefined;
  }

  set(key: string, value: string): void {
    const keys = key.split(".");
    let target: Record<string, unknown> = this.data as Record<string, unknown>;
    for (let i = 0; i < keys.length - 1; i++) {
      const k = keys[i]!;
      if (!(k in target) || typeof target[k] !== "object") {
        target[k] = {};
      }
      target = target[k] as Record<string, unknown>;
    }
    const lastKey = keys[keys.length - 1];
    if (lastKey !== undefined) {
      target[lastKey] = value;
      this.save(this.data);
    }
  }

  list(): RefineryConfigData {
    return { ...this.data };
  }

  static configPath(): string {
    return CONFIG_FILE;
  }
}
