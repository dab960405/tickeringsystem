# Progreso del proyecto

Última actualización: 2026-11-06 · Rama de trabajo: `dev`

## Fase actual

**Fase 3 (reservas y órdenes): EN CURSO.** Las fases 0, 1 y 2 están completadas según sus criterios documentados. La rama `dev` contiene trabajo posterior a `main`; la retoma se hizo desde `origin/dev` sin sobrescribirlo.

| Criterio o alcance de Fase 3 | Estado |
| --- | --- |
| Máquina de estados de la orden y transiciones parametrizadas | Implementada en `OrderStatus`, con 50 tests de transiciones válidas e inválidas |
| Reserva con `UPDATE` atómico condicional, ventana de venta y TTL configurable | Implementada, con tests unitarios y de API |
| Expiración con lote, `FOR UPDATE SKIP LOCKED` y devolución de stock exactamente una vez | Implementada en `ReservationExpirationService`; existe `ReservationExpirationIT`, pendiente de ejecutarse con Docker |
| Test de concurrencia de 100 hilos repetido 20 veces | **Pendiente** |
| Órdenes desde reservas activas, precio copiado e idempotencia con `Idempotency-Key` | Implementadas en `OrderService` y `OrderController`, con tests unitarios y de API; falta prueba de integración |
| Webhook HMAC, confirmación/cancelación, generación de tickets y caso de reserva expirada sin stock | Implementados en `OrderPaymentService` y `PaymentWebhookService`, con tests unitarios y de API; falta prueba de integración |
| Cancelación de órdenes y consulta `GET /me/tickets` | **Pendiente** |
| Eventos mediante outbox | Pendiente; su implementación completa corresponde a la Fase 5 |

## Última implementación publicada

- `8f30485` `feat: process signed payment webhooks`.
- Último commit confirmado en `origin/dev` al retomar: `8f30485`.
- No se creó ningún recurso de AWS ni se modificaron ADRs anteriores.

## Última verificación

- `./mvnw -B clean verify -DskipITs`: **BUILD SUCCESS** con Temurin 21.0.13; 282 tests de `ticketing-api`, 5 de `common-events` y 1 de `notification-service` (**288 totales**, 0 fallos). JaCoCo y Spotless correctos.
- `./mvnw -B clean verify`: el primer intento no pudo iniciar porque el entorno no tenía Java ni `JAVA_HOME`. Se instaló Temurin 21.0.13 temporalmente fuera del repositorio. El build completo con `*IT` sigue sin poder ejecutarse aquí porque Docker no está instalado.
- GitHub Actions: la consulta con `gh run list` devolvió HTTP 403 (`Resource not accessible by integration`); no se afirma el estado de CI sin evidencia.

## Próximos 3 pasos

1. Añadir `ReservationConcurrencyIT`: 100 virtual threads, 10 entradas, exactamente 10 reservas y 90 conflictos, repetido 20 veces.
2. Añadir pruebas de integración para idempotencia de órdenes, webhook repetido, generación única de tickets y pago con reserva expirada.
3. Implementar cancelación de órdenes y `GET /me/tickets` con sus tests y reglas de propiedad.

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

- `./mvnw -B clean verify -DskipITs` con Temurin 21.0.13: **BUILD SUCCESS**; 288 tests, 0 fallos.
- Tests `*IT`: no ejecutados en esta sesión por ausencia de Docker; no se desactivó ni borró ninguno en el código.
- El test de concurrencia de 100 hilos todavía no existe y no se afirma su resultado.

## Problemas conocidos

- El entorno de la IA no tiene Docker: Testcontainers y Compose deben verificarse en GitHub Actions o en una máquina local con Docker.
- El token disponible para GitHub CLI no tiene permiso para leer ejecuciones de Actions.
- El job `docker-build` de CI llega en la Fase 6; todavía no hay Dockerfiles.
- La mensajería outbox/Kafka y `notification-service` corresponden a la Fase 5.

## Cambios locales sin publicar

Ninguno.
