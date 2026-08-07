# Backend Orchestration Provider — Pre-Runtime Checkpoint

## Status

This checkpoint records the current development state of the Backend Orchestration Provider milestone.

Baseline:

```text
main @ ddc082319243da621d4e5364d4c4957f8d088b0d
feat: add distributed backend bootstrap foundation (#74)
```

Active branch:

```text
feature/backend-orchestration-provider
```

This is deliberately a **pre-runtime** checkpoint. It does not claim that a real backend process has been started by TheosferaProxy yet.

---

## Progress summary

```text
B.1 provider contracts                         LOCALLY VALIDATED
B.2 fenced provider / actuator strategy        LOCALLY VALIDATED
B.3 startup operation lifecycle                LOCALLY VALIDATED
B.4 Control Channel readiness bridge           IMPLEMENTED, PENDING LOCAL GATE
B.5 provider-neutral cold-start composition    FOUNDATION IMPLEMENTED, PENDING LOCAL GATE
B.6 real runtime acceptance                    NOT YET POSSIBLE
```

B.6 remains open because no concrete orchestration technology/actuator has been selected and therefore no real process-start side effect is wired.

---

## B.1 — Provider contracts

The public provider boundary is asynchronous:

```java
CompletionStage<BackendStartResult> requestStart(
        BackendStartRequest request
);
```

`BackendStartRequest` carries the exact `BackendBootstrapLease` so the provider receives:

- target backend name;
- bootstrap requestId;
- playerId;
- exact Proxy membership owner/incarnation/fencing;
- bootstrap fencing token.

Provider result statuses are explicit and fail-closed.

Important invariant:

```text
provider ACCEPTED
    != process running
    != control authenticated
    != backend HEALTHY
    != capacity reserved
```

---

## B.2 — Atomic fenced actuator boundary

The provider layer now separates:

```text
logical Theosfera backend name
```

from:

```text
trusted opaque orchestration target reference
```

The lower `BackendStartActuator` contract requires bootstrap fencing comparison and start-side-effect acceptance/emission to be one atomic decision from the orchestrator/actuator point of view.

Explicitly forbidden:

```text
check Redis fencing
→ pause/race
→ perform an unfenced process start later
```

Required semantics per target:

```text
older fencing
→ STALE_AUTHORITY
→ zero side effect

same fencing + exact immutable operation
→ ACCEPTED replay
→ zero duplicate start

same fencing + conflicting immutable operation
→ CONFLICT
→ zero side effect

newer fencing
→ ACCEPTED
→ new authoritative side effect may occur once
```

The test-only atomic actuator model proves these semantics but is not a production actuator.

---

## B.3 — Startup operation lifecycle

`BackendStartupOperationLifecycle` manages the provider-acceptance phase while distributed bootstrap ownership remains active.

States:

```text
NEW
STARTING
RETRY_WAIT
START_ACCEPTED
FAILED
TIMED_OUT
FENCED
CANCELLED
```

Rules:

- lifecycle is single-use;
- exact bootstrap lease is captured and revalidated;
- only `PROVIDER_UNAVAILABLE` is retryable;
- retry uses bounded exponential backoff;
- total startup timeout is independent from the bootstrap lease TTL;
- the bootstrap ownership lifecycle continues its own Redis renewal;
- `STALE_AUTHORITY` fences the operation;
- conflict/not-found/rejected fail closed;
- provider exceptions fail closed;
- a provider that never completes cannot defeat the independent timeout;
- ownership loss aborts the operation;
- late callbacks cannot revive a terminal lifecycle;
- cancellation stops owned bootstrap authority;
- `START_ACCEPTED` intentionally keeps bootstrap ownership alive for B.4.

User-local validation completed before B.4 work:

```text
BackendStartupOperationLifecycleTest + BackendStartupPolicyTest
→ BUILD SUCCESSFUL

full test suite
→ BUILD SUCCESSFUL
```

---

## B.4 — Authoritative readiness bridge

