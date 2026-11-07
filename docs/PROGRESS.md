# Progreso del proyecto

Última actualización: 2026-11-06 · Rama de trabajo: `dev`

## Fase actual

**Fase 3 (reservas y órdenes): EN CURSO.** El alcance funcional de las fases 1–3 está implementado en la rama `dev`. Las fases 1 y 2 se consideran completadas; la Fase 3 no se cerrará hasta validar en un entorno Docker los tests de integración pendientes.

| Criterio o alcance de Fase 3 | Estado |
| --- | --- |
| Máquina de estados de la orden y transiciones parametrizadas | Implementada; tests unitarios verdes |
| Reserva con `UPDATE` atómico condicional, ventana de venta y TTL configurable | Implementada; tests unitarios y de API verdes |
| Expiración por lotes con `FOR UPDATE SKIP LOCKED` y devolución de stock condicional | Implementada; `ReservationExpirationIT` requiere ejecución con Docker |
| Test de concurrencia de 100 virtual threads, 10 entradas y 20 repeticiones | Implementado en `ReservationConcurrencyIT`; requiere ejecución con Docker |
| Órdenes desde reservas activas, snapshot de precio e idempotencia con `Idempotency-Key` | Implementadas; integración pendiente de Docker |
| Webhook HMAC, confirmación/cancelación, generación de tickets y caso de reserva expirada sin stock | Implementados; integración pendiente de Docker |
| Cancelación de órdenes y consulta `GET /me/tickets` | Implementadas; integración pendiente de Docker |
| Publicación outbox y consumo Kafka | Pendiente; alcance de Fase 5 |

## Deuda de documentación de Fases 1–3

- README y hoja de ruta sincronizados con el estado real.
- [ERD](erd.md) creado desde las migraciones Flyway V1–V10.
- [Arquitectura](architecture.md) documenta módulos y flujo reserva → orden → pago → tickets.
- ADR-010 documenta UPDATE atómico frente a bloqueo pesimista y optimista.
- ADR-011 documenta idempotencia de órdenes y webhooks, incluidos límites actuales.
- ADR-012 documenta expiración por lotes con `FOR UPDATE SKIP LOCKED`.
- Se establece el criterio permanente de cierre: README/roadmap, ERD cuando cambie el modelo, ADRs de decisiones importantes y evidencia de pruebas.

## Última verificación registrada

- `./mvnw -B clean verify -DskipITs`: **BUILD SUCCESS** con Temurin 21.0.13; 299 tests unitarios/slice, 0 fallos; JaCoCo y Spotless correctos.
- `./mvnw -B test`: **BUILD SUCCESS**; 299 tests, 0 fallos.
- `./mvnw -B clean verify`: los tests que requieren Testcontainers no pudieron inicializarse en el entorno sin Docker. No se desactivaron ni se marcan como aprobados.
- `ReservationConcurrencyIT` tiene `@RepeatedTest(20)`; las 20 ejecuciones contra PostgreSQL real siguen pendientes de un runner con Docker.
- No se afirma que CI esté verde sin consultar una ejecución accesible.

## Próximos pasos

1. Ejecutar los tests `*IT` en GitHub Actions o en una máquina con Docker, incluyendo las 20 repeticiones de concurrencia.
2. Corregir cualquier fallo de integración real y repetir la suite completa.
3. Cerrar formalmente Fase 3 solo con evidencia verde; después iniciar Fase 4 (caché Redis, TTL, invalidación y degradación).

## Decisiones registradas

- ADR-001 a ADR-009: decisiones históricas conservadas sin modificación.
- ADR-010: actualización atómica condicional del inventario.
- ADR-011: idempotencia de órdenes y webhooks.
- ADR-012: expiración concurrente con `FOR UPDATE SKIP LOCKED`.
- La región de AWS sigue pendiente de decisión explícita; no se crean recursos cloud sin autorización.

## Comandos de verificación

```bash
./mvnw clean verify                      # build completo; requiere Docker para *IT
./mvnw clean verify -DskipITs            # unitarios/slices, cobertura y formato
docker compose --profile infra up -d     # dependencias locales
docker compose ps                        # todos deben aparecer healthy
```

## Problemas conocidos

- El entorno de desarrollo asistido no dispone de Docker: Testcontainers y Compose deben verificarse en GitHub Actions o localmente.
- El job `docker-build` de CI llega en la Fase 6; todavía no hay Dockerfiles.
- La integración outbox/Kafka y `notification-service` corresponde a la Fase 5.
