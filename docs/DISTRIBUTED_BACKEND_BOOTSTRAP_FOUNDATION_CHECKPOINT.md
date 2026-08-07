# Distributed Backend Bootstrap Foundation — Checkpoint

## Estado

El foundation de coordinación distribuida para bootstrap de backends quedó implementado en la rama:

```text
feature/distributed-backend-bootstrap
```

Base autoritativa:

```text
main @ da0f0659482636b49f7450ace29f519944f6c92a
```

Último HEAD funcional previo a este checkpoint:

```text
f996cd7f3a1c8849abbd5e40a769bc64958e449b
```

La rama se encontraba `31` commits por delante de `main` y `0` commits por detrás antes de agregar este documento.

Este milestone implementa exclusivamente la fundación de ownership distribuido, fencing, TTL/renewal y composición Velocity necesarios para que un Proxy pueda adquirir el derecho temporal y exclusivo de coordinar el arranque de un backend.

**Todavía no inicia procesos de backend y todavía no modifica el flujo productivo de transferencias.**

---

## Contexto autoritativo previo

El punto de partida es el estado post-merge de Backend Control Channel Increment E:

- `BACKEND_HELLO` / `BACKEND_HELLO_ACK` retirados;
- identidad live de backend derivada exclusivamente de la sesión de control TLS/HMAC autenticada actual;
- `PING` / `PONG` de health exclusivamente por el control channel persistente;
- Plugin Messaging reservado para tráfico player-scoped;
- perder la sesión de control actual elimina autorización live fail-closed;
- una generación vieja de control no puede revocar una nueva;
- destinos jugables requieren identidad live + health `HEALTHY` vigente + capacidad Redis;
- zero-player identity/health y reconnect/fencing quedaron validados en runtime.

Checkpoint previo:

```text
docs/BACKEND_CONTROL_CHANNEL_INCREMENT_E_POST_MERGE.md
```

Esta fundación **no cambia ni debilita** ese modelo.

---

## Objetivo del milestone

Resolver una pregunta única:

> Si múltiples proxies detectan que un mismo backend frío necesita ser iniciado, ¿qué Proxy posee el derecho distribuido, temporal y fenced de coordinar ese bootstrap?

El resultado es un lease de bootstrap que representa únicamente **orchestration ownership**.

No representa:

- proceso arrancado;
- puerto abierto;
- identidad de backend;
- control channel autenticado;
- health actual;
- capacidad reservada;
- jugador transferido;
- presencia confirmada.

Invariante principal:

```text
bootstrap ownership
    != backend identity
    != backend health
    != backend capacity reservation
```

---

## Scope implementado

El foundation quedó construido en incrementos A.1–A.8.

### A.1 — Contratos públicos

Se introdujeron:

- `BackendBootstrapAcquireRequest`;
- `BackendBootstrapLease`;
- `BackendBootstrapAcquireResult`;
- `BackendBootstrapRenewResult`;
- `BackendBootstrapReleaseResult`;
- `BackendBootstrapCoordinator`.

Un acquire queda ligado a:

```text
targetBackendName
requestId
playerId
ProxyMembershipLease exacto
```

Un lease adquirido conserva además:

```text
bootstrap fencing token
```

El fencing de membership y el fencing de bootstrap son deliberadamente distintos:

- membership fencing demuestra qué incarnation posee actualmente el `proxyName`;
- bootstrap fencing ordena generaciones sucesivas de ownership sobre el backend objetivo.

---

## Redis keyspace

Namespace por defecto:

```text
theosfera:coordination
```

Keys de bootstrap:

```text
theosfera:coordination:backend-bootstrap:lease:<backendName>
theosfera:coordination:backend-bootstrap:request:<requestId>
theosfera:coordination:backend-bootstrap:fencing
```

Responsabilidades:

- `lease:<backend>`: ownership exclusivo del backend objetivo;
- `request:<requestId>`: índice que impide reutilizar el mismo request para otra operación incompatible;
- `fencing`: contador monotónico global de generaciones de bootstrap.

El índice por request y el lease por target se mantienen como una unidad lógica y se validan entre sí. Un estado cruzado inconsistente se considera corrupción y falla cerrado.

---

## Store Redis y atomicidad

`LettuceRedisBackendBootstrapStore` implementa las operaciones mediante Lua atómico.

Operaciones:

```text
ACQUIRE
RENEW
RELEASE
```

Las tres vuelven a validar el `ProxyMembershipLease` exacto contra Redis antes de conceder o modificar ownership.

### ACQUIRE

Flujo autoritativo:

```text
validar membership key actual
        ↓
proxyName exacto
        ↓
incarnationId exacto
        ↓
membership fencing exacto
        ↓
validar request index
        ↓
validar target lease
        ↓
INCR bootstrap fencing
        ↓
crear target lease + request index
        ↓
aplicar TTL
```

Resultados públicos posibles:

```text
ACQUIRED
ALREADY_OWNED
TARGET_BUSY
REQUEST_ID_CONFLICT
MEMBERSHIP_NOT_FOUND
NOT_MEMBERSHIP_OWNER
COORDINATION_UNAVAILABLE
```

Propiedades:

- repeated exact acquire es idempotente y devuelve `ALREADY_OWNED` con el mismo lease;
- el mismo backend no puede tener dos owners simultáneos;
- un `requestId` no puede representar dos bootstraps incompatibles;
- una membership fabricada o expirada no concede ownership;
- acquire exitoso genera fencing positivo y monotónico.

### RENEW

Renew exige exact-match de:

```text
backend
requestId
playerId
proxyName
incarnationId
membership fencing
bootstrap fencing
```

También exige que la membership Redis actual continúe perteneciendo a esa incarnation.

No revive leases faltantes ni crea ownership nuevo.

Resultados públicos:

```text
RENEWED
NOT_FOUND
NOT_OWNER
CONFLICT
MEMBERSHIP_NOT_FOUND
NOT_MEMBERSHIP_OWNER
COORDINATION_UNAVAILABLE
```

### RELEASE

Release también es exact-match y vuelve a validar membership.

Una incarnation vieja o un lease con bootstrap fencing incorrecto no puede borrar una generación nueva.

Resultados públicos:

```text
RELEASED
NOT_FOUND
NOT_OWNER
CONFLICT
MEMBERSHIP_NOT_FOUND
NOT_MEMBERSHIP_OWNER
COORDINATION_UNAVAILABLE
```

---

## Corrupción y política fail-closed

El store Redis utiliza estados internos `CORRUPT` para detectar shapes inválidos o desacuerdo entre target lease y request index.

`RedisBackendBootstrapInvalidStateException` materializa esta condición.

Decisión aprobada:

- errores operativos del store se convierten en `COORDINATION_UNAVAILABLE` en la frontera pública;
- corrupción/invariantes Redis rotas **no** se disfrazan como backend libre, target busy ni indisponibilidad ordinaria;
- una corrupción se expone explícitamente y la operación falla cerrado.

Nunca tratar datos Redis desconocidos o inconsistentes como permiso para arrancar un backend.

---

## Coordinator público

`RedisBackendBootstrapCoordinator` adapta el store Redis hacia `BackendBootstrapCoordinator`.

Además de mapear estados, valida que los leases devueltos por Redis correspondan exactamente con la solicitud esperada.

En particular:

- `ACQUIRED` / `ALREADY_OWNED` deben devolver backend, request, player y membership exactos;
- `RENEWED` debe devolver el lease exacto esperado;
- respuestas inesperadas o inconsistentes fallan cerrado.

---

## Política temporal productiva inicial

`BackendBootstrapLeasePolicy.productDefaults()` quedó fijada en:

```text
TTL:            60 segundos
renew interval: 20 segundos
```

Interpretación correcta:

> El TTL es una ventana de detección/recuperación de ownership perdido, no un timeout máximo de startup del backend.

Mientras una operación de bootstrap siga legítimamente en progreso, el owner renueva el lease.

Por lo tanto un backend puede tardar más de 60 segundos en iniciar si el Proxy mantiene ownership mediante renew exitoso.

La relación inicial mantiene:

```text
TTL = 3 × renew interval
```

---

## Ownership lifecycle

`BackendBootstrapOwnershipLifecycle` representa una sola operación de bootstrap y es single-use.

Estados:

```text
NEW
 ↓
ACQUIRING
 ↓
OWNED
 ↓
DEGRADED
 ↓
FENCED
```

Cierre intencional:

```text
NEW / ACQUIRING / OWNED / DEGRADED
 ↓
STOPPING
 ↓
STOPPED
```

### OWNED

Existe lease vigente y la última coordinación es válida.

### DEGRADED

Ocurrió `COORDINATION_UNAVAILABLE`, pero el último deadline local conocido del lease todavía no expiró.

La operación puede conservar autoridad únicamente dentro de esa ventana conocida.

### FENCED

Se pierde autoridad inmediatamente cuando existe evidencia explícita de:

- lease no encontrado;
- owner incorrecto;
- conflicto;
- membership ausente;
- membership perteneciente a otra incarnation;
- lease renovado inconsistente;
- deadline local expirado;
- error/invariante que haga inseguro continuar.

Al llegar a `FENCED`, el futuro orchestrator debe abortar cualquier side effect autoritativo de esa generación.

