import { computed, effect, inject, Injectable, signal } from '@angular/core';
import { Router } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Auth } from '../core/auth';
import { EventDetail, Order, PaymentAction, Reservation } from '../core/models';
import { Notice } from '../core/notice';
import { TicketingApi } from '../core/ticketing-api';

/** Keeps an unfinished checkout while the buyer navigates or signs in. */
@Injectable({ providedIn: 'root' })
export class Purchase {
  private readonly api = inject(TicketingApi);
  private readonly auth = inject(Auth);
  private readonly notice = inject(Notice);
  private readonly router = inject(Router);
  private readonly selectedEvent = signal<EventDetail | null>(null);
  private readonly activeReservation = signal<Reservation | null>(null);
  private readonly activeOrder = signal<Order | null>(null);
  private key: string | null = null;
  readonly event = this.selectedEvent.asReadonly();
  readonly reservation = this.activeReservation.asReadonly();
  readonly order = this.activeOrder.asReadonly();
  readonly busy = signal(false);
  readonly pending = computed(
    () => !!this.reservation() && (!this.order() || this.order()?.status === 'PENDING_PAYMENT'),
  );

  constructor() {
    let activeSession = this.auth.sessionVersion();
    effect(() => {
      const session = this.auth.sessionVersion();
      // Logout and another login may complete in one reactive turn. Track the session
      // generation so a new authenticated account never inherits the previous checkout.
      if (session !== activeSession || !this.auth.signedIn()) {
        this.clearCheckout();
      }
      activeSession = session;
    });
  }

  select(event: EventDetail): boolean {
    if ((this.pending() || this.busy()) && this.event()?.id !== event.id) return false;
    if (this.event()?.id !== event.id) this.clearCheckout();
    this.selectedEvent.set(event);
    return true;
  }

  newSelection() {
    if (!this.pending() && !this.busy()) this.clearCheckout();
  }

  async reserve(ticketTypeId: string, quantity: number) {
    await this.run(async (session) => {
      const reservation = await firstValueFrom(this.api.reserve(ticketTypeId, quantity));
      // An in-flight request may have committed under the previous user. The server TTL
      // reclaims that hold; never attach it to a different signed-in account.
      if (!this.sameSession(session)) return;
      this.activeReservation.set(reservation);
      this.key = crypto.randomUUID();
      this.notice.show('Tickets reserved temporarily. Continue by creating your order.');
    });
  }

  async createOrder() {
    const reservation = this.reservation();
    if (!reservation || !this.key || this.order()) return;
    const key = this.key;
    await this.run(async (session) => {
      const order = await firstValueFrom(this.api.createOrder(reservation.id, key));
      if (!this.sameSession(session)) return;
      this.activeOrder.set(order);
      this.notice.show('Order created. You can simulate payment; no real money is charged.');
    });
  }

  async release() {
    const reservation = this.reservation();
    const event = this.event();
    if (!reservation || this.order() || !event) return;
    await this.run(async (session) => {
      if (Date.parse(reservation.expiresAt) > Date.now())
        await firstValueFrom(this.api.release(reservation.id));
      if (!this.sameSession(session)) return;
      this.clearCheckout();
      const updatedEvent = await firstValueFrom(this.api.event(event.id));
      if (!this.sameSession(session)) return;
      this.selectedEvent.set(updatedEvent);
      this.notice.show('Reservation released or expired. Check the updated availability.');
    });
  }

  async cancel(id: string): Promise<Order | null> {
    return this.run(async (session) => {
      const order = await firstValueFrom(this.api.cancel(id));
      if (!this.sameSession(session)) return null;
      this.update(order);
      this.notice.show(
        order.refundStatus === 'REQUESTED'
          ? 'Request received. Confirm the simulated refund to complete it.'
          : 'Order cancelled.',
      );
      return order;
    });
  }

  async pay(id: string, action: PaymentAction): Promise<Order | null> {
    return this.run(async (session) => {
      await firstValueFrom(this.api.pay(id, action));
      if (!this.sameSession(session)) return null;
      const order = await firstValueFrom(this.api.order(id));
      if (!this.sameSession(session)) return null;
      this.update(order);
      if (
        action === 'capture' &&
        order.status === 'PAID' &&
        order.fulfillmentStatus === 'FULFILLED'
      ) {
        this.notice.show('Purchase complete! Your tickets are ready.');
        await this.router.navigate(['/tickets']);
      } else if (action === 'refund' && order.refundStatus === 'CONFIRMED') {
        this.notice.show('Simulated refund confirmed. Your tickets are no longer valid.');
      } else if (order.status === 'CANCELLED') {
        this.notice.show('Simulated payment declined. The order was cancelled.');
      } else {
        this.notice.show('Response received. Check your order status before continuing.');
      }
      return order;
    });
  }

  private update(order: Order) {
    if (this.order()?.id === order.id) this.activeOrder.set(order);
  }
  private clearCheckout() {
    this.activeReservation.set(null);
    this.activeOrder.set(null);
    this.key = null;
  }
  private sameSession(session: number): boolean {
    return this.auth.signedIn() && this.auth.sessionVersion() === session;
  }

  private async run<T>(action: (session: number) => Promise<T>): Promise<T | null> {
    if (this.busy() || !this.auth.signedIn()) return null;
    const session = this.auth.sessionVersion();
    this.busy.set(true);
    try {
      return await action(session);
    } catch (error) {
      if (this.sameSession(session)) this.notice.error(error);
      return null;
    } finally {
      this.busy.set(false);
    }
  }
}
