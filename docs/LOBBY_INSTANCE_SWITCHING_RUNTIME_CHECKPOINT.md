# Lobby Instance Switching Runtime Checkpoint

## Estado

Checkpoint final del milestone `Lobby Instance Switching` sobre la rama:

```text
feature/lobby-instance-switching
```

Base confirmada antes de documentar:

```text
main @ bdc484602ac90cbd4de4e9bddeb60ac474455b6a
```

HEAD funcional validado antes de este checkpoint:

```text
1263cf8553e5b7a66dd2c8632e50367a0a6f6561
test: cover bidirectional distributed lobby switching
```

Comparación confirmada antes de crear este documento:

```text
13 commits ahead de main
0 commits behind de main
```

No existía PR abierto para esta rama al iniciar el cierre documental.

---

## Objetivo del milestone

Añadir una superficie oficial para que un jugador ya ubicado en un Lobby pueda
solicitar otra instancia sin seleccionar un backend físico por nombre y sin
saltar routing, health, preferencia, capacidad Redis, fencing ni ownership.

Superficie aprobada e implementada:

```text
/lobby switch
/hub switch
```

`/hub` y `/lobby` comparten la misma implementación; `switch` es equivalente en
ambos comandos.

---

## Semántica final implementada

- `switch` solo se permite cuando la conexión actual corresponde a una identidad
  backend autorizada y registrada de tipo `LOBBY`;
- el Lobby actual se excluye desde el primer intento;
- el jugador nunca selecciona `lobby-1`, `lobby-2` ni otro nombre físico;
- la selección reutiliza la ruta distribuida existente de candidatos,
  preferencia y capacidad Redis;
- se reutiliza `DistributedPlayerTransferRetryCoordinator`;
- las exclusiones iniciales se copian defensivamente y persisten durante retries;
- cada reserva usa el `PlayerSessionLease` exacto;
- se preservan `proxyName`, `incarnationId` y fencing;
- la secuencia sigue siendo reservation -> connection -> destination presence ->
  handoff -> exact release;
- `NO_CAPACITY` puede excluir el candidato fallido y probar otro candidato
  elegible;
- `TIMED_OUT` permanece terminal;
- Redis indisponible falla cerrado;
- no existe fallback silencioso a conteos o reservas locales;
- si no existe otra instancia elegible, la operación termina de forma
  controlada y nunca reconecta al Lobby actual;
- desde un backend no-Lobby, `/lobby switch` no se degrada a `/lobby`: se
  rechaza y se indica al jugador que use `/lobby` para regresar.

Mensajes visibles principales:

```text
Debes estar en el Lobby para cambiar de instancia. Usa /lobby para regresar al Lobby.
No hay otro Lobby disponible en este momento. Inténtalo de nuevo en unos segundos.
Has cambiado a otro Lobby.
No pudimos cambiarte de Lobby. Inténtalo de nuevo.
El cambio de Lobby está tardando demasiado. Inténtalo de nuevo.
```

No se exponen nombres físicos de backend, Redis, leases, fencing ni detalles de
infraestructura en la UX ordinaria del jugador.

---

## Cambios de implementación

### `DistributedPlayerTransferRetryCoordinator`

Se añadió una sobrecarga:

```java
start(request, initialExcludedServerNames)
```

La sobrecarga histórica `start(request)` continúa delegando con `Set.of()`.
Las exclusiones iniciales se validan, se copian defensivamente y permanecen
activas durante toda la cadena de retries.

### `LobbyTransferService`

Se añadió `BackendIdentityRegistry` como dependencia productiva y el método:

```java
switchLobbyInstance(Player player)
```

El flujo:

1. exige sesión autenticada;
2. obtiene el backend actual desde Velocity;
3. verifica que ese backend posea identidad autorizada de tipo `LOBBY`;
4. crea un `TransferRetryRequest` en modo `SWITCH_INSTANCE`;
5. inicia el retry distribuido con el backend actual como exclusión inicial.

