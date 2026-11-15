# ADR-022: Contrato mínimo de evidencia de reembolsos del proveedor simulado

- **Estado:** Propuesto para dictamen del Tech Lead; no aprobado.
- **Fecha:** 2026-11-14
- **Fase:** 4; alcance H84-01.
- **Relación:** complemento propuesto de ADR-019, sin modificar sus decisiones aprobadas.
- **Naturaleza:** sistema de portafolio y simulación local. No hay ni habrá integración con un proveedor de pago real.

## Contexto y objetivo

La especificación técnica del proyecto establece expresamente un pago simulado, claridad del código, simplicidad y pruebas reproducibles. El modelo existente ya persiste tres identidades, digests, evidencia append-only, disposiciones por efecto comercial y fencing. No se requiere introducir un conciliador financiero ni nuevas estructuras para demostrar su funcionamiento.

## Alternativas evaluadas

| Tema | Opción conservadora | Opción ampliada | Recomendación |
| --- | --- | --- | --- |
| Cambio de `providerReference` para la misma operación | Digest inmutable; HTTP 409 | Enriquecimiento `null → valor` con nueva persistencia y reglas de concurrencia | Conservar el digest inmutable para el simulador |
| `REFUNDED` antes de captura | `NO_OP` y nuevo evento firmado posterior | Conciliación automática sin nuevo evento | Nuevo evento firmado, sin reconciliador automático |

## Contrato propuesto, pendiente de aceptación

1. La identidad estable es `(provider_code, merchant_account_id, identity_namespace, providerOperationId)`; `providerEventId` identifica cada notificación. El digest semántico incluye orden, tipo, referencia, importe y moneda, pero no el estado.
2. Si un reembolso se simula como una operación con varios estados (`PENDING`, `FAILED`, `REFUNDED`), la **primera notificación debe informar una referencia financiera no nula y estable**. Los siguientes eventos de esa operación conservan referencia, orden, tipo, importe y moneda; cambian únicamente estado y `providerEventId`.
3. El contrato de entrada existente admite `PENDING` y `FAILED` con referencia nula. Esas evidencias se aceptan y quedan registradas, pero **no se pueden promocionar dentro de la misma operación** a una notificación con nueva referencia: el digest cambia y el conflicto responde HTTP 409. No se altera este comportamiento para el portafolio.
4. Un `REFUNDED` recibido antes de la captura conserva evidencia y disposición `NO_OP`, sin afirmar un reembolso ejecutado. La captura posterior se procesa con sus validaciones normales; por sí sola no reconcilia el reembolso.
5. Para demostrar conciliación manual del simulador, se genera **otra notificación HMAC válida** con nuevo `providerEventId`, la misma identidad de operación y el mismo digest semántico. Tras la captura, el negocio se revalida. Si cambia el estado financiero, la nueva disposición es `APPLIED`; los duplicados sin efecto producen `NO_OP`.
6. Se mantienen restricciones UNIQUE, triggers append-only, HMAC, orden global ADR-016, timeout y fencing. No se mutan evidencias previas ni se automatiza un reenvío sin causa.

## Consecuencias

- Sin cambios de API, tablas, migraciones V14/V15, dependencias, workers o mecanismos de locks. Solo especificación del comportamiento existente y una regresión de integración.
- Una notificación inicial sin referencia no permite posteriormente vincular otra referencia al mismo `providerOperationId`. Es una limitación deliberada del simulador; se documenta, no se oculta.
- La conciliación depende de una **nueva** notificación firmada tras la captura; no es una garantía de conciliación automática. El flujo se demuestra sin servicios financieros externos.
- Si el alcance cambia hacia pagos reales, se reabre esta decisión con otro ADR antes de diseñar enriquecimiento o conciliación persistente.

## Validación y aprobación

- Evidencia histórica: CI #302 (carreras `PENDING/REFUNDED` y clasificación); CI #306 (recuperación de transición y fencing).
- Regresión adicional: `OrderPaymentFlowIT.should_applyPreviouslyEarlyRefund_whenNewSignedEventArrivesAfterCapture`, introducida en `8e2c8787a7fe5bb3c8c8d1571bf523295e413154`, ajustada a Spotless en `a8013f67bffe3a5826606469485335b61e13a93d`. [CI #309](https://github.com/dab960405/tickeringsystem/actions/runs/43647059382) `success`: 390 unitarias, 170 integraciones, `OrderPaymentFlowIT` 36/36 y `OperationWorkerIT` 12/12.
- El Tech Lead debe dictaminar este **contrato mínimo**. Su publicación como `Propuesto` no aprueba H84-01 ni cierra la Fase 4.

## Double-check externo previo al dictamen

La [matriz de verificación reproducible](../engineering-decision-verification.md) confronta el SHA `d92238a9f7d37dd0127db4029bb4fcb387886457` y CI #318 con PostgreSQL 17 (unicidad y `ON CONFLICT`), Java 21 (HMAC-SHA256 y comparación segura) e IETF RFC 9110 (§15.5.10, HTTP 409). Se comprobaron `PaymentEvidenceDigest`, `ProviderEventInboxService`, `PaymentWebhookService`, `OrderPaymentService` y `GlobalExceptionHandler`.

**Alcance de la conclusión:** las fuentes avalan el mecanismo de integridad, autenticidad y señalización de conflictos; **no definen el comportamiento de un proveedor de reembolso**, que es el contrato específico del simulador. Los eventos `PENDING`/`FAILED` con referencia nula siguen siendo válidos, pero un evento posterior de la **misma operación** con una referencia nueva genera 409 por el cambio del digest. Un `REFUNDED` anticipado solo se reevalúa con **otro** `providerEventId` firmado. No hay reconciliación automática ni protección temporal antirreplay, y no se afirma que existan.

## Referencias

- Especificación técnica Ticketing, secciones 1, 2, 10, 15, 22 y 24 (fuente funcional del proyecto).
- PostgreSQL 17: [restricciones de unicidad](https://www.postgresql.org/docs/17/ddl-constraints.html) y [INSERT ON CONFLICT](https://www.postgresql.org/docs/17/sql-insert.html), mecanismos utilizados para identidades únicas.
- Spring Framework: [rollback de transacciones declarativas](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html).
- La elección de **qué hacer ante pagos simulados** es una decisión del proyecto, no una recomendación comercial de PostgreSQL o Spring.
