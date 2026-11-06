# Progreso del proyecto

Última actualización: 2026-11-05 · Rama de trabajo: `dev`

## Fase actual

**Fase 2 (seguridad): implementación completa, pendiente de confirmar los tests de integración en CI.** No se inicia la Fase 3 hasta cerrar esta verificación.

La Fase 1 (dominio base) está completa y su CI quedó verde en `944b375`. La Fase 0 conserva la limitación de verificar `docker compose --profile infra up` en una máquina con Docker.

| Criterio de aceptación (Fase 2) | Estado |
| --- | --- |
| Registro con rol `USER`, email normalizado y BCrypt | Implementado (`POST /api/v1/auth/register`), con validación de contraseña y tests |
| Login con access token de 15 minutos y refresh token de 7 días | Implementado (`POST /api/v1/auth/login`) con JWT HS256 y claims `sub`, `role`, `iat`, `exp`, `type` |
| Refresh y logout revocable | Implementado (`POST /api/v1/auth/refresh`, `/logout`); Redis guarda solo SHA-256 del `jti` con TTL |
| Roles y propiedad de recursos | Implementado con reglas de rutas, `@PreAuthorize` y servicios de autorización para eventos y tipos de entrada |
| Rate limiting en login | Implementado con contador Redis de ventana fija y respuesta 429 |
| Matriz de tests por rol y recursos ajenos | Tests unitarios y `@WebMvcTest` con seguridad real añadidos; `AuthenticationFlowIT` cubre el flujo con PostgreSQL y Redis |
| Swagger con `bearerAuth` | Implementado en `OpenApiConfig` y operaciones protegidas; `OpenApiDocsIT` comprueba rutas y esquema |

## Última implementación publicada

- `4b94c7d` feat: enforce JWT roles and resource ownership

Commits publicados en esta sesión de retoma (rama `dev`):

1. `583e0be` feat: add user registration with BCrypt
2. `fc4c3d1` feat: add JWT authentication and Redis token lifecycle
3. `4b94c7d` feat: enforce JWT roles and resource ownership

## Próximos 3 pasos

1. Confirmar en GitHub Actions el commit `4b94c7d`, incluyendo `AuthenticationFlowIT` y `OpenApiDocsIT`; corregir cualquier fallo real que aparezca.
2. Cuando la integración de seguridad esté verde, cerrar la Fase 2 y registrar aquí el resultado de CI y de la matriz completa.
3. Iniciar la Fase 3 con reservas: entidad, repositorio y decremento atómico de stock, sin adelantar esa implementación antes de cerrar la Fase 2.

## Decisiones registradas

- ADR-006: `spring-boot-starter-oauth2-resource-server` con Nimbus y HS256.
- ADR-007: Redis desde la Fase 2 para refresh tokens y rate limiting de login.
- ADR-008: mapeo manual de DTOs con records.
- No se modificaron ADRs anteriores ni se creó ningún recurso de AWS.

## Decisiones pendientes del usuario

- **Región de AWS:** se elegirá en la etapa 0 de la pista AWS. No se creará ningún recurso sin confirmación explícita.

## Comandos de verificación

```bash
./mvnw clean verify                      # build completo (requiere Docker para los *IT)
./mvnw clean verify -DskipITs            # unitarios, slices, formato y cobertura sin Docker
docker compose --profile infra up -d     # dependencias locales
docker compose ps                        # todos deben aparecer healthy
```

## Tests ejecutados y resultados reales

- `./mvnw -B verify -DskipITs` con Temurin 21.0.13: **BUILD SUCCESS**; 123 tests de `ticketing-api`, 5 de `common-events` y 1 de `notification-service`, 0 fallos; JaCoCo y Spotless correctos.
- `./mvnw -B clean verify`: los 9 `*IT` no pudieron ejecutarse porque el entorno no tiene Docker (`Could not find a valid Docker environment`). No se desactivó ningún test para ocultar esta limitación.
- Consulta de GitHub Actions con `gh run list --branch dev`: HTTP 403 (`Resource not accessible by integration`). El estado de CI de los commits nuevos queda **sin confirmar**; no se afirma que esté verde.

## Problemas conocidos

- El entorno de la IA no tiene Docker: Testcontainers y Compose deben verificarse en GitHub Actions o en una máquina local con Docker.
- El job `docker-build` de CI llega en la Fase 6; todavía no hay Dockerfiles.
- La Fase 2 no se considera cerrada hasta que los tests de integración de seguridad pasen en CI o en local.

## Cambios locales sin publicar

La actualización de esta documentación se publicará como el siguiente commit `docs:` en `dev`.
