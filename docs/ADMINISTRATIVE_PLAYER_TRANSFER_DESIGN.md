# Administrative Player Transfer — Planned Design

> Feature plan only. This document does **not** declare the command implemented.

## Goal

Replace raw Velocity `/send` with an official Theosfera administrative transfer surface that preserves authentication, distributed session ownership, routing policy, health, preference, Redis capacity, fencing, presence handoff and exact cleanup.

Official surface planned:

```text
/theosfera send <player> <BackendType>
```

Examples:

```text
/theosfera send HarriOcho LOBBY
/theosfera send HarriOcho SKYBLOCK
```

The administrator expresses the desired **backend type / modality**, not a physical instance name. Theosfera chooses the best eligible backend using the same policy and distributed routing primitives used by official player transfers.

## Raw `/send` policy

Raw Velocity `/send` must be retired as an administrative movement bypass, analogous to the existing raw `/server` hardening for players.

Expected policy:

```text
/send ...
→ forbidden as an operational Theosfera transfer route

/theosfera send <player> <BackendType>
→ official administrative route
```

The implementation must determine the exact Velocity command surface that needs unregister/guard behavior without breaking non-conflicting commands.

There is no staff shortcut that is allowed to bypass Theosfera transfer invariants.

## No physical backend selection in ordinary admin UX

The ordinary command must not expose or tab-complete physical names such as:

```text
lobby-1
lobby-2
skyblock-1
```

Instead:

```text
BackendType requested
        ↓
static policy
        ↓
live authenticated control identity
        ↓
fresh HEALTHY state
        ↓
preference / global occupancy
        ↓
Redis capacity
        ↓
best eligible backend
```

Physical backend targeting, if ever required for diagnostics, must be designed as a separate privileged operational tool and must still preserve all security/ownership checks. It is not part of this feature.

## Authentication is an absolute precondition

Administrative permission does not make an unauthenticated player transferable.

Before any routing, capacity reservation, bootstrap or connection request, the current player connection must have a provable authenticated Theosfera session backed by the exact current `PlayerSessionLease`.

Conceptually:

```text
locate current player session
        ↓
prove exact Proxy/session ownership
        ↓
prove the current connection is authenticated
        ↓
only then resolve a destination
```

If authentication cannot be proven or local/global state is inconsistent:

```text
NO transfer
NO capacity reservation
NO bootstrap
NO local fallback
```

The suspicious/inconsistent connection must be disconnected in a controlled way so that reconnecting forces the normal Auth/nLogin flow to establish a fresh authenticated generation and fresh session lease.

The player-facing reason should be neutral, for example:

```text
Tu sesión necesita volver a validarse. Reconéctate para continuar.
```

The administrative result should report that the session could not be authenticated and was forced through revalidation, without exposing sensitive infrastructure details.

There must be **no** permission such as an auth-bypass permission for this command. Console, owner, operator or staff authority cannot turn an unauthenticated connection into an authenticated one.

The Proxy does not need to treat a direct nLogin query as its distributed source of authority. The existing Theosfera authentication flow remains the security boundary:

```text
Auth / nLogin confirms authentication
        ↓
PLAYER_AUTHENTICATED
        ↓
Proxy validates authorized Auth source/current connection
        ↓
PlayerSessionLease acquired and bound
        ↓
PLAYER_AUTHENTICATED_ACK
```

If the Proxy cannot prove that chain for the current connection, the connection is not eligible for protected movement.

## Cross-proxy ownership

The command must work when the administrator executes it on a Proxy different from the one currently owning the player.

Example:

```text
admin command @ proxy-1
player session owner = proxy-2
```

`proxy-1` must not perform a local Velocity side effect for a player it does not own.

A future distributed administrative transfer instruction must bind at least to the exact current player-session authority, conceptually including:

```text
playerId
expected proxyName
expected incarnationId
expected PlayerSessionLease fencing token
requestId
requested BackendType
```

The owner Proxy revalidates the exact session before performing any side effect.

If the player reconnects/migrates and obtains a newer session fencing token before execution, the old instruction is stale and must become a no-op/fail-closed result.

```text
instruction for session fencing N
new session fencing N+1 exists
→ stale instruction cannot move N+1
```

## Transfer invariants

The official administrative path must reuse or compose the same authorities as normal Theosfera movement:

- exact authenticated `PlayerSessionLease`;
- exact Proxy owner/incarnation/fencing;
- static backend policy;
- current authenticated Backend Control Session;
- fresh backend health;
- preference/load ordering;
- global Redis occupancy;
- Redis capacity reservation;
- pending transfer correlation;
- Velocity `ConnectionRequest` only on the owning Proxy;
- `PLAYER_SERVER_READY`;
- Redis presence handoff;
- exact capacity cleanup;
- retry semantics appropriate to the requested operation;
- fail-closed behavior on unavailable required coordination.

No direct `ConnectionRequest` from the command handler is acceptable.

## Interaction with cold bootstrap

After Backend Orchestration Provider exists, an explicit administrative send may be allowed to bootstrap a cold target selected by routing policy.

That future flow would be:

```text
admin intent: BackendType
        ↓
resolve candidate
        ↓
BOOTSTRAP_REQUIRED
        ↓
distributed bootstrap ownership
        ↓
Backend Orchestration Provider
        ↓
current TLS/HMAC control authentication
        ↓
fresh PONG / HEALTHY
        ↓
re-resolve / revalidate
        ↓
Redis capacity reservation
        ↓
normal transfer lifecycle
```

This does **not** change kick failover policy. Kick failover remains `RESOLVED`-only and must not start a cold backend.

## Command visibility and permissions

TheosferaProxy administrative commands should follow a unified stealth-discovery policy.

For every root/subcommand:

```text
has permission
→ visible in TAB
→ permitted arguments/subcommands are suggested
→ execution allowed

no permission
→ absent from TAB
→ protected subcommands/arguments absent from suggestions
→ manual execution behaves like unknown/nonexistent command
```

Do not reveal protected administrative surfaces with a dedicated "you do not have permission" response when the desired security UX is invisibility.

Visibility must be evaluated at the subcommand level, not only at the root command.

For `/theosfera send` specifically:

- unauthorized users do not discover `send` through TAB;
- authorized staff may receive player suggestions;
- after a player, TAB suggests only administratively valid `BackendType` values;
- physical backend names are not suggested;
- `AUTH` should not automatically become an ordinary administrative target merely because it exists in the protocol; Auth is a security boundary and requires an explicit design decision if ever exposed.

## Planned surface summary

```text
raw /server                         → forbidden for player movement
raw /send                           → planned forbidden admin bypass
/lobby /hub                         → official player routing
/lobby switch /hub switch           → official instance switching
/theosfera transfer <BackendType>   → official self transfer
/theosfera send <player> <BackendType>
                                    → planned official admin transfer
```

The design goal is a single movement authority: **TheosferaProxy coordinated transfer primitives**, not raw Velocity movement commands.

## Scheduling

This feature is recorded now so it is not lost, but it is **not the active technical milestone**.

Current dependency order:

```text
Distributed Backend Bootstrap Foundation ✅
Backend Orchestration Provider           ← active next milestone
Administrative Player Transfer           ← planned after orchestration / admin tooling boundary
Operational State / Drain
Maintenance / Access policy
```

Implementation must be re-reviewed against the current repository state when its milestone begins.