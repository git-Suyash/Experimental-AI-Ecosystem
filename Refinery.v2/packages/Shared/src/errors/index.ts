import type { ErrorCode } from "../constants";

export interface ErrorDetail {
  field?: string;
  issue?: string;
  [key: string]: unknown;
}

/** Standard API error envelope (§4.7) */
export class RegistryError extends Error {
  readonly code: ErrorCode;
  readonly requestId?: string;
  readonly details: ErrorDetail[];
  readonly statusCode: number;

  constructor(
    message: string,
    options: {
      code: ErrorCode;
      requestId?: string;
      details?: ErrorDetail[];
      statusCode?: number;
    }
  ) {
    super(message);
    this.name = "RegistryError";
    this.code = options.code;
    this.requestId = options.requestId;
    this.details = options.details ?? [];
    this.statusCode = options.statusCode ?? 500;
    Object.setPrototypeOf(this, RegistryError.prototype);
  }

  toJSON(): {
    error: string;
    message: string;
    requestId?: string;
    details: ErrorDetail[];
  } {
    return {
      error: this.code,
      message: this.message,
      ...(this.requestId && { requestId: this.requestId }),
      details: this.details,
    };
  }
}
