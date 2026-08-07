# Backend Orchestration Provider — Design

## Status

Active milestone started from:

```text
main @ ddc082319243da621d4e5364d4c4957f8d088b0d
feat: add distributed backend bootstrap foundation (#74)
```

Active branch:

```text
feature/backend-orchestration-provider
```

This document defines the provider boundary before wiring any real process-start side effect.

## Problem

Distributed Backend Bootstrap Foundation answers:

> Which Proxy generation currently has the distributed right to coordinate startup for this exact backend target?

It intentionally does **not** answer:

> How is the backend process actually started?

A new provider boundary is required between fenced bootstrap ownership and any platform-specific side effect such as Docker, systemd, a hosting panel, Kubernetes, a cloud API or another future orchestrator.

## Core invariant

```text
bootstrap ownership
    != process running
    != TCP open
    != TLS/HMAC control authenticated
    != backend HEALTHY
    != capacity reserved
```

The provider only handles the process-start instruction boundary.

Backend readiness remains authoritative through the current authenticated Backend Control Channel followed by fresh PONG/HEALTHY evidence.

## Initial provider contract

The first increment exposes an asynchronous operation conceptually equivalent to:

```text
requestStart(exact BackendBootstrapLease)
    → BackendStartResult
```

The request carries the exact distributed bootstrap lease because that lease contains:

- target backend name;
- bootstrap requestId;
- playerId that triggered the operation;
- exact owning `ProxyMembershipLease`;
- monotonic bootstrap fencing token.

The provider must receive this authority. Passing only a backend name would make it impossible for the side-effect boundary to distinguish a current bootstrap owner from a stale one.

## Required provider fencing semantics

A concrete provider/adapter must protect start side effects against stale bootstrap generations.

At minimum, for each target backend it must be able to reject a request whose bootstrap fencing token is older than the latest accepted authority for that target.

Conceptually:

```text
accepted target=lobby-2 fencing=41
later request target=lobby-2 fencing=40
→ STALE_AUTHORITY
→ MUST NOT start process
```

An exact replay of the same authoritative operation must be idempotent:

```text
same target
same requestId
same owner membership
same bootstrap fencing
→ safe replay
→ no duplicate start side effect
```

The same fencing token combined with conflicting immutable operation identity is invalid and must fail closed.

The provider implementation may use its own durable/transactional mechanism, an external platform primitive or another approved fencing adapter. The interface does not authorize an implementation that merely ignores the fencing token.

## Start result semantics

Initial result statuses:

```text
ACCEPTED
STALE_AUTHORITY
CONFLICT
TARGET_NOT_FOUND
PROVIDER_UNAVAILABLE
REJECTED
```

Meaning:

### `ACCEPTED`

The provider accepted the start instruction or an exact idempotent replay.

It means only:

```text
start side-effect instruction accepted
```

It does **not** prove process state, port reachability, control authentication or health.

### `STALE_AUTHORITY`

The provider can prove a newer bootstrap authority already supersedes the request.

Caller must stop treating the current bootstrap operation as capable of issuing process side effects.

### `CONFLICT`

The provider observed incompatible immutable operation identity for an authority/replay that should have been exact.

This is fail-closed and must never be converted to `ACCEPTED`.

### `TARGET_NOT_FOUND`

The target name is not configured/mappable by the provider.

Static backend policy in Theosfera remains a separate authority. Provider mappings must not allow arbitrary untrusted target names to become process commands.

### `PROVIDER_UNAVAILABLE`

Expected temporary inability to reach/use the orchestration platform.

The bootstrap ownership lifecycle may continue renewing while a bounded retry policy is later defined, but no process side effect can be assumed to have succeeded.

### `REJECTED`

The provider deliberately refused the request for a non-success condition not represented above.

Provider-specific sensitive details should remain in controlled logs/observability rather than being exposed to players.

## Exceptional completion

Expected operational outcomes should use explicit statuses where practical.

Structural corruption, impossible provider state or contract violations should complete exceptionally rather than being silently flattened into a successful/ambiguous result.

No exceptional path authorizes fallback to an unfenced process-start mechanism.

## Provider-neutral architecture

TheosferaProxy must not hard-code Docker/systemd/panel/Kubernetes assumptions into transfer/routing services.

Desired shape:

```text
product bootstrap flow
        ↓
BackendOrchestrationProvider
        ↓
platform adapter
        ↓
actual process platform
```

Examples of future adapters could include:

```text
DockerBackendOrchestrationProvider
SystemdBackendOrchestrationProvider
PanelBackendOrchestrationProvider
```

Those names are examples only; no provider technology is selected by this design.

## No readiness polling authority from provider

Even if a concrete orchestration platform can report `RUNNING`, that process-layer observation is not enough to route a player.

Required post-start readiness chain remains:

```text
provider ACCEPTED
        ↓
wait current TLS/HMAC control authentication for target
        ↓
wait fresh PONG / HEALTHY
        ↓
re-resolve / revalidate target
        ↓
reserve Redis capacity
        ↓
Velocity ConnectionRequest
```

A provider-reported process state can be useful for diagnostics/timeouts later but cannot replace Control Channel authority.

## Capacity ordering

Current product capacity reservation TTL is approximately 20 seconds and is designed for already-ready backends.

True cold startup must not reserve capacity before boot and hold it for an arbitrary startup duration.

Future product flow:

```text
acquire bootstrap ownership
→ request provider start
→ renew bootstrap ownership while starting
→ wait control auth
→ wait HEALTHY
→ revalidate routing
→ reserve Redis capacity
→ connect player
```

## Failure policy

- no bootstrap authority -> no provider call;
- stale/fenced bootstrap operation -> no provider side effect;
- provider unavailable -> no assumption that backend started;
- provider accepted -> still no player transfer until control+health validation;
- lost bootstrap ownership while waiting -> abort/fail closed;
- no silent local process-start fallback;
- no Plugin Messaging signal may substitute for process/readiness authority.

## Scope of B.1

B.1 introduces only:

- provider-neutral start request/result contracts;
- exact `BackendBootstrapLease` propagation into the provider boundary;
- documented fencing/idempotency semantics;
- basic contract tests.

B.1 does **not**:

- select a concrete orchestration technology;
- start a real backend process;
- modify `TheosferaProxy` product composition;
- change `DistributedPlayerTransferRetryCoordinator`;
- change `TransferTargetResolver`;
- change capacity reservation ordering yet;
- poll process status;
- change TheosferaProtocol;
- add Plugin Messaging channels.

## Next increments after B.1

Expected order, subject to review after each increment:

```text
B.1 provider contracts
B.2 fenced provider implementation/adapter strategy
B.3 startup operation lifecycle + bounded timeout/retry semantics
B.4 readiness bridge using current Control Channel + health
B.5 product cold-start wiring and capacity reordering
B.6 runtime acceptance / checkpoint
```

Do not jump directly from `BackendOrchestrationProvider.ACCEPTED` to `ConnectionRequest`.