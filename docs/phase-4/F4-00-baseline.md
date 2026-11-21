# F4-00 — Informe de línea base

- **Fecha:** 2026-11-13 (UTC)
- **Repositorio:** `dab960405/tickeringsystem`
- **Rama autorizada:** `dev`
- **SHA inicial y HEAD remoto verificado:** `c6917e152c5440fbd346b1f21e1b98d065bca519`
- **Estado de trabajo inicial:** limpio, sin archivos modificados ni pendientes
- **Alcance auditado:** el mismo SHA `c6917e152c5440fbd346b1f21e1b98d065bca519`; el commit que publica este informe queda identificado por `git log -1 -- docs/phase-4/F4-00-baseline.md` y se relaciona en la auditoría final.

## 1. Objetivo

Establecer una línea base reproducible antes de modificar código, contrastar el estado real con el encargo F4-00–F4-07 y detectar brechas que impidan considerar cerrada la Fase 4.

## 2. Archivos y documentos revisados

- Especificación técnica completa suministrada con el encargo, incluidas las secciones 3, 6–12, 15, 22 y 24.
- Encargo integral F4-00–F4-07 y autorización de autonomía técnica/entrega continua.
- `README.md`, `docs/PROGRESS.md`, `docs/architecture.md`, `docs/decisions.md`, `docs/erd.md` y `docs/LOCAL_RUN.md`.
- ADR-009 en `docs/decisions.md` y ADR-010 a ADR-015 en `docs/adr/`.
- POM padre, POM de `ticketing-api`, Maven Wrapper y `.github/workflows/ci.yml`.
- Migraciones V1–V11, con revisión específica de reservas, órdenes, tickets y pagos.
- `ReservationService`, `ReservationExpirationService`, `ReservationRepository`, `TicketTypeInventoryService`, `TicketTypeRepository`, `OrderService`, `OrderPaymentService`, `OrderCancellationService`, `TicketService` y entidades asociadas.
- `AbstractIntegrationTest` y `ReservationConcurrencyIT`.
- Historial completo de `dev`: no contiene las cadenas `H-75` ni `ADR-016`; los diseños 03-E mencionados por el encargo no están versionados en este repositorio.

## 3. Criterios originales de Fase 4

La especificación original, sección 22, define para Fase 4: **Redis: caché con invalidación, TTL de reservas y rate limiting**. Su aceptación exige una prueba de degradación con Redis caído y verificación de aciertos de caché. Las secciones 9–11 precisan además:

1. Caché de `GET /events/{id}` y listados populares, TTL de 60 segundos e invalidación al editar/publicar/cancelar.
2. Claves `reservation:{id}` con TTL equivalente a la reserva.
3. Rate limiting en login y en creación de reservas/órdenes.
4. Redis nunca como fuente de verdad y funcionamiento degradado con PostgreSQL como autoridad.

El encargo actual amplía la evidencia necesaria con H-75–H-80: orden global de locks, unidades/generaciones/tickets, ciclos/workers/recuperación, recuperación de reservas y tres identidades de idempotencia. La Fase 5 sigue bloqueada.

## 4. Estado inicial de compilación, pruebas, cobertura y CI

### Verificación local

- Toolchain instalado fuera del repositorio: Temurin `21.0.13+9` LTS.
- Comando: `JAVA_HOME=/tmp/jdk21 PATH=/tmp/jdk21/bin:$PATH ./mvnw -B test`.
- Resultado: `BUILD SUCCESS`; 46 informes Surefire, **315 tests, 0 fallos, 0 errores, 0 omitidos**.
- Docker no está instalado en el entorno asistido; no se ejecutó localmente Failsafe/Testcontainers. Esta limitación no se sustituye por una afirmación de éxito.

### Verificación independiente en GitHub Actions

