# Progreso del proyecto

Última actualización: 2026-11-10 · Rama de trabajo: `dev`

## Estado actual

**Fase 4 — implementación completada, evidencia CI verde y documentación preparada; pendiente confirmación explícita de cierre.** Las Fases 1–3 permanecen completadas. La Fase 5 no ha comenzado.

El alcance real de Fase 4 es: caché Redis de metadatos del catálogo, fallback ante fallos Redis, rate limiting de login fail-open, refresh token store fail-closed y validación local de access tokens. No se implementó caché de eventos ni almacenamiento de reservas en Redis.

## Criterios de Fase 4 y evidencia

| Criterio | Implementación / evidencia | Estado |
| --- | --- | --- |
| Cache-aside de metadatos del catálogo con TTL configurable | `TicketTypeCatalogCacheService`; TTL `TICKET_TYPES_CACHE_TTL`, 5 minutos por defecto; [ADR-013](adr/ADR-013-redis-catalog-cache.md) | Verificado |
| No cachear disponibilidad de inventario | `availableQuantity` se consulta en PostgreSQL en cada lectura; PostgreSQL sigue siendo fuente de verdad | Verificado |
| Invalidación transaccional tras escrituras del catálogo | `RedisCacheManager.transactionAware()`; invalidación diferida hasta commit | Verificado |
| Fallback ante fallos Redis de caché | `CacheErrorHandler` maneja `DataAccessException` y `SerializationException` para GET/PUT/EVICT/CLEAR | Verificado |
| Expiración y recuperación del catálogo | `TicketTypeCatalogCacheIT` cubre hit, TTL y serialización con Redis real | Verificado |
| Rate limiting de login | Redis con ventana fija; fail-open ante `DataAccessException`; pruebas unitarias y de integración de rate limiter | Verificado |
| Refresh token store fail-closed | Guardar, comprobar o revocar refresh token falla con HTTP 503 si Redis no responde; `should_failClosedForAuthenticationSessionsDuringRedisOutage` | Verificado |
| Access JWT sin dependencia Redis | `should_acceptValidAccessTokenDuringRedisOutage` devuelve 200 durante la caída de Redis | Verificado |
| Invalidación fallida conserva dato hasta TTL | `should_serveStaleCatalogUntilTtlAfterFailedInvalidation` comprueba dato obsoleto y posterior actualización | Verificado |
| Timeout de comando Lettuce y aislamiento Redis | `should_configureShortCommandTimeoutOnLettuceClient`, `should_useDedicatedRedisContainerInSpringContext`; 500 ms | Verificado en contexto de integración |
| Documentación de operación y decisión | ADR-014, LOCAL_RUN.md y flow.http publicados; Compose no se verificó en entorno asistido | Documentado; Compose pendiente de verificación local |

### Evidencia cuantitativa del código

