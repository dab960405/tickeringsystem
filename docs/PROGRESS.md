# Progreso del proyecto

Última actualización: 2026-11-14 · Rama de trabajo: `dev`

## Estado actual

**Fase 4 abierta. Fase 5 bloqueada y no iniciada.** El cierre exige evidencia para H75–H84 y aprobación independiente; los pipelines verdes anteriores no demuestran ese cierre.

La auditoría reinició desde `ed0628982c3a9af10d919b8e02195c4ccf6fbe3b`, cuyo CI `43437152585` estaba en verde. En ese punto existían configuración operativa y mejoras de reservas/pagos, pero no las estructuras de H77/H78 ni las identidades persistentes de H80.

## Matriz de cierre H75–H84

| Requisito | Estado demostrado | Pendiente |
| --- | --- | --- |
| H75 — orden global de locks | **Validado en CI #292:** auditoría de locks explícitos, FK implícitas y flushes JPA; secuencias existentes compatibles tras dos correcciones | Reabrir si cambian FK, locks o materialización de flush |
| H76 — protocolo T → R | **Validado en CI #292:** creación, cancelación, confirmación, recuperación, expiración y creación de orden mantienen T antes de R | Integrar en expediente final |
| H77 — unidades y generaciones | **Implementado y validado en CI:** V16, unidades comerciales estables, generaciones, backfill, emisión/reemisión y restricciones de integridad | Integrar en expediente final |
| H78 — ciclos, jobs, ejecuciones, leases, fencing y proyecciones | **Implementado y probado localmente:** V15, TX-RX/CLAIM/APPLY/PROJECT, renovación, reemplazo y recuperación | Validación integral final |
| H79 — pagos tardíos | Recuperación con gracia y estados `UNFULFILLABLE`/`REQUIRED`, integrados con workers | Validación integral con idempotencia global |
| H80 — identidades e idempotencia persistente | **Implementado y probado localmente:** V14+V15, inbox, tres identidades, digest, aplicación global única y webhooks concurrentes | Validación integral final |
| H81 — Redis | **Implementado y validado en CI:** caché de tipos/eventos, proyección de reservas, rate limiting login/reservas/órdenes y degradación | Cierre documental dentro del expediente integral de Fase 4 |
| H82 — configuración operativa | **Integrada:** timeouts, lock/statement timeout, lease, lote, reintentos, backoff y recuperación se consumen en procesos reales | Validación integral final |
| H83 — consistencia global de locks | **Validado en CI #292:** corregidas las inversiones implícitas EN → U en reemisión y R → T por FK en creación de orden | Reabrir ante cambios de esquema/locks/flush |
| H84 — reembolsos | Parcial: solicitud y confirmación con evidencia | Integración con identidades, inbox, reintentos y workers |

## Incremento H80 — V14

- `provider_events`, `provider_operations` y `semantic_applications` tienen restricciones únicas independientes y triggers append-only.
- `ProviderEventInboxService` registra la recepción en una transacción independiente y detecta reuso de identidad con payload o evidencia distinta.
- `SemanticApplicationService` reserva `(application_scope, operation_kind, idempotency_key)` en la misma transacción que los efectos de negocio; un duplicado con el mismo digest es no-op.
- `PaymentWebhookService` ya no depende únicamente del estado de la orden para deduplicar: persiste recepción e identidad semántica antes de devolver éxito.
- Validación local: suite unitaria completa en verde y `OrderPaymentFlowIT` con **10 tests, 0 fallos y 0 errores** usando PostgreSQL/Testcontainers. Los nuevos casos cubren webhook repetido, eventos distintos de una misma operación, digest conflictivo y evidencia append-only.
- Este incremento no implementa todavía ciclos, workers, leases ni recuperación; H80 permanece vinculado a H78 para el cierre.

## Incremento H78/H82 — V15

- V15 crea ciclos, participaciones, jobs, ejecuciones, disposiciones y proyecciones.
- `OperationTransactionExecutor` aplica los timeouts y límites de `OperationProperties` en transacciones RX, CLAIM, APPLY y PROJECT.
- `OperationJobService` implementa claims J → XA, renovación y expiración de leases, fencing, reemplazo de generaciones, backoff y agotamiento de intentos.
- `PaymentOperationService` aplica la identidad semántica y el negocio en una sola transacción; la recuperación proyecta evidencia sin repetir efectos.
- `OperationRecoveryWorker` procesa lotes configurables; el webhook conserva procesamiento síncrono y una ejecución superada por otro worker se trata como duplicado seguro.
- Validación específica actual: `OperationWorkerIT` **9 tests** y `OrderPaymentFlowIT` **16 tests**, todos sin fallos con PostgreSQL/Testcontainers en CI #278. Detalles históricos en [F4-02](phase-4/F4-02-worker-processing.md), cuya cifra de casos debe sincronizarse.

