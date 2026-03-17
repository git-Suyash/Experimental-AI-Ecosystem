import type { Finding, Severity } from "../types";

export interface ScanResult {
  passed: boolean;
  severity: Severity;
  findings: Finding[];
  score: number; // 0–100, higher is safer
  scanVersion: string;
  scannedAt: string; // ISO 8601
}


/** Storage adapter interface - swappable S3, GCS, Azure, LocalFs */
export interface StorageAdapter {
  put(
    key: string,
    content: Buffer | string,
    metadata?: Record<string, string>
  ): Promise<void>;

  get(key: string): Promise<Buffer>;

  delete(key: string): Promise<void>;

  list(prefix: string): Promise<string[]>;

  getSignedUrl(key: string, expiresIn: number): Promise<string>;
}