La ruta histórica `transferToLobby(Player)` conserva su comportamiento de
retorno normal al Lobby.

### `LobbyCommand`

Semántica final:

```text
/hub
/lobby
    -> retorno normal al Lobby

/hub switch
/lobby switch
    -> cambio voluntario a otra instancia Lobby
```

Argumentos inválidos o nombres físicos de backend no ejecutan transferencias.

### Composition root

`TheosferaProxy` entrega el `BackendIdentityRegistry` existente a
`LobbyTransferService`; no se creó un registro ni subsistema paralelo.

---

## Cobertura automatizada añadida

Se añadieron o ampliaron pruebas para:

- exclusiones iniciales del retry distribuido;
- conservación defensiva de exclusiones;
- parsing de `/lobby switch` y `/hub switch`;
- rechazo de argumentos/nombres físicos;
- rechazo desde backend no-Lobby;
- switch service y mensajes por modo;
- ruta distribuida integrada `lobby-1 -> lobby-2`;
- ruta distribuida integrada `lobby-2 -> lobby-1`;
- único Lobby elegible;
- alternativa sin capacidad;
- fallo de lectura/capacidad distribuida sin fallback local;
- carrera atómica donde un candidato parece tener una plaza pero Redis devuelve
  `NO_CAPACITY` al reservar.

Durante la implementación se corrigieron dos defectos de fixtures de prueba,
no de producción:

1. un `UnfinishedStubbingException` por stubbing Mockito anidado;
2. un caso de `NO_CAPACITY` que originalmente configuraba occupancy `100/100` y
   por diseño era descartado antes de `reserve()`. Se cambió a `99/100` para
   ejercitar la carrera autoritativa de reserva.

Gates pre-runtime confirmados sobre el HEAD funcional:

```powershell
.\gradlew.bat test --no-daemon
BUILD SUCCESSFUL in 21s

.\gradlew.bat build --no-daemon
BUILD SUCCESSFUL in 11s

git diff --check origin/main...HEAD
<sin salida>

git status
nothing to commit, working tree clean
```

---

## Artefacto runtime congelado

JAR exacto validado:

```text
TheosferaProxy-0.1.0-SNAPSHOT.jar
Size: 8,599,980 bytes
SHA-256: B1B673622F38CCDB1533A3EB213EBF202E72A4AEBB26FEA4A165DF93D72DFCCC
```

El hash del artefacto en `build/libs` coincidió exactamente con los dos JAR
desplegados:

```text
C:\Theosfera\Network\dev\proxy-1\plugins\TheosferaProxy-0.1.0-SNAPSHOT.jar
C:\Theosfera\Network\dev\proxy-2\plugins\TheosferaProxy-0.1.0-SNAPSHOT.jar
```

La ruta histórica `C:\Theosfera\Network\dev\proxy\...` no existe en esta
topología; la instancia correcta es `proxy-1`.

Cualquier cambio posterior de código invalida este freeze y exige reconstruir y
volver a registrar tamaño/hash antes de afirmar equivalencia del artefacto.

---

## Runtime PASS 1 - `lobby-1 -> /lobby switch -> lobby-2`

Con ambos Lobbies disponibles y saludables, HarriOcho ejecutó:

```text
/lobby switch
```

Resultado visible:

```text
Has cambiado a otro Lobby.
```

El jugador llegó a la otra instancia y la operación terminó sin residuos de
capacidad Redis.

Resultado:

```text
PASS
```

---

## Runtime PASS 2 - `lobby-2 -> /hub switch -> lobby-1`

La ruta inversa se ejecutó mediante el alias equivalente:

```text
/hub switch
```

El cambio de instancia volvió a completarse correctamente.

Resultado:

```text
PASS
```

---

## Runtime PASS 3 - backend no-Lobby

HarriOcho fue enviado a Skyblock mediante la superficie productiva:

```text
/theosfera transfer skyblock
```

Desde `skyblock-1` ejecutó:

```text
/lobby switch
```

Resultado visible:

