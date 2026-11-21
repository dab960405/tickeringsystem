import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { Auth } from '../core/auth';
import { EventDetail, Order } from '../core/models';
import { Purchase } from './purchase';

const event: EventDetail = {
  id: 'event-one',
  title: 'Festival',
  description: '',
  startsAt: '2030-01-01',
  venue: { name: 'Estadio', city: 'Lima' },
  ticketTypes: [],
};
const order: Order = {
  id: 'order-one',
  reservationId: 'reservation-one',
  status: 'PENDING_PAYMENT',
  fulfillmentStatus: 'PENDING',
  refundStatus: 'NONE',
  totalAmount: 45,
  currency: 'USD',
  items: [],
  createdAt: '2026-11-15',
};

describe('Purchase workflow', () => {
  let purchase: Purchase;
  let backend: HttpTestingController;
  const navigate = vi.fn().mockResolvedValue(true);
  beforeEach(() => {
    navigate.mockClear();
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Router, useValue: { navigate } },
      ],
    });
    backend = TestBed.inject(HttpTestingController);
    const auth = TestBed.inject(Auth);
    auth.login('buyer@example.com', 'password').subscribe();
    backend
      .expectOne('/api/v1/auth/login')
      .flush({ accessToken: 'access', refreshToken: 'refresh', expiresIn: 60 });
    purchase = TestBed.inject(Purchase);
    TestBed.tick();
    purchase.select(event);
  });
  afterEach(() => backend.verify());

  async function reserve() {
    const result = purchase.reserve('general', 1);
    backend.expectOne('/api/v1/reservations').flush({
      id: 'reservation-one',
      ticketTypeId: 'general',
      quantity: 1,
      status: 'ACTIVE',
      expiresAt: new Date(Date.now() + 600000).toISOString(),
    });
    await result;
  }

  it('reuses the idempotency key when creating an order again after an uncertain response', async () => {
    await reserve();
    const first = purchase.createOrder();
    const request = backend.expectOne('/api/v1/orders');
    const key = request.request.headers.get('Idempotency-Key');
    expect(key).toBeTruthy();
    request.flush({}, { status: 502, statusText: 'Bad Gateway' });
    await first;
    const second = purchase.createOrder();
    const retry = backend.expectOne('/api/v1/orders');
    expect(retry.request.headers.get('Idempotency-Key')).toBe(key);
    expect(retry.request.body).toEqual({ reservationId: 'reservation-one' });
    retry.flush(order);
    await second;
    expect(purchase.order()?.id).toBe(order.id);
  });

  it('prevents double reservation submission while the first request is pending', async () => {
    const first = purchase.reserve('general', 1);
    await purchase.reserve('general', 1);
    const requests = backend.match('/api/v1/reservations');
    expect(requests).toHaveLength(1);
    requests[0].flush({ id: 'one', quantity: 1, expiresAt: '2030-01-01' });
    await first;
  });

  it('keeps the pending reservation when another event is selected', async () => {
    await reserve();
    expect(purchase.select({ ...event, id: 'another' })).toBe(false);
    expect(purchase.event()?.id).toBe(event.id);
  });

  it('never releases the reservation directly after an order has been created', async () => {
    await reserve();
    const creating = purchase.createOrder();
    backend.expectOne('/api/v1/orders').flush(order);
    await creating;
    await purchase.release();
    backend.expectNone('/api/v1/reservations/reservation-one');
  });

  it('only goes to tickets after the API confirms payment AND fulfillment', async () => {
    const first = purchase.pay(order.id, 'capture');
    const payment = backend.expectOne('/demo/payment');
    expect(payment.request.body).toEqual({ orderId: order.id, action: 'capture' });
    expect(payment.request.headers.get('X-Demo-Action')).toBe('simulate');
    payment.flush({ simulated: true });
    await Promise.resolve();
    backend.expectOne('/api/v1/orders/order-one').flush({
      ...order,
      status: 'PAID',
      fulfillmentStatus: 'UNFULFILLABLE',
      refundStatus: 'REQUIRED',
    });
    await first;
    expect(navigate).not.toHaveBeenCalled();

    const second = purchase.pay(order.id, 'capture');
    backend.expectOne('/demo/payment').flush({ simulated: true });
    await Promise.resolve();
    backend
      .expectOne('/api/v1/orders/order-one')
      .flush({ ...order, status: 'PAID', fulfillmentStatus: 'FULFILLED' });
    await second;
    expect(navigate).toHaveBeenCalledWith(['/entradas']);
  });

  it('clears checkout on logout without persisting credentials', async () => {
    await reserve();
    TestBed.inject(Auth).logout().subscribe();
    backend.expectOne('/api/v1/auth/logout').flush(null);
    TestBed.tick();
    expect(purchase.reservation()).toBeNull();
  });
});
