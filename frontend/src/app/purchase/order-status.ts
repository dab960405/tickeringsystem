import { Pipe, PipeTransform } from '@angular/core';
import { Order } from '../core/models';

const labels: Record<string, string> = {
  PENDING_PAYMENT: 'Awaiting payment',
  PAID: 'Paid',
  CANCELLED: 'Cancelled',
  EXPIRED: 'Expired',
  REFUNDED: 'Refunded',
  FULFILLED: 'Tickets issued',
  UNFULFILLABLE: 'Tickets unavailable · refund required',
  PENDING: 'Pending',
  NONE: 'No refund',
  REQUESTED: 'Refund requested',
  REQUIRED: 'Refund required',
  CONFIRMED: 'Refund confirmed',
  FAILED: 'Refund failed',
  PENDING_RECONCILIATION: 'Refund under review',
};

@Pipe({ name: 'orderStatus' })
export class OrderStatus implements PipeTransform {
  transform(value: string): string {
    return labels[value] ?? value;
  }
}

export function deliveryLabel(order: Order): string {
  if (order.status === 'REFUNDED') return 'Tickets invalidated';
  if (order.status === 'CANCELLED' || order.status === 'EXPIRED') return 'No fulfillment';
  return labels[order.fulfillmentStatus] ?? order.fulfillmentStatus;
}
