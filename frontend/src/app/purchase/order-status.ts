import { Pipe, PipeTransform } from '@angular/core';
import { Order } from '../core/models';

const labels: Record<string, string> = {
  PENDING_PAYMENT: 'Pendiente de pago',
  PAID: 'Pagada',
  CANCELLED: 'Cancelada',
  EXPIRED: 'Vencida',
  REFUNDED: 'Reembolsada',
  FULFILLED: 'Entradas emitidas',
  UNFULFILLABLE: 'Sin entradas · requiere reembolso',
  PENDING: 'Pendiente',
  NONE: 'Sin reembolso',
  REQUESTED: 'Reembolso solicitado',
  REQUIRED: 'Reembolso requerido',
  CONFIRMED: 'Reembolso confirmado',
  FAILED: 'Reembolso fallido',
  PENDING_RECONCILIATION: 'Reembolso en revisión',
};

@Pipe({ name: 'orderStatus' })
export class OrderStatus implements PipeTransform {
  transform(value: string): string {
    return labels[value] ?? value;
  }
}

export function deliveryLabel(order: Order): string {
  if (order.status === 'REFUNDED') return 'Entradas invalidadas';
  if (order.status === 'CANCELLED' || order.status === 'EXPIRED') return 'Sin entrega';
  return labels[order.fulfillmentStatus] ?? order.fulfillmentStatus;
}
