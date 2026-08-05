# Redis Kick Failover Runtime Checkpoint

## Estado

Checkpoint de continuidad para la rama `feature/redis-kick-failover-capacity`.

Base de la rama:

```text
main @ b65065e17a7c4c23f8ad175264e19f00043a9acd
feat: route lobby commands through distributed capacity (#63)
```

Head funcional previo a este checkpoint:

```text
0469c4117ee501f46810bdfdbe8f7df72ad5482a
docs: require clear player-facing messages
```

Comparación confirmada antes de crear este documento:

```text
42 commits ahead de main
0 commits behind de main
```

No existe PR abierto para este milestone todavía. La validación final continúa siendo manual/local + runtime y no debe confundirse con una validación de GitHub Actions asociada al head de esta rama.

---

## Objetivo del milestone

Migrar el failover ante kicks de backends desde la antigua autoridad local de capacidad hacia la capacidad distribuida Redis preservando estrictamente:

- destinos activos `RESOLVED` únicamente;
- nunca `BOOTSTRAP_REQUIRED`;
- nunca arrancar ni reservar un Lobby frío por un kick;
- `PlayerSessionLease` exacto;
- fencing;
- reserva distribuida atómica;
- release exact-match;
- handoff hasta presencia confirmada;
- política fail-closed;
- cero fallback local silencioso.

---

## Implementación cerrada

### Allocation `RESOLVED`-only

Se añadieron:

- `DistributedResolvedTargetAllocation`;
- `DistributedResolvedTargetAllocationService`.

Semántica:

- `AUTH` se rechaza antes de consultar resolver/carga/Redis;
- solo participan candidatos activos;
- un candidato frío nunca se devuelve como destino del kick;
- Redis es autoridad para occupancy + reservations;
- se exige el `PlayerSessionLease` exacto;
- `NO_CAPACITY` permite intentar otro candidato activo;
- fallos terminales y fallos de coordinación fallan cerrados;
- resultado `null`, stage `null`, excepción síncrona o asíncrona también fallan cerrados;
- una reserva devuelta debe coincidir exactamente con request, jugador y backend.

### Coordinator de kick distribuido

`DistributedBackendKickFailoverCoordinator` implementa:

- fuente `AUTH` -> disconnect;
- marcador pending local antes de iniciar Redis async;
- duplicados pending ignorados;
- primero intenta el mismo `BackendType`;
- si el mismo tipo queda agotado por disponibilidad o `NO_CAPACITY`:
  - fuente `LOBBY` -> disconnect;
  - fuente no Lobby -> intenta `LOBBY` una sola vez;
- fallos terminales de capacidad no degradan hacia Lobby:
  - `REQUEST_ID_CONFLICT`;
  - `SESSION_NOT_FOUND`;
  - `NOT_SESSION_OWNER`;
  - `OCCUPANCY_UNAVAILABLE`;
  - `COORDINATION_UNAVAILABLE`;
- no existe dependencia de bootstrap para kick failover.

### Lifecycle de reserva y handoff

- una reserva exitosa se conserva hasta confirmar presencia exacta en el backend destino;
- presencia exacta -> handoff -> release exact-match;
- backend real distinto del reservado -> release exacto sin registrar handoff;
- carreras de disconnect/attach ejecutan release exacto cuando es seguro;
- incertidumbre de ownership nunca provoca release inseguro; TTL queda como fallback;
- disconnect puede limpiar la reserva exacta;
- no existe fallback local.

### Listener async de Velocity

`BackendKickFailoverListener` usa `EventTask.resumeWhenComplete(...)`.

No usa `.join()` ni `.get()` para adaptar Redis/network async al event loop de Velocity.

El redirect usa el overload soportado por Velocity con mensaje personalizado.

### Limpieza de capacidad local

Se eliminaron las rutas productivas antiguas:

- `PlayerTransferTargetAllocationService`;
- `PlayerTransferRetryCoordinator`;
- `PlayerTransferTargetAllocation`;
- `BackendCapacityReservationResult`.

También se eliminaron:

- `TransferTargetResolver.reserveCapacity()`;
- `TransferTargetResolver.releaseCapacity()`;
- release local de capacidad desde `PlayerDisconnectListener`;
- métricas de reserva/carga local falsas en `/theosferaproxy status`.

