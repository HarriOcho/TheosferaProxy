# Redis Backend Capacity — Design Checkpoint

## Objetivo

Eliminar la sobre-reserva de capacidad entre múltiples procesos Proxy sin degradar la política fail-closed ni acoplar `TransferTargetResolver` directamente a Redis.

## Problema actual

`BackendCapacityReservationRegistry` mantiene reservas en memoria local y `TransferTargetResolver` calcula carga con jugadores visibles por el proceso Proxy más reservas locales.

Con múltiples proxies, dos procesos pueden observar simultáneamente el mismo último slot disponible y reservarlo. Por tanto, distribuir únicamente el contador de reservas no es suficiente si la ocupación conectada continúa siendo local.

## Frontera de coordinación de capacidad

`BackendCapacityCoordinator` será la frontera runtime para:

- reservar capacidad;
- liberar una reserva exacta;
- consultar reservas distribuidas por backend.

La operación `reserve(reservation, capacity)` no recibe `connectedPlayers` desde el caller. La implementación debe resolver la ocupación usando una fuente autoritativa compatible con su modo de coordinación.

## Frontera de ocupación

`BackendOccupancyCoordinator` separa la lectura agregada de ocupación de la lógica de reservas.

Su contrato devuelve `BackendOccupancyReadResult` con estados:

- `AVAILABLE`, incluyendo un conteo no negativo;
- `BACKEND_NOT_FOUND`;
- `COORDINATION_UNAVAILABLE`.

Un backend con cero jugadores sigue siendo una lectura `AVAILABLE(0)`. No se debe confundir ausencia de jugadores con imposibilidad de demostrar el estado global.

`BackendCapacityCoordinator` puede consumir esta frontera, pero el caller productivo no debe suministrar un conteo local como sustituto.

## Invariantes obligatorios

1. Una reserva se identifica exactamente por `requestId`, `playerId` y `backendName`.
2. Repetir exactamente la misma reserva es idempotente.
3. Reutilizar un `requestId` con otro payload falla cerrado.
4. La decisión de capacidad no puede basarse únicamente en el conteo local de jugadores de un Proxy cuando el runtime sea distribuido.
5. La comprobación de ocupación y la creación de la reserva deben formar una única decisión atómica en Redis.
6. Un fallo de Redis no debe degradar silenciosamente hacia una reserva local.
7. La liberación debe ser condicional sobre la identidad exacta esperada.
8. Las reservas distribuidas deben expirar mediante TTL para recuperar capacidad después de crash o pérdida de conexión.
9. El adapter Redis debe reutilizar la conexión Lettuce existente del runtime de coordinación.
10. El resolver de destinos no debe conocer detalles de claves, Lua ni comandos Redis.
11. La ocupación global no puede calcularse mediante `SCAN` de todas las claves de presencia por jugador.
12. El índice de ocupación debe mantenerse dentro de la misma decisión fenced que publica, mueve o elimina presencia, evitando doble conteo y pérdidas de conteo.

## Ocupación global

La presencia Redis actual permite `find(playerId)`, pero no ofrece una consulta por backend. Cada presencia se almacena por jugador y los scripts validan sesión, fencing token, secuencia y ownership antes de escribir o eliminar.

Para capacidad distribuida se introduce una lectura agregada separada mediante `BackendOccupancyCoordinator`. La implementación Redis utiliza un índice por backend y no escanea claves de jugadores.

El índice respeta estas transiciones:

```text
sin presencia -> backend A        : añadir/refrescar miembro en A
backend A -> backend A            : refrescar expiración en A
backend A -> backend B            : eliminar de A, añadir a B
backend A -> sin presencia        : eliminar de A
stale / conflict / invalid owner  : no mutar índice
```

## Representación Redis concreta

Cada backend tiene un sorted set:

```text
theosfera:coordination:backend-presence:<backendName>
```

- miembro: `playerId`;
- score: timestamp absoluto `expiresAt` en milisegundos calculado con `Redis TIME`;
- el score se refresca en publish/renew exitoso;
- un movimiento elimina el miembro del backend anterior y lo añade al nuevo dentro de la misma decisión Lua;
- un remove exitoso elimina el miembro del backend correspondiente.

La lectura de ocupación ejecuta atómicamente:

```text
Redis TIME
ZREMRANGEBYSCORE backend-index -inf nowMillis
ZCARD backend-index
```

Así, una presencia cuyo hash expire por TTL puede dejar temporalmente un miembro vencido, pero la siguiente lectura lo poda antes de contar. No se depende de keyspace notifications ni de un contador eterno susceptible a drift.

