# Registro de decisiones (ADR)

Formato: contexto, alternativas, decisión y consecuencias. Las decisiones se numeran y no se borran ni reescriben; si una decisión cambia, se añade otra que la sustituya. Los ADR individuales nuevos están en [`docs/adr/`](adr/).

---

## ADR-001: Spring Boot 3.5.x en lugar de Spring Boot 4

## ADR-002: Versiones de herramientas fijadas en la Fase 0

## ADR-003: Paquete base y nombres de módulos

## ADR-004: Umbral de cobertura configurado desde el inicio

## ADR-005: Workflow de CI adelantado y rama `dev`

## ADR-006: JWT con `spring-boot-starter-oauth2-resource-server` y HS256

## ADR-007: Redis desde la Fase 2 para refresh tokens y rate limiting de login

## ADR-008: Mapeo DTO manual con records

## ADR-009: Cancelación de órdenes y reembolsos

## ADR-010: UPDATE atómico condicional para el inventario

## ADR-011: Idempotencia de órdenes y webhooks

## ADR-012: Expiración con `FOR UPDATE SKIP LOCKED`

## Pendientes de decidir

- **Región de AWS:** se elegirá en la etapa 0 de la pista AWS según las regiones disponibles en la cuenta. No se creará ningún recurso sin confirmación explícita.
