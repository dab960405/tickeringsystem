# Progreso del proyecto

Última actualización: 2026-11-13 · Rama de trabajo: `dev`

## Estado actual

**Fase 4 abierta. Fase 5 bloqueada y no iniciada.** El cierre exige evidencia para H75–H84 y aprobación independiente; los pipelines verdes anteriores no demuestran ese cierre.

La auditoría reinició desde `ed0628982c3a9af10d919b8e02195c4ccf6fbe3b`, cuyo CI `43437152585` estaba en verde. En ese punto existían configuración operativa y mejoras de reservas/pagos, pero no las estructuras de H77/H78 ni las identidades persistentes de H80.

## Matriz de cierre H75–H84

| Requisito | Estado demostrado | Pendiente |
| --- | --- | --- |
| H75 — orden global de locks | Parcial: T → R en reservas; J → XA todavía no existe | Auditoría de todos los flujos y nuevos workers |
| H76 — protocolo T → R | Implementado en confirmación, expiración, liberación y recuperación; pruebas de carreras presentes | Validación integral con los componentes nuevos |
| H77 — unidades y generaciones | Pendiente | Modelo, backfill, emisión y reemisión |
| H78 — ciclos, jobs, ejecuciones, leases, fencing y proyecciones | Pendiente; solo existe `OperationProperties` | Persistencia, TX-RX/CLAIM/APPLY/PROJECT y recuperación |
| H79 — pagos tardíos | Parcial: recuperación con gracia y estados `UNFULFILLABLE`/`REQUIRED` | Integración con inbox, workers e idempotencia global |
| H80 — identidades e idempotencia persistente | **Implementado y probado localmente:** V14, inbox durable, tres identidades, digest y aplicación semántica única | Integración con procesamiento recuperable H78 y pruebas concurrentes |
| H81 — Redis | Parcial: caché de catálogo, login y degradación | Caché de eventos, claves de reserva y límites de reservas/órdenes de ADR-020 |
| H82 — configuración operativa | Propiedades validadas y probadas | Consumo por transacciones y workers reales |
| H83 — consistencia global de locks | Parcial | Auditoría después de H77/H78/H80 |
| H84 — reembolsos | Parcial: solicitud y confirmación con evidencia | Integración con identidades, inbox, reintentos y workers |

## Incremento H80 — V14

- `provider_events`, `provider_operations` y `semantic_applications` tienen restricciones únicas independientes y triggers append-only.
- `ProviderEventInboxService` registra la recepción en una transacción independiente y detecta reuso de identidad con payload o evidencia distinta.
- `SemanticApplicationService` reserva `(application_scope, operation_kind, idempotency_key)` en la misma transacción que los efectos de negocio; un duplicado con el mismo digest es no-op.
- `PaymentWebhookService` ya no depende únicamente del estado de la orden para deduplicar: persiste recepción e identidad semántica antes de devolver éxito.
- Validación local: suite unitaria completa en verde y `OrderPaymentFlowIT` con **10 tests, 0 fallos y 0 errores** usando PostgreSQL/Testcontainers. Los nuevos casos cubren webhook repetido, eventos distintos de una misma operación, digest conflictivo y evidencia append-only.
- Este incremento no implementa todavía ciclos, workers, leases ni recuperación; H80 permanece vinculado a H78 para el cierre.

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

1. H77: unidades comerciales y generaciones individuales.
2. H78/H82: ciclos, participaciones, jobs, ejecuciones, leases, fencing, disposiciones y proyecciones conectados a `OperationProperties`.
3. H80: concurrencia de webhooks y recuperación mediante workers; la evidencia V14 ya está persistida.
4. H81: alcance Redis de ADR-020.
5. H75/H76/H83: auditoría global de locks después de incorporar los componentes estructurales.
6. H79/H84: validación integral de pagos tardíos y reembolsos con el procesamiento recuperable.
7. Cancelación de eventos y transición de órdenes pendientes a `EXPIRED`, conservadas como deuda previa por resolver dentro del alcance autorizado cuando corresponda.
8. Refresh token sin rotación y webhook sin timestamp anti-replay.
9. Outbox/Kafka y consumidor de notificaciones pertenecen a Fase 5 y permanecen bloqueados.

## Verificación local y limitaciones

- Los tests de integración requieren Docker/Testcontainers; la evidencia de Fase 4 se obtuvo en GitHub Actions.
- [LOCAL_RUN](LOCAL_RUN.md) describe comandos y dependencias, pero los pasos con Docker Compose no se verificaron en el entorno de desarrollo asistido.
- [flow.http](flow.http) es manual y no se ejecuta en CI. La firma del webhook debe generarse sobre el cuerpo raw exacto.
- Desde el incremento V14 se ejecutan Maven, Spotless y Testcontainers localmente con Java 21 y Docker. La evidencia histórica anterior procede de CI.

## Próximos pasos

1. Implementar H78/H82 sobre el inbox V14: TX-RX, TX-CLAIM, TX-APPLY y TX-PROJECT, con leases, fencing y recuperación.
2. Implementar H77 y conectar emisión/reemisión a la aplicación de pagos.
3. Completar H81 y auditar H75/H76/H83.
4. Validar H79/H84, concurrencia, flujo completo, cobertura y CI.
5. Preparar el expediente de cierre; la aprobación formal sigue reservada al responsable del proyecto.
6. No iniciar Fase 5.

## Comandos de referencia

```bash
./mvnw test
./mvnw clean verify -DskipITs
./mvnw clean verify
docker compose --profile infra up -d
docker compose ps
```
