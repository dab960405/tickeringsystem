# F4-04 — Validación integral de operaciones y reembolsos

- **Fecha:** 2026-11-14
- **Alcance:** H78, H79, H80, H82 y H84; ADR-018/019 y F4-01/F4-02.
- **Línea base de evidencia:** `88a8f717d4c3079d124166dff487167e373fec6d`, GitHub Actions CI #295; última validación ampliada: `30517047d80e7b861874b0a81284fe921ba1e068`, CI #302.
- **Estado:** clasificación de disposiciones, progresión `FAILED → REFUNDED` y carrera `PENDING`/`REFUNDED` validadas en CI #302. Referencias evolutivas, conciliación fuera de orden y recuperación interrumpida permanecen pendientes. **No se declara H84 ni Fase 4 cerrados.**

## Evidencia histórica y de la corrección

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

### Verificación de la corrección — CI #298

El commit `4acb8240e951cebc2ac3178d3543db1a74549d23` separó el resultado `PaymentEffectOutcome` del conflicto de inserción en `semantic_applications`. `PaymentOperationService` ahora registra `APPLIED` / `NO_OP` y su payload JSON en función del efecto comercial real; `OrderPaymentService` mantiene el método público `process` y añade `processWithOutcome`. No hubo migraciones ni alteración del orden de adquisición de locks.

