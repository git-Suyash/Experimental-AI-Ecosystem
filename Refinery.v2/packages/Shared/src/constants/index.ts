/** Standard API error codes (see §4.7) */
export const ErrorCodes = {
  VALIDATION_FAILED: "VALIDATION_FAILED",
  UNAUTHORIZED: "UNAUTHORIZED",
  FORBIDDEN: "FORBIDDEN",
  NOT_FOUND: "NOT_FOUND",
  VERSION_EXISTS: "VERSION_EXISTS",
  DEPRECATED: "DEPRECATED",
  SECURITY_SCAN_FAILED: "SECURITY_SCAN_FAILED",
  TEST_CASES_FAILED: "TEST_CASES_FAILED",
  RATE_LIMITED: "RATE_LIMITED",
  INTERNAL_ERROR: "INTERNAL_ERROR",
} as const;

export type ErrorCode = (typeof ErrorCodes)[keyof typeof ErrorCodes];

/** HTTP status to error code mapping */
export const HTTP_STATUS_TO_ERROR: Record<number, ErrorCode> = {
  400: ErrorCodes.VALIDATION_FAILED,
  401: ErrorCodes.UNAUTHORIZED,
  403: ErrorCodes.FORBIDDEN,
  404: ErrorCodes.NOT_FOUND,
  409: ErrorCodes.VERSION_EXISTS,
  410: ErrorCodes.DEPRECATED,
  422: ErrorCodes.VALIDATION_FAILED, // or SECURITY_SCAN_FAILED / TEST_CASES_FAILED per context
  429: ErrorCodes.RATE_LIMITED,
  500: ErrorCodes.INTERNAL_ERROR,
};
