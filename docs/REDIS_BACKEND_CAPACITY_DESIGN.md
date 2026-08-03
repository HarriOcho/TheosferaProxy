# Redis Backend Capacity — Final Checkpoint

## Estado del milestone

El foundation de capacidad distribuida para backends quedó completado y fusionado mediante el PR `#59` (`feat: add Redis backend capacity foundation`).

Estado post-merge:

```text
main @ 4080f0364f2821af7e5cad15b4e5e5a5edb64702
```

Este checkpoint documenta la frontera terminada antes de activar capacidad Redis dentro del flujo productivo de transferencias.

## Objetivo alcanzado

Se eliminó el vacío arquitectónico que permitiría sobre-reservar el mismo último slot desde múltiples procesos Proxy, sin acoplar `TransferTargetResolver` directamente a Redis y sin debilitar la política fail-closed.

El milestone introduce:

- ocupación global agregada por backend derivada de presencia Redis fenced;
- reservas de capacidad Redis atómicas;
- ownership exacto ligado al `PlayerSessionLease`;
- TTL y pruning temporal para recuperación ante crashes;
- factories runtime/bootstrap sobre la conexión Lettuce ya existente;
- una barrera explícita de rollout antes del wiring productivo.

## Frontera de coordinación de capacidad

`BackendCapacityCoordinator` es la frontera asíncrona para:

- reservar capacidad;
- liberar una reserva exacta;
- consultar reservas distribuidas por backend.

La operación `reserve(request, capacity)` recibe un `BackendCapacityReserveRequest` que une:

- `BackendCapacityReservation`;
- `PlayerSessionLease` exacto.

No recibe `connectedPlayers` desde el caller. La implementación distribuida no puede aceptar un conteo local como sustituto de ocupación global.

## Fencing de sesión

Una reserva distribuida no depende únicamente de `playerId` y `requestId`. Un Proxy stale que ya perdió el lease de sesión no puede crear ni renovar capacidad.

`BackendCapacityReserveRequest` exige coincidencia exacta entre:

```text
reservation.playerId()
sessionLease.session().playerId()
```

El Lua de reserva valida la sesión Redis antes de crear o renovar capacidad:

```text
session exists
session is a live hash
player-id matches
player-name matches
authenticated-at matches
proxy-name matches
incarnation-id matches
fencing-token matches
```

Resultados relevantes:

- `SESSION_NOT_FOUND`: no existe una sesión coordinada viva;
- `NOT_SESSION_OWNER`: la sesión existe pero pertenece a otro owner/incarnation/fencing;
- estado estructural Redis corrupto: error duro, sin overwrite silencioso.

La identidad almacenada de cada reserva incluye:

```text
request-id
player-id
backend-name
proxy-name
incarnation-id
session-fencing-token
```

`releaseIfOwned()` compara esa identidad exacta. Puede limpiar una reserva propia aunque la sesión ya haya terminado, pero un owner distinto no puede eliminarla.

## Frontera de ocupación

`BackendOccupancyCoordinator` separa la lectura agregada de ocupación de la lógica de reservas.

`BackendOccupancyReadResult` expone:

- `AVAILABLE`, con conteo no negativo;
- `BACKEND_NOT_FOUND`;
- `COORDINATION_UNAVAILABLE`.

Un backend configurado sin jugadores es `AVAILABLE(0)`. No se confunde ausencia de jugadores con imposibilidad de demostrar el estado global.

La implementación Redis de reserva lee el índice de ocupación directamente dentro del mismo Lua de reserva para conservar atomicidad. El coordinator de ocupación existe como frontera de lectura separada para consumidores que necesiten observar ese agregado sin conocer detalles Redis.

## Invariantes cerrados

