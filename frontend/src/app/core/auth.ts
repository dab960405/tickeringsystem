import { HttpClient } from '@angular/common/http';
import { computed, inject, Injectable, signal } from '@angular/core';
import { catchError, finalize, map, Observable, of, shareReplay, tap, throwError } from 'rxjs';
import { Session, Tokens } from './models';

@Injectable({ providedIn: 'root' })
export class Auth {
  private readonly http = inject(HttpClient);
  private readonly current = signal<Session | null>(null);
  private refreshing?: Observable<Tokens>;
  readonly email = computed(() => this.current()?.email ?? null);
  readonly signedIn = computed(() => this.current() !== null);

  accessToken(): string | null {
    return this.current()?.accessToken ?? null;
  }

  register(email: string, password: string, fullName: string) {
    return this.http.post('/api/v1/auth/register', { email, password, fullName });
  }

  login(email: string, password: string) {
    return this.http
      .post<Tokens>('/api/v1/auth/login', { email, password })
      .pipe(tap((tokens) => this.current.set({ ...tokens, email })));
  }

  refresh(): Observable<Tokens> {
    if (this.refreshing) return this.refreshing;
    const original = this.current();
    if (!original) return throwError(() => new Error('Sign in to continue.'));
    this.refreshing = this.http
      .post<Tokens>('/api/v1/auth/refresh', {
        refreshToken: original.refreshToken,
      })
      .pipe(
        map((tokens) => {
          // A late response must never restore a session that has already been closed.
          if (this.current() !== original) throw new Error('The session changed. Sign in again.');
          this.current.set({ ...original, ...tokens });
          return tokens;
        }),
        catchError((error) => {
          if (this.current() === original) this.current.set(null);
          return throwError(() => error);
        }),
        finalize(() => {
          this.refreshing = undefined;
        }),
        shareReplay({ bufferSize: 1, refCount: false }),
      );
    return this.refreshing;
  }

  logout(): Observable<unknown> {
    const session = this.current();
    this.current.set(null);
    if (!session) return of(null);
    return this.http.post('/api/v1/auth/logout', { refreshToken: session.refreshToken });
  }
}
