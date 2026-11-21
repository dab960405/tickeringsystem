import { HttpErrorResponse } from '@angular/common/http';
import { Injectable, signal } from '@angular/core';

export function errorMessage(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    const messages: Record<number, string> = {
      0: 'No se pudo confirmar la respuesta. Consulta Mis compras antes de repetir la operación.',
      401: 'La sesión o las credenciales no son válidas. Vuelve a entrar.',
      403: 'Esta acción requiere una cuenta de comprador con acceso al recurso.',
      429: 'Has realizado varios intentos. Espera un minuto y vuelve a intentarlo.',
    };
    return (
      messages[error.status] ??
      (typeof error.error?.detail === 'string'
        ? error.error.detail
        : 'No se pudo completar la operación.')
    );
  }
  if (error instanceof Error && error.name === 'TimeoutError') {
    return 'La respuesta tardó demasiado. Consulta Mis compras antes de repetir la operación.';
  }
  return error instanceof Error ? error.message : 'No se pudo completar la operación.';
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
