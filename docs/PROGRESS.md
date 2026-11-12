# Progreso del proyecto

Última actualización: 2026-11-12 · Rama de trabajo: `dev`

## Estado actual

**Fases 0–2 completadas. Fase 3: alcance principal implementado; hardening de cancelación de eventos pendiente. Fase 4: implementación y evidencia CI completadas; cierre formal pendiente de confirmación explícita. Fase 5 no iniciada.**

El alcance real de Fase 4 es: caché Redis de metadatos del catálogo, fallback ante fallos Redis, rate limiting de login fail-open, refresh token store fail-closed y validación local de access tokens. No se implementó caché de eventos ni almacenamiento de reservas en Redis.

## Cambios de integridad de órdenes — V11 y ADR-015

**Implementado en código y esquema:**

- `OrderService.create` comprueba si ya existe una orden para la reserva antes de crear otra.
- Flyway V11 crea el índice único no parcial `ux_orders_reservation_id` sobre `orders(reservation_id)`.
- La restricción se aplica a todas las órdenes, incluidas las que están en estado `CANCELLED`.
- Una reserva cuya orden fue cancelada no se puede reutilizar; una compra posterior requiere una reserva nueva.
- Los conflictos de integridad se mapean a HTTP `409 Conflict`.
- La decisión está registrada en [ADR-015](adr/ADR-015-one-order-per-reservation.md).

**Verificado mediante CI:** el commit de código `aabc02301deda4b983c5f3e56af00ed4ecd717ec`, run #116, incluye `OrderCreationIntegrityIT` (4 tests). La ejecución completó correctamente. La carrera con la misma clave se documenta con la salvedad de ADR-015: el log del job permite inferir el conflicto de unicidad, pero no registra directamente los códigos HTTP individuales.

## Criterios de Fase 4 y evidencia

| Criterio | Implementación / evidencia | Estado |
| --- | --- | --- |
| Cache-aside de metadatos del catálogo con TTL configurable | `TicketTypeCatalogCacheService`; TTL `TICKET_TYPES_CACHE_TTL`, 5 minutos por defecto; [ADR-013](adr/ADR-013-redis-catalog-cache.md) | Verificado |
| No cachear disponibilidad de inventario | `availableQuantity` se consulta en PostgreSQL en cada lectura | Verificado |
| Invalidación transaccional tras escrituras del catálogo | `RedisCacheManager.transactionAware()`; invalidación diferida hasta commit | Verificado |
| Fallback ante fallos Redis de caché | `CacheErrorHandler` maneja `DataAccessException` y `SerializationException` para GET/PUT/EVICT/CLEAR | Verificado |
| Expiración y recuperación del catálogo | `TicketTypeCatalogCacheIT` cubre hit, TTL y serialización con Redis real | Verificado |
| Rate limiting de login | Redis con ventana fija; fail-open ante `DataAccessException`; pruebas unitarias y de integración | Verificado |
| Refresh token store fail-closed | Operaciones de refresh fallan con HTTP 503 si Redis no responde; `should_failClosedForAuthenticationSessionsDuringRedisOutage` | Verificado |
| Access JWT sin dependencia Redis | `should_acceptValidAccessTokenDuringRedisOutage` devuelve 200 durante la caída de Redis | Verificado |
| Invalidación fallida conserva dato hasta TTL | `should_serveStaleCatalogUntilTtlAfterFailedInvalidation` comprueba dato obsoleto y posterior actualización | Verificado |
| Timeout de Lettuce y aislamiento Redis | `should_configureShortCommandTimeoutOnLettuceClient`, `should_useDedicatedRedisContainerInSpringContext`; 500 ms | Verificado en integración |
| Documentación de operación y decisión | ADR-014, LOCAL_RUN.md y flow.http publicados; Compose no se verificó en entorno asistido | Documentado; Compose pendiente de verificación local |

### Evidencia cuantitativa del código

