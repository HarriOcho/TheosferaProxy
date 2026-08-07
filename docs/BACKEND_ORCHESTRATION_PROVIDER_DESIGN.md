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

Progress:

```text
B.1 provider contracts                         IMPLEMENTED + LOCALLY VALIDATED
B.2 fenced provider / actuator strategy        IMPLEMENTED, PENDING LOCAL GATE
B.3 startup operation lifecycle                NEXT
B.4 readiness bridge                           PENDING
B.5 product cold-start wiring                  PENDING
B.6 runtime acceptance / checkpoint            PENDING
```

No real process-start side effect is wired yet.

---

## Problem

Distributed Backend Bootstrap Foundation answers:

> Which Proxy generation currently has the distributed right to coordinate startup for this exact backend target?

It intentionally does **not** answer:

> How is the backend process actually started?

A provider boundary is required between fenced bootstrap ownership and any platform-specific side effect such as Docker, systemd, a hosting panel, Kubernetes, a cloud API or another future orchestrator.

---

## Core invariant

```text
bootstrap ownership
    != process running
    != TCP open
    != TLS/HMAC control authenticated
    != backend HEALTHY
    != capacity reserved
```

The provider handles only the process-start instruction boundary.

Backend readiness remains authoritative through the current authenticated Backend Control Channel followed by fresh PONG/HEALTHY evidence.

---

# B.1 — Provider contracts

## Public provider contract

```java
CompletionStage<BackendStartResult> requestStart(
        BackendStartRequest request
);
```

`BackendStartRequest` carries the exact `BackendBootstrapLease` rather than copying its fields into a second mutable interpretation.

That lease contains:

- target backend name;
- bootstrap requestId;
- playerId that triggered the operation;
- exact owning `ProxyMembershipLease`;
- monotonic bootstrap fencing token.

The provider therefore receives the exact authority Redis granted.

## Public result statuses

```text
ACCEPTED
STALE_AUTHORITY
CONFLICT
TARGET_NOT_FOUND
PROVIDER_UNAVAILABLE
REJECTED
```

### `ACCEPTED`

The provider accepted the start instruction or an exact idempotent replay.

It means only:

```text
start side-effect instruction accepted
```

It does **not** prove process state, port reachability, control authentication, health or capacity.

### `STALE_AUTHORITY`

A newer bootstrap authority already supersedes the request.

### `CONFLICT`

The same fenced generation was observed with incompatible immutable operation identity.

### `TARGET_NOT_FOUND`

No trusted orchestration target mapping exists for the logical backend name.

### `PROVIDER_UNAVAILABLE`

The orchestration layer cannot currently accept the instruction.

### `REJECTED`

The orchestration layer deliberately refused the request for another explicit non-success reason.

Structural corruption, impossible state or contract violations remain exceptional failures and must not be flattened into success.

---

# B.2 — Fenced provider / actuator strategy

## Why a Redis pre-check is insufficient

This implementation deliberately rejects the following architecture:

```text
Proxy checks fencing in Redis
        ↓
Redis says current
        ↓
Proxy pauses
        ↓
newer bootstrap owner supersedes it
        ↓
old Proxy wakes up
        ↓
unfenced Docker/systemd/panel start
```

That design contains a time-of-check/time-of-use race. The old owner can still emit the real side effect after losing authority.

Therefore:

> Comparing bootstrap authority and accepting/emitting the process-start side effect must be one atomic decision from the actuator/orchestrator point of view.

The Proxy must not emulate that guarantee with a separate remote pre-check.

## B.2 architecture

```text
BackendStartRequest
        │ exact BackendBootstrapLease
        ↓
FencedBackendOrchestrationProvider
        ↓
BackendStartTargetResolver
        │ logical backend → trusted opaque target
        ↓
BackendStartActuationRequest
        │ trusted target + exact bootstrap lease
        ↓
BackendStartActuator.startIfCurrent(...)
        │ atomic fencing + side-effect acceptance
        ↓
BackendStartActuationResult
        ↓
BackendStartResult
```

## Trusted target mapping

`BackendStartTargetResolver` maps a logical Theosfera backend name to a trusted `BackendStartTarget`.

`BackendStartTarget` separates:

```text
backendName
```

from:

```text
targetReference
```

The target reference is an opaque provider-side mapping key. It is not supplied by players, commands or arbitrary protocol payloads.

This prevents future adapters from turning untrusted text directly into process commands.

A concrete adapter must still treat `targetReference` as data, not concatenate it into an unsafe shell command.

If no mapping exists:

```text
TARGET_NOT_FOUND
→ actuator is not called
→ zero process side effect
```

If a resolver returns a target whose logical backend does not match the exact bootstrap lease:

```text
contract violation
→ fail closed
→ actuator is not called
```

## Actuation request

`BackendStartActuationRequest` contains:

- trusted `BackendStartTarget`;
- exact `BackendBootstrapLease`.

Construction requires:

```text
target.backendName == bootstrapLease.targetBackendName
```

No caller may combine a lease for one backend with a process target for another.

## Atomic actuator contract

`BackendStartActuator` exposes:

```java
CompletionStage<BackendStartActuationResult> startIfCurrent(
        BackendStartActuationRequest request
);
```

Its contract requires the concrete actuator/orchestrator to atomically evaluate authority and accept/emit the process-start side effect.

For each backend target, minimum semantics are:

```text
incoming fencing < latest accepted fencing
→ STALE_AUTHORITY
→ zero start side effect

incoming fencing == latest accepted fencing
+ exact same immutable operation
→ ACCEPTED
→ idempotent replay
→ zero duplicate start side effect

incoming fencing == latest accepted fencing
+ conflicting immutable operation
→ CONFLICT
→ zero start side effect

incoming fencing > latest accepted fencing
→ ACCEPTED
→ new authoritative start instruction may be emitted once
```

Immutable replay identity includes at least:

```text
targetReference
requestId
playerId
proxyName
incarnationId
membership fencing token
bootstrap fencing token
```

A real platform adapter may encode this atomically through a transactional external API, durable compare-and-set, orchestration-native idempotency/fencing primitive or another reviewed mechanism.

An adapter that ignores `bootstrapLease.fencingToken()` is invalid.

## Internal actuation results

```text
ACCEPTED
STALE_AUTHORITY
CONFLICT
ACTUATOR_UNAVAILABLE
REJECTED
```

`FencedBackendOrchestrationProvider` maps:

```text
ACTUATOR_UNAVAILABLE
→ PROVIDER_UNAVAILABLE
```

All other statuses preserve their public meaning.

No failure status is converted to `ACCEPTED`.

## Replay and stale tests

B.2 coverage includes a test-only atomic actuator model demonstrating:

```text
fencing 41 first request       → ACCEPTED / side effect #1
fencing 41 exact replay        → ACCEPTED / no duplicate
fencing 40 stale               → STALE_AUTHORITY / no side effect
fencing 41 conflicting request → CONFLICT / no side effect
fencing 42 newer authority     → ACCEPTED / side effect #2
```

The in-memory model exists only in tests to prove the required semantics. It is not a production actuator and is not a substitute for atomic guarantees in the real orchestration platform.

---

## Provider-neutral architecture

TheosferaProxy must not hard-code Docker/systemd/panel/Kubernetes assumptions into routing or transfer services.

Desired eventual shape:

```text
product bootstrap flow
        ↓
BackendOrchestrationProvider
        ↓
FencedBackendOrchestrationProvider
        ↓
platform target mapping
        ↓
platform-specific BackendStartActuator
        ↓
actual orchestration platform
```

Possible future adapters could include Docker, systemd, a hosting panel or another orchestrator, but no provider technology has been selected by B.1 or B.2.

---

## No readiness authority from the provider

Even if a future platform reports `RUNNING`, that observation is insufficient for routing.

Required post-start chain remains:

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

Provider/process state can later support diagnostics and timeout decisions, but it cannot replace Control Channel authority.

---

## Capacity ordering

Current capacity reservation TTL is designed for already-ready backends.

True cold startup must not reserve capacity before boot and hold it for an arbitrary startup duration.

Future product flow:

```text
acquire bootstrap ownership
→ request fenced provider start
→ renew bootstrap ownership while starting
→ wait control auth
→ wait HEALTHY
→ revalidate routing
→ reserve Redis capacity
→ connect player
```

---

## Failure policy

- no bootstrap authority -> no provider call;
- stale/fenced bootstrap operation -> no provider side effect;
- missing trusted target -> no actuator call;
- conflicting target/lease mapping -> fail closed;
- provider unavailable -> no assumption that backend started;
- provider accepted -> still no player transfer until control+health validation;
- lost bootstrap ownership while waiting -> abort/fail closed;
- no silent local process-start fallback;
- no Plugin Messaging signal may substitute for process/readiness authority.

---

## Scope completed through B.2

Implemented:

- provider-neutral public start contracts;
- exact `BackendBootstrapLease` propagation;
- trusted logical-backend → orchestration-target mapping boundary;
- exact target/lease consistency validation;
- atomic fenced actuator contract;
- public/internal status mapping;
- stale/replay/conflict semantics documented;
- automated adapter/contract coverage.

Still intentionally absent:

- concrete Docker/systemd/panel/cloud implementation;
- real backend process start;
- product `TheosferaProxy` composition;
- startup retry/timeout lifecycle;
- process-state polling;
- readiness bridge;
- transfer cold-start wiring;
- capacity reordering in production;
- TheosferaProtocol changes;
- new Plugin Messaging channels.

---

# B.3 — Next increment

B.3 must define the **startup operation lifecycle** around bootstrap ownership and provider invocation.

Questions B.3 must answer before product wiring:

- when is the provider called after bootstrap acquire?;
- which provider outcomes are terminal vs retryable?;
- what is the bounded retry/backoff policy for `PROVIDER_UNAVAILABLE`?;
- how long may an operation remain in STARTING?;
- how is the bootstrap lease renewed while waiting?;
- what happens if ownership becomes `FENCED` during provider retry or startup wait?;
- how are late provider completions prevented from reviving an aborted operation?;
- how is cancellation/stop propagated?;
- what explicit terminal result does the caller receive?;

B.3 must remain independent from backend readiness authority. Control Channel + HEALTHY integration belongs to B.4.

Expected order remains:

```text
B.1 provider contracts                         DONE
B.2 fenced provider / actuator strategy        DONE after local gate
B.3 startup operation lifecycle                NEXT
B.4 readiness bridge using Control Channel     LATER
B.5 product cold-start wiring                  LATER
B.6 runtime acceptance / checkpoint            LATER
```

Do not jump directly from `BackendOrchestrationProvider.ACCEPTED` to `ConnectionRequest`.
