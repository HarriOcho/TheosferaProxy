# Backend Control Channel — Increment E Design

Status: design baseline

Date: 2026-08-06

Primary branches:

```text
TheosferaProxy: feature/backend-control-identity
TheosferaCore:  feature/backend-control-identity
```

## Objective

Retire the player-carried backend identity handshake (`BACKEND_HELLO` / `BACKEND_HELLO_ACK`) and make the authenticated persistent control session the live authority for backend identity.

Increment E must preserve Plugin Messaging only for traffic that is genuinely scoped to a Minecraft player, including authentication acknowledgement, player readiness/presence and transfer traffic.

## Starting point after Increment D

Increment D already proves backend identity cryptographically during control authentication:

```text
Core -> Proxy
CONTROL_AUTH_RESPONSE
  backendName
  backendType
  HMAC proof
```

The Proxy validates the requested backend name/type against `BackendAuthorizationPolicy`, verifies the backend-specific HMAC proof and only then creates `BackendControlSession` with a monotonically increasing generation.

Therefore `BACKEND_HELLO` currently duplicates identity proof after control authentication and additionally depends on a connected Minecraft player carrier.

## Target boundary

```text
BACKEND LEVEL
Core ==============================> Proxy
     persistent TLS/HMAC control

identity      -> control session
health        -> control PING/PONG
session epoch -> control generation

PLAYER LEVEL
Core ------------------------------> Proxy
       Plugin Messaging carrier

PLAYER_AUTHENTICATED
PLAYER_SERVER_READY
TRANSFER_REQUEST
and their player-scoped replies
```

No player is required for backend identity or backend health.

## Security invariants

1. A backend is live-authorized only while at least one current authenticated control session exists for its exact logical backend name and authorized backend type.
2. Losing the current/last authenticated control session removes live backend authorization for player-scoped messages until a valid reconnect succeeds.
3. An OLD control generation may not revoke, replace or mutate authorization belonging to a newer generation.
4. Plugin Messaging must never become a fallback for backend-level identity or health.
5. Backend policy remains the source of allowed backend name/type pairs.
6. TLS alone is not backend authentication; HMAC-backed control authentication remains required.
7. A source server name carried by Velocity must match the backend identity associated with the current control session before player-scoped protocol traffic is accepted.
8. Control-disabled or control-unavailable runtime remains fail-closed for backend authorization.
9. No secret material is logged or moved into Plugin Messaging.
10. Redis remains Proxy-to-Proxy coordination infrastructure; TheosferaCore does not use Redis for backend identity.

## Architectural decision: one live identity source

Increment E must not copy a successful control identity into a second long-lived registry that can remain authorized after the control connection disappears.

The live `BackendControlSessionRegistry` is the source of truth for current backend identity.

A read-only backend identity abstraction may project current session identities for existing routing/authorization consumers, but that projection must derive from current control sessions instead of storing an independent authorization lifetime.

This ensures:

```text
control authenticated -> backend authorized
control lost          -> backend unauthorized
control reconnect     -> backend authorized on new generation
```

## Status semantics

`/theosferaproxy status` must change meaningfully in E:

```text
Autenticado: Sí
```

means that the backend currently owns a valid authenticated control session whose identity matches policy.

It must no longer mean that a historical player-carried `BACKEND_HELLO` was accepted.

Expected zero-player state after E:

```text
auth-1       HEALTHY | Autenticado: Sí | players=0
lobby-1      HEALTHY | Autenticado: Sí | players=0
lobby-2      HEALTHY | Autenticado: Sí | players=0
skyblock-1   HEALTHY | Autenticado: Sí | players=0
```

## Migration plan

### E1 — Proxy live control identity authority

Introduce a read-only identity provider backed by current `BackendControlSessionRegistry` sessions.

Migrate Proxy consumers that currently depend on `BackendIdentityRegistry` for authorization/routing to the control-backed identity provider, including at minimum:

- `BackendMessageAuthorizer`;
- operational status snapshots;
- transfer target resolution;
- lobby transfer routing;
- kick failover routing;
- transfer request authorization/routing.

During E1 the legacy `BACKEND_HELLO` handler may remain temporarily for compatibility with the still-unmigrated Core, but it must no longer be authoritative for normal routing or player-scoped authorization.

Acceptance:

```text
control session present + no BACKEND_HELLO authority -> player-scoped source can be authorized
control session absent -> player-scoped source rejected fail-closed
status authenticated derives from control session
OLD control disconnect cannot remove NEW control identity
```

### E2 — Core control authorization gate

Remove `BackendHandshakeService` as the backend authorization gate.

Player-scoped services continue to require a real online player carrier for Plugin Messaging transport, but their backend-level authorization check comes from the control client runtime.

Requirements:

- `PLAYER_AUTHENTICATED` requires live control authorization plus an online carrier;
- `PLAYER_SERVER_READY` requires live control authorization plus an online carrier;
- `TRANSFER_REQUEST` requires live control authorization plus an online carrier;
- no `BACKEND_HELLO` is emitted on player join/channel registration;
- no `BACKEND_HELLO_ACK` is required before player-scoped traffic;
- transition from zero authorized Proxy endpoints to one or more authorized endpoints triggers readiness handling for already-online playable-backend players on the Bukkit primary thread;
- loss of the final authorized Proxy endpoint fails closed for new player-scoped publications until reconnect.

Multiple configured Proxy endpoints remain supported: backend authorization is available while at least one intended Proxy control session is authenticated.

### E3 — Retire legacy wire surface

After Proxy and Core operate without the carrier handshake:

- remove `BackendHelloMessageHandler` and legacy handshake state/classes/tests;
- remove Plugin Messaging dispatch/authorization exceptions for `BACKEND_HELLO` / `BACKEND_HELLO_ACK`;
- determine whether the now-unused protocol message constants/payload registrations should be removed in the same coordinated release or retained temporarily only for explicit compatibility. The default objective is removal once no current artifact depends on them.

If protocol contracts are removed, use a dedicated `TheosferaProtocol` feature branch and update Proxy/Core against the coordinated Protocol revision before merge.

## Runtime acceptance

Final Increment E runtime acceptance must include all of the following.

### Zero-player identity

Start Proxy and all approved backends without connecting a Minecraft player.

Expected:

```text
all backends Autenticado: Sí
all backends HEALTHY
connected players: 0
no BACKEND_HELLO required
```

### Control loss

Stop one backend or break its control connection and wait for session removal/freshness expiry.

Expected:

```text
Autenticado: No
health eventually STALE
player-scoped messages from that backend are rejected while control authorization is absent
other backends remain unaffected
```

### Reconnect / generation fencing

Restart the backend.

Expected:

```text
new authenticated generation > old generation
Autenticado returns to Sí
health returns to HEALTHY
OLD generation cleanup cannot revoke NEW generation
```

### Player-scoped regression

After zero-player identity is proven, connect a real player and validate the existing flows:

```text
AUTH -> LOBBY
PLAYER_AUTHENTICATED / ACK
PLAYER_SERVER_READY
/hub or /lobby transfer path
kick failover path as applicable
```

No backend identity or health packet may require that player as a carrier.

## Completion rule

Increment E is CLOSED only when:

```text
DESIGN          PASS
PROXY CODE      PASS
CORE CODE       PASS
PROTOCOL        PASS or explicitly NO CHANGE
TEST/BUILD      PASS
DEPLOYMENT      PASS
ZERO-PLAYER ID  PASS
CONTROL LOSS    PASS
RECONNECT       PASS
PLAYER FLOWS    PASS
CHECKPOINT      PASS
CI              PASS
MERGE           PASS
```

The removal of the player carrier is not considered complete merely because `BACKEND_HELLO` stops being emitted; the live control session must actually be the authorization source used by operational routing and player-scoped message validation.