[CI #298](https://github.com/dab960405/tickeringsystem/actions/runs/43640665242) terminado en `success` sobre ese SHA: **390 pruebas unitarias, 157 pruebas de integración**, `OrderPaymentFlowIT` **24/24**, `OperationWorkerIT` **11/11**, Spotless 221 Java limpios, JaCoCo y `BUILD SUCCESS`. Los cuatro casos nuevos prueban `PENDING → REFUNDED` con operación y referencia idénticas, reembolso prematuro sin efecto, repetición de `REFUNDED` con evento nuevo y `REFUNDED → PENDING` sin regresión. En todos los casos la disposición coincide con el cambio comercial, y la operación conserva una sola fila en `semantic_applications`.

### Ampliación de regresiones y carrera — CI #302

Se agregaron `should_confirmRefundAfterFailedEvidenceForSameProviderOperation` y `should_notLoseRefund_whenPendingAndRefundedArriveConcurrently` (`@RepeatedTest(10)`), reutilizando `providerOperationId` y la referencia financiera dentro de cada caso, con referencias de captura/reembolso distintas entre fixtures. La prueba concurrente verifica `REFUNDED`, disposición `APPLIED` del evento final, `APPLIED`/`NO_OP` de `PENDING` según interleaving, un solo registro de `semantic_applications` y ticket cancelado.

La evidencia CI tiene trazabilidad completa: CI #300 falló por referencias fijas incompatibles con `uq_payments_provider_reference` entre repeticiones; CI #301 ejecutó **390 unitarias y 168 integraciones sin errores**, pero falló exclusivamente en un cambio de formato Spotless; se aplicó el diff exacto en `30517047d80e7b861874b0a81284fe921ba1e068`. [CI #302](https://github.com/dab960405/tickeringsystem/actions/runs/43642005154) terminó en **success** sobre este SHA: **390 unitarias, 168 integraciones, `OrderPaymentFlowIT` 35/35, `OperationWorkerIT` 11/11, Spotless 221 Java limpios, JaCoCo y `BUILD SUCCESS`**. No se omitió ni debilitó ninguna prueba.

## H84-01 — identidad semántica y evolución de estado de reembolso

**Estado:** el defecto original de clasificación y la carrera `PENDING`/`REFUNDED` están validados con PostgreSQL en CI #302; quedan la definición contractual de referencia mutable, conciliación de evidencia anticipada y prueba de recuperación interrumpida. Se conserva la criticidad original sin reclasificación unilateral.

### Secuencia validada en CI #298

1. Una compra pagada solicita reembolso, quedando `refund_status=REQUESTED`.
2. El proveedor envía `PAYMENT_REFUND` con `status=PENDING`, `providerOperationId=R`, `providerEventId=E1`, referencia, importe y moneda válidos. La orden pasa a `PENDING_RECONCILIATION`; la operación R se registra en `semantic_applications`.
3. El mismo proveedor envía un segundo evento `E2` para la **misma operación R**, con `status=REFUNDED` y sin cambiar orden, referencia, importe ni moneda.
4. E2 materializa la transición financiera y la disposición queda `APPLIED`, pese a que ambas notificaciones comparten una sola identidad en `semantic_applications`. Se validó con referencia estable.

### Diagnóstico del defecto original (SHA `23893305`)

- `PaymentEvidenceDigest.semantic` calcula el digest de `orderId`, `operationKind`, `providerReference`, importe y moneda; **no incluye `status`**. Por tanto, E1 y E2 presentan el mismo digest semántico.
- `ProviderEventInboxService.registerOperation` acepta la identidad R ya registrada cuando el digest coincide; `registerEvent` acepta E2 como un evento nuevo si su `providerEventId` es distinto.
- `OperationCycleService.register` genera una participación/job para E2; el job puede llegar a APPLY.
- `SemanticApplicationService.apply` ejecuta `application.run()` **antes** de intentar el `INSERT ... ON CONFLICT DO NOTHING` en `semantic_applications`. Para R repetida, el conflicto devuelve un resultado semántico `false` (NO_OP), **después** de ejecutar el servicio comercial.
- `OrderPaymentService.processRefund` permite que E2 (`REFUNDED`) confirme el reembolso, transforme el pago a `REFUNDED` y cancele tickets si el pago era `SUCCEEDED`, incluso si E1 ya había consumido R con `PENDING`.

Este flujo justificó la prueba que fallaría en el SHA anterior: **`NO_OP` con un efecto financiero real**, y también `APPLIED` sin efecto para una notificación `REFUNDED` anticipada. La corrección y las pruebas correspondientes están validadas en CI #298; el párrafo anterior es diagnóstico histórico y no describe el código actual.

### Semántica implementada y decisiones pendientes

- **Decisión aplicada:** una operación de proveedor puede comunicar estados sucesivos sin crear otra identidad. La disposición de la ejecución describe el efecto comercial observado bajo locks (`APPLIED`/`NO_OP`), mientras la fila única de `semantic_applications` preserva la identidad y digest originales. La aclaración queda en ADR-019.
- **Restricciones conservadas:** `UNIQUE(provider_operation_id)`, `idempotency_key VARCHAR(100)`, trigger append-only y orden global ADR-016; no se añadió `#status`, columna ni nuevo lock.
- **Pendiente de contrato externo:** `PENDING` con `providerReference=null` seguido de `REFUNDED` con referencia nueva actualmente se rechaza por digest distinto (HTTP 409). No se ha autorizado relajar esa protección ni definir una política de enriquecimiento de referencia.
- **Pendiente de recuperación:** decidir si un `REFUNDED` anterior a la captura queda solo archivado o debe reconciliarse automáticamente al recibir evidencia de captura.
- **Pruebas completadas en CI #302:** `FAILED → REFUNDED` con identidad estable y diez carreras `PENDING`/`REFUNDED`, sin duplicación de efecto financiero.
- **Pruebas por completar:** recuperación/fencing durante la transición de estado y comprobación de disposición/proyección tras interrupción; además de los casos de referencia evolutiva cuando el Tech Lead defina el contrato.

Estos puntos requieren contrato y dictamen específico del Tech Lead. La corrección de etiquetas y disposiciones no autoriza por sí sola el cierre de H84/Fase 4.

## Dictamen de avance

- H78: workers, fencing y recuperación probados en CI para los escenarios cubiertos; sin autorización de cierre formal.
- H79: captura tardía, gracia, inventario e imposibilidad de entrega probados en la suite de pago; la política sigue regida por ADR-019.
- H80: identidades persistentes, append-only e idempotencia probadas; H84-01 ya registra disposiciones por efecto real sin romper unicidad de operación; falta la política de referencia mutable y conciliación.
- H82: límites operativos y timeouts PostgreSQL verificados en CI #296 sobre el SHA exacto; queda la aceptación del expediente integral, no un defecto técnico detectado en H82.
- H84: cuatro regresiones iniciales (CI #298), `FAILED → REFUNDED` y diez carreras de estado (`OrderPaymentFlowIT` 35/35 en CI #302); **no se propone cierre** hasta definir referencia evolutiva, conciliación anticipada y recuperación interrumpida.

La excepción cross-module de `EventCancellationService` permanece pendiente del dictamen del Tech Lead para el cierre formal de Fase 3. Este documento no aprueba cambios de arquitectura, no cierra Fase 4 y no habilita Fase 5.