- Run: [43283365129](https://github.com/dab960405/tickeringsystem/actions/runs/43283365129).
- SHA: `c6917e152c5440fbd346b1f21e1b98d065bca519`.
- Job `Build and test`: `completed` / `success`; `./mvnw -B clean verify` correcto.
- Artefactos descargados y contados desde XML:
  - Surefire: **315 tests, 0 fallos, 0 errores, 0 omitidos**.
  - Failsafe: **61 tests, 0 fallos, 0 errores, 0 omitidos**.
  - Total: **376 tests**.
- JaCoCo de `ticketing-api`, calculado desde los nodos `package` del XML:

| Paquetes | Líneas | Cobertura de líneas | Ramas | Cobertura de ramas |
| --- | ---: | ---: | ---: | ---: |
| `service` | 501 / 522 | **95,98 %** | 126 / 151 | 83,44 % |
| `domain` | 291 / 296 | **98,31 %** | 84 / 101 | 83,17 % |

## 5. Hallazgos pendientes verificados

| Hallazgo | Estado real en el SHA inicial | Brecha |
| --- | --- | --- |
| H-75 | Existe `UPDATE` condicional y prueba de 100 compradores/10 unidades repetida 20 veces | La evidencia física inicial es válida para ese escenario, pero no cubre todos los flujos de escritura ni los nuevos protocolos |
| H-76 | `confirmForPayment`, `releaseForPayment` y expiración bloquean/consultan R antes de descontar/restaurar T | Implementa `R → T`, incompatible con el protocolo consolidado `T → R`; ADR-012 no define el orden global y ADR-016 no existe |
| H-77 | `OrderItem.quantity` y tickets relacionados solo con `order_item_id` | No hay unidades comerciales, generaciones, relación unidad-ticket ni unicidad por unidad/generación |
| H-78 | Solo existe un scheduler de expiración con `SKIP LOCKED` | No hay ciclos, participaciones, jobs, ejecuciones, disposiciones, leases con fencing ni proyecciones |
| H-79 | Un éxito tardío intenta recuperar stock; sin stock marca orden/pago `REFUNDED` | No distingue recepción, evidencia financiera y reembolso ejecutado; puede declarar reembolso sin confirmación del proveedor |
| H-80 | Webhook con `orderId`, `status` y `providerReference`; deduplicación por estado/referencia | Faltan identidad de evento, identidad de operación, aplicación semántica global, digest y restricciones persistentes |
| Redis original | Caché de metadatos de tipos, rate limit de login y refresh tokens | ADR-014 documenta como desviaciones la ausencia de caché de eventos, claves de reserva y rate limit de reservas/órdenes; no se marcarán como cumplidas |

## 6. Riesgos y contradicciones

1. **Diseños 03-E ausentes:** el encargo resume las decisiones consolidadas, pero los documentos completos no existen en el historial del repositorio. Se usará el texto del encargo como requisito suministrado y cada decisión de diseño quedará formalizada antes del código correspondiente. No se afirmará que se reprodujeron documentos no disponibles.
2. **ADR-014 frente al encargo:** sus tres desviaciones explícitas impiden cerrar la Fase 4 original. Deben implementarse o mantener el dictamen como no viable; este trabajo las incluye en remediación.
3. **H-79 frente a ADR-009/ADR-011:** el comportamiento actual de reembolso automático no aporta evidencia financiera independiente. La política nueva debe separar pago recibido, necesidad de reembolso y reembolso confirmado.
4. **Cobertura:** añadir servicios/dominio sin pruebas hará fallar el umbral JaCoCo del 70 %; cada incremento funcional debe incluir tests.
5. **Entorno local sin Docker:** la concurrencia y las restricciones PostgreSQL deben validarse en CI con Testcontainers; los resultados se registrarán por SHA y run.
6. **Alcance:** no se añadirán publicación Kafka, consumidor de notificaciones, despliegue, AWS ni integraciones externas.

No se detectó una contradicción que exija detener todo el trabajo: el encargo actual autoriza resolver mediante ADR las decisiones de implementación y prohíbe expresamente declarar reembolsos sin evidencia. H-79 se tratará como obligatorio y bloqueante hasta contar con código y pruebas.

## 7. Plan de ejecución propuesto

1. **F4-01:** ADR-016 para orden global `T → R`; decisiones de unidades/generaciones, workers, identidades, recuperación, roles, retención, timeouts y parámetros; matriz de compatibilidad.
2. **F4-02:** bloqueo de inventario ordenado antes de reserva, revalidación con `clock_timestamp()`, lotes ≤100, pruebas de carreras y rollback.
3. **F4-03:** unidades comerciales y generaciones con FK/unicidad parcial en PostgreSQL; emisión idempotente y sustitución controlada.
4. **F4-04:** ciclos, participaciones, jobs efectivos, ejecuciones con lease/fencing, disposiciones y proyecciones; TX-RX/TX-CLAIM/TX-APPLY/TX-PROJECT.
5. **F4-05:** inbox de eventos, operaciones del proveedor y aplicaciones semánticas globales con digest; política H-79 sin reembolso no evidenciado.
6. **F4-06:** completar los requisitos Redis originales, validar migraciones limpias/evolutivas, regresión, concurrencia, seguridad, cobertura y CI.
7. **F4-07:** matrices de aceptación y H-75–H-80, riesgos residuales y dictamen; no declarar el cierre formal sin aprobación del usuario.

## 8. Dictamen de la entrega F4-00

**LÍNEA BASE VERIFICADA; IMPLEMENTACIÓN NO APTA TODAVÍA PARA CIERRE.** La compilación y las pruebas existentes pasan, pero los requisitos H-76–H-80 y tres desviaciones Redis permanecen abiertos. No se modificó código, no se creó ninguna rama, no se tocó `main`, no se desplegó y no se inició Fase 5.
