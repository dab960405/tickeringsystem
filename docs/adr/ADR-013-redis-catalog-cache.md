# ADR-013: Caché Redis de metadatos del catálogo

- **Fecha:** 2026-11-07
- **Estado:** Aceptado
- **Fase:** 4

## Contexto

Las consultas del catálogo repiten lecturas de metadatos relativamente estables, pero la disponibilidad cambia con cada reserva y liberación de inventario. Cachear la respuesta completa podría exponer stock obsoleto. Redis ya existe como dependencia de la API y como contenedor de integración.

## Alternativas

1. Cachear la respuesta completa del catálogo, incluyendo disponibilidad.
2. Cachear entidades JPA.
3. Cachear únicamente metadatos serializables y consultar la disponibilidad en cada petición.

## Decisión

- Aplicar cache-aside sobre records DTO que contienen únicamente metadatos del tipo de entrada.
- Obtener `availableQuantity` siempre desde PostgreSQL mediante una proyección liviana.
- Configurar TTL mediante `TICKET_TYPES_CACHE_TTL`, con valor predeterminado de cinco minutos.
- Invalidar el catálogo tras las escrituras; el `RedisCacheManager` transaccional difiere la invalidación hasta el commit.
- Ante errores Redis de acceso a datos (`DataAccessException`), registrar la incidencia y continuar sin caché para permitir el fallback a PostgreSQL.

## Consecuencias

- Se reducen lecturas de metadatos sin cachear inventario crítico.
- La invalidación actual es global para el cache del catálogo porque la operación de actualización recibe el ID del tipo y no el ID del evento. Se acepta el alcance amplio por simplicidad y corrección.
- La degradación no debe ocultar errores de serialización ni otros errores de programación; solo se omiten fallos de acceso a datos.
- La serialización, expiración, invalidación y degradación deben quedar cubiertas por pruebas de integración antes de cerrar la Fase 4.
