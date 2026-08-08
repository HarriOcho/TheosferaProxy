# Backend Orchestration Provider — Pre-Runtime Checkpoint

## Status

Baseline:

```text
main @ ddc082319243da621d4e5364d4c4957f8d088b0d
feat: add distributed backend bootstrap foundation (#74)
```

Active Proxy branch:

```text
feature/backend-orchestration-provider
```

Companion Orchestrator repository/branch:

```text
HarriOcho/TheosferaOrchestrator
feature/orchestrator-foundation
```

This remains a **pre-runtime** checkpoint. It does not claim that Theosfera has started a real backend process through the new cold-start orchestration path yet.

Current progress:

```text
B.1 provider contracts                         VALIDATED
B.2 fenced provider / actuator strategy        VALIDATED
B.3 startup operation lifecycle                VALIDATED
B.4 Control Channel readiness bridge           VALIDATED
B.5 provider-neutral cold-start foundation     VALIDATED
B.5c Pterodactyl process plane                 SELECTED
B.5c Proxy -> Orchestrator adapter              VALIDATED
Orchestrator C.1 contracts/config              VALIDATED
Orchestrator C.2 durable Redis fencing         VALIDATED
Orchestrator C.3 trusted admission             VALIDATED
Orchestrator C.4 Pterodactyl client            VALIDATED
Orchestrator C.5 crash/replay recovery         VALIDATED
Orchestrator C.6 authenticated HTTPS endpoint  VALIDATED
Orchestrator C.7 real runtime                  OPEN
B.6 end-to-end Proxy runtime                   OPEN
```

No PR has been opened for this milestone and no product cold-path switch has been activated.

---

## 1. Local validation evidence

Proxy B.4/B.5 foundation gates:

```text
BackendControlGenerationResetListenerTest      BUILD SUCCESSFUL
BackendReadiness* tests                        BUILD SUCCESSFUL
BackendColdStart* / cold contract / schedulers BUILD SUCCESSFUL
full test suite                                BUILD SUCCESSFUL
clean build                                    BUILD SUCCESSFUL
```

Proxy Pterodactyl Gateway adapter gates:

```text
PterodactylGatewayConfigLoaderTest             BUILD SUCCESSFUL
PterodactylGatewayProviderFactoryTest          BUILD SUCCESSFUL
PterodactylGatewayBackendStartActuatorTest     BUILD SUCCESSFUL
JdkPterodactylGatewayTransportTest             BUILD SUCCESSFUL
full test suite                                BUILD SUCCESSFUL
clean build                                    BUILD SUCCESSFUL
```

TheosferaOrchestrator gates:

```text
LettuceRedisBackendStartStoreIntegrationTest   BUILD SUCCESSFUL
OrchestratorConfigLoaderTest                   BUILD SUCCESSFUL
GatewayTokenAuthenticatorTest                 BUILD SUCCESSFUL
BackendStartRequestCodecTest                  BUILD SUCCESSFUL
BackendStartHttpApplicationTest               BUILD SUCCESSFUL
BackendStartCommandTest                       BUILD SUCCESSFUL
BackendStartAdmissionServiceTest              BUILD SUCCESSFUL
BackendStartDispatchServiceTest               BUILD SUCCESSFUL
RedisBackendStartKeyspaceTest                 BUILD SUCCESSFUL
full test suite                               BUILD SUCCESSFUL
clean build                                   BUILD SUCCESSFUL
```

Orchestrator Redis integration was exercised through Testcontainers + Docker Desktop + a real Redis container. This is development validation; it does not replace C.7 production-equivalent runtime acceptance.

---

## 2. Permanent orchestration invariants

Never collapse these states:

```text
bootstrap ownership
    != Orchestrator ACCEPTED
    != Pterodactyl process running
    != TLS/HMAC Control Channel authenticated
    != fresh PONG / HEALTHY
    != Redis capacity reserved
    != player connected/ready
```

Correct future cold-start order:

```text
select cold target
→ NO capacity reservation yet
→ acquire exact distributed bootstrap ownership
→ request fenced process start through TheosferaOrchestrator
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

No capacity reservation may be held across backend startup.

Kick failover remains `RESOLVED`-only; a backend kick must not trigger cold startup.

---

## 3. B.1–B.3 — Provider and startup lifecycle

`BackendOrchestrationProvider` receives an exact `BackendBootstrapLease` carrying immutable authority:

- target backend;
- requestId;
- playerId;
- Proxy logical name/incarnation;
- Proxy membership fencing;
- backend bootstrap fencing.

`BackendStartActuator.startIfCurrent(...)` is the boundary where fencing validation and acceptance/emission of the process-start side effect must be serialized.

Required per-target semantics:

```text
older fencing
→ STALE_AUTHORITY
→ zero new start emission