1. Una reserva se identifica por `requestId`, `playerId`, `backendName` y ownership/fencing del `PlayerSessionLease`.
2. Repetir exactamente la misma reserva con el mismo lease es idempotente.
3. Reutilizar un `requestId` con otro payload o lease falla cerrado.
4. Un Proxy que perdió la sesión no puede crear ni renovar capacidad.
5. La decisión distribuida no usa el conteo local de jugadores de un solo Proxy como autoridad.
6. Ocupación, reservas vivas, comparación de capacidad y creación de reserva forman una sola decisión Redis atómica.
7. Un fallo Redis no degrada silenciosamente hacia autoridad local.
8. La liberación es exact-match.
9. Las reservas expiran mediante TTL.
10. El adapter Redis reutiliza la conexión Lettuce existente.
11. `TransferTargetResolver` no conoce claves, Lua ni comandos Redis.
12. La ocupación global no se calcula mediante `SCAN`.
13. El índice de ocupación se mantiene dentro de la misma decisión fenced que publica, mueve o elimina presencia.

## Índice global de presencia por backend

La presencia Redis continúa almacenándose por jugador, pero ahora mantiene adicionalmente un índice agregado por backend.

Cada backend utiliza un sorted set:

```text
theosfera:coordination:backend-presence:<backendName>
```

Representación:

- miembro: `playerId`;
- score: `expiresAt` absoluto en milisegundos calculado con `Redis TIME`.

Transiciones mantenidas dentro del Lua fenced de presencia:

```text
sin presencia -> backend A        : añadir/refrescar en A
backend A -> backend A            : refrescar expiración en A
backend A -> backend B            : retirar de A y añadir a B
backend A -> sin presencia        : retirar de A
stale / conflict / invalid owner  : no mutar índice
```

La renovación idempotente refresca el score, por lo que una presencia activa termina incorporándose al índice sin una migración mediante `SCAN`.

La lectura de ocupación ejecuta:

```text
Redis TIME
ZREMRANGEBYSCORE backend-index -inf nowMillis
ZCARD backend-index
```

Si el hash de presencia expira después de un crash, un miembro vencido puede permanecer temporalmente en el sorted set, pero la siguiente lectura lo poda antes de contar.

No se depende de keyspace notifications ni de un contador eterno susceptible a drift.

Un ZSET inexistente para un backend configurado representa ocupación `0`. `BACKEND_NOT_FOUND` se decide contra la configuración/política de backends, no por existencia de la clave Redis.

## Reserva Redis concreta

Cada reserva usa:

```text
theosfera:coordination:backend-capacity-reservation:<requestId>
theosfera:coordination:backend-capacity:<backendName>
```

La primera clave es un hash exacto con TTL. La segunda es un sorted set por backend con:

- miembro: `requestId`;
- score: `expiresAt`.

La reserva ejecuta en una sola operación Lua:

```text
validar PlayerSessionLease exacto contra Redis
Redis TIME
prune occupancy expirado
prune reservations expiradas
validar requestId existente e idempotencia
contar occupancy + reservations
comparar contra capacity
crear hash con TTL + ZADD reservation
```

No existe una secuencia insegura cliente:

```text
GET -> comprobar en Java -> SET
```

`reservedCount()` poda miembros vencidos antes de contar.

Si el hash de una reserva expira antes de que su miembro sea retirado del sorted set, el score vencido se elimina en la siguiente operación de reserva o conteo. No se convierte en capacidad fantasma permanente.

## Estados de reserva

`BackendCapacityReserveResult` contempla:

- `RESERVED`;
- `ALREADY_RESERVED`;
- `REQUEST_ID_CONFLICT`;
- `NO_CAPACITY`;
- `SESSION_NOT_FOUND`;
- `NOT_SESSION_OWNER`;
- `OCCUPANCY_UNAVAILABLE`;
- `COORDINATION_UNAVAILABLE`.

Los estados de ownership e indisponibilidad preservan fail-closed cuando no se puede demostrar autoridad o capacidad disponible.

## Fail-closed

`RedisBackendCapacityCoordinator` conserva estas reglas:

- fallo operacional al reservar -> `COORDINATION_UNAVAILABLE`;
- release no confirmado -> `false`;
- conteo no demostrable -> future excepcional, nunca `0` inventado;
- estado Redis estructuralmente corrupto -> error duro;
- TTL nulo, negativo o menor a un milisegundo -> configuración inválida.

No existe fallback automático hacia `BackendCapacityReservationRegistry` si Redis falla.

## Factories runtime sin activación productiva

`RedisCoordinationRuntime` y `VelocityRedisCoordinationBootstrap` exponen factories para:

- `RedisBackendOccupancyCoordinator`, recibiendo el conjunto explícito de backends configurados;
- `RedisBackendCapacityCoordinator`, recibiendo un `reservationTtl` explícito.

Ambos reutilizan la conexión Lettuce existente y requieren runtime Redis saludable.

No se añadió todavía una propiedad arbitraria de TTL a `redis-coordination.properties`. La política temporal productiva de reservas debe definirse explícitamente cuando se diseñe el wiring.

## Barrera de rollout

El índice agregado fue introducido después de que ya existían presencias Redis por jugador. Durante un rolling upgrade, un Proxy con código anterior podría renovar su presencia sin mantener el nuevo sorted set.

Por eso el PR `#59` no activa capacidad distribuida en `TransferTargetResolver`.

Secuencia segura aprobada:

```text
1. fusionar y desplegar el foundation
2. ejecutar el código nuevo en todos los proxies
3. permitir que renewals de presencia calienten/pueblen el índice
4. validar ocupación agregada multi-proxy
5. definir reservationTtl productivo
6. en un milestone posterior, migrar el resolver a BackendCapacityCoordinator
```

Hasta completar esa barrera:

- `BackendCapacityReservationRegistry` sigue siendo la ruta productiva local de transferencias;
- `TransferTargetResolver` no consume aún capacidad Redis;
- el coordinator Redis permanece disponible pero sin consumidores productivos de transferencia.

## Scope completado

Incluye:

- `BackendCapacityCoordinator`;
- `BackendCapacityReserveRequest`;
- `BackendCapacityReserveResult`;
- fencing contra el `PlayerSessionLease` exacto;
- `BackendOccupancyCoordinator` y lectura agregada fail-closed;
- índice Redis de presencia por backend;
- pruning temporal;
- mantenimiento atómico del índice desde publish/update/remove fenced de presencia;
- `RedisBackendOccupancyCoordinator`;
- `RedisBackendCapacityCoordinator`;
- store Lua atómico de reservas;
- keyspaces de ocupación y reservas;
- factories runtime/bootstrap;
- reutilización de la conexión Redis existente;
- tests de contratos, keyspaces, coordinators, ownership y fail-closed;
- estrategia de rollout por fases.

## Fuera de scope

Continúa fuera de este checkpoint:

- wiring productivo de `TransferTargetResolver`;
- migración del flujo productivo desde `BackendCapacityReservationRegistry`;
- política/configuración final de `reservationTtl`;
- validación runtime multi-proxy del índice ya calentado;
- distributed transfer coordination;
- distributed backend bootstrap coordination.

## Validación final

Antes de abrir el PR `#59` se ejecutó localmente:

```powershell
.\gradlew.bat test --no-daemon
.\gradlew.bat clean build --no-daemon
git diff main...HEAD --check
git status
```

Resultado:

- `BUILD SUCCESSFUL` en tests;
- `BUILD SUCCESSFUL` en clean build;
- `git diff main...HEAD --check` sin salida;
- working tree limpio y sincronizado.

GitHub Actions:

- workflow `Build`;
- run `#131`;
- resultado `success`.

PR final:

- `#59 feat: add Redis backend capacity foundation`;
- head funcional: `8b9784f58a75a016ba1033f2ba27c68067b01f40`;
- squash merge en `main`: `4080f0364f2821af7e5cad15b4e5e5a5edb64702`.

## Punto exacto de reanudación

El siguiente milestone **no** debe empezar conectando el resolver directamente a Redis.

Primero:

1. desplegar `main @ 4080f03` en todos los procesos Proxy que participen del runtime distribuido;
2. permitir que las renovaciones de presencia pueblen/calienten el índice global por backend;
3. validar en runtime multi-proxy que el conteo agregado refleja correctamente movimientos, renewals, disconnect y expiración;
4. confirmar que no existen proxies antiguos escribiendo presencia sin mantener el índice;
5. definir una política explícita y justificable para `reservationTtl`;
6. solo después diseñar el wiring productivo de `TransferTargetResolver` / allocation flow hacia `BackendCapacityCoordinator`.

Transfer coordination y backend bootstrap coordination permanecen como fronteras independientes posteriores.

No introducir parties, amigos o escuadrones dentro del siguiente incremento.