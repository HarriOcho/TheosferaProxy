# TheosferaProxy — Instrucciones para agentes

## Lectura obligatoria

Antes de proponer o implementar cambios:

1. leer este archivo;
2. leer `CONTRIBUTING.md`;
3. leer `PROJECT_STATE.md` para el último estado fusionado;
4. leer `docs/README.md`;
5. leer el checkpoint/diseño más reciente del milestone activo;
6. verificar código y estado Git reales.

Durante una rama activa, un checkpoint posterior puede complementar `PROJECT_STATE.md` hasta la consolidación final. No reconstruir el presente desde checkpoints históricos superseded.

## Identidad

- Proyecto: TheosferaProxy.
- Plataforma: Velocity `3.5.0-SNAPSHOT`.
- Java: 21.
- Build: Gradle Kotlin DSL.
- Package raíz: `com.theosfera.proxy`.
- Rol: coordinador global/cross-server de Theosfera.

No introducir gameplay específico de Paper/Bukkit o de una modalidad dentro del Proxy.

## Estado fusionado vigente

`main @ ddc082319243da621d4e5364d4c4957f8d088b0d` incluye PR `#74`, Distributed Backend Bootstrap Foundation A.1–A.8.

El runtime fusionado ya incluye:

- TheosferaProtocol v2;
- Backend Control Channel TLS/HMAC;
- identidad backend live desde la control session autenticada actual;
- health PING/PONG por Control Channel;
- Redis Coordination Runtime;
- Proxy membership fenced;
- player sessions Redis;
- player presence Redis;
- occupancy y capacity Redis;
- transferencias distribuidas;
- Auth → Lobby;
- `/hub`, `/lobby`, `/hub switch`, `/lobby switch`;
- kick failover distribuido;
- raw Velocity `/server` bloqueado para jugadores;
- observabilidad administrativa;
- distributed bootstrap ownership con TTL/renew/fencing.

## Rama técnica activa

```text
feature/backend-orchestration-provider
```

Leer primero:

```text
docs/BACKEND_ORCHESTRATION_PROVIDER_PRE_RUNTIME_CHECKPOINT.md
```

Estado del milestone:

```text
B.1 provider contracts                         VALIDATED
B.2 fenced provider / actuator strategy        VALIDATED
B.3 startup operation lifecycle                VALIDATED
B.4 Control Channel readiness bridge           IMPLEMENTED / LOCAL GATE PENDING
B.5 provider-neutral cold-start foundation     IMPLEMENTED / LOCAL GATE PENDING
B.6 real runtime acceptance                    BLOCKED ON REAL ACTUATOR
```

No existe todavía un proceso real arrancado por TheosferaProxy y el product cold path legado no se ha sustituido.

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
- orchestration side effects.

Nunca introducir fallback productivo local silencioso cuando una frontera distribuida es autoritativa.

### Estados separados

Nunca confundir:

```text
TCP connection
    != TLS/HMAC authenticated control identity
    != backend HEALTHY
    != bootstrap ownership
    != process state
    != capacity reservation
    != player readiness
```

### Backend identity y health

`BACKEND_HELLO` y `BACKEND_HELLO_ACK` están retirados.

- Backend identity proviene de la current authenticated Control Channel session.
- PING/PONG health pertenece exclusivamente al Control Channel.
- Plugin Messaging es player-scoped.
- Static backend policy define nombre/tipo/capacidad/preferencia, pero no demuestra live identity ni health.
- Una nueva generación de Control Channel debe invalidar pending PING + health name-scoped anteriores y producir su propio PONG antes de ser HEALTHY.

No reintroducir identidad/health por Plugin Messaging.

### Redis y fencing

- usar exact owner/incarnation/fencing;
- renew exact-match;
- release/remove exact-match;
- owner stale no puede mutar/borrar una generación nueva;
- corrupción estructural falla cerrada;
- TTL es recuperación ante crash, no permiso para ownership ambiguo;
- Redis es coordinación temporal, no persistencia durable de perfiles/progreso.

### Backend bootstrap

El bootstrap lease representa únicamente el derecho fenced de coordinar bootstrap.

```text
bootstrap lease
    != backend identity
    != backend health
    != capacity reservation
```

Foundation fusionado:

- Redis acquire/renew/release atómico;
- membership fencing;
- bootstrap fencing;
- TTL 60 s / renew 20 s;
- ownership lifecycle;
- DEGRADED/FENCED semantics;
- Velocity lifecycle factory.

### Orchestration B.1–B.5 foundation

B.1:

- `BackendOrchestrationProvider`;
- `BackendStartRequest` conserva el `BackendBootstrapLease` exacto;
- resultados explícitos.

B.2:

- logical backend y orchestration target son fronteras distintas;
- target del orchestrator debe provenir de mapping confiable, nunca de input directo del jugador/admin;
- `BackendStartActuator.startIfCurrent(...)` debe combinar fencing + aceptación/emisión del start side effect de forma atómica;
- Redis pre-check + unfenced start posterior es inválido por TOCTOU;
- replay exacto es idempotente;
- stale/conflict produce cero side effect.

