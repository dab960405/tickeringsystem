# Registro de decisiones (ADR)

Formato breve: contexto, decisión y consecuencias. Las decisiones se numeran y nunca se borran; si cambian, se añade una nueva que reemplaza a la anterior.

---

## ADR-001: Spring Boot 3.5.x en lugar de Spring Boot 4

- **Fecha:** 2026-11-05
- **Contexto:** a la fecha existe Spring Boot 4.1.x estable. La especificación indica usar 3.5.x por defecto salvo que todas las dependencias soporten Boot 4.
- **Decisión:** usar Spring Boot **3.5.16** (última estable de la rama 3.x) con Java 21.
- **Consecuencias:** máxima compatibilidad con springdoc 2.8.x, Testcontainers 1.21.x y la documentación disponible. Migrar a Boot 4 queda como mejora futura documentada.

## ADR-002: Versiones de herramientas fijadas en la Fase 0

- **Fecha:** 2026-11-05
- **Contexto:** la especificación exige verificar la última versión estable y fijarla explícitamente (nunca `latest`).
- **Decisión:** versiones fijadas en el POM padre y en `docker-compose.yml`:

| Herramienta | Versión | Nota |
| --- | --- | --- |
| Maven (wrapper) | 3.9.16 | Maven Wrapper 3.3.4, tipo `only-script` |
| Spring Boot | 3.5.16 | ver ADR-001 |
| Testcontainers BOM | 1.21.4 | 2.0.x cambia nombres de artefactos; 1.21.x es la línea soportada por Boot 3.5 |
| springdoc-openapi | 2.8.17 | 3.x es para Spring Boot 4 |
| JaCoCo | 0.8.16 | |
| Spotless + google-java-format | 3.11.0 + 2.0.0 | Spotless 2.46 no es compatible con google-java-format 2.x |
| maven-enforcer-plugin | 3.7.0 | Exige Java 21 y Maven >= 3.9 |
| PostgreSQL | `postgres:17.11-alpine` | |
| Redis | `redis:7.4.12-alpine` | La especificación pide Redis 7 |
| Kafka | `apache/kafka:4.4.0` | Modo KRaft; los clientes 3.9 de Boot 3.5 son compatibles con brokers 4.x |
| MongoDB | `mongo:7.0.45` | La especificación pide MongoDB 7 |

- **Consecuencias:** Dependabot (Fase 7) propondrá las actualizaciones posteriores.

## ADR-003: Paquete base y nombres de módulos

- **Fecha:** 2026-11-05
- **Decisión:** `com.portfolio.ticketing` (API), `com.portfolio.notification` (notificaciones) y `com.portfolio.events` (contratos compartidos). El envoltorio de eventos `EventEnvelope<T>` vive en `common-events` para no duplicar el contrato.
- **Consecuencias:** los dos servicios dependen de `common-events`, pero no entre sí.

## ADR-004: Umbral de cobertura configurado desde el inicio

- **Fecha:** 2026-11-05
- **Contexto:** el umbral del 70 % aplica a los paquetes `service` y `domain`.
- **Decisión:** la regla de JaCoCo se configura ya en la Fase 0 por paquete (`*.service`, `*.domain`) sobre los datos combinados de tests unitarios e integración. Mientras no existan esos paquetes la regla no tiene clases que evaluar y pasa.
- **Consecuencias:** desde la Fase 1 cualquier código de negocio sin tests rompe el build.

## Pendientes de decidir

- **Región de AWS:** se elegirá en la etapa 0 de la pista AWS según las regiones disponibles en la cuenta.
- **Librería JWT:** `jjwt` frente a `oauth2-resource-server` (Fase 2).
- **Mapeo DTO:** MapStruct frente a mapeo manual con records (Fase 1).
