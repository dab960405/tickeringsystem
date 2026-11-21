# Pruebas adversarias locales — 15 de noviembre de 2026

Se ejecutaron intentos controlados contra la API y el frontend locales, además de
inyección de fallos en PostgreSQL/Redis desechables. El resultado es **cuatro fallos
reproducidos**, de consistencia o funcionalidad. No equivalen a cuatro accesos
ilegítimos: los intentos de acceso ajeno, alteración de firmas y escalada de rol
fueron rechazados.

Base del backend: rama `dev`, commit
`76a0fbb2380d009e13df75e1ab7e9a80f14b69e7`. El frontend Angular y las herramientas
locales eran cambios de trabajo aún sin commit. Esta entrega agrega pruebas y
documentación; no corrige la lógica productiva ni cambia contratos o migraciones.

## Resultado y evidencia

| Grupo | Superados | Fallidos | Inconclusos | Evidencia local |
| --- | ---: | ---: | ---: | --- |
| HTTP: 16 escenarios, 219 solicitudes | 15 | 1 | 0 | [Markdown](../reports/adversarial/run-20261115-02/http.md), [JSON con trazas](../reports/adversarial/run-20261115-02/http.json) |
| Integración aislada: 14 pruebas | 12 | 2 | 0 | [Markdown](../reports/adversarial/run-20261115-01/integration.md), [JSON](../reports/adversarial/run-20261115-01/integration.json), [log Maven](../reports/adversarial/run-20261115-01/integration.log) |
| Navegador real: 2 comprobaciones | 1 | 1 | 0 | [Observaciones del navegador y comprobación HTTP de assets](../reports/adversarial/run-20261115-02/browser.json) |
| Pruebas del propio script | 2 | 0 | 0 | `python3 -m unittest discover -s scripts -p test_adversarial.py -v` |

Los informes de `reports/adversarial/` están ignorados por Git y se generan en
cada ejecución. Este documento conserva la descripción revisable en el repositorio.
Las observaciones de navegador se tomaron con CUA; no están automatizadas por el
script Python. El archivo `browser.json` registra esa procedencia explícitamente.

La primera ejecución HTTP, en `run-20261115-01/http.json`, tenía una expectativa
demasiado estricta: exigía 401 cuando faltaba `X-Signature`. Spring rechaza esa
cabecera obligatoria ausente con 400, sin ejecutar el pago. Se corrigió **el test**,
se conservó la evidencia inicial y se repitieron los 16 escenarios en
`run-20261115-02`. Ese resultado inicial no cuenta como vulnerabilidad.

## Hallazgos confirmados

### REV-DEV-01 — Recuperar la proyección vuelve a evaluar un reembolso antiguo

**Criticidad: ALTA propuesta**, conservando la clasificación del análisis previo;
pendiente de dictamen del Tech Lead.

Reproducción en `OperationWorkerIT`, usando
[esta prueba adicional](../scripts/adversarial-fixtures/recovery-projection.java.txt):

1. Crear una orden pendiente.
2. Recibir evidencia de reembolso y completar APPLY: queda registrada como `NO_OP`
   porque aún no existe una captura. Interrumpir el recorrido antes de PROJECT.
3. Procesar una captura válida: pago `SUCCEEDED`, una entrada emitida.
4. Recuperar el job original mediante `processJob`, sin recibir otro evento.

**Esperado:** el pago permanece `SUCCEEDED`; recuperar PROJECT no crea un nuevo
efecto comercial. **Observado:** el pago cambia a `REFUNDED` y falla esa aserción.
La prueba se detiene en ese primer fallo; las comprobaciones posteriores de tickets,
fencing y nuevo evento de reembolso del archivo no llegaron a ejecutarse.

Causa identificada: `PaymentOperationService.processJob` siempre llama a APPLY
antes de PROJECT; `OperationJobService.claim` permite reclamar trabajos ya aplicados;
`SemanticApplicationService.apply` evalúa el callback comercial antes de comprobar
la identidad persistida. El mismo evento antiguo se interpreta sobre un estado
comercial posterior. No se observó un movimiento de dinero real ni doble cargo.

La prueba corta deliberadamente la secuencia entre dos transacciones confirmadas;
no mata una JVM. Reproduce el estado durable que debe soportar la recuperación.
La corrección debe respetar ADR-018 y separar la recuperación de proyecciones de
una nueva aplicación comercial. No necesita agregar infraestructura.

### REV-DEV-02 — La orden no expira si su reserva se canceló directamente

**Criticidad: MEDIA propuesta**, conservando la clasificación del análisis previo.

