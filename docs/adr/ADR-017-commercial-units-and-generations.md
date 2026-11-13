# ADR-017: Unidades comerciales estables y generaciones de emisión

- **Estado:** Aceptado
- **Fecha:** 2026-11-13
- **Fase:** 4
- **Resuelve:** H-77

## Contexto

`OrderItem.quantity` representa un importe agregado, pero no da identidad a cada entrada adquirida. Una lista de tickets por `order_item_id` tampoco impide por sí sola dos emisiones concurrentes ni permite sustituir una emisión conservando la identidad de la unidad.

## Decisión

Se introducen cuatro conceptos persistentes:

1. **Commercial generation (`commercial_generations`):** pertenece a una orden, tiene `generation_number` creciente y estado `ACTIVE`, `SUPERSEDED` o `CANCELLED`. Como máximo una generación está activa por orden.
2. **Commercial unit (`commercial_units`):** pertenece a una orden y a un `order_item`; `unit_number` es único dentro del item. Su UUID es estable y no cambia al reemitir.
3. **Unit generation (`commercial_unit_generations`):** asocia una unidad con una generación de su misma orden. La unicidad `(unit_id, generation_id)` impide asociaciones repetidas.
4. **Ticket (`tickets`):** referencia la unidad, la generación, la orden y el item. Solo puede existir un ticket por `(commercial_unit_id, generation_id)`, incluso si está cancelado; una sustitución exige una generación nueva.

Las claves foráneas compuestas incluyen `order_id` y, cuando corresponde, `order_item_id`. PostgreSQL impide asociar una unidad con una generación de otra orden o un ticket con un item que no sea el de su unidad. No se depende únicamente de validación Java.

### Emisión inicial

El pago confirmado crea la generación 1, una unidad por cada cantidad del item, su asociación de generación y un ticket `VALID` por unidad. Todo ocurre en la transacción de aplicación. La emisión repetida lee la generación activa y devuelve/no reproduce el resultado sin crear duplicados; las restricciones únicas son la última defensa ante carreras.

### Sustitución

La reemisión de una orden pagada:

1. Bloquea la orden y sus items siguiendo ADR-016.
2. Marca los tickets anteriores `CANCELLED` y la generación anterior `SUPERSEDED`.
3. Crea la generación siguiente y asociaciones para las mismas unidades.
4. Emite nuevos códigos UUID.

Un ticket `USED` no se reemite ni se cancela mediante este flujo. `VALID → USED` y `VALID → CANCELLED` son las únicas transiciones permitidas; `USED` y `CANCELLED` son finales.

### Migración evolutiva

La migración crea el modelo y backfill para tickets existentes: generación 1 por orden con tickets, unidades por cantidad, asociaciones y correspondencia determinista por item. Las columnas nuevas pasan a `NOT NULL` solo después del backfill. Nunca se modifica una migración ya publicada.

## Consecuencias

- La identidad de la unidad sobrevive a las reemisiones; el historial de tickets conserva generaciones anteriores.
- La unicidad de ticket por unidad/generación y las FK compuestas impiden duplicados y asociaciones cruzadas en base de datos.
- La reemisión es una capacidad de servicio interno en esta fase; no se inventa una ruta pública ni un flujo administrativo nuevo.
