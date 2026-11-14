# F4-05 — Expediente de revisión final de Fase 4

- **Estado:** expediente candidato a cierre, pendiente de dictamen del Tech Lead y responsable. Cierre formal NO aprobado.
- **Fecha:** 2026-11-14
- **Rama auditada:** `dev`.
- **Base técnica validada:** `a8013f67bffe3a5826606469485335b61e13a93d` — CI #309 `success` ([run](https://github.com/dab960405/tickeringsystem/actions/runs/43647059382)).
- **Última base documental previa validada:** `40b6cad9aecf33af666a0150c7393a72fa84317d`, CI #307, `success`.
- **Alcance:** H75–H84, F4-01–F4-04, ADR-016–ADR-020 y propuesta ADR-022.

## Matriz de evidencias

| Hallazgo | Evidencia ya verificada | Dictamen para revisión |
| --- | --- | --- |
| H75 / H76 / H83 — locks globales, T → R y FK | F4-03, CI #292; regresiones de locks y concurrencia | Cobertura técnica validada; aceptación formal pendiente |
| H77 — unidades y generaciones | V16, `CommercialIssuanceIT`, CI #278 y sucesivos | Implementado y probado |
| H78 — jobs, leases, fencing, recuperación | V15, `OperationWorkerIT` 12/12 en CI #306 | Implementado y probado para escenarios cubiertos |
| H79 — pago tardío y recuperación | `OrderPaymentFlowIT`, CI #296; ADR-019 | Implementado y probado |
| H80 — identidades y evidencia persistente | V14/V15, CI #298, #302 y #306 | Implementado; contrato mínimo de simulación requiere dictamen |
| H81 — Redis y degradación | ADR-013/014/020, CI #286 y suite completa posterior | Implementado y probado |
| H82 — parámetros y timeouts | `OperationPropertiesTest`, `OperationWorkerIT`, CI #296 | Implementado y probado |
| H84 — estados de reembolso y carreras | CI #302 (`OrderPaymentFlowIT` 35/35), CI #306 (`OperationWorkerIT` 12/12), CI #309 (`OrderPaymentFlowIT` 36/36) | Evidencia técnica verificada; pendiente aceptación ADR-022 y dictamen formal |

## Resolución técnica propuesta de H84-01

De acuerdo con la especificación de portafolio, se propone documentar como contrato del proveedor **simulado** la referencia inmutable por operación y el reenvío explícito de un nuevo evento HMAC después de una captura tardía. Evita diseñar enriquecimiento de referencias y conciliación automática sin requisitos reales. Véase [ADR-022](../adr/ADR-022-simulated-refund-evidence-contract.md) (`Propuesto`). No requiere nuevas tablas, APIs, migraciones ni workers.

## Comprobaciones antes del dictamen

1. **Verificado:** CI #308 completó 390 unitarias y 170 integraciones sin fallos, pero `BUILD FAILURE` por formato Spotless en un único método nuevo. Corrección exacta en `a8013f67`; CI #309 terminó `success` sobre ese SHA: 390 unitarias, 170 integraciones, `OrderPaymentFlowIT` 36/36, `OperationWorkerIT` 12/12, Spotless 221 Java limpios, JaCoCo y `BUILD SUCCESS`.
2. Obtener dictamen expreso del Tech Lead sobre ADR-022, sin presumir aceptación por el hecho de estar documentado.
3. Resolver la excepción cross-module de `EventCancellationService` que bloquea el **cierre formal de Fase 3**. **Recomendación para el portafolio:** aceptar expresamente una excepción transaccional estrecha, limitada al coordinador de cancelación, porque el código actual mantiene E → T → R → O en una sola transacción y evita introducir orquestación adicional; exigir documentación del límite, invariantes y regresiones existentes. Alternativa: refactor aprobado si el Tech Lead considera inaceptable el acoplamiento. El acceso directo a repositorios y SQL de otros módulos sigue en tensión con la especificación; **no se considera aprobada** la excepción.
4. Revisar la trazabilidad de los criterios de aceptación y emitir dictamen independiente por hallazgo. Solo el Tech Lead puede proponer la aceptación; el responsable autoriza el cierre formal.
5. Una vez obtenido el cierre expreso de Fase 3 y 4, autorizar por separado el inicio de Fase 5.

## Límites explícitos

- No se ha ejecutado un smoke test manual con Docker Compose en el equipo del usuario; `clean verify` en CI sí cubre PostgreSQL y Redis mediante Testcontainers. No se presenta evidencia local inexistente.
- La **primera validación local** del proyecto está planificada para el arranque autorizado de Fase 5, antes de implementar mensajería. Véase [F5-00](../phase-5/F5-00-plan-and-local-validation.md).
- `main` queda fuera del alcance; no se toca. No se crea infraestructura AWS ni se ejecuta Terraform.

## Solicitud de dictamen

Se solicita aceptación/rechazo expreso de: (a) contrato mínimo simulado ADR-022; (b) excepción arquitectónica de Fase 3; (c) cobertura H75–H84 y cierre formal de Fase 4. **Hasta ese dictamen: Fase 4 ABIERTA y Fase 5 BLOQUEADA.**
