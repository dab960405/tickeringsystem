import {
  ChangeDetectionStrategy,
  Component,
  computed,
  DestroyRef,
  inject,
  input,
  output,
} from '@angular/core';
import { Order, PaymentAction } from '../core/models';
import { Purchase } from './purchase';

@Component({
  selector: 'app-order-actions',
  template: `
    <div class="actions" [attr.aria-busy]="purchase.busy()">
      @if (order().status === 'PENDING_PAYMENT') {
        <button class="button" [disabled]="purchase.busy()" (click)="pay('capture')">
          Simular pago aprobado
        </button>
        <button class="button secondary" [disabled]="purchase.busy()" (click)="pay('failure')">
          Simular rechazo
        </button>
        <button class="button danger" [disabled]="purchase.busy()" (click)="cancel()">
          Cancelar orden
        </button>
      }
      @if (
        order().status === 'PAID' &&
        order().fulfillmentStatus === 'FULFILLED' &&
        order().refundStatus === 'NONE'
      ) {
        <button class="button secondary" [disabled]="purchase.busy()" (click)="cancel()">
          Solicitar reembolso
        </button>
      }
      @if (canRefund()) {
        <button class="button secondary" [disabled]="purchase.busy()" (click)="pay('refund')">
          Confirmar reembolso simulado
        </button>
      }
    </div>
  `,
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class OrderActions {
  private readonly destroyRef = inject(DestroyRef);
  readonly purchase = inject(Purchase);
  readonly order = input.required<Order>();
  readonly updated = output<Order>();
  readonly canRefund = computed(() =>
    ['REQUESTED', 'REQUIRED', 'FAILED', 'PENDING_RECONCILIATION'].includes(
      this.order().refundStatus,
    ),
  );

  async pay(action: PaymentAction) {
    const result = await this.purchase.pay(this.order().id, action);
    if (result && !this.destroyRef.destroyed) this.updated.emit(result);
  }
  async cancel() {
    const result = await this.purchase.cancel(this.order().id);
    if (result && !this.destroyRef.destroyed) this.updated.emit(result);
  }
}
