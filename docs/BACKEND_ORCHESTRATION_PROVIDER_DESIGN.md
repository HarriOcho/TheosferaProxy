# Backend Orchestration Provider — Active Design

## Status

Milestone base:

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
B.1 provider contracts                         LOCALLY VALIDATED
B.2 fenced provider / actuator strategy        LOCALLY VALIDATED
B.3 startup operation lifecycle                IMPLEMENTED, PENDING LOCAL GATE
B.4 readiness bridge                           NEXT
B.5 product cold-start wiring                  LATER
B.6 runtime acceptance / checkpoint            LATER
```

No real backend process is started by TheosferaProxy yet.

---

## 1. Core separation

Distributed bootstrap ownership answers:

> Which exact Proxy generation currently owns the right to coordinate startup for this backend?

The orchestration provider answers only:

> Can this exact fenced authority submit the process-start instruction?

Neither answer proves readiness.

Permanent invariant:

```text
bootstrap ownership
    != provider start accepted
    != process running
    != TCP open
    != TLS/HMAC control authenticated
    != backend HEALTHY
    != capacity reserved
    != player ready
```

The only future routing-ready proof remains:

```text
current authenticated Control Channel
+ fresh PONG / HEALTHY
```

Provider/process state may assist diagnostics but cannot replace that authority.

---

# B.1 — Provider contracts

## Public API

```java
CompletionStage<BackendStartResult> requestStart(
        BackendStartRequest request
);
```

`BackendStartRequest` carries the exact `BackendBootstrapLease` instead of copying its authority into unrelated fields.

That lease binds:

- target backend name;
- bootstrap requestId;
- playerId that triggered the operation;
- exact `ProxyMembershipLease` owner;
- membership fencing;
- bootstrap fencing.

Public result statuses:

```text
ACCEPTED
STALE_AUTHORITY
CONFLICT
TARGET_NOT_FOUND
PROVIDER_UNAVAILABLE
REJECTED
```

`ACCEPTED` means only that the fenced start instruction was accepted or replayed idempotently.

It does **not** mean that a backend can receive players.

Expected operational failures use explicit statuses. Structural corruption, impossible state and contract violations remain exceptional and fail closed.

---

# B.2 — Fenced provider / actuator strategy

## TOCTOU rule

The following design is forbidden:

```text
Proxy checks fencing remotely
        ↓
check succeeds
        ↓
Proxy pauses
        ↓
new owner supersedes it
        ↓
old Proxy wakes up
        ↓
unfenced process start
```

Therefore:

> Authority comparison and acceptance/emission of the actual start side effect must be one atomic decision from the actuator/orchestrator point of view.

A Redis pre-check followed by an unrelated Docker/systemd/panel call is not sufficient fencing.

## B.2 pipeline

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

`BackendStartTarget` separates:

```text
backendName
```

from:

```text
targetReference
```

`targetReference` is trusted configuration/adapter data. It is never supplied directly by a player, command argument or arbitrary protocol payload.

The adapter must treat the target reference as data, not concatenate it into an unsafe shell command.

No mapping:

```text
TARGET_NOT_FOUND
→ actuator not called
→ zero process side effect
```

A target mapped to a different logical backend than the bootstrap lease is a contract violation and fails before actuation.

## Atomic actuator semantics

For each backend target:

```text
incoming fencing < latest accepted fencing
→ STALE_AUTHORITY
→ zero side effect

incoming fencing == latest accepted fencing
+ exact immutable replay identity
→ ACCEPTED
→ zero duplicate side effect

incoming fencing == latest accepted fencing
+ conflicting immutable identity
→ CONFLICT
→ zero side effect

