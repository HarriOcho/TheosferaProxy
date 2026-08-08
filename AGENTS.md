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
docs/PTERODACTYL_ORCHESTRATION_GATEWAY_DESIGN.md
```

Estado:

```text
B.1 provider contracts                         VALIDATED
B.2 fenced provider / actuator strategy        VALIDATED
B.3 startup operation lifecycle                VALIDATED
B.4 Control Channel readiness bridge           VALIDATED
B.5 provider-neutral cold-start foundation     VALIDATED
B.5c Pterodactyl process plane                 SELECTED
B.5c Proxy -> Gateway adapter                  IMPLEMENTED / LOCAL GATE PENDING
B.6 real runtime acceptance                    OPEN
```

El product cold path legado todavía no se ha sustituido.

## Invariantes obligatorios

### Fail-closed

Si no puede demostrarse una autoridad requerida, la operación no continúa.

Aplica a:

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
    != provider ACCEPTED
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
- Nueva Control Channel generation invalida pending PING + health name-scoped anteriores y debe producir un PONG nuevo antes de ser HEALTHY.

No reintroducir identidad/health por Plugin Messaging.

### Redis y fencing

- usar exact owner/incarnation/fencing;
- renew exact-match;
- release/remove exact-match;
- owner stale no puede mutar/borrar generación nueva;
- corrupción estructural falla cerrada;
- TTL es recuperación ante crash, no ownership ambiguo;
- Redis es coordinación temporal, no persistencia durable de perfiles/progreso.

### Backend bootstrap

```text
bootstrap lease
    != backend identity
    != backend health
    != capacity reservation
```

Foundation fusionado incluye acquire/renew/release Redis atómico, membership/bootstrap fencing, TTL 60 s / renew 20 s, ownership lifecycle, DEGRADED/FENCED y Velocity lifecycle factory.

## Backend Orchestration Provider

B.1:

- `BackendOrchestrationProvider`;
- `BackendStartRequest` conserva `BackendBootstrapLease` exacto;
- resultados explícitos.

B.2:

- logical backend y orchestration target son fronteras distintas;
- target del orchestrator viene de mapping confiable, nunca input directo player/admin;
- fencing + aceptación/emisión del start side effect deben ser una decisión serializada del orchestrator;
- Redis pre-check + unfenced side effect posterior es inválido por TOCTOU;
- exact replay idempotente;
- stale/conflict produce cero new side effect.

B.3:

- startup lifecycle single-use;
- solo `PROVIDER_UNAVAILABLE` retryable;
- bounded backoff + timeout independiente;
- callbacks tardíos no reviven estados terminales;
- `START_ACCEPTED` conserva bootstrap ownership para readiness.

B.4:

- readiness exige static policy + current Control identity + HEALTHY/fresh;
- process state/TCP no son readiness;
- Control reconnect invalida old pending PING/health;
- timeout/cancel/fencing fail-closed.

B.5:

- `BackendColdStartCoordinator` compone ownership → B.3 → B.4 → exact release;
- capacity queda fuera del cold-start coordinator;
- `DistributedPlayerTransferTargetAllocation` soporta future `BOOTSTRAP_REQUIRED` pre-capacity;
- product allocation/retry legado sigue activo hasta runtime real.

Flujo objetivo:

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
→ pending transfer
→ ConnectionRequest
→ PLAYER_SERVER_READY
→ presence handoff / exact release
```

No mantener la capacity reservation TTL de ~20 s durante backend boot.

## Concrete process plane: Pterodactyl

Arquitectura seleccionada:

```text
TheosferaProxy
→ HTTPS Theosfera Orchestration Gateway
→ Pterodactyl Panel/Wings
→ backend process/container
```

Reglas:

- TheosferaProxy NO llama directamente al Pterodactyl power API;
- Proxy NO contiene token/credenciales Pterodactyl;
- Gateway token del Proxy viene de environment;
- Pterodactyl target mapping es static/trusted config;
- Gateway es la frontera que persiste/serializa fencing, replay y conflict;
- Gateway debe conservar fencing/idempotency tras restart;
- Pterodactyl process state NO es backend readiness;
- no Wings/Docker/systemd fallback directo desde Proxy.

Proxy adapter implementado bajo:

```text
com.theosfera.proxy.orchestration.pterodactyl
```

Default `orchestration.properties` está disabled. Enabled requiere HTTPS, token env y al menos un target. AUTH no es ordinary gameplay cold-start target.

## Routing y transfers productivos actuales

Capacity Redis es productiva para:

- `TRANSFER_REQUEST`;
- `/hub`;
- `/lobby`;
- `/hub switch` / `/lobby switch`;
- backend kick failover.

Raw Velocity `/server` no es ruta válida para jugadores.

Kick failover mantiene `RESOLVED`-only; jamás cold bootstrap durante kick.

## Feature administrativa futura registrada

Leer `docs/ADMINISTRATIVE_PLAYER_TRANSFER_DESIGN.md`.

```text
raw /send                              → bloquear
/theosfera send <player> <BackendType> → superficie oficial futura
```

Requiere routing automático, sesión autenticada exacta, ningún auth bypass, cross-proxy fencing y TAB/descubribilidad permission-aware/stealth.

No asumir que ya está implementada.

## Concurrencia y composition root

- No bloquear threads de Velocity con Redis/network I/O o waits arbitrarios.
- Preferir fronteras asíncronas.
- Proteger generations, callbacks tardíos, deadlines y cleanup.
- Mantener `TheosferaProxy` enfocado en lifecycle/composición.
- Separar contracts, stores, coordinators, services, platform adapters y composition.
- Startup en `ProxyInitializeEvent`; teardown en `ProxyShutdownEvent`.

## Seguridad

Nunca versionar ni registrar:

- contraseñas;
- tokens;
- claves privadas;
- secretos HMAC;
- passwords de keystore/truststore;
- credenciales Redis productivas;
- token del Orchestration Gateway;
- credenciales/token Pterodactyl;
- datos sensibles de jugadores.

Nunca concatenar target references en shell commands.

## Validación

Antes de completar un cambio:

```text
git diff --check
focused tests
full test suite
clean build cuando corresponda
review del diff completo
runtime acceptance proporcional al riesgo
working tree clean
```

Un build exitoso no sustituye runtime testing para cambios operacionales.

## Punto exacto de continuación

Primero validar localmente el nuevo Proxy-side Pterodactyl Gateway adapter según el pre-runtime checkpoint.

Después:

```text
implementar/deploy durable Theosfera Orchestration Gateway
→ probar stale/replay/conflict contra Pterodactyl real
→ activar B.5 product cold path
→ retirar legacy local bootstrap authority del cold path
→ B.6 runtime matrix
→ final checkpoint + PROJECT_STATE consolidation
→ PR
```

No marcar B.6 ni abrir PR antes de runtime real. No saltar a Maintenance, Administrative Player Transfer o sistemas sociales salvo repriorización explícita del propietario.
