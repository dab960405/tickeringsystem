import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Auth } from './auth';

/** Navigation convenience only: ownership and authorization are enforced by the API. */
export const authGuard: CanActivateFn = (_route, state) =>
  inject(Auth).signedIn() ||
  inject(Router).createUrlTree(['/entrar'], { queryParams: { returnUrl: state.url } });

export const guestGuard: CanActivateFn = () =>
  !inject(Auth).signedIn() || inject(Router).createUrlTree(['/eventos']);
