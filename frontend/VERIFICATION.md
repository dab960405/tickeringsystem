# Verificación local de la migración Angular

Fecha: 15 de noviembre de 2026. Checkout `dev`, API Java local, PostgreSQL y Redis.
Angular 22.2.6, CLI 22.2.7, Node 24.22.3. Todos los pagos descritos son simulados.

## Comprobaciones automatizadas

| Comando | Resultado |
| --- | --- |
| `npm ci --no-fund` | Instalación limpia reproducible; auditoría npm sin vulnerabilidades reportadas en esa ejecución |
| `npm test` | 14 pruebas aprobadas, 4 archivos, sin fallos |
| `npm run build` | Compilación de producción correcta, sin advertencias de presupuesto |
| `npm run format:check` | Formato correcto |
| `python3 -m unittest discover -s frontend -p 'test_*.py' -v` (raíz) | 10 pruebas aprobadas |
| `git diff --check` | Correcto |

El bundle inicial es de aproximadamente 309 kB, con 86 kB de transferencia estimada
por Angular CLI. Las pantallas se dividen en chunks de carga diferida. Es una medida
del build, no una medición de rendimiento de producción.

## Recorrido de navegador contra la API real

- Registro de un comprador ficticio e inicio de sesión con retorno al evento.
- Compra de dos entradas General por USD 90: reserva, navegación a Mis compras,
  retorno conservando la reserva, orden, pago aprobado y dos tickets visibles.
- Recarga y acceso a una ruta privada: redirección al login; al entrar se recupera
  la compra guardada en el backend.
- Solicitud y confirmación de reembolso: orden reembolsada y entradas vigentes vacías.
- Liberación de una reserva previa a la orden, con nueva consulta de disponibilidad.
- Cancelación de una orden pendiente y pago rechazado en otra: ambas canceladas.
- Segunda compra aprobada de una entrada por USD 45 y copia de su código.
- Cierre de sesión y nuevo acceso: la entrada válida permanece disponible.
- Búsqueda vacía y con resultados; el filtro aparece en la URL.
- Vista adaptable a 320, 768, 1024 y 1440 píxeles sin desbordamiento horizontal en las
  pantallas revisadas. Enlace de salto por teclado enfoca el contenido y conserva la URL.

Órdenes de demostración conservadas para inspección:

| Orden | Resultado |
| --- | --- |
| `8605c6ab-c5e7-45f5-a6ca-954ecfbf283a` | USD 90, pagada y posteriormente reembolsada |
| `1d86843b-1495-4e51-82a9-802b1c11cffc` | Cancelación antes del pago |
| `23bad2e6-7914-4d8d-9974-e4ef7cb538b0` | Pago rechazado; orden cancelada |
| `758cfe71-aefd-4df5-88d5-643c80706bb9` | USD 45, pagada y ticket vigente |

## Hallazgo corregido durante la prueba

Al confirmar un pago, la navegación destruía `OrderActions` antes de que terminara su
promesa; el componente intentaba emitir `updated` después y Angular avisaba `NG0953`.
La prueba de regresión reprodujo el fallo. La corrección comprueba `DestroyRef.destroyed`
antes de emitir. La prueba pasó y la compra posterior no produjo nuevos avisos ni
errores en consola. También se comprobó que el enlace de salto no recarga la SPA.

## Límites de esta evidencia

No se modificó Java ni se volvió a ejecutar su suite completa en esta migración.
La caducidad real de diez minutos y el diálogo del sistema de impresión no se
recorrieron manualmente. La renovación del token se verificó con pruebas HTTP
controladas, sin esperar su caducidad real. Estas comprobaciones locales no equivalen
a una auditoría de accesibilidad ni a pruebas de carga o despliegue público.
