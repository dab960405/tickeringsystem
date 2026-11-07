# Progreso del proyecto

Última actualización: 2026-11-07 · Rama de trabajo: `dev`

## Fase actual

**Fase 4 (caché Redis del catálogo): EN CURSO.** La Fase 3 permanece completada y verificada en GitHub Actions CI, run #62. El primer incremento separa metadatos cacheables de disponibilidad dinámica y añade TTL configurable, invalidación transaccional y tolerancia a fallos de Redis.

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

## Fase 4 — Incremento 1: caché de metadatos del catálogo

- Caché-aside Redis para metadatos de tipos de entrada, con TTL configurable (`TICKET_TYPES_CACHE_TTL`, por defecto 5 minutos).
- `availableQuantity` queda fuera de Redis y se consulta siempre desde PostgreSQL para evitar servir inventario obsoleto.
- Las mutaciones del catálogo invalidan la caché; `RedisCacheManager.transactionAware()` difiere la invalidación hasta el commit.
- Los errores de acceso a Redis (`DataAccessException`) se registran y se omiten para que la lectura continúe contra PostgreSQL.
- Añadida prueba de integración `TicketTypeCatalogCacheIT` con Redis real para comprobar hit y expiración TTL. Pendiente de ejecución en CI.
- Pendiente validar invalidación desde el flujo de escritura y fallo/recuperación de Redis mediante pruebas de integración específicas.

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

1. Ejecutar CI para el primer incremento y corregir cualquier fallo antes de continuar.
2. Añadir pruebas de invalidación después del commit y degradación cuando Redis esté inaccesible.
3. Mantener commits pequeños en `dev` y no iniciar la Fase 5 hasta cerrar y verificar la Fase 4.

## Decisiones registradas

- ADR-001 a ADR-009: textos originales restaurados desde el historial Git y preservados; verificación de igualdad realizada contra el commit histórico `14dcc32`.
- ADR-010: actualización atómica condicional del inventario.
- ADR-011: idempotencia de órdenes y webhooks.
- ADR-012: expiración concurrente con `FOR UPDATE SKIP LOCKED`.
- ADR-013: caché Redis de metadatos del catálogo, TTL, invalidación transaccional y disponibilidad dinámica fuera de caché.
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
