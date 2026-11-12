# ADR-011: Idempotencia de creación de órdenes y procesamiento de webhooks

- **Estado:** Aceptado
- **Fecha:** 2026-11-06
- **Fase:** 3

## Contexto

Los clientes pueden reintentar una petición por timeout o pérdida de respuesta. Los proveedores de pago también pueden reenviar callbacks. Sin idempotencia, un reintento puede crear órdenes/pagos duplicados, emitir tickets más de una vez o aplicar transiciones incompatibles.

## Decisión: órdenes

- El cliente envía `Idempotency-Key`; la API exige una clave no vacía de hasta 100 caracteres.
- El ámbito de unicidad es el usuario: `UNIQUE(user_id, idempotency_key)` en PostgreSQL. No se confía únicamente en una consulta previa de aplicación.
- Si la clave ya existe y apunta a la misma reserva, la API devuelve la orden previamente creada y no crea otro pago.
- Si la clave se reutiliza con una reserva diferente, la petición se rechaza como conflicto (`409`). Una clave no puede cambiar de significado.
- La orden conserva el precio en `order_items.unit_price` y cantidad/moneda; el importe no se recalcula desde el catálogo en los reintentos.

La restricción única es la última línea de defensa ante dos solicitudes concurrentes que consulten la clave antes de que cualquiera haya insertado la orden. La aplicación debe traducir el conflicto de unicidad a una respuesta de conflicto/relectura cuando esa carrera se produzca.

### Carrera concurrente con la misma clave y reserva

En la carrera simultánea con la misma `Idempotency-Key` y la misma reserva, la evidencia del run #116 sugiere que una de las peticiones recibe `409` por la violación de `uq_orders_user_idempotency_key`, en lugar del `200` de reproducción. Esta conclusión se infiere del log del job de CI (que registra la violación de unicidad); los códigos HTTP individuales de ambas peticiones no quedan registrados, por lo que no puede afirmarse que el log confirme directamente el `409`. El test de concurrencia acepta explícitamente `200` o `409` para esa petición concurrente. El cliente puede reintentar y obtener `200`; un reintento posterior a la finalización de la primera petición sí devuelve `200` de reproducción.

## Decisión: webhooks de pago

- El endpoint exige `X-Signature`, calculada como HMAC-SHA256 hexadecimal del **cuerpo HTTP original** con el secreto compartido.
- La firma se compara en tiempo constante. Un cuerpo alterado o una firma inválida se rechaza antes de deserializar/procesar el evento.
- La transición se aplica en una transacción sobre orden, pago y reserva. Un éxito válido confirma la reserva, marca orden/pago como pagados y emite tickets. Si la reserva expiró y no puede recuperarse el stock, se registra el pago recibido y el flujo lo marca reembolsado; no se emiten tickets.
- La repetición de un callback de éxito para una orden ya pagada (o reembolsada por falta de stock) es no-op únicamente si conserva la misma `provider_reference`. Una referencia distinta produce conflicto.
- La repetición de un callback fallido para una orden ya cancelada con pago fallido es no-op. Las transiciones fuera de estado pendiente se rechazan.

La base de datos refuerza la unicidad con `UNIQUE(orders.user_id, orders.idempotency_key)`, `UNIQUE(payments.order_id)` y `UNIQUE(payments.provider_reference)`.

## Consecuencias y límites

- Los reintentos normales de creación de orden y los callbacks repetidos compatibles no duplican sus efectos de negocio.
- La firma HMAC autentica integridad/origen, pero no es por sí misma un identificador de evento ni evita replay.
- **Limitación conocida:** el contrato actual no recibe ni persiste un ID único de evento del proveedor. La deduplicación de webhook se apoya en el estado y la referencia del pago; callbacks fuera de orden o una repetición fallida después de un éxito pueden terminar en conflicto.
- Si el gateway real ofrece `event_id`, una futura evolución debe añadir un inbox persistente con restricción única por proveedor/evento, política de retención y respuesta idempotente. No se simula que esa capacidad ya exista.
- La emisión de tickets comprueba si existen tickets para los items de la orden antes de emitirlos; la transacción y las restricciones de datos siguen siendo necesarias para proteger la operación ante concurrencia.
