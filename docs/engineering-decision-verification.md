# Política de verificación técnica previa a decisiones — Ticketing

- **Estado:** criterio obligatorio de revisión para decisiones nuevas de ingeniería y para revisar las existentes.
- **Fecha de incorporación:** 2026-11-14.
- **Alcance:** backend, PostgreSQL, concurrencia, seguridad, caché, mensajería, Docker, Terraform, AWS e infraestructura de laboratorio.
- **Propósito:** demostrar competencias en un portafolio sin reinventar primitivas estándar ni importar arquitectura empresarial que no exige la especificación.
- **Gobernanza:** ninguna recomendación supone aceptación del Tech Lead. Los ADR con estado `Propuesto` requieren dictamen explícito; una aprobación de tareas no equivale a aprobación arquitectónica.

## Protocolo de doble comprobación por decisión

1. **Fijar el problema y el contrato:** localizar requisito de la especificación, ADR aprobados, componente, versión del software, SHA exacto y restricciones de alcance. Separar comportamiento exigido de una hipótesis de diseño.
2. **Primera fuente confiable:** consultar la documentación **oficial y versionada** de la tecnología que ejecuta el mecanismo (Spring Framework/Spring Boot, PostgreSQL, Java, Apache Kafka, MongoDB, Docker, HashiCorp, AWS, GitHub).
3. **Segunda comprobación independiente:** contrastar con otra fuente primaria o normativa de un mantenedor u organismo diferente cuando sea aplicable (p. ej. Spring + PostgreSQL para atomicidad; PostgreSQL + IETF para semántica HTTP; Oracle Java + PostgreSQL para autenticidad/identidad). Una segunda URL del mismo fabricante es apoyo adicional, pero **no** valida independientemente una política de negocio.
4. **Confrontar el código real:** comprobar implementación y configuración por archivo/función y SHA, no por comentarios o diagramas solamente. Registrar condiciones de validez (por ejemplo, llamadas vía proxy de Spring, FK implícitas, versiones soportadas).
5. **Comparar alternativas:** mantener solución existente, reutilizar primitivas documentadas o introducir implementación nueva; registrar costo de complejidad, seguridad, operabilidad, reversibilidad, impacto en lock order/API/esquema y presupuesto del portafolio.
6. **Evidencia ejecutada:** antes de declarar un cambio exitoso, exigir regresiones pertinentes y `./mvnw -B clean verify` con CI verde y mismo SHA; usar PostgreSQL/Testcontainers para transacciones y carreras. Si no se ejecutó localmente, indicarlo.
7. **Registrar y solicitar dictamen:** documentar fuentes, evidencia, riesgos no resueltos y la decisión **propuesta** en el ADR. No cambiar de estado hasta aceptación del Tech Lead; no cerrar fase hasta autorización formal.

**Regla de parada:** sin fuente oficial relevante, con contradicción entre fuente y código, o sin prueba del requisito afectado, detener la implementación/aceptación y solicitar el criterio faltante. No crear tecnologías, workers, migraciones o dependencias para resolver hipótesis sin reproducir el problema.

## Verificación aplicada — Fase 3, ADR-024

**SHA examinado:** `d92238a9f7d37dd0127db4029bb4fcb387886457`.