Un ZSET inexistente para un backend configurado representa `AVAILABLE(0)`. `BACKEND_NOT_FOUND` se resuelve contra la política/configuración de backends, no por existencia de la clave Redis.

## Reserva Redis concreta

Cada reserva mantiene:

```text
theosfera:coordination:backend-capacity-reservation:<requestId>
theosfera:coordination:backend-capacity:<backendName>
```

La primera clave es un hash con identidad exacta y TTL. La segunda es un sorted set por backend con `requestId` como miembro y `expiresAt` como score.

La reserva ejecuta en una sola operación Lua:

```text
Redis TIME
prune occupancy expirado
prune reservations expiradas
validar requestId existente e idempotencia
contar occupancy + reservations
comparar contra capacity
crear hash con TTL + ZADD reservation
```

El release valida `requestId`, `playerId` y `backendName` antes de borrar el hash y retirar el miembro del índice. `reservedCount()` poda miembros vencidos antes de contar.

`RedisBackendCapacityCoordinator` conserva fail-closed: fallos operativos al reservar producen `COORDINATION_UNAVAILABLE`; un release no confirmado devuelve `false`; un conteo no demostrable falla en lugar de devolver cero.

## Factories runtime sin activación productiva

`RedisCoordinationRuntime` y `VelocityRedisCoordinationBootstrap` exponen factories para:

- `RedisBackendOccupancyCoordinator`, recibiendo el conjunto explícito de backends configurados;
- `RedisBackendCapacityCoordinator`, recibiendo un `reservationTtl` explícito.

Ambos reutilizan la conexión Lettuce existente y requieren runtime Redis saludable.

No se añadió todavía una propiedad arbitraria de TTL a `redis-coordination.properties`. La política temporal de reservas debe definirse explícitamente cuando se diseñe el wiring productivo.

## Barrera de rollout

El índice de ocupación se introduce después de que ya existían presencias Redis por jugador. Durante un rolling upgrade, un Proxy con código antiguo puede seguir renovando su presencia sin mantener el nuevo sorted set por backend.

Por tanto, **no se debe activar capacidad distribuida en `TransferTargetResolver` en el mismo rollout que introduce el índice**.

Secuencia segura:

```text
1. desplegar/fusionar foundation de índice + scripts de presencia actualizados
2. permitir que todos los proxies ejecuten el código nuevo
3. dejar que las renovaciones de presencia refresquen/pueblen el índice global
4. validar ocupación agregada
5. en un milestone posterior, activar reservas distribuidas en el resolver
```

La renovación idempotente de presencia refresca el índice, por lo que las presencias activas terminan incorporándose sin una migración por `SCAN`.

Hasta completar esta barrera, `BackendCapacityReservationRegistry` continúa siendo la ruta productiva local y el nuevo coordinator Redis permanece sin consumidores de transferencia.

## Estados de resultado de capacidad

- `RESERVED`;
- `ALREADY_RESERVED`;
- `REQUEST_ID_CONFLICT`;
- `NO_CAPACITY`;
- `OCCUPANCY_UNAVAILABLE`;
- `COORDINATION_UNAVAILABLE`.

Los estados de indisponibilidad mantienen fail-closed cuando no se puede demostrar capacidad disponible.

## Scope alcanzado

Incluye:

- `BackendCapacityCoordinator` y resultado distribuido;
- `BackendOccupancyCoordinator` y lectura agregada fail-closed;
- índice Redis de presencia por backend con pruning temporal;
- mantenimiento atómico del índice desde publish/update/remove fenced de presencia;
- `RedisBackendOccupancyCoordinator`;
- `RedisBackendCapacityCoordinator` y store Lua atómico;
- keyspaces de ocupación y reservas;
- factories runtime/bootstrap que reutilizan la conexión Redis existente;
- tests de contratos, keyspaces, coordinators y fail-closed;
- estrategia de rollout por fases.

No incluye todavía:

- wiring de `TransferTargetResolver`;
- migración del flujo productivo desde `BackendCapacityReservationRegistry`;
- política/configuración final de `reservationTtl`;
- validación runtime multi-proxy del índice ya calentado;
- transfer coordination;
- backend bootstrap coordination.

## Siguiente paso

Ejecutar gates completos sobre este foundation y auditar el diff. Si queda limpio, fusionarlo antes del wiring productivo para permitir que el índice global de ocupación se caliente en todos los proxies. El milestone posterior podrá definir TTL, estrategia de activación y migración de `TransferTargetResolver` hacia `BackendCapacityCoordinator`.
