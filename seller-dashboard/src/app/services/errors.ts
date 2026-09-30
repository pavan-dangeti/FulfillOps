import { HttpErrorResponse } from '@angular/common/http';

/** Turns a failed call into a message a seller can act on (services answer with RFC 9457 problem details). */
export function describeError(e: unknown): string {
  if (e instanceof HttpErrorResponse) {
    if (e.status === 0) {
      return 'Cannot reach the server. Check that the backend is running.';
    }
    const detail = typeof e.error === 'object' && e.error !== null ? (e.error as { detail?: unknown }).detail : null;
    return typeof detail === 'string' && detail ? detail : `Request failed (${e.status})`;
  }
  return e instanceof Error ? e.message : 'Something went wrong';
}