## Incremento H77 — V16

- V16 crea `commercial_generations`, `commercial_units` y `commercial_unit_generations`, añade identidad de unidad/generación a `tickets` y realiza backfill de tickets existentes.
- `TicketService` emite la generación inicial por unidad comercial, preserva la identidad de la unidad en reemisiones y cancela la generación activa al confirmar un reembolso.
- Las restricciones de base de datos impiden enlaces cruzados entre órdenes, más de una generación activa por orden y más de un ticket por unidad/generación.
- `CommercialIssuanceIT` contiene **14 tests** para compra multiunidad, idempotencia, emisión concurrente, reemisión, carreras con reembolso, restricciones y migración desde V15.
- CI #278, SHA `715af51fad107ede9e152e0ae7b63f151a806133`: `CommercialIssuanceIT` 14/14, `OperationWorkerIT` 9/9, `OrderPaymentFlowIT` 16/16; suite de integración 140/140 y Surefire de `ticketing-api` 388/388, sin fallos, errores ni omitidos. JaCoCo y Spotless correctos.
- H77 deja de clasificarse como “pendiente de implementación”; todavía participa en la auditoría global de locks H75/H83 y no implica cierre de Fase 4.

## Incremento H81 — cierre del alcance Redis de ADR-020

- La caché pública de eventos cubre detalle publicado y primera página popular con TTL de 60 s; la disponibilidad de inventario permanece en PostgreSQL.
- Las escrituras de eventos y tipos de entrada invalidan detalle/listado después del commit. Los cambios de venue publican un evento de aplicación y el módulo de eventos invalida sus caches en `AFTER_COMMIT`, evitando una dependencia directa `venue → event`.
- `ReservationRedisProjectionService` mantiene `ticketing:reservation:{id}` con `status=ACTIVE`, `expiresAt` y TTL restante. La escritura se programa después del commit y la clave se elimina tras cancelación, expiración o confirmación, incluidas las rutas de cancelación de evento y expiración de orden.
- La proyección de reserva es fail-open: errores Redis se registran y no alteran la transacción PostgreSQL.
- `ReservationController` aplica 20 creaciones/60 s por usuario y `OrderController` 10/60 s; login conserva 5/60 s. `RedisRateLimiter` usa incremento+expiración atómicos y degrada fail-open ante indisponibilidad Redis.
- CI #286, SHA `65c6abf13af7eb8c1195f41c0e25f9d9e5cd2277`: 390 unitarios y 147 integraciones de `ticketing-api`, sin fallos, errores ni omitidos. `EventCatalogCacheIT` 5/5, `RedisRateLimiterIT` 3/3 y `ReservationRedisProjectionIT` 5/5; Spotless y JaCoCo correctos; `BUILD SUCCESS`.
- H81 deja de clasificarse como pendiente técnico. Esto no implica cierre de Fase 4: H75/H76/H83 y H79/H84 continúan pendientes de validación integral.

## Auditoría H75/H76/H83 — F4-03

- Se auditaron los `service` y `repository` que adquieren locks explícitos y las escrituras que pueden introducir locks implícitos por claves foráneas o por flush de JPA. La matriz completa está en [F4-03](phase-4/F4-03-lock-audit.md).
- Se identificó una inversión potencial **EN → U** en reemisión: cancelar tickets antiguos antes de un `saveAndFlush` podía materializar UPDATE sobre `tickets` antes de las FK de la nueva generación hacia `commercial_units`. La corrección difiere la cancelación de los tickets antiguos hasta después de persistir enlaces U y nuevos EN.
- Se identificó una inversión **R → T** en creación de orden: `lockActiveForOrder` bloqueaba R y el posterior insert de `order_items` podía adquirir `KEY SHARE` sobre T por la FK `ticket_type_id`. La corrección hace explícito E → T → R antes de persistir O/P/I.
- `TicketServiceTest` fija el orden de reemisión y `ReservationServiceTest` fija E → T → R. `OrderCreationIntegrityIT` usa PostgreSQL real para demostrar que creación de orden espera en T antes de adquirir R.
- CI #292, SHA `24be7cbe670e7076d8e6999a9d708ef4cad09fe1`: 390 unitarios y 148 integraciones de `ticketing-api`, 0 fallos, 0 errores y 0 omitidos; `OrderCreationIntegrityIT` 5/5, `CommercialIssuanceIT` 14/14, `EventGuardConcurrencyIT` 9/9, `OperationWorkerIT` 9/9, `ReservationConcurrencyIT` 20/20 y `ReservationHttpConcurrencyIT` 20/20. Spotless y JaCoCo correctos; `BUILD SUCCESS`.
- **Dictamen técnico:** H75, H76 y H83 quedan validados para el modelo del SHA auditado. Deben reabrirse si una nueva migración añade FK relevantes, si aparece un nuevo lock explícito o si cambia el orden de flush de escrituras relacionadas.

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

