# Frontend Angular de Ticketing

Interfaz del comprador con Angular 22.2.6, CLI 22.2.7 y TypeScript estricto.
Conserva los contratos existentes de la API Java: catálogo, autenticación, reservas,
órdenes, pagos simulados, reembolsos y tickets. El servidor Python es únicamente una
herramienta local para servir la compilación y firmar los webhooks ficticios.

## Requisitos y arranque

- Node 24 LTS (24.15 o superior; versión utilizada: 24.22.3, indicada en `.nvmrc`).
- npm 11 o 12, Python 3.10+, API Java y PostgreSQL/Redis iniciados.
- `.env` configurado en la raíz; `PAYMENT_WEBHOOK_SECRET` debe coincidir con la API.

Desde la raíz del repositorio, instala las dependencias una vez:

```bash
cd frontend
npm ci
cd ..
bash scripts/local.sh ui
```

Abre <http://localhost:5173>. `ui` compila Angular en modo producción y sirve el resultado
con el simulador local. `Ctrl+C` detiene la interfaz. Consulta
[LOCAL_RUN.md](../docs/LOCAL_RUN.md) para levantar la API.

Si Node está instalado fuera del `PATH`, puedes definir `NODE_HOME` en tu `.env` local
con la carpeta de instalación que contiene `bin/node`. `local.sh ui` añade ese `bin`
al `PATH` del proceso. Para ejecutar npm directamente, añade también esa carpeta al
`PATH` de tu terminal. No se requiere instalar Angular CLI globalmente.

Para editar con recompilación automática, deja el servidor local iniciado y ejecuta
`npm run watch` desde `frontend` en otra terminal. Recarga el navegador después de los
cambios. No se utiliza el servidor de `ng serve`: la misma dirección local sirve
Angular, la API y el simulador, sin introducir una configuración CORS adicional.

## Organización y decisiones

```text
src/app/
  core/       DTOs, cliente HTTP, autenticación, interceptor, guardas y avisos
  catalog/    Búsqueda paginada; filtros en la URL
  auth/       Registro e inicio de sesión con formularios reactivos tipados
  purchase/   Reserva, orden, contador y acciones de pago/reembolso
  account/    Compras y entradas del comprador
  app.*       Navegación, composición y configuración
```

- Componentes standalone y organización por funcionalidad. Las páginas se cargan
  bajo demanda mediante Angular Router; no hay NgModules ni un store externo.
- `signal`/`computed` para estado local y compartido pequeño, componentes `OnPush` y
  listas con `@for` y claves estables. El checkout sobrevive a la navegación interna.
- `HttpClient` y DTOs tipados centralizan el contrato. Un interceptor funcional limita
  el envío del token a las rutas locales, comparte una renovación concurrente y
  reintenta el 401 una vez. No reintenta automáticamente mutaciones por errores de red.
- Formularios reactivos tipados, validación explícita, estados vacíos, errores,
  reintentos y bloqueo de envíos repetidos. La API sigue validando cantidades e importes.
- Las búsquedas usan `switchMap` y `takeUntilDestroyed`. El contador se cancela al salir
  de la vista. Las acciones asíncronas comprueban `DestroyRef` antes de emitir tras navegar.
- Tokens solo en memoria. Recargar exige volver a entrar; compras y tickets persisten
  en el backend. Las guardas ayudan a navegar, pero la autorización pertenece a la API.
- Interpolación de Angular; no `innerHTML`, bypass del sanitizador ni secretos en el bundle.
- Presupuesto de tamaño del CLI, compilación AOT, TypeScript y plantillas estrictos,
  formateo con Prettier y dependencias reproducibles mediante `package-lock.json`.

## Recorrido funcional

1. Busca un evento y pulsa **Ver entradas**.
2. Regístrate o inicia sesión; volverás al evento elegido.
3. Elige tipo y cantidad. Reserva y crea la orden antes de vencer el contador.
4. Simula un pago aprobado para recibir tickets, o uno rechazado para comprobar el fallo.
5. Consulta **Mis entradas**, copia los códigos o utiliza la impresión del navegador.
6. En **Mis compras**, cancela una orden pendiente o solicita y confirma un reembolso.
   Después de confirmarlo, sus entradas dejan de estar vigentes.

Antes de crear una orden puedes liberar la reserva. Después se utiliza la cancelación
de la orden, para no dejar una orden pendiente sin su reserva. La clave de idempotencia
se conserva al reintentar la creación. Los importes y la moneda del pago se leen desde
la API con el token del propietario, nunca desde datos financieros elegidos por el navegador.

## Verificación

El recorrido ejecutado, los resultados y sus límites están registrados en
[VERIFICATION.md](VERIFICATION.md).

Desde `frontend`:

```bash
npm test
npm run build
npm run format:check
python3 -m unittest discover -s . -p 'test_*.py' -v
```

Las pruebas Angular usan Vitest, TestBed y HttpTestingController. Cubren renovación
concurrente, límites de envío del token, cierre de sesión, formularios, idempotencia,
reserva duplicada, confirmación de entrega y destrucción del componente al navegar.
Las pruebas Python cubren firma/propiedad, reintentos, origen local y entrega de archivos
compilados/rutas Angular. No necesitan Docker, aunque Python abre un puerto efímero.
Las pruebas Java siguen ejecutándose con los comandos del repositorio.

## Fuentes oficiales utilizadas

Consultadas el 15 de noviembre de 2026; las versiones efectivas están fijadas en el lockfile.

| Decisión | Fuente |
| --- | --- |
| Node, TypeScript y RxJS compatibles | [Angular: Version compatibility](https://angular.dev/reference/versions) |
| Archivos por funcionalidad y componentes enfocados | [Angular: Style guide](https://angular.dev/style-guide) |
| Estado reactivo sencillo | [Angular: Signals](https://angular.dev/guide/signals) |
| Formularios reactivos y tipados | [Angular: Reactive forms](https://angular.dev/guide/forms/reactive-forms), [Typed forms](https://angular.dev/guide/forms/typed-forms) |
| Autenticación HTTP transversal | [Angular: Interceptors](https://angular.dev/guide/http/interceptors) |
| Rutas y carga de páginas | [Angular: Define routes](https://angular.dev/guide/routing/define-routes) |
| Limpieza al destruir vistas | [Angular: DestroyRef](https://angular.dev/api/core/DestroyRef) |
| Compilación e interpolación seguras | [Angular: Security](https://angular.dev/best-practices/security) |
| Pruebas del cliente HTTP y componentes | [Angular: HTTP testing](https://angular.dev/guide/http/testing), [Testing overview](https://angular.dev/guide/testing) |

## Alcance

Demo local del comprador. No implementa una pasarela comercial, panel administrativo,
escáner de acceso ni correo. Los tickets usan el código real de la API; no se inventa
un formato QR. Los contratos de órdenes/tickets no incluyen el nombre del evento, por
eso se identifican por el número de compra y sus identificadores.

`dev_server.py` escucha exclusivamente en `127.0.0.1`; no es un servidor para producción.
`FRONTEND_PORT` y `TICKETING_API_PORT` permiten cambiar los puertos locales. El secreto
HMAC permanece en Python. Los archivos servidos se limitan a `dist/ticketing-ui/browser`;
no se expone el código fuente ni `.env`. No se incorpora SSR para este recorrido local.
