# F4-03 — Auditoría transversal de locks

- **Fecha:** 2026-11-14
- **Alcance:** H75, H76 y H83; ADR-016.
- **Referencia validada:** `24be7cbe670e7076d8e6999a9d708ef4cad09fe1`, CI #292.
- **Estado:** auditoría técnica completada para el modelo y los flujos existentes. Fase 4 permanece abierta.

## Método

Se revisaron todos los `service` y `repository` de `ticketing-api` que adquieren locks explícitos o ejecutan escrituras capaces de introducir locks implícitos por claves foráneas. La revisión incluyó `FOR SHARE`, `FOR UPDATE`, `SKIP LOCKED`, actualizaciones condicionales, inserts con FK y los flushes de JPA.

El contrato contrastado es el orden de ADR-016:

`E → D → PE → J → XA → V → N → CR → C → DS → T → R → O → P → I → U → EN → IS → K`

Las lecturas sin lock usadas para descubrir IDs no se contabilizan como adquisiciones; cuando una decisión depende de estado mutable, el servicio revalida después de adquirir los locks correspondientes. Los inserts de filas nuevas no son equivalentes a un lock de una fila existente, pero sus FK sí pueden adquirir `KEY SHARE` sobre filas padre y por tanto forman parte de esta auditoría.

## Secuencias verificadas

| Flujo | Secuencia relevante | Resultado |
| --- | --- | --- |
| Edición/publicación de evento | E | Compatible |
| Creación/edición de ticket type | E → T | Compatible |
| Creación de reserva | E → T → R (insert) | Compatible |
| Cancelación de reserva | T → R | Compatible |
| Confirmación/recuperación de reserva | E → T → R | Compatible |
| Expiración de reservas | E ordenado → T ordenado → R ordenado/`SKIP LOCKED` | Compatible |
| Creación de orden | E → T → R; después O/P/I nuevos | Compatible tras H83-02 |
| Cancelación de orden pendiente | E → T → R → O | Compatible |
| Cancelación de evento | E → T → R → O | Compatible |
| Expiración de orden | E → T → R → O | Compatible |
| Captura/fallo de pago | E → T → R → O → P → I → U → EN | Compatible |
| Aplicación recuperable | E → J → XA → T → R → O → P → I → U → EN → IS | Compatible |
| Claim/reintento de worker | J → XA | Compatible |
| Proyección de operación | J → XA → K | Compatible |
| Reembolso confirmado | O → P → EN | Compatible |
| Emisión inicial | O → I → U → EN | Compatible |
| Reemisión | O → U → EN | Compatible tras H83-01 |

`DS`, `CR`, `C` y las identidades recibidas se crean como evidencia append-only en sus transacciones correspondientes; no existe una ruta que bloquee una fila existente posterior y después adquiera una fila existente anterior del orden canónico. Las actualizaciones posteriores de J/XA en APPLY/PROJECT recaen sobre filas que ya fueron bloqueadas al inicio de la transacción.

## H83-01 — reemisión EN → U

### Hallazgo

`TicketService.reissueForOrder` cancelaba en memoria los tickets de la generación anterior y luego ejecutaba `generationRepository.saveAndFlush(current)`. Ese flush podía materializar primero los UPDATE de `tickets` (EN). Más tarde, la creación de asociaciones de la nueva generación valida FK contra `commercial_units` y puede adquirir locks implícitos sobre U.

La secuencia efectiva podía ser EN → U, inversa a ADR-016.

### Corrección

- `92fc29957f968bb26486c230eab07720058297db` — `fix(tickets): preserve global lock order during reissue`.
- `798537ba0d7f0b2d153a3d521e735dd91317d3e8` — ajuste Spotless.

La generación anterior se marca `SUPERSEDED` y se hace flush antes de crear la sucesora, pero los tickets anteriores no se cancelan hasta después de persistir los enlaces U/generación y los tickets EN de la sucesora. Todo sigue ocurriendo en la misma transacción: no existe un estado intermedio observable tras commit.

