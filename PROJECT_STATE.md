# TheosferaProxy — Current Project State

> Fuente de verdad de continuidad técnica y funcional de TheosferaProxy.
>
> Este archivo describe **el estado vigente**, no la historia completa del repositorio.
> La evidencia detallada de milestones cerrados vive en `docs/*CHECKPOINT.md` y documentos de diseño/runbook.
>
> Antes de proponer o implementar cambios, revisar en este orden:
>
> 1. `AGENTS.md`;
> 2. `CONTRIBUTING.md`;
> 3. este `PROJECT_STATE.md`;
> 4. el checkpoint del milestone activo;
> 5. el código y estado Git reales.
>
> Si un checkpoint histórico contradice el código fusionado o un checkpoint posterior, prevalece el estado más reciente.

---

## 1. Identidad del proyecto

- Proyecto: `TheosferaProxy`.
- Repositorio: `HarriOcho/TheosferaProxy`.
- Plataforma: Velocity `3.5.0-SNAPSHOT`.
- Java: 21.
- Build: Gradle Kotlin DSL mediante Gradle Wrapper.
- Package raíz: `com.theosfera.proxy`.
- Plugin ID: `theosferaproxy`.
- Versión actual: `0.1.0-SNAPSHOT`.
- Rol: proxy y coordinador global de la network Theosfera.

TheosferaProxy coordina infraestructura cross-server y estado global temporal. No debe absorber lógica específica de gameplay de Paper/Bukkit ni lógica interna de modalidades.

---

## 2. Estado Git autoritativo

### Baseline fusionada en `main`

El milestone fusionado más reciente antes de la rama activa es:

```text
main @ da0f0659482636b49f7450ace29f519944f6c92a
docs: record Increment E post-merge checkpoint (#73)
```

Ese estado incluye:

- Lobby Instance Switching (`#66`);
- hardening de raw Velocity `/server` (`#67`);
- Backend Control Channel TLS/HMAC (`#70`);
- health sobre Control Channel (`#71`);
- retiro de identidad backend transportada por jugadores (`#72`);
- checkpoint coordinado post-merge de Protocol/Core/Proxy (`#73`).

### Rama activa

```text
feature/distributed-backend-bootstrap
```

La rama parte exactamente de `main @ da0f065`.

El último HEAD funcional de A.1–A.8 antes del checkpoint documental es:

```text
f996cd7f3a1c8849abbd5e40a769bc64958e449b
```

Checkpoint del foundation:

```text
docs/DISTRIBUTED_BACKEND_BOOTSTRAP_FOUNDATION_CHECKPOINT.md
```

El foundation distribuido de bootstrap está implementado y validado automáticamente, pero **todavía no está fusionado a `main` y todavía no altera el flujo productivo de transferencias frías**.

---

## 3. Topología de desarrollo validada

Topología habitual de aceptación runtime:

```text
proxy-1
auth-1
lobby-1
lobby-2
skyblock-1
```

Ejemplo de policy de backends usada en runtime:

```properties
auth-1=AUTH,1,100
lobby-1=LOBBY,100,90
lobby-2=LOBBY,100,80
skyblock-1=SKYBLOCK,200,80
```

Formato:

```text
nombre=TIPO,capacidad,preferencia
```

Reglas:

- capacidad > 0;
- preferencia >= 0;
- nombre/tipo/capacidad/preferencia provienen de policy estática;
- la policy no demuestra identidad live, health ni capacidad disponible.

Tipos actuales:

- `AUTH`;
- `LOBBY`;
- `SKYBLOCK`.

La arquitectura debe seguir permitiendo nuevas modalidades sin introducir su lógica específica dentro del Proxy.

---

## 4. Principios arquitectónicos obligatorios