same fencing + exact immutable replay
→ ACCEPTED/replay
→ zero duplicate start emission

same fencing + conflicting operation
→ CONFLICT
→ zero new start emission

newer fencing
→ may establish newer authority and emit one start
```

A Redis pre-check followed by a separate unfenced side effect remains forbidden because of TOCTOU.

B.3 provides:

- exact bootstrap ownership checks;
- retry only for provider unavailable;
- bounded exponential backoff;
- independent timeout;
- cancellation/fencing;
- late-callback protection;
- exact cleanup on failure;
- `START_ACCEPTED` without releasing bootstrap ownership.

---

## 4. B.4 — Authoritative backend readiness

Readiness is proven by:

```text
static BackendAuthorizationPolicy
+
current authenticated Backend Control Channel identity
+
fresh BackendHealthRegistry HEALTHY evidence
```

Pterodactyl/TCP/process state is never routing readiness.

Control-generation hardening:

```text
new authenticated Control generation becomes current
→ remove pending PING for backend
→ remove old health evidence
→ require a new correlated PING/PONG
```

Therefore a new current Control generation cannot inherit health from an older generation.

---

## 5. B.5 — Provider-neutral cold-start coordinator

`BackendColdStartCoordinator` composes:

```text
distributed bootstrap ownership
→ B.3 Orchestrator/provider acceptance
→ B.4 Control Channel + HEALTHY readiness
→ exact bootstrap release
→ READY
```

Capacity deliberately remains outside the coordinator.

`DistributedPlayerTransferTargetAllocation` can represent future pre-capacity `BOOTSTRAP_REQUIRED` allocation.

The current productive `DistributedPlayerTransferTargetAllocationService` remains unchanged from `main`; the legacy local cold branch has NOT yet been replaced.

This is intentional until C.7 proves the real Orchestrator/Pterodactyl side-effect boundary.

---

## 6. Concrete process plane

Selected architecture:

```text
TheosferaProxy
    → HTTPS
TheosferaOrchestrator
    → Pterodactyl Panel/Wings
    → backend container/process
```

TheosferaProxy MUST NOT call Pterodactyl directly.

Responsibility split:

### TheosferaProxy

- distributed bootstrap lease;
- B.3 retry/timeout;
- B.4 readiness;
- post-readiness Redis capacity;
- player transfer/presence lifecycle;
- no Pterodactyl credentials.

### TheosferaOrchestrator

- authenticates Gateway requests;
- independently validates logical backend -> Pterodactyl target;
- persists durable highest fencing/idempotency state per target;
- serializes stale/replay/conflict/new-authority decisions;
- owns Pterodactyl credentials;
- dispatches/reconciles the Pterodactyl START side effect.

### Pterodactyl

- process/container lifecycle;
- not Theosfera identity authority;
- not Theosfera health authority.

---

## 7. Proxy -> Orchestrator adapter

Proxy package:

```text
com.theosfera.proxy.orchestration.pterodactyl
```

Core components:

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

Proxy data file:

```text
orchestration.properties
```

Default remains disabled until runtime activation.

Production shape:

```properties
enabled=true
gateway-uri=https://<orchestrator-host>:25610
request-timeout-seconds=5
gateway-token-env=THEOSFERA_ORCHESTRATION_GATEWAY_TOKEN

target.lobby-1=<real-pterodactyl-id>
target.lobby-2=<real-pterodactyl-id>
target.skyblock-1=<real-pterodactyl-id>
```

Rules:

- HTTPS only;
- token value environment-only;
- static trusted target mapping;
- AUTH excluded from ordinary gameplay cold start;
- duplicate physical target rejected;
- redirects disabled;
- bounded async HTTP timeout;
- no Pterodactyl API token in Proxy;
- no local/unfenced fallback.

Gateway request includes:

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

---

## 8. TheosferaOrchestrator C.1–C.6

Repository:

```text
HarriOcho/TheosferaOrchestrator
feature/orchestrator-foundation
```

Durable Redis key shape:

```text
theosfera:orchestrator:backend-start:<base64url(target)>
```

No TTL.

Durable phases:

```text
ADMITTED
→ DISPATCHING
→ ACCEPTED | REJECTED
```

Admission:

```text
incoming < current
→ STALE_AUTHORITY

incoming == current + exact operation
→ REPLAY

incoming == current + different operation
→ CONFLICT

