import { CurrencyPipe, DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, effect, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';
import { firstValueFrom } from 'rxjs';
import { Auth } from '../core/auth';
import { Order, Page } from '../core/models';
import { errorMessage } from '../core/notice';
import { TicketingApi } from '../core/ticketing-api';
import { OrderActions } from '../purchase/order-actions';
import { deliveryLabel, OrderStatus } from '../purchase/order-status';

@Component({
  selector: 'app-orders',
  imports: [CurrencyPipe, DatePipe, RouterLink, OrderActions, OrderStatus],
  templateUrl: './orders.html',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class Orders {
  readonly auth = inject(Auth);
  private readonly api = inject(TicketingApi);
  readonly result = signal<Page<Order> | null>(null);
  readonly busy = signal(false);
  readonly error = signal('');
  readonly page = signal(0);
  readonly delivery = deliveryLabel;
  private generation = 0;

  constructor() {
    effect(() => {
      if (!this.auth.signedIn()) {
        this.generation++;
        this.result.set(null);
      }
    });
    void this.load();
  }

  async load(page = this.page()) {
    const generation = ++this.generation;
    this.busy.set(true);
    this.error.set('');
    try {
      const result = await firstValueFrom(this.api.orders(page));
      if (generation === this.generation && this.auth.signedIn()) {
        this.result.set(result);
        this.page.set(result.page);
      }
    } catch (error) {
      if (generation === this.generation) this.error.set(errorMessage(error));
    } finally {
      if (generation === this.generation) this.busy.set(false);
    }
  }
  update(order: Order) {
    this.result.update((page) =>
      page
        ? { ...page, content: page.content.map((item) => (item.id === order.id ? order : item)) }
        : null,
    );
  }
  quantity(order: Order) {
    return order.items.reduce((total, item) => total + item.quantity, 0);
  }
}
