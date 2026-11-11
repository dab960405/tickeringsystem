# Ejecución local

## Requisitos

- Java 21.
- Maven 3.9 o superior (se recomienda usar el Maven Wrapper incluido).
- Docker Engine/Desktop con Docker Compose v2 y soporte para Testcontainers si se ejecutan pruebas de integración.
- Puertos disponibles: 5432 (PostgreSQL), 6379 (Redis), 9092 (Kafka), 27017 (MongoDB) y 8080 (API), salvo que se cambien en `.env`.

## Preparar variables de entorno

Desde la raíz del repositorio, copia el archivo de ejemplo:

```bash
cp .env.example .env
```

En Windows PowerShell:

```powershell
Copy-Item .env.example .env
```

Edita `.env` y reemplaza los valores de ejemplo, especialmente contraseñas y secretos. Genera un secreto JWT de al menos 32 bytes:

```bash
openssl rand -base64 32
```

Configura el resultado en `JWT_SECRET`. Define también un valor aleatorio para `PAYMENT_WEBHOOK_SECRET`; ambos secretos son independientes. No subas `.env` al repositorio.

Docker Compose lee `.env` automáticamente para interpolar los valores del compose. La aplicación ejecutada directamente desde el host necesita que esas variables estén exportadas en el proceso que inicia Java.

Bash / Linux / macOS:

```bash
set -a
source ./.env
set +a
```

Windows PowerShell:

```powershell
Get-Content .env | ForEach-Object {
  if ($_ -match '^\s*([^#][^=]*)=(.*)$') {
    [Environment]::SetEnvironmentVariable($matches[1].Trim(), $matches[2].Trim(), 'Process')
  }
}
```

Vuelve a cargar las variables en cada terminal nueva. Spring Boot usa el perfil `dev` por defecto; `SPRING_PROFILES_ACTIVE` puede sobrescribirlo.

## Levantar infraestructura

Desde la raíz del repositorio:

```bash
docker compose --profile infra up -d
docker compose ps
```

El perfil `infra` inicia PostgreSQL, Redis, Kafka y MongoDB. Espera a que los cuatro servicios muestren estado `healthy` antes de iniciar las aplicaciones. MongoDB y Kafka forman parte de la infraestructura del proyecto, aunque la API principal utiliza PostgreSQL y Redis para las funciones cubiertas en esta fase.

Para consultar el estado de salud de un servicio:

```bash
docker compose ps
docker compose logs --tail=100 postgres redis kafka mongodb
```

## Ejecutar pruebas

Los tres comandos tienen alcances distintos:

```bash
# Ejecuta las pruebas unitarias (*Test.java) mediante Surefire.
./mvnw test

# Ejecuta el ciclo hasta verify, incluidas las pruebas unitarias,
# pero omite las pruebas de integración (*IT.java) de Failsafe.
./mvnw clean verify -DskipITs

# Ejecuta el ciclo completo: pruebas unitarias, integración,
# JaCoCo y comprobación de formato Spotless.
./mvnw clean verify
```

- `./mvnw test` no alcanza la fase `integration-test` de Failsafe.
- `./mvnw clean verify -DskipITs` omite las pruebas de integración; no demuestra que los escenarios con Testcontainers funcionen.
- `./mvnw clean verify` ejecuta las pruebas de integración. Requiere Docker disponible para los contenedores de Testcontainers.
- El build aplica Spotless durante `verify` y comprueba el umbral de cobertura de líneas JaCoCo para paquetes `service` y `domain`.

## Construir e iniciar ticketing-api

Con las variables de entorno cargadas y la infraestructura disponible:

```bash
./mvnw -pl ticketing-api -am package -DskipTests
java -jar ticketing-api/target/ticketing-api-0.1.0-SNAPSHOT.jar
```

El primer comando construye el módulo compartido requerido y empaqueta la API sin ejecutar las pruebas. El segundo inicia la API en el puerto definido por `TICKETING_API_PORT` (8080 por defecto). Mantén la terminal abierta para consultar los logs.

## Comprobar la API

En otra terminal:

```bash
curl -i http://localhost:8080/actuator/health
```

La respuesta esperada cuando la aplicación y sus dependencias reportadas por Actuator están saludables es HTTP 200 con `"status":"UP"`. Si se cambia `TICKETING_API_PORT`, ajusta el puerto de la URL.

La documentación OpenAPI está disponible en:

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`

## Parámetros relevantes de Fase 4

| Variable | Valor predeterminado | Uso |
| --- | --- | --- |
| `TICKET_TYPES_CACHE_TTL` | `5m` | TTL de la caché de metadatos del catálogo de tipos de entrada. Acepta unidades de duración de Spring, por ejemplo `30s` o `5m`. |
| `LOGIN_MAX_ATTEMPTS` | `5` | Máximo de intentos de login por combinación de IP y email dentro de la ventana fija. |
| `LOGIN_WINDOW_SECONDS` | `60` | Duración de la ventana del rate limiter de login, en segundos. |

Los timeouts Redis de la API están configurados en `application.yml` a 500 ms para comandos. La prueba específica de indisponibilidad también verifica el timeout de comando de Lettuce y el timeout de conexión de 500 ms configurado en su contexto.

## Firma HMAC del webhook en flow.http

La solicitud `POST /api/v1/payments/webhook` requiere que `X-Signature` contenga el HMAC-SHA256 hexadecimal del cuerpo raw exacto. La extensión REST Client de VS Code **no puede calcular esta firma**; debes generarla fuera del archivo HTTP y copiar el digest hexadecimal en la variable `@webhookSignature` de `docs/flow.http`.

Con `PAYMENT_WEBHOOK_SECRET` exportado desde `.env`, sustituye el UUID de ejemplo por el `orderId` devuelto al crear la orden. El contenido de `PAYLOAD` debe coincidir byte por byte con el cuerpo JSON de la solicitud HTTP (sin espacios adicionales ni salto de línea final):

```bash
PAYLOAD='{"orderId":"REPLACE_WITH_ORDER_UUID","status":"SUCCEEDED","providerReference":"smoke-provider-0001"}'
printf '%s' "$PAYLOAD" | openssl dgst -sha256 -hmac "$PAYMENT_WEBHOOK_SECRET"
```

Copia únicamente el valor hexadecimal que aparece después de `(stdin)=` y úsalo como `@webhookSignature`. Si cambias cualquier carácter, espacio, orden de propiedades o salto de línea del cuerpo, vuelve a generar la firma sobre el cuerpo exacto que enviarás. La firma no es el secreto; no publiques `PAYMENT_WEBHOOK_SECRET`.

## Alcance de verificación

Los comandos anteriores describen el flujo previsto por los archivos de configuración y build del repositorio. **Los pasos con Docker Compose no se verificaron en el entorno de desarrollo asistido**. Verifica el estado real de los contenedores y la respuesta de Actuator en tu equipo antes de considerar operativo el entorno local.
