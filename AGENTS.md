# TheosferaProxy — Instrucciones para agentes

## Lectura obligatoria

Antes de proponer o implementar cambios:

1. leer este archivo;
2. leer `CONTRIBUTING.md`;
3. leer `PROJECT_STATE.md`;
4. revisar `docs/README.md` y el checkpoint del milestone activo;
5. verificar el código y estado Git reales.

No reconstruir el estado actual desde checkpoints antiguos si `PROJECT_STATE.md`, un checkpoint posterior o el código fusionado ya los supersedió.

## Identidad

TheosferaProxy es el plugin Velocity y coordinador global de la network Theosfera.

- Plataforma: Velocity 3.5.0-SNAPSHOT.
- Java: 21.
- Build: Gradle Kotlin DSL.
- Package raíz: `com.theosfera.proxy`.

TheosferaProxy coordina operaciones y estado temporal cross-server. No debe contener gameplay específico de Paper/Bukkit o de una modalidad.

## Estado arquitectónico vigente

No asumir que el proyecto sigue en fase inicial.

El runtime actual ya incluye:

- TheosferaProtocol v2;
- Backend Control Channel TLS/HMAC;
- identidad backend live desde control sessions autenticadas;
- PING/PONG health por Control Channel;
- Redis runtime;
- Proxy membership distribuida;
- player sessions Redis;
- player presence Redis;
- occupancy y capacity Redis;
- transferencias distribuidas;
- Auth → Lobby;
- `/hub`, `/lobby`, Lobby switching;
- kick failover distribuido;
- raw Velocity `/server` bloqueado para jugadores;
- observabilidad administrativa.

La rama `feature/distributed-backend-bootstrap` añade el foundation A.1–A.8 de ownership distribuido de bootstrap. Todavía no arranca procesos reales.

## Invariantes obligatorios

### Fail-closed

Si no puede demostrarse una autoridad requerida, la operación no continúa.

Esto aplica a:

- Proxy membership;
- player-session ownership;
- backend live identity;
- backend health/freshness;
- capacity;
- bootstrap ownership;
- futuros side effects de orchestration.

Nunca introducir fallback productivo local silencioso cuando Redis es la autoridad distribuida.

### Separación de estados

Nunca confundir:

```text
TCP connection
TLS/HMAC authentication
backend live identity
backend health
backend process state
bootstrap ownership
capacity reservation
player presence/readiness
```

Uno no demuestra automáticamente al siguiente.

### Backend identity y health

`BACKEND_HELLO` y `BACKEND_HELLO_ACK` están retirados.

No reintroducir identidad backend mediante Plugin Messaging.

`PING`/`PONG` de health pertenecen exclusivamente al authenticated persistent Control Channel.

Plugin Messaging queda reservado para mensajes player-scoped.

La static backend policy define nombre/tipo/capacidad/preferencia, pero no demuestra live identity ni health.

### Redis y fencing

Redis coordina estado temporal y ownership distribuido.

- usar exact owner/incarnation/fencing;
- renew exact-match;
- release/remove exact-match;
- un owner stale no puede mutar o borrar generaciones nuevas;
- corrupción estructural debe fallar cerrada;
- TTL sirve para recuperación ante crash, no como excusa para ownership ambiguo.

Redis no es la fuente durable de perfiles, progreso u otros datos permanentes.

### Concurrencia

No bloquear threads de Velocity con:

- Redis/network I/O;
- consultas persistentes;
- waits arbitrarios;
- lectura/escritura pesada.

Preferir fronteras asíncronas y declarar explícitamente ownership de callbacks, deadlines, races y cleanup.

No ejecutar cierre bloqueante de clientes/conexiones desde un event loop Lettuce.

## Composition root

Mantener `TheosferaProxy` enfocado en lifecycle y composición.

- constructor injection cuando corresponda;
- evitar global mutable state;
- separar contratos, stores, coordinators, services, platform adapters y composition;
- no crear god classes;
- no meter Lua/Redis keys dentro de resolvers de producto.

El constructor del plugin no debe realizar registro de listeners/comandos/tareas ni I/O de inicialización.

Usar:

- `ProxyInitializeEvent` para startup;
- `ProxyShutdownEvent` para teardown.

## Auth y player state

Auth es un estado restringido.

La autenticación global requiere adquirir un `PlayerSessionLease` Redis y vincularlo a la conexión exacta antes de considerar al jugador dueño de una sesión válida.

