# Coordination Runtime Policy

This document defines the production coordination policy of TheosferaProxy after Redis proxy membership and Redis player-session runtime became authoritative.

## Production policy

The production runtime is distributed-required by contract.

This means that every responsibility which already has a distributed coordinator must use that coordinator and must never silently fall back to local authority.

At the current milestone, the distributed responsibilities are:

- proxy membership;
- authenticated player-session ownership and renewal.

If Redis coordination cannot initialize, membership cannot be acquired, or the required distributed session runtime cannot be composed, the operational surface must not activate.

There is no supported production switch that downgrades these responsibilities to local-only coordination.

## Why there is no CoordinationMode toggle

The historical `CoordinationMode` enum exposed `LOCAL` and `DISTRIBUTED_REQUIRED`, but it had no production or test consumers.

Keeping a `LOCAL` production option would conflict with the runtime that is already fail-closed on Redis membership and player-session authority. It would also reintroduce a path toward split-brain and duplicate authenticated sessions when multiple proxies exist.

For that reason the unused enum is removed instead of wiring a configuration toggle with unsafe or misleading semantics.

`CoordinationState` remains the operational lifecycle/state model:

- `STARTING`;
- `HEALTHY`;
- `DEGRADED`;
- `FENCED`;
- `STOPPING`.

`CoordinationState` describes the health and lifecycle of the distributed coordination layer. It does not claim that every proxy responsibility has already been migrated to Redis.

## Responsibilities that remain local

The following state intentionally remains local to each Proxy process at this milestone:

- backend authenticated identities;
- backend health and freshness;
- pending backend pings;
- player presence;
- pending transfers;
- temporary capacity reservations;
- bootstrap reservations;
- pending kick failovers;
- Velocity connection callbacks and `ConnectionRequest` operations;
- current administrative status snapshots.

Their local status is not weakened or hidden by the production distributed-required policy. Each future distributed migration must define its own ownership, TTL, atomicity, fencing, failure behavior and observability before replacing local authority.

## Admission invariant

Operational protocol, commands and listeners require the distributed player-session runtime to be composed first.

The current startup sequence remains:

```text
ProxyInstanceIdentity
→ Redis coordination runtime
→ acquire proxy membership
→ CoordinationState HEALTHY
→ create RedisPlayerSessionCoordinator
→ compose session release/disconnect/renewal services
→ compose protocol and commands
→ activate operational surface
```

Failure before the distributed runtime is ready is fail-closed. TheosferaProxy must not activate a local-only operational surface as fallback.

## Scope

This policy change does not distribute presence, transfers, capacity, bootstrap or backend health. It only removes the obsolete mode abstraction and makes the already-active production policy explicit.

The next distributed milestone should migrate one additional global responsibility at a time, preserving the same fail-closed and exact-ownership principles.
