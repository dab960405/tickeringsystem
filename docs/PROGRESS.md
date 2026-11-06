# Progreso del proyecto

Última actualización: 2026-11-06 · Rama de trabajo: `dev`

## Fase actual

**Fase 3 (reservas y órdenes): EN CURSO.** Las fases 0, 1 y 2 quedaron completadas según la documentación y la CI publicada anteriormente (`727330c`). La rama remota `dev` ya contenía trabajo adicional respecto a `main`; se retomó desde `e03a9f8` sin sobrescribirlo.

| Criterio de Fase 3 | Estado |
| --- | --- |
| Máquina de estados de la orden y transiciones parametrizadas | Implementada en `OrderStatus`, con tests de transiciones válidas e inválidas |
| Reserva con `UPDATE` atómico condicional, ventana de venta y TTL configurable | Implementada, con tests unitarios y de API |
| Expiración con lote, `FOR UPDATE SKIP LOCKED` y devolución de stock exactamente una vez | Implementada en `ReservationExpirationService`; existe `ReservationExpirationIT` |
| Test de concurrencia de 100 hilos repetido 20 veces | Pendiente |
| Órdenes desde reservas activas e idempotencia con `Idempotency-Key` | Pendiente |
| Webhook HMAC, confirmación/cancelación y generación de tickets | Pendiente |
| Eventos de dominio mediante outbox para estas operaciones | Pendiente |

## Última implementación publicada

- `e03a9f8` `feat: expire reservations with locked stock restoration`.
- La rama local está basada en `origin/dev`; no se creó ningún recurso de AWS ni se modificaron ADRs anteriores.

## Última verificación y publicación

- `./mvnw -B verify -DskipITs`: **BUILD SUCCESS** con Temurin 21.0.13; 213 tests de `ticketing-api`, 5 de `common-events` y 1 de `notification-service` (219 totales), 0 fallos; JaCoCo y Spotless correctos.
- `./mvnw -B clean verify`: compilación y 219 tests unitarios/slices correctos; los 11 `*IT` fallaron al iniciar Testcontainers porque este entorno no tiene Docker (`Could not find a valid Docker environment`). No se desactivó ningún test.
- Último commit disponible en `origin/dev` al iniciar esta sesión: `e03a9f8b524e0eafb72364d60d1acccc234fd0c7`.
- El estado de GitHub Actions para `e03a9f8` no se ha comprobado en esta sesión.

## Próximos 3 pasos

1. Añadir entidades y repositorios de `order`, `order_items`, `payments` y `tickets`, reutilizando las migraciones existentes.
2. Implementar creación de órdenes desde reservas activas con precio copiado e idempotencia por usuario y payload.
3. Implementar webhook HMAC idempotente, generación de tickets y pruebas de concurrencia del flujo de reservas.

## Decisiones registradas

- ADR-006: `spring-boot-starter-oauth2-resource-server` con Nimbus y HS256.
- ADR-007: Redis desde la Fase 2 para refresh tokens y rate limiting de login.
- ADR-008: mapeo manual de DTOs con records.
- No se modificaron ADRs anteriores ni se creó ningún recurso de AWS.

## Decisiones pendientes del usuario

- **Región de AWS:** se elegirá en la etapa 0 de la pista AWS. No se creará ningún recurso sin confirmación explícita.

## Comandos de verificación

```bash
./mvnw clean verify                      # build completo (requiere Docker para los *IT)
./mvnw clean verify -DskipITs            # unitarios, slices, formato y cobertura sin Docker
docker compose --profile infra up -d     # dependencias locales
docker compose ps                        # todos deben aparecer healthy
```

## Tests ejecutados y resultados reales

- `./mvnw -B verify -DskipITs` con Temurin 21.0.13: **BUILD SUCCESS**; 219 tests, 0 fallos.
- `./mvnw -B clean verify`: 11 tests de integración con errores de inicialización por ausencia de Docker; no se ejecutó ninguna lógica de Testcontainers.
- No se ejecutaron todavía los nuevos tests de órdenes ni el test de concurrencia de 100 hilos.

## Problemas conocidos

- El entorno de la IA no tiene Docker: Testcontainers y Compose deben verificarse en GitHub Actions o en una máquina local con Docker.
- Java no estaba instalado al iniciar esta retoma; se descargó Temurin 21.0.13 fuera del repositorio. No se añadieron binarios ni secretos al proyecto.
- El job `docker-build` de CI llega en la Fase 6; todavía no hay Dockerfiles.
- La Fase 3 todavía no tiene implementación de órdenes, pagos ni tickets.

## Cambios locales sin publicar

Esta actualización documental se publicará como el siguiente commit `docs:` en `dev`.
