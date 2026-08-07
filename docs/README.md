# TheosferaProxy — Documentation Index

Este directorio conserva diseño, runbooks y evidencia de milestones de TheosferaProxy.

`PROJECT_STATE.md` en la raíz es la fuente principal para conocer el **estado vigente**. Los documentos de este directorio deben usarse para ampliar una decisión concreta, consultar evidencia runtime o recuperar historia técnica sin volver a inflar `PROJECT_STATE.md`.

## Orden recomendado de lectura

Para continuar desarrollo:

1. `../AGENTS.md`;
2. `../CONTRIBUTING.md`;
3. `../PROJECT_STATE.md`;
4. checkpoint del milestone activo;
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
  - milestone activo en `feature/distributed-backend-bootstrap`;
  - ownership Redis de bootstrap;
  - membership/bootstrap fencing;
  - TTL/renew lifecycle;
  - A.1–A.8;
  - siguiente frontera: Backend Orchestration Provider.

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

## Operación y estándares

- `BACKEND_CONTROL_CHANNEL_RUNBOOK.md`
  - provisioning y operación del Control Channel.

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
        > PROJECT_STATE vigente
        > checkpoint posterior
        > checkpoint histórico anterior
```

## Política documental a partir de la consolidación

- No añadir cronologías extensas a `PROJECT_STATE.md`.
- Un checkpoint específico puede conservar logs, hashes, matrices runtime y debugging.
- Al cerrar un WIP, renombrarlo o eliminarlo; no dejar nombres que indiquen un estado falso.
- Los `*_PROJECT_STATE.md` temporales deben eliminarse cuando su contenido haya sido consolidado en el archivo principal.
- No duplicar el mismo punto de reanudación en múltiples documentos activos.
- No eliminar checkpoints finales solo porque sean antiguos: son evidencia técnica y de runtime.
- Sí eliminar documentos temporales que hayan quedado completamente absorbidos por una fuente autoritativa posterior.

## Punto actual

A fecha de esta consolidación:

```text
main @ da0f065
feature/distributed-backend-bootstrap
Distributed Backend Bootstrap Foundation A.1–A.8: implementado y validado
PR del foundation: pendiente
siguiente milestone después del merge: Backend Orchestration Provider
```
