# Progreso del proyecto

Última actualización: 2026-11-06 · Rama de trabajo: `dev`

## Fase actual

**Fase 2 (seguridad): completada y verificada en GitHub Actions.** El check `Build and test` terminó en `success` para `727330c`, incluyendo los tests de integración con Docker del runner.

La Fase 1 (dominio base) está completa y su CI quedó verde en `944b375`. La Fase 0 conserva la limitación de verificar `docker compose --profile infra up` en una máquina con Docker. La Fase 3 (reservas y órdenes) es la siguiente.

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

- `727330c` fix: map method security denials to 403 — cierre de seguridad con CI verde.
- `23cdd27` docs: record verified phase zero baseline — reconciliación y diagnóstico publicados en `dev`.

La rama local quedó reconciliada con `origin/dev`; no se sobrescribió trabajo remoto.

## Última verificación y publicación

- `./mvnw -B verify -DskipITs` sobre el código reconciliado: **BUILD SUCCESS**, 124 tests de `ticketing-api`, 5 de `common-events` y 1 de `notification-service` (130 totales), 0 fallos; JaCoCo y Spotless correctos.
- `./mvnw -B clean verify`: compilación y tests unitarios correctos; los 9 `*IT` fallaron al iniciar Testcontainers porque este entorno no tiene Docker. No se desactivó ningún test.
- GitHub Actions: `Build and test` **success** en `727330c` (Fase 2 completa). El estado del commit documental `23cdd27` queda sujeto a su propio check.
- Último push confirmado: `23cdd2721d9d036cee5cf54cfce167b77f2a88d0` en `origin/dev`.

## Próximos 3 pasos

1. Fase 3: modelar `OrderState` y cubrir todas las transiciones válidas e inválidas con tests parametrizados.
2. Fase 3: implementar reservas con decremento atómico condicional de `ticket_types`, TTL configurable y tests de concurrencia.
3. Fase 3: implementar órdenes idempotentes, webhook HMAC, generación de tickets y expiración con devolución de stock exactamente una vez.

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

- `./mvnw -B verify -DskipITs` con Temurin 21.0.13: **BUILD SUCCESS**; 124 tests de `ticketing-api`, 5 de `common-events` y 1 de `notification-service`, 0 fallos; JaCoCo y Spotless correctos.
- `./mvnw -B clean verify`: los 9 `*IT` no pudieron ejecutarse porque el entorno no tiene Docker (`Could not find a valid Docker environment`). No se desactivó ningún test para ocultar esta limitación.
- `gh api .../commits/727330c/check-runs`: `Build and test` completado con `success`; `gh run list` no está permitido por el token, pero el endpoint de check runs sí confirma el resultado.

## Problemas conocidos

- El entorno de la IA no tiene Docker: Testcontainers y Compose deben verificarse en GitHub Actions o en una máquina local con Docker.
- El entorno tampoco tenía Java al iniciar esta retoma; se descargó Temurin 21.0.13 fuera del repositorio para ejecutar la verificación. No se añadieron binarios ni secretos al proyecto.
- El job `docker-build` de CI llega en la Fase 6; todavía no hay Dockerfiles.
- La Fase 3 todavía no tiene implementación de reservas, órdenes, pagos ni expiración.

## Cambios locales sin publicar

Esta actualización documental se publicará como el siguiente commit `docs:` en `dev`.