`BackendCapacityReservationRegistry` permanece únicamente como shim vacío de compatibilidad de firmas antiguas; no contiene estado y no es autoridad productiva.

---

## Validación automatizada

Antes de la pasada UX final quedaron confirmados:

```text
targeted kick gate -> BUILD SUCCESSFUL
full test suite     -> BUILD SUCCESSFUL
git diff main...HEAD --check -> limpio
clean build         -> BUILD SUCCESSFUL
```

Después de la pasada UX de `/hub` y `/lobby`:

```text
.\gradlew.bat test --tests "*LobbyCommandTest" --tests "*LobbyTransferServiceTest" --no-daemon
BUILD SUCCESSFUL in 41s

.\gradlew.bat test --no-daemon
BUILD SUCCESSFUL in 17s

.\gradlew.bat clean build --no-daemon
BUILD SUCCESSFUL in 14s
```

---

## Artefacto TheosferaProxy desplegado

Artefacto vigente de esta validación runtime:

```text
TheosferaProxy-0.1.0-SNAPSHOT.jar
Size: 8,595,961 bytes
SHA-256: 2D8D07AE55D84534B7693848A47FF12813DF864E83945C3DAD2397C74EC3658C
```

Fue destinado a:

```text
C:\Theosfera\Network\dev\proxy\plugins\TheosferaProxy-0.1.0-SNAPSHOT.jar
C:\Theosfera\Network\dev\proxy-2\plugins\TheosferaProxy-0.1.0-SNAPSHOT.jar
```

Cualquier cambio posterior de código invalida este hash y exige reconstrucción + nuevo freeze.

---

## Dependencia runtime: TheosferaCore UX

Durante este milestone también se ajustó la mensajería visible del ecosistema.

Rama Core:

```text
style/player-facing-network-messages
```

Head vigente:

```text
d54ac6631515ecf0c9f9a65b2dffcb997cccffb2
style: remove message status prefixes
```

Artefacto Core vigente:

```text
TheosferaCore-0.1.0-SNAPSHOT.jar
Size: 533,422 bytes
SHA-256: F2BF6460463E3B58166857BC00E2895EDE85537D522C42CA830FF9780707DF60
```

Gates confirmados:

```text
NetworkTransferCommandHandlerTest -> BUILD SUCCESSFUL
full test suite                    -> BUILD SUCCESSFUL
clean build                        -> BUILD SUCCESSFUL
```

Se detectó y corrigió un problema de despliegue ajeno al código: coexistían `TheosferaCore.jar` y `TheosferaCore-0.1.0-SNAPSHOT.jar`, lo que produjo:

```text
Ambiguous plugin name 'TheosferaCore'
```

Se eliminó el JAR legacy duplicado y sus copias `.paper-remapped`; Paper regeneró una única copia válida.

---

## Estándar oficial de mensajería

Documento autoritativo:

```text
docs/THEOSFERA_VISUAL_MESSAGING_STANDARD.md
```

Paleta oficial:

```text
Oro principal     #E8B85B
Oro luminoso      #F8E798
Ámbar              #C46C19
Bronce             #8E5B29
Marfil cálido      #F2E4C5
Texto secundario  #B89A79
Marrón profundo   #3D1F10
Negro de fondo    #0B0503
```

Regla adicional cerrada:

- un mensaje ordinario al jugador debe explicar intención/estado en lenguaje humano;
- IDs internos, fencing, leases, enums, Redis, Plugin Messaging y detalles de infraestructura pertenecen a logs/debug o herramientas administrativas explícitas;
- no se usan colores arbitrarios fuera de la paleta oficial para mensajes generados por plugins Theosfera;
- razones externas de kick pueden preservarse sin recolorear cuando seguridad/semántica lo requieran.

UX validada en runtime:

```text
Enviándote a Skyblock...
Has llegado a tu destino.
Has llegado al Lobby.
Ya estás en el Lobby.
Redireccionando a Lobby-2...
```

El prefijo decorativo `- ` de `MessageService` fue eliminado.

---

## Runtime PASS 1: LOBBY -> LOBBY

Escenario:

```text
HarriOcho -> lobby-1
GirlOcho  -> lobby-2
ambos mediante proxy-1
lobby-1 HEALTHY
lobby-2 HEALTHY
```

Se detuvo `lobby-1`.

Evidencia Proxy:

```text
[connected player] HarriOcho (...): kicked from server lobby-1: Server closed
[server connection] HarriOcho -> lobby-1 has disconnected
[server connection] HarriOcho -> lobby-2 has connected
[theosferaproxy]: Jugador ... listo en lobby-2.
```

Redis mostró dos ciclos de prueba independientes con request IDs:

```text
e8ee3478-bd79-4fd3-838b-968520f0a4b4
50bae247-16f3-434a-ac33-f2dde857be03
```

Cada ciclo mostró:

```text
prune/read occupancy + reservations
-> reserve Lua
-> HSET reservation
-> PEXPIRE 20000
-> ZADD backend reservation index
-> redirect
-> destination presence
-> exact release Lua
-> DEL reservation
-> ZREM backend reservation index
```

No quedaron keys residuales `backend-capacity:*`.

Resultado:

```text
Runtime VALIDATED — Distributed Redis Kick Failover: LOBBY -> LOBBY
```

---

## Runtime PASS 2: SKYBLOCK -> LOBBY fallback

### Topología preparada

`lobby-1` quedó apagado accidentalmente antes de la prueba, lo que produjo un laboratorio determinista útil:

```text
HarriOcho -> skyblock-1
GirlOcho  -> lobby-2
lobby-1   -> no disponible
lobby-2   -> HEALTHY
skyblock-1 -> HEALTHY antes del stop
```

Se detuvo `skyblock-1` para producir un kick real.

### Evidencia Proxy

```text
[16:51:48 INFO]: [connected player] HarriOcho (...): kicked from server skyblock-1: Server closed
[16:51:48 INFO]: [server connection] HarriOcho -> skyblock-1 has disconnected
[16:51:48 INFO]: [server connection] HarriOcho -> lobby-2 has connected
[16:51:49 INFO] [theosferaproxy]: Jugador 5cf7b272-2369-4b4f-9945-3507231c3142 listo en lobby-2.
```

El cliente permaneció conectado y recibió:

```text
Redireccionando a Lobby-2...
```

### Reserva Redis exacta

Request ID del failover:

```text
dca446c3-c241-4098-b3df-e8be9b809966
```

Backend reservado:

```text
lobby-2
capacity = 100
reservationTtl = 20000 ms
```

Lease/session exacta validada por Redis:

```text
playerId       = 5cf7b272-2369-4b4f-9945-3507231c3142
playerName     = HarriOcho
proxyName      = proxy-1
incarnationId  = e71a97d6-1442-4cd1-9bad-8744f3b5b4ee
fencingToken   = 32
```

Secuencia observada:

```text
ZREMRANGEBYSCORE backend-capacity:backend:lobby-2
ZCARD backend-capacity:backend:lobby-2
EVAL reserve
EXISTS exact player-session
HMGET exact session ownership/fencing
ZREMRANGEBYSCORE occupancy + reservations
ZCARD occupancy
ZCARD reservations
HSET reservation:dca446c3...
PEXPIRE reservation:dca446c3... 20000
ZADD backend-capacity:backend:lobby-2 ... dca446c3...
```

Después de la conexión/presencia en `lobby-2`, el release exacto verificó:

```text
request-id
player-id
backend-name
proxy-name
incarnation-id
session-fencing-token
```

y ejecutó:

```text
DEL reservation:dca446c3...
ZREM backend-capacity:backend:lobby-2 dca446c3...
```

El reserve comenzó alrededor de `1785966708.484` y el release se observó alrededor de `1785966709.435`, por lo que la reserva se liberó por el happy-path de handoff en menos de un segundo, muy antes de depender del TTL de 20 segundos.

### Limpieza final

Comando:

```bash
redis-cli --scan --pattern "theosfera:coordination:backend-capacity:*"
```

Resultado:

```text
<sin salida>
```

Post-status observado:

```text
auth-1     -> STALE
lobby-1    -> STALE
lobby-2    -> HEALTHY, Authenticated Yes, connected local = 2
skyblock-1 -> STALE
```

