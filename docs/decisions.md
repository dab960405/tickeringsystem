# Registro de decisiones (ADR)

Formato: contexto, alternativas, decisión y consecuencias. Las decisiones se numeran y no se borran ni reescriben; si una decisión cambia, se añade otra que la sustituya. Los ADR individuales nuevos están en [`docs/adr/`](adr/).

---

## ADR-001: Spring Boot 3.5.x en lugar de Spring Boot 4

- **Fecha:** 2026-11-05
- **Contexto:** a la fecha existe Spring Boot 4.1.x estable. La especificación indica usar 3.5.x por defecto salvo que todas las dependencias soporten Boot 4.
- **Decisión:** usar Spring Boot **3.5.16** (última estable de la rama 3.x) con Java 21.
- **Consecuencias:** máxima compatibilidad con springdoc 2.8.x, Testcontainers 1.21.x y la documentación disponible. Migrar a Boot 4 queda como mejora futura documentada.

## ADR-002: Versiones de herramientas fijadas en la Fase 0

- **Fecha:** 2026-11-05
- **Contexto:** la especificación exige verificar la última versión estable y fijarla explícitamente (nunca `latest`).
- **Decisión:** versiones fijadas en el POM padre y en `docker-compose.yml`:

| Herramienta | Versión | Nota |
| --- | --- | --- |
| Maven (wrapper) | 3.9.16 | Maven Wrapper 3.3.4, tipo `only-script` |
| Spring Boot | 3.5.16 | ver ADR-001 |
| Testcontainers BOM | 1.21.4 | 2.0.x cambia nombres de artefactos; 1.21.x es la línea soportada por Boot 3.5 |
| springdoc-openapi | 2.8.17 | 3.x es para Spring Boot 4 |
| JaCoCo | 0.8.16 | |
| Spotless + google-java-format | 3.11.0 + 2.0.0 | Spotless 2.46 no es compatible con google-java-format 2.x |
| maven-enforcer-plugin | 3.7.0 | Exige Java 21 y Maven >= 3.9 |
| PostgreSQL | `postgres:17.11-alpine` | |
| Redis | `redis:7.4.12-alpine` | La especificación pide Redis 7 |
| Kafka | `apache/kafka:4.4.0` | Modo KRaft; los clientes 3.9 de Boot 3.5 son compatibles con brokers 4.x |
| MongoDB | `mongo:7.0.45` | La especificación pide MongoDB 7 |

- **Consecuencias:** Dependabot (Fase 7) propondrá las actualizaciones posteriores.

## ADR-003: Paquete base y nombres de módulos

- **Fecha:** 2026-11-05
- **Decisión:** `com.portfolio.ticketing` (API), `com.portfolio.notification` (notificaciones) y `com.portfolio.events` (contratos compartidos). El envoltorio de eventos `EventEnvelope<T>` vive en `common-events` para no duplicar el contrato.
- **Consecuencias:** los dos servicios dependen de `common-events`, pero no entre sí.

## ADR-004: Umbral de cobertura configurado desde el inicio

- **Fecha:** 2026-11-05
- **Contexto:** el umbral del 70 % aplica a los paquetes `service` y `domain`.
- **Decisión:** la regla de JaCoCo se configura ya en la Fase 0 por paquete (`*.service`, `*.domain`) sobre los datos combinados de tests unitarios e integración. Mientras no existan esos paquetes la regla no tiene clases que evaluar y pasa.
- **Consecuencias:** desde la Fase 1 cualquier código de negocio sin tests rompe el build.

## ADR-005: Workflow de CI adelantado y rama `dev`

