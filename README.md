# Ticketing Platform

[![CI](https://github.com/dab960405/tickeringsystem/actions/workflows/ci.yml/badge.svg)](https://github.com/dab960405/tickeringsystem/actions/workflows/ci.yml)

Plataforma de venta de entradas para eventos, construida como proyecto de portafolio con Java 21 y Spring Boot. El objetivo técnico es demostrar diseño de dominio, seguridad, consistencia transaccional, concurrencia de inventario, reservas temporales, pagos idempotentes y, en fases posteriores, caché y mensajería asíncrona.

> **Estado actual:** Fases 1, 2 y 3 completadas. La Fase 3 quedó verificada en GitHub Actions CI, [run #62](https://github.com/dab960405/tickeringsystem/actions/runs/42332571762). **Fase 4 en curso:** primer incremento de caché Redis para metadatos del catálogo; la disponibilidad de inventario continúa consultándose en PostgreSQL.

## Documentación

- [Progreso y evidencia de verificación](docs/PROGRESS.md)
- [Arquitectura y flujo de negocio](docs/architecture.md)
- [Diagrama ERD basado en migraciones Flyway](docs/erd.md)
- [Registro de decisiones](docs/decisions.md)
- [ADR-010: actualización atómica del inventario](docs/adr/ADR-010-atomic-inventory-update.md)
- [ADR-011: idempotencia de órdenes y webhooks](docs/adr/ADR-011-order-and-webhook-idempotency.md)
- [ADR-012: expiración con SKIP LOCKED](docs/adr/ADR-012-reservation-expiration-skip-locked.md)
- [ADR-013: caché Redis del catálogo sin inventario](docs/adr/ADR-013-redis-catalog-cache.md)

## Arquitectura y módulos

La API es un monolito modular Spring Boot. Los límites de dominio se organizan por paquete dentro de `ticketing-api`; `common-events` contiene contratos compartidos y `notification-service` es el servicio separado previsto para la pista de eventos asíncronos.

| Módulo | Descripción |
| --- | --- |
| `common-events` | Contratos de eventos compartidos (records), p. ej. `EventEnvelope` |
| `ticketing-api` | API principal (puerto 8080): auth, catálogo, reservas, órdenes, pagos y tickets |
| `notification-service` | Consumidor separado previsto (puerto 8081); integración Kafka pendiente de Fase 5 |
| `docs/` | Arquitectura, ERD, progreso y ADRs |

## Stack

Java 21 · Maven 3.9 (Maven Wrapper) · Spring Boot 3.5 · PostgreSQL 17 · Redis 7 · Apache Kafka 4 (KRaft) · MongoDB 7 · Flyway · Spring Security · JUnit 5 · Testcontainers · JaCoCo · Spotless · Docker Compose.

## Requisitos previos

- JDK 21 (por ejemplo Eclipse Temurin 21)
- Docker con Docker Compose v2 para infraestructura y tests de integración
- No hace falta instalar Maven: se usa `./mvnw`

## Inicio rápido

```bash
# 1. Variables de entorno locales (edita los valores de ejemplo)
cp .env.example .env

# 2. Levantar dependencias (Postgres, Redis, Kafka y Mongo)
docker compose --profile infra up -d
docker compose ps        # todos los servicios deben aparecer como "healthy"

# 3. Compilar y ejecutar todos los tests (incluye *IT; requiere Docker)
./mvnw clean verify

# 4. Arrancar la API
./mvnw -pl ticketing-api -am spring-boot:run
curl http://localhost:8080/actuator/health
```

Para detener y borrar los volúmenes: `docker compose --profile infra down -v`.

## Comandos útiles

| Comando | Qué hace |
| --- | --- |
| `./mvnw clean verify` | Build completo: tests unitarios e integración, cobertura y formato; los *IT necesitan Docker |
| `./mvnw clean verify -DskipITs` | Verificación sin ejecutar los tests Failsafe de integración |
| `./mvnw test` | Tests unitarios/slice |
| `./mvnw -pl ticketing-api -am package` | Empaqueta la API y sus dependencias |
| `./mvnw spotless:apply` | Formatea el código (google-java-format) |

## Concurrencia

La prueba [`ReservationConcurrencyIT`](ticketing-api/src/test/java/com/portfolio/ticketing/reservation/ReservationConcurrencyIT.java) modela una venta simultánea de las últimas entradas:

- Crea un tipo de entrada con **10 unidades disponibles**.
- Lanza **100 virtual threads** que esperan en una barrera (`CountDownLatch`) y compiten por reservar una unidad cada uno.
- Ejecuta el escenario **20 veces** mediante `@RepeatedTest(20)`.
- El resultado esperado en cada repetición es: **10 reservas exitosas**, **90 conflictos por stock insuficiente**, inventario disponible en **0** y **10 unidades** contabilizadas en reservas activas.

El test valida el descuento mediante un `UPDATE` condicional atómico en PostgreSQL, no mediante una lectura seguida de una escritura. La ejecución [CI run #62](https://github.com/dab960405/tickeringsystem/actions/runs/42332571762), sobre el commit `6a588fa`, confirmó `Tests run: 20, Failures: 0` para `ReservationConcurrencyIT`; los 13 tests de integración `*IT` también finalizaron correctamente. La prueba usa PostgreSQL real mediante Testcontainers.

## Hoja de ruta

- [x] Fase 0: esqueleto Maven multi-módulo, Maven Wrapper, Compose `infra` y configuración base.
- [x] Fase 1: dominio base, migraciones Flyway V1–V10, entidades/repositorios, CRUD, errores y OpenAPI.
- [x] Fase 2: autenticación/autorización JWT, roles, propiedad de recursos, refresh tokens y rate limiting.
- [x] Fase 3: reservas y órdenes. Inventario concurrente, expiración, idempotencia, webhook HMAC, pagos, cancelación y tickets. **Verificada y cerrada** con 13 tests `*IT` aprobados y `ReservationConcurrencyIT` (20 ejecuciones, 0 fallos) en [GitHub Actions CI run #62](https://github.com/dab960405/tickeringsystem/actions/runs/42332571762), commit `6a588fa`.
- [ ] Fase 4 (en curso): caché Redis de metadatos del catálogo con TTL configurable, invalidación transaccional y degradación ante fallos. La disponibilidad de inventario no se cachea.
- [ ] Fase 5: publicación transaccional outbox, Kafka y consumidor `notification-service`.
- [ ] Fase 6: calidad e infraestructura (Dockerfiles, Compose `full`, observabilidad/Grafana y revisión N+1).
- [ ] Fase 7: CI/CD y despliegue en AWS.
- [ ] Fase 8: pulido, documentación de operación y mejoras finales.

## Criterio permanente de cierre de fase

Una fase no se considera cerrada solo porque el código compile o la funcionalidad esté implementada. Su cierre exige:

1. README y hoja de ruta actualizados con estado real.
2. ERD actualizado cuando cambie el esquema o sus relaciones.
3. ADRs nuevos para las decisiones técnicas importantes, documentando contexto, alternativas, decisión y consecuencias; los ADRs históricos no se reescriben.
4. Evidencia de validación de los criterios de aceptación. Los tests no ejecutados se marcan como pendientes, nunca como aprobados.

La regla aplica a todas las fases siguientes y se debe cumplir antes de iniciar la fase posterior.

## Decisiones técnicas

Consulta el [registro ADR](docs/decisions.md). El ERD y el flujo de módulos están en [docs/erd.md](docs/erd.md) y [docs/architecture.md](docs/architecture.md).
