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

No se añadió todavía una propiedad arbitraria de TTL a `redis-coordination.properties`. La política temporal productiva inicial queda definida post-rollout como `20` segundos; la configuración productiva y el nombre de su property pertenecen al wiring siguiente.

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

La sección post-rollout siguiente registra la validación que completa esta
barrera.

## Runtime Rollout Validation

La barrera de rollout anterior quedo satisfecha despues de una validacion
runtime real multi-proxy. Esta seccion no reescribe el Final Checkpoint
historico del foundation; documenta la evidencia posterior que habilita el
siguiente milestone de wiring productivo.

Topologia validada:

- Redis Open Source `7.4.2` en `127.0.0.1:6379`;
- `proxy-1` en `127.0.0.1:25565`;
- `proxy-2` en `127.0.0.1:25564`;
- ambos proxies adquirieron membresias Redis independientes y renovables.

Presencia y ocupacion multi-proxy:

- dos jugadores distintos quedaron simultaneamente en `lobby-1` mediante
  proxies distintos;
- el indice global
  `theosfera:coordination:backend-presence:lobby-1` alcanzo `ZCARD = 2`;
- los hashes `player-presence` confirmaron ownership distinto:
  jugador A -> `proxy-1` y jugador B -> `proxy-2`;
- ambos scores del sorted set avanzaron despues de mas de un intervalo de
  renovacion, confirmando renewals activos desde ambos proxies;
- no aparecieron warnings de `PLAYER_SERVER_READY` no autorizado ni `PONG` no
  autorizado.

Durante el rollout se detecto un blocker real en TheosferaCore:
`BackendHandshakeService` mantenia autorizacion global por backend, por lo que
un carrier conectado mediante `proxy-2` podia heredar autorizacion obtenida
originalmente mediante `proxy-1`.

El prerequisite quedo corregido y fusionado:

- TheosferaCore PR `#17`;
- merge commit `bd29cfe`;
- `fix(network): scope backend handshake authorization by carrier (#17)`;
- despues del fix, cada Proxy registro independientemente `auth-1` y `lobby-1`
  mediante su propio carrier.

Movimiento y limpieza validados:

- occupancy inicial de `lobby-1`: `2`;
- el jugador de `proxy-2` se movio `lobby-1` -> `skyblock-1`;
- despues del movimiento:
  - `lobby-1` `ZCARD = 1`;
  - `skyblock-1` `ZCARD = 1`;
  - cada indice contenia exactamente al jugador esperado;
- clean disconnect del jugador de `proxy-2`:
  - `lobby-1` permanecio en `1`;
  - `skyblock-1` paso a `0`;
  - `player-presence` del jugador desconectado dejo de existir;
  - `player-session` del jugador desconectado dejo de existir.

Crash/pruning validado:

- antes del crash abrupto de `proxy-1`, `lobby-1` tenia raw `ZCARD = 1`, la
  player session existia y la membership de `proxy-1` tenia TTL positivo;
- despues del crash, la membership expiro y la player session expiro por TTL;
- raw `ZCARD` permanecio temporalmente en `1`, como se esperaba porque un ZSET
  no elimina miembros por score automaticamente;
- despues de superar el TTL, la lectura autoritativa equivalente ejecuto:

```text
Redis TIME
ZREMRANGEBYSCORE -inf nowMillis
ZCARD
```

Resultado:

- `1` miembro stale eliminado;
- occupancy resultante `0`;
- `ZCARD` posterior `0`;
- `ZRANGE` posterior vacio.

Conclusiones:

- un `ZCARD` crudo no es autoridad despues de crashes;
- la lectura con `Redis TIME` + pruning si produce occupancy autoritativa;
- solo participaron `proxy-1` y `proxy-2`;
- ambos ejecutaban el foundation moderno;
- no participo ningun Proxy legacy que escribiera presencia sin mantener el
  indice global;
- la barrera de rollout queda marcada como satisfecha.

## Politica inicial de reservationTtl

Decision productiva inicial:

```text
reservationTtl = 20 segundos
```

Justificacion:

- `PlayerTransferExecutor.DEFAULT_TIMEOUT` actual es `10` segundos;
- una reserva debe vivir mas que el intento de conexion que protege;
- `20` segundos da un margen de `10` segundos sobre el timeout maximo normal;
- `PlayerTransferRetryCoordinator` libera la reserva de capacidad del intento
  terminado antes de iniciar un retry alternativo;
- por tanto `reservationTtl` no necesita cubrir toda una cadena de retries:
  protege una reserva de un intento/backend concreto;
- el TTL es fallback de recuperacion ante crash, no sustituto del release
  exact-match;
- `20` segundos limita cuanto tiempo puede permanecer capacidad fantasma
  despues de una caida sin arriesgar expiracion durante el intento normal;
- Redis sigue siendo fail-closed;
- no existe fallback silencioso a `BackendCapacityReservationRegistry`;
- no se introduce todavia una propiedad de configuracion ni se inventa el
  nombre de esa property; eso pertenece al wiring productivo siguiente.

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
- configuración productiva de `reservationTtl` y el nombre de su property;
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

La barrera de rollout runtime ya fue satisfecha. El siguiente milestone es:

```text
Diseñar el wiring productivo de capacidad distribuida.
```

Restricciones:

- `TransferTargetResolver` no debe conocer Redis, claves Redis, Lua ni Lettuce;
- `BackendCapacityCoordinator` es asíncrono; no forzar llamadas bloqueantes para
  encajarlo artificialmente en el resolver síncrono actual;
- separar selección/candidatos de allocation/reservation si es necesario;
- una reserva distribuida debe recibir el `PlayerSessionLease` exacto;
- no usar connected player count local como autoridad;
- mapear explícitamente:
  - `RESERVED`;
  - `ALREADY_RESERVED`;
  - `REQUEST_ID_CONFLICT`;
  - `NO_CAPACITY`;
  - `SESSION_NOT_FOUND`;
  - `NOT_SESSION_OWNER`;
  - `OCCUPANCY_UNAVAILABLE`;
  - `COORDINATION_UNAVAILABLE`;
- Redis/coordination unavailable debe fallar cerrado;
- jamás fallback automático a `BackendCapacityReservationRegistry` en modo
  distribuido;
- preservar release exact-match en success, failure, rejection, timeout, retry,
  disconnect/lifecycle apropiado;
- distributed transfer coordination y distributed backend bootstrap continúan
  siendo fronteras posteriores e independientes;
- no introducir parties, friends ni squads.
