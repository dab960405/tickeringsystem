/** DTOs consumed from the existing Java API. Dates are ISO-8601 strings. */
export interface Page<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
  totalPages: number;
}

export interface EventSummary {
  id: string;
  title: string;
  category: string;
  startsAt: string;
  venueName: string;
  city: string;
}

export interface TicketType {
  id: string;
  name: string;
  price: number;
  currency: string;
  availableQuantity: number;
  maxPerOrder: number;
  saleStartsAt: string;
  saleEndsAt: string;
}

export interface EventDetail {
  id: string;
  title: string;
  description: string | null;
  startsAt: string;
  venue: { name: string; city: string };
  ticketTypes: TicketType[];
}

export interface Reservation {
  id: string;
  ticketTypeId: string;
  quantity: number;
  status: string;
  expiresAt: string;
}

export interface Order {
  id: string;
  reservationId: string;
  status: 'PENDING_PAYMENT' | 'PAID' | 'CANCELLED' | 'EXPIRED' | 'REFUNDED';
  fulfillmentStatus: 'PENDING' | 'FULFILLED' | 'UNFULFILLABLE';
  refundStatus:
    'NONE' | 'REQUESTED' | 'REQUIRED' | 'PENDING_RECONCILIATION' | 'FAILED' | 'CONFIRMED';
  totalAmount: number;
  currency: string;
  items: { ticketTypeId: string; quantity: number; unitPrice: number; subtotal: number }[];
  createdAt: string;
}

export interface Ticket {
  code: string;
  orderId: string;
  ticketTypeId: string;
  unitId: string;
  status: string;
}

export interface Tokens {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;
}

export interface Session extends Tokens {
  email: string;
}

export type PaymentAction = 'capture' | 'failure' | 'refund';