B.3:

- startup lifecycle single-use;
- `PROVIDER_UNAVAILABLE` es retryable con bounded backoff;
- timeout independiente del bootstrap TTL;
- late callbacks no reviven una generación terminal;
- `START_ACCEPTED` no equivale a readiness y conserva ownership para B.4.

B.4:

- readiness exige static policy + current control identity + HEALTHY/fresh;
- no usar process state/TCP como readiness;
- Control reconnect invalida old pending PING/health;
- timeout/cancel/fencing son fail-closed;
- exact lease replacement no autoriza liberar una generación desconocida.

B.5 foundation:

- `BackendColdStartCoordinator` compone ownership → B.3 → B.4 → exact release;
- capacity queda fuera del cold-start coordinator;
- `DistributedPlayerTransferTargetAllocation` ya puede representar un futuro `BOOTSTRAP_REQUIRED` pre-capacity;
- schedulers Velocity one-shot existen para startup/readiness;
- el product allocation/retry legado sigue intencionalmente activo hasta elegir un actuator real.

Flujo objetivo cuando B.5 productivo se active:

```text
select cold target
→ acquire bootstrap ownership
→ fenced provider start
→ renew ownership while starting
→ current TLS/HMAC control auth
→ fresh PONG / HEALTHY
→ exact bootstrap release
→ re-resolve / revalidate
→ reserve Redis capacity
→ register pending transfer
→ Velocity ConnectionRequest
→ PLAYER_SERVER_READY
→ presence handoff / exact release
```

No mantener la reservation TTL de ~20 s durante backend boot.

## Routing y transfers productivos actuales

Capacity Redis ya es productiva para:

- `TRANSFER_REQUEST`;
- `/hub`;
- `/lobby`;
- `/hub switch` / `/lobby switch`;
- backend kick failover.

Raw Velocity `/server` no es una ruta válida para jugadores.

Kick failover mantiene:

- destinos `RESOLVED` solamente;
- `BOOTSTRAP_REQUIRED` inválido;
- cero bootstrap frío durante kicks;
- destino live + HEALTHY + capacity Redis.

## Feature administrativa futura registrada

Leer:

```text
docs/ADMINISTRATIVE_PLAYER_TRANSFER_DESIGN.md
```

Decisiones:

```text
raw /send                             → bloquear
/theosfera send <player> <BackendType> → superficie oficial futura
```

- routing automático por policy/preference/health/capacity;
- sesión autenticada exacta obligatoria;
- ningún auth bypass administrativo;
- sesión inconsistente/no demostrable → reject + controlled disconnect para revalidar Auth/nLogin;
- cross-proxy transfer command fenced por sesión;
- TAB y descubribilidad permission-aware/stealth;
- sin permiso: no TAB y ejecución manual indistinguible de comando inexistente.

No asumir que esta feature ya está implementada.

## Concurrencia y composition root

- No bloquear threads de Velocity con Redis/network I/O o waits arbitrarios.
- Preferir fronteras asíncronas.
- Proteger epochs/generations, callbacks tardíos, deadlines y cleanup.
- Mantener `TheosferaProxy` enfocado en lifecycle/composición.
- Preferir constructor injection.
- Separar contracts, stores, coordinators, services, platform adapters y composition.
- No meter Lua/Redis keys dentro de resolvers de producto.
- Startup en `ProxyInitializeEvent`; teardown en `ProxyShutdownEvent`.

## Seguridad

Nunca versionar ni registrar:

- contraseñas;
- tokens;
- claves privadas;
- secretos HMAC;
- passwords de keystore/truststore;
- credenciales Redis productivas;
- tokens/credenciales de orchestration providers;
- datos sensibles de jugadores.

El concrete actuator futuro debe tratar targets como datos confiables, no concatenarlos en shell commands inseguros.

## Validación

Antes de completar un cambio:

```text
git diff --check
relevant focused tests
full test suite
clean build cuando corresponda
review del diff completo
runtime acceptance proporcional al riesgo
working tree clean
```

Un build exitoso no sustituye runtime testing para cambios operacionales.

## Punto exacto de continuación

Primero validar localmente B.4/B.5 foundation según:

```text
docs/BACKEND_ORCHESTRATION_PROVIDER_PRE_RUNTIME_CHECKPOINT.md
```

Después:

```text
seleccionar orchestration platform real
→ implementar trusted target resolver + fenced BackendStartActuator
→ probar fencing/idempotency contra esa plataforma
→ activar B.5 product cold path
→ retirar autoridad local legacy de bootstrap del cold path
→ B.6 runtime matrix
→ final checkpoint
→ PR
```

No marcar B.6 como completado ni abrir PR del milestone antes de runtime real. No saltar a Maintenance, Administrative Player Transfer o sistemas sociales salvo repriorización explícita del propietario.
