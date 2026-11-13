# ADR-016: Orden global de locks y protocolo T → R

- **Estado:** Aceptado
- **Fecha:** 2026-11-13
- **Fase:** 4
- **Relación:** complementa ADR-010 y sustituye la estrategia de adquisición implícita de ADR-012; conserva `SKIP LOCKED`.

## Contexto

La confirmación, cancelación y expiración podían bloquear una reserva y después modificar su inventario (`R → T`), mientras otros flujos descuentan inventario antes de tocar la reserva (`T → R`). Entre varias instancias, esa inversión permite deadlocks y decisiones basadas en lecturas anteriores al bloqueo.

## Decisión

El orden global canónico es:

`E → D → PE → J → XA → V → N → CR → C → DS → T → R → O → P → I → U → EN → IS → K`

Los símbolos usados por este alcance se asignan así: `E` events, `PE` provider_events, `J` operation_jobs, `XA` operation_executions, `V` venues, `CR` operation_cycles, `C` operation_participations, `DS` operation_dispositions, `T` ticket_types, `R` reservations, `O` orders, `P` payments, `I` order_items, `U` commercial_units, `EN` tickets, `IS` semantic_applications y `K` operation_projections. `D` y `N` quedan reservados sin una tabla asociada en esta fase; no se les asigna un significado nuevo.

Una transacción solo puede adquirir locks de fila siguiendo una subsecuencia de ese orden. Las lecturas sin lock para descubrir identificadores no son adquisiciones, pero sus datos deben revalidarse después de bloquear. Los inserts de filas nuevas no sustituyen la revalidación de filas existentes.

### Protocolo de inventario y reservas

1. Descubrir candidatos sin lock, con lote máximo de 100 para expiración.
2. Extraer, ordenar y deduplicar los UUID de `ticket_types`.
3. Bloquear T en orden ascendente con `SELECT ... FOR UPDATE`.
4. Bloquear R en orden ascendente; el descubrimiento de workers usa `FOR UPDATE SKIP LOCKED`.
5. Revalidar propietario, estado, cantidad y relación `reservation.ticket_type_id`.
6. Obtener `clock_timestamp()` de PostgreSQL después de los locks.
7. Considerar válida una reserva solo si `expires_at > evaluation_time`; si `expires_at <= evaluation_time`, está expirada.
8. Cambiar reserva e inventario en la misma transacción. Cualquier inconsistencia revierte todo.

La creación bloquea/actualiza T antes de insertar R. Cancelación, liberación, confirmación, recuperación y expiración siguen T → R. La expiración no calcula su hora de decisión antes de bloquear.

### Inventario

Se conserva el `UPDATE` condicional de ADR-010 como última defensa de stock. El lock T coordina operaciones compuestas; no se reemplaza por sincronización Java. La restauración sigue condicionada a no superar `total_quantity` y solo ocurre con una transición válida de disposición de inventario.

### Workers

TX-CLAIM y TX-APPLY bloquean J antes que XA. La aplicación de negocio posterior sigue T → R → O → P → I → U → EN → IS → K. TX-PROJECT no retrocede a entidades anteriores: proyecta desde evidencia ya persistida y bloquea K al final.

## Consecuencias

- ADR-012 conserva el reparto con `SKIP LOCKED`, pero su consulta ya no es por sí sola el protocolo completo.
- Los locks se mantienen en transacciones cortas y con timeouts configurados.
- Las pruebas con PostgreSQL real deben cubrir carreras de confirmación/expiración/cancelación, dos workers y rollback.
- Una prueba de ausencia de deadlock no demuestra por sí sola el orden; los repositorios hacen explícitas las adquisiciones y las pruebas verifican sus invariantes.
