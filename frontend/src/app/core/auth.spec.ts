import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { afterEach, beforeEach, describe, expect, it } from 'vitest';
import { Auth } from './auth';
import { authInterceptor } from './auth-interceptor';

describe('Authentication HTTP boundary', () => {
  let auth: Auth;
  let http: HttpClient;
  let backend: HttpTestingController;
  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([authInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    auth = TestBed.inject(Auth);
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
    auth.login('buyer@example.com', 'example-password').subscribe();
    backend
      .expectOne('/api/v1/auth/login')
      .flush({ accessToken: 'old', refreshToken: 'refresh', expiresIn: 60 });
  });
  afterEach(() => backend.verify());

  it('attaches credentials only to our API, never an external URL', () => {
    http.get('/api/v1/orders').subscribe();
    const own = backend.expectOne('/api/v1/orders');
    expect(own.request.headers.get('Authorization')).toBe('Bearer old');
    own.flush([]);
    http.get('https://other.example/api').subscribe();
    const other = backend.expectOne('https://other.example/api');
    expect(other.request.headers.has('Authorization')).toBe(false);
    other.flush({});
  });

  it('shares a single refresh across concurrent 401 responses and retries with the new token', () => {
    http.get('/api/v1/orders').subscribe();
    http.get('/api/v1/me/tickets').subscribe();
    backend.expectOne('/api/v1/orders').flush({}, { status: 401, statusText: 'Unauthorized' });
    backend.expectOne('/api/v1/me/tickets').flush({}, { status: 401, statusText: 'Unauthorized' });
    const refresh = backend.expectOne('/api/v1/auth/refresh');
    expect(refresh.request.body).toEqual({ refreshToken: 'refresh' });
    refresh.flush({ accessToken: 'new', refreshToken: 'rotated', expiresIn: 60 });
    const retried = backend.match((req) => req.url !== '/api/v1/auth/refresh');
    expect(retried).toHaveLength(2);
    for (const request of retried) {
      expect(request.request.headers.get('Authorization')).toBe('Bearer new');
      request.flush([]);
    }
    expect(auth.accessToken()).toBe('new');
  });

  it('clears the session when refresh is rejected and does not loop', () => {
    let failed = false;
    http.get('/api/v1/orders').subscribe({
      error: () => {
        failed = true;
      },
    });
    backend.expectOne('/api/v1/orders').flush({}, { status: 401, statusText: 'Unauthorized' });
    backend
      .expectOne('/api/v1/auth/refresh')
      .flush({}, { status: 401, statusText: 'Unauthorized' });
    expect(failed).toBe(true);
    expect(auth.signedIn()).toBe(false);
  });

  it('does not revive a logged-out session when refresh finishes late', () => {
    auth.refresh().subscribe({ error: () => undefined });
    const refresh = backend.expectOne('/api/v1/auth/refresh');
    auth.logout().subscribe();
    backend.expectOne('/api/v1/auth/logout').flush(null);
    refresh.flush({ accessToken: 'late', refreshToken: 'late-refresh', expiresIn: 60 });
    expect(auth.signedIn()).toBe(false);
  });

  it('keeps the session generation stable during a normal token refresh', () => {
    const before = auth.sessionVersion();
    auth.refresh().subscribe();
    backend
      .expectOne('/api/v1/auth/refresh')
      .flush({ accessToken: 'new', refreshToken: 'rotated', expiresIn: 60 });
    expect(auth.sessionVersion()).toBe(before);
  });

  it('does not automatically retry a failed payment mutation', () => {
    http
      .post('/demo/payment', { orderId: 'one', action: 'capture' })
      .subscribe({ error: () => undefined });
    backend.expectOne('/demo/payment').flush({}, { status: 502, statusText: 'Bad Gateway' });
    backend.expectNone('/demo/payment');
    backend.expectNone('/api/v1/auth/refresh');
  });
});
