# Progreso del proyecto

Última actualización: 2026-11-05 · Rama de trabajo: `dev`

## Fase actual

**Fase 0 (esqueleto): código completo, falta confirmar un criterio.**

| Criterio de aceptación (Fase 0) | Estado |
| --- | --- |
| `./mvnw verify` pasa | Hecho: verificado localmente con Temurin 21.0.13 (7 tests, 0 fallos) |
| `docker compose --profile infra up` levanta Postgres, Redis, Kafka y Mongo sanos | **Sin verificar**: el entorno de desarrollo no tiene Docker. Lo debe comprobar el usuario en local |

Siguiente fase: **Fase 1 (dominio base)**, pendiente de empezar.

## Completado

- Maven multi-módulo (`common-events`, `ticketing-api`, `notification-service`) con POM padre, Maven Wrapper 3.9.16, enforcer (Java 21), JaCoCo (umbral 70 % en `service`/`domain`) y Spotless.
- `docker-compose.yml` con perfil `infra` (Postgres 17, Redis 7, Kafka 4 en modo KRaft, Mongo 7), con healthchecks, red y volúmenes con nombre.
- `.env.example` documentado, README inicial y ADR-001 a ADR-005.
- Revisión de versiones: todas las fijadas existen en Maven Central (Boot 3.5.16, Testcontainers 1.21.4, springdoc 2.8.17, JaCoCo 0.8.16, Spotless 3.11.0, google-java-format 2.0.0, enforcer 3.7.0, Maven 3.9.16).
- `.gitignore`: eliminadas las entradas ajenas (`node_modules/`, `.next/`).
- CI: `.github/workflows/ci.yml` diseñado (ver ADR-005). **Pendiente de que el usuario lo suba**: la integración de v0 no tiene permiso `workflows` en GitHub y no puede subir archivos de `.github/workflows/`.

## Próximos 3 pasos

1. Fase 1: añadir dependencias (JPA, Flyway, PostgreSQL y Testcontainers) y las migraciones `V1__create_users.sql` … `V8__create_outbox_events.sql`, junto con la clase base de tests con Testcontainers.
2. Fase 1: entidades y repositorios de `venue`, `event` y `tickettype`, con `@DataJpaTest` sobre Postgres real.
3. Fase 1: CRUD REST (DTOs como records, validación, `PageResponse<T>`), `@RestControllerAdvice` con `ProblemDetail` y Swagger.

## Decisiones pendientes del usuario

- **Región de AWS**: se decide en la etapa 0 de la pista AWS. No se creará ningún recurso sin tu confirmación.
- **Librería JWT** (Fase 2): `jjwt` u `oauth2-resource-server`. Lo decidirá la IA con un ADR siguiendo la opción más simple, salvo que prefieras otra.
- **Mapeo de DTOs** (Fase 1): por defecto, mapeo manual con records, que es lo más simple. Quedará registrado en un ADR.

## Comandos de verificación

```bash
./mvnw clean verify                      # build completo con tests, cobertura y formato
docker compose --profile infra up -d     # dependencias locales
docker compose ps                        # todo debe estar "healthy"
```

## Problemas conocidos

- El entorno de la IA no tiene Docker, así que los tests de Testcontainers (desde la Fase 1) y Compose solo se verifican en GitHub Actions o en tu máquina.
- El job `docker-build` de CI llega en la Fase 6 (todavía no hay Dockerfiles).