## Estabilización de CI — runs #269 a #281

- CI #269 falló en `UserRepositoryIT` porque el slice `@DataJpaTest` no importaba `JpaAuditingConfig`; el resto de los repository slices que persisten entidades auditadas sí lo hacía.
- El commit `7629e51de62509a9fb1bab3f64070967f479dbfd` añadió la configuración de auditoría al slice y aserciones explícitas sobre `createdAt`/`updatedAt`. En CI #270, `UserRepositoryIT` pasó y la suite llegó a 387 unitarios y 140 integraciones sin fallos; el build quedó rojo únicamente por Spotless en archivos modificados por otros incrementos.
- Tras las correcciones posteriores, CI #280 sobre `82032baf563c84b0d85315bfd6fa955ee4412b84` completó 388 unitarios y 140 integraciones de `ticketing-api`, sin fallos, errores ni omitidos; Spotless y JaCoCo pasaron.
- CI #280 reveló un efecto secundario de lifecycle de tests: el contexto cacheado de `RedisOutageIT` conservaba activo `OrderExpirationService` después de detener su PostgreSQL dedicado, produciendo un error asíncrono contra un Hikari pool ya cerrado aunque el build terminaba en verde.
- El commit `cc72f1bc3de90ed58f66cfb6da4c75b522208130` aisló los schedulers de expiración en `RedisOutageIT`. CI #281 terminó en verde sobre ese SHA exacto: 388 unitarios y 140 integraciones de `ticketing-api`, `BUILD SUCCESS`, Spotless limpio y sin `too many clients already`, sin acceso tardío a `HikariPool-5` y sin `Unexpected error occurred in scheduled task`.
- Se mantiene `spring.test.context.cache.maxSize=4`; la evidencia actual muestra que permite completar la suite sin reproducir el agotamiento de conexiones observado en CI #268.

## Deuda técnica y trabajo diferido

1. H78/H79/H80/H82/H84: validación integral final de workers, pagos tardíos, identidades, configuración operativa y reembolsos.
2. `EventCancellationService` coordina reservas, inventario y órdenes pendientes, pero mantiene acceso directo a repositorios/tablas de otros módulos; requiere decisión arquitectónica o refactor antes de proponer el cierre formal de Fase 3.
3. Refresh token sin rotación y webhook sin timestamp anti-replay.
4. Outbox/Kafka y consumidor de notificaciones pertenecen a Fase 5 y permanecen bloqueados.

## Verificación local y limitaciones

- Los tests de integración requieren Docker/Testcontainers; la evidencia de Fase 4 se obtuvo en GitHub Actions.
- [LOCAL_RUN](LOCAL_RUN.md) describe comandos y dependencias, pero los pasos con Docker Compose no se verificaron en el entorno de desarrollo asistido.
- [flow.http](flow.http) es manual y no se ejecuta en CI. La firma del webhook debe generarse sobre el cuerpo raw exacto.
- Desde el incremento V14 se ejecutan Maven, Spotless y Testcontainers localmente con Java 21 y Docker. La evidencia histórica anterior procede de CI.

## Próximos pasos

1. Resolver o aceptar explícitamente el acceso directo entre módulos de `EventCancellationService` antes de proponer el cierre de Fase 3.
2. Validar integralmente H78/H79/H80/H82/H84 con la infraestructura y locks ya auditados.
3. Sincronizar el resto de la documentación con la evidencia actual.
4. Preparar los expedientes de cierre; la aprobación formal sigue reservada al responsable del proyecto.
5. No iniciar Fase 5.

