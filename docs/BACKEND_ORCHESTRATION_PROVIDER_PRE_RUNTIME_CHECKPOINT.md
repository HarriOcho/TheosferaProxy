# Backend Orchestration Provider — Pre-Runtime Checkpoint

## Status

Baseline:

```text
main @ ddc082319243da621d4e5364d4c4957f8d088b0d
feat: add distributed backend bootstrap foundation (#74)
```

Active branch:

```text
feature/backend-orchestration-provider
```

This remains a **pre-runtime** checkpoint. It does not claim that Theosfera has started a real backend process through orchestration yet.

Current progress:

```text
B.1 provider contracts                         VALIDATED
B.2 fenced provider / actuator strategy        VALIDATED
B.3 startup operation lifecycle                VALIDATED
B.4 Control Channel readiness bridge           VALIDATED
B.5 provider-neutral cold-start foundation     VALIDATED
B.5c concrete process plane                    PTERODACTYL SELECTED
B.5c Proxy -> Gateway adapter                  IMPLEMENTED, PENDING LOCAL GATE
B.6 real runtime acceptance                    OPEN
```

The user's local validation after B.4/B.5 foundation work was fully green:

```text
BackendControlGenerationResetListenerTest      BUILD SUCCESSFUL
BackendReadiness* tests                        BUILD SUCCESSFUL
BackendColdStart* / cold contract / schedulers BUILD SUCCESSFUL
full test suite                                BUILD SUCCESSFUL
clean build                                    BUILD SUCCESSFUL
```

---

## 1. Permanent orchestration invariants

Never collapse these states:

```text
bootstrap ownership
    != provider ACCEPTED
    != process running
    != TLS/HMAC Control Channel authenticated
    != fresh PONG / HEALTHY
    != Redis capacity reserved
    != player connected/ready
```

The correct future cold-start order remains:

```text
select cold target
→ acquire distributed bootstrap ownership
→ emit fenced process-start request
→ renew bootstrap ownership while starting
→ wait current Control Channel authentication
→ wait fresh PONG / HEALTHY
→ exact bootstrap release
→ re-resolve / revalidate
→ reserve Redis capacity
→ register pending transfer
→ ConnectionRequest
→ PLAYER_SERVER_READY
→ presence handoff / exact cleanup
```

No capacity reservation may be held across the backend startup window.

---

## 2. B.1 — Provider contracts

`BackendOrchestrationProvider` is asynchronous and receives `BackendStartRequest` containing the exact `BackendBootstrapLease`.

The provider therefore receives immutable operation identity including:

- logical target backend;
- requestId;
- playerId;
- exact Proxy membership owner/incarnation/fencing;
- backend bootstrap fencing token.

Provider statuses remain explicit and fail-closed.

---

## 3. B.2 — Atomic fenced actuator boundary

The trusted target boundary separates:

```text
logical backend name
```

from:

```text
opaque orchestration target reference
```

`BackendStartActuator.startIfCurrent(...)` represents the boundary where fencing validation and acceptance/emission of the start side effect must be serialized by the real orchestrator.

Required per-target semantics:

```text
older fencing
→ STALE_AUTHORITY
→ zero new start emission

same fencing + exact same immutable operation
→ ACCEPTED replay
→ zero duplicate start emission

same fencing + conflicting operation
→ CONFLICT
→ zero new start emission

newer fencing
→ accept newer authority
→ emit at most one authoritative start
```

A Redis pre-check followed by an unfenced side effect remains forbidden because of TOCTOU.

---

## 4. B.3 — Startup lifecycle

`BackendStartupOperationLifecycle` provides:

- single-use lifecycle;
- exact bootstrap-lease capture and revalidation;
- retry only for `PROVIDER_UNAVAILABLE`;
- bounded exponential backoff;
- independent total startup timeout;
- cancellation;
- ownership-loss fencing;
- late-callback protection;
- cleanup on terminal failures;
- `START_ACCEPTED` handoff without releasing bootstrap ownership.

Bootstrap renewal remains owned by `BackendBootstrapOwnershipLifecycle`; B.3 does not duplicate Redis renewal.

---

## 5. B.4 — Authoritative readiness

Readiness combines existing authoritative sources:

```text
BackendAuthorizationPolicy
+
BackendIdentityProvider
(production: current authenticated Control Channel identity)
+
BackendHealthRegistry
```

`READY` requires:

```text
configured target
+ current authenticated identity
+ exact name/type match with static policy
+ HEALTHY / fresh PONG evidence
```