1. **Fail-closed por defecto.** Si no se puede demostrar ownership, identidad, health, capacidad o coordinación requerida, la operación no continúa.
2. **Una autoridad por responsabilidad.** No mantener dos fuentes productivas paralelas para el mismo ownership distribuido.
3. **Sin fallback local silencioso.** Redis indisponible no autoriza a degradar a reservas/ownership locales permisivos.
4. **Fencing explícito.** Incarnations, sesiones y operaciones distribuidas deben impedir que owners stale muten estado nuevo.
5. **Exact-match cleanup.** Release/remove solo elimina la generación exacta esperada.
6. **No bloquear el thread de Velocity con Redis/network I/O.** Las rutas runtime usan fronteras asíncronas.
7. **Redis es coordinación temporal, no persistencia durable de perfiles/progreso.**
8. **Control, health, capacity, orchestration y player state son conceptos separados.** Ninguno implica automáticamente al otro.
9. **Paper/Bukkit y Velocity permanecen separados.** No introducir dependencias de gameplay de backend en TheosferaProxy.
10. **No duplicar contratos de TheosferaProtocol.**
11. **Las superficies productivas de transferencia deben pasar por Theosfera.** Raw `/server` no es una ruta válida para jugadores.
12. **No reintroducir identidad o health backend mediante Plugin Messaging.**

Invariante conceptual central:

```text
TCP connected
    != TLS authenticated
    != backend identity authorized
    != backend HEALTHY
    != bootstrap ownership
    != capacity reserved
    != player ready
```

---

## 5. TheosferaProtocol v2 y superficies de comunicación

Protocol v2 es breaking respecto de v1 y requiere despliegue coordinado de Protocol/Core/Proxy compatibles.

Tipos registrados vigentes:

```text
CONTROL_AUTH_CHALLENGE
CONTROL_AUTH_RESPONSE
CONTROL_AUTH_RESULT
PLAYER_AUTHENTICATED
PLAYER_AUTHENTICATED_ACK
PLAYER_SERVER_READY
TRANSFER_REQUEST
TRANSFER_RESULT
PING
PONG
```

Retirados definitivamente:

```text
BACKEND_HELLO
BACKEND_HELLO_ACK
```

Separación vigente:

```text
Persistent TLS/HMAC Control Channel
├── backend authentication / live identity
└── PING / PONG health

Plugin Messaging
└── mensajes player-scoped
```

No volver a usar un jugador como carrier para identidad o health de backend.

---

## 6. Backend Control Channel

Estado productivo fusionado:

- listener TLS 1.3;
- autenticación challenge-response HMAC-SHA256;
- secretos por backend con validación fail-closed;
- sesión de control autenticada por backend;
- generation fencing por reconnect;
- replacement/cleanup exacto de sesiones;
- cero dependencia de jugadores conectados para identidad;
- health PING/PONG sobre la sesión autenticada actual;
- pérdida de la sesión current revoca inmediatamente autorización live;
- una desconexión de generación vieja no puede revocar una generación nueva.

Autoridad vigente de identidad backend:

```text
current authenticated TLS/HMAC control session
```

La static `BackendAuthorizationPolicy` sigue siendo autoridad de configuración del backend, pero no reemplaza la identidad live.

Checkpoint principal:

```text
docs/BACKEND_CONTROL_CHANNEL_INCREMENT_E_POST_MERGE.md
```

Runbook operacional:

```text
docs/BACKEND_CONTROL_CHANNEL_RUNBOOK.md
```

---

## 7. Backend health

Health ya no depende de Plugin Messaging.

Flujo vigente:

```text
Proxy
  → PING por current authenticated control session
  → backend
  → PONG por la misma frontera de control
  → correlación requestId/backend/generation
  → BackendHealthRegistry
```

Reglas:

- un PONG stale/replayed/unmatched no refresca health;
- una generación de control stale no refresca health;
- freshness vencida excluye el backend de routing jugable;
- pérdida de control elimina autorización live y evita que health viejo mantenga el backend elegible;
- zero-player health fue validado en runtime.

Destino jugable normal exige al menos:

```text
policy compatible
+ current live control identity
+ HEALTHY/fresh
+ capacidad distribuida disponible cuando corresponda reservar
```

---

## 8. Redis Coordination Runtime

Redis está conectado al runtime productivo. Las afirmaciones históricas de que Redis era solo un adapter aislado están superseded.

Componentes principales:

- `RedisCoordinationRuntime`;
- `VelocityRedisCoordinationBootstrap`;
- `ProxyMembershipCoordinator` / Redis implementation;
- `RedisPlayerSessionCoordinator`;
- `RedisPlayerPresenceCoordinator`;
- `RedisBackendOccupancyCoordinator`;
- `RedisBackendCapacityCoordinator`.

La conexión Lettuce se comparte; no crear clientes Redis independientes por subsistema sin una razón arquitectónica aprobada.

Estados globales de coordinación:

```text
STARTING
HEALTHY
DEGRADED
FENCED
STOPPING
```

La superficie operacional no se activa sin membership Redis válida.

Cuando coordinación pierde definitivamente autoridad:

- el Proxy entra en `FENCED`;
- nuevas operaciones autoritativas no continúan;
- las sesiones/jugadores que ya no pueden mantenerse con autoridad se cierran de forma controlada;
- no existe fallback local productivo.

Config runtime:

```text
plugins/theosferaproxy/redis-coordination.properties
```

Políticas temporales consolidadas:

```text
Proxy membership:        TTL 15 s / renew 5 s
Player session:          TTL 30 s / renew 10 s
Backend capacity lease:  reservation TTL 20 s
Bootstrap ownership:     TTL 60 s / renew 20 s   [rama activa]
```

---

## 9. Proxy membership

`ProxyInstanceIdentity` separa:

```text
proxyName      = identidad lógica estable configurada
incarnationId  = UUID efímero nuevo en cada arranque
```

Archivo:

```text
plugins/theosferaproxy/proxy-instance.properties
```

Ejemplo:

```properties
proxy-name=proxy-1
```

Membership Redis:

- adquisición atómica por `proxyName`;
- TTL;
- renew exact-match;
- release exact-match;
- fencing monotónico;
- dos incarnations del mismo `proxyName` no pueden ser owners válidos simultáneamente;
- corrupción Redis falla cerrada.

El `ProxyMembershipLease` exacto es utilizado como autoridad/fencing por operaciones distribuidas que dependen del owner Proxy.

---

## 10. Player sessions

`RedisPlayerSessionCoordinator` es la autoridad runtime distribuida de ownership de sesiones autenticadas.

Un `PlayerSessionLease` conserva:

- sesión autenticada;
- `proxyName`;
- `incarnationId`;
- fencing token.

El proceso mantiene mirrors/bindings locales únicamente para carreras y relación con objetos/conexiones Velocity exactos.

Protecciones vigentes:

- connection generations OLD/NEW;
- binding exacto a `Player`/conexión;
- requestId/attemptId;
- replay y deduplicación;
- fencing floors locales;
- timeouts;
- quarantine/release lifecycle;
- callbacks tardíos no pueden mutar un lease nuevo;
- stale owner no puede renovar ni liberar el lease vigente.

Renovación:

```text
RENEWED
  → nuevo deadline local

COORDINATION_UNAVAILABLE
  → DEGRADED temporal solo hasta el último deadline confirmado

NOT_FOUND / NOT_OWNER / CONFLICT
  → pérdida terminal de autoridad
```

---

## 11. Player presence

Redis es la frontera distribuida de presencia; el registro local se conserva como mirror operativo para callbacks y estado local de Velocity.

Flujo de llegada:

```text
PLAYER_SERVER_READY
  → validar conexión/origen
  → PlayerSessionLease exacto
  → publish Redis presence fenced
  → mirror local
```

Salida:

```text
disconnect / shutdown
  → remove presence if owned
  → release session lease
  → TTL Redis como fallback de limpieza
```

La presencia mantiene también un índice global por backend usado por capacidad distribuida.

Checkpoint final:

```text
docs/PLAYER_PRESENCE_RUNTIME_CHECKPOINT.md
```

---

## 12. Backend occupancy y capacity Redis

La capacidad global ya es productiva.

Autoridades:

- ocupación conectada: índice Redis derivado de presencia fenced;
- reservas in-flight: `BackendCapacityCoordinator` Redis;
- policy de capacidad máxima: `BackendAuthorizationPolicy`.

Cada reserva exige el `PlayerSessionLease` exacto.