Reproducción en `OrderPaymentFlowIT`, usando
[esta prueba adicional](../scripts/adversarial-fixtures/cancelled-reservation.java.txt):

1. Crear reserva y orden pendiente del mismo comprador.
2. Cancelar directamente la reserva. Su stock se libera.
3. Situar `expires_at` tres minutos en el pasado, únicamente en la base desechable.
4. Ejecutar dos veces el servicio de expiración.

**Esperado:** cerrar la orden vencida, sin volver a liberar inventario.
**Observado:** `PENDING_PAYMENT / PENDING / CANCELLED`, cero entradas y stock
restaurado. No se realizó la transición esperada a `EXPIRED`.

La consulta `OrderRepository.findExpiredPendingOrderCandidates` solo selecciona
reservas `ACTIVE` o `EXPIRED`. Además, `OrderExpirationProcessor` retorna para una
reserva `CANCELLED / RELEASED`. Por tanto, repetir ese proceso no resuelve la orden.
Las aserciones posteriores sobre captura tardía no se ejecutaron al fallar la de
expiración; este informe no las presenta como verificadas.

La corrección debe definir y mantener un cierre coherente entre orden y reserva,
con liberación de inventario exactamente una vez.

### ADV-03 — Una cantidad decimal se convierte silenciosamente en entero

**Criticidad propuesta: BAJA**, por validación de entrada. No se demostró compra
gratuita, sobreventa ni cambio de precio.

Enviar a `POST /api/v1/reservations`, con una sesión de comprador y un tipo válido:

```json
{"ticketTypeId":"UUID_DEL_TIPO","quantity":1.5}
```

**Esperado:** 400 por cantidad no entera. **Observado:** 201 y `quantity: 1`.
`ReservationRequest` recibe un `Integer` y valida `@Positive`; la conversión a
entero ocurre antes de esa validación. El script canceló la reserva creada por
esta prueba y comprobó stock 20, igual al inicial.

Sí se rechazaron cero, negativos, nulo, booleano, desbordamiento de entero y
cantidad superior al máximo por orden. La corrección debe rechazar fracciones en
la deserialización, además de conservar las restricciones existentes.

### ADV-04 — Abrir o recargar una ruta de evento deja Angular en blanco

**Criticidad propuesta: MEDIA**, por impedir acceso directo a una página de compra.

1. Abrir `/` y navegar desde el catálogo a un evento: funciona.
2. Copiar `/eventos/{id}` a una pestaña nueva o recargar esa página: pantalla vacía.

En el navegador se observó `<app-root>` vacío y el script resuelto como
`/eventos/main-5YBPYVWV.js`. La comprobación HTTP confirmó 404 para esa ruta y 200
para `/main-5YBPYVWV.js`.

