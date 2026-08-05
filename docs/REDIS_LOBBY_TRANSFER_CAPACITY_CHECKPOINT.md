# TheosferaProxy — Redis Lobby Transfer Capacity Checkpoint

Fecha: 2026-08-05
Rama: `feature/redis-lobby-transfer-capacity`
Base: `main @ 7c76a06`
HEAD: `56b56c6`

## Estado cerrado

`/hub` y `/lobby` ya usan la misma ruta distribuida de capacidad que `TRANSFER_REQUEST` mediante `DistributedPlayerTransferRetryCoordinator`.

Se preservan:
- `PlayerSessionLease` exacto;
- fencing;
- reserva Redis;
- release exact-match;
- retry alternativo;
- `TIMED_OUT` terminal;
- fail-closed;
- ausencia de fallback local silencioso.

## Git

La rama está:
- 4 commits ahead de `main`;
- 0 behind.

Commits:
1. `50d274d` — `refactor: route lobby service through distributed retry`
2. `a3e1b18` — `test: define distributed lobby transfer contract`
3. `96fe294` — `feat: wire lobby commands to distributed capacity runtime`
4. `56b56c6` — `test: avoid nested Mockito stubbing in lobby fixture`

## Validación automatizada

```text
git diff main...HEAD --check -> limpio
gradlew test -> BUILD SUCCESSFUL in 34s
gradlew clean build -> BUILD SUCCESSFUL in 12s
git status -> working tree clean
```

## Artefacto runtime

```text
TheosferaProxy-0.1.0-SNAPSHOT.jar
Size: 8,600,674 bytes
SHA-256: 5D1A676D9227637C99D7E636F71ED465584702713F9FFAD93432C89F1EA0B8AD
```

El hash y tamaño coincidieron entre build local, proxy-1 y proxy-2. Se crearon backups previos.

## Runtime validado

El usuario confirmó:
- `/hub` y `/lobby` same-target mantienen `Ya estás en el Lobby.`;
- Skyblock -> `/hub` funciona por la ruta distribuida;
- Skyblock -> `/lobby` usa la misma ruta;
- no quedan claves `theosfera:coordination:backend-capacity:*`;
- retry con `lobby-1` no disponible y `lobby-2` operativo funciona sin reservas residuales;
- Redis caído no produce fallback local;
- el Proxy conserva fail-closed/fencing;
- Redis fue restaurado y respondió `PONG`.

El arranque con Redis indisponible también confirmó que TheosferaProxy se fencea y rechaza autoridad distribuida en vez de degradar a estado local.

## Nota Redis

El warning `vm.overcommit_memory=1` queda como hardening del entorno WSL/Linux; no bloquea este milestone.

## Siguiente milestone exacto

Migrar el kick failover hacia capacidad Redis conservando:
- `RESOLVED`-only;
- ningún uso de `BOOTSTRAP_REQUIRED`;
- ningún intento de arrancar/reservar Lobby frío;
- fail-closed;
- ownership/fencing distribuido;
- sin fallback local silencioso.

`BackendCapacityReservationRegistry` todavía no puede eliminarse hasta cerrar esa migración.

Después: hardening de `/server` de Velocity como bypass de routing para todos, incluido Staff.

Rama sugerida:
`feature/redis-kick-failover-capacity`