`termination()` completa con el estado terminal y será la señal de aborto/terminación para la capa de orchestration.

---

## Carreras async protegidas

El lifecycle cubre explícitamente carreras importantes.

### Stop durante ACQUIRE

Caso:

```text
ACQUIRE Redis en vuelo
        ↓
stop()
        ↓
Redis concede ownership tarde
```

Resultado requerido e implementado:

```text
NO iniciar trabajo autoritativo
↓
release exact-match del lease tardío
↓
STOPPED
```

### Renew tardío después de stop

Un completion viejo de renew no puede restaurar `OWNED` ni `HEALTHY` después del cierre.

### Scheduler failure

Si el scheduler no puede instalar el ciclo de renew después del acquire:

- la operación se fencea;
- intenta liberar el lease exacto;
- no conserva ownership local huérfano.

---

## Velocity composition

`VelocityBackendBootstrapRenewalScheduler` adapta `BackendBootstrapRenewalScheduler` al scheduler de Velocity.

`BackendBootstrapOwnershipLifecycleFactory` crea y arranca una operación usando el `ProxyMembershipLease` vigente al inicio.

Guardia local:

```text
ProxyMembershipLifecycle.state() == HEALTHY
AND
currentLease() != null
```

Si la membership local está:

```text
DEGRADED
FENCED
STOPPING
sin lease
```

no se intenta un nuevo bootstrap.

Esta guardia local no reemplaza Redis.

Existe una carrera inevitable entre leer membership local y ejecutar el Lua de acquire; Redis vuelve a validar owner/incarnation/fencing de forma autoritativa, por lo que una membership que se vuelva stale durante esa ventana será rechazada fail-closed.

`VelocityRedisCoordinationBootstrap` expone la factory sin activar ningún consumer productivo.

---

## Integración con RedisCoordinationRuntime

`RedisCoordinationRuntime` puede crear `RedisBackendBootstrapCoordinator` cuando el runtime de coordinación está `HEALTHY`.

No se introdujo un renew loop global en `RedisCoordinationRuntime`.

Decisión arquitectónica:

> El renew pertenece a la operación concreta de bootstrap, no al runtime Redis global.

Cada backend puede tener su propia operación, fencing y lifecycle independientes.

---

## Validación automatizada

Se añadieron pruebas unitarias para:

- lease policy;
- coordinator mapping;
- unavailable/corrupt behavior;
- lease mismatches;
- lifecycle state machine;
- temporary degraded state;
- deadline fencing;
- explicit ownership loss;
- exact release;
- late renew completion;
- stop durante acquire;
- scheduler failure;
- lifecycle factory HEALTHY-only;
- Velocity renewal scheduler.

Se añadió integración Redis real con Testcontainers usando:

```text
redis:7.4.2-alpine
```

La integración demuestra:

1. bootstrap requiere membership Redis actual;
2. dos proxies no pueden poseer simultáneamente el mismo backend;
3. repeated exact acquire es idempotente;
4. un requestId no puede poseer dos bootstraps;
5. una membership vieja no puede renovar ni liberar;
6. bootstrap fencing incorrecto produce conflicto;
7. después de expiry otro Proxy puede adquirir el target;
8. el nuevo owner recibe bootstrap fencing mayor.

Último gate local reportado después de A.8:

```text
.\gradlew.bat test --no-daemon
BUILD SUCCESSFUL
```

También se reportaron repetidamente `git diff --check` limpio y suites completas verdes durante los incrementos anteriores.

El gate final de PR todavía debe ejecutar nuevamente:

```text
git diff --check
.\gradlew.bat test --no-daemon
.\gradlew.bat clean build --no-daemon
```

---

## Scope deliberadamente NO implementado

Este foundation **no** modifica todavía:

- `DistributedPlayerTransferRetryCoordinator`;
- `DistributedPlayerTransferTargetAllocationService`;
- `TransferTargetResolver`;
- `BackendBootstrapRegistry` local existente;
- `BackendCapacityReservationRegistry`;
- kick failover;
- `/hub`;
- `/lobby`;
- Lobby instance switching;
- `/theosfera transfer`;
- Plugin Messaging;
- TheosferaProtocol;
- Backend Control Channel;
- backend process management.

No existe todavía ningún `ProcessBuilder`, SSH, Docker API, Kubernetes API, panel API, systemd invocation ni proveedor concreto de infraestructura.

No existe fallback local de orchestration ownership.

---

## Relación con el bootstrap local legacy

`BackendBootstrapRegistry` continúa existiendo en la ruta productiva histórica.

Ese registro local:

- no es equivalente al nuevo lease Redis;
- no debe convertirse silenciosamente en fallback cuando Redis falle;
- será reemplazado o retirado únicamente cuando el wiring productivo del nuevo orchestrator esté completo y validado.

