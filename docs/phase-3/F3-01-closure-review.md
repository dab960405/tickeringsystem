# F3-01 — Expediente de auditoría y cierre de Fase 3

- **Estado:** preparado para dictamen; Fase 3 **no cerrada formalmente**.
- **Fecha:** 2026-11-14 · **Rama:** `dev`.
- **Referencia documental final verificada:** `0e6f5d21ec1665cef19f5145cfcdf97a97097da1`, [CI #317](https://github.com/dab960405/tickeringsystem/actions/runs/43655953752) `success` (390 unitarias, 171 integraciones, Spotless, JaCoCo).
- **Regresión de rollback validada:** `9d11d83344b88007e2b5d89de698bf62899817c0` — [CI #315](https://github.com/dab960405/tickeringsystem/actions/runs/43654824223) `success` (390 unitarias, 171 integraciones, `EventGuardConcurrencyIT` 10/10, Spotless/JaCoCo y BUILD SUCCESS).
- **Decisión arquitectónica pendiente:** [ADR-024](../adr/ADR-024-event-cancellation-transactional-exception.md); [solicitud de dictamen Tech Lead #5](https://github.com/dab960405/tickeringsystem/issues/5) abierta.

## A. Matriz de criterios funcionales

| Criterio | Componentes | Evidencia CI previa (suite completa #312) |
| --- | --- | --- |
| Reserva y stock atómico | `ReservationService`, `TicketTypeInventoryService`, ADR-010 | `ReservationConcurrencyIT` 20, `ReservationHttpConcurrencyIT` 20 |
| TTL, cancelación, expiración y devolución única | `ReservationExpirationService`, `ReservationLifecycleConcurrencyIT` | 5 regresiones de carrera/rollback, `ReservationExpirationIT` |
| Orden única/idempotente y precio snapshot | `OrderService`, V11, ADR-011/015 | `OrderCreationIntegrityIT` 5; `OrderPaymentFlowIT` 36 |
| Pago exclusivamente simulado, webhook HMAC | `PaymentWebhookService`, `OrderPaymentService` | `PaymentWebhookControllerTest` y `OrderPaymentFlowIT` 36 |
| Gracia configurable, pago tardío y reembolso evidenciado | `OrderExpirationProcessor`, ADR-019 | `OrderPaymentFlowIT`, `OperationWorkerIT` 12 |
| Tickets, unidades y generaciones | `TicketService`, V16, ADR-017 | `CommercialIssuanceIT` 14 |
| Cancelación de evento, idempotencia y carreras | `EventCancellationService` | `EventGuardConcurrencyIT` 10/10 en CI #315 (9 anteriores y nueva regresión rollback) |

La evidencia técnica es `./mvnw -B clean verify`: CI #317 ejecutó 390 unitarias y 171 integraciones sin fallos, con Spotless/JaCoCo correctos y `BUILD SUCCESS`. No se documenta un smoke test local con Docker Compose que todavía no se ha ejecutado; se realizará en el inicio autorizado de Fase 5.

## B. Hallazgo arquitectónico residual

**Hallazgo:** `EventCancellationService` llama repositorios de reserva/orden e implementa SQL transversal, incumpliendo la separación modular estricta.

**Causa raíz:** coordinación transaccional y orden estricto de locks de evento, ticket type, reserva y orden en una cancelación atómica.

**Estado de código:** implementado, con orden `E → T → R → O` compatible con ADR-016; no modifica pago sin evidencia; invalida Redis después del commit. La regresión adicional pasó en CI #315, demostrando rollback integral ante fallo de restitución. CI #313/#314 fallaron por defectos del fixture SQL y la aserción inicial de inventario; se corrigieron sin cambios productivos.

**Recomendación:** aceptar una **excepción acotada** expresamente documentada en ADR-024, conservando el comportamiento existente. Si el Tech Lead rechaza, diseñar un refactor de límites de módulo previo a cualquier modificación.

## C. Condiciones para cierre formal

1. **Verificado:** CI #315 verde sobre SHA exacto, nueva regresión de rollback y regresión completa.
2. Dictamen **expreso** del Tech Lead: aceptar ADR-024 como excepción delimitada o solicitar un refactor concreto.
3. Si se acepta, registrar la excepción y las evidencias en arquitectura/README/PROGRESS y aprobar formalmente la Fase 3.
4. Fase 4 solo podrá solicitar cierre posterior a esta decisión; Fase 5 permanece bloqueada.

## D. Resultado del desarrollador

**Cobertura técnica conforme para escenarios registrados; expediente candidato, no cierre formal.** No se alteraron servicios ni contratos; se solicita decisión del Tech Lead sobre una desviación arquitectónica explícita.