El HTML contiene `<base href="/">`, pero el servidor local entrega CSP
`base-uri 'none'`. Esa directiva impide usar el elemento base; la ruta del script
relativo se resuelve desde `/eventos/`. El diagnóstico concuerda con la
[documentación de MDN sobre base-uri](https://developer.mozilla.org/en-US/docs/Web/HTTP/Reference/Headers/Content-Security-Policy/base-uri).
La corrección debe compatibilizar el base de Angular con la política CSP y
verificarse con navegación directa y recarga, conservando el resto de protecciones.

## Protecciones que resistieron estos intentos

- Acceder, cancelar o pagar órdenes de otro usuario, cancelar su reserva o crear
  una orden con ella: cinco rechazos 404, sin emitir entradas ni cambiar la orden.
- Solicitud anónima, JWT con rol alterado y refresh token usado como access token:
  401. Registro con `role: ADMIN`: se creó `USER`; crear evento devolvió 403.
- Precio y moneda enviados por el comprador: la orden conservó 45 USD del catálogo.
- Firma ausente: 400. Firma falsa o cuerpo alterado conservando la firma: 401.
  El control posterior con firma válida emitió una entrada.
- Evidencia firmada con importe o moneda incorrectos: 422, sin emitir entradas.
  Estos casos usan el secreto de prueba para simular un proveedor defectuoso;
  no demuestran que un cliente anónimo pueda firmar evidencia.
- Seis solicitudes simultáneas con la misma clave: una orden, 201/200. Reutilizar
  la clave con otra reserva: 409.
- Ocho capturas iguales, seis reembolsos iguales y ocho eventos con identidad
  comercial compartida: efectos únicos. La identidad con datos contradictorios
  devolvió 409.
- Veinte reservas simultáneas sobre dos unidades: dos aceptadas, dieciocho 409,
  stock final reservado cero y stock dos después de liberar ambas reservas.
- Tres carreras HTTP entre captura y cancelación: resultados coherentes. En esta
  ejecución ganó la cancelación en las tres: cero entradas, stock liberado y
  `refundStatus: REQUIRED` al llegar la captura. No se afirma haber observado
  ambos órdenes de ejecución en esas tres carreras.
- Proxy local que descarta la respuesta después del commit: primer resultado
  interno 201, cliente sin respuesta, reintento 200 con el mismo ID y una sola orden.
- Reembolso anticipado, captura, repetición del mismo evento y fallo tardío:
  la compra siguió pagada con una entrada. Esto no reproduce la interrupción
  APPLY/PROJECT de REV-DEV-01, que necesita la prueba aislada.
- Origen ajeno, falta de origen o sesión en el simulador: 403/401. El simulador
  autorizado ignoró el importe enviado por el navegador y tomó el de la API.
- Archivos privados, fuentes y traversal en el servidor local: 404; Host ajeno: 403.
- Tres cadenas con sintaxis SQL en búsqueda se trataron como texto y devolvieron
  cero resultados, sin ampliar acceso ni producir 500.
- XSS en descripción: el payload `<img ... onerror=...>` se vio como texto literal;
  cero elementos `img[onerror]`, título normal. Coincide con el uso de interpolación
  en `checkout.html` y el [modelo de seguridad de Angular](https://angular.dev/best-practices/security).
- Redis caído en contenedores aislados: fallback a PostgreSQL, recuperación,
  sesiones que fallan de forma cerrada y token de acceso válido todavía utilizable.
  Se ejercitó también caché desactualizada hasta su TTL y los timeouts de Lettuce.
- Carreras de confirmación/cancelación/expiración y dos workers: cinco pruebas de
  integración pasaron, incluyendo rollback si falla restaurar inventario.

## Ejecutar de nuevo

Con API y frontend iniciados y `.env` configurado:

```bash
bash scripts/local.sh adversarial
```

Para incluir las pruebas aisladas (Java 21, Docker y dependencias Maven disponibles):

```bash
bash scripts/local.sh adversarial --with-integration
```

También puede ejecutarse únicamente la parte aislada:

```bash
python3 scripts/adversarial-integration.py --output reports/adversarial/integration-nueva
```

`--api` y `--frontend` permiten otros puertos de loopback; no se aceptan destinos
remotos, credenciales en URL ni redirecciones. `--output` selecciona una carpeta
nueva: los informes existentes no se sobrescriben. Valores de salida: **0** todos
los controles ejecutados pasan; **1** hallazgos; **2** ejecución incompleta.
Actualmente la salida 1 es esperada por los fallos documentados.

El script HTTP usa solo la biblioteca estándar de Python, crea un evento y cuentas
ficticias por ejecución y conserva esas compras para inspección. Los eventos se
reconocen por `Adversarial <runId>`. No borra datos existentes. Los tests aislados
se insertan en una copia temporal del código y usan bases desechables; no se añaden
tests rojos a la suite habitual ni se modifica su configuración de calidad.

Los fixtures suponen el organizador y recinto del seed de desarrollo. Pueden
configurarse con `SMOKE_ORGANIZER_EMAIL`, `SMOKE_ORGANIZER_PASSWORD` y
`SMOKE_VENUE_ID`. `PAYMENT_WEBHOOK_SECRET` se carga de `.env`, no se imprime.
La traza HTTP guarda método, ruta, estado y duración, nunca cuerpos de login,
tokens, contraseñas o firmas.

## Límites de esta evidencia

Son escenarios concretos y acotados, no una certificación ni una auditoría
exhaustiva. No se probaron pagos reales, ataques de carga sostenida, Internet,
Kafka/MongoDB, dependencias vulnerables, todas las superficies XSS/SQL ni todas las
intercalaciones posibles. La suite Java completa de 567 pruebas no se volvió a
ejecutar en esta entrega: se ejecutó el conjunto adversario indicado arriba.

Al finalizar, la API respondió 200 a `/actuator/health` y el frontend 200 en `/`.
El proceso del servidor frontend dejó de estar activo después de las comprobaciones
de navegador y se volvió a iniciar; no hay evidencia para atribuir ese fin de proceso
a una vulnerabilidad. La pantalla vacía de ADV-04 se observó antes, con el servidor
respondiendo, y tiene evidencia independiente de resolución de assets.

Para un portfolio, priorizar REV-DEV-01 por consistencia de pagos y ADV-04 por la
demo visible; después resolver REV-DEV-02 y la validación de cantidades. Ninguno
de estos hallazgos requiere añadir servicios o infraestructura nueva.
