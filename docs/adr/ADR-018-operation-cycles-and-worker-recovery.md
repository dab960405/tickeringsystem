# ADR-018: Ciclos de operación, leases, fencing y proyecciones

- **Estado:** Aceptado
- **Fecha:** 2026-11-13
- **Fase:** 4
- **Resuelve:** H-78

## Contexto

Recibir un webhook, ejecutar un trabajo y aplicar una operación comercial son hechos distintos. Un timeout no demuestra que la transacción anterior no haya confirmado, y un worker que pierde su lease no debe seguir escribiendo. Tampoco una ejecución técnicamente completada equivale a un pago o reembolso financieramente confirmado.

## Modelo decidido

- `operation_cycles` (**CR**): ciclo numerado para un agregado de negocio.
- `operation_participations` (**C**): participación de un evento del proveedor en un ciclo; FK inequívoca a ciclo y evento.
- `operation_jobs` (**J**): trabajo de una participación, con generación, `effective`, `fencing_token`, estado técnico y contador de intentos. Un índice parcial permite como máximo un job efectivo por participación.
- `operation_executions` (**XA**): claim por worker, con `worker_id`, token, `lease_expires_at` y estado técnico `CLAIMED`, `APPLIED`, `PROJECTED`, `COMPLETED`, `FAILED` o `INVALIDATED`.
- `operation_dispositions` (**DS**): resultado persistente y append-only de una ejecución; una disposición por ejecución.
- `operation_projections` (**K**): lectura agregada por participación con campos separados `technical_status`, `commercial_status` y `financial_status`, token y versión.

La aplicación semántica se persiste aparte en `semantic_applications` (ADR-019) y es global a ciclos, participaciones, jobs y ejecuciones.

## Límites transaccionales

### TX-RX

Verifica la identidad/digest, registra evento y operación del proveedor y crea la participación/job inicial si son necesarios. No aplica cambios comerciales ni financieros.

### TX-CLAIM

Bloquea J con `FOR UPDATE SKIP LOCKED`, comprueba que siga efectivo, invalida ejecuciones cuyo lease venció, incrementa `fencing_token` e inserta XA con lease nuevo. Dos workers no reciben el mismo token efectivo.

### TX-APPLY

Bloquea J → XA y revalida job efectivo, token y lease con `clock_timestamp()`. Después ejecuta la operación de negocio en el orden de ADR-016. Registra la aplicación semántica y la disposición antes del commit. Un token antiguo, lease vencido o job reemplazado aborta toda escritura.

Si la aplicación semántica ya existe con el mismo digest, el resultado es no-op recuperable; no se repiten efectos. Un digest distinto genera conflicto y rollback.

### TX-PROJECT

Lee la evidencia confirmada y actualiza K sin repetir la operación comercial. Marca la ejecución `PROJECTED`/`COMPLETED` solo después de proyectar. Un fallo entre APPLY y PROJECT se recupera proyectando la evidencia existente, no reaplicando el pago.

## Reemplazo y recuperación

Reemplazar un job lo marca `REPLACED`, `effective=false`, invalida sus ejecuciones vigentes y crea otro job con generación superior. El índice parcial y los tokens impiden que el job anterior continúe. Un nuevo ciclo puede crear otra participación/job para la misma operación, pero la unicidad semántica global impide una segunda aplicación.

Los reintentos respetan backoff y `max_attempts`; agotarlos deja el job `FAILED` y evidencia del fallo, nunca una operación financiera ficticiamente completada.

## Parámetros y acceso

Los valores se definen en `docs/phase-4/F4-01-operational-decisions.md` y en `OperationProperties`. Los workers son componentes internos, sin endpoint público. Las evidencias no exponen secretos, firmas HMAC ni datos personales completos.

## Consecuencias

- La recuperación es idempotente y funciona entre instancias mediante PostgreSQL.
- El estado técnico no se mezcla con el comercial o financiero.
- No se introduce Kafka, outbox publisher, SQS ni otra funcionalidad de Fase 5; el modelo coordina el webhook simulado síncrono y su recuperación local.
