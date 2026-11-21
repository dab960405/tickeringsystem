import { TestBed } from '@angular/core/testing';
import { describe, expect, it, vi } from 'vitest';
import { Order } from '../core/models';
import { OrderActions } from './order-actions';
import { Purchase } from './purchase';

describe('Order action lifecycle', () => {
  it('does not emit into a destroyed view after payment navigates to tickets', async () => {
    let finish!: (order: Order) => void;
    const pending = new Promise<Order>((resolve) => {
      finish = resolve;
    });
    TestBed.configureTestingModule({
      providers: [{ provide: Purchase, useValue: { pay: () => pending } }],
    });
    const fixture = TestBed.createComponent(OrderActions);
    const order = { id: 'order-one' } as Order;
    fixture.componentRef.setInput('order', order);
    const emit = vi.spyOn(fixture.componentInstance.updated, 'emit');
    const result = fixture.componentInstance.pay('capture');
    fixture.destroy();
    finish(order);
    await result;
    expect(emit).not.toHaveBeenCalled();
  });
});