Presence distribuida se publica usando el lease exacto y fencing de sesión.

Disconnect/shutdown debe respetar el orden seguro de presence cleanup y session release.

No habilitar features sociales o movimientos que requieran identidad global antes de autenticar al jugador.

## Routing, capacity y transfers

Capacity Redis ya es productiva.

No reintroducir `BackendCapacityReservationRegistry` como autoridad/fallback local.

Los consumers productivos incluyen:

- `TRANSFER_REQUEST`;
- `/hub`;
- `/lobby`;
- `/hub switch` / `/lobby switch`;
- backend kick failover.

Las transferencias oficiales deben pasar por Theosfera y conservar:

- policy;
- live backend identity;
- health/freshness;
- distributed capacity;
- session ownership/fencing;
- exact release/handoff;
- retry semantics.

Raw Velocity `/server` no es una ruta válida para jugadores y no tiene bypass de staff.

## Kick failover

Mantener:

- destinos `RESOLVED` solamente;
- `BOOTSTRAP_REQUIRED` inválido para kicks;
- cero intento de bootstrap de backend frío durante kick failover;
- destino siempre live + HEALTHY + capacidad Redis;
- source classification desde static policy únicamente cuando la pérdida de control del origen ya eliminó su live identity.

No conservar identidad stale para resolver esa carrera.

## Distributed Backend Bootstrap — rama activa

Foundation A.1–A.8 implementado:

- public contracts;
- Redis keyspace/store;
- Lua atomic acquire/renew/release;
- membership fencing;
- bootstrap fencing;
- TTL 60 s / renew 20 s;
- ownership lifecycle;
- `DEGRADED` / `FENCED` semantics;
- async race handling;
- Velocity scheduler;
- lifecycle factory HEALTHY-only;
- Redis/Testcontainers integration coverage.

El bootstrap lease representa solo el derecho fenced de **coordinar** bootstrap.

No prueba:

- process running;
- port readiness;
- control authentication;
- health;
- capacity;
- player readiness.

No cablear todavía side effects de process startup sin diseñar el `Backend Orchestration Provider`.

El provider futuro debe recibir suficiente autoridad/fencing para rechazar órdenes stale.

Cuando exista true cold startup, capacity debe reservarse después de control authentication + fresh health/revalidation; no mantener la reservation TTL actual durante el tiempo de boot.

## Responsabilidades fuera del Proxy

No introducir:

- Bukkit/Paper gameplay;
- mundos, entidades o inventarios;
- mecánicas Skyblock;
- SuperiorSkyblock2 integration;
- `/storage` / `/workbench` de Skyblock;
- menús de inventario de Lobby;
- progreso específico de modalidades;
- UI específica del cliente.

## Sistemas futuros

Maintenance, Drain, Friends, Parties, Squads, Matchmaking, perfiles y otros sistemas previstos deben planificarse antes de escribir implementación.

Definir para cada uno:

- objetivo;
- owner/plugin;
- source of truth;
- persistencia;
- estado distribuido;
- comandos/permisos/UI;
- fallos;
- seguridad;
- dependencias;
- runtime acceptance.

No implementar features sociales ad-hoc porque aparezcan como visión de producto.

## Flujo Git y validación

No trabajar directamente sobre `main`.

Antes de completar un cambio:

```text
git diff --check
full relevant tests
clean build cuando corresponda
review del diff completo
runtime acceptance proporcional al riesgo
working tree clean
```

Integration tests Redis usan Testcontainers; en CI la ausencia de Docker requerido debe fallar explícitamente según la política existente.

Un build exitoso no sustituye runtime testing para cambios operacionales.

## Seguridad

Nunca versionar ni registrar:

- contraseñas;
- tokens;
- claves privadas;
- secretos HMAC;
- passwords de keystore/truststore;
- credenciales Redis productivas;
- tokens de orchestration providers;
- datos sensibles de jugadores.

## Punto de continuación actual

```text
Distributed Backend Bootstrap Foundation
→ rama feature/distributed-backend-bootstrap
→ A.1–A.8 implementado y validado
→ checkpoint creado
→ pendiente PR/squash merge
→ siguiente milestone: Backend Orchestration Provider
```

No saltar directamente a Maintenance o sistemas sociales dentro del mismo cambio técnico salvo repriorización explícita del propietario.