## Comandos de referencia

```bash
./mvnw test
./mvnw clean verify -DskipITs
./mvnw clean verify
docker compose --profile infra up -d
docker compose ps
```

## Incremento de caché del catálogo público — commit 889a5c5

Integrado en `dev` el commit `889a5c5521c6f47f0604002c2d0425e31f534031` mediante avance fast-forward desde el HEAD anterior de `dev` (`5e9616458230c0ec5da49194854ff26c7afb7cf0`).

- Se añade caché Redis de metadatos de eventos publicados y de la primera página del catálogo público sin filtros, con TTL configurable de 60 segundos por defecto.
- La disponibilidad/cantidad de inventario no se almacena en caché: los tipos de entrada se consultan nuevamente.
- Se invalida la caché tras operaciones de escritura de eventos y tipos de entrada, respetando el commit transaccional.
- Se incorpora `ResilientRedisCacheWriter` para que fallos de operaciones Redis de caché no interrumpan el flujo de negocio.
- Se añade `EventCatalogCacheIT` con cobertura de TTL, lecturas cacheadas, invalidación tras commit y exclusión de consultas filtradas.
- CI del commit original: run #43532844492 reportado como exitoso en la verificación previa. La integración en `dev` requiere su propia ejecución CI; no se considera validada hasta que finalice en verde.

**Evolución posterior:** este incremento histórico fue completado por la proyección de reservas, los límites efectivos de reservas/órdenes y la invalidación post-commit ante cambios de venue. La evidencia integrada actual de H81 es CI #286 sobre `65c6abf13af7eb8c1195f41c0e25f9d9e5cd2277`. Fase 4 continúa abierta por otros requisitos.

## Fase 2 — auditoría de cierre y correcciones de autorización (2026-11-13)

**Estado: CERRADA FORMALMENTE el 2026-11-13**, tras autorización expresa del responsable del proyecto. Los criterios de aceptación de la especificación fueron contrastados con la matriz, el ADR-021, las pruebas y la evidencia de CI.

### Alcance realizado en dev

- Se añadió `docs/phase-2/authorization-matrix.md`, que clasifica las rutas funcionales por método HTTP, acceso, rol y reglas de propiedad.
- Se añadió ADR-021: los recursos privados ajenos e inexistentes responden 404 en las mismas operaciones de propiedad; se conserva 403 para denegación por rol.
- `EventAuthorizationService` ahora responde mediante `ResourceNotFoundException` cuando un ORGANIZER intenta gestionar un evento existente de otro organizador. ADMIN conserva acceso global; un evento inexistente sigue llegando al servicio de negocio y responde 404.
- `AuthenticationFlowIT` amplía la cobertura con tokens reales:
  - Dos organizadores: uno crea un borrador, el otro intenta modificarlo y recibe el mismo 404 que un ID inexistente; el propietario legítimo puede actualizarlo.
  - Dos usuarios: el usuario A crea reserva y orden; el usuario B no puede consultar/cancelar la orden ni cancelar la reserva. El propietario A conserva acceso a la lectura de su orden.
- README y registro de decisiones enlazan ADR-021 y la matriz, y dejan de declarar la Fase 2 cerrada anticipadamente.

### Commits de los incrementos

- `4ba7117c594e315e775247abe6ba6b0b0a6ddad3` — prueba de aislamiento entre organizadores con JWT reales.
- `f72fbe1d86646d4c827442c83b1693cdce4375cc` — prueba unitaria de ocultación de propiedad.
- `6890df100902b432fab06c5c251573a276782bbb` — implementación de ocultación de eventos ajenos.
- `40007149a5a53415f8e7aedd646f6278c96057b6` — matriz inicial de autorización.
- `b281bebf8df5c79e5b04a5b035133b445fae0385` — ADR-021.
- `de05bc43ca7b512ebf677f442794a0d8ad5916f5` — registro ADR actualizado.
- `3631e0407d1c92d43f5b23314b943a7a127fa9ec` — pruebas de aislamiento de órdenes y reservas con dos JWT reales.
- `57246b3722f323c37a4f8f9e8e203bb5c8223877` — matriz actualizada con cobertura añadida.
- `87d92f5a0f9c9c034c1b7aa910eb86d5289c81c0` — ADR-021 actualizado con la evidencia prevista.
- `75f69668859be13c3a5f8a76b5ddb98e892a4377` — README y roadmap corregidos.

