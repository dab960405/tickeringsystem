import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import {
  EventDetail,
  EventSummary,
  Order,
  Page,
  PaymentAction,
  Reservation,
  Ticket,
} from './models';

@Injectable({ providedIn: 'root' })
export class TicketingApi {
  private readonly http = inject(HttpClient);

  events(query: string, page: number) {
    return this.http.get<Page<EventSummary>>('/api/v1/events', {
      params: { q: query, page, size: 6, sort: 'startsAt,asc' },
    });
  }
  event(id: string) {
    return this.http.get<EventDetail>(`/api/v1/events/${encodeURIComponent(id)}`);
  }
  reserve(ticketTypeId: string, quantity: number) {
    return this.http.post<Reservation>('/api/v1/reservations', { ticketTypeId, quantity });
  }
  release(id: string) {
    return this.http.delete<void>(`/api/v1/reservations/${encodeURIComponent(id)}`);
  }
  createOrder(reservationId: string, key: string) {
    return this.http.post<Order>(
      '/api/v1/orders',
      { reservationId },
      { headers: { 'Idempotency-Key': key } },
    );
  }
  orders(page: number) {
    return this.http.get<Page<Order>>('/api/v1/orders', { params: { page, size: 10 } });
  }
  order(id: string) {
    return this.http.get<Order>(`/api/v1/orders/${encodeURIComponent(id)}`);
  }
  cancel(id: string) {
    return this.http.post<Order>(`/api/v1/orders/${encodeURIComponent(id)}/cancel`, {});
  }
  pay(orderId: string, action: PaymentAction) {
    return this.http.post(
      '/demo/payment',
      { orderId, action },
      { headers: { 'X-Demo-Action': 'simulate' } },
    );
  }
  tickets() {
    return this.http.get<Ticket[]>('/api/v1/me/tickets');
  }
}