B.4 introduced:

```text
BackendReadinessStatus
BackendReadinessSnapshot
BackendReadinessProbe
BackendReadinessPolicy
BackendReadinessScheduler
BackendReadinessLifecycleState
BackendReadinessLifecycle
```

### Control-generation health hardening

`BackendHealthRegistry` is name-scoped, therefore a new Control Channel generation must never inherit health produced by an older generation.

`BackendControlGenerationResetListener` is now wired into `BackendControlRuntime`:

```text
new authenticated Control generation becomes current
→ remove pending PING for backend
→ remove previous health evidence for backend
→ invoke existing authenticated-identity listener
→ require new correlated PING/PONG
```

This hardening is global to backend health, not specific to cold startup.

---

## 6. B.5 — Provider-neutral cold-start foundation

New provider-neutral boundary:

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
→ B.4 Control Channel + HEALTHY readiness
→ exact bootstrap ownership release
→ READY
```

Capacity deliberately remains outside this coordinator.

One-shot Velocity adapters exist for startup and readiness timing:

```text
VelocityBackendStartupScheduler
VelocityBackendReadinessScheduler
```

`DistributedPlayerTransferTargetAllocation` has a future `bootstrapRequired(...)` outcome capable of representing a cold target with no pending transfer and no capacity reservation yet.

The current productive `DistributedPlayerTransferTargetAllocationService` remains unchanged from `main`. The legacy local cold branch is intentionally still active until a real actuator is deployable and testable.

---

## 7. Concrete process plane selected: Pterodactyl

Theosfera's concrete backend process plane is now selected as:

```text
Pterodactyl Panel/Wings
```

However TheosferaProxy MUST NOT call the Pterodactyl power API directly.

The selected architecture is:

```text
TheosferaProxy
    → HTTPS
Theosfera Orchestration Gateway
    → Pterodactyl Panel/Wings
    → backend container/process
```

Reason: the normal Pterodactyl process-start surface does not provide Theosfera bootstrap fencing semantics. A direct Proxy → Panel call would reopen the TOCTOU race forbidden by B.2.

Canonical design:

```text
docs/PTERODACTYL_ORCHESTRATION_GATEWAY_DESIGN.md
```

### Responsibility split

TheosferaProxy:

- owns Redis bootstrap lease;
- owns B.3 retry/timeout;
- owns B.4 readiness;
- never holds Pterodactyl credentials.

Orchestration Gateway:

- owns durable highest-fencing/idempotency state per target;
- serializes exact replay/conflict/stale decisions;
- owns Pterodactyl credentials;
- accepts/emits the actual Pterodactyl start side effect.

Pterodactyl:

- owns Wings/container/process lifecycle;
- does not become Theosfera backend identity or health authority.

---

## 8. Proxy-side Pterodactyl Gateway adapter

Implemented under:

```text
com.theosfera.proxy.orchestration.pterodactyl
```

Components:

```text
PterodactylGatewayConfig
PterodactylGatewayConfigLoader
PterodactylGatewayTargetResolver
PterodactylGatewayStartCommand
PterodactylGatewayTransport
JdkPterodactylGatewayTransport
PterodactylGatewayBackendStartActuator
PterodactylGatewayProviderFactory
```

### Config

Proxy data file:

```text
orchestration.properties
```

Default is disabled.

Expected production shape:

```properties
enabled=true
gateway-uri=https://orchestration.internal.example:25610
request-timeout-seconds=5
gateway-token-env=THEOSFERA_ORCHESTRATION_GATEWAY_TOKEN

