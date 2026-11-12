# Ticketing Platform

[![CI](https://github.com/dab960405/tickeringsystem/actions/workflows/ci.yml/badge.svg)](https://github.com/dab960405/tickeringsystem/actions/workflows/ci.yml)

Plataforma de venta de entradas para eventos, construida con Java 21 y Spring Boot. Demuestra diseño de dominio, seguridad, consistencia transaccional, concurrencia de inventario, reservas temporales, pagos idempotentes y resiliencia ante indisponibilidad de Redis.

> **Estado (2026-11-12):** Fases 0–2 completadas. El alcance principal de Fase 3 está implementado; el hardening de cancelación de eventos sigue pendiente. Fase 4 está implementada y verificada por CI, pero su cierre formal espera confirmación explícita. Fase 5 no ha comenzado.

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
- Rate limiting de login con Redis y política fail-open ante indisponibilidad (`LOGIN_MAX_ATTEMPTS`, `LOGIN_WINDOW_SECONDS`).
- Refresh token store fail-closed: las operaciones que requieren Redis fallan con HTTP 503 si no se puede comprobar o persistir el estado de sesión.
- Los access tokens JWT se validan localmente mediante Spring Security Resource Server, sin consultar Redis.

La degradación y sus límites están documentados en [ADR-014](docs/adr/ADR-014-redis-resilience-and-auth-degradation.md).

## Evidencia CI de código

La evidencia cuantitativa más reciente corresponde al commit de código `aabc02301deda4b983c5f3e56af00ed4ecd717ec`, [GitHub Actions run #116](https://github.com/dab960405/tickeringsystem/actions/runs/43046441594), rama `dev`, conclusión `success`. Los conteos proceden de los XML del artefacto `test-reports` de esa ejecución.

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

Los runs documentales posteriores no modificaron código funcional. El detalle de CI y los estados por criterio están en [docs/PROGRESS.md](docs/PROGRESS.md).

## Concurrencia

La prueba [`ReservationConcurrencyIT`](ticketing-api/src/test/java/com/portfolio/ticketing/reservation/ReservationConcurrencyIT.java) crea un tipo con **10 unidades**, lanza **100 virtual threads** sincronizados con `CountDownLatch` y repite el escenario **20 veces** mediante `@RepeatedTest(20)`. Cada repetición espera 10 reservas exitosas, 90 conflictos por stock, inventario en 0 y 10 unidades en reservas activas.

El descuento se valida mediante un `UPDATE` condicional atómico en PostgreSQL. La ejecución histórica [CI run #62](https://github.com/dab960405/tickeringsystem/actions/runs/42332571762) confirmó `Tests run: 20, Failures: 0`; el escenario también forma parte de la suite de integración del run #116.

## Hoja de ruta

- [x] Fase 0: esqueleto Maven multi-módulo, Maven Wrapper, Compose `infra` y configuración base.
- [x] Fase 1: dominio base, migraciones Flyway V1–V11, entidades/repositorios, CRUD, errores y OpenAPI.
- [x] Fase 2: autenticación/autorización JWT, roles, propiedad de recursos, refresh tokens y rate limiting.
- [x] Fase 3: alcance principal de reservas y órdenes, inventario concurrente, expiración, idempotencia, webhook HMAC, pagos y tickets. **Pendiente:** hardening del ciclo de cancelación de eventos.
- [ ] Fase 4: implementación y evidencia CI completadas; **pendiente confirmación explícita de cierre**.
- [ ] Fase 5: publicación transaccional outbox, Kafka y consumidor `notification-service`.
- [ ] Fase 6: calidad e infraestructura (Dockerfiles, Compose `full`, observabilidad/Grafana y revisión N+1).
- [ ] Fase 7: CI/CD y despliegue en AWS.
- [ ] Fase 8: pulido, documentación de operación y mejoras finales.

## Deuda técnica conocida

- El ciclo de cancelación de eventos no coordina todavía reservas, órdenes pendientes, pagos, tickets ni la transición/evento correspondiente; requiere hardening y pruebas específicas.
- Órdenes que nunca alcanzan el estado `EXPIRED`.
- El reembolso no devuelve stock automáticamente; decisión documentada en [ADR-009](docs/decisions.md).
- No existe todavía una prueba de webhooks concurrentes.
- El rate limiting está implementado únicamente para login.
- No se implementó caché para eventos ni claves Redis `reservation:{id}`.
- Los refresh tokens no rotan durante la operación de refresh.
- El webhook HMAC no incluye timestamp ni mecanismo anti-replay temporal.

## Criterio permanente de cierre de fase

Una fase no se considera cerrada solo porque el código compile o la funcionalidad esté implementada. Su cierre exige README y hoja de ruta actualizados, ERD actualizado cuando cambie el esquema, ADRs para decisiones importantes y evidencia verificable de los criterios de aceptación. Los tests no ejecutados se marcan como pendientes, nunca como aprobados.

La Fase 4 queda lista para revisión de cierre, pero no se marca como cerrada hasta recibir confirmación explícita. La Fase 5 no debe iniciarse antes de ese cierre.

## Decisiones técnicas

Consulta el [registro ADR](docs/decisions.md). El ERD y el flujo de módulos están en [docs/erd.md](docs/erd.md) y [docs/architecture.md](docs/architecture.md).