incoming fencing > latest accepted fencing
→ ACCEPTED
→ new authoritative start instruction may be emitted once
```

Replay identity includes at least:

```text
targetReference
requestId
playerId
proxyName
incarnationId
membership fencing
bootstrap fencing
```

A production actuator that ignores `bootstrapLease.fencingToken()` is invalid.

B.2 tests model:

```text
41 first request       → ACCEPTED / side effect #1
41 exact replay        → ACCEPTED / no duplicate
40 stale               → STALE_AUTHORITY / no side effect
41 conflicting replay  → CONFLICT / no side effect
42 newer authority     → ACCEPTED / side effect #2
```

The in-memory actuator used by tests is not a production orchestrator.

---

# B.3 — Startup operation lifecycle

## Purpose

B.3 owns the time-bounded **provider-acceptance phase** after distributed bootstrap ownership has already been acquired.

It deliberately stops at:

```text
START_ACCEPTED
```

B.4 will later continue from there into Control Channel + HEALTHY readiness.

## Lifecycle states

```text
NEW
 ↓
STARTING
 ↕
RETRY_WAIT
 ↓
START_ACCEPTED
```

Abort terminals:

```text
FAILED
TIMED_OUT
FENCED
CANCELLED
```

`START_ACCEPTED` is terminal only for B.3. It is **not backend readiness**.

## Exact ownership coupling

`BackendStartupOperationLifecycle` receives the already-running `BackendBootstrapOwnershipLifecycle` for the same operation.

Before every provider attempt it requires:

```text
ownershipLifecycle.hasAuthority()
+ currentLease != null
+ currentLease == exact lease captured for this startup operation
```

This local validation narrows needless stale calls. It is not the final side-effect fence; B.2 actuator fencing remains authoritative against the race between this check and the actual external action.

## Bootstrap renewal while STARTING

B.3 does not create a second renewal mechanism.

The A.7 `BackendBootstrapOwnershipLifecycle` already renews its 60-second bootstrap lease every 20 seconds while it remains active.

B.3 keeps that lifecycle alive throughout:

```text
STARTING
RETRY_WAIT
START_ACCEPTED hand-off
```

Abort paths stop/release ownership exactly through the existing ownership lifecycle.

This preserves the separation:

```text
bootstrap lease TTL = owner failure-detection window
startup timeout      = maximum local provider-acceptance operation duration
```

They are independent policies.

## Retry policy

Only this explicit provider result is retryable:

```text
PROVIDER_UNAVAILABLE
```

Retry delay uses capped exponential backoff:

```text
initial
initial × 2
initial × 4
...
maxRetryDelay cap
```

The concrete timeout/retry values are constructor policy, not product defaults yet.

No production timeout is guessed before the real orchestration platform is selected and measured.

Terminal provider outcomes:

```text
STALE_AUTHORITY → FENCED
CONFLICT         → FAILED
TARGET_NOT_FOUND → FAILED
REJECTED         → FAILED
```

Unexpected exceptional provider completion is `FAILED` and remains exceptional to the caller after exact ownership cleanup.

## Independent total timeout

The total startup timeout has its own scheduled task.

It does not depend on receiving another provider callback.

Therefore a permanently hung provider call still becomes:

```text
STARTING
→ timeout timer fires
→ TIMED_OUT
→ cancel retry/timeout handles
→ exact ownership stop/release
```

A late provider response cannot revive the operation because each terminal transition advances the local lifecycle epoch.

## Cancellation

Cancellation while `STARTING` or `RETRY_WAIT`:

```text
→ CANCELLED
→ cancel scheduled retry/timeout
→ exact ownership stop/release
→ ignore late callbacks
```

Cancellation does not promise that an external process side effect already atomically accepted before cancellation can be rolled back. B.3 cancels the local authoritative operation; a future provider-specific stop/drain contract is a separate concern.

## Ownership loss

If the bootstrap ownership lifecycle terminates as `FENCED` while B.3 is active:

```text
→ B.3 FENCED immediately
→ cancel local scheduled work
→ no more provider attempts
→ late provider completion ignored
```

If ownership is intentionally `STOPPED` externally while B.3 is active:

```text
→ B.3 CANCELLED
```

Provider `STALE_AUTHORITY` is also treated as `FENCED` and triggers local ownership stop so a generation rejected by the actuator does not continue holding Redis bootstrap ownership.

## Successful hand-off

When provider returns `ACCEPTED` before timeout:

```text
STARTING
→ START_ACCEPTED
→ cancel B.3 retry/timeout timers
→ DO NOT stop bootstrap ownership
```

The same bootstrap authority remains alive for B.4.

Future B.4 must still require:

```text
current TLS/HMAC control authentication
→ fresh PONG / HEALTHY
```

before any capacity reservation or player connection.

## B.3 automated coverage

Coverage includes:

- immediate provider acceptance;
- exact bootstrap lease propagation;
- `PROVIDER_UNAVAILABLE` retry;
- capped exponential backoff;
- hung provider total timeout;
- exact ownership cleanup on timeout/failure/cancel;
- ownership `FENCED` during an in-flight provider request;
- late `ACCEPTED` after timeout/fencing cannot revive;
- cancellation during retry prevents another provider call;
- `STALE_AUTHORITY` maps to `FENCED`;
- provider conflict/rejection maps to terminal failure;
- exceptional provider failure remains exceptional after cleanup;
- missing bootstrap authority never calls the provider;
- lifecycle is single-use.

B.3 remains pending local validation until its targeted and full test gates pass.

---

## 4. Provider-neutral architecture remains mandatory

No Docker/systemd/panel/Kubernetes/cloud technology has been selected.

Desired future shape:

```text
product cold-start flow
        ↓
