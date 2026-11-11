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

La configuración de la API usa timeouts de comando y conexión de Redis de 500 ms. Las pruebas de integración de indisponibilidad usan un contenedor Redis dedicado, separado del contenedor compartido por el resto de las pruebas, y pausan el contenedor para simular la caída.

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
- El TTL de producción se configura con `TICKET_TYPES_CACHE_TTL` y tiene un valor predeterminado de cinco minutos. El test de invalidación fallida usa un TTL aislado de 20 segundos para verificar la expiración.

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

- Los timeouts de comando y conexión configurados para Redis son de 500 ms.
- En la ejecución de pruebas de indisponibilidad se observaron aproximadamente 1.014 ms para el fallback del catálogo y 1.023 ms para el GET público del evento.
- Estas latencias reflejan el coste de esperar los timeouts de las operaciones de caché que fallan durante la caída; el fallback mantiene la respuesta funcional, pero no elimina la latencia de espera.

### Fuente de verdad transaccional

- PostgreSQL continúa siendo la fuente de verdad para inventario, reservas y órdenes.
- El precio de cada línea de orden se conserva como snapshot en `order_items`; la caché Redis no determina el precio ni la disponibilidad de una compra.

## Evidencia de CI

El run **#107**, asociado al commit de código `24d6a514f1f8a4d467b1c457d12a478e24f75c36`, terminó correctamente. Sus XML reportan 313 tests unitarios y 57 tests de integración, sin fallos, errores ni omisiones.

En `RedisOutageIT` se ejecutaron 7 tests, todos correctos. Entre ellos se verifican el timeout real configurado en Lettuce, el uso de Redis dedicado, el fallback durante la caída, la expiración de una entrada obsoleta, el fail-closed del almacén de refresh tokens y la validación local de access tokens.

## Consecuencias y límites

- El catálogo conserva disponibilidad durante una caída de Redis, pero una actualización puede no reflejarse inmediatamente en una entrada cacheada: el límite de obsolescencia está determinado por el TTL restante.
- Los timeouts de 500 ms limitan la espera por operación Redis, pero una petición que realiza más de una operación cacheada puede acumular latencia.
- El rate limiting de login queda temporalmente desactivado cuando Redis no está disponible; esto aumenta la exposición a intentos de fuerza bruta durante la incidencia.
- La autenticación que depende del estado de refresh token queda indisponible con HTTP 503 mientras Redis no pueda responder. Los access tokens ya emitidos continúan siendo validables hasta su expiración.
- El fail-open del catálogo solo aplica a los fallos Redis contemplados por el manejador; errores ajenos a acceso/serialización no deben ocultarse.
- La recuperación de Redis no implica reconstruir o invalidar automáticamente toda entrada obsoleta; la expiración TTL permite recuperar el catálogo.

## Trabajo diferido

1. **Caché de `GET /events/{id}`:** no se incorpora porque la respuesta pública contiene información relacionada con el catálogo y su disponibilidad; se evita ampliar el conjunto de datos cacheados antes de definir una estrategia de consistencia e invalidación específica.
2. **Claves Redis `reservation:{id}`:** no se incorporan. Las reservas y su estado transaccional permanecen en PostgreSQL; Redis no debe convertirse en una segunda fuente de verdad para el ciclo de vida de una reserva.
3. **Rate limiting de reservas y órdenes:** permanece fuera del alcance de esta fase. El rate limiting implementado se limita al login; ampliar políticas requiere definir límites, sujetos y respuestas por operación.

