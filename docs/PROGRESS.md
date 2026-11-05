# Progreso del proyecto

Última actualización: 2026-11-05 · Rama de trabajo: `dev`

## Fase actual

**Fase 1 (dominio base): IN_PROGRESS.**

Fase 0: código completo y `./mvnw verify` en verde (local y CI). Queda **sin verificar** por la IA `docker compose --profile infra up` (el entorno no tiene Docker): lo debe comprobar el usuario en local.

| Criterio de aceptación (Fase 1) | Estado |
| --- | --- |
| Migraciones Flyway (V1–V10) | Hecho, validadas en CI con Postgres real (`FlywayMigrationIT`) |
| Entidades y repositorios de `venue`, `event`, `tickettype` | Hecho (con tests unitarios y `@DataJpaTest` *IT) |
| Seed de datos solo para perfil `dev` (`db/seed`) | Pendiente |
| CRUD REST de venues (`/api/v1/venues`) con DTOs records, validación y `PageResponse<T>` | Hecho (`@WebMvcTest` + tests unitarios del servicio; roles en Fase 2) |
| CRUD REST de eventos y tipos de entrada | Pendiente |
| Manejo global de errores (`ProblemDetail`) | Hecho (`GlobalExceptionHandler`, mapeo 404/409/422/500 y `errors` por campo) |
| Swagger muestra todos los endpoints | Parcial: springdoc 2.8.17 en `/swagger-ui.html`, venues documentados; falta `bearerAuth` (Fase 2) y el resto de rutas |
| Tests unitarios y de repositorio verdes | Verde hasta ahora |

## Última implementación publicada

- `dcb3bf5` feat: add venue CRUD API with pagination and OpenAPI docs

Commits de esta sesión (rama `dev`): `b788dcf`, `9beb22b`, `9241fb5`, `be243a3`, `c187039`, `53f7b18`, `ced25c9`, `dcb3bf5`.

## Próximos 3 pasos

1. CRUD de eventos: `GET /events` con filtros `q`, `category`, `city`, `from`, `to` (solo PUBLISHED), `GET /events/{id}` con tipos de entrada, `POST/PUT /events`, `publish` (requiere al menos un tipo de entrada) y `cancel`.
2. Tipos de entrada: `POST /events/{id}/ticket-types` y `PUT /ticket-types/{id}` (sin bajar el total por debajo de lo vendido o reservado; regla ya implementada en `TicketType.update`).
3. Seed `db/seed` para el perfil `dev` y un test que compruebe que Swagger (`/v3/api-docs`) lista todas las rutas; después, cierre de la Fase 1.

## Decisiones tomadas (pendientes de registrar como ADR con el usuario)

Elegidas por ser la opción más simple; se registrarán en `docs/decisions.md` cuando el usuario confirme la numeración (ver "Decisiones pendientes"):

- Mapeo DTO: manual con records (`XxxResponse.from(entity)`), sin MapStruct.
- `ResourceNotFoundException` → 404, `InsufficientStock`/`InvalidStateTransition`/`DuplicateResource` → 409, `BusinessRuleViolation` → 422; violaciones de integridad de BD → 409 sin detalles internos.
- Ciclo de estados del evento: `DRAFT → PUBLISHED | CANCELLED`, `PUBLISHED → CANCELLED | FINISHED`; editable solo en `DRAFT` o `PUBLISHED`.
- `events.organizer_id` se guarda como UUID simple (sin relación JPA a `User`) para no acoplar módulos.
- IDs UUID generados por Hibernate (`GenerationType.UUID`) en el `persist`.
- Columna `currency` (`CHAR(3)`, default `USD`) en todas las tablas con dinero: `ticket_types`, `orders`, `order_items`, `payments`.
- Un único `payment` por orden (`UNIQUE (order_id)`).

## Decisiones pendientes del usuario

- **ADR-006**: confirmar qué decisión ocupa ese número antes de añadir ADRs nuevos.
- **Región de AWS**: etapa 0 de la pista AWS. No se creará ningún recurso sin confirmación.
- **Librería JWT** (Fase 2): `jjwt` u `oauth2-resource-server`.
- **Mapeo de DTOs**: por defecto mapeo manual con records.

## Comandos de verificación

```bash
./mvnw clean verify                      # build completo (requiere Docker para los *IT)
./mvnw clean verify -DskipITs            # sin Docker: unitarios, formato y cobertura
docker compose --profile infra up -d     # dependencias locales
docker compose ps                        # todo debe estar "healthy"
```

## Tests ejecutados (sesión 2026-11-05)

- Local (Temurin 21.0.13, sin Docker): `./mvnw -B clean verify -DskipITs` → BUILD SUCCESS, 61 tests unitarios/slice, 0 fallos.
- Los `*IT` (Testcontainers) no se pueden ejecutar en el entorno de la IA; se verifican en GitHub Actions (job "Build and test", consultado con `gh api .../check-runs`).
- CI en verde confirmado para `b788dcf`, `9beb22b`, `9241fb5`, `be243a3`, `53f7b18`, `ced25c9` (`c187039` cancelado por `concurrency` al llegar `53f7b18`, que lo incluye).

## Problemas conocidos

- El entorno de la IA no tiene Docker: Testcontainers y Compose solo se verifican en CI o en local.
- El job `docker-build` de CI llega en la Fase 6 (todavía no hay Dockerfiles).

## Cambios locales sin publicar

Ninguno.
