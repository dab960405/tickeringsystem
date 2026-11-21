# ADR-023 — Transactional Outbox y consumo idempotente local para Fase 5

- **Estado:** PROPUESTO — no aprobado.
- **Fecha:** 2026-11-14.
- **Fase:** 5 (planificación solamente).
- **Base:** `dev` / `73eb12d307af696533ba182574d7f9f54ab2ec42`.
- **Precondiciones:** cierre formal Fases 3 y 4, autorización de Fase 5.

## Contexto

La especificación fija Transactional Outbox, Kafka KRaft, MongoDB para notificaciones, ack manual, tres entregas, DLT, idempotencia y local primero. V10 ya creó la tabla `outbox_events`. El objetivo es demostrar garantías reales sin montar arquitectura bancaria/empresarial.

## Alternativas

1. **Publicación directa desde la transacción comercial**: descartada; el commit DB y el envío Kafka pueden divergir.
2. **Outbox con publicador sencillo `FOR UPDATE SKIP LOCKED`**: recomendado provisionalmente para portafolio y broker local. Una fila/tx, send con ACK y timeout bajo el lock, marcar `published_at` y commit. Kafka puede recibir duplicados si PostgreSQL revierte después del ACK.
3. **Outbox con claim/lease en V17 y envío fuera de transacción**: alternativa más escalable; añade columnas/estados, recuperación y fencing, exige nueva migración aprobada y auditoría de locks. No justificarla sin una limitación probada de opción 2.
4. **CDC/Debezium o transacción distribuida 2PC**: fuera de alcance, sobredimensionado.

## Recomendación propuesta

Reutilizar V10 sin alteración. Writer dentro de TX comercial. Relay cada segundo con `SKIP LOCKED`, `NOT EXISTS` de evento previo pendiente para el mismo agregado, orden `created_at,id`, Kafka key `aggregateId`, envío JSON completo y confirmación dentro de timeout corto. Estado publicado solo tras ACK Kafka. `at-least-once` + consumidor Mongo idempotente con `eventId` único.

Spring Kafka `@KafkaListener` con `enable.auto.commit=false`, ack manual después de persistencia, `DefaultErrorHandler` (inicial + dos reintentos) y DLT `<topic>.DLT` confirmada antes de avanzar offset. No correos reales. Endpoint consulta interno con API key.

**Coste:** Docker Compose local sin AWS ni servicios de pago.

## Riesgos aceptados para revisión

- Se mantiene una TX de PostgreSQL durante un envío Kafka, máximo 2s por mensaje; puede aumentar la ocupación del pool si el broker cae. Limitar concurrencia y demostrar comportamiento en IT.
- Kafka/Mongo no son atómicos. Una entrega duplicada es posible y debe ser inocua.
- Orden por agregado basado en `clock_timestamp()` al insertar y `id` para desempate es determinista para la consulta, pero no constituye secuencia transaccional global. Validar carreras reales; si falla el criterio, revisar si una V17 secuencial es necesaria.
- DLT puede necesitar reprocesamiento manual tras indisponibilidad prolongada de Mongo.
- Cada `EventCancelled` puede necesitar fan-out a compradores y organizador; obtener aceptación del criterio de destinatarios antes de implementar.

## Consecuencias y validación

No modifica servicios, migraciones ni APIs durante el diseño. Las piezas y matriz están en [F5-01](../phase-5/F5-01-technical-design.md) y [F5-02](../phase-5/F5-02-test-and-acceptance-plan.md).

**Aprobar antes de implementar:** estrategia relay bajo TX, destinatarios y semántica de eventos, esquema V1, política de retries/DLT, seguridad de consulta. Reabrir si las pruebas demuestran que el diseño no cumple orden por agregado o el tiempo máximo.

## Referencias técnicas

- [PostgreSQL 17 — SELECT FOR UPDATE SKIP LOCKED](https://www.postgresql.org/docs/17/sql-select.html).
- [Spring Kafka 3.3 — KafkaTemplate](https://docs.spring.io/spring-kafka/reference/3.3/kafka/sending-messages.html), [manual ACK](https://docs.spring.io/spring-kafka/reference/3.3/kafka/receiving-messages/message-listener-container.html), [error handler y DLT](https://docs.spring.io/spring-kafka/reference/3.3/kafka/annotation-error-handling.html).
- [Spring Data MongoDB — índices](https://docs.spring.io/spring-data/mongodb/reference/mongodb/mapping/mapping-index-management.html).
- [Patrón Transactional Outbox](https://microservices.io/patterns/data/transactional-outbox.html); patrón de diseño, no un estándar oficial.
