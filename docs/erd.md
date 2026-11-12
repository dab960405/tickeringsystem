# Modelo de datos (ERD)

Fuente de verdad: migraciones Flyway `V1__create_users.sql` a `V11__one_order_per_reservation.sql` en `ticketing-api/src/main/resources/db/migration/`. Este diagrama refleja el esquema SQL, no un modelo conceptual futuro.

```mermaid
erDiagram
    USERS {
        UUID id PK
        VARCHAR email UK
        VARCHAR password_hash
        VARCHAR full_name
        VARCHAR role
        BOOLEAN enabled
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }
    VENUES {
        UUID id PK
        VARCHAR name
        VARCHAR city
        VARCHAR address
        INTEGER capacity
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }
    EVENTS {
        UUID id PK
        UUID organizer_id FK
        UUID venue_id FK
        VARCHAR title
        TEXT description
        VARCHAR category
        TIMESTAMPTZ starts_at
        TIMESTAMPTZ ends_at
        VARCHAR status
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }
    TICKET_TYPES {
        UUID id PK
        UUID event_id FK
        VARCHAR name
        NUMERIC price
        CHAR currency
        INTEGER total_quantity
        INTEGER available_quantity
        BIGINT version
        TIMESTAMPTZ sale_starts_at
        TIMESTAMPTZ sale_ends_at
        INTEGER max_per_order
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }
    RESERVATIONS {
        UUID id PK
        UUID user_id FK
        UUID ticket_type_id FK
        INTEGER quantity
        VARCHAR status
        TIMESTAMPTZ expires_at
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }
    ORDERS {
        UUID id PK
        UUID user_id FK
        UUID reservation_id FK_UK
        VARCHAR status
        NUMERIC total_amount
        CHAR currency
        VARCHAR idempotency_key
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }
    ORDER_ITEMS {
        UUID id PK
        UUID order_id FK
        UUID ticket_type_id FK
        INTEGER quantity
        NUMERIC unit_price
        CHAR currency
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }
    TICKETS {
        UUID id PK
        UUID order_item_id FK
        VARCHAR code UK
        VARCHAR status
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }
    PAYMENTS {
        UUID id PK
        UUID order_id FK_UK
        VARCHAR status
        VARCHAR provider_reference UK
        NUMERIC amount
        CHAR currency
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
    }
    OUTBOX_EVENTS {
        UUID id PK
        VARCHAR aggregate_type
        UUID aggregate_id
        VARCHAR event_type
        JSONB payload
        TIMESTAMPTZ created_at
        TIMESTAMPTZ updated_at
        TIMESTAMPTZ published_at
    }

    USERS ||--o{ EVENTS : organizes
    VENUES ||--o{ EVENTS : hosts
    EVENTS ||--o{ TICKET_TYPES : offers
    USERS ||--o{ RESERVATIONS : creates
    TICKET_TYPES ||--o{ RESERVATIONS : reserved_as
    USERS ||--o{ ORDERS : places
    RESERVATIONS ||--o| ORDERS : originates
    ORDERS ||--|{ ORDER_ITEMS : contains
    TICKET_TYPES ||--o{ ORDER_ITEMS : prices
    ORDER_ITEMS ||--o{ TICKETS : issues
    ORDERS ||--o| PAYMENTS : paid_by
```

## Integridad y reglas relevantes

- **Identidad:** `users.email` es único y debe almacenarse en minúsculas. Los roles permitidos son `USER`, `ORGANIZER` y `ADMIN`.
- **Catálogo:** un evento pertenece a un organizador y a un recinto. Un tipo de entrada pertenece a un evento; `(event_id, name)` es único. `available_quantity` debe permanecer entre cero y `total_quantity`.
- **Reserva:** cada reserva pertenece a un usuario y a un tipo de entrada. `quantity > 0`; los estados persistidos son `ACTIVE`, `CONFIRMED`, `EXPIRED` y `CANCELLED`. El índice parcial `ix_reservations_active_expires_at` acelera el barrido de reservas activas vencidas.
- **Orden:** cada orden pertenece a un usuario y referencia exactamente una reserva. `UNIQUE(user_id, idempotency_key)` limita la idempotencia por usuario. V11 crea el índice único no parcial `ux_orders_reservation_id` sobre `orders(reservation_id)`: una reserva puede tener cero o una orden. La restricción incluye órdenes canceladas; una reserva cuya orden fue cancelada no puede reutilizarse y requiere crear una reserva nueva.
- **Creación de orden:** `OrderService.create` comprueba si existe una orden para la reserva antes de insertar. PostgreSQL protege la misma regla ante solicitudes concurrentes mediante el índice único; los conflictos de integridad se traducen a HTTP `409 Conflict`.
- **Detalle y tickets:** cada `order_item` conserva cantidad, precio unitario y moneda al momento de la compra. Cada ticket pertenece a un detalle y su código es único.
- **Pago:** `UNIQUE(order_id)` impone como máximo un pago por orden; `provider_reference` también es única (PostgreSQL permite múltiples valores NULL en esta restricción).
- **Outbox:** `aggregate_id` es deliberadamente polimórfico y no tiene FK a una tabla concreta. El índice parcial `ix_outbox_events_unpublished` cubre filas cuyo `published_at` aún es NULL. La tabla está creada; el publicador y el consumo Kafka corresponden a la Fase 5.

## Migraciones

| Versión | Tabla | Decisión estructural |
| --- | --- | --- |
| V1 | `users` | identidad, rol y correo único |
| V2 | `venues` | recintos y capacidad positiva |
| V3 | `events` | organizador, recinto, fechas y estado |
| V4 | `ticket_types` | precio, ventana de venta e inventario |
| V5 | `reservations` | reserva temporal con vencimiento e índice parcial |
| V6 | `orders` | orden, estado e idempotencia por usuario |
| V7 | `order_items` | snapshot de cantidad/precio por orden |
| V8 | `tickets` | tickets emitidos y código único |
| V9 | `payments` | pago 1:1 con orden y referencia única |
| V10 | `outbox_events` | registro durable para futura publicación asíncrona |
| V11 | `orders` | índice único no parcial `ux_orders_reservation_id`; máximo una orden por reserva, incluso cancelada |
