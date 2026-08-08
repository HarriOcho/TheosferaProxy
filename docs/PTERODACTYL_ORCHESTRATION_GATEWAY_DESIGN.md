# Pterodactyl Orchestration Gateway — Concrete Actuator Design

Status: selected concrete orchestration architecture for `feature/backend-orchestration-provider` after B.1–B.5 validation.

## 1. Decision

Theosfera production backend processes are managed through Pterodactyl Panel/Wings. TheosferaProxy MUST NOT call the Pterodactyl power API directly.

The concrete process-start path is:

```text
TheosferaProxy
    -> HTTPS
    -> Theosfera Orchestration Gateway
    -> Pterodactyl Panel/Wings
    -> backend container/process
```

The Gateway is the authoritative fenced side-effect boundary required by B.2.

## 2. Why a Gateway is mandatory

Pterodactyl is the process plane, but its normal power-start operation does not carry Theosfera bootstrap ownership/fencing semantics.

This invalid design remains forbidden:

```text
Proxy
  -> Redis says fencing 41 is current
  -> delay / race
  -> direct Pterodactyl start
```

A newer owner may acquire fencing 42 during the delay, allowing stale owner 41 to emit a process side effect.

Therefore:

```text
fencing comparison
+ exact operation identity comparison
+ acceptance/emission of the Pterodactyl start side effect
```

must be one serialized Gateway decision for the exact backend target.

## 3. Trust boundaries

### TheosferaProxy owns

- distributed Proxy membership;
- distributed backend-bootstrap lease;
- exact bootstrap fencing token;
- startup lifecycle/retry/timeout;
- Control Channel readiness;
- Redis capacity after readiness;
- player transfer/presence handoff.

### Orchestration Gateway owns

- the last accepted fencing generation per configured Pterodactyl target;
- exact replay/idempotency detection;
- conflict detection;
- serialization of process-start acceptance/emission;
- Pterodactyl credentials;
- actual call into the Pterodactyl process plane.

### Pterodactyl owns

- Wings/node/container lifecycle;
- physical/container process execution;
- server resource limits and process supervision.

Pterodactyl process state is NEVER backend readiness for routing.

## 4. Required Gateway semantics

For one target backend, given incoming bootstrap fencing token `N`:

```text
N < highest accepted
    -> STALE_AUTHORITY
    -> zero new Pterodactyl start emission

N == highest accepted + exact same operation identity
    -> ACCEPTED
    -> idempotent replay; no duplicate process-start emission

N == highest accepted + different operation identity
    -> CONFLICT
    -> zero new process-start emission

N > highest accepted
    -> persist/accept newer authority
    -> serialize and emit at most one start side effect
    -> ACCEPTED
```

The exact operation identity includes at minimum:

- logical backend name;
- trusted Pterodactyl target reference;
- requestId;
- playerId;
- Proxy logical name;
- Proxy incarnationId;
- Proxy membership fencing token;
- backend bootstrap fencing token.

## 5. Proxy-to-Gateway protocol

Initial endpoint:

```text
POST /v1/backend-start
```

Transport requirements:

- HTTPS only;
- dedicated bearer token supplied to TheosferaProxy through an environment variable;
- no Pterodactyl token in TheosferaProxy;
- bounded request timeout;
- no redirects;
- no arbitrary URL/host supplied by players or admins.

Request fields:

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

Canonical response body is one exact token:

```text
ACCEPTED
STALE_AUTHORITY
CONFLICT
REJECTED
```

HTTP/network handling in Proxy:

```text
2xx + recognized body   -> mapped result
401/403/other 4xx       -> REJECTED (terminal misconfiguration/rejection)
408/425/429/5xx         -> ACTUATOR_UNAVAILABLE (B.3 retry policy)
network/timeout         -> ACTUATOR_UNAVAILABLE
malformed response      -> REJECTED fail-closed
redirect                -> REJECTED fail-closed
```

## 6. Configuration

Planned Proxy data file:

```text
orchestration.properties
```

Shape:

```properties
enabled=false
gateway-uri=https://orchestration.internal.example:25610
request-timeout-seconds=5
gateway-token-env=THEOSFERA_ORCHESTRATION_GATEWAY_TOKEN

target.lobby-1=<pterodactyl-server-reference>
target.lobby-2=<pterodactyl-server-reference>
target.skyblock-1=<pterodactyl-server-reference>
```

Rules:

- gateway URI is static config and HTTPS;
- token value is never stored in the file;
- target references are static config;
- duplicate target references are rejected to avoid two logical backends accidentally controlling one Pterodactyl server;
- target backend names must exist in backend policy;
- AUTH remains excluded from ordinary gameplay cold-start routing unless an explicit later operational policy enables it.

## 7. Readiness remains separate

Gateway `ACCEPTED` means only:

> this fenced start request was accepted/emitted according to orchestration authority.

It does NOT mean:

- the Pterodactyl server is running;
- Minecraft is accepting connections;
- TheosferaCore initialized;
- TLS/HMAC Control Channel authenticated;
- fresh PONG exists;
- Redis capacity is available;
- a player may connect.

The existing B.4 flow remains authoritative:

```text
Gateway ACCEPTED
    -> wait current authenticated Control Channel
    -> wait fresh PONG / HEALTHY
    -> exact bootstrap release
    -> re-resolve/revalidate
    -> Redis capacity
    -> ConnectionRequest
```

## 8. Gateway deployment direction

Run one Gateway instance per Pterodactyl node/VPS initially. Keep it private/internal where possible.

The Gateway implementation must persist fencing/idempotency state across process restart. A purely in-memory highest-token map is insufficient for production.

The exact persistence mechanism belongs to the Gateway implementation, not TheosferaProxy. It must support crash-safe replay semantics before B.6 can be marked complete.

## 9. Non-goals of the Proxy adapter increment

This increment does NOT:

- put Pterodactyl API credentials in Proxy;
- call Wings directly;
- call Docker directly;
- shell out to `systemctl`;
- weaken bootstrap fencing;
- activate the legacy cold-transfer replacement before Gateway runtime exists;
- treat Pterodactyl process state as backend health.

## 10. Acceptance gates before productive activation

1. Proxy adapter unit tests green.
2. Gateway implementation supports durable fencing/idempotency.
3. Gateway can start a provisioned Pterodactyl backend.
4. stale fencing causes zero new start emission.
5. exact replay causes zero duplicate start emission.
6. newer fencing supersedes older authority.
7. backend authenticates on current TLS/HMAC Control Channel.
8. new-generation PONG produces HEALTHY.
9. capacity is reserved only after readiness.
10. player transfer and `PLAYER_SERVER_READY` complete with exact cleanup.
11. Gateway/Panel outage remains fail-closed with no local fallback.
