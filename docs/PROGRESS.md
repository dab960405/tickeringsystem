# Progreso del proyecto

Última actualización: 2026-11-06 · Rama de trabajo: `dev`

## Fase actual

**Fase 3 (reservas y órdenes): EN CURSO.** Las fases 0, 1 y 2 están completadas según sus criterios documentados. La rama `dev` contiene trabajo posterior a `main`; la retoma se hizo desde `origin/dev` sin sobrescribirlo.

| Criterio o alcance de Fase 3 | Estado |
| --- | --- |
| Máquina de estados de la orden y transiciones parametrizadas | Implementada en `OrderStatus`; 50 tests unitarios verdes |
| Reserva con `UPDATE` atómico condicional, ventana de venta y TTL configurable | Implementada, con tests unitarios y de API verdes |
| Expiración con lote, `FOR UPDATE SKIP LOCKED` y devolución de stock exactamente una vez | Implementada en `ReservationExpirationService`; `ReservationExpirationIT` existe, pendiente de ejecutarse con Docker |
| Test de concurrencia de 100 virtual threads, 10 entradas y 20 repeticiones | Implementado en `ReservationConcurrencyIT` con `@RepeatedTest(20)`; pendiente de ejecutarse con Docker |
| Órdenes desde reservas activas, precio copiado e idempotencia con `Idempotency-Key` | Implementadas en `OrderService` y `OrderController`; `OrderPaymentFlowIT` existe, pendiente de ejecutarse con Docker |
| Webhook HMAC, confirmación/cancelación, generación de tickets y caso de reserva expirada sin stock | Implementados en `OrderPaymentService` y `PaymentWebhookService`; pruebas unitarias/API verdes e integración pendiente de Docker |
| Cancelación de órdenes y consulta `GET /me/tickets` | Implementadas con tests unitarios, de API e integración; integración pendiente de Docker |
| Eventos mediante outbox | Pendiente; su implementación completa corresponde a la Fase 5 |

## Última implementación publicada

- `5cedf6e` `test: cover order cancellation integration flows`.
- Último commit confirmado en `origin/dev` al retomar: `5cedf6e`.
- Esta actualización documental se publica en el siguiente commit de `dev`.
- No se creó ningún recurso de AWS ni se modificaron ADRs anteriores.

## Última verificación

- `./mvnw -B clean verify -DskipITs`: **BUILD SUCCESS** con Temurin 21.0.13; 293 tests de `ticketing-api`, 5 de `common-events` y 1 de `notification-service` (**299 totales**, 0 fallos). JaCoCo y Spotless correctos.
- `./mvnw -B test`: **BUILD SUCCESS**; los mismos 299 tests unitarios y de slice pasan.
- `./mvnw -B clean verify`: compila y ejecuta los tests no Docker, pero termina en **BUILD FAILURE** porque los 13 `*IT` no pueden iniciar Testcontainers: no existe Docker CLI, daemon ni socket en este entorno. No se desactivó ningún test.
- GitHub Actions: la consulta con `gh run list` devolvió anteriormente HTTP 403 (`Resource not accessible by integration`); no se afirma el estado de CI sin evidencia.

## Próximos 3 pasos

1. Ejecutar en GitHub Actions o en una máquina con Docker los 13 `*IT`, incluyendo las 20 repeticiones de `ReservationConcurrencyIT`, y corregir cualquier fallo real que aparezca.
2. Confirmar con esas ejecuciones la idempotencia de órdenes/webhooks, la expiración exactamente una vez y los flujos de cancelación/reembolso; solo entonces cerrar la Fase 3.
3. Después de cerrar la Fase 3, comenzar la Fase 4 con caché Redis, invalidación, TTL y prueba de degradación con Redis caído.

## Decisiones registradas

- ADR-006: `spring-boot-starter-oauth2-resource-server` con Nimbus y HS256.
- ADR-007: Redis desde la Fase 2 para refresh tokens y rate limiting de login.
- ADR-008: mapeo manual de DTOs con records.
- ADR-009: cancelación de órdenes y reembolsos.
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

- `./mvnw -B clean verify -DskipITs` con Temurin 21.0.13: **BUILD SUCCESS**; 299 tests, 0 fallos; cobertura y formato verificados.
- `./mvnw -B test` con Temurin 21.0.13: **BUILD SUCCESS**; 299 tests, 0 fallos.
- Tests `*IT`: intentados mediante `./mvnw -B clean verify`; 13 clases no pudieron inicializar Testcontainers por ausencia de Docker. No se afirma su resultado funcional.
- El test de concurrencia existe con 20 repeticiones, pero sus 20 ejecuciones con PostgreSQL real quedan pendientes de Docker; no se afirma que sean estables todavía.

## Problemas conocidos

- El entorno de la IA no tiene Docker: Testcontainers y Compose deben verificarse en GitHub Actions o en una máquina local con Docker.
- El token disponible para GitHub CLI no tiene permiso para leer ejecuciones de Actions.
- El job `docker-build` de CI llega en la Fase 6; todavía no hay Dockerfiles.
- La mensajería outbox/Kafka y `notification-service` corresponden a la Fase 5.

## Cambios locales sin publicar

Ninguno después de publicar este commit.
