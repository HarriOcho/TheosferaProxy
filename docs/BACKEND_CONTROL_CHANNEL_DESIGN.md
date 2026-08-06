# Backend Control Channel — Design

Status: design baseline for implementation

## 1. Objective

Remove the player-carrier dependency from backend-level identity and health.

Today `BACKEND_HELLO`, `BACKEND_HELLO_ACK`, `PING` and `PONG` travel through
Minecraft Plugin Messaging. That transport requires a connected player. As a
result, an otherwise healthy empty backend cannot prove its current identity or
answer health challenges.

The control channel introduced by this milestone is a backend-level transport
between TheosferaCore and TheosferaProxy. Player-scoped traffic remains on the
existing Plugin Messaging path during this milestone.

## 2. Scope

Move only backend control-plane traffic to the new channel:

```text
BACKEND_HELLO
BACKEND_HELLO_ACK
PING
PONG
```

Keep these messages on the existing player-scoped path:

```text
PLAYER_AUTHENTICATED
PLAYER_AUTHENTICATED_ACK
PLAYER_SERVER_READY
TRANSFER_REQUEST
TRANSFER_RESULT
```

Do not change Redis player sessions, Redis presence, backend capacity,
transfer retry, kick failover, Lobby instance switching or raw `/server`
hardening.

Distributed backend bootstrap remains a later milestone.

## 3. Architectural boundary

Redis remains coordination infrastructure between Proxy processes. TheosferaCore
must not become a general Redis coordination client merely to solve backend
health.

The control channel is a separate transport boundary:

```text
TheosferaCore
    |
    | outbound persistent authenticated control connection
    v
TheosferaProxy
```

A backend establishes one control connection to each configured Proxy control
endpoint. This direction avoids exposing one additional control listener on every
backend and keeps the accepted inbound surface centralized on Proxy instances.

## 4. Multi-proxy semantics

A backend may be simultaneously connected to multiple Proxy instances.

Each Proxy independently authenticates the backend and maintains its own live
control session. A control session is local process state and must not be shared
through Redis.

The backend identity itself remains the configured logical backend identity,
for example:

```text
lobby-1 / LOBBY
skyblock-1 / SKYBLOCK
```

A connection to `proxy-1` does not make the connection to `proxy-2` healthy and
vice versa.

## 5. Transport

The implementation must use a persistent stream connection and explicit message
framing. The first implementation should prefer JDK networking primitives over
adding a second large networking stack to both Core and Proxy.

Frames are length-prefixed and bounded. An invalid, negative, zero where not
allowed, or oversized frame closes the control connection fail-closed.

`ProtocolJsonCodec.MAX_MESSAGE_BYTES` remains the protocol payload upper bound.
The transport must never allocate an unbounded buffer from an untrusted length.

Transport I/O must never block Velocity's event loop or Paper's server thread.
Connection accept, read, write, reconnect and authentication run on owned
lifecycle executors/threads.

## 6. Security model

A raw TCP connection is never proof of backend identity or health.

The channel must authenticate both sides before any control-plane message is
trusted.

### 6.1 Proxy authentication

Production control connections use TLS. The backend validates the Proxy control
certificate against explicitly configured trust material. Trust-all TLS is not
allowed.

Development may use a dedicated development certificate, but verification
remains enabled.

### 6.2 Backend authentication

Each backend has a distinct control secret. Secrets are configuration, never
part of `TheosferaProtocol`, logs, Redis state or Git-tracked runtime defaults.

After TLS is established the Proxy issues a cryptographically random challenge.
The backend answers with an HMAC-SHA-256 proof bound at minimum to:

```text
challenge
backendName
backendType
protocolVersion
```

The secret itself is never transmitted.

The Proxy verifies the proof with a constant-time comparison and rejects:

- unknown backend names;
- backend type mismatch;
- missing/invalid secret configuration;
- invalid HMAC;
- incompatible protocol version;
- malformed authentication frames;
- authentication timeout.

A challenge is single-use for one connection. Replaying a proof from another
connection does not authenticate a new connection.

## 7. Identity ownership

`BackendIdentityRegistry` remains the Proxy-side logical authority for which
configured backend identity has been authenticated.

The source of authentication changes from a player-carried `BACKEND_HELLO` to an
authenticated control session.

The control session must bind the network connection to exactly one configured
backend name/type. A connection cannot change identity after authentication.

If a second live connection claims the same backend identity on the same Proxy,
the implementation must use an explicit generation/connection ownership rule;
it must never silently treat both as authoritative.

The exact duplicate-connection replacement policy will be implemented and tested
before runtime activation.

## 8. Core authorization model

TheosferaCore currently tracks authorization per player carrier. That model must
be replaced for backend-level authorization.

After the control channel is authenticated:

```text
backend control state = AUTHORIZED
```

Player-scoped protocol publishers may then use their existing player Plugin
Messaging carrier without performing a second backend identity handshake for
each player.

A player carrier is transport for player-scoped data only; it is no longer the
proof that the backend itself is authorized.

If no authenticated control session to an applicable Proxy exists, Core fails
closed for operations that require Proxy authorization.

## 9. Health model

`PING` and `PONG` move to the control connection.

The Proxy remains the active challenger:

```text
Proxy -> PING(requestId, sentAt)
Core  -> PONG(correlated requestId)
```

Existing correlation, timeout and freshness semantics should be preserved where
possible.

A valid PONG is accepted only from the authenticated control connection bound to
the expected backend identity.

Important invariant:

```text
TCP connected != authenticated
TLS established != backend healthy
authenticated != fresh
fresh PONG == current health evidence
```

An empty backend with a live authenticated control connection can therefore stay
`HEALTHY` without a player carrier.

Connection loss alone invalidates the current transport immediately. Health then
transitions according to the explicit registry/freshness policy; stale historical
identity must never keep routing a backend indefinitely.

## 10. Player-scoped Plugin Messaging

The existing Minecraft channel remains necessary in this milestone because the
following operations intrinsically involve a player connection:

- authenticated-player publication;
- transfer requests originating from a backend player;
- transfer results delivered to the current player carrier;
- destination-ready publication.

The player transport does not become a fallback for backend-level
`BACKEND_HELLO` or health. Once control-channel health is activated, silently
falling back to player-carried backend health is prohibited.

## 11. Lifecycle

### Proxy

Control listener lifecycle is owned by TheosferaProxy:

```text
initialize listener
accept connections
authenticate
register control session
start/serve health traffic
close sessions
stop listener
```

Listener initialization failure is explicit. The final activation policy
(fail plugin startup versus disable only the control surface) must be decided
before wiring production health to it; there must be no silent legacy fallback.

### Core

Control client lifecycle is owned by `TheosferaNetworkModule` or a dedicated
component composed by it:

```text
initialize
connect to configured Proxy endpoints
authenticate
reconnect with bounded backoff
close cleanly on plugin shutdown
```

Reconnect loops must be bounded/backed off and cancellable. They must not busy
spin or prevent plugin shutdown.

## 12. Configuration

No secrets are committed to the repository.

Proxy configuration will need, at minimum:

```text
control bind host
control bind port
TLS identity/trust configuration
per-backend authentication secret reference/value
authentication timeout
```

Core configuration will need, at minimum:

```text
one or more Proxy control endpoints
TLS trust configuration
backend authentication secret
connect timeout
authentication timeout
reconnect policy
```

Configuration validation fails early for malformed endpoints, invalid ports,
blank secrets, missing trust configuration and duplicate Proxy endpoint names.

The concrete file layout is implementation work; this design does not force the
existing `backends.properties` file to absorb unrelated secret material.

## 13. Observability

Never log control secrets, raw HMACs or private keys.

Useful structured/loggable facts include:

```text
proxy name
backend name
backend type
control connection generation/id
authenticated / rejected / disconnected
reason category
PING requestId / timeout
health transition
```

Authentication failures should be rate-conscious to avoid log flooding.

## 14. Implementation sequence

### Increment A — protocol/control primitives

- define control authentication payloads/contracts;
- define bounded framing;
- tests for malformed/oversized frames;
- tests for challenge correlation and HMAC verification;
- no production health switch yet.

### Increment B — Proxy control server

- listener lifecycle;
- TLS;
- authentication;
- connection registry/generation ownership;
- authenticated BACKEND_HELLO handling;
- unit/integration tests.

### Increment C — Core control client

- endpoint configuration;
- TLS verification;
- authentication;
- reconnect/backoff lifecycle;
- global backend authorization state;
- tests.

### Increment D — health migration

- move PING/PONG to control transport;
- stop using `BackendPingConnectionResolver` for backend-level health;
- preserve pending correlation/freshness semantics;
- prohibit silent fallback to player carriers;
- validate an empty backend stays healthy.

### Increment E — carrier-handshake retirement

- remove per-player backend identity handshake where safe;
- retain Plugin Messaging only for player-scoped messages;
- verify authentication, transfer and presence flows remain unchanged.

## 15. Runtime acceptance matrix

Minimum required runtime evidence before the milestone is complete:

```text
backend with 0 players + valid control auth -> HEALTHY
backend with 0 players + repeated valid PONG -> remains HEALTHY
backend process stopped -> loses freshness / becomes unavailable
wrong backend secret -> never authorized / never HEALTHY
wrong backend type -> rejected
unknown backend name -> rejected
invalid TLS trust -> connection rejected
replayed auth proof -> rejected
malformed/oversized frame -> connection closed fail-closed
proxy-1 control loss while proxy-2 remains connected -> independent state
player joins empty backend -> no backend identity bootstrap through player needed
PLAYER_SERVER_READY -> still works
PLAYER_AUTHENTICATED -> still works
TRANSFER_REQUEST / TRANSFER_RESULT -> still works
Redis outage semantics -> unchanged from existing Proxy coordination policy
```

## 16. Non-goals

This milestone does not:

- start or stop backend processes;
- implement a remote orchestration agent;
- make a TCP port check equivalent to health;
- replace Redis Proxy coordination;
- move player-scoped messages off Plugin Messaging;
- introduce parties, friends, squads or gameplay systems;
- declare Folia support.

## 17. Next milestone after completion

Once backend identity and health are independent of players, the next separate
architecture milestone may design Distributed Backend Bootstrap/orchestration.
That system must consume authenticated control/health evidence but remains a
separate ownership boundary.