Hasta entonces, el foundation Redis permanece desacoplado para no cambiar comportamiento de transferencias accidentalmente.

---

## Orden productivo futuro aprobado

El flujo de cold backend real debe evolucionar hacia:

```text
resolver candidato frío
        ↓
adquirir distributed bootstrap ownership
        ↓
request backend process start mediante orchestration provider
        ↓
renovar bootstrap lease mientras STARTING
        ↓
esperar sesión TLS/HMAC de control actual
        ↓
esperar PONG fresco / HEALTHY
        ↓
re-resolver y revalidar destino
        ↓
reservar capacidad Redis
        ↓
Velocity ConnectionRequest
        ↓
PLAYER_SERVER_READY
        ↓
presence handoff / exact capacity release
        ↓
exact bootstrap release
```

Cambio importante respecto de la ruta histórica:

> La reserva de capacidad debe ocurrir **después** de que el backend frío esté realmente listo para recibir al jugador.

La reserva productiva de capacidad no debe mantenerse durante todo el startup del proceso.

`backendCapacityReservationTtl` y `backend bootstrap lease TTL` resuelven problemas diferentes y no deben acoplarse.

---

## Invariantes que el siguiente milestone debe preservar

1. Plugin Messaging nunca vuelve a transportar identidad backend.
2. TCP conectado no significa backend autenticado.
3. TLS establecido no significa backend healthy.
4. proceso `RUNNING` no significa backend healthy.
5. puerto abierto no significa backend ready.
6. sesión de control autenticada no significa health fresco.
7. fresh `PONG` del control session actual es evidencia de health.
8. bootstrap lease no puede usarse como prueba de identidad o health.
9. bootstrap fencing debe entregarse al provider/orchestrator para cercar side effects stale.
10. una incarnation vieja no puede continuar arrancando, renovar o liberar trabajo de una incarnation nueva.
11. Redis indisponible no habilita fallback local silencioso.
12. capacidad Redis se reserva únicamente cuando el destino vuelva a ser apto para transferencia.
13. el Core no se convierte en cliente Redis para resolver bootstrap.
14. la autoridad de process orchestration pertenece al Proxy/control plane, no a Plugin Messaging player-scoped.

Resumen:

```text
TCP connected      != authenticated
authenticated      != healthy
process running    != healthy
bootstrap owned    != backend ready
fresh control PONG == current health evidence
```

---

## Siguiente milestone exacto

El siguiente milestone aprobado es:

# Backend Orchestration Provider

Objetivo:

> Definir una frontera de infraestructura que permita al owner fenced de bootstrap solicitar el arranque de un backend sin acoplar TheosferaProxy a Docker, Kubernetes, systemd, un panel específico o una máquina concreta.

Antes de wiring productivo se debe diseñar:

- `BackendOrchestrationProvider` o frontera equivalente;
- request de start con backend objetivo y bootstrap fencing;
- semántica idempotente de start;
- estado observado vs desired state;
- timeouts de startup;
- cancel/abort al perder bootstrap ownership;
- protección contra owner stale;
- qué significa `START_REQUESTED`, `STARTING`, `RUNNING`, `FAILED`;
- política cuando el proceso ya existe;
- validación de backend permitido por policy;
- separación entre provider genérico y adaptadores concretos;
- observabilidad/logging;
- pruebas con fake provider antes de seleccionar infraestructura real.

No conectar todavía transfers directamente a un proveedor concreto sin cerrar primero este contrato.

Después del provider/fake orchestration y su lifecycle, el wiring productivo podrá integrar:

```text
cold target
→ bootstrap ownership
→ process orchestration
→ control authentication
→ health readiness
→ capacity reservation
→ player transfer
```

---

## Estado final del checkpoint

```text
Distributed ownership contracts       DONE
Redis keyspace                        DONE
Atomic acquire                        DONE
Atomic renew                          DONE
Exact-match release                   DONE
Proxy membership validation           DONE
Membership fencing                    DONE
Bootstrap fencing                     DONE
Request exclusivity                   DONE
Target exclusivity                    DONE
TTL / renew policy                    DONE
Ownership lifecycle                   DONE
DEGRADED / FENCED semantics           DONE
Async race hardening                  DONE
Velocity renewal scheduler            DONE
Lifecycle factory                     DONE
Redis integration tests               DONE
Product transfer wiring               NOT STARTED
Backend process orchestration         NOT STARTED
Real infrastructure provider          NOT SELECTED
```

Punto exacto de reanudación:

```text
Diseñar Backend Orchestration Provider
sin tocar todavía la autoridad de identity/health
ni reintroducir Plugin Messaging para backend control.
```
