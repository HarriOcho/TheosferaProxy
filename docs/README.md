# TheosferaProxy — Documentation Index

Este directorio conserva diseño, runbooks y evidencia de milestones de TheosferaProxy.

`PROJECT_STATE.md` en la raíz es la fuente principal para conocer el **estado fusionado vigente**. Durante una rama activa, el checkpoint/diseño más reciente del milestone complementa ese estado hasta su consolidación final.

## Orden recomendado de lectura

Para continuar desarrollo:

1. `../AGENTS.md`;
2. `../CONTRIBUTING.md`;
3. `../PROJECT_STATE.md`;
4. checkpoint/diseño del milestone activo;
5. documentos específicos de la frontera que se va a modificar.

## Estado y arquitectura distribuida

- `DISTRIBUTED_COORDINATION_BOUNDARY.md`
  - frontera conceptual de coordinación distribuida;
  - responsabilidades temporales vs persistentes;
  - ownership y consistencia esperados.

- `REDIS_RUNTIME_CHECKPOINT.md`
  - lifecycle Redis;
  - Proxy membership;
  - player-session ownership;
  - fencing y política fail-closed.

- `PLAYER_PRESENCE_RUNTIME_CHECKPOINT.md`
  - player presence Redis productiva;
  - publish/renew/remove;
  - orden presence -> session cleanup.

- `REDIS_BACKEND_CAPACITY_DESIGN.md`
  - occupancy global;
  - capacity reservations;
  - session fencing;
  - rollout de capacidad distribuida.

- `DISTRIBUTED_BACKEND_BOOTSTRAP_FOUNDATION_CHECKPOINT.md`
  - foundation A.1–A.8 fusionado mediante PR `#74`;
  - ownership Redis de bootstrap;
  - membership/bootstrap fencing;
  - TTL/renew lifecycle;
  - frontera previa al Backend Orchestration Provider.

- `BACKEND_ORCHESTRATION_PROVIDER_DESIGN.md`
  - diseño base del milestone de orchestration;
  - provider contracts;
  - trusted target mapping;
  - atomic fenced actuator strategy;
  - startup lifecycle y separación de readiness.

- `BACKEND_ORCHESTRATION_PROVIDER_PRE_RUNTIME_CHECKPOINT.md`
  - checkpoint incremental autoritativo de la rama activa;
  - B.1–B.5 foundation validados localmente;
  - readiness por current Control Channel + fresh HEALTHY;
  - invalidación de health/PING al cambiar generación de Control Channel;
  - cold-start coordinator y schedulers Velocity;
  - Pterodactyl seleccionado como process plane;
  - Proxy -> Orchestration Gateway adapter implementado y pendiente de gate local;
  - product cold path todavía sin reemplazar;
  - B.6 pendiente del Gateway real + runtime.

- `PTERODACTYL_ORCHESTRATION_GATEWAY_DESIGN.md`
  - arquitectura concreta `Proxy -> Gateway -> Pterodactyl Panel/Wings`;
  - por qué el Proxy no debe llamar directamente al power API;
  - fencing/replay/conflict requeridos en Gateway;
  - HTTPS/token/target mapping;
  - matriz previa a activación productiva.

## Runtime acceptance y superficies productivas

- `REDIS_LOBBY_TRANSFER_CAPACITY_CHECKPOINT.md`
  - `/hub` y `/lobby` sobre capacity Redis.

- `REDIS_KICK_FAILOVER_RUNTIME_CHECKPOINT.md`
  - kick failover distribuido;
  - `RESOLVED`-only;
  - capacity/handoff/exact release.

- `LOBBY_INSTANCE_SWITCHING_RUNTIME_CHECKPOINT.md`
  - `/lobby switch` y `/hub switch`;
  - exclusión del Lobby actual;
  - runtime multi-instance.

- `RAW_SERVER_COMMAND_HARDENING_RUNTIME_CHECKPOINT.md`
  - retiro de raw Velocity `/server` como bypass para jugadores.

