# Progreso del proyecto

Última actualización: 2026-11-05 · Rama de trabajo: `dev`

## Fase actual

**Fase 1 (dominio base): COMPLETA** (CI en verde en `944b375`). Siguiente: **Fase 2 (seguridad)**, pendiente de empezar.

Fase 0: código completo y `./mvnw verify` en verde (local y CI). Queda **sin verificar** por la IA `docker compose --profile infra up` (el entorno no tiene Docker): lo debe comprobar el usuario en local.

| Criterio de aceptación (Fase 1) | Estado |
| --- | --- |
| Migraciones Flyway (V1–V10) | Hecho, validadas en CI con Postgres real (`FlywayMigrationIT`) |
| Entidades y repositorios de `venue`, `event`, `tickettype` | Hecho (con tests unitarios y `@DataJpaTest` *IT) |
| Seed de datos solo para perfil `dev` (`db/seed`) | Hecho: `R__seed_dev_data.sql` (migración repetible e idempotente) cargada solo con `application-dev.yml`; los IT usan el perfil `test`; `DevSeedMigrationIT` lo valida en un esquema aislado |
| CRUD REST de venues (`/api/v1/venues`) con DTOs records, validación y `PageResponse<T>` | Hecho (`@WebMvcTest` + tests unitarios del servicio; roles en Fase 2) |
| CRUD REST de eventos y tipos de entrada | Hecho: `POST/PUT /events`, `publish`, `cancel`, `POST /events/{id}/ticket-types`, `PUT /ticket-types/{id}` con `@WebMvcTest`. El organizador llega provisionalmente en la cabecera `X-User-Id` hasta la Fase 2 |
| Manejo global de errores (`ProblemDetail`) | Hecho (`GlobalExceptionHandler`, mapeo 404/409/422/500 y `errors` por campo) |
| Swagger muestra todos los endpoints | Hecho: todas las rutas de la Fase 1 con resumen y respuestas; `OpenApiDocsIT` lo comprueba en `/v3/api-docs`. `bearerAuth` llega en la Fase 2 |
| Tests unitarios y de repositorio verdes | Hecho: verdes en CI (`944b375`, unitarios + `*IT` con Testcontainers) |

## Última implementación publicada

- `944b375` test: verify OpenAPI documents every phase 1 endpoint

Commits de la sesión de retoma (rama `dev`): `2e3b6ac`, `dbea9a6`, `f526101`, `739e828`, `944b375`.

## Próximos 3 pasos

1. Fase 2: decidir librería JWT (propuesta: `oauth2-resource-server` nativo con HS256, sin dependencia extra) y añadir `spring-boot-starter-security`.
2. Fase 2: entidad `User`, registro (`POST /auth/register`) con BCrypt y política de contraseña, y sustituir `X-User-Id` por el principal autenticado.
3. Fase 2: login con access token (15 min) y refresh token (7 días), con matriz de tests por rol.

## Decisiones tomadas (pendientes de registrar como ADR con el usuario)

Elegidas por ser la opción más simple; se registrarán en `docs/decisions.md` cuando el usuario confirme la numeración (ver "Decisiones pendientes"):

- Mapeo DTO: manual con records (`XxxResponse.from(entity)`), sin MapStruct.
- `ResourceNotFoundException` → 404, `InsufficientStock`/`InvalidStateTransition`/`DuplicateResource` → 409, `BusinessRuleViolation` → 422; violaciones de integridad de BD → 409 sin detalles internos.
- Ciclo de estados del evento: `DRAFT → PUBLISHED | CANCELLED`, `PUBLISHED → CANCELLED | FINISHED`; editable solo en `DRAFT` o `PUBLISHED`.
- `events.organizer_id` se guarda como UUID simple (sin relación JPA a `User`) para no acoplar módulos.
- IDs UUID generados por Hibernate (`GenerationType.UUID`) en el `persist`.
- Columna `currency` (`CHAR(3)`, default `USD`) en todas las tablas con dinero: `ticket_types`, `orders`, `order_items`, `payments`.
- Un único `payment` por orden (`UNIQUE (order_id)`).
- Organizador provisional: cabecera `X-User-Id` en `POST /events` hasta la Fase 2 (lo más simple sin adelantar seguridad).
- Seed `dev` como migración repetible (`R__`) para no bloquear nuevas migraciones versionadas; contraseña pública de demo `Password123` para los tres usuarios.

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

## Tests ejecutados (sesión de retoma 2026-11-05)

- Local (Temurin 21.0.13, sin Docker): `./mvnw -B clean verify -DskipITs` → BUILD SUCCESS, 86 tests unitarios/slice en `ticketing-api` + 5 en `common-events` + 1 en `notification-service`, 0 fallos.
- Local `./mvnw -B clean verify` completo: los `*IT` fallan solo por falta de Docker (`Could not find a valid Docker environment`); no es un fallo de código.
- CI (GitHub Actions, con Docker): verde en `9686f24` y `f526101`. `944b375` (incluye `DevSeedMigrationIT` y `OpenApiDocsIT`). `dbea9a6` y `739e828` aparecen cancelados por `concurrency` (los incluyen commits posteriores en verde).

## Problemas conocidos

- El entorno de la IA no tiene Docker: Testcontainers y Compose solo se verifican en CI o en local.
- El job `docker-build` de CI llega en la Fase 6 (todavía no hay Dockerfiles).

## Cambios locales sin publicar

Ninguno.