```text
Debes estar en el Lobby para cambiar de instancia. Usa /lobby para regresar al Lobby.
```

No ocurrió transferencia.

Después ejecutó:

```text
/lobby
```

Resultado visible:

```text
Has llegado al Lobby.
```

Esto confirma que `switch` no se degrada accidentalmente al comportamiento de
retorno normal.

Resultado:

```text
PASS
```

---

## Runtime PASS 4 - único Lobby elegible

Para hacer el escenario determinista se retiró temporalmente `lobby-2` de la
política runtime de `proxy-1`, dejando a HarriOcho en `lobby-1`.

`/theosferaproxy status` confirmó `lobby-1` como `HEALTHY` y único Lobby de la
política del experimento.

Al ejecutar:

```text
/lobby switch
```

se observó:

```text
No hay otro Lobby disponible en este momento. Inténtalo de nuevo en unos segundos.
```

El jugador permaneció en `lobby-1`; no se produjo self-reconnect.

El scan:

```bash
redis-cli --scan --pattern "theosfera:coordination:backend-capacity:*"
```

quedó vacío.

Después se restauró `lobby-2` y se reinició `proxy-1`.

Resultado:

```text
PASS
```

---

## Runtime PASS 5 - destino alternativo lleno / no overcommit

Se configuró temporalmente:

```properties
lobby-2=LOBBY,1,80
```

GirlOcho ocupó `lobby-2`, dejando:

```text
capacity = 1
connected = 1
```

GirlOcho había podido cambiar de instancia cuando existía el cupo. Después,
HarriOcho intentó cambiar desde `lobby-1` y recibió:

```text
No hay otro Lobby disponible en este momento. Inténtalo de nuevo en unos segundos.
```

HarriOcho no entró a `lobby-2`; nunca se observó un estado `2/1`.

El scan de `backend-capacity:*` quedó vacío.

Esta prueba runtime valida el caso de destino ya lleno y la ausencia de
overcommit. La carrera más estricta donde la plaza desaparece entre lectura y
reserva se cubre mediante la prueba automatizada que deja occupancy aparente
`99/100` y hace que Redis responda `NO_CAPACITY` al reserve autoritativo.

Después se restauró:

```properties
lobby-2=LOBBY,100,80
```

Resultado:

```text
PASS
```

---

## Runtime PASS 6 - handoff y limpieza de capacidad

Después de los switches exitosos, y también después de los escenarios de fallo,
se ejecutó repetidamente:

```bash
redis-cli --scan --pattern "theosfera:coordination:backend-capacity:*"
```

Resultado:

```text
<sin salida>
```

Los switches exitosos completaron llegada/presencia en destino y no dejaron
reservas de capacidad residuales. El exact-match release y el lifecycle de
handoff están además cubiertos por la ruta distribuida y sus pruebas
automatizadas. En este milestone no se capturó un `MONITOR` Redis dedicado para
un switch, por lo que no se afirma una traza manual nueva de `DEL + ZREM`; esa
mecánica ya había sido validada en checkpoints anteriores de capacidad
productiva y kick failover.

Resultado:

```text
PASS
```

---

## Runtime PASS 7 - Redis indisponible / fail-closed

Topología inicial:

```text
HarriOcho -> lobby-1
GirlOcho  -> lobby-2
Redis     -> PONG
```

Redis fue detenido deliberadamente con:

```bash
redis-cli shutdown nosave
```

La comprobación inmediata devolvió:

```text
Could not connect to Redis at 127.0.0.1:6379: Connection refused
```

HarriOcho intentó cambiar de Lobby y no fue transferido. No se observó fallback
hacia conteos o reservas locales.

Lettuce inició sus intentos de reconexión y, al prolongarse la pérdida de la
capa distribuida, el Proxy registró:

```text
[theosferaproxy]: Estado de coordinacion distribuida: HEALTHY -> FENCED.
[theosferaproxy]: El Proxy fue fenced; se desconectaran 2 jugadores para evitar autoridad distribuida obsoleta.
```