Fuente: artefactos `test-reports` y `jacoco-reports` del [CI run #107](https://github.com/dab960405/tickeringsystem/actions/runs/42954580181), asociado al SHA de código `24d6a514f1f8a4d467b1c457d12a478e24f75c36`.

| Suite | Ejecutados | Fallidos | Errores | Omitidos |
| --- | ---: | ---: | ---: | ---: |
| Surefire (unitarios, todos los módulos) | 313 | 0 | 0 | 0 |
| Failsafe (integración, `ticketing-api`) | 57 | 0 | 0 | 0 |
| **Total** | **370** | **0** | **0** | **0** |

Surefire por módulo: `ticketing-api` 307, `common-events` 5 y `notification-service` 1. Failsafe ejecutó 57 tests en `ticketing-api`. La clase `RedisOutageIT` ejecutó 7 tests, con 0 fallos, 0 errores y 0 omitidos.

JaCoCo — contadores agregados de paquetes de `ticketing-api`:

| Paquetes | Líneas cubiertas | Líneas no cubiertas | Cobertura de líneas | Ramas cubiertas | Cobertura de ramas |
| --- | ---: | ---: | ---: | ---: | ---: |
| `service` | 499 | 21 | **95,96 %** | 124 / 149 | 83,22 % |
| `domain` | 291 | 5 | **98,31 %** | 84 / 101 | 83,17 % |

Los porcentajes se calcularon como `covered / (covered + missed)` desde los contadores de los nodos `package` del XML JaCoCo de `ticketing-api`; no se mezclaron módulos ni se copiaron porcentajes de una ejecución anterior.

### Latencia observada en Redis outage

Las pruebas de indisponibilidad registraron aproximadamente:

- Catálogo con Redis pausado: **1.014 ms**.
- GET público del evento con Redis pausado: **1.023 ms**.

Los fallbacks mantienen la respuesta funcional, pero las operaciones Redis fallidas pueden consumir sus timeouts antes de continuar con PostgreSQL. Los valores son observaciones de prueba, no objetivos ni garantías de latencia.

## Historial de CI

| Run | SHA | Resultado | Interpretación |
| --- | --- | --- | --- |
| [#70](https://github.com/dab960405/tickeringsystem/actions/runs/42746676925) | `3054acf604e01fd8b4d18b1c909741e560b10274` | Failure | Spotless detectó formato en el incremento de caché. |
| [#71](https://github.com/dab960405/tickeringsystem/actions/runs/42747004968) | `93092b9a7696e0b1d0cb31868e3a567d0c121a6d` | Success | Corrección de formato; build verde. |
| [#105](https://github.com/dab960405/tickeringsystem/actions/runs/42952420841) | `cab03e43900550359567517ddbd9aa269fc115dc` | Success | Runner fijado a `ubuntu-24.04`. |
| [#106](https://github.com/dab960405/tickeringsystem/actions/runs/42953061576) | `a53514ec76c690dd8e34976425ff6458652485b1` | Failure | Spotless detectó formato en `RedisOutageIT`; las pruebas finalizaron sin fallos. |
| [#107](https://github.com/dab960405/tickeringsystem/actions/runs/42954580181) | `24d6a514f1f8a4d467b1c457d12a478e24f75c36` | Success | Corrección Spotless. **Run de referencia del código de Fase 4 y de los XML de evidencia.** |
| [#108](https://github.com/dab960405/tickeringsystem/actions/runs/42955123807) | `7d65089181309fa1a8c2ca641e85f24d4f3a150b` | Success | ADR-014 y registro de decisiones. |
| [#109](https://github.com/dab960405/tickeringsystem/actions/runs/42955586010) | `ac6e7d061c2115ff6485937dcb371bfb25e8e55b` | Success | Guía de ejecución local y flujo HTTP. |

El run #107 es la evidencia de código usada para los conteos y cobertura. Los runs #108 y #109 verifican los commits documentales posteriores. El run #78 ya no se presenta como último run verde.

## Decisiones registradas

- ADR-001 a ADR-009: decisiones históricas preservadas.
- ADR-010: actualización atómica condicional del inventario.
- ADR-011: idempotencia de órdenes y webhooks.
- ADR-012: expiración concurrente con `FOR UPDATE SKIP LOCKED`.
- ADR-013: caché Redis de metadatos del catálogo, TTL, invalidación transaccional y disponibilidad dinámica fuera de caché.
- ADR-014: degradación Redis diferenciada por riesgo; catálogo fallback, rate limiter fail-open, refresh store fail-closed y access JWT local.

## Deuda técnica y trabajo diferido

1. **Órdenes que nunca pasan a `EXPIRED`:** queda pendiente definir y verificar una transición explícita para órdenes pendientes que sobreviven a la expiración de la reserva.
2. **Reembolso sin devolución automática de stock:** se mantiene la decisión de ADR-009; cambiarla requiere una regla de negocio explícita.
3. **Webhooks concurrentes sin prueba de integración:** las transiciones son idempotentes, pero falta un escenario de concurrencia real que valide callbacks simultáneos.
4. **Rate limiting limitado a login:** no hay políticas para reservas ni órdenes.
5. **Sin caché de eventos ni claves Redis `reservation:{id}`:** se difiere para no ampliar consistencia distribuida ni crear una segunda fuente de verdad de reservas.
6. **Refresh token sin rotación:** el endpoint refresh emite un nuevo access token y conserva el mismo refresh token.
7. **Webhook sin timestamp anti-replay:** la firma HMAC valida el cuerpo, pero no existe ventana temporal ni protección basada en timestamp.
8. **Sin caché de `GET /events/{id}`:** pendiente una estrategia de invalidación y consistencia para la respuesta pública completa.
9. **Outbox/Kafka y consumidor de notificaciones:** pertenecen a Fase 5.

## Verificación local y limitaciones

- Los tests de integración requieren Docker/Testcontainers; la evidencia de Fase 4 se obtuvo en GitHub Actions.
- `docs/LOCAL_RUN.md` describe los comandos y dependencias, pero **los pasos con Docker Compose no se verificaron en el entorno de desarrollo asistido**.
- `docs/flow.http` es un flujo manual y no se ejecuta en CI. La firma del webhook debe generarse externamente sobre el cuerpo raw exacto.
- No se ejecutaron Maven ni Spotless en el entorno de desarrollo asistido; el estado reportado corresponde a CI.

## Próximos pasos

1. Revisar esta evidencia y confirmar explícitamente el cierre de Fase 4.
2. No iniciar Fase 5 hasta recibir esa confirmación.
3. Mantener los diferidos anteriores visibles en el backlog.

## Comandos de referencia

```bash
./mvnw test
./mvnw clean verify -DskipITs
./mvnw clean verify
docker compose --profile infra up -d
docker compose ps
```
