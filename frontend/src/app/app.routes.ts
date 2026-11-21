import { Routes } from '@angular/router';
import { authGuard, guestGuard } from './core/auth-guard';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'eventos' },
  {
    path: 'eventos',
    title: 'Eventos · Ticketing',
    loadComponent: () => import('./catalog/catalog').then((m) => m.Catalog),
  },
  {
    path: 'eventos/:id',
    title: 'Compra de entradas · Ticketing',
    loadComponent: () => import('./purchase/checkout').then((m) => m.Checkout),
  },
  {
    path: 'entrar',
    title: 'Iniciar sesión · Ticketing',
    canActivate: [guestGuard],
    loadComponent: () => import('./auth/login').then((m) => m.Login),
  },
  {
    path: 'registro',
    title: 'Crear cuenta · Ticketing',
    canActivate: [guestGuard],
    data: { register: true },
    loadComponent: () => import('./auth/login').then((m) => m.Login),
  },
  {
    path: 'compras',
    title: 'Mis compras · Ticketing',
    canActivate: [authGuard],
    loadComponent: () => import('./account/orders').then((m) => m.Orders),
  },
  {
    path: 'entradas',
    title: 'Mis entradas · Ticketing',
    canActivate: [authGuard],
    loadComponent: () => import('./account/tickets').then((m) => m.Tickets),
  },
  { path: '**', redirectTo: 'eventos' },
];