| Control | Fuente primaria / contraste | Evidencia en repositorio | Resultado |
| --- | --- | --- | --- |
| Una única transacción PostgreSQL | [Spring Framework 6.2: PROPAGATION_REQUIRED](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/tx-propagation.html); [@Transactional y reglas de rollback](https://docs.spring.io/spring/reference/6.2/data-access/transaction/declarative/annotations.html) | `EventCancellationService` lleva `@Service @Transactional`, método público `cancel(UUID)` invocado como bean; `EventGuardConcurrencyIT` fuerza una excepción de PostgreSQL y verifica rollback | **Conforme en escenarios probados**; no prometer rollback en autoinvocaciones ni excepciones checked sin regla explícita |
| Orden de locks y deadlocks | [PostgreSQL 17: explicit locking / deadlocks](https://www.postgresql.org/docs/17/explicit-locking.html) + reglas de transacción Spring | `EventLockService.lockExclusive`, `TicketTypeInventoryService.lockAll`, `FOR UPDATE OF r`, `FOR UPDATE OF o`; ADR-016 audita locks implícitos de FK | **Conforme al protocolo E → T → R → O** documentado; reauditar si cambian FKs o flujo |
| Proyección Redis solo después del commit | [Spring Framework 6.2: TransactionSynchronization.afterCommit](https://docs.spring.io/spring-framework/docs/6.2.6/javadoc-api/org/springframework/transaction/support/TransactionSynchronization.html) | `ReservationRedisProjectionService.deleteAfterCommit` usa `TransactionSynchronizationManager` y callback `afterCommit`; regresión mantiene proyección si falla transacción | **Conforme en llamada transaccional**. Precaución: si se invoca sin sincronización activa, su utilidad ejecuta la acción inmediatamente |
| Integridad de límites modulares | [Spring Modulith 1.4: verificación de módulos](https://docs.spring.io/spring-modulith/reference/1.4/verification.html) | `EventCancellationService` importa repositorios de otros módulos y SQL transversal | **Excepción real** frente a separación recomendada. Spring NO prescribe aceptar la excepción; requiere decisión del Tech Lead |
| Evidencia automatizada | [CI #318](https://github.com/dab960405/tickeringsystem/actions/runs/43665308382) y [F3-01](phase-3/F3-01-closure-review.md) | 390 unitarias, 171 integraciones; `EventGuardConcurrencyIT` 10/10; Spotless y JaCoCo | Conforme a los tests ejecutados, no prueba exhaustividad de todas las intercalaciones |

**Dictamen de desarrollador:** recomendar una excepción **limitada solo a `EventCancellationService.cancel`**, conservando TX/locks/pruebas; **ADR-024 sigue PROPUESTO**. Alternativa con interfaces de aplicación si el Tech Lead exige límites estrictos, sin rehacer el workflow con sagas.

## Verificación aplicada — Fase 4, ADR-022 / H84-01

| Control | Fuente primaria / contraste | Evidencia en repositorio | Resultado |
| --- | --- | --- | --- |
| Identidades únicas y conflictos concurrentes | [PostgreSQL 17: UNIQUE](https://www.postgresql.org/docs/17/ddl-constraints.html) y [INSERT ON CONFLICT](https://www.postgresql.org/docs/17/sql-insert.html) | `ProviderEventInboxService.registerOperation/registerEvent` usa `INSERT ... ON CONFLICT DO NOTHING RETURNING id` y compara evidencia; Flyway V14 impone índices únicos | Conforme para las identidades cubiertas; no cambiar digest sin nuevo contrato |
| Autenticidad del webhook | [Oracle Java 21: javax.crypto.Mac](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/javax/crypto/Mac.html) y [MessageDigest.isEqual](https://docs.oracle.com/en/java/javase/21/docs/api/java.base/java/security/MessageDigest.html) | `PaymentWebhookService` calcula HMAC-SHA256 sobre el payload **raw UTF-8**, compara mediante `MessageDigest.isEqual` antes de procesar | Conforme para simulador; **no proporciona por sí solo protección temporal anti-replay**, deuda ya documentada |
| HTTP 409 ante evidencia incompatible | [IETF RFC 9110 `15.5.10](https://www.rfc-editor.org/rfc/rfc9110.html#section-15.5.10); [PostgreSQL 17: conflictos de unicidad](https://www.postgresql.org/docs/17/sql-insert.html) | `DuplicateResourceException` se traduce a `HttpStatus.CONFLICT` en `GlobalExceptionHandler` | Conforme |
| Referencia estable del mismo reembolso | PostgreSQL UNIQUE no define reglas de pagos; esta es una **decisión de contrato del simulador**, no exigencia de PostgreSQL | `PaymentEvidenceDigest.semantic` incluye `providerReference` pero excluye `status`; `ProviderEventInboxService` rechaza distinta evidencia para `providerOperationId` | Contrato mínimo propuesto: usar referencia estable desde el primer evento si se espera una progresión de estados |
| REFUNDED anticipado y reevaluación | [Spring Framework 6.2: transacciones declarativas](https://docs.spring.io/spring-framework/reference/6.2/data-access/transaction/declarative/tx-propagation.html); integridad/identidad PostgreSQL | `OrderPaymentService.confirmRefund` devuelve `NO_OP` sin captura `SUCCEEDED`; otro evento firmado con nuevo `providerEventId` pero misma operación/digest se reevalúa y da `APPLIED` si procede; `OrderPaymentFlowIT.should_applyPreviouslyEarlyRefund_whenNewSignedEventArrivesAfterCapture` | **Validado en CI #309 y #318**, sin reconciliador automático. No equivale a reconciliación financiera garantizada |

**Dictamen de desarrollador:** recomendar mantener el contrato SIMULATED existente, sin enriquecimiento `null → non-null` ni reconciliador. **ADR-022 sigue PROPUESTO**. Proveedor real, antirreplay temporal, conciliación automática y otras capacidades bancarias están **fuera de alcance**, salvo cambio expreso de requisitos.

## Límites de esta auditoría

- La documentación de terceros **respalda mecanismos, no aprueba decisiones de negocio** ni una excepción modular.
- Se inspeccionaron los servicios, la validación de entrada, el mapeo 409, el ERD, los ADR y las pruebas relevantes en el SHA indicado; el CI #318 es verde. Esto **no equivale** a una prueba formal de todas las ejecuciones concurrentes ni a una prueba manual Docker Compose en el equipo del usuario.
- No se han creado nuevas dependencias, tablas, APIs, servicios o recursos AWS; las pruebas locales están planificadas para Fase 5, **sin autorizar todavía su implementación**.
- Requests para dictamen técnico: [Fase 3, issue #5](https://github.com/dab960405/tickeringsystem/issues/5) y [Fase 4, issue #6](https://github.com/dab960405/tickeringsystem/issues/6). **Abiertos hasta resolución expresa**.