target.lobby-1=<pterodactyl-server-reference>
target.lobby-2=<pterodactyl-server-reference>
target.skyblock-1=<pterodactyl-server-reference>
```

Rules enforced by Proxy config:

- enabled Gateway requires HTTPS;
- URI cannot contain credentials/query/fragment/application path;
- bearer token value comes only from environment;
- missing token fails closed;
- target backend must exist in static backend policy;
- AUTH cannot be configured as ordinary gameplay cold-start target;
- duplicate Pterodactyl target reference is rejected;
- missing target mapping resolves as `TARGET_NOT_FOUND`, never arbitrary shell/process input.

### Gateway start request

Proxy sends exact operation identity:

```text
backendName
pterodactylTarget
requestId
playerId
proxyName
proxyIncarnationId
membershipFencingToken
bootstrapFencingToken
```

Transport:

- HTTPS endpoint is fixed by config;
- endpoint: `POST /v1/backend-start`;
- JDK async `HttpClient`;
- request timeout;
- redirects disabled;
- dedicated Gateway bearer token;
- no Pterodactyl API token in Proxy.

Semantic response body:

```text
ACCEPTED
STALE_AUTHORITY
CONFLICT
REJECTED
```

Mapping:

```text
2xx + recognized semantic token -> B.2 result
408 / 425 / 429 / 5xx         -> ACTUATOR_UNAVAILABLE
network/timeout                 -> ACTUATOR_UNAVAILABLE
other 4xx / redirect            -> REJECTED
malformed 2xx response           -> REJECTED
```

`ACTUATOR_UNAVAILABLE` is the only result B.3 retries.

---

## 9. Product path intentionally unchanged

Still present in current production composition:

```text
BackendBootstrapRegistry
legacy cold allocation path
legacy DistributedPlayerTransferRetryCoordinator bootstrap handling
```

Do not remove them until the Gateway runtime exists and B.6 proves the replacement end-to-end.

There is no silent fallback from the future Gateway path to local bootstrap.

---

## 10. Remaining real component: Orchestration Gateway runtime

The next technical milestone is NOT another Proxy abstraction.

It is the deployable Gateway that must:

1. authenticate Proxy requests;
2. validate request schema and configured target;
3. persist highest accepted bootstrap fencing per Pterodactyl target;
4. persist exact operation identity for replay detection;
5. serialize per-target fencing comparison and start acceptance/emission;
6. return `STALE_AUTHORITY` for older fencing with zero new start;
7. return idempotent `ACCEPTED` for exact replay with zero duplicate start;
8. return `CONFLICT` for same fencing/different operation;
9. call Pterodactyl using credentials unavailable to Proxy;
10. survive Gateway restart without forgetting fencing/idempotency state.

A purely in-memory Gateway is not production-safe.

---

## 11. B.6 runtime matrix

Do not mark PASS until observed against real Gateway + Pterodactyl:

```text
cold target selected without capacity reservation       PASS required
one Proxy acquires bootstrap ownership                  PASS required
competing Proxy gets TARGET_BUSY                        PASS required
Gateway starts exact configured Pterodactyl target      PASS required
stale bootstrap fencing causes zero new start           PASS required
exact replay causes zero duplicate process start        PASS required
same fencing conflicting identity is rejected           PASS required
provider ACCEPTED does not immediately transfer         PASS required
new Control generation invalidates old health           PASS required
backend authenticates TLS/HMAC                          PASS required
backend produces fresh PONG / HEALTHY                   PASS required
bootstrap ownership renews during startup               PASS required
ownership loss aborts startup/readiness                 PASS required
capacity reserved only after readiness + revalidation   PASS required
ConnectionRequest occurs only after Redis capacity      PASS required
PLAYER_SERVER_READY completes presence handoff          PASS required
failed start/readiness leaves no bootstrap residue      PASS required
Redis/Gateway/Pterodactyl outage fails closed           PASS required
no local bootstrap fallback                             PASS required
```

---

## 12. Local gate for the new Proxy adapter

After syncing the branch:

```powershell
git status
git diff --check

.\gradlew.bat test --tests "*PterodactylGatewayConfigLoaderTest" --tests "*PterodactylGatewayProviderFactoryTest" --no-daemon

.\gradlew.bat test --tests "*PterodactylGatewayBackendStartActuatorTest" --tests "*JdkPterodactylGatewayTransportTest" --no-daemon

.\gradlew.bat test --no-daemon
.\gradlew.bat clean build --no-daemon
```

The Proxy-side Pterodactyl Gateway adapter is not considered validated until these gates are green.

---

## 13. Exact continuation point

```text
validate Proxy-side Pterodactyl Gateway adapter locally
→ implement/deploy durable Theosfera Orchestration Gateway on Pterodactyl node/VPS
→ prove Gateway atomic fencing + replay semantics
→ wire Gateway-backed cold-start coordinator into product transfer path
→ retire legacy local bootstrap authority from cold transfers
→ reserve capacity only after B.4 readiness + re-resolution
→ run full B.6 runtime matrix
→ final checkpoint / PROJECT_STATE consolidation
→ PR
```

Administrative Player Transfer (`/theosfera send`), raw `/send` hardening and unified TAB/permission visibility remain recorded future work and should not displace this active orchestration boundary unless explicitly reprioritized.