BackendBootstrapOwnershipLifecycle
        ↓
BackendStartupOperationLifecycle
        ↓
FencedBackendOrchestrationProvider
        ↓
trusted target mapping
        ↓
platform-specific BackendStartActuator
        ↓
actual orchestration platform
```

Routing and transfer services must not contain platform-specific process commands.

---

## 5. Capacity ordering remains unchanged for now

Current product transfers still target already-ready backends and use the existing approximately 20-second Redis capacity reservation TTL.

True cold startup must eventually use:

```text
acquire distributed bootstrap ownership
→ B.3 provider start acceptance
→ B.4 wait control authentication
→ B.4 wait HEALTHY
→ re-resolve / revalidate
→ reserve Redis capacity
→ Velocity ConnectionRequest
→ PLAYER_SERVER_READY
→ exact cleanup / handoff
```

Do not reserve capacity during an arbitrary cold boot duration.

---

## 6. Failure policy

- no bootstrap authority -> no provider call;
- stale/fenced bootstrap operation -> no authoritative continuation;
- missing trusted target -> zero actuator side effect;
- conflicting target/lease mapping -> fail closed;
- only explicit `PROVIDER_UNAVAILABLE` is retried by B.3;
- exceptional provider failure is terminal;
- timeout/cancel/failure releases owned bootstrap authority exactly;
- provider accepted -> still no player transfer;
- ownership lost while starting -> immediate fenced abort;
- no silent local process-start fallback;
- no Plugin Messaging signal substitutes for process/readiness authority.

---

## 7. Still intentionally absent

B.1–B.3 do not implement:

- a concrete Docker/systemd/panel/cloud actuator;
- real backend process startup;
- provider process-state polling;
- Control Channel readiness bridge;
- product cold-start transfer wiring;
- production capacity reordering;
- new TheosferaProtocol messages;
- new Plugin Messaging channels.

---

# B.4 — Next increment after B.3 validation

B.4 will define the readiness bridge after `START_ACCEPTED`.

It must wait for the exact target to prove:

```text
current authenticated TLS/HMAC Control Channel session
+ fresh PONG / HEALTHY
```

while the same bootstrap ownership remains authoritative.

It must also handle:

- ownership fencing while waiting;
- control generation replacement;
- stale health evidence;
- timeout/cancellation;
- late callbacks;
- exact transition to later re-resolution/capacity allocation.

B.4 still must not equate provider process state or open TCP ports with readiness.

Expected order:

```text
B.1 provider contracts                         DONE
B.2 fenced provider / actuator strategy        DONE
B.3 startup operation lifecycle                PENDING LOCAL GATE
B.4 readiness bridge using Control Channel     NEXT
B.5 product cold-start wiring                  LATER
B.6 runtime acceptance / checkpoint            LATER
```

Do not jump directly from `START_ACCEPTED` to `ConnectionRequest`.
