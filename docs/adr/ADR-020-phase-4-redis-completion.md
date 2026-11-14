# ADR-020: Cierre de alcance Redis de Fase 4

- **Estado:** Aceptado
- **Fecha:** 2026-11-13
- **Fase:** 4
- **Relación:** sustituye las tres desviaciones diferidas de ADR-014; conserva su degradación diferenciada.

## Contexto

ADR-014 dejó sin implementar caché de eventos, claves `reservation:{id}` y rate limiting de reservas/órdenes. El encargo de cierre no permite presentar esas desviaciones como criterios cumplidos.

## Decisión

### Caché pública de eventos

- Cachear metadatos del detalle `GET /events/{id}` y del listado público inicial más común con prefijo `ticketing:` y TTL de 60 segundos.
- No cachear `availableQuantity`: cada respuesta combina metadatos con disponibilidad leída de PostgreSQL.
- Invalidar detalle/listados al crear, editar, publicar o cancelar eventos y al modificar sus tipos de entrada.
- Los listados con filtros/paginación no considerados populares pueden consultar PostgreSQL directamente para evitar claves ilimitadas.

### Reservas temporales

- Escribir `ticketing:reservation:{id}` con un record mínimo (`status`, `expiresAt`) y TTL igual al restante de la reserva.
- Escribir después del commit y eliminar/actualizar tras cancelación, expiración o confirmación. Un fallo Redis se registra y no revierte la transacción PostgreSQL.
- La clave es una proyección de consulta/aviso, nunca autoridad para vender, confirmar o autorizar. Si falta o Redis cae, PostgreSQL responde.

### Rate limiting

- Conservar login: 5 intentos/60 s por IP+email.
- Reservas: 20 creaciones/60 s por usuario.
- Órdenes: 10 creaciones/60 s por usuario.
- Las claves usan ventana fija y expiración atómica. Al exceder, HTTP 429.
- Ante indisponibilidad Redis, estas políticas son fail-open; PostgreSQL mantiene las invariantes de stock e idempotencia. El riesgo de fuerza bruta de login durante outage permanece documentado en ADR-014.

## Consecuencias

- La degradación se prueba con Redis pausado y se verifican hits, TTL e invalidación con Redis real.
- Ninguna escritura comercial depende de que la proyección Redis se haya completado.
- Los refresh tokens siguen fail-closed y los access tokens se validan localmente.

## Evidencia de implementación

- Caché pública de eventos: `EventCatalogCacheIT` cubre TTL, exclusión de inventario, invalidación post-commit por evento/tipo de entrada y, desde el cierre de H81, invalidación tras cambios de venue mediante evento de aplicación.
- Reservas temporales: `ReservationRedisProjectionService` escribe `ticketing:reservation:{id}` solo después del commit, con `status`, `expiresAt` y TTL restante; elimina la proyección al resolver la reserva. `ReservationRedisProjectionIT` cubre commit, TTL, cancelación, confirmación y expiración.
- Rate limiting: `RedisRateLimiterIT` demuestra los límites independientes de reservas y órdenes, TTL de ventana y reinicio del contador; los controllers aplican el límite antes de crear recursos.
- Degradación: la proyección de reservas y el rate limiter toleran indisponibilidad Redis sin revertir la operación PostgreSQL; el refresh token store conserva la política fail-closed de ADR-014.
- CI #286, SHA `65c6abf13af7eb8c1195f41c0e25f9d9e5cd2277`: 390 unitarios y 147 integraciones de `ticketing-api`, 0 fallos, 0 errores y 0 omitidos; `EventCatalogCacheIT` 5/5, `RedisRateLimiterIT` 3/3 y `ReservationRedisProjectionIT` 5/5; Spotless y JaCoCo correctos; `BUILD SUCCESS`.

Esta evidencia completa el alcance técnico de H81. No implica por sí sola el cierre de Fase 4.