Ambos jugadores fueron desconectados con:

```text
Este Proxy perdio su autoridad distribuida. Reconecta en unos momentos.
```

Durante el outage, los intentos de renovar/publicar presencia Redis fallaron de
forma explícita. La retirada de presencia en disconnect no pudo confirmarse y
el runtime registró que el TTL actuaría como fallback, sin fingir cleanup
exitoso.

Resultado:

```text
PASS - fail-closed
PASS - zero local fallback
PASS - HEALTHY -> FENCED en outage sostenido
PASS - disconnect al perder definitivamente autoridad
PASS - TTL como fallback cuando cleanup distribuido no puede confirmarse
```

---

## Recuperación final del laboratorio

Redis se restauró y confirmó:

```bash
redis-cli ping
PONG
```

`proxy-1` fue reiniciado después del fencing para adquirir una nueva
encarnación/autoridad limpia.

Estado final observado mediante `/theosferaproxy status`:

```text
lobby-1 -> HEALTHY, capacidad 100, conectado local 1
lobby-2 -> HEALTHY, capacidad 100, conectado local 1
```

El scan final:

```bash
redis-cli --scan --pattern "theosfera:coordination:backend-capacity:*"
```

terminó sin salida.

Configuración runtime restaurada:

```properties
auth-1=AUTH,1,100
lobby-1=LOBBY,100,90
lobby-2=LOBBY,100,80
skyblock-1=SKYBLOCK,200,80
```

La network quedó sin estado experimental de capacidad pendiente.

---

## Incidente externo observado durante shutdown

Durante un apagado de Velocity, nLogin produjo después de su propio mensaje de
shutdown:

```text
java.sql.SQLException: The database has been closed
```

seguido de un `NullPointerException` dentro de tareas internas de nLogin.

La secuencia muestra una tarea de nLogin ejecutándose después de que su SQLite
y otros recursos ya habían sido cerrados. TheosferaProxy únicamente registró
su transición normal:

```text
Estado de coordinacion distribuida: HEALTHY -> STOPPING.
```

No se observó evidencia de defecto de TheosferaProxy ni de corrupción Redis.
El incidente se clasifica como race externo de shutdown de nLogin y no bloquea
este milestone. Si apareciera durante operación normal, fuera del shutdown,
debería investigarse por separado.

---

## Matriz final del milestone

```text
lobby-1 -> /lobby switch -> lobby-2             PASS
lobby-2 -> /hub switch -> lobby-1               PASS
non-Lobby -> /lobby switch -> no transfer       PASS
/lobby normal desde non-Lobby                   PASS
único Lobby elegible -> fallo controlado        PASS
destino lleno -> no overcommit                  PASS
switch success -> presencia -> cero residuos    PASS
Redis unavailable -> no transfer                PASS
Redis unavailable -> no local fallback          PASS
outage sostenido -> HEALTHY -> FENCED            PASS
FENCED -> disconnect controlado                 PASS
recuperación -> PONG + restart + scan vacío     PASS
```

Con esta matriz, el milestone `Lobby Instance Switching` queda funcionalmente
cerrado.

---

## Próximo milestone aprobado

El siguiente hito permanece separado y ya estaba aprobado arquitectónicamente:

```text
Hardening de raw Velocity /server
```

Objetivo:

- eliminar `/server` como bypass de transferencias dentro de Theosfera;
- no conservar excepción especial para staff;
- obligar a que los cambios voluntarios de backend pasen por superficies de
  Theosfera que respeten policy, health, routing, capacity, ownership, fencing y
  coordinación distribuida;
- preservar `/lobby`, `/hub`, `/lobby switch`, `/hub switch` y
  `/theosfera transfer ...` como superficies controladas según su semántica.

Ese hardening no forma parte del código de este milestone y debe planificarse y
validarse en una rama posterior.

Distributed transfer coordination y distributed backend bootstrap coordination
continúan siendo fronteras independientes y no deben mezclarse con el hardening
de `/server` sin un milestone explícito.
