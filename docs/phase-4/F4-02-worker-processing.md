# F4-02 — Procesamiento recuperable de operaciones

- **Fecha:** 2026-11-13
- **Alcance:** H78, H80 y H82; ADR-016, ADR-018 y ADR-019.
- **Estado:** implementado y probado con PostgreSQL/Testcontainers. Fase 4 permanece abierta.

## Modelo persistido

La migración `V15__operation_cycles_jobs_and_projections.sql` crea:

- `operation_cycles`: ciclo numerado por agregado.
- `operation_participations`: relación inequívoca entre ciclo y evento recibido.
- `operation_jobs`: generación, vigencia, estado técnico, intentos y `fencing_token`.
- `operation_executions`: worker, token, lease y estado de ejecución.
- `operation_dispositions`: evidencia append-only `APPLIED`, `NO_OP` o `FAILED` por ejecución.
- `operation_projections`: estado técnico, comercial y financiero separado, con token y versión.

V14 conserva las tres identidades de proveedor/aplicación. La unicidad semántica es global: una participación o ciclo nuevo no vuelve a aplicar una operación ya registrada.

## Límites transaccionales

`OperationTransactionExecutor` crea transacciones independientes y aplica los valores validados de `OperationProperties`:

| Operación | Implementación | Reglas |
| --- | --- | --- |
| TX-RX | `ProviderEventInboxService` + `OperationCycleService` | Registra identidades, evento, participación, job y proyección inicial; no aplica negocio |
| TX-CLAIM | `OperationJobService.claim` | Bloquea J antes de escribir XA, invalida leases vencidos y emite un token nuevo |
| TX-APPLY | `PaymentOperationService.apply` | Revalida J → XA, token y lease con `clock_timestamp()`; aplica la operación semántica y negocio en la misma transacción |
| TX-PROJECT | `PaymentOperationService.project` | Lee evidencia confirmada y actualiza K sin repetir la operación comercial |

Cada transacción configura `lock_timeout` y `statement_timeout` mediante `SET LOCAL`/`set_config`. Los tiempos máximos proceden de `tx-rx-timeout`, `tx-claim-timeout`, `tx-apply-timeout` y `tx-project-timeout`.

## Leases, fencing y recuperación

- Dos workers no pueden reclamar el mismo job mientras exista un lease activo.
- `renewLease` solo renueva una ejecución vigente con token actual.
- La expiración permite otro claim con token superior; el worker antiguo no puede aplicar ni proyectar.
- `replace` marca el job anterior `REPLACED`, lo deja no efectivo, invalida sus ejecuciones y crea una generación superior.
- Un fallo de aplicación revierte la aplicación semántica y registra una disposición `FAILED`. El job vuelve a `PENDING` con backoff o queda `FAILED` al agotar `max-attempts`.
- Si el proceso se interrumpe después de APPLY, la recuperación crea otra ejecución, registra `NO_OP` por idempotencia semántica y completa la proyección sin duplicar tickets ni efectos financieros.
- `OperationRecoveryWorker` procesa lotes con `batch-size` cada `recovery-interval`. El perfil `test` desactiva el scheduler para evitar claims desde otros contextos Spring que comparten contenedores; `OperationRecoveryWorkerTest` verifica el lote y la continuación tras fencing, y los tests de integración controlan la recuperación explícitamente. Producción conserva `recovery-enabled=true` por defecto.

## Evidencia de pruebas

`OperationWorkerIT` ejecuta 8 casos con PostgreSQL real:

1. Dos workers compitiendo por el mismo job.
2. Renovación de lease.
3. Reemplazo de job y fencing de la ejecución anterior.
4. Lease vencido y rechazo del worker antiguo.
5. Recuperación de proyección sin reaplicar negocio.
6. Reintentos y agotamiento sin aplicación semántica.
7. Nueva operación de ciclo sin repetir la aplicación semántica global.
8. Webhook duplicado concurrente con una sola emisión.

`OrderPaymentFlowIT` ejecuta 10 casos de extremo a extremo para compra, fallo, pagos tardíos, reembolsos, identidades persistentes y evidencia append-only.

## Pendientes

H77, H81 y la auditoría global H75/H76/H83 siguen abiertos. H79/H84 deben someterse a la validación integral final. Este documento no representa el cierre formal de Fase 4 ni autoriza Fase 5.
