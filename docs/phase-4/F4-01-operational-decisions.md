# F4-01 — Decisiones operativas y compatibilidad

- **Fecha:** 2026-11-13
- **SHA de línea base:** `c6917e152c5440fbd346b1f21e1b98d065bca519`
- **Entrega F4-00 publicada:** `0a16001eebf934bfd466878aacf058d8cf6bc89b`
- **Alcance:** decisiones previas a F4-02–F4-05; no implementa Fase 5.

## 1. Decisiones pendientes resueltas

| Decisión | Resolución |
| --- | --- |
| Timeout máximo TX-APPLY | 5 s de transacción; `lock_timeout=1s`, `statement_timeout=4s`. TX-RX/CLAIM/PROJECT: 3 s. Ninguna llamada externa dentro de estas transacciones. |
| Roles y privilegios | Matriz de la sección 2. Workers solo internos; evidencias sin API pública. Restricciones y triggers append-only en PostgreSQL. |
| Retención de evidencias | Eventos, operaciones, aplicaciones semánticas y disposiciones: mínimo 365 días; ejecuciones: 90 días; proyecciones: vida de la participación. No hay purga automática en Fase 4; una purga requerirá ADR y prueba de reconciliación. |
| Acceso/protección de evidencias | Sin endpoint público. Servicios internos y auditoría ADMIN futura; payload sin HMAC, secretos ni PII completa. Logs solo con IDs técnicos. |
| Provider y namespace | `SIMULATED` / merchant `default` / namespace `payments.v1`, configurables por entorno. |
| Catálogo `operation_kind` | `PAYMENT_CAPTURE`, `PAYMENT_FAILURE`, `PAYMENT_REFUND`; cualquier otro valor se rechaza. |
| `idempotency_key` semántica | `providerOperationId` emitido por el proveedor; ámbito `order:{orderId}`; inmutable, 1–100 caracteres; retención igual a la evidencia. |
| Parámetros de workers | Lease 30 s, lote 50, máximo 3 intentos, backoff 5 s, recuperación cada 1 s. Valores configurables y acotados. |
| Recuperación de reservas H-79 | Gracia 15 min, evento no iniciado, evidencia de pago exacta y stock completo. Sin recuperación: `UNFULFILLABLE` + `REQUIRED`, nunca `REFUNDED` sin evento de reembolso. |
| Redis original | ADR-020 incorpora caché de eventos, claves de reserva y límites de reservas/órdenes. |

## 2. Matriz de roles y privilegios

| Operación | Público | USER | ORGANIZER | ADMIN | Worker interno |
| --- | --- | --- | --- | --- | --- |
| Registro/login/refresh | Sí | — | — | — | No |
| Lectura de eventos/recintos publicados | Sí | Sí | Sí | Sí | No |
| Crear/cancelar reserva propia | No | Sí | No | No | Expiración/liberación autorizada por estado |
| Crear/listar/cancelar orden propia | No | Sí | No | No | Aplicación de webhook autorizada por evidencia |
| Consultar tickets propios | No | Sí | No | No | Emisión/reemisión por orden pagada |
| Crear evento | No | No | Sí | Sí | No |
| Editar/publicar/cancelar evento | No | No | Solo dueño | Sí | No |
| Gestionar tipos de entrada | No | No | Solo evento propio | Sí | No |
| CRUD de recintos | Lectura | Lectura | Lectura | Escritura | No |
| Webhook de pago | Solo HMAC + contrato válido | No | No | No | RX/CLAIM/APPLY/PROJECT |
| Evidencias operativas | No | No | No | Solo lectura si se expone en el futuro | Escritura append-only por flujo interno |

A nivel SQL, Flyway conserva el rol migrador/propietario. El runtime solo necesita SELECT/INSERT/UPDATE/DELETE sobre tablas de negocio; las tablas de evidencia rechazan UPDATE/DELETE por trigger incluso si el runtime comparte credenciales con el propietario en desarrollo. Separar credenciales de migración/runtime queda como endurecimiento de despliegue, sin crear recursos cloud.

## 3. Parámetros operativos

| Propiedad | Valor | Límite/regla |
| --- | ---: | --- |
| `ticketing.operation.tx-rx-timeout` | `3s` | Sin aplicación comercial |
| `ticketing.operation.tx-claim-timeout` | `3s` | J → XA |
| `ticketing.operation.tx-apply-timeout` | `5s` | Máximo; rollback al exceder |
| `ticketing.operation.tx-project-timeout` | `3s` | No reaplica negocio |
| `ticketing.operation.lock-timeout` | `1s` | PostgreSQL `SET LOCAL` |
| `ticketing.operation.lease-duration` | `30s` | Debe superar timeout APPLY |
| `ticketing.operation.batch-size` | `50` | Máximo 100 |
| `ticketing.operation.max-attempts` | `3` | Después, FAILED con evidencia |
| `ticketing.operation.retry-backoff` | `5s` | Reintento con revalidación completa |
| `ticketing.reservation.expiration-batch-size` | `100` | Nunca mayor que 100 |
| `ticketing.reservation.recovery-grace` | `15m` | Desde `expires_at` |
| `ticketing.cache.events.ttl` | `60s` | Solo metadatos |
| `ticketing.rate-limits.scopes.reservation` | `20/60s` | Por usuario |
| `ticketing.rate-limits.scopes.order` | `10/60s` | Por usuario |

## 4. Matriz de compatibilidad ADR

| ADR | Compatibilidad |
| --- | --- |
| ADR-009 | Se conserva no devolver stock tras reembolso. ADR-019 sustituye el reembolso local inmediato por solicitud + evidencia del proveedor. |
| ADR-010 | Se conserva UPDATE atómico condicional; ADR-016 añade lock T para operaciones compuestas. |
| ADR-011 | Se conserva idempotencia de creación de órdenes. ADR-019 sustituye la deduplicación de webhook por estado/referencia con tres identidades persistentes. |
| ADR-012 | Se conserva `FOR UPDATE SKIP LOCKED`; ADR-016 sustituye el orden R → T por T → R y exige revalidación/hora posterior a locks. |
| ADR-013 | Se conserva no cachear disponibilidad; ADR-020 extiende caché solo a metadatos de eventos. |
| ADR-014 | Se conservan fallback, fail-open de rate limit y fail-closed de refresh. ADR-020 elimina las tres desviaciones de alcance. |
| ADR-015 | Se conserva una orden por reserva, incluidas órdenes canceladas. |
| ADR-016 | Nueva fuente del orden global de locks. |
| ADR-017 | Nuevo modelo de unidades/generaciones; compatible con snapshot de `order_items`. |
| ADR-018 | Nuevo modelo técnico de workers; no introduce mensajería de Fase 5. |
| ADR-019 | Nueva identidad/idempotencia y política H-79; bloqueante para cierre. |
| ADR-020 | Completa el alcance Redis original sin convertir Redis en fuente de verdad. |

## 5. Decisiones que siguen bloqueadas

Ninguna decisión técnica necesaria para F4-02–F4-05 queda bloqueada. La aprobación formal del cierre de Fase 4 sigue reservada al usuario. AWS, despliegue, Kafka, notificaciones y cualquier integración real permanecen bloqueados/fuera de alcance.

## 6. Dictamen de la entrega F4-01

**DECISIONES FORMALIZADAS Y COMPATIBLES PARA INICIAR IMPLEMENTACIÓN.** La aceptación de estas ADR no se presenta como evidencia de funcionamiento; cada decisión requiere código, migraciones y pruebas en las entregas siguientes.
