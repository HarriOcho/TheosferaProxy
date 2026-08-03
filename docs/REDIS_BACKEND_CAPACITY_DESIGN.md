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

La presencia Redis actual permite `find(playerId)`, pero todavía no ofrece una consulta o índice por backend. Cada presencia se almacena por jugador y los scripts actuales validan sesión, fencing token, secuencia y ownership antes de escribir o eliminar.

Para capacidad distribuida se introducirá un índice agregado por backend mantenido desde esos mismos caminos fenced.

El índice debe respetar estas transiciones:

```text
sin presencia -> backend A        : +1 en A
backend A -> backend A            :  0
backend A -> backend B            : -1 en A, +1 en B
backend A -> sin presencia        : -1 en A
stale / conflict / invalid owner  :  0
```

La renovación idempotente de presencia no debe cambiar el conteo.

No se utilizará `SCAN` ni una suma de `RegisteredServer.getPlayersConnected().size()` de un único Proxy como fuente productiva de ocupación global.

## Índice Redis previsto

La forma concreta de claves se decidirá en el adapter Redis, pero debe existir una entrada agregada por backend que pueda leerse en O(1) o costo equivalente acotado.

El mantenimiento del índice debe ocurrir en los scripts fenced de presencia o en una primitiva Redis igualmente atómica con la escritura por jugador. No es aceptable publicar presencia y después incrementar/decrementar el índice desde una segunda llamada cliente independiente.

El TTL de la presencia por jugador introduce una consideración adicional: si Redis elimina una presencia únicamente por expiración, el índice agregado no se decrementa automáticamente. Por ello, antes del wiring productivo se debe definir una estrategia consistente de reconciliación/expiración del índice, por ejemplo mediante membresía indexada con timestamps y pruning atómico, no mediante un contador eterno susceptible de drift.

## Semántica Redis prevista para reservas

La reserva productiva deberá ejecutar una operación atómica equivalente a:

```text
validate exact request identity
prune/ignore expired occupancy members and reservations
read authoritative present-player count for backend
read live distributed reservations for backend
if present + reservations >= capacity -> NO_CAPACITY
else create reservation with TTL -> RESERVED
```

La implementación concreta puede usar Lua u otra primitiva Redis atómica, pero no una secuencia cliente `GET -> check -> SET`.

## Estados de resultado de capacidad

- `RESERVED`;
- `ALREADY_RESERVED`;
- `REQUEST_ID_CONFLICT`;
- `NO_CAPACITY`;
- `OCCUPANCY_UNAVAILABLE`;
- `COORDINATION_UNAVAILABLE`.

Los dos últimos estados existen para mantener fail-closed cuando no se puede demostrar capacidad disponible.

## Scope alcanzado hasta ahora

Incluye:

- contrato `BackendCapacityCoordinator`;
- resultado distribuido explícito;
- contrato `BackendOccupancyCoordinator`;
- resultado de lectura agregada con fail-closed;
- definición de invariantes y semántica del futuro índice global;
- tests de invariantes de los resultados.

No incluye todavía:

- wiring de `TransferTargetResolver`;
- scripts Redis del índice;
- implementación Redis de `BackendOccupancyCoordinator`;
- implementación Redis de `BackendCapacityCoordinator`;
- migración de `BackendCapacityReservationRegistry`;
- transfer coordination;
- backend bootstrap coordination.

## Siguiente paso

Diseñar la representación Redis concreta del índice de presencia por backend con expiración segura y sin drift. Después, extender los scripts fenced de presencia para mantener ese índice atómicamente, implementar su lectura agregada y solo entonces construir `RedisBackendCapacityCoordinator` sobre esa fuente.
