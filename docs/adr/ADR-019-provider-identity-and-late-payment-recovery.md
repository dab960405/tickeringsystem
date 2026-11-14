# ADR-019: Identidad del proveedor, idempotencia semántica y recuperación tardía

- **Estado:** Aceptado
- **Fecha:** 2026-11-13
- **Fase:** 4
- **Resuelve:** H-79 y H-80
- **Relación:** evoluciona ADR-011 y sustituye el reembolso local inmediato de ADR-009.
- **Aclaración H84-01 (2026-11-14):** diferencia identidad semántica de operación y efecto de cada ejecución, sin alterar V14/V15.

## Tres identidades independientes

1. **Evento recibido:** `(provider_code, merchant_account_id, provider_event_id)` en `provider_events`.
2. **Operación del proveedor:** `(provider_code, merchant_account_id, identity_namespace, provider_operation_id)` en `provider_operations`.
3. **Aplicación semántica:** `(application_scope, operation_kind, idempotency_key)` en `semantic_applications`.

Cada tabla tiene una restricción única correspondiente. Un evento referencia su operación; una aplicación referencia la operación y conserva `payload_digest` SHA-256. La recepción, la ejecución técnica y la aplicación semántica tienen evidencias separadas.

Para el proveedor simulado, `provider_code=SIMULATED`, `merchant_account_id=default` e `identity_namespace=payments.v1` son configurables. El contrato del webhook exige `providerEventId`, `providerOperationId`, `operationKind`, `providerReference`, importe y moneda para evidencia financiera. La firma HMAC autentica el cuerpo, pero no sustituye ninguna de estas identidades. `PAYMENT_REFUND` admite `REFUNDED` para confirmar, `FAILED` para registrar el fallo y `PENDING` para dejar la operación en conciliación; ninguno de los dos últimos estados cambia el pago a `REFUNDED`.

## Catálogo y digest

`operation_kind` admite `PAYMENT_CAPTURE`, `PAYMENT_FAILURE` y `PAYMENT_REFUND`. El ámbito es `order:{orderId}` y la clave semántica estable es `providerOperationId`. El digest se calcula sobre una representación canónica de orden, tipo, referencia, importe y moneda.

- Misma identidad y digest: la operación es conocida, pero un nuevo evento debe revalidarse frente al estado comercial actual. Puede aplicar una transición nueva (por ejemplo, `PENDING → REFUNDED`) o producir `NO_OP` si no cambia el negocio; no se infiere el resultado de la unicidad de `semantic_applications`.
- Misma identidad y digest distinto: HTTP 409, sin aplicar efectos.
- Nuevo evento para operación conocida: evidencia de recepción nueva, aplicación semántica única.
- Nuevo ciclo, participación, worker o ejecución: no cambia la identidad semántica.

### Disposición de la ejecución y estado comercial (aclaración H84-01)

`semantic_applications` registra una sola identidad semántica por `providerOperationId`: su inserción, repetición o conflicto no determina por sí sola si un evento aplicó efectos de negocio. `OrderPaymentService.processWithOutcome` devuelve `PaymentEffectOutcome.APPLIED` cuando una transición comercial/financiera válida cambia el estado y `NO_OP` cuando no se produce efecto. `PaymentOperationService` persiste esa clasificación y el campo JSON `result` en `operation_dispositions` para cada ejecución.

Una misma operación de reembolso, con referencia, importe y moneda constantes, puede producir `APPLIED` para `PENDING` y después `APPLIED` para `REFUNDED`. Otro evento `REFUNDED` equivalente no repite el cambio y recibe `NO_OP`. `REFUNDED` recibido antes de poder aplicar un reembolso se conserva como evidencia con `NO_OP`, no como reembolso ejecutado. La sincronización conserva E→J→XA→O→P→EN→IS→K de ADR-016; no cambia el índice único por operación ni el esquema.

**Límite pendiente:** el digest estable sigue incluyendo `providerReference`. Una operación inicialmente informada como `PENDING` con referencia nula y posteriormente `REFUNDED` con referencia nueva produce conflicto de digest. El proveedor simulado requiere un contrato expreso para ese caso antes del cierre integral de H84. Asimismo debe definirse la conciliación de evidencia `REFUNDED` recibida antes de la captura. Este complemento no constituye aprobación del cierre de Fase 4.

Las filas de evento, operación, aplicación y disposición son append-only mediante triggers que rechazan UPDATE/DELETE. Los estados cambiantes viven en jobs/ejecuciones y entidades de negocio.

## Política H-79

### Confirmación normal

Una reserva `ACTIVE` solo es válida si `expires_at > clock_timestamp()` después de T → R. El pago `SUCCEEDED` debe coincidir exactamente con el importe/moneda de la orden. La confirmación cambia la disposición de inventario a `CONSUMED` y emite una sola generación de tickets.

### Expiración y liberación

Expirar o cancelar una reserva cambia su disposición de `HELD` a `RELEASED` y devuelve stock exactamente una vez. Confirmar una reserva vigente cambia `HELD` a `CONSUMED` sin descontar otra vez. Recuperar una reserva `RELEASED` descuenta stock antes de pasar a `CONSUMED`.

### Pago exitoso tardío

Se permite recuperar una reserva expirada/liberada solo si:

- la orden y el pago pertenecen a esa misma reserva y usuario;
- existe evidencia `SUCCEEDED` válida del proveedor;
- el evento no ha comenzado;
- han transcurrido como máximo 15 minutos desde `expires_at` (`recovery-grace`);
- hay stock suficiente para toda la cantidad.

Si se cumplen las condiciones, se recupera atomicamente y se emite la generación inicial. Si no hay stock, venció la gracia, el evento comenzó o la reserva fue cancelada, **no se emiten tickets ni se declara un reembolso ejecutado**:

- el pago registra la evidencia financiera `SUCCEEDED`;
- la orden conserva su estado comercial compatible (`PAID` si estaba pendiente; una orden ya `CANCELLED` no reaparece como pagada);
- `fulfillment_status=UNFULFILLABLE` y `refund_status=REQUIRED` dejan explícita la obligación pendiente.

### Reembolso con evidencia

La cancelación de una orden pagada antes del evento crea `refund_status=REQUESTED`; no cambia localmente el pago a `REFUNDED`. Solo un evento `PAYMENT_REFUND` con referencia/importe/moneda válidos confirma el reembolso: pago `REFUNDED`, `refund_status=CONFIRMED`, tickets cancelados y orden `REFUNDED` cuando su estado comercial previo es `PAID`.

Un evento financiero fuera de orden se conserva como evidencia y se dispone como ignorado/no-op; no sobrescribe un éxito con un fallo ni un reembolso con una captura. Un conflicto de digest o importe requiere tratamiento explícito y no produce cambios de negocio.

## Consecuencias

- H-79 es obligatorio para el cierre: la ausencia de recuperación o la declaración de reembolso sin evidencia mantiene el dictamen rechazado.
- No se integra una pasarela real. El contrato simulado representa evidencia del proveedor y permite probar captura/reembolso sin servicios externos.
- ADR-009 conserva la decisión de no devolver stock automáticamente tras reembolso; se sustituye únicamente la transición local inmediata a `REFUNDED`.