### Verificación y fundamento del cierre

- La especificación original, sección 22, define la aceptación de Fase 2 como «matriz de tests por rol; un usuario no puede tocar recursos ajenos». No exige una suite única que cubra exhaustivamente el producto cartesiano método × rol × estado.
- La matriz clasifica las rutas funcionales por método y rol, y registra las restricciones de propiedad y respuestas esperadas.
- `AuthenticationFlowIT` verifica aislamiento entre dos organizadores y entre dos usuarios mediante JWT reales.
- CI #154, SHA `06490392e86866094ee9dddbff450f4e76e646ad`: 479 tests (379 Surefire y 100 Failsafe), 0 fallos, 0 errores y 0 omitidos; Spotless y JaCoCo correctos.
- CI #43545520476 validó el HEAD `7146517ef80f3fe350a57d00e272f66b59ffc923`: workflow completado con éxito, `BUILD SUCCESS`, Spotless correcto, checks JaCoCo cumplidos y 100 tests Failsafe sin fallos.
- La cobertura exhaustiva de todas las celdas método × rol × estado se conserva como mejora no bloqueante, no como criterio adicional inventado.

### Dictamen

Fase 2 queda cerrada formalmente con autorización expresa del responsable. La implementación, las pruebas de aislamiento con identidades reales, la documentación de permisos y la evidencia CI satisfacen los criterios de aceptación de la especificación. La matriz exhaustiva método × rol × estado queda como mejora no bloqueante. Este cierre no autoriza ni inicia Fase 5; Fase 4 continúa abierta y Fase 5 permanece bloqueada.


## Fase 2 — resultado final de CI tras corrección Spotless (2026-11-14)

**Evidencia añadida:** el workflow [CI run #154](https://github.com/dab960405/tickeringsystem/actions/runs/43543996946), asociado al commit \`06490392e86866094ee9dddbff450f4e76e646ad\` en \`dev\`, terminó con \`status=completed\` y \`conclusion=success\`.

- El job \`Build and test\` terminó correctamente.
- El paso \`Build, test and verify coverage\` terminó correctamente.
- Los pasos de publicación de reportes de tests y cobertura terminaron correctamente.
- El bloqueo anterior de Spotless quedó resuelto en los tres archivos Java identificados por el run fallido #153.
- Commits correctivos publicados individualmente en \`dev\`:
  - \`612ed9e2cba51ff2a392e96f01958256270be16a\` — formato de \`AuthenticationFlowIT.java\`.
  - \`460506a560470b4e96aba896046798db9f84f593\` — formato de \`EventAuthorizationServiceTest.java\`.
  - \`06490392e86866094ee9dddbff450f4e76e646ad\` — formato de \`EventAuthorizationService.java\`.

**Conteos verificados desde el artefacto `test-reports` del run #154:**

| Suite | Ejecutados | Fallidos | Errores | Omitidos |
| --- | ---: | ---: | ---: | ---: |
| Surefire — `ticketing-api` | 373 | 0 | 0 | 0 |
| Surefire — `common-events` | 5 | 0 | 0 | 0 |
| Surefire — `notification-service` | 1 | 0 | 0 | 0 |
| **Surefire total** | **379** | **0** | **0** | **0** |
| Failsafe — `ticketing-api` | 100 | 0 | 0 | 0 |
| **Total general** | **479** | **0** | **0** | **0** |

El artefacto `jacoco-reports` también fue publicado. El log de Maven confirma que los checks de cobertura JaCoCo se cumplieron en los módulos y que `spotless:check` finalizó correctamente. No se trasladan porcentajes históricos de cobertura al run #154.

**Estado de referencias:** el run #154 probó el SHA de código `06490392e86866094ee9dddbff450f4e76e646ad`. El HEAD de `dev` observado antes de esta actualización documental era `c25e0d323edf32e86b3590df0131d0fe87181b29`, que solo añadía este resultado al propio `docs/PROGRESS.md`; la comparación entre ambos SHA mostró exactamente ese único archivo modificado. Los commits documentales posteriores también requieren comprobar su CI antes de afirmar que el HEAD final está validado.

**Dictamen actualizado:** la validación automatizada completa de Maven \`clean verify\` pasó en el SHA indicado. Esto elimina el bloqueo de CI registrado para el cierre de Fase 2. La aprobación formal de cierre permanece reservada al responsable del proyecto; no implica iniciar Fase 5.
