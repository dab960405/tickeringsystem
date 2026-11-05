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
| CRUD REST de venues, eventos y tipos de entrada (DTOs records, validación, `PageResponse<T>`) | Pendiente |
| Manejo global de errores (`ProblemDetail`) | Pendiente (jerarquía `BusinessException` ya creada) |
| Swagger muestra todos los endpoints | Pendiente |
| Tests unitarios y de repositorio verdes | Verde hasta ahora |

## Última implementación publicada

- `c187039` feat: add ticket type entity with optimistic locking and repository

Commits de esta sesión (rama `dev`): `b788dcf`, `9beb22b`, `9241fb5`, `be243a3`, `c187039`.

## Próximos 3 pasos

1. `@RestControllerAdvice` global con `ProblemDetail` (mapeo de `BusinessException` a 404/409/422 y errores de validación en `errors`).
2. `PageResponse<T>` + CRUD de venues (`/api/v1/venues`) con DTOs records, validación y `@WebMvcTest` (la seguridad por rol llega en la Fase 2).
3. CRUD de eventos y tipos de entrada, springdoc con `bearerAuth`, y seed `db/seed` para el perfil `dev`.

## Decisiones tomadas (pendientes de registrar como ADR con el usuario)

Elegidas por ser la opción más simple; se registrarán en `docs/decisions.md` cuando el usuario confirme la numeración (ver "Decisiones pendientes"):

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

- Local (Temurin 21.0.13, sin Docker): `./mvnw -B clean verify -DskipITs` → BUILD SUCCESS, 41 tests unitarios, 0 fallos.
- Los `*IT` (Testcontainers) no se pueden ejecutar en el entorno de la IA; se verifican en GitHub Actions (job "Build and test", consultado con `gh api .../check-runs`).
- CI en verde confirmado para `b788dcf`, `9beb22b`, `9241fb5`.

## Problemas conocidos

- El entorno de la IA no tiene Docker: Testcontainers y Compose solo se verifican en CI o en local.
- El job `docker-build` de CI llega en la Fase 6 (todavía no hay Dockerfiles).

## Cambios locales sin publicar

Ninguno.
