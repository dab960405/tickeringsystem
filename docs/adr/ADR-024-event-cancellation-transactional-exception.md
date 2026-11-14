# ADR-024 — Excepción transaccional limitada para cancelación de eventos

- **Estado:** PROPUESTO para dictamen técnico del Tech Lead; no aprobado por publicación.
- **Fecha:** 2026-11-14 · **Fase:** 3.
- **Relaciones:** [ADR-016](ADR-016-global-lock-order.md) y [F3-01](../phase-3/F3-01-closure-review.md).
- **Alcance:** documentar y auditar la excepción existente; no aprobar refactor, nuevas migraciones ni cambios de API.

## Contexto

`EventCancellationService` coordina en una misma transacción las tablas `events`, `ticket_types`, `reservations` y `orders`. Utiliza `EventRepository`, `TicketTypeInventoryService`, `ReservationRepository`, `OrderRepository`, `ReservationRedisProjectionService` y SQL con `JdbcTemplate` sobre tablas de otros módulos. **Esto infringe el límite modular por defecto** que pide colaborar mediante servicios o eventos, aunque cumple el orden de locks de ADR-016.

Su flujo toma `E FOR UPDATE → T (id ordenado) → R FOR UPDATE (id ordenado) → O FOR UPDATE (id ordenado)`; la cancelación de evento no constituye evidencia de fallo o reembolso del pago. Las proyecciones Redis se eliminan después del commit, no como fuente de verdad.

## Opciones y trade-offs

| Opción | A favor | En contra | Valoración |
| --- | --- | --- | --- |
| Mantener acceso directo sin ADR | Cero cambios | Incumplimiento oculto | Rechazada |
| **Aceptar excepción estrecha para el coordinador** | Mantiene transacción y locks; bajo riesgo y claro para portafolio | Acoplamiento explícito entre módulos | **Recomendada para aprobación** |
| Refactor hacia interfaces de aplicación de otros módulos | Mejor aislamiento | Reabre locks, atomicidad y pruebas de carreras | Solo si Tech Lead rechaza excepción |
| Saga/eventos asíncronos | Desacoplamiento | Cambia semántica, requiere compensaciones/infraestructura | Fuera de F3 |

## Condiciones precisas de la excepción propuesta

1. Solo `EventCancellationService.cancel(UUID eventId)` conserva la capacidad de coordinar directamente los repositorios/SQL de inventario, reservas y órdenes para cancelación **de evento**. No crea una excepción genérica para otros servicios.
2. Garantizar una sola transacción PostgreSQL y adquisición `E → T → R → O`, incluida validación de FK implícitas; no introducir nuevas inversiones (ADR-016).
3. Tras fallos de cualquier paso, ningún efecto parcial se confirma; la caché/proyección Redis no se elimina si hay rollback. Usar Spring `@Transactional` y callback post-commit existente.
4. Cancelar solo reservas con inventario `HELD` y órdenes `PENDING_PAYMENT` elegibles; no devolver stock dos veces ni modificar un pago por inferencia financiera.
5. Revalidar relaciones/estados tras los bloqueos, respetar llamadas repetidas y preservar pagos confirmados y sus reservas.
6. Si se añaden nuevas entidades, FKs, side effects o nuevas rutas de cancelación, **reabrir ADR-024 y auditoría ADR-016**.
7. Documentar la excepción como deuda técnica aceptada y acotada; no justificar su expansión por analogía.

## Evidencia y verificación

- Código vigente: `ticketing-api/src/main/java/com/portfolio/ticketing/event/service/EventCancellationService.java`.
- `EventGuardConcurrencyIT`: nueve pruebas existentes validadas en CI #312, que incluyen cancelación idempotente, carreras contra capturas/pago tardío y preservación de compras confirmadas.
- Regresión adicional: `should_rollbackEventReservationAndOrder_when_stockRestorationFails`, prueba PostgreSQL con trigger de fallo de restitución, aserciones de rollback de evento/reserva/orden/inventario y de proyección Redis; añadida `4d381899`, fixture SQL corregido `c18d9f2` y aserción de stock corregida en `9d11d833`. CI #313 falló por sintaxis del fixture; CI #314 por una expectativa incorrecta del stock inicial. [CI #315](https://github.com/dab960405/tickeringsystem/actions/runs/43654824223) `success` sobre `9d11d83344b88007e2b5d89de698bf62899817c0`: 390 unitarias, 171 integraciones, `EventGuardConcurrencyIT` 10/10, Spotless y JaCoCo correctos. No modifica código productivo.
- El dictamen requiere evidencias por SHA/CI y aceptación expresa del Tech Lead; ningún resultado de CI equivale a aprobación automática.

## Referencias

- [Spring Framework — transacciones y propagation REQUIRED](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html).
- [Spring Framework — rollback declarativo](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/rolling-back.html).
- [PostgreSQL 17 — lock modes y prevención de deadlocks](https://www.postgresql.org/docs/17/explicit-locking.html).

**La decisión de permitir el acoplamiento es específica de Ticketing y no una regla prescrita por esos documentos oficiales.**
