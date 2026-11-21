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
    effect(() => {
      if (!this.auth.signedIn()) {
        this.activeReservation.set(null);
        this.activeOrder.set(null);
        this.key = null;
      }
    });
  }

  select(event: EventDetail): boolean {
    if (this.pending() && this.event()?.id !== event.id) return false;
    if (this.event()?.id !== event.id) this.clearCheckout();
    this.selectedEvent.set(event);
    return true;
  }

  newSelection() {
    if (!this.pending()) this.clearCheckout();
  }

  async reserve(ticketTypeId: string, quantity: number) {
    await this.run(async () => {
      this.activeReservation.set(await firstValueFrom(this.api.reserve(ticketTypeId, quantity)));
      this.key = crypto.randomUUID();
      this.notice.show('Entradas reservadas temporalmente. Continúa creando la orden.');
    });
  }

  async createOrder() {
    const reservation = this.reservation();
    if (!reservation || !this.key || this.order()) return;
    const key = this.key;
    await this.run(async () => {
      this.activeOrder.set(await firstValueFrom(this.api.createOrder(reservation.id, key)));
      this.notice.show('Orden creada. Ahora puedes simular el pago, sin cobros reales.');
    });
  }

  async release() {
    const reservation = this.reservation();
    const event = this.event();
    if (!reservation || this.order() || !event) return;
    await this.run(async () => {
      if (Date.parse(reservation.expiresAt) > Date.now())
        await firstValueFrom(this.api.release(reservation.id));
      this.clearCheckout();
      this.selectedEvent.set(await firstValueFrom(this.api.event(event.id)));
      this.notice.show('Reserva liberada o vencida. Consulta la disponibilidad actualizada.');
    });
  }

  async cancel(id: string): Promise<Order | null> {
    return this.run(async () => {
      const order = await firstValueFrom(this.api.cancel(id));
      this.update(order);
      this.notice.show(
        order.refundStatus === 'REQUESTED'
          ? 'Solicitud registrada. Confirma el reembolso simulado para completar la devolución.'
          : 'Orden cancelada.',
      );
      return order;
    });
  }

  async pay(id: string, action: PaymentAction): Promise<Order | null> {
    return this.run(async () => {
      await firstValueFrom(this.api.pay(id, action));
      const order = await firstValueFrom(this.api.order(id));
      this.update(order);
      if (
        action === 'capture' &&
        order.status === 'PAID' &&
        order.fulfillmentStatus === 'FULFILLED'
      ) {
        this.notice.show('¡Compra completada! Tus entradas están disponibles.');
        await this.router.navigate(['/entradas']);
      } else if (action === 'refund' && order.refundStatus === 'CONFIRMED') {
        this.notice.show('Reembolso simulado confirmado. Las entradas dejaron de estar vigentes.');
      } else if (order.status === 'CANCELLED') {
        this.notice.show('Pago de prueba rechazado. La orden quedó cancelada.');
      } else {
        this.notice.show('Respuesta recibida. Consulta el estado de la orden antes de continuar.');
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
  private async run<T>(action: () => Promise<T>): Promise<T | null> {
    if (this.busy() || !this.auth.signedIn()) return null;
    this.busy.set(true);
    try {
      return await action();
    } catch (error) {
      this.notice.error(error);
      return null;
    } finally {
      this.busy.set(false);
    }
  }
}