incoming > current
→ NEW_AUTHORITY
```

Crash rule:

```text
DISPATCHING
→ process may have received START
→ exact replay NEVER blindly re-sends START
→ query Pterodactyl state
→ STARTING/RUNNING may reconcile ACCEPTED
→ otherwise same generation remains retryable/fail-closed
```

Initial topology is one active Orchestrator per target set/Pterodactyl node. The in-process per-target serializer is not an HA lock; multiple active Orchestrators controlling the same targets require a future distributed dispatcher design.

C.6 endpoint:

```text
TLS 1.3
POST /v1/backend-start
Bearer token
strict/bounded JSON
trusted target revalidation
Redis fencing
Pterodactyl dispatch/recovery
```

TLS trust-all and disabled hostname verification are forbidden.

---

## 9. Production VPS source of truth

Authoritative provisioning/rebuild checklist lives in:

```text
HarriOcho/TheosferaOrchestrator
docs/PRODUCTION_DEPLOYMENT_RUNBOOK.md
```

It covers:

- Linux/VPS baseline, SSH/time/firewall;
- Java 21;
- Pterodactyl Panel/Wings/Docker Engine;
- backend instance inventory;
- dedicated Pterodactyl API credential;
- Redis ACL/TLS/persistence/backups/keyspace durability;
- Orchestrator non-root service identity/filesystem;
- `orchestrator.properties`;
- environment-only Redis/Pterodactyl/Gateway/TLS secrets;
- TLS certificate/keystore/Proxy trust;
- Orchestrator firewall/listener exposure;
- systemd/autostart/reboot recovery;
- Proxy Gateway config/trust;
- Control Channel readiness;
- exact no-capacity-during-boot ordering;
- C.7 stale/replay/conflict/crash/multi-Proxy/runtime matrix;
- real cold Lobby and Skyblock tests;
- failure matrix;
- explicit network/port inventory;
- backups/disaster recovery/rebuild-from-zero;
- logs/monitoring/alerts;
- upgrades and secret rotation.

Permanent operational rule:

> Nothing required to rebuild or operate production Theosfera may exist only in chat history or human memory.

Any command, port, certificate step, systemd dependency, restore action or operational workaround discovered during C.7/VPS provisioning must be recorded in the production runbook (or an explicitly linked authoritative document) before that task is considered complete.

Development-only Windows WSL 2, Docker Desktop and Testcontainers must not be confused with Linux production dependencies. Docker Engine required by Pterodactyl/Wings is a separate production component.

---

## 10. Product path intentionally unchanged

Still present until real runtime acceptance:

```text
BackendBootstrapRegistry
legacy cold allocation path
legacy DistributedPlayerTransferRetryCoordinator bootstrap handling
```

Do not remove or half-migrate these pieces before C.7 proves the Orchestrator side-effect boundary.

There is no silent fallback from the future Orchestrator path to local bootstrap.

---

## 11. C.7 / B.6 runtime matrix

Do not mark PASS until observed against real Redis + TheosferaOrchestrator + Pterodactyl + Control Channel:

```text
cold target selected without capacity reservation       PASS required
one Proxy acquires bootstrap ownership                  PASS required
competing Proxy gets TARGET_BUSY                        PASS required
Orchestrator starts exact configured target             PASS required
stale bootstrap fencing causes zero new start           PASS required
exact replay causes zero duplicate process start        PASS required
same fencing conflicting identity rejected              PASS required
Gateway auth/JSON/TLS failures fail closed              PASS required
Orchestrator restart preserves fencing                  PASS required
DISPATCHING replay never blindly re-sends START         PASS required
provider ACCEPTED does not immediately transfer         PASS required
new Control generation invalidates old health           PASS required
backend authenticates TLS/HMAC                          PASS required
backend produces fresh PONG / HEALTHY                   PASS required
bootstrap ownership renews during startup               PASS required
ownership loss aborts startup/readiness                 PASS required
capacity reserved only after readiness + revalidation   PASS required
ConnectionRequest occurs only after Redis capacity      PASS required
PLAYER_SERVER_READY completes presence handoff          PASS required
failed start/readiness leaves no stale residue          PASS required
Redis/Orchestrator/Pterodactyl outage fails closed      PASS required
no local bootstrap fallback                             PASS required
VPS reboot/recovery behavior validated                  PASS required
```

---

## 12. Exact continuation point

```text
C.1-C.6 locally validated
→ follow TheosferaOrchestrator production deployment runbook
→ provision real VPS/Pterodactyl/Redis/TLS/secrets
→ execute Orchestrator C.7 runtime matrix
→ prove real cold Lobby + Skyblock
→ wire Gateway-backed BackendColdStartCoordinator into product cold path
→ remove legacy local cold bootstrap authority atomically
→ reserve capacity only after B.4 readiness + re-resolution
→ execute full Proxy B.6 runtime acceptance
→ final checkpoints / PROJECT_STATE consolidation
→ PR only with explicit authorization
```

Administrative Player Transfer (`/theosfera send`), raw `/send` hardening and unified TAB/permission visibility remain recorded future work and should not displace this active orchestration milestone unless explicitly reprioritized.