- **Fecha:** 2026-11-05
- **Contexto:** la especificación sitúa los workflows en la Fase 7, pero el usuario necesita una verificación independiente (`./mvnw -B clean verify` en runners con Docker) desde ahora, porque el entorno de desarrollo asistido no siempre tiene Docker para Testcontainers.
- **Decisión:** crear ya `.github/workflows/ci.yml` con el job `build-test` (Temurin 21, caché Maven, `permissions: contents: read`, `concurrency`, subida de reportes de Surefire/Failsafe y JaCoCo). El job `docker-build` se añadirá en la Fase 6, cuando existan los Dockerfiles, y `deploy.yml` en la Fase 7. El trabajo se hace en la rama `dev`; `main` solo recibe cambios verificados por CI. Actions fijadas por tag mayor: `checkout@v7`, `setup-java@v6`, `upload-artifact@v7`.
- **Consecuencias:** cada push y pull request queda verificado en GitHub desde la Fase 0. La Fase 7 sigue pendiente de `docker-build`, `deploy.yml`, Dependabot y el badge.

## ADR-006: JWT con `spring-boot-starter-oauth2-resource-server` y HS256

- **Fecha:** 2026-11-05
- **Contexto:** la Fase 2 necesita emitir y validar JWT. Opciones: `jjwt` o el soporte nativo de Spring Security (Nimbus).
- **Decisión:** `spring-boot-starter-oauth2-resource-server` con `NimbusJwtEncoder`/`NimbusJwtDecoder` y HS256. El secreto se lee de `JWT_SECRET` (Base64, mínimo 256 bits; el arranque falla si es más corto). Claims: `sub` (id de usuario), `role`, `iat`, `exp` y `type` (`access` o `refresh`); el refresh token añade `jti`. El decodificador de acceso rechaza tokens con `type` distinto de `access`.
- **Consecuencias:** sin dependencias extra ni filtro JWT propio; la validación de firma y expiración la hace el filtro estándar `BearerTokenAuthenticationFilter`. Decidido con el usuario.

## ADR-007: Redis desde la Fase 2 para refresh tokens y rate limiting de login

- **Fecha:** 2026-11-05
- **Contexto:** la especificación (sección 9) guarda el hash del `jti` del refresh token en Redis para poder revocarlo y pide rate limiting en login con contador en Redis, pero Redis se plantea en la Fase 4.
- **Decisión:** añadir `spring-boot-starter-data-redis` en la Fase 2. Clave `auth:refresh:<sha256(jti)>` con TTL igual a la vida del refresh token; logout la borra. Rate limiting de login con ventana fija: `INCR` sobre `rate:login:<ip>:<email>` y `EXPIRE` en el primer intento; al superar el límite, 429.
- **Consecuencias:** los tests de integración levantan también un contenedor Redis. La degradación con Redis caído se trata en la Fase 4. Decidido con el usuario.

## ADR-008: Mapeo DTO manual con records

- **Fecha:** 2026-11-05
- **Contexto:** estaba pendiente elegir entre MapStruct y mapeo manual.
- **Decisión:** mapeo manual con métodos estáticos `from(...)` en los records de respuesta, como ya se hizo en la Fase 1.
- **Consecuencias:** sin procesador de anotaciones adicional; el mapeo es explícito y lo cubren los tests de controlador.

## ADR-009: Cancelación de órdenes y reembolsos

- **Fecha:** 2026-11-06
- **Contexto:** la especificación permite cancelar una orden `PENDING_PAYMENT` y reembolsar una orden `PAID` si el evento no empezó, pero no define un estado `CANCELLED` para pagos ni indica si un reembolso debe devolver stock.
- **Decisión:** al cancelar una orden pendiente se libera la reserva activa y su stock; el pago permanece `PENDING` porque no existe un estado de cancelación y la orden final impide que un webhook posterior la confirme. Al reembolsar una orden pagada antes del evento, el pago pasa a `REFUNDED` y sus tickets a `CANCELLED`; la reserva queda `CONFIRMED` como historial y no se devuelve stock automáticamente, ya que no es un requisito explícito.
- **Consecuencias:** las transiciones usan únicamente los estados definidos. Una devolución de stock posterior a reembolsos requeriría una regla de negocio y un ADR nuevos.

## ADR-010: UPDATE atómico condicional para el inventario

