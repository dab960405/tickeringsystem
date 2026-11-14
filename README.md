# Ticketing Platform

[![CI](https://github.com/dab960405/tickeringsystem/actions/workflows/ci.yml/badge.svg)](https://github.com/dab960405/tickeringsystem/actions/workflows/ci.yml)

Plataforma de venta de entradas para eventos, construida con Java 21 y Spring Boot. Demuestra diseño de dominio, seguridad, consistencia transaccional, concurrencia de inventario, reservas temporales, pagos idempotentes y resiliencia ante indisponibilidad de Redis.

> **Estado (2026-11-14):** Fases 0–2 completadas. Fase 2 cerrada formalmente tras validar los criterios de autorización, propiedad de recursos y aislamiento con JWT reales, respaldados por CI #154 y #43545520476. El alcance funcional de Fase 3, incluido el hardening de cancelación de eventos y la expiración de órdenes tras la gracia configurable, está implementado y validado por CI; el cierre formal sigue pendiente de resolver o aceptar explícitamente el acceso directo entre módulos de `EventCancellationService`, completar la documentación de cierre y recibir autorización del responsable. Fase 4 permanece abierta. Fase 5 no ha comenzado y continúa bloqueada.

## Documentación

- [Progreso y evidencia de verificación](docs/PROGRESS.md)
- [Guía de ejecución local](docs/LOCAL_RUN.md)
- [Flujo HTTP de smoke test](docs/flow.http) — no se ejecuta en CI; requiere generar manualmente la firma HMAC del webhook.
- [Arquitectura y flujo de negocio](docs/architecture.md)
- [Diagrama ERD basado en migraciones Flyway](docs/erd.md)
- [Registro de decisiones](docs/decisions.md)
- [ADR-010: actualización atómica del inventario](docs/adr/ADR-010-atomic-inventory-update.md)
- [ADR-011: idempotencia de órdenes y webhooks](docs/adr/ADR-011-order-and-webhook-idempotency.md)
- [ADR-012: expiración con SKIP LOCKED](docs/adr/ADR-012-reservation-expiration-skip-locked.md)
- [ADR-013: caché Redis de metadatos del catálogo](docs/adr/ADR-013-redis-catalog-cache.md)
- [ADR-014: resiliencia Redis y degradación de autenticación](docs/adr/ADR-014-redis-resilience-and-auth-degradation.md)
- [ADR-015: una orden por reserva](docs/adr/ADR-015-one-order-per-reservation.md)
- [ADR-021: ocultación de recursos ajenos](docs/adr/ADR-021-phase-2-authorization-resource-concealment.md)
- [Matriz de autorización de Fase 2](docs/phase-2/authorization-matrix.md)

## Arquitectura y módulos

La API es un monolito modular Spring Boot. Los límites de dominio se organizan por paquete dentro de `ticketing-api`; `common-events` contiene contratos compartidos y `notification-service` es el servicio separado previsto para la pista de eventos asíncronos.

| Módulo | Descripción |
| --- | --- |
| `common-events` | Contratos de eventos compartidos (records), p. ej. `EventEnvelope` |
| `ticketing-api` | API principal (puerto 8080): auth, catálogo, reservas, órdenes, pagos y tickets |
| `notification-service` | Consumidor separado previsto (puerto 8081); integración Kafka pendiente de Fase 5 |
| `docs/` | Arquitectura, ERD, progreso, guía local, flujo HTTP y ADRs |

## Stack

Java 21 · Maven 3.9 (Maven Wrapper) · Spring Boot 3.5 · PostgreSQL 17 · Redis 7 · Apache Kafka 4 (KRaft) · MongoDB 7 · Flyway · Spring Security · JUnit 5 · Testcontainers · JaCoCo · Spotless · Docker Compose.

## Inicio rápido

Consulta [docs/LOCAL_RUN.md](docs/LOCAL_RUN.md) para requisitos, exportación de variables y comandos de pruebas.

```bash
cp .env.example .env
# Edita .env y exporta sus variables al proceso de la terminal.
docker compose --profile infra up -d
docker compose ps
./mvnw -pl ticketing-api -am package -DskipTests
java -jar ticketing-api/target/ticketing-api-0.1.0-SNAPSHOT.jar
curl -i http://localhost:8080/actuator/health
```

PostgreSQL, Redis, Kafka y MongoDB deben aparecer como `healthy` antes de iniciar las aplicaciones. Los pasos con Docker Compose no se verificaron en el entorno de desarrollo asistido; consulta la advertencia de [LOCAL_RUN](docs/LOCAL_RUN.md).

## Comandos útiles

| Comando | Alcance |
| --- | --- |
| `./mvnw test` | Pruebas unitarias Surefire (`*Test.java`); no ejecuta Failsafe |
| `./mvnw clean verify -DskipITs` | Unitarias, build, JaCoCo y Spotless; omite integración |
| `./mvnw clean verify` | Verificación completa, incluidas pruebas Failsafe `*IT.java`; requiere Docker |
| `./mvnw -pl ticketing-api -am package -DskipTests` | Empaqueta API y módulo compartido sin tests |

## Fase 4 — alcance implementado

- Caché-aside Redis solo para metadatos del catálogo, con TTL configurable mediante `TICKET_TYPES_CACHE_TTL` (5 minutos por defecto).
- La disponibilidad de inventario se consulta siempre en PostgreSQL; no se cachea stock.
- Las escrituras del catálogo invalidan la caché mediante `RedisCacheManager` transaccional.
- Los fallos de caché Redis contemplados por `CacheErrorHandler` permiten fallback a PostgreSQL. Si falla una invalidación, una entrada anterior puede permanecer hasta su TTL.
- Caché pública de eventos con TTL de 60 s para detalle publicado y primera página popular; la disponibilidad de inventario permanece fuera de caché.
- Proyección Redis `ticketing:reservation:{id}` para reservas activas, escrita después del commit con TTL restante y eliminada tras cancelación, expiración o confirmación; PostgreSQL sigue siendo la autoridad.
- Rate limiting Redis fail-open para login (5/60 s), creación de reservas (20/60 s por usuario) y creación de órdenes (10/60 s por usuario).
- Refresh token store fail-closed: las operaciones que requieren Redis fallan con HTTP 503 si no se puede comprobar o persistir el estado de sesión.
- Los access tokens JWT se validan localmente mediante Spring Security Resource Server, sin consultar Redis.

La degradación y sus límites están documentados en [ADR-014](docs/adr/ADR-014-redis-resilience-and-auth-degradation.md).

## Evidencia CI de código

La evidencia cuantitativa histórica de Fase 4 corresponde al commit `aabc02301deda4b983c5f3e56af00ed4ecd717ec` (run #116). Para Fase 2, la evidencia más reciente es [GitHub Actions run #154](https://github.com/dab960405/tickeringsystem/actions/runs/43543996946), SHA `06490392e86866094ee9dddbff450f4e76e646ad`, rama `dev`, conclusión `success`. Los conteos de esta ejecución proceden de los XML del artefacto `test-reports`:

| Suite | Ejecutados | Fallidos | Errores | Omitidos |
| --- | ---: | ---: | ---: | ---: |
| Surefire (todos los módulos) | 315 | 0 | 0 | 0 |
| Failsafe (`ticketing-api`) | 61 | 0 | 0 | 0 |
| **Total** | **376** | **0** | **0** | **0** |

Surefire por módulo: `ticketing-api` 309, `common-events` 5 y `notification-service` 1. Failsafe ejecutó 61 tests en `ticketing-api`.

Cobertura JaCoCo de paquetes de `ticketing-api`, desde el XML del artefacto `jacoco-reports` del mismo run #116:

| Paquetes | Líneas cubiertas | Cobertura de líneas | Ramas cubiertas | Cobertura de ramas |
| --- | ---: | ---: | ---: | ---: |
| `service` | 501 / 522 | **95,98 %** | 126 / 151 | 83,44 % |
| `domain` | 291 / 296 | **98,31 %** | 84 / 101 | 83,17 % |


**CI #154 — verificación de Fase 2:** 379 tests Surefire y 100 Failsafe; 479 en total, con 0 fallos, 0 errores y 0 omitidos. Spotless pasó y JaCoCo confirmó que se cumplieron los checks de cobertura. El run #43545520476 validó el HEAD previo al registro de cierre (`7146517ef80f3fe350a57d00e272f66b59ffc923`): `BUILD SUCCESS`, Spotless correcto, checks JaCoCo cumplidos y 100 tests Failsafe sin fallos. Con la autorización expresa del responsable, Fase 2 queda cerrada formalmente.

Los runs documentales posteriores no modificaron código funcional. El detalle de CI y los estados por criterio están en [docs/PROGRESS.md](docs/PROGRESS.md).

## Concurrencia

La prueba [`ReservationConcurrencyIT`](ticketing-api/src/test/java/com/portfolio/ticketing/reservation/ReservationConcurrencyIT.java) crea un tipo con **10 unidades**, lanza **100 virtual threads** sincronizados con `CountDownLatch` y repite el escenario **20 veces** mediante `@RepeatedTest(20)`. Cada repetición espera 10 reservas exitosas, 90 conflictos por stock, inventario en 0 y 10 unidades en reservas activas.

El descuento se valida mediante un `UPDATE` condicional atómico en PostgreSQL. La ejecución histórica [CI run #62](https://github.com/dab960405/tickeringsystem/actions/runs/42332571762) confirmó `Tests run: 20, Failures: 0`; el escenario también forma parte de la suite de integración del run #116.

## Hoja de ruta

- [x] Fase 0: esqueleto Maven multi-módulo, Maven Wrapper, Compose `infra` y configuración base.
- [x] Fase 1: dominio base, migraciones Flyway V1–V11, entidades/repositorios, CRUD, errores y OpenAPI.
- [x] Fase 2: autenticación/autorización JWT, roles, propiedad de recursos, refresh tokens y rate limiting implementados; matriz de permisos documentada; aislamiento entre organizadores y usuarios validado con JWT reales; CI #154 (479 tests, 0 fallos/errores) y CI #43545520476 satisfactorias. Cierre formal autorizado el 2026-11-13.
- [ ] Fase 3: reservas, órdenes, inventario concurrente, expiración de reservas y órdenes, idempotencia, webhook HMAC, pagos, tickets y hardening de cancelación de eventos implementados y cubiertos por la suite. **Cierre formal pendiente:** resolver o aceptar explícitamente el acceso directo entre módulos de `EventCancellationService`, consolidar la evidencia/documentación y recibir autorización del responsable.
- [ ] Fase 4: abierta. H75/H76/H77/H81/H83 y los flujos actuales de H78/H79/H80/H82/H84 tienen evidencia CI; queda pendiente H84-01 (identidad semántica ante estados sucesivos de reembolso), el expediente integral y la aprobación del responsable.
- [ ] Fase 5: publicación transaccional outbox, Kafka y consumidor `notification-service`.
- [ ] Fase 6: calidad e infraestructura (Dockerfiles, Compose `full`, observabilidad/Grafana y revisión N+1).
- [ ] Fase 7: CI/CD y despliegue en AWS.
- [ ] Fase 8: pulido, documentación de operación y mejoras finales.

## Deuda técnica conocida

- `EventCancellationService` coordina la cancelación con reservas, inventario y órdenes pendientes, pero actualmente accede directamente a repositorios/tablas de otros módulos; este acoplamiento requiere decisión arquitectónica explícita o refactor antes de proponer el cierre formal de Fase 3.
- El reembolso no devuelve stock automáticamente; decisión documentada en [ADR-009](docs/decisions.md).
- Los refresh tokens no rotan durante la operación de refresh.
- El webhook HMAC no incluye timestamp ni mecanismo anti-replay temporal.

## Criterio permanente de cierre de fase

Una fase no se considera cerrada solo porque el código compile o la funcionalidad esté implementada. Su cierre exige README y hoja de ruta actualizados, ERD actualizado cuando cambie el esquema, ADRs para decisiones importantes y evidencia verificable de los criterios de aceptación. Los tests no ejecutados se marcan como pendientes, nunca como aprobados.

La Fase 4 continúa abierta: los escenarios integrados pasaron en CI #296, pero H84-01 exige decisión técnica y prueba específica antes del expediente final y cierre formal. La Fase 5 no debe iniciarse antes de ese cierre.

## Auditoría de locks de Fase 4

La auditoría transversal de ADR-016 revisó locks explícitos y locks implícitos por claves foráneas/flush. Se corrigieron dos inversiones: reemisión de tickets (EN → U) y creación de órdenes (R → T por la FK de `order_items`). CI #292 sobre `24be7cbe670e7076d8e6999a9d708ef4cad09fe1` validó 390 unitarios y 148 integraciones de `ticketing-api`, incluidos los escenarios de concurrencia e integridad; H75, H76 y H83 quedan validados para ese modelo. Véase [F4-03](docs/phase-4/F4-03-lock-audit.md).

## Validación de operaciones y reembolsos

CI #296 sobre `522a2dcf706122760ffee4fd46e8027e41e2723d` completó 390 pruebas unitarias y 153 de integración de `ticketing-api`, con `OrderPaymentFlowIT` 20/20 y `OperationWorkerIT` 11/11, incluidos los timeouts PostgreSQL reales de RX/CLAIM/APPLY/PROJECT. Spotless y JaCoCo finalizaron correctamente. La auditoría [F4-04](docs/phase-4/F4-04-integrated-payment-audit.md) registra una inconsistencia pendiente de decisión en operaciones de reembolso con una misma identidad y estados sucesivos; por ello no se declara H84 cerrado.

## Decisiones técnicas

Consulta el [registro ADR](docs/decisions.md). El ERD y el flujo de módulos están en [docs/erd.md](docs/erd.md) y [docs/architecture.md](docs/architecture.md).
