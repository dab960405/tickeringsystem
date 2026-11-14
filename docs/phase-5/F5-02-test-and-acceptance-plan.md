# F5-02 — Plan de pruebas y aceptación de Fase 5 (PROPUESTO)

- **Estado:** especificación de pruebas, no ejecutadas.
- **Base:** `dev` @ `73eb12d307af696533ba182574d7f9f54ab2ec42`; CI #310 success.
- **Depende de:** [F5-01](F5-01-technical-design.md), ADR-023 (propuesto).
- **Bloqueo:** Fases 3 y 4 sin cierre formal; Fase 5 no autorizada.

## Prerequisito 0 — validación manual local (antes de cualquier código de mensajería)

Usar `docs/LOCAL_RUN.md` y `docs/flow.http`, ejecutar `docker compose --profile infra up -d`, verificar cuatro servicios healthy y realizar el flujo de pago **simulado**. El seed dev contiene un evento publicado y un tipo General de **USD 45.00**, mientras `flow.http` tiene un cuerpo de ejemplo de **USD 80.00**: **debe derivarse el importe real de la orden y firmar los bytes exactos**, nunca usar 80.00 fijo. Registrar comandos, salidas sin secretos, SHA y posibles fallos locales; no marcar «verde» sin ejecutar.

```bash
docker compose --profile infra up -d
docker compose ps
./mvnw -B clean verify
./mvnw -pl ticketing-api -am package -DskipTests
java -jar ticketing-api/target/ticketing-api-0.1.0-SNAPSHOT.jar
# En otra terminal:
curl -i http://localhost:8080/actuator/health
curl -i http://localhost:8080/api/v1/events
```

## Matriz de pruebas

| ID | Prueba | Invariante observable | Herramienta |
| --- | --- | --- | --- |
| F5-T01 | Emisión `OrderConfirmed` tras captura válida | Orden cumplida, tickets y **un** outbox en el mismo commit | PostgreSQL Testcontainers |
| F5-T02 | Error tras insert outbox antes de commit | Rollback de negocio y outbox | PostgreSQL Testcontainers |
| F5-T03 | Webhook duplicado o `NO_OP` | No genera nuevo outbox/comercialización | PostgreSQL IT |
| F5-T04 | Captura tardía `UNFULFILLABLE` | No crea falso `OrderConfirmed` | PostgreSQL IT |
| F5-T05 | Cancelación pendiente por usuario/fallo/evento | Un `OrderCancelled` por transición real | PostgreSQL IT |
| F5-T06 | Reembolso confirmado frente a `PENDING/FAILED/NO_OP` | `OrderRefunded` solo tras confirmación real | PostgreSQL IT |
| F5-T07 | Reserva vence (TTL, expiración de orden) | Un `ReservationExpired` por transición | PostgreSQL IT |
| F5-T08 | Evento cancelado y misma llamada repetida | `EventCancelled` por destinatario, no duplicados | PostgreSQL IT |
| F5-T09 | Kafka detenido durante relay | Negocio confirmado, `published_at IS NULL`; sin pérdida | PostgreSQL+Kafka IT |
| F5-T10 | Broker vuelve a estar disponible | Se publica y marca timestamp solo tras ACK | PostgreSQL+Kafka IT |
| F5-T11 | Kafka ACK seguido de rollback PG simulado | Posible duplicado Kafka; **un** documento Mongo | Kafka+Mongo IT |
| F5-T12 | Dos relays reclamando en paralelo | `SKIP LOCKED` no procesa simultáneamente la misma fila | PostgreSQL+Kafka IT |
| F5-T13 | Dos eventos por el mismo agregado | Orden de emisión de un agregado correcto | PostgreSQL+Kafka IT |
| F5-T14 | Eventos de agregados distintos | Se pueden publicar concurrentemente | PostgreSQL+Kafka IT |
| F5-T15 | JSON envelope V1 válido | Recompone tipo, versión, UUID, instante y destinatario | JUnit / Jackson |
| F5-T16 | Versión desconocida y payload inválido | No se persiste notificación; llega a DLT | Kafka+Mongo IT |
| F5-T17 | Evento duplicado con `eventId` idéntico | Índice único Mongo; una sola notificación | Mongo Testcontainers IT |
| F5-T18 | UUID eventId igual con payload contradictorio | Error controlado y DLT, no falso duplicado válido | Kafka+Mongo IT |
| F5-T19 | Mongo indisponible | No ACK de éxito; dos reintentos y DLT tras tercera entrega | Kafka+Mongo IT |
| F5-T20 | DLT no disponible | No dar por recuperado ni perder el evento | Kafka IT |
| F5-T21 | Query con API key inválida/ausente | HTTP 401, no filtra documentos | MockMvc |
| F5-T22 | Query con API key válida | Paginación, orden estable, filtro por userId, size ≤100 | Mongo IT |
| F5-T23 | Aplicación sin `NOTIFICATION_API_KEY` | No arranca en modo funcional abierto | Spring configuration test |
| F5-T24 | Flujo íntegro del usuario | Eventos Kafka → notificación en Mongo mediante Awaitility | Testcontainers PG/Redis/Kafka/Mongo |
| F5-T25 | Regresión completa | Tests existentes y ≥70 % JaCoCo en service/domain, Spotless | `./mvnw -B clean verify` |

**Ejecución**: usar `*Test` con Surefire y `*IT` con Failsafe; no desactivar ni reescribir los 390 unitarios/170 IT actuales. Los contenedores Kafka y Mongo solo en las suites nuevas; no iniciar cuatro contenedores para cada test existente. `@ServiceConnection` cuando sea compatible y propiedades dinámicas si hace falta. Evitar `Thread.sleep`: Awaitility/CountDownLatch y tiempos acotados.

**Reporte obligatorio**: SHA exacto, comando, conteos por suite, CI run, casos fallidos, warnings reales, scripts del smoke local con credenciales ocultas, evidencia Kafka/Mongo y decisión formal de cierre.

**Criterio de aceptación formal**: T01–T25 implementados/pasados o excepción explícita aprobada; prueba local completa documentada; todas las notificaciones simuladas; cero dependencias AWS/pasarelas reales/correo real; Fase 5 no se declara cerrada sin dictamen del Tech Lead.