La decisión atómica Redis incluye:

```text
validar session ownership/fencing
→ prune occupancy expirado
→ prune reservations expiradas
→ occupied + reserved
→ comparar con capacity
→ crear/renovar reserva exacta si cabe
```

No se usa `SCAN` como mecanismo de conteo global de ocupación.

No existe una autoridad productiva local de capacidad como fallback.

Consumers productivos de capacity Redis:

- `TRANSFER_REQUEST`;
- `/hub`;
- `/lobby`;
- `/lobby switch` / `/hub switch`;
- backend kick failover.

Handoff exitoso:

```text
reserve capacity
→ ConnectionRequest
→ destination presence
→ exact release
```

La reservation TTL productiva quedó definida en 20 segundos para el flujo actual de backends ya disponibles.

**Ese TTL no debe usarse para cubrir un futuro startup real de procesos fríos.**

---

## 13. Routing y transferencia

`TransferTargetResolver` conserva la clasificación de candidatos y no conoce detalles Redis/Lua.

La asignación distribuida combina:

- BackendType solicitado;
- policy;
- live control identity;
- health/freshness;
- preference;
- occupancy global;
- capacity Redis;
- exclusions/retry.

Flujo productivo normal:

```text
request
→ validar sesión/origen
→ resolver candidatos
→ reservar capacidad Redis
→ registrar transferencia pendiente
→ Velocity ConnectionRequest
→ result correlacionado
→ PLAYER_SERVER_READY / presence
→ capacity handoff release
```

`TIMED_OUT` sigue siendo terminal.

`NO_CAPACITY` puede permitir probar otro candidato elegible según el consumer.

Errores de ownership/coordinación son fail-closed.

---

## 14. Auth → Lobby

El circuito Auth → Lobby está operativo y validado.

Secuencia conceptual:

```text
TheosferaAuth autentica
→ PLAYER_AUTHENTICATED
→ Proxy valida AUTH + current control authorization
→ adquirir PlayerSessionLease Redis
→ binding exacto
→ PLAYER_AUTHENTICATED_ACK
→ resolver Lobby
→ capacidad Redis
→ ConnectionRequest
→ PLAYER_SERVER_READY
→ presence/handoff
```

La desconexión de `auth-1` durante el cambio exitoso de backend es parte normal del lifecycle.

---

## 15. `/hub`, `/lobby` y Lobby Instance Switching

Superficies oficiales:

```text
/hub
/lobby
/hub switch
/lobby switch
```

`/hub` y `/lobby`:

- requieren sesión autenticada;
- resuelven LOBBY mediante la ruta distribuida;
- capacity Redis;
- retries compatibles;
- fail-closed si no existe Lobby elegible.

`switch`:

- solo desde un backend autorizado de tipo `LOBBY`;
- excluye el Lobby actual desde el primer intento;
- no permite seleccionar nombres físicos de backend;
- no se degrada a `/lobby` normal cuando se ejecuta desde un backend no-Lobby;
- si no existe otra instancia elegible, falla de forma controlada sin self-reconnect.

Runtime validado bidireccionalmente entre `lobby-1` y `lobby-2`.

Checkpoint:

```text
docs/LOBBY_INSTANCE_SWITCHING_RUNTIME_CHECKPOINT.md
```

---

## 16. Raw Velocity `/server`

`/server` no es una superficie válida de transferencia para jugadores.

Hardening vigente:

- desregistro del alias raw `server`;
- guard sobre `CommandExecuteEvent`;
- case-insensitive;
- whitespace/rewrite resistant;
- sin bypass especial para staff;
- fuentes no-player quedan fuera de esta política de movimiento del jugador.

Rutas oficiales preservadas:

```text
/lobby
/hub
/lobby switch
/hub switch
/theosfera transfer ...
```

Checkpoint:

```text
docs/RAW_SERVER_COMMAND_HARDENING_RUNTIME_CHECKPOINT.md
```

---

## 17. Backend kick failover

El failover ante kicks usa capacidad Redis y permanece estrictamente fail-closed.

Reglas vigentes:

- solo destinos `RESOLVED`;
- `BOOTSTRAP_REQUIRED` nunca es válido para kick failover;
- un kick no arranca ni reserva un backend frío;
- primero intenta el mismo `BackendType` cuando existe alternativa segura;
- un backend no-Lobby puede degradar una sola vez hacia LOBBY activo cuando corresponde;
- destino exige live control identity + `HEALTHY` + capacidad Redis;
- source classification puede usar static policy si el control del backend origen ya fue revocado por la misma falla;
- esa excepción de clasificación del **origen** no conserva identidad stale ni debilita los requisitos del destino.

Runtime validado:

```text
LOBBY -> LOBBY
SKYBLOCK -> LOBBY
Redis reservation -> destination presence -> exact release
cold/unresolved destination -> rejected
```

Checkpoint:

```text
docs/REDIS_KICK_FAILOVER_RUNTIME_CHECKPOINT.md
```

---

## 18. Observabilidad administrativa

Comando:

```text
/theosferaproxy status
```

Permiso:

```text
theosferaproxy.admin
```

La vista es administrativa, read-only y de mejor esfuerzo. No es fuente de autoridad para routing.

Muestra información operacional de backends autorizados, incluyendo identidad/control live, health, jugadores/carga/preferencia y señales relevantes del estado local/distribuido compuesto.

La UI usa el estándar visual oficial de Theosfera.

Documento:

```text
docs/THEOSFERA_VISUAL_MESSAGING_STANDARD.md
```

---

## 19. Distributed Backend Bootstrap — foundation de la rama activa

### Objetivo

Resolver ownership distribuido exclusivo para coordinar el arranque futuro de un backend frío.

Invariante:

```text
bootstrap lease
    != backend process running
    != backend control identity
    != backend health
    != backend capacity reservation
```

El foundation A.1–A.8 está implementado en `feature/distributed-backend-bootstrap`.

### Contratos públicos

- `BackendBootstrapAcquireRequest`;
- `BackendBootstrapLease`;
- `BackendBootstrapAcquireResult`;
- `BackendBootstrapRenewResult`;
- `BackendBootstrapReleaseResult`;
- `BackendBootstrapCoordinator`;
- `BackendBootstrapLeasePolicy`;
- `BackendBootstrapOwnershipLifecycle`;
- `BackendBootstrapOwnershipLifecycleFactory`.

Acquire liga la operación a:

```text
targetBackendName
requestId
playerId
exact ProxyMembershipLease
```

El lease añade un fencing token propio de bootstrap.

### Doble fencing

```text
membership fencing
  → demuestra qué incarnation posee proxyName

bootstrap fencing
  → ordena generaciones de bootstrap sobre el backend objetivo
```

Ambos deben permanecer separados.

### Redis keyspace

```text
theosfera:coordination:backend-bootstrap:lease:<backend>
theosfera:coordination:backend-bootstrap:request:<requestId>
theosfera:coordination:backend-bootstrap:fencing
```

### Semántica atómica

`ACQUIRE`:

```text
validate current exact ProxyMembershipLease
→ requestId exclusivity
→ backend target exclusivity
→ INCR bootstrap fencing
→ target lease + request index con mismo TTL
```

`RENEW`:

```text
validate current membership
→ exact backend/request/player/owner/membership fencing/bootstrap fencing
→ refresh target + request TTL atomically
```

`RELEASE`:

```text
validate current membership
→ exact-match completo
→ delete target + request index atomically
```

Corrupción/cross-index mismatch falla cerrada; no se interpreta como lease libre.

### Policy temporal

```text
TTL:   60 s
renew: 20 s
```

El TTL es una ventana de recuperación de ownership, **no un startup timeout máximo**.

### Ownership lifecycle

```text
NEW
→ ACQUIRING
→ OWNED
→ DEGRADED
→ FENCED
```

Cierre intencional:

```text
OWNED / DEGRADED / ACQUIRING
→ STOPPING
→ exact release si fue adquirido
→ STOPPED
```

Garantías de carrera:

- stop durante acquire pendiente libera un lease concedido tarde;
- late renew no resucita una operación detenida;
- ownership explícitamente perdido fences inmediatamente;
- coordinación temporalmente unavailable solo conserva autoridad hasta el último deadline confirmado;
- `termination()` permite que el futuro orchestrator reaccione a `FENCED` o `STOPPED`.

### Velocity composition

- `VelocityBackendBootstrapRenewalScheduler`;
- factory expuesta desde `VelocityRedisCoordinationBootstrap`;
- nueva operación solo puede comenzar localmente con membership `HEALTHY` y lease actual;
- Redis vuelve a validar membership autoritativamente.

### Tests

Cobertura incluye:

- unit tests de contratos/coordinator/lifecycle/factory/scheduler;
- Redis Lua integration mediante Testcontainers `redis:7.4.2-alpine`;
- single owner cross-proxy;
- idempotencia exacta;
- requestId conflict;
- stale membership;
- bootstrap fencing forged/stale;
- expiración y reacquire con fencing mayor;
- races de acquire/renew/stop.

Checkpoint:

```text
docs/DISTRIBUTED_BACKEND_BOOTSTRAP_FOUNDATION_CHECKPOINT.md
```

---

## 20. Lo que Distributed Backend Bootstrap todavía NO hace

La rama activa **no**:

- arranca procesos Java;
- llama Docker/Kubernetes/systemd/Pterodactyl u otro provider;
- demuestra que un puerto está abierto;
- autentica el backend;
- marca health por estado de proceso;
- modifica Plugin Messaging;
- reemplaza health del Control Channel;
- reserva capacidad durante el startup futuro;
- modifica todavía `DistributedPlayerTransferRetryCoordinator` para usar el nuevo ownership lifecycle;
- elimina todavía el `BackendBootstrapRegistry` local histórico usado por el flujo cold actual.

Realidad productiva actual del cold path:

- `TransferTargetResolver` puede clasificar un destino como `BOOTSTRAP_REQUIRED`;
- el flujo legacy puede registrar bootstrap local e intentar la conexión hacia un backend registrado en Velocity;
- esto **no inicia un proceso remoto**;
- la capacidad actual fue diseñada para backends disponibles y no debe mantenerse durante un startup real largo.

Por tanto, el foundation distribuido recién creado es una frontera preparada, no una falsa prueba de readiness.

---

## 21. Siguiente milestone exacto — Backend Orchestration Provider

Después del PR/squash merge del foundation, el siguiente milestone técnico es diseñar e implementar la frontera que pueda solicitar el arranque real de una instancia.

Orden objetivo futuro:

```text
cold target candidate
→ acquire distributed bootstrap ownership
→ provider receives exact bootstrap fencing authority
→ request backend process start
→ renew bootstrap ownership while STARTING
→ wait current TLS/HMAC control authentication
→ wait fresh PONG / HEALTHY
→ re-resolve / revalidate target
→ reserve Redis capacity
→ Velocity ConnectionRequest
→ PLAYER_SERVER_READY
→ presence / handoff
→ exact bootstrap cleanup
```

Reglas no negociables:

1. process started != backend ready;
2. TCP port open != backend ready;
3. provider state != backend identity;
4. provider state != health;
5. un owner bootstrap `FENCED` debe dejar de emitir side effects autoritativos;
6. el provider debe recibir/propagar bootstrap fencing suficiente para rechazar órdenes stale;
7. capacity reservation se mueve **después** de readiness para true cold startup;
8. no mantener el TTL de capacity (~20 s) mientras un proceso tarda en iniciar;
9. no introducir fallback local silencioso si Redis/provider falla;
10. TheosferaCore no se convierte en cliente Redis para resolver este milestone.

No seleccionar todavía una tecnología concreta de orchestration sin diseñar primero el contrato/provider boundary.

---

## 22. Milestones cerrados relevantes

Resumen de continuidad; los detalles viven en checkpoints/commits.

