import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, signal } from '@angular/core';

export function errorMessage(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    const messages: Record<number, string> = {
      0: 'The response could not be confirmed. Check My orders before retrying.',
      401: 'The session or credentials are invalid. Sign in again.',
      403: 'This action requires an authorized buyer account.',
      429: 'Too many attempts. Wait a minute and try again.',
    };
    return (
      messages[error.status] ??
      (typeof error.error?.detail === 'string'
        ? error.error.detail
        : 'The operation could not be completed.')
    );
  }
  if (error instanceof Error && error.name === 'TimeoutError') {
    return 'The response timed out. Check My orders before retrying.';
  }
  return error instanceof Error ? error.message : 'The operation could not be completed.';
}

@Injectable({ providedIn: 'root' })
export class Notice {
  readonly message = signal('');
  readonly failed = signal(false);
  show(message: string, failed = false) {
    this.failed.set(failed);
    this.message.set(message);
  }
  error(error: unknown) {
    this.show(errorMessage(error), true);
  }
  clear() {
    this.message.set('');
  }
}