- **Fecha:** 2026-11-06 · **Fase:** 3
- **Decisión:** descontar unidades con un único `UPDATE ... WHERE available_quantity >= :quantity`; la base de datos serializa la escritura y las filas afectadas indican éxito. Se prefiere a mantener bloqueos pesimistas explícitos durante la lógica de servicio y a gestionar conflictos/reintentos optimistas en un contador de alta contención.
- **Documento:** [ADR-010](adr/ADR-010-atomic-inventory-update.md).

## ADR-011: Idempotencia de órdenes y webhooks

- **Fecha:** 2026-11-06 · **Fase:** 3
- **Decisión:** clave de orden única por usuario y reserva; el mismo par clave/reserva reproduce la orden existente y reutilizar la clave con otra reserva es conflicto. Webhooks verifican HMAC y aplican transiciones idempotentes condicionadas por estado y referencia del proveedor.
- **Límite:** aún no hay inbox ni ID de evento del proveedor persistido; el ADR documenta este límite y la evolución requerida para un gateway real.
- **Documento:** [ADR-011](adr/ADR-011-order-and-webhook-idempotency.md).

## ADR-012: Expiración con `FOR UPDATE SKIP LOCKED`

- **Fecha:** 2026-11-06 · **Fase:** 3
- **Decisión:** workers de expiración reclaman lotes de filas vencidas sin esperar por filas bloqueadas por otros workers; la transición condicional y la liberación de inventario se ejecutan en la misma transacción.
- **Documento:** [ADR-012](adr/ADR-012-reservation-expiration-skip-locked.md).

## ADR-013: Caché Redis de metadatos del catálogo

- **Fecha:** 2026-11-07 · **Fase:** 4
- **Contexto:** las consultas del catálogo repiten lecturas de metadatos relativamente estables, pero la disponibilidad cambia con cada reserva y liberación de inventario. Cachear la respuesta completa podría exponer stock obsoleto.
- **Alternativas:** cachear la respuesta completa; cachear entidades JPA; cachear solo metadatos serializables y consultar inventario en cada petición.
- **Decisión:** aplicar cache-aside sobre records DTO que contienen solo metadatos del tipo de entrada. `availableQuantity` se obtiene siempre desde PostgreSQL mediante una proyección liviana. TTL configurable (`TICKET_TYPES_CACHE_TTL`, por defecto 5 minutos). Las escrituras del catálogo invalidan las entradas y el `RedisCacheManager` es transaccional para diferir la invalidación hasta el commit. Los fallos Redis de tipo `DataAccessException` se registran y se omiten, permitiendo fallback a PostgreSQL.
- **Consecuencias:** el catálogo reduce lecturas de metadatos sin cachear inventario crítico. La invalidación actual es global para el cache del catálogo porque el servicio de escritura de actualización recibe el ID del tipo y no el ID del evento; se acepta el alcance amplio por simplicidad y corrección. Se requiere validar en CI la serialización, expiración, invalidación y degradación ante indisponibilidad de Redis.

## ADR-014: Resiliencia ante indisponibilidad de Redis y degradación de autenticación

- **Fecha:** 2026-11-10 · **Fase:** 4
- **Decisión:** degradación diferenciada por riesgo: caché de catálogo con fallback a PostgreSQL y TTL ante invalidación fallida; rate limiting de login fail-open; almacén de refresh tokens fail-closed con HTTP 503; validación local de access tokens sin Redis.
- **Límites:** los fallos de invalidación pueden dejar metadatos obsoletos hasta el TTL; durante una caída Redis se omite temporalmente el rate limiting de login, mientras que las operaciones que requieren estado de refresh token no se aceptan.
- **Evidencia:** run CI #107 en `24d6a514f1f8a4d467b1c457d12a478e24f75c36`: 313 tests unitarios y 57 de integración, sin fallos ni errores. Latencias observadas durante Redis outage: ~1.014 ms para catálogo y ~1.023 ms para GET público de evento.
- **Documento:** [ADR-014](adr/ADR-014-redis-resilience-and-auth-degradation.md).

## Pendientes de decidir

- **Región de AWS:** se elegirá en la etapa 0 de la pista AWS según las regiones disponibles en la cuenta. No se creará ningún recurso sin confirmación explícita.