```text
#36  Backend health checking
#37  Capacity-aware backend load balancing
#38  Alternate backend retry
#39  Runtime checkpoint load balancing
#40–42 Operational observability
#43–55 Distributed coordination boundary, membership y session runtime
#56–58 Redis player presence
#59–61 Redis backend capacity foundation + runtime rollout
#62  Redis backend capacity product wiring
#63  Redis capacity para /hub y /lobby
#64–65 Redis kick failover capacity + checkpoint
#66  Lobby Instance Switching
#67  Raw Velocity /server hardening
#70  Secure Backend Control Channel
#71  Health over authenticated Control Channel
#72  Retire player-carried backend identity
#73  Increment E post-merge checkpoint
```

El detalle histórico de PRs anteriores permanece disponible en Git y checkpoints, pero no debe volver a copiarse dentro de este archivo salvo que siga siendo necesario para entender el estado actual.

---

## 23. Runtime acceptance consolidada

Se ha validado, en distintos milestones, al menos:

- Auth → Lobby;
- `/theosfera transfer skyblock`;
- `/hub` y `/lobby`;
- Lobby switching `lobby-1 ↔ lobby-2`;
- alternate retry con un Lobby no disponible;
- capacidad global Redis multi-proxy sin overcommit;
- reservation → destination presence → exact release;
- kick failover `LOBBY → LOBBY`;
- kick failover `SKYBLOCK → LOBBY`;
- rechazo de cold target en kick failover;
- Redis outage sostenido → `HEALTHY → FENCED`;
- controlled player disconnect al perder autoridad;
- raw `/server` bloqueado;
- zero-player backend control authentication;
- zero-player health;
- control loss → autorización retirada;
- control reconnect con generation fencing;
- capacity keys sin residuos después de handoffs exitosos.

La rama de bootstrap distribuido añade validación automatizada/Redis integration del ownership, pero **todavía no tiene runtime acceptance de process startup porque ese proceso startup no existe aún**.

---

## 24. Configuración y secretos

Archivos runtime principales:

```text
plugins/theosferaproxy/backends.properties
plugins/theosferaproxy/proxy-instance.properties
plugins/theosferaproxy/redis-coordination.properties
```

El Control Channel posee configuración/provisioning propia documentada en su runbook.

Nunca versionar:

- passwords de keystore/truststore;
- secretos HMAC;
- credenciales Redis productivas;
- tokens de providers futuros.

---

## 25. Responsabilidades que NO pertenecen al Proxy

No implementar dentro de TheosferaProxy:

- lógica Paper/Bukkit de mundos, entidades o inventarios;
- mecánicas de Skyblock;
- SuperiorSkyblock2 integration;
- `/storage` o `/workbench` de Skyblock;
- menús de inventario de Lobby;
- progreso específico de modalidades;
- lógica de NPCs;
- UI específica del cliente;
- persistencia durable de perfiles/progreso como si Redis fuera base de datos primaria.

El Proxy sí puede coordinar contratos globales necesarios para social, matchmaking, acceso y lifecycle cross-server cuando esos sistemas sean planificados explícitamente.

---

## 26. Sistemas futuros previstos — todavía por planificar

Estas funciones forman parte de la visión de Theosfera, pero **su diseño interno no se considera aprobado por aparecer aquí**.

### Network Operations

- global Maintenance Mode;
- maintenance por modalidad;
- Backend Draining;
- backend lifecycle/start/stop;
- MOTD/status integration;
- herramientas administrativas de network;
- access policy/bypass de maintenance.

### Lobby / Discovery

- selección de modalidades;
- disponibilidad de modalidades;
- jugados recientemente;
- integración con NPC/UI de Lobby.

### Player / Progress

- perfiles;
- estadísticas generales y por modalidad;
- progreso general y por modalidad;
- logros;
- misiones;
- rangos;
- tienda;
- cosméticos.

### Social

- amigos;
- parties;
- escuadrones;
- invitaciones cross-server;
- presencia social;
- movimiento coordinado de grupos.

### Matchmaking / Sessions

- queues;
- matchmaking;
- pre-game;
- party-aware allocation;
- selección/arranque de instancias según demanda.

### Client

- futura coordinación necesaria con TheosferaClient, solo cuando un contrato global lo requiera.

Antes de implementar cualquiera de estos sistemas se debe definir:

- objetivo/relevancia;
- owner/plugin responsable;
- autoridad/source of truth;
- estado persistente vs temporal;
- coordinación cross-server;
- comandos/permisos/UI;
- fallos y política fail-open/fail-closed;
- dependencias;
- runtime acceptance.

No introducir Friends/Parties/Squads como lógica ad-hoc antes de definir persistencia y consistencia distribuida.

---

## 27. Orden arquitectónico de producto previsto

No es un calendario rígido, pero refleja dependencias actuales:

```text
Control / Protocol               ✅
Redis coordination               ✅
Sessions / Presence              ✅
Health / Capacity / Transfers    ✅
Lobby switching / hardening      ✅
Distributed Bootstrap Ownership  ✅ branch pending merge
Backend Orchestration Provider   ← next technical milestone
Operational State / Drain
Maintenance / Access policy
Lobby / Discovery
Player & Social systems
Matchmaking / Pre-game
Modalities / expansion
```

Maintenance no debe implementarse como un simple boolean local; cuando llegue su milestone deberá diseñarse como estado operacional distribuido y coordinarse con drain/orchestration.

---

## 28. Índice de documentos autoritativos

Estado actual / arquitectura:

```text
PROJECT_STATE.md
docs/DISTRIBUTED_COORDINATION_BOUNDARY.md
docs/REDIS_RUNTIME_CHECKPOINT.md
docs/PLAYER_PRESENCE_RUNTIME_CHECKPOINT.md
docs/REDIS_BACKEND_CAPACITY_DESIGN.md
docs/DISTRIBUTED_BACKEND_BOOTSTRAP_FOUNDATION_CHECKPOINT.md
```

Runtime / seguridad:

```text
docs/REDIS_LOBBY_TRANSFER_CAPACITY_CHECKPOINT.md
docs/REDIS_KICK_FAILOVER_RUNTIME_CHECKPOINT.md
docs/LOBBY_INSTANCE_SWITCHING_RUNTIME_CHECKPOINT.md
docs/RAW_SERVER_COMMAND_HARDENING_RUNTIME_CHECKPOINT.md
docs/BACKEND_CONTROL_CHANNEL_INCREMENT_E_POST_MERGE.md
```

Operación / estilo:

```text
docs/BACKEND_CONTROL_CHANNEL_RUNBOOK.md
docs/THEOSFERA_VISUAL_MESSAGING_STANDARD.md
```

Los checkpoints más antiguos permanecen como evidencia histórica, pero no son la fuente primaria para determinar el estado vigente si un milestone posterior los supersedió.

---

## 29. Regla para mantener este archivo limpio

A partir de esta consolidación:

- `PROJECT_STATE.md` debe describir el **presente**;
- un milestone cerrado puede actualizar su sección vigente, no añadir cientos de líneas históricas;
- evidencia extensa, comandos runtime, hashes, matrices y debugging pertenecen a un checkpoint específico bajo `docs/`;
- WIP temporal debe eliminarse o renombrarse al cerrar el milestone;
- deltas temporales de Project State deben eliminarse una vez incorporados aquí;
- no conservar puntos de reanudación que ya fueron completados;
- no repetir la misma arquitectura en cinco checkpoints distintos dentro de este archivo.

Objetivo práctico: cualquier nuevo chat/ingeniero debe poder leer este archivo y conocer el estado real sin tener que reconstruir cronológicamente todo el repositorio.

---

## 30. Punto exacto de reanudación

Estado inmediato:

```text
Distributed Backend Bootstrap Foundation A.1–A.8
→ implementación completa en feature/distributed-backend-bootstrap
→ tests + clean build verdes
→ checkpoint documental creado
→ pendiente PR / squash merge
```

Después del merge:

```text
Backend Orchestration Provider
```

No empezar Maintenance, Friends, Parties, Squads, Matchmaking u otro sistema de producto antes de cerrar la frontera de orchestration que necesita el bootstrap distribuido, salvo decisión explícita de repriorización.

El siguiente cambio técnico no debe reintroducir identidad/health por Plugin Messaging, capacity local, ownership local silencioso ni process readiness inferida desde un puerto abierto.
