# ADR-014: Resiliencia ante indisponibilidad de Redis y degradación de autenticación

- **Fecha:** 2026-11-10
- **Estado:** Aceptado
- **Fase:** 4

## Contexto

Redis participa en tres responsabilidades con perfiles de riesgo diferentes:

1. Caché de metadatos del catálogo de tipos de entrada.
2. Rate limiting de intentos de inicio de sesión.
3. Persistencia y revocación de refresh tokens.

Una caída de Redis no debe convertir una optimización de lectura en una caída de la API ni permitir que se acepten sesiones cuya revocación no puede comprobarse. La validación de access tokens debe seguir siendo independiente de Redis.

En runtime, `application.yml` configura `spring.data.redis.timeout=500ms` como timeout de comando. El contexto aislado de `RedisOutageIT` configura además `spring.data.redis.connect-timeout=500ms`. Las pruebas de integración de indisponibilidad usan un contenedor Redis dedicado, separado del contenedor compartido por el resto de las pruebas, y pausan el contenedor para simular la caída.

## Alternativas consideradas

1. **Fail-closed para todas las operaciones Redis:** mantener la consistencia estricta, a costa de hacer indisponible el catálogo y el login ante una caída de Redis.
2. **Fail-open para todas las operaciones Redis:** mantener disponibilidad, pero permitir operaciones de autenticación sin poder verificar o persistir el estado de sesión.
3. **Degradación diferenciada según el riesgo:** permitir fallback a PostgreSQL para lecturas cacheadas y omitir temporalmente el rate limiting, pero rechazar operaciones que requieren persistencia o revocación de refresh tokens.

## Decisión

Se adopta la degradación diferenciada:

### Caché de catálogo: fail-open con fallback a PostgreSQL

- Redis almacena exclusivamente metadatos serializables del catálogo mediante cache-aside; la disponibilidad de inventario se consulta siempre en PostgreSQL.
- Los fallos Redis de acceso a datos y serialización en las operaciones de caché se registran y se omiten mediante `CacheErrorHandler`; la lectura continúa contra PostgreSQL.
- Las invalidaciones fallidas no se reintentan. Una entrada previamente cacheada puede permanecer obsoleta hasta que expire su TTL.
- El TTL de runtime se configura con `TICKET_TYPES_CACHE_TTL` y tiene un valor predeterminado de cinco minutos. La anotación de clase `@SpringBootTest(properties = "ticketing.cache.ticket-types.ttl=20s")` de `RedisOutageIT` aplica el TTL de 20 segundos a **todos los tests de esa clase**, no únicamente al test de invalidación fallida; es una configuración aislada de pruebas.

La prueba `should_serveStaleCatalogUntilTtlAfterFailedInvalidation` verifica que, si Redis está pausado durante una actualización, la API actualiza PostgreSQL y responde correctamente, pero la entrada cacheada anterior sigue visible hasta que vence el TTL. Después de la expiración, la lectura devuelve el valor actualizado.

### Rate limiting de login: fail-open

- Si Redis falla durante el chequeo del rate limiter, la excepción `DataAccessException` se registra y se permite continuar la solicitud.
- Esta decisión prioriza la disponibilidad del flujo de autenticación frente a la indisponibilidad temporal del componente que aplica el límite.
- Fail-open del rate limiter **no** implica fail-open de la sesión: si un login válido necesita guardar un refresh token y Redis no está disponible, la emisión de tokens falla de forma cerrada (HTTP 503), porque no puede persistirse el estado de sesión.

### Refresh tokens: fail-closed, HTTP 503

- Guardar, comprobar o revocar un refresh token requiere Redis.
- Los errores de acceso a Redis se convierten en `TokenStoreUnavailableException`, que la API traduce a HTTP 503.
- Refresh y logout no se consideran exitosos cuando no es posible consultar o modificar el estado de revocación.
- Un login con credenciales válidas también puede responder 503 si no logra persistir el refresh token. Un login con credenciales inválidas conserva su respuesta de autenticación fallida.

La prueba `should_failClosedForAuthenticationSessionsDuringRedisOutage` cubre el comportamiento de refresh, login y logout durante la caída, además de la recuperación posterior.

### Access tokens: validación local, sin Redis

- Los access tokens JWT se validan mediante Spring Security Resource Server, verificando firma, expiración y tipo de token localmente con la configuración criptográfica de la API.
- La comprobación de un access token no consulta el almacén Redis de refresh tokens.
- La prueba `should_acceptValidAccessTokenDuringRedisOutage` registra e inicia sesión antes de pausar Redis y verifica que una petición autenticada con el access token sigue respondiendo correctamente durante la caída.

### Timeouts y latencia observada

- La API configura un timeout de comando Redis de 500 ms. En el contexto de `RedisOutageIT`, el timeout de conexión también se fija en 500 ms; `should_configureShortCommandTimeoutOnLettuceClient` verifica el timeout de comando efectivo de Lettuce.
- En la ejecución de pruebas de indisponibilidad se observaron aproximadamente **1.014 ms para el fallback del catálogo** y **1.023 ms para el GET público del evento**.
- En ambos flujos, la petición espera aproximadamente **500 ms al intentar leer desde Redis** y otros **500 ms al intentar escribir/actualizar la caché** antes de completar el fallback. La suma observada es aproximadamente 1.014 ms y 1.023 ms, respectivamente; la diferencia restante corresponde a procesamiento y overhead de la petición.
- Estas latencias reflejan el coste de esperar las operaciones Redis que fallan durante la caída; el fallback mantiene la respuesta funcional, pero no elimina la latencia de espera.
- Un HTTP 503 causado por timeout no demuestra que Redis no haya aplicado el comando: si el comando alcanzó Redis antes de perderse la respuesta, podría completarse después de que Redis se reanude.

### Fuente de verdad transaccional

- PostgreSQL continúa siendo la fuente de verdad para inventario, reservas y órdenes.
- El precio de cada línea de orden se conserva como snapshot en `order_items`; la caché Redis no determina el precio ni la disponibilidad de una compra.

## Evidencia de CI

El run **#107**, asociado al commit de código `24d6a514f1f8a4d467b1c457d12a478e24f75c36`, terminó correctamente. Sus XML reportan 313 tests unitarios y 57 tests de integración, sin fallos, errores ni omisiones.

En `RedisOutageIT` se ejecutaron 7 tests, todos correctos. Entre ellos se verifican el timeout real configurado en Lettuce, el uso de Redis dedicado, el fallback durante la caída, la expiración de una entrada obsoleta, el fail-closed del almacén de refresh tokens y la validación local de access tokens.

## Consecuencias y límites

- El catálogo conserva disponibilidad durante una caída de Redis, pero una actualización puede no reflejarse inmediatamente en una entrada cacheada: el límite de obsolescencia está determinado por el TTL restante.
- El timeout de comando de 500 ms limita la espera por operación Redis; el timeout de conexión de 500 ms se configura específicamente en el contexto de prueba de indisponibilidad. Una petición que realiza más de una operación cacheada puede acumular latencia.
- El rate limiting de login queda temporalmente desactivado cuando Redis no está disponible; esto aumenta la exposición a intentos de fuerza bruta durante la incidencia.
- La autenticación que depende del estado de refresh token queda indisponible con HTTP 503 mientras Redis no pueda responder. Los access tokens ya emitidos continúan siendo validables hasta su expiración.
- El fail-open del catálogo solo aplica a los fallos Redis contemplados por el manejador; errores ajenos a acceso/serialización no deben ocultarse.
- La recuperación de Redis no implica reconstruir o invalidar automáticamente toda entrada obsoleta; la expiración TTL permite recuperar el catálogo.

## Trabajo diferido — desviaciones aceptadas respecto a la especificación

Los tres puntos siguientes son **DESVIACIONES respecto a la especificación original**, en particular sus secciones 9, 10 y 11 y el alcance previsto para la Fase 4. Se aceptan explícitamente para esta fase; no se consideran requisitos implementados ni criterios completados. La decisión limita el alcance para mantener PostgreSQL como fuente de verdad transaccional y evitar introducir nuevas rutas de consistencia distribuida sin una política de invalidación, expiración y recuperación definida.

1. **DESVIACIÓN — Caché de `GET /events/{id}`:** la especificación contempla ampliar la caché a la consulta pública del evento. Se difiere porque esa respuesta combina datos del evento y del catálogo; antes de cachearla se necesita definir una estrategia de consistencia e invalidación que no exponga metadatos obsoletos. En esta fase se limita la caché a metadatos de tipos de entrada.
2. **DESVIACIÓN — Claves Redis `reservation:{id}`:** la especificación contempla almacenar información de reservas en Redis. Se difiere para no duplicar en Redis el estado transaccional que ya administra PostgreSQL ni crear una segunda fuente de verdad para el ciclo de vida de una reserva. Las reservas continúan persistidas y gobernadas por PostgreSQL.
3. **DESVIACIÓN — Rate limiting de reservas y órdenes:** la especificación prevé extender la limitación de solicitudes más allá del login. Se difiere porque esta fase implementa únicamente el rate limiting de login; las políticas para reservas y órdenes requieren definir límites, sujetos de aplicación y respuestas específicas por operación.