- `BACKEND_CONTROL_CHANNEL_INCREMENT_E_POST_MERGE.md`
  - Protocol v2 coordinado;
  - TLS/HMAC control identity autoritativa;
  - retiro de `BACKEND_HELLO`;
  - zero-player identity/health;
  - reconnect/fencing y runtime final.

## Diseño futuro aprobado para recordar

- `ADMINISTRATIVE_PLAYER_TRANSFER_DESIGN.md`
  - raw Velocity `/send` debe dejar de ser una ruta administrativa de movimiento;
  - `/theosfera send <player> <BackendType>` como superficie oficial futura;
  - selección automática de instancia por policy/preference/health/capacity;
  - autenticación y `PlayerSessionLease` exactos como precondición absoluta;
  - sesión inconsistente -> reject + disconnect para forzar revalidación Auth/nLogin;
  - ejecución cross-proxy fenced;
  - TAB y descubribilidad condicionados por permisos;
  - ningún auth bypass administrativo.

Este documento registra una feature futura; no significa que `/theosfera send` o el hardening de raw `/send` ya estén implementados.

## Operación y estándares

- `BACKEND_CONTROL_CHANNEL_RUNBOOK.md`
  - provisioning y operación del Control Channel.

- `INSTANCE_PROVISIONING_POLICY.md`
  - naming de instancias;
  - capacity/preference derivadas;
  - unicidad de Proxy identity;
  - provisioning baseline.

- `THEOSFERA_VISUAL_MESSAGING_STANDARD.md`
  - estándar visual/mensajería de Theosfera para superficies del Proxy.

## Checkpoints históricos

Los checkpoints anteriores permanecen válidos como **evidencia histórica de su momento**, pero pueden contener estados que fueron superseded después.

Ejemplos de afirmaciones históricas que ya no deben tomarse como estado vigente:

- Redis todavía no está conectado al runtime;
- player sessions son únicamente locales;
- player presence es local;
- capacity productiva es local;
- `BACKEND_HELLO` sigue siendo identidad backend;
- PING/PONG de health viaja por Plugin Messaging;
- Lobby Instance Switching está pendiente;
- raw `/server` sigue disponible;
- distributed backend bootstrap no tiene foundation.

Ante una contradicción:

```text
código fusionado / rama activa validada
        > checkpoint activo más reciente
        > PROJECT_STATE fusionado
        > checkpoint histórico anterior
```

## Política documental

- No añadir cronologías extensas a `PROJECT_STATE.md`.
- Un checkpoint específico puede conservar logs, hashes, matrices runtime y debugging.
- Al cerrar un WIP, renombrarlo o eliminarlo; no dejar nombres que indiquen un estado falso.
- Los `*_PROJECT_STATE.md` temporales deben eliminarse cuando su contenido haya sido consolidado en el archivo principal.
- No duplicar el mismo punto de reanudación en múltiples documentos activos.
- No eliminar checkpoints finales solo porque sean antiguos: son evidencia técnica y de runtime.
- Sí eliminar documentos temporales que hayan quedado completamente absorbidos por una fuente autoritativa posterior.

## Punto actual

Estado fusionado:

```text
main @ ddc082319243da621d4e5364d4c4957f8d088b0d
PR #74: feat: add distributed backend bootstrap foundation
Distributed Backend Bootstrap Foundation A.1–A.8: MERGED
```

Rama activa:

```text
feature/backend-orchestration-provider
```

Estado exacto de continuidad:

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

Leer primero:

```text
docs/BACKEND_ORCHESTRATION_PROVIDER_PRE_RUNTIME_CHECKPOINT.md
docs/PTERODACTYL_ORCHESTRATION_GATEWAY_DESIGN.md
```

Regla central:

```text
bootstrap ownership
    != provider ACCEPTED
    != process running
    != TLS/HMAC control authentication
    != backend HEALTHY
    != capacity reserved
```

Concrete process path selected:

```text
TheosferaProxy
→ HTTPS Theosfera Orchestration Gateway
→ Pterodactyl Panel/Wings
→ backend process/container
```

El Gateway debe preservar atomic fencing + replay/idempotency. El Proxy no obtiene credenciales de Pterodactyl y no existe fallback local silencioso.
