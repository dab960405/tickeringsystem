import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, switchMap, throwError, timeout } from 'rxjs';
import { Auth } from './auth';

export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(Auth);
  const local = request.url.startsWith('/api/v1/') || request.url === '/demo/payment';
  const authenticated = local && !request.url.startsWith('/api/v1/auth/');
  const token = authenticated ? auth.accessToken() : null;
  const authorized = token
    ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
    : request;
  return next(authorized).pipe(
    timeout(25000),
    catchError((error: unknown) => {
      if (
        !(error instanceof HttpErrorResponse) ||
        error.status !== 401 ||
        !token ||
        !auth.signedIn()
      ) {
        return throwError(() => error);
      }
      // A concurrent request may already have renewed the access token.
      if (auth.accessToken() !== token) {
        return next(
          request.clone({ setHeaders: { Authorization: `Bearer ${auth.accessToken()}` } }),
        ).pipe(timeout(25000));
      }
      return auth.refresh().pipe(
        switchMap((tokens) =>
          next(
            request.clone({
              setHeaders: { Authorization: `Bearer ${tokens.accessToken}` },
            }),
          ).pipe(timeout(25000)),
        ),
      );
    }),
  );
};
