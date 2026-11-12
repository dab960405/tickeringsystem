# ADR-015: Una orden por reserva

- **Estado:** Aceptado
- **Fecha:** 2026-11-12
- **Fase:** 3

## Contexto

El modelo permitía crear dos órdenes pagables para una misma reserva. Ambas podían emitir tickets contra el mismo inventario. Además, un webhook `FAILED` asociado a la segunda orden podía cancelar o liberar la reserva compartida y afectar a la primera orden, aunque esta fuera la que debía conservarla.

## Decisión

- `OrderService.create` comprueba si ya existe una orden para la reserva antes de crear otra. Si existe, rechaza la solicitud como conflicto.
- La migración Flyway V11 crea el índice único `ux_orders_reservation_id` sobre `orders(reservation_id)`. La restricción en PostgreSQL es la última defensa ante dos creaciones concurrentes que superen simultáneamente el guard de aplicación.
- `DataIntegrityViolationException` se mapea globalmente a HTTP `409 Conflict`, por lo que la colisión de unicidad durante esa carrera se presenta como conflicto y no como error interno.
- En la carrera con la MISMA `Idempotency-Key` y la misma reserva, el log del job de CI permite inferir un `409` por la violación de unicidad; no registra directamente los códigos HTTP individuales. El test acepta `200` o `409` para la petición concurrente. Véase [ADR-011, carrera concurrente con la misma clave y reserva](ADR-011-order-and-webhook-idempotency.md#carrera-concurrente-con-la-misma-clave-y-reserva).

## Consecuencias

- El índice cubre todas las órdenes, incluidas las canceladas; no es un índice parcial condicionado por el estado.
- Una reserva asociada a una orden cancelada no se reutiliza para crear otra orden. En particular, `cancelPending` libera la reserva y conserva la orden cancelada, por lo que la unicidad de `reservation_id` continúa impidiendo una segunda orden para esa misma reserva.
- La restricción de base de datos protege el inventario y la relación orden-reserva incluso cuando las solicitudes llegan en paralelo.
