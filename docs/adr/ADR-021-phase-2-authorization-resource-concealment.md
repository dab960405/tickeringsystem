# ADR-021: Ocultación de recursos ajenos en autorización de Fase 2

- **Fecha:** 2026-11-13
- **Estado:** Aceptado
- **Fase:** 2

## Contexto

La API permite que ORGANIZER gestione eventos y tipos de entrada propios, mientras ADMIN puede gestionarlos globalmente. Las consultas y operaciones de órdenes y reservas están acotadas al usuario autenticado. La política anterior diferenciaba entre un recurso inexistente (404) y un recurso existente propiedad de otro organizador (403), lo que podía revelar la existencia de borradores y recursos privados.

La especificación de seguridad exige comprobar propiedad y evitar filtrar recursos que el usuario no está autorizado a consultar. El sujeto propietario debe derivarse del JWT validado, no de un identificador aportado por el cliente.

## Alternativas consideradas

1. **403 para todo recurso existente ajeno:** comunica explícitamente que el recurso existe, aunque el usuario no pueda gestionarlo.
2. **404 para recurso ajeno e inexistente:** oculta la existencia del recurso y mantiene 403 para usuarios autenticados que carecen del rol funcional requerido.
3. **Autorización únicamente en controladores:** centraliza menos lógica, pero deja expuestas llamadas internas al servicio y facilita omisiones en nuevos puntos de entrada.

## Decisión

Se adopta la ocultación mediante HTTP 404 para recursos privados existentes que pertenecen a otro usuario/organizador y para recursos inexistentes dentro de las mismas operaciones protegidas.

- ORGANIZER solo puede gestionar eventos propios y tipos de entrada asociados a sus eventos.
- ADMIN conserva acceso global a operaciones de gestión de eventos y tipos de entrada.
- Un ORGANIZER que intenta gestionar un evento ajeno recibe el mismo 404 que para un ID inexistente.
- Un usuario autenticado con rol no permitido continúa recibiendo 403 por la regla de rol. La ocultación de propiedad no sustituye el control de roles.
- Las órdenes y reservas continúan consultándose mediante identificadores compuestos por ID de recurso y userId derivado del JWT; un recurso ajeno/inexistente responde 404.
- La autorización de propiedad permanece en la capa de servicio mediante PreAuthorize y servicios de autorización reutilizables.

## Consecuencias

- La API no revela si un borrador o recurso privado existe cuando el solicitante no es su propietario.
- Los clientes no pueden distinguir entre ID inexistente y recurso ajeno en las operaciones de propiedad.
- Se conserva 403 para denegaciones por rol, evitando confundir el control de roles con el control de propiedad.
- Las pruebas deben comprobar igualdad de estado HTTP entre recurso ajeno e inexistente y verificar que el propietario legítimo conserva acceso.

## Evidencia y límites

- EventAuthorizationService aplica la decisión para eventos; TicketTypeAuthorizationService delega la comprobación de propiedad del evento asociado.
- OrderService consulta por findByIdAndUserId; ReservationService consulta por ID y userId.
- AuthenticationFlowIT incluye un escenario con dos organizadores que obtienen access tokens reales, uno crea el borrador y el otro intenta modificarlo. El escenario compara la respuesta para recurso ajeno e inexistente y comprueba que el propietario legítimo puede actualizar.
- La matriz completa de rutas y roles está en authorization-matrix.md.
- La prueba de integración y las pruebas de regresión deben quedar verdes en CI del HEAD final. No se considera que la Fase 2 esté cerrada hasta verificar esa evidencia.
