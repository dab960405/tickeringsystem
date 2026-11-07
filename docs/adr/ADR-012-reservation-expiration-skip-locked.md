# ADR-012: Expiración concurrente de reservas con FOR UPDATE SKIP LOCKED

- **Estado:** Aceptado
- **Fecha:** 2026-11-06
- **Fase:** 3

## Contexto

La expiración periódica debe procesar reservas vencidas y devolver sus unidades al inventario. En despliegues con más de una instancia de la API, todos los schedulers pueden ejecutarse al mismo tiempo. Un `SELECT` normal permitiría que dos workers seleccionaran la misma reserva y ambos intentaran liberar su stock.

## Decisión

Reclamar lotes de hasta 100 reservas mediante una consulta PostgreSQL equivalente a:

```sql
SELECT *
FROM reservations
WHERE status = 'ACTIVE'
  AND expires_at < :now
ORDER BY expires_at
LIMIT :limit
FOR UPDATE SKIP LOCKED;
```

El método `ReservationRepository.findExpiredForUpdate` se ejecuta dentro de la transacción del job. `SKIP LOCKED` hace que cada worker omita filas que otro worker ya bloqueó y continúe con otras filas elegibles. Así se distribuye el trabajo sin que los schedulers esperen por las mismas reservas.

Por cada fila reclamada, el servicio ejecuta una transición condicional:

```sql
UPDATE reservations
SET status = 'EXPIRED', updated_at = CURRENT_TIMESTAMP
WHERE id = :id
  AND status = 'ACTIVE'
  AND expires_at < :now;
```

El inventario se libera **solo si el UPDATE afecta exactamente una fila**. La selección/bloqueo, el cambio de estado y la restauración de stock ocurren en la misma transacción.

## Alternativas

- **Un scheduler único:** simple, pero crea un punto único de ejecución y limita escalado/recuperación.
- **SELECT sin bloqueo:** puede entregar la misma fila a varios workers.
- **Bloquear sin SKIP LOCKED:** evita duplicidad, pero los workers pueden quedarse esperando por filas que otro worker ya procesa, reduciendo throughput del lote.
- **Cambiar estado y liberar stock en transacciones separadas:** abre una ventana de inconsistencia si falla uno de los pasos.

## Consecuencias

- Workers concurrentes reclaman conjuntos disjuntos de filas bloqueadas.
- Si el proceso falla antes del commit, PostgreSQL revierte el cambio de estado y la liberación de stock; la fila podrá procesarse en la siguiente ejecución.
- La transición condicional protege la devolución de inventario contra una segunda ejecución lógica.
- El índice parcial `ix_reservations_active_expires_at` reduce el conjunto que debe inspeccionar el job.
- El tamaño del lote es 100 y el intervalo de ejecución es configurable mediante `ticketing.reservation.expiration-fixed-delay-ms`.
- Debe mantenerse el trabajo dentro de una transacción corta. La prueba `ReservationExpirationIT` y la ejecución multi-instancia en PostgreSQL deben formar parte de la verificación con Docker/CI.