Fuente: artefactos `test-reports` y `jacoco-reports` del [CI run #116](https://github.com/dab960405/tickeringsystem/actions/runs/43046441594), asociado al SHA `aabc02301deda4b983c5f3e56af00ed4ecd717ec`. GitHub Actions registró `status=completed` y `conclusion=success`.

| Suite | Ejecutados | Fallidos | Errores | Omitidos |
| --- | ---: | ---: | ---: | ---: |
| Surefire (unitarios, todos los módulos) | 315 | 0 | 0 | 0 |
| Failsafe (integración, `ticketing-api`) | 61 | 0 | 0 | 0 |
| **Total** | **376** | **0** | **0** | **0** |

Surefire por módulo: `ticketing-api` 309, `common-events` 5 y `notification-service` 1. Failsafe ejecutó 61 tests en `ticketing-api`.

JaCoCo — contadores agregados de paquetes de `ticketing-api`:

| Paquetes | Líneas cubiertas | Líneas no cubiertas | Cobertura de líneas | Ramas cubiertas | Cobertura de ramas |
| --- | ---: | ---: | ---: | ---: | ---: |
| `service` | 501 | 21 | **95,98 %** | 126 / 151 | 83,44 % |
| `domain` | 291 | 5 | **98,31 %** | 84 / 101 | 83,17 % |

Los porcentajes se calcularon como `covered / (covered + missed)` desde los contadores de los nodos `package` del XML JaCoCo de `ticketing-api` del artefacto del run #116.

### Latencia observada en Redis outage

- Catálogo con Redis pausado: **1.014 ms**.
- GET público del evento con Redis pausado: **1.023 ms**.

Son observaciones de prueba, no objetivos ni garantías de latencia.

## Historial de CI

| Run | SHA | Resultado | Interpretación |
| --- | --- | --- | --- |
| [#70](https://github.com/dab960405/tickeringsystem/actions/runs/42746676925) | `3054acf604e01fd8b4d18b1c909741e560b10274` | Failure | Spotless detectó formato en el incremento de caché. |
| [#71](https://github.com/dab960405/tickeringsystem/actions/runs/42747004968) | `93092b9a7696e0b1d0cb31868e3a567d0c121a6d` | Success | Corrección de formato; build verde. |
| [#105](https://github.com/dab960405/tickeringsystem/actions/runs/42952420841) | `cab03e43900550359567517ddbd9aa269fc115dc` | Success | Runner fijado a `ubuntu-24.04`. |
| [#106](https://github.com/dab960405/tickeringsystem/actions/runs/42953061576) | `a53514ec76c690dd8e34976425ff6458652485b1` | Failure | Spotless detectó formato en `RedisOutageIT`; las pruebas finalizaron sin fallos. |
| [#107](https://github.com/dab960405/tickeringsystem/actions/runs/42954580181) | `24d6a514f1f8a4d467b1c457d12a478e24f75c36` | Success | Evidencia anterior de Fase 4; conteos reemplazados por run #116. |
| [#108](https://github.com/dab960405/tickeringsystem/actions/runs/42955123807) | `7d65089181309fa1a8c2ca641e85f24d4f3a150b` | Success | ADR-014 y registro de decisiones. |
| [#109](https://github.com/dab960405/tickeringsystem/actions/runs/42955586010) | `ac6e7d061c2115ff6485937dcb371bfb25e8e55b` | Success | Guía local y flujo HTTP. |
| [#116](https://github.com/dab960405/tickeringsystem/actions/runs/43046441594) | `aabc02301deda4b983c5f3e56af00ed4ecd717ec` | Success | **Run de referencia del código y de los XML de tests/cobertura.** |
| [#117](https://github.com/dab960405/tickeringsystem/actions/runs/43048762317) | `c26a8cf5fa4b618ddd88063c05696bbe9a7ca43a` | Success | Actualización documental previa. |
| [#118](https://github.com/dab960405/tickeringsystem/actions/runs/43264556420) | `244e998c6888f1f29e84c5dd2b6a27f8d8895e60` | Success | ADR-015 y corrección de ADR-011; sin cambios funcionales. |

El run #116 es la evidencia de código usada para los conteos y cobertura. Los runs #117 y #118 verifican commits documentales posteriores y no sustituyen la evidencia cuantitativa del código.

## Decisiones registradas

- ADR-001 a ADR-009: decisiones históricas preservadas.
- ADR-010: actualización atómica condicional del inventario.
- ADR-011: idempotencia de órdenes y webhooks.
- ADR-012: expiración concurrente con `FOR UPDATE SKIP LOCKED`.
- ADR-013: caché Redis de metadatos del catálogo.
- ADR-014: resiliencia Redis y degradación diferenciada de autenticación.
- ADR-015: una orden por reserva, con guard de aplicación e índice único PostgreSQL en V11.

## Deuda técnica y trabajo diferido

1. **Cancelación de eventos incompleta:** `EventService.cancel()` no coordina todavía reservas, órdenes pendientes, pagos, tickets ni la transición/evento correspondiente. Requiere hardening y pruebas específicas.
2. **Órdenes que nunca pasan a `EXPIRED`:** pendiente definir y verificar una transición explícita para órdenes pendientes que sobreviven a la expiración de la reserva.
3. **Reembolso sin devolución automática de stock:** se mantiene ADR-009; cambiarlo requiere una regla de negocio explícita.
4. **Webhooks concurrentes sin prueba de integración:** falta validar callbacks simultáneos.
5. **Rate limiting limitado a login:** no hay políticas para reservas ni órdenes.
6. **Sin caché de eventos ni claves Redis `reservation:{id}`.**
7. **Refresh token sin rotación:** refresh conserva el mismo refresh token.
8. **Webhook sin timestamp anti-replay.**
9. **Sin caché de `GET /events/{id}`.**
10. **Outbox/Kafka y consumidor de notificaciones:** pertenecen a Fase 5.

## Verificación local y limitaciones

- Los tests de integración requieren Docker/Testcontainers; la evidencia de Fase 4 se obtuvo en GitHub Actions.
- [LOCAL_RUN](LOCAL_RUN.md) describe comandos y dependencias, pero los pasos con Docker Compose no se verificaron en el entorno de desarrollo asistido.
- [flow.http](flow.http) es manual y no se ejecuta en CI. La firma del webhook debe generarse sobre el cuerpo raw exacto.
- No se ejecutaron Maven ni Spotless en el entorno de desarrollo asistido; el estado reportado corresponde a CI.

## Próximos pasos

1. Ejecutar el hardening pendiente del ciclo de cancelación de eventos bajo una tarea autorizada independiente. Esta actualización documental no autoriza ni ejecuta esa implementación.
2. Verificar los criterios de aceptación pendientes de las fases anteriores.
3. Realizar la revisión formal de cierre de Fase 4.
4. Esperar la confirmación explícita del cierre de Fase 4.
5. No iniciar Fase 5 antes de completar y autorizar el cierre de Fase 4.
6. Mantener los demás diferidos visibles en el backlog.

## Comandos de referencia

```bash
./mvnw test
./mvnw clean verify -DskipITs
./mvnw clean verify
docker compose --profile infra up -d
docker compose ps
```
