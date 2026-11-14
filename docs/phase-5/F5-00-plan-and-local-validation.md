# F5-00 — Plan de Fase 5 y primera validación local

- **Estado:** planificación exclusivamente documental. **Fase 5 no iniciada**.
- **Diseño propuesto:** [F5-01](F5-01-technical-design.md), [F5-02](F5-02-test-and-acceptance-plan.md), [ADR-023](../adr/ADR-023-phase-5-outbox-and-notification-delivery.md). Pendiente de aprobación del Tech Lead.
- **Requisito de entrada:** cierres expresos de Fase 3 y Fase 4 y autorización independiente del Tech Lead/responsable para implementar Fase 5.
- **Objetivo:** probar primero la aplicación actual en local; después incorporar Transactional Outbox, Kafka y notificaciones simuladas con MongoDB.

## Orden previsto

### 0. Prueba manual local de la plataforma actual (primera tarea de Fase 5)

Antes de editar mensajería: usar la guía existente [LOCAL_RUN](../LOCAL_RUN.md) con Java 21, Docker Compose v2 y Maven Wrapper. No ejecutar este paso ni modificar `docker-compose.yml` durante el cierre de Fase 4.

1. Preparar `.env` desde `.env.example`, generar secretos distintos JWT/HMAC y exportar variables al proceso Java; no versionar secretos.
2. Ejecutar `docker compose --profile infra up -d` y `docker compose ps`; comprobar `healthy` en PostgreSQL, Redis, Kafka y MongoDB.
3. Ejecutar `./mvnw -B clean verify` con Docker/Testcontainers disponible y registrar resultado y advertencias.
4. Arrancar la API mediante `./mvnw -pl ticketing-api -am package -DskipTests` y `java -jar ticketing-api/target/ticketing-api-0.1.0-SNAPSHOT.jar`.
5. Comprobar `http://localhost:8080/actuator/health`, `/swagger-ui.html` y el catálogo público sembrado por el perfil `dev`.
6. Probar el camino registro/login → reserva → orden → webhook HMAC de pago **simulado** → tickets, según [flow.http](../flow.http), ajustando importe/referencia a la orden real. Comprobar errores y un caso de repetición idempotente.
7. Registrar evidencia verificable de la prueba local y errores concretos. Corregir fallos detectados antes de desarrollar el siguiente incremento.

### 1. Publicación de eventos con Transactional Outbox

Reutilizar la tabla `outbox_events` existente; insertar eventos de negocio dentro de las mismas transacciones que los cambios correspondientes. Publicador programado entrega a Kafka y marca publicación; diseñar reintentos e idempotencia sin prometer exactly-once de extremo a extremo.

### 2. Kafka y servicio de notificaciones

Crear y documentar tópicos `ticketing.orders`, `ticketing.reservations` y `ticketing.events` con tres particiones/replicación 1 en desarrollo. Configurar consumidor de `notification-service`, ack manual, tres reintentos y DLT; registrar IDs procesados con restricción única en MongoDB. Notificación de correo **simulada** (log y documento), nunca email externo.

### 3. Prueba de integración funcional completa

Probar registro → orden → captura simulada → outbox → Kafka → consumidor → notificación en MongoDB mediante Testcontainers y Awaitility, además de duplicados y caída/reintento. Confirmar CI `clean verify` verde con SHA exacto. Probar localmente el recorrido completo con Compose antes de solicitar cierre de Fase 5.

## Alcance y exclusiones

- Fase 5 **solo**: mensajería y evidencia de flujo, con pruebas locales. El perfil Compose `full`, Dockerfiles multi-stage y Grafana corresponden principalmente a Fase 6.
- Ninguna integración financiera real, correo real, pagos de nube ni despliegue AWS.
- `main` permanece sin cambios; ningún recurso AWS se crea sin autorización expresa y evaluación de costo $0.

## Fuentes

- Especificación técnica Ticketing, secciones 12, 13, 15, 16 y 22.
- [Docker Compose: perfiles](https://docs.docker.com/compose/how-tos/profiles/).
- [Spring Kafka 3.3: contenedores y ack manual](https://docs.spring.io/spring-kafka/reference/3.3/kafka/receiving-messages/message-listener-container.html).
- [PostgreSQL: ON CONFLICT](https://www.postgresql.org/docs/17/sql-insert.html), para registros únicos de idempotencia.
