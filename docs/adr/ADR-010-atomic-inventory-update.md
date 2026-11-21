# ADR-010: Descuento de inventario mediante UPDATE atómico condicional

- **Estado:** Aceptado
- **Fecha:** 2026-11-06
- **Fase:** 3

## Contexto

Varios compradores pueden intentar reservar las últimas entradas del mismo `ticket_type` simultáneamente. La operación debe impedir que `available_quantity` sea negativa o que dos transacciones vendan la misma unidad. Se evaluaron tres mecanismos de concurrencia en PostgreSQL/JPA.

## Opciones consideradas

| Estrategia | Ventajas | Desventajas |
| --- | --- | --- |
| **UPDATE atómico condicional** | Una sola sentencia comprueba y descuenta; la base de datos serializa la escritura de la fila; el número de filas afectadas indica éxito o falta de stock; evita la ventana de carrera entre lectura y escritura; reduce viajes a la base de datos. | La lógica de negocio queda expresada en SQL; requiere interpretar correctamente 0/1 filas afectadas; operaciones que deban leer varios datos relacionados pueden requerir consultas adicionales. |
| **Bloqueo pesimista** (`SELECT ... FOR UPDATE`) | Lectura y validación bajo bloqueo explícito; apropiado para invariantes que requieren inspeccionar y modificar varias filas dentro de una transacción; evita que otra transacción modifique la fila bloqueada. | Mantiene bloqueos durante la lógica de aplicación; aumenta espera y contención en picos; mayor riesgo de deadlocks si se bloquean filas en distinto orden; normalmente añade una lectura y exige transacciones cortas. |
| **Bloqueo optimista** (`@Version`) | No bloquea durante la lectura; funciona bien con baja contención; detecta escrituras concurrentes al actualizar y permite reintentar. | En inventario caliente puede producir muchos conflictos/reintentos; exige política de retry y manejo de conflictos; un contador de stock necesita igualmente una condición que preserve el límite inferior. |

## Decisión

Usar un **UPDATE condicional atómico** para reservar inventario:

```sql
UPDATE ticket_types
SET available_quantity = available_quantity - :quantity,
    version = version + 1
WHERE id = :id
  AND available_quantity >= :quantity;
```

Una fila actualizada significa reserva de stock exitosa; cero filas significa que el tipo de entrada no existe o que no hay unidades suficientes (la capa de servicio distingue/normaliza el resultado según el caso). La restauración de stock también es condicional para no superar `total_quantity`.

El test de integración [`ReservationConcurrencyIT`](../../ticketing-api/src/test/java/com/portfolio/ticketing/reservation/ReservationConcurrencyIT.java) valida el escenario de alta contención con PostgreSQL real.

## Consecuencias

- La condición de disponibilidad y el decremento forman una única operación indivisible a nivel de base de datos.
- La columna `version` se incrementa en la sentencia, pero la estrategia de inventario elegida no depende de un ciclo JPA de lectura-modificación con `@Version`.
- Se reserva el bloqueo pesimista explícito para operaciones donde la transición de una entidad existente requiere leer y validar su estado, como la confirmación de una reserva para pago.
- Los resultados bajo contención deben seguir verificándose con Testcontainers en CI/entorno Docker; un test que no se haya ejecutado no se considera evidencia de estabilidad.