`TicketServiceTest` fija el orden de interacción: transición de generación → enlaces de unidades → tickets nuevos → cancelación de tickets anteriores. `CommercialIssuanceIT` mantiene 14 casos de emisión, reemisión, concurrencia y carrera con reembolso.

## H83-02 — creación de orden R → T por FK

### Hallazgo

`ReservationService.lockActiveForOrder` bloqueaba E → R y omitía T porque la creación de la orden no modifica inventario. Sin embargo, V7 define `order_items.ticket_type_id REFERENCES ticket_types(id)`. Al hacer flush del `Order` y sus items, PostgreSQL puede adquirir `KEY SHARE` sobre T para validar esa FK.

Por tanto, la ruta podía mantener R y adquirir T después: R → T. En paralelo, cancelación/expiración siguen T → R, formando la inversión que ADR-016 pretende eliminar.

### Corrección

- `ea4a061eec20cf1fd02eb305163d9d0c14937c36` — `fix(orders): lock ticket type before reservation`.
- `7a7b75a50b1e2c125c44d4bdfe15fc1bad81ede3` — corrección del fixture Mockito.
- `24be7cbe670e7076d8e6999a9d708ef4cad09fe1` — ajuste Spotless y SHA final validado.

`lockActiveForOrder` sigue ahora E → T → R. T no se bloquea para modificar inventario, sino para garantizar que la futura validación FK de `order_items` no introduzca una adquisición tardía T después de R.

`ReservationServiceTest` verifica E → T → R mediante `InOrder`. `OrderCreationIntegrityIT.should_lockTicketTypeBeforeReservation_when_orderCreationCompetesWithRelease` usa PostgreSQL real: mantiene T bloqueado, demuestra que la creación de orden espera en T y comprueba que la transacción bloqueadora todavía puede adquirir R, lo que descarta que la creación haya tomado R antes que T.

## Evidencia CI

CI #292 sobre `24be7cbe670e7076d8e6999a9d708ef4cad09fe1`:

- Surefire `ticketing-api`: **390**, 0 fallos, 0 errores, 0 omitidos.
- Failsafe `ticketing-api`: **148**, 0 fallos, 0 errores, 0 omitidos.
- `OrderCreationIntegrityIT`: 5/5.
- `CommercialIssuanceIT`: 14/14.
- `EventGuardConcurrencyIT`: 9/9.
- `OperationWorkerIT`: 9/9.
- `ReservationConcurrencyIT`: 20/20.
- `ReservationHttpConcurrencyIT`: 20/20.
- Spotless: 220 archivos Java de `ticketing-api` limpios.
- JaCoCo: checks cumplidos.
- Resultado Maven: `BUILD SUCCESS`.

## Dictamen

- **H75:** validado para el HEAD auditado. No quedan inversiones de locks de filas existentes ni de locks padre por FK identificadas en los flujos actuales.
- **H76:** validado. Todos los flujos que coordinan inventario y reserva adquieren T antes de R; las decisiones temporales de lifecycle se realizan con hora de PostgreSQL después de los locks donde corresponde.
- **H83:** validado para el modelo actual tras corregir H83-01 y H83-02.

Este dictamen debe reabrirse si una migración añade nuevas FK a tablas de la cadena, si un servicio introduce un nuevo `FOR UPDATE/FOR SHARE`, o si un flush de JPA pasa a materializar escrituras sobre una entidad anterior al lock que debe precederla.

La excepción arquitectónica cross-module de `EventCancellationService` es un asunto separado: su secuencia de locks es E → T → R → O y no viola ADR-016, pero su acceso directo a repositorios/tablas de módulos vecinos sigue pendiente de decisión del Tech Lead para el cierre formal de Fase 3.

Este documento no cierra formalmente Fase 4 ni autoriza Fase 5.
