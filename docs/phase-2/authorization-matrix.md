# Matriz de autorización — Fase 2

**Estado:** vigente para el código de la rama dev revisado el 13-11-2026.  
**Fuente de verdad:** SecurityConfig, @PreAuthorize de los servicios y pruebas HTTP/IT asociadas.  
**Objetivo:** describir las reglas efectivas; no sustituye la ejecución de las pruebas.

## Convenciones

- **Público:** no requiere JWT en el filtro HTTP. Las validaciones funcionales adicionales siguen aplicando.
- **ADMIN, ORGANIZER, USER:** authorities Spring ROLE_ADMIN, ROLE_ORGANIZER y ROLE_USER.
- **Propietario:** el sub del JWT debe coincidir con el propietario persistido del recurso.
- **404 oculto:** un recurso ajeno y uno inexistente producen el mismo 404, para no revelar su existencia.
- **401:** no hay autenticación válida.
- **403:** hay autenticación válida, pero falta el rol requerido. Las reglas de propiedad con ocultación pueden traducir un recurso ajeno a 404.

## Rutas y permisos

| Método | Ruta | Acceso | Restricción adicional / resultado esperado |
| --- | --- | --- | --- |
| POST | /api/v1/auth/register | Público | Registro; el rol asignado por el servidor no lo elige el cliente |
| POST | /api/v1/auth/login | Público | Credenciales válidas; rate limit puede responder 429 |
| POST | /api/v1/auth/refresh | Público | Requiere refresh token activo; inválido/revocado 401; Redis no disponible 503 |
| POST | /api/v1/auth/logout | Público en filtro HTTP | Revoca el refresh token presentado; no requiere access JWT |
| POST | /api/v1/payments/webhook | Público en filtro HTTP | La firma HMAC se valida en la capa de pagos; firma inválida 401 |
| GET | /api/v1/events | Público | Solo catálogo publicado |
| GET | /api/v1/events/{id} | Público | Solo evento publicado; inexistente/no publicado 404 |
| POST | /api/v1/events | ORGANIZER, ADMIN | El servidor asigna el organizerId desde el JWT |
| PUT | /api/v1/events/{id} | ORGANIZER, ADMIN | ORGANIZER solo propietario; ajeno/inexistente 404. ADMIN puede gestionar cualquier evento existente |
| POST | /api/v1/events/{id}/publish | ORGANIZER, ADMIN | ORGANIZER solo propietario; ajeno/inexistente 404 |
| POST | /api/v1/events/{id}/cancel | ORGANIZER, ADMIN | ORGANIZER solo propietario; ajeno/inexistente 404 |
| POST | /api/v1/events/{eventId}/ticket-types | ORGANIZER, ADMIN | ORGANIZER debe ser propietario del evento; ajeno/inexistente 404 |
| PUT | /api/v1/ticket-types/{id} | ORGANIZER, ADMIN | ORGANIZER debe ser propietario del evento asociado; ajeno/inexistente 404 |
| GET | /api/v1/venues | Público | Lectura pública |
| GET | /api/v1/venues/{id} | Público | Lectura pública; inexistente 404 |
| POST | /api/v1/venues | ADMIN | USER/ORGANIZER 403 |
| PUT | /api/v1/venues/{id} | ADMIN | USER/ORGANIZER 403 |
| DELETE | /api/v1/venues/{id} | ADMIN | USER/ORGANIZER 403 |
| POST | /api/v1/reservations | USER | La reserva se crea para el sub del JWT |
| DELETE | /api/v1/reservations/{id} | USER | Solo reserva del sub; ajena/inexistente 404 |
| POST | /api/v1/orders | USER | Orden y reserva quedan ligadas al sub del JWT |
| GET | /api/v1/orders | USER | Lista exclusivamente las órdenes del sub |
| GET | /api/v1/orders/{id} | USER | Consulta por ID y propietario; ajena/inexistente 404 |
| POST | /api/v1/orders/{id}/cancel | USER | Cancelación se ejecuta con ID y propietario del JWT |
| GET | /api/v1/me/tickets | USER | Devuelve exclusivamente tickets del usuario autenticado |

## Rutas de infraestructura

| Ruta | Acceso | Nota |
| --- | --- | --- |
| /error | Público | Manejo de errores del framework |
| /swagger-ui.html, /swagger-ui/**, /v3/api-docs/** | Público | Documentación OpenAPI |
| /actuator/health | Público | Health check |
| /actuator/prometheus | Público | Métricas; restringir en infraestructura antes de exposición pública |

Las rutas no enumeradas quedan sujetas a .anyRequest().authenticated(); deben añadirse aquí al incorporarse endpoints nuevos.

## Evidencia existente y brechas de prueba

| Área | Evidencia identificada | Estado de evidencia |
| --- | --- | --- |
| Login, emisión, refresh y logout | AuthenticationFlowIT con tokens reales | Existe |
| Crear evento con token ORGANIZER real | AuthenticationFlowIT | Existe |
| Rol requerido para venues | VenueControllerTest | Pruebas HTTP con usuarios simulados |
| Rol requerido para eventos y tipos | EventControllerTest, TicketTypeControllerTest | Pruebas HTTP con usuarios simulados |
| USER requerido para órdenes/reservas/tickets | OrderControllerTest, ReservationControllerTest, TicketControllerTest | Pruebas HTTP con usuarios simulados |
| Aislamiento entre dos organizadores con JWT reales | Caso añadido a AuthenticationFlowIT | Pendiente de CI |
| Aislamiento entre dos usuarios reales para órdenes y reservas | Caso añadido a AuthenticationFlowIT: GET/cancelación de orden y cancelación de reserva de otro usuario | Pendiente de CI |
| Matriz exhaustiva método × rol × estado de recurso | No existe una suite única que cubra todas las celdas | Pendiente de completar según endpoints existentes |

## Criterio de aceptación para Fase 2

1. Cada ruta funcional queda clasificada por método y rol.
2. Las operaciones de propietario derivan la identidad del JWT, nunca de un ID de usuario suministrado por el cliente.
3. Los recursos privados ajenos no pueden consultarse ni modificarse; se aplica el 404 de ocultación documentado.
4. Las pruebas de integración usan dos identidades autenticadas reales para acreditar aislamiento.
5. ./mvnw -B clean verify termina correctamente en CI para el HEAD final de dev.
6. La evidencia de pruebas y cobertura se registra en docs/PROGRESS.md.

La matriz no declara la Fase 2 cerrada por sí sola. El cierre requiere verificar las pruebas y la evidencia de CI del commit final.
