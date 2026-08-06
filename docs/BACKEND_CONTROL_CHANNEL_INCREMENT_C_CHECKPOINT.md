# Backend Control Channel — Increment C Runtime Checkpoint

Status: **END-TO-END RUNTIME PASS / FROZEN**

Date: 2026-08-06

This checkpoint records the completed runtime validation of Increment C: the outbound TheosferaCore backend control client authenticating persistently against the TheosferaProxy TLS control listener without any player carrier.

## Validated topology

```text
proxy-1
  Velocity: 127.0.0.1:25565
  Control TLS: 127.0.0.1:25590

lobby-1
  Purpur/Paper 1.21.11
  Backend: 127.0.0.1:25566
  Players during validation: 0
```

Runtime backend policy contained:

```properties
auth-1=AUTH,1,100
lobby-1=LOBBY,100,90
lobby-2=LOBBY,100,80
skyblock-1=SKYBLOCK,200,80
```

No private keys, passwords, HMAC secrets, or certificate material are committed by this checkpoint.

## Build and deployment evidence

The feature-branch artifacts were built successfully before deployment.

Proxy runtime JAR matched the feature-branch build exactly by SHA-256:

```text
8BB12FDB15DE67609385C8BE329090CEA2791319B4DC9051571DA67C03FC880A
```

Core runtime JAR matched the feature-branch build exactly by SHA-256:

```text
45CD0169998A5E5D1E59857F7FE50E2C7386BC3A0C27064852D35250A84BCF93
```

## TLS provisioning evidence

The Proxy used a local PKCS#12 server keystore containing one `PrivateKeyEntry` named `theosfera-control`.

The public certificate was imported into the lobby-1 Core truststore as one `trustedCertEntry` named `proxy-1-control`.

The certificate SHA-256 fingerprint matched on both sides:

```text
16:B5:07:BB:C6:34:41:E8:45:8E:55:69:28:16:11:DA:65:9D:FF:BC:D9:A4:B1:64:71:67:BB:9A:46:C1:2E:4A
```

The certificate SAN covered the configured local endpoint (`127.0.0.1`, with `localhost` also provisioned).

## Fail-closed configuration validation

Before supplying runtime environment variables, the new artifacts were intentionally observed failing closed.

Proxy rejected activation of the operational surface when its configured keystore password environment variable was absent:

```text
Backend control keystore password environment variable is missing: THEOSFERA_CONTROL_KEYSTORE_PASSWORD
```

Core disabled only the control client during the migration when its backend secret environment variable was absent, while keeping existing Plugin Messaging available:

```text
Backend control secret environment variable is missing: THEOSFERA_CONTROL_BACKEND_SECRET
```

This confirmed that secrets are not silently substituted and that missing control credentials do not produce a false authenticated state.

## Environment isolation used for the successful run

The successful Proxy process received only the control server keystore password environment variable.

The successful lobby-1 process received only:

```text
THEOSFERA_CONTROL_TRUSTSTORE_PASSWORD
THEOSFERA_CONTROL_BACKEND_SECRET
```

The lobby-1 process did not receive the Proxy private-keystore password.

## Successful zero-player authentication

With Proxy running and lobby-1 containing zero connected Minecraft players, Proxy logged:

```text
Backend control TLS listener iniciado en /127.0.0.1:25590.
Backend lobby-1 autenticado en control channel (generation 1).
```

Core logged:

```text
Backend control client iniciado para 1 Proxy endpoint(s).
Control channel autenticado con proxy-1 (generation 1).
```

Therefore the runtime completed, without a player carrier:

```text
Core outbound TCP connection
  -> TLS 1.3
  -> Proxy certificate/trust validation
  -> CONTROL_AUTH_CHALLENGE
  -> HMAC-SHA256 response for lobby-1
  -> CONTROL_AUTH_RESULT accepted
  -> authenticated persistent control session
```

## Session teardown validation

After stopping lobby-1 while leaving Proxy alive, Proxy observed exact session loss:

```text
Backend lobby-1 perdio su sesion de control (generation 1).
```

This validated cleanup of the active control session rather than leaving a stale authenticated session.

## Restart and fencing validation

lobby-1 was then started again with zero players while Proxy remained alive.

Proxy accepted a new session:

```text
Backend lobby-1 autenticado en control channel (generation 2).
```

The restarted Core process logged:

```text
Backend control client iniciado para 1 Proxy endpoint(s).
Control channel autenticado con proxy-1 (generation 1).
```

The generation numbers are intentionally local to each process/session registry:

- Proxy remained alive, so its generation advanced from 1 to 2.
- Core restarted, so its local generation sequence restarted at 1.

The successful Proxy generation increment demonstrates replacement/fencing progression across backend reconnects without requiring a player carrier.

## Increment C acceptance result

The following runtime gates are PASS:

```text
Proxy TLS listener startup                 PASS
Core outbound TLS connection               PASS
PKCS#12 trust verification                 PASS
Hostname/IP SAN verification               PASS
HMAC challenge-response                    PASS
Backend identity acceptance                PASS
Persistent authenticated session           PASS
Zero-player operation                      PASS
Session teardown detection                 PASS
Reconnect after backend restart            PASS
Proxy generation advancement               PASS
Secret/environment fail-closed behavior    PASS
```

Increment C is frozen.

## Explicit limitation before Increment D

Backend health has **not** moved to the control connection yet.

The legacy health path still depends on player-carried Plugin Messaging, so this remains valid before Increment D:

```text
0 players + authenticated control session -> control AUTHORIZED
0 players + legacy Plugin Messaging health -> may remain UNKNOWN/STALE
```

An `UNKNOWN` or stale legacy health state with zero players is not a failure of Increment C.

## Exact next increment

Increment D must migrate backend health `PING` / `PONG` to the authenticated persistent control session.

Target behavior:

```text
backend alive + 0 players
  -> authenticated control session
  -> Proxy PING
  -> Core PONG
  -> correlated fresh health
  -> HEALTHY
```

Player-scoped authentication, ready, presence, and transfer messages remain on Plugin Messaging during this increment.

Only after control-channel health is stable should Increment E retire the player-carried backend identity handshake (`BACKEND_HELLO` / `BACKEND_HELLO_ACK`).
