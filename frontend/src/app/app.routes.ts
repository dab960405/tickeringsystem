import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/auth-guard';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'events' },
  {
    path: 'events',
    title: 'Events · Ticketing',
    loadComponent: () => import('./catalog/catalog').then((m) => m.Catalog),
  },
  {
    path: 'events/:id',
    title: 'Buy tickets · Ticketing',
    loadComponent: () => import('./purchase/checkout').then((m) => m.Checkout),
  },
  {
    path: 'login',
    title: 'Sign in · Ticketing',
    canActivate: [guestGuard],
    loadComponent: () => import('./auth/login').then((m) => m.Login),
  },
  {
    path: 'register',
    title: 'Create account · Ticketing',
    canActivate: [guestGuard],
    data: { register: true },
    loadComponent: () => import('./auth/login').then((m) => m.Login),
  },
  {
    path: 'orders',
    title: 'My orders · Ticketing',
    canActivate: [authGuard],
    loadComponent: () => import('./account/orders').then((m) => m.Orders),
  },
  {
    path: 'tickets',
    title: 'My tickets · Ticketing',
    canActivate: [authGuard],
    loadComponent: () => import('./account/tickets').then((m) => m.Tickets),
  },
  // Preserve existing bookmarks while making English routes canonical.
  { path: 'eventos', pathMatch: 'full', redirectTo: 'events' },
  { path: 'eventos/:id', pathMatch: 'full', redirectTo: 'events/:id' },
  { path: 'entrar', pathMatch: 'full', redirectTo: 'login' },
  { path: 'registro', pathMatch: 'full', redirectTo: 'register' },
  { path: 'compras', pathMatch: 'full', redirectTo: 'orders' },
  { path: 'entradas', pathMatch: 'full', redirectTo: 'tickets' },
  { path: '**', redirectTo: 'events' },
];
