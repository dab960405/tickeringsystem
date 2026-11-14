# F4-04 — Validación integral de operaciones y reembolsos

- **Fecha:** 2026-11-14
- **Alcance:** H78, H79, H80, H82 y H84; ADR-018/019 y F4-01/F4-02.
- **Línea base de evidencia:** `88a8f717d4c3079d124166dff487167e373fec6d`, GitHub Actions CI #295.
- **Estado:** evidencia automatizada satisfactoria para los escenarios actuales. **No se propone el cierre de H84 ni el cierre formal de Fase 4** por la inconsistencia H84-01 detallada abajo.

## Evidencia sobre el SHA de referencia

CI #295: [workflow](https://github.com/dab960405/tickeringsystem/actions/runs/43634870599).

| Suite o control | Resultado |
| --- | --- |
| Surefire de `ticketing-api` | 390; 0 fallos, 0 errores, 0 omitidos |
| Failsafe de `ticketing-api` | 152; 0 fallos, 0 errores, 0 omitidos |
| `OrderPaymentFlowIT` | 20/20 |
| `OperationWorkerIT` | 10/10 |
| `OperationPropertiesTest` | 16/16 |
| Spotless | 220 archivos Java de `ticketing-api` limpios |
| JaCoCo | checks del build superados |
| Maven | `BUILD SUCCESS` |

Las cuatro pruebas añadidas tras CI #293 documentan: reembolso `FAILED` sin confirmación financiera ni cancelación de ticket; estado `PENDING_RECONCILIATION` sin fingir reembolso; evidencia `REFUNDED` fuera de orden antes de captura; y recuperación de proyección de reembolso por worker sin repetir efectos en un nuevo claim. Las suites de concurrencia, reservas, emisión y expiración también forman parte del mismo `verify`.

La validación H82 de `OperationPropertiesTest` cubre límites y defaults; `OperationTransactionExecutor` configura `lock_timeout` y `statement_timeout` con `SET LOCAL`. El commit `522a2dcf706122760ffee4fd46e8027e41e2723d` añadió una prueba que consulta esos valores dentro de TX-RX/CLAIM/APPLY/PROJECT con PostgreSQL real. CI #296 terminó verde: 390 unitarios y 153 integraciones (`OperationWorkerIT` 11/11), sin fallos; Spotless y JaCoCo correctos; `BUILD SUCCESS`. [Run #296](https://github.com/dab960405/tickeringsystem/actions/runs/43635133428).

## H84-01 — identidad semántica y evolución de estado de reembolso

**Estado:** hallazgo técnico identificado mediante trazado estático de código; falta reproducción específica con PostgreSQL y dictamen arquitectónico del Tech Lead. No se asigna ni modifica criticidad unilateralmente.

### Secuencia que debe validarse

1. Una compra pagada solicita reembolso, quedando `refund_status=REQUESTED`.
2. El proveedor envía `PAYMENT_REFUND` con `status=PENDING`, `providerOperationId=R`, `providerEventId=E1`, referencia, importe y moneda válidos. La orden pasa a `PENDING_RECONCILIATION`; la operación R se registra en `semantic_applications`.
3. El mismo proveedor envía un segundo evento `E2` para la **misma operación R**, con `status=REFUNDED` y sin cambiar orden, referencia, importe ni moneda.
4. Se debe decidir si E2 puede materializar la transición financiera o si la repetición de R debe ser un no-op estricto. En ambos casos, la disposición persistida debe coincidir con los efectos realmente aplicados.

### Inconsistencia deducible del código

- `PaymentEvidenceDigest.semantic` calcula el digest de `orderId`, `operationKind`, `providerReference`, importe y moneda; **no incluye `status`**. Por tanto, E1 y E2 presentan el mismo digest semántico.
- `ProviderEventInboxService.registerOperation` acepta la identidad R ya registrada cuando el digest coincide; `registerEvent` acepta E2 como un evento nuevo si su `providerEventId` es distinto.
- `OperationCycleService.register` genera una participación/job para E2; el job puede llegar a APPLY.
- `SemanticApplicationService.apply` ejecuta `application.run()` **antes** de intentar el `INSERT ... ON CONFLICT DO NOTHING` en `semantic_applications`. Para R repetida, el conflicto devuelve un resultado semántico `false` (NO_OP), **después** de ejecutar el servicio comercial.
- `OrderPaymentService.processRefund` permite que E2 (`REFUNDED`) confirme el reembolso, transforme el pago a `REFUNDED` y cancele tickets si el pago era `SUCCEEDED`, incluso si E1 ya había consumido R con `PENDING`.

De este flujo se deduce el riesgo de **evidencia de aplicación `NO_OP` con un cambio financiero efectivo en la misma transacción**. La suite actual cubre E1 y la confirmación por separado, pero no esta progresión sobre un `providerOperationId` idéntico. La evidencia de CI verde no resuelve el contrato de esta carrera.

### Decisión requerida

El Tech Lead debe determinar cuál de estas semánticas representa ADR-019:

- **Identidad inmutable por aplicación:** la primera aplicación de R fija la operación. Una notificación posterior de R no puede producir efectos, aunque cambie su estado. Debe definirse cómo se registran y concilian las transiciones posteriores sin perder evidencia del proveedor.
- **Operación financiera evolutiva:** R puede pasar de `PENDING`/`FAILED` a `REFUNDED` mediante nuevos eventos. En ese caso se necesita una identidad/versionado para cada aplicación de transición, idempotencia independiente por transición y disposiciones que reflejen exactamente cuándo hubo efectos. Puede exigir una revisión aprobada de ADR-019 y del esquema.

No se debe resolver este hallazgo agregando `status` al digest de forma unilateral, eliminando la unicidad existente ni cambiando el orden de locks de ADR-016.

### Casos de aceptación pendientes

- `PENDING → REFUNDED` con el mismo `providerOperationId` y eventos distintos.
- `FAILED → REFUNDED` con el mismo `providerOperationId`.
- Duplicación concurrente de `REFUNDED` y combinación `REFUNDED → PENDING/FAILED` fuera de orden.
- Comparar `payment.status`, `orders.refund_status`, tickets, `semantic_applications`, `operation_dispositions` y proyección por participación; una disposición `NO_OP` nunca debe ocultar efectos financieros nuevos.
- Fencing y recuperación de un job interrumpido durante esa progresión.

## Dictamen de avance

- H78: workers, fencing y recuperación probados en CI para los escenarios cubiertos; sin autorización de cierre formal.
- H79: captura tardía, gracia, inventario e imposibilidad de entrega probados en la suite de pago; la política sigue regida por ADR-019.
- H80: identidades persistentes, append-only e idempotencia probadas para duplicados equivalentes; H84-01 requiere fijar semántica de cambio de estado de una misma operación.
- H82: límites operativos y timeouts PostgreSQL verificados en CI #296 sobre el SHA exacto; queda la aceptación del expediente integral, no un defecto técnico detectado en H82.
- H84: pruebas funcionales de FAILED, PENDING, fuera de orden y recuperación de reembolsos verificadas; **no se propone cierre** hasta resolver H84-01.

La excepción cross-module de `EventCancellationService` permanece pendiente del dictamen del Tech Lead para el cierre formal de Fase 3. Este documento no aprueba cambios de arquitectura, no cierra Fase 4 y no habilita Fase 5.
