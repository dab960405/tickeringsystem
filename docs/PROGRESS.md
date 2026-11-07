# Progreso del proyecto

Última actualización: 2026-11-07 · Rama de trabajo: `dev`

## Fase actual

**Fase 3 (reservas y órdenes): COMPLETADA.** Los criterios de aceptación funcionales y de integración quedaron verificados en GitHub Actions CI, run #62, sobre el commit `6a588fa`. La siguiente fase es la Fase 4.

| Criterio o alcance de Fase 3 | Estado |
| --- | --- |
| Máquina de estados de la orden y transiciones parametrizadas | Implementada; tests unitarios verdes |
| Reserva con `UPDATE` atómico condicional, ventana de venta y TTL configurable | Implementada; tests unitarios y de API verdes |
| Expiración por lotes con `FOR UPDATE SKIP LOCKED` y devolución de stock condicional | Implementada y verificada; `ReservationExpirationIT` pasó en CI run #62 |
| Test de concurrencia de 100 virtual threads, 10 entradas y 20 repeticiones | Verificado en CI run #62: `Tests run: 20, Failures: 0` |
| Órdenes desde reservas activas, snapshot de precio e idempotencia con `Idempotency-Key` | Implementadas; integración aprobada en CI run #62 |
| Webhook HMAC, confirmación/cancelación, generación de tickets y caso de reserva expirada sin stock | Implementados; integración aprobada en CI run #62 |
| Cancelación de órdenes y consulta `GET /me/tickets` | Implementadas; integración aprobada en CI run #62 |
| Publicación outbox y consumo Kafka | Pendiente; alcance de Fase 5 |

## Deuda de documentación de Fases 1–3

- README y hoja de ruta sincronizados con el estado real.
- [ERD](erd.md) creado desde las migraciones Flyway V1–V10.
- [Arquitectura](architecture.md) documenta módulos y flujo reserva → orden → pago → tickets.
- ADR-010 documenta UPDATE atómico frente a bloqueo pesimista y optimista.
- ADR-011 documenta idempotencia de órdenes y webhooks, incluidos límites actuales.
- ADR-012 documenta expiración por lotes con `FOR UPDATE SKIP LOCKED`.
- Se establece el criterio permanente de cierre: README/roadmap, ERD cuando cambie el modelo, ADRs de decisiones importantes y evidencia de pruebas.

## Evidencia de verificación — GitHub Actions

- [CI run #62](https://github.com/dab960405/tickeringsystem/actions/runs/42332571762), commit `6a588faa32cd88b2510d4acb028fc5a3af8cd379`, rama `dev`: **completed / success**. El job `Build and test` terminó en **success**.
- Los logs de la ejecución reportan **13 tests de integración `*IT` aprobados**.
- `ReservationConcurrencyIT`: **Tests run: 20, Failures: 0**. Se ejecutaron las 20 repeticiones contra PostgreSQL real mediante Testcontainers.
- GitHub Actions publicó los artefactos `test-reports` y `jacoco-reports` para ese run.

## Próximos pasos

1. Iniciar la Fase 4: caché Redis del catálogo, TTL, invalidación y degradación cuando Redis no esté disponible.
2. Mantener la regla de publicar incrementos pequeños en `dev` y actualizar README/PROGRESS/ERD/ADRs con evidencia real.
3. No iniciar la Fase 5 hasta cerrar y verificar la Fase 4.

## Decisiones registradas

- ADR-001 a ADR-009: textos originales restaurados desde el historial Git y preservados; verificación de igualdad realizada contra el commit histórico `14dcc32`.
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

- El entorno de desarrollo asistido no dispone de Docker; los tests `*IT` de Fase 3 quedaron verificados en GitHub Actions. La verificación local de Compose/infraestructura sigue siendo independiente.
- El job `docker-build` de CI llega en la Fase 6; todavía no hay Dockerfiles.
- La integración outbox/Kafka y `notification-service` corresponde a la Fase 5.
