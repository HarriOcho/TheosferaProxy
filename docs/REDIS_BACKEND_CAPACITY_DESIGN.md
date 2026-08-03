# Redis Backend Capacity — Design Checkpoint

## Objetivo

Eliminar la sobre-reserva de capacidad entre múltiples procesos Proxy sin degradar la política fail-closed ni acoplar `TransferTargetResolver` directamente a Redis.

## Problema actual

`BackendCapacityReservationRegistry` mantiene reservas en memoria local y `TransferTargetResolver` calcula carga con jugadores visibles por el proceso Proxy más reservas locales.

Con múltiples proxies, dos procesos pueden observar simultáneamente el mismo último slot disponible y reservarlo. Por tanto, distribuir únicamente el contador de reservas no es suficiente si la ocupación conectada continúa siendo local.

## Frontera de coordinación

`BackendCapacityCoordinator` será la frontera runtime para:

- reservar capacidad;
- liberar una reserva exacta;
- consultar reservas distribuidas por backend.

La operación `reserve(reservation, capacity)` no recibe `connectedPlayers` desde el caller. La implementación debe resolver la ocupación usando una fuente autoritativa compatible con su modo de coordinación.

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

## Ocupación global

La presencia Redis actual permite `find(playerId)`, pero todavía no ofrece una consulta o índice por backend. Por tanto, aún no existe una fuente eficiente y autoritativa para calcular `presentPlayers(backendName)` globalmente.

Antes del wiring productivo de capacidad distribuida se debe introducir una de estas capacidades equivalentes:

- índice Redis de presencia por backend mantenido atómicamente junto con la presencia por jugador; o
- agregado autoritativo equivalente capaz de devolver el conteo global por backend.

No se debe simular ocupación global sumando `RegisteredServer.getPlayersConnected().size()` de un único Proxy.

## Semántica Redis prevista

La reserva productiva deberá ejecutar una operación atómica equivalente a:

```text
validate exact request identity
prune/ignore expired reservations
read authoritative present-player count for backend
read live distributed reservations for backend
if present + reservations >= capacity -> NO_CAPACITY
else create reservation with TTL -> RESERVED
```

La implementación concreta puede usar Lua u otra primitiva Redis atómica, pero no una secuencia cliente `GET -> check -> SET`.

## Estados de resultado

- `RESERVED`;
- `ALREADY_RESERVED`;
- `REQUEST_ID_CONFLICT`;
- `NO_CAPACITY`;
- `OCCUPANCY_UNAVAILABLE`;
- `COORDINATION_UNAVAILABLE`.

Los dos últimos estados existen para mantener fail-closed cuando no se puede demostrar capacidad disponible.

## Scope de este incremento

Incluye:

- contrato `BackendCapacityCoordinator`;
- resultado distribuido explícito;
- definición de invariantes y de la futura ocupación global.

No incluye todavía:

- wiring de `TransferTargetResolver`;
- scripts Redis;
- índice de presencia por backend;
- migración de `BackendCapacityReservationRegistry`;
- transfer coordination;
- backend bootstrap coordination.

## Siguiente paso

Diseñar e implementar el índice/contador autoritativo de presencia por backend de forma compatible con las escrituras fenced ya existentes. Después, implementar `RedisBackendCapacityCoordinator` sobre esa fuente y validar atomicidad, TTL, idempotencia y fail-closed antes del wiring runtime.