Resultado:

```text
Runtime VALIDATED — Distributed Redis Kick Failover: SKYBLOCK -> LOBBY fallback
```

Esta prueba demuestra que un backend no-Lobby sin alternativa activa del mismo tipo puede degradar exactamente una vez hacia un Lobby activo y seguro, usando capacidad Redis, sin bootstrap frío y sin fallback local.

---

## Runtime PASS: no active alternate / cold refusal

También quedó validado previamente:

- `lobby-1` fue detenido;
- `lobby-2` estaba físicamente encendido pero no poseía identidad/health fresca en `proxy-1`;
- el kick path no trató ese backend frío/no autenticado como `RESOLVED`;
- no se intentó `BOOTSTRAP_REQUIRED`;
- el jugador fue desconectado fail-closed conservando la razón real del kick;
- no se esperaba tráfico `backend-capacity` porque no existió candidato activo que alcanzara allocation.

Resultado:

```text
PASS — cold/unresolved destination refused by kick failover
```

---

## Limitación runtime de health dependiente de carrier

La validación descubrió una limitación arquitectónica independiente de Redis capacity:

- `Velocity: Sí` solo prueba que el backend está configurado/registrado en Velocity;
- `Autenticado: Sí` requiere que este Proxy haya observado identidad válida del backend;
- el handshake/health actual depende de Plugin Messaging con un carrier jugador;
- un backend físicamente vivo pero vacío puede no quedar autenticado/HEALTHY en un Proxy que todavía no tuvo carrier hacia él;
- `TransferTargetResolver` exige identidad exacta + `HEALTHY`, por lo que ese backend no puede ser `RESOLVED`.

No debe solucionarse permitiendo `BOOTSTRAP_REQUIRED` en kick failover.

Follow-up futuro correcto: diseñar un mecanismo seguro de control/health desacoplado del carrier jugador que pruebe identidad Theosfera exacta y reachability por Proxy. Un TCP ping genérico no prueba identidad confiable.

---

## Incidente Netty observado

Durante una entrada casi simultánea de HarriOcho y GirlOcho se observó una única vez:

```text
GirlOcho -> ClientConfigSessionHandler
A packet did not decode successfully (invalid data)

HarriOcho -> ClientPlaySessionHandler
IllegalReferenceCountException: refCnt: 0, decrement: 1
```

Velocity runtime:

```text
3.5.0-SNAPSHOT (git-06eb052a-b609)
```

El incidente:

- ocurrió durante ingreso normal simultáneo, no durante el kick failover;
- no se reprodujo al repetir el escenario;
- no se atribuye a la paleta ni al redirect sin evidencia;
- queda como observación no bloqueante.

Si reaparece, habilitar diagnóstico de Velocity con:

```text
-Dvelocity.packet-decode-logging=true
```

y capturar el paquete/flujo exacto antes de modificar código.

---

## Runtime PASS adicional: outage sostenido de Redis y fencing global

Se ejecutó una prueba adicional con ambos Lobbies inicialmente activos, autenticados y `HEALTHY`:

```text
HarriOcho -> lobby-1
GirlOcho  -> lobby-2
Redis     -> PONG
backend-capacity:* -> vacío
```

Redis fue detenido deliberadamente. Lettuce comenzó a fallar al reconectar contra `127.0.0.1:6379`.

El Proxy mantuvo temporalmente a los jugadores conectados mientras todavía conservaba su ventana de autoridad. Posteriormente se observó explícitamente:

```text
[17:06:13 INFO] [theosferaproxy]: Estado de coordinacion distribuida: HEALTHY -> FENCED.
[17:06:13 ERROR] [theosferaproxy]: El Proxy fue fenced; se desconectaran 2 jugadores para evitar autoridad distribuida obsoleta.
```

Ambos jugadores fueron desconectados con el mensaje:

```text
Este Proxy perdio su autoridad distribuida. Reconecta en unos momentos.
```

Los backends confirmaron la salida de ambos jugadores a las `17:06:13`.

`lobby-1` no comenzó su apagado hasta las `17:06:28`, quince segundos después. Por tanto, esta prueba **no** demuestra directamente el flujo:

```text
backend kick
-> DistributedBackendKickFailoverCoordinator
-> COORDINATION_UNAVAILABLE
-> disconnect
```

El fencing global preemptó el kick real del backend.

Lo que sí queda validado directamente es:

```text
PASS  sustained Redis outage
PASS  HEALTHY -> FENCED
PASS  disconnect bajo pérdida definitiva de autoridad
PASS  cero fallback local silencioso
PASS  cleanup incierto conserva TTL como fallback
```

No se afirma observación runtime directa de un status terminal específico del kick coordinator cuando los logs no lo demuestran. La semántica terminal de `COORDINATION_UNAVAILABLE` permanece cubierta por pruebas automatizadas.

Durante el cleanup, Redis continuó indisponible. La retirada de presencia no pudo confirmarse y el runtime registró que TTL actuaría como fallback. Los intentos posteriores de liberar los leases de sesión agotaron el timeout Redis; no se fingió una liberación exitosa.

### Recuperación del laboratorio

Después de la prueba:

```text
redis-cli ping
PONG
```

El scan:

```bash
redis-cli --scan --pattern "theosfera:coordination:backend-capacity:*"
```

quedó sin salida.

Tras reiniciar el Proxy y recuperar el laboratorio:

```text
lobby-1 -> HEALTHY, Autenticado: Sí
lobby-2 -> HEALTHY, Autenticado: Sí
```

Redis volvió a estar saludable y no quedaron residuos de capacidad.

---

## Estado final del milestone

Validado en runtime:

```text
PASS  LOBBY -> LOBBY kick failover
PASS  SKYBLOCK -> LOBBY fallback kick failover
PASS  no active alternate -> fail-closed / no cold bootstrap
PASS  Redis reservation exact session/fencing
PASS  destination handoff releases exact reservation
PASS  zero residual backend-capacity keys
PASS  branded redirect message with official palette
PASS  no production local-capacity authority
PASS  sustained Redis outage -> global fencing fail-closed
```

Cobertura automatizada adicional confirma:

- `COORDINATION_UNAVAILABLE` es terminal y no degrada hacia Lobby;
- ausencia o pérdida de `PlayerSessionLease` falla cerrada;
- disconnect durante una reserva pendiente intenta release exacto;
- fallos excepcionales de allocation limpian el pending marker y desconectan;
- resultados corruptos, `null` o contract violations no se convierten en éxito.

Casos como stale/wrong-owner provocado deliberadamente, disconnect in-flight reproducido manualmente y concurrent multi-proxy kick race quedan como hardening adicional no bloqueante. La contención global multi-proxy de última plaza ya fue validada en el milestone de transferencias explícitas y no se repetirá artificialmente sin una razón concreta.

Con esta matriz, el milestone **Distributed Redis Kick Failover Capacity** se considera funcionalmente cerrado y listo para gates finales y PR.

---

## Siguiente hito después de cerrar este branch

Hardening aprobado y todavía no implementado:

```text
bloquear/eliminar el raw Velocity /server para todos, incluido staff
```

Razón:

`/server` puede saltarse routing, health, capacity, session ownership y fencing de Theosfera.

No mezclar ese hardening en esta rama.

Distributed transfer coordination y distributed backend bootstrap coordination permanecen fronteras separadas.

---

## Re-entry para un chat nuevo

Antes de continuar:

1. leer este documento;
2. revisar `docs/THEOSFERA_VISUAL_MESSAGING_STANDARD.md`;
3. confirmar rama `feature/redis-kick-failover-capacity`;
4. confirmar que local y origin están sincronizados;
5. ejecutar gates finales antes del PR;
6. no abrir PR sin autorización explícita;
7. después de fusionar este milestone, continuar con el hardening de `/server` en una rama separada.

Comando de contexto recomendado para un chat nuevo:

```text
Broer, continuemos TheosferaProxy desde docs/REDIS_KICK_FAILOVER_RUNTIME_CHECKPOINT.md. El milestone Distributed Redis Kick Failover Capacity quedó cerrado en runtime con LOBBY->LOBBY, SKYBLOCK->LOBBY, cold refusal, handoff/release exacto, cero residuos y fencing global ante outage sostenido de Redis. Continuemos con los gates finales/PR o con el siguiente hardening de /server :3
```
