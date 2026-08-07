# Backend Control Channel — Increment D Runtime Checkpoint

Status: **RUNTIME PASS / ready for PR closure**

Date: 2026-08-06

Branch: `feature/backend-control-health`

## Objective

Validate that backend health no longer depends on Minecraft Plugin Messaging or a connected player carrier.

Increment D migrates backend-level `PING`/`PONG` health traffic to the persistent authenticated TLS/HMAC backend control channel while preserving pending-request correlation, freshness semantics and fail-closed behavior.

## Automated gates

### TheosferaProxy

Final functional lifecycle fix validated at:

```text
b56cc8c3adda2eea2a81b08a27b7a1837497bfa6
```

Validation:

```text
git diff --check origin/main...HEAD                                      PASS
./gradlew.bat test --tests "*TheosferaProxyLifecycleTest" \
  --tests "*BackendControlRuntimeTest" --no-daemon                       PASS
./gradlew.bat test --no-daemon                                           PASS
./gradlew.bat clean build --no-daemon                                    PASS
working tree clean                                                        PASS
```

Frozen runtime artifact used for final Increment D deployment:

```text
TheosferaProxy-0.1.0-SNAPSHOT.jar
SHA-256: 97F38D1CC9C588050EA49F2AF6662093BE15293594AE778D5F1E57E5FF5D81AF
```

### TheosferaCore

Final branch code validated at:

```text
475db63f378c434f28904dbc95ea26d3d9d0b574
```

Validation:

```text
git diff --check origin/main...HEAD     PASS
./gradlew.bat test --no-daemon          PASS
./gradlew.bat clean build --no-daemon   PASS
working tree clean                       PASS
```

Frozen runtime artifact deployed to `auth-1`, `lobby-1`, `lobby-2` and `skyblock-1`:

```text
TheosferaCore-0.1.0-SNAPSHOT.jar
SHA-256: 92475EE093D2858A8201D031C9B6DE5AEA63608CC39C6285A08C8D6DAF674057
```

## TLS/HMAC provisioning used by the runtime

Development TLS was reprovisioned after the previous interactive keystore/truststore passwords were unavailable. Existing per-backend HMAC secrets were preserved; only development TLS passwords/material were regenerated.

New Proxy certificate:

```text
alias: theosfera-control
key: RSA 3072
signature: SHA256withRSA
SAN: 127.0.0.1, localhost
EKU: serverAuth
certificate SHA-256 fingerprint:
6A:79:6E:7B:0E:E7:2E:A3:E8:18:8A:29:1F:18:9A:D9:75:1A:5A:8E:78:D6:D8:57:06:E6:98:BE:C1:5C:D2:74
```

Core truststore alias:

```text
proxy-1-control
```

The Proxy-side truststore artifact and all four backend copies matched exactly:

```text
SHA-256: 7029A16ECF1448566A07CD0142B8C0C1EB89FFCB78205198E1C82882DCE9EF10
```

No secret values, private keys or passwords are committed by this checkpoint.

## Configuration validation

All four backend `config.yml` files were verified structurally before runtime:

```text
NetworkRoot          True
ControlInsideNetwork True
ControlAtRoot        False
ControlEnabled       True
Proxy1Nested         True
HostNested           True
PortNested           True
```

Backends:

```text
auth-1
lobby-1
lobby-2
skyblock-1
```

This confirms `control:` is correctly nested under `network:` and targets `proxy-1` at `127.0.0.1:25590`.

## Runtime acceptance — zero players

The network was started with **zero Minecraft players connected**.

Proxy control authentication evidence:

```text
Backend auth-1 autenticado en control channel (generation 1).
Backend lobby-1 autenticado en control channel (generation 2).
Backend lobby-2 autenticado en control channel (generation 3).
Backend skyblock-1 autenticado en control channel (generation 4).
```

After multiple health cycles, `theosferaproxy status` reported:

```text
auth-1      [AUTH]      HEALTHY   connected players: 0
lobby-1     [LOBBY]     HEALTHY   connected players: 0
lobby-2     [LOBBY]     HEALTHY   connected players: 0
skyblock-1  [SKYBLOCK]  HEALTHY   connected players: 0
```

Fresh health activity was observed at approximately three seconds since the last valid response.

### Acceptance result

```text
0 players + authenticated persistent control session + repeated correlated PONG -> HEALTHY
```

**PASS**

No Minecraft player carrier was required for backend health.

## Runtime acceptance — session loss, staleness and reconnect

`lobby-2` was stopped while the remaining network stayed online.

Proxy evidence:

```text
[19:08:49] Backend lobby-2 perdio su sesion de control (generation 3).
```

At `19:09:26`, after the freshness window elapsed:

```text
lobby-2 [LOBBY] — STALE
connected players: 0
last health: 37 s ago
```

At the same time:

```text
auth-1      HEALTHY
lobby-1     HEALTHY
skyblock-1  HEALTHY
```

`lobby-2` was then restarted with the same logical backend identity and its own HMAC secret.

Proxy evidence:

```text
[19:10:16] Backend lobby-2 autenticado en control channel (generation 5).
```

The new generation is strictly newer than the disconnected generation 3.

At `19:10:55`:

```text
auth-1      HEALTHY   last health: 1 s
lobby-1     HEALTHY   last health: 1 s
lobby-2     HEALTHY   last health: 1 s
skyblock-1  HEALTHY   last health: 1 s
```

All four remained at zero connected players.

### Acceptance result

```text
current generation 3
    -> session loss
    -> health freshness expires
    -> STALE
    -> reconnect authenticates generation 5
    -> fresh control PING/PONG
    -> HEALTHY
```

**PASS**

This validates loss of current control ownership, freshness expiry and recovery on a newer authenticated generation. Unit coverage separately verifies stale-generation PONG fencing and replay/unmatched correlation rejection.

## Expected legacy status during Increment D

`theosferaproxy status` still displayed:

```text
Autenticado: No
```

for empty backends during this runtime.

This does **not** indicate control-channel authentication failure. The control-authentication logs above prove successful TLS/HMAC authentication and health is fresh over that channel.

The displayed `Autenticado` field still reflects the legacy backend identity registry populated through the player-carried `BACKEND_HELLO` flow. Retirement of that carrier handshake is explicitly deferred to **Increment E**.

Therefore the Increment D boundary is:

```text
backend control TLS/HMAC authentication  PASS
backend PING/PONG health                  PASS
zero-player health                       PASS
legacy player-carried BACKEND_HELLO      still present until Increment E
```

## Increment D final classification

```text
DESIGN       PASS
CODE         PASS
TEST/BUILD   PASS
DEPLOYMENT   PASS
TLS/HMAC     PASS
ZERO-PLAYER  PASS
HEALTH       PASS
RECONNECT    PASS
RUNTIME      PASS
```

Increment D is technically complete and ready for Pull Request / merge closure.

## Operational documents added during closure

The branch also introduces durable operational guidance so future provisioning does not depend on chat history:

```text
docs/BACKEND_CONTROL_CHANNEL_RUNBOOK.md
docs/INSTANCE_PROVISIONING_POLICY.md
.agents/skills/allow-backend/SKILL.md
.agents/skills/pending-works-theosfera/SKILL.md
```

The provisioning policy records the intended future instance convention, including derived backend preference by ordinal, persistent-mode capacity policy, and globally unique active Proxy logical names.

These operational conventions do not change Increment D runtime semantics.

## Next milestone

**Increment E — carrier-handshake retirement**

Goal:

- retire player-carried backend identity handshake where safe;
- move backend identity authority fully onto authenticated control sessions;
- retain Minecraft Plugin Messaging only for player-scoped messages;
- preserve authentication, transfer, destination-ready and presence flows;
- validate that an empty backend is both control-authorized and healthy without a player carrier.