B.4 does not use process state or TCP reachability as backend readiness.

New components:

```text
BackendReadinessStatus
BackendReadinessSnapshot
BackendReadinessProbe
BackendReadinessPolicy
BackendReadinessScheduler
BackendReadinessLifecycleState
BackendReadinessLifecycle
```

### Readiness evidence

`BackendReadinessProbe` combines the already-authoritative runtime sources:

```text
BackendAuthorizationPolicy
        +
BackendIdentityProvider
(production: current BackendControlIdentityProvider)
        +
BackendHealthRegistry
```

Result is `READY` only when:

```text
static target is configured
+ current authenticated Control Channel identity exists
+ identity name/type matches static policy
+ current backend health is HEALTHY/fresh
```

Waiting states distinguish:

```text
CONTROL_NOT_AUTHENTICATED
HEALTH_NOT_READY
```

Policy/identity contradictions are terminal fail-closed outcomes.

### Control-generation health hardening

B.4 exposed an important generation-boundary issue: `BackendHealthRegistry` is keyed by backend name, so a newly authenticated control generation must not inherit a fresh timestamp created by an older generation.

The branch now adds `BackendControlGenerationResetListener` and wires it into `BackendControlRuntime`.

Whenever a new authenticated control generation becomes current:

```text
remove pending PING for backend
→ remove previous name-scoped health evidence
→ invoke the pre-existing authenticated identity listener
```

Therefore the new generation must complete a new correlated PING/PONG before it can become `HEALTHY` and satisfy B.4 readiness.

This hardening applies globally, not only to cold-start readiness.

### Readiness lifecycle

`BackendReadinessLifecycle`:

- is single-use;
- requires the exact current bootstrap lease;
- listens to bootstrap ownership termination;
- has an independent readiness timeout;
- polls readiness using a one-shot scheduler;
- waits separately for control authentication and fresh health;
- `READY` does not itself release bootstrap ownership;
- timeout/cancel/contract failure performs exact owned cleanup;
- ownership fencing or unexpected lease replacement cannot cause release of an unknown generation;
- late timers cannot revive terminal state.

Automated coverage was added for probe semantics, timing policy, lifecycle races, exact lease replacement and control-generation health invalidation.

B.4 is pending the user's local Gradle gate after this checkpoint.

---

## B.5 — Provider-neutral cold-start foundation

B.5 cannot be activated productively until a real fenced orchestration actuator is selected.

The branch therefore implements only provider-neutral pieces and intentionally leaves the legacy product path active.

### New cold-start boundary

```text
BackendColdStartService
BackendColdStartResult
BackendColdStartCoordinator
UnavailableBackendColdStartService
```

`BackendColdStartCoordinator` composes:

```text
exact distributed bootstrap ownership
→ B.3 provider acceptance
→ B.4 current Control Channel + HEALTHY readiness
→ exact bootstrap ownership release
→ READY
```

Capacity is deliberately outside this coordinator.

A `READY` result is only returned after readiness was proven and exact bootstrap ownership release was confirmed.

After `READY`, future product wiring must:

```text
re-resolve / revalidate target
→ reserve Redis capacity
→ register pending transfer
→ Velocity ConnectionRequest
→ PLAYER_SERVER_READY
→ presence handoff / exact release
```

### Velocity timing adapters

Provider-neutral one-shot Velocity adapters were added:

```text
VelocityBackendStartupScheduler
VelocityBackendReadinessScheduler
```

They use Velocity delayed tasks, support cancellation and fail closed on invalid/null scheduling results.

### Pre-capacity allocation contract

`DistributedPlayerTransferTargetAllocation` now supports a future explicit `bootstrapRequired(...)` outcome with:

```text
cold target known
+ no PendingPlayerTransfer yet
+ no BackendCapacityReserveRequest yet
```

This contract exists so future product wiring can represent the correct ordering without holding the current ~20 second capacity reservation through backend startup.

The current productive allocation service was deliberately restored after design work so existing transfers are not silently changed before a real actuator exists.

---

## Product path intentionally unchanged

At this checkpoint, the existing production cold branch still behaves through the historical local bootstrap path.

This is intentional.

The branch does **not** yet replace product transfer composition because doing so without a real actuator would either break cold transfers or tempt a silent local fallback.

Still present in product composition:

```text
BackendBootstrapRegistry
legacy cold allocation path
legacy DistributedPlayerTransferRetryCoordinator bootstrap handling
```

They are now compatibility debt to be removed only when the real fenced actuator is selected and B.5 product wiring can be validated end-to-end.

---

## Why B.6 is not closed

Real runtime acceptance requires an actual platform-specific actuator capable of preserving B.2 fencing semantics.

Examples of possible infrastructure categories include Docker, systemd, a hosting panel, Kubernetes or another orchestrator, but this branch deliberately does not guess which Theosfera deployment will use.

Before product activation, the operator/deployment architecture must decide:

- concrete orchestration platform;
- trusted backend-name → target mapping source;
- how the actuator atomically persists/compares bootstrap fencing;
- how exact replay/idempotency is implemented;
- credentials/secrets location outside Git;
- process start invocation mechanism;
- operational timeout values based on observed startup behavior.

Only then may B.5c wire a real actuator into `TheosferaProxy`.

---

## Runtime acceptance matrix reserved for B.6

The final milestone must prove at least:

```text
cold target selected without capacity reservation       PASS required
one Proxy acquires bootstrap ownership                  PASS required
competing Proxy gets TARGET_BUSY                        PASS required
stale bootstrap fencing causes zero start side effect   PASS required
exact provider replay causes no duplicate process start PASS required
provider accepted != immediate transfer                 PASS required
new control generation invalidates previous health      PASS required
backend must authenticate TLS/HMAC                      PASS required
backend must produce fresh PONG/HEALTHY                  PASS required
bootstrap ownership renews throughout startup           PASS required
ownership loss aborts startup/readiness                  PASS required
capacity reserved only after readiness + revalidation   PASS required
ConnectionRequest occurs only after Redis capacity      PASS required
PLAYER_SERVER_READY completes presence handoff          PASS required
failed start/readiness leaves no bootstrap residue      PASS required
Redis/provider outage fails closed                      PASS required
no local bootstrap fallback                             PASS required
```

Do not mark these PASS until observed in real runtime with the selected actuator.

---

## Local gate for the next session

After syncing the branch, run:

```powershell
git status
git diff --check

.\gradlew.bat test --tests "*BackendControlGenerationResetListenerTest" --no-daemon

.\gradlew.bat test --tests "*BackendReadinessProbeTest" --tests "*BackendReadinessPolicyTest" --tests "*BackendReadinessLifecycleTest" --no-daemon

.\gradlew.bat test --tests "*BackendColdStartCoordinatorTest" --tests "*DistributedPlayerTransferTargetAllocationColdStartContractTest" --tests "*VelocityBackendOrchestrationSchedulersTest" --no-daemon

.\gradlew.bat test --no-daemon
.\gradlew.bat clean build --no-daemon
```

The branch must not be considered merge-ready until these gates are green and the remaining actuator/product-runtime boundary is resolved.

---

## Exact continuation point

After local B.4/B.5 foundation validation:

```text
choose concrete fenced orchestration platform
→ implement platform-specific BackendStartTargetResolver / BackendStartActuator
→ prove atomic fencing + replay semantics against that platform
→ wire provider-neutral cold-start coordinator into product transfer path
→ remove/retire legacy local bootstrap authority from product cold path
→ reserve capacity only after B.4 readiness + re-resolution
→ runtime B.6 matrix
→ final checkpoint
→ PR
```

Do not jump to Administrative Player Transfer, Maintenance, Friends/Parties or other product systems until this orchestration boundary is closed, unless explicitly reprioritized.
