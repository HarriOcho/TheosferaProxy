# Increment E — Post-Merge Checkpoint

## Estado

Increment E quedó fusionado y cerrado en los tres repositorios coordinados.

La identidad y autorización live de los backends ya no dependen de jugadores portadores ni de `BACKEND_HELLO` / `BACKEND_HELLO_ACK`. La autoridad live es la sesión persistente TLS/HMAC de control autenticada para el nombre/tipo exactos del backend.

## Merge coordinado

Orden ejecutado:

1. TheosferaProtocol PR #14
   - squash merge: `8757cf940fbf5c3d92d6f244404ceb8c252e8a8d`
   - título: `feat: retire legacy backend hello in protocol v2 (#14)`
2. TheosferaCore PR #20
   - squash merge: `ad979f88bcfd5f3f7a71c075331710d76a8aa3f9`
   - título: `feat(network): authorize backend traffic from live control sessions (#20)`
3. TheosferaProxy PR #72
   - squash merge: `5e2f0f0044e5a3688782917e263461b84311bce1`
   - título: `feat: retire player-carried backend identity handshake (#72)`

Protocol v2 es breaking respecto de v1; los artefactos Core/Proxy correspondientes deben desplegarse coordinadamente y no mezclarse con v1.

## Superficie de protocolo v2

Tipos registrados vigentes:

- `CONTROL_AUTH_CHALLENGE`
- `CONTROL_AUTH_RESPONSE`
- `CONTROL_AUTH_RESULT`
- `PLAYER_AUTHENTICATED`
- `PLAYER_AUTHENTICATED_ACK`
- `PLAYER_SERVER_READY`
- `TRANSFER_REQUEST`
- `TRANSFER_RESULT`
- `PING`
- `PONG`

Retirados:

- `BACKEND_HELLO`
- `BACKEND_HELLO_ACK`

`PING` / `PONG` permanecen exclusivamente en el control channel persistente para health; Plugin Messaging queda reservado para mensajes player-scoped.

## Autoridad de identidad

Estado productivo:

- Proxy proyecta identidad desde la sesión de control autenticada actual;
- perder la sesión actual elimina la autorización live fail-closed;
- una generación vieja no puede revocar una generación nueva;
- Core publica presence, authentication y transferencias solo con control authorization live;
- no existe fallback silencioso a identidad histórica ni a Hello mediante Plugin Messaging;
- `BackendIdentityRegistry` y `BackendRegistrationResult` ya no existen en producción;
- `BackendHandshakeService` y `BackendHandshakeStatus` fueron retirados de Core.

La policy estática sigue siendo fuente autorizada de nombre/tipo/capacidad/preferencia. Después de una pérdida de control, el kick failover puede clasificar el backend origen mediante policy sin conservar una identidad stale. Los destinos continúan exigiendo identidad live, health vigente y capacidad distribuida Redis.

## Runtime acceptance

Topología validada:

```text
proxy-1
auth-1
lobby-1
lobby-2
skyblock-1
```

Prueba zero-player:

```text
auth-1      HEALTHY | Autenticado: Sí | players=0
lobby-1     HEALTHY | Autenticado: Sí | players=0
lobby-2     HEALTHY | Autenticado: Sí | players=0
skyblock-1  HEALTHY | Autenticado: Sí | players=0
```

Esto demuestra que ningún jugador es necesario para identidad ni health.

Control loss / reconnect:

- `lobby-2` perdió su sesión de control y pasó a `STALE | Autenticado: No`;
- el resto de backends permaneció HEALTHY;
- el reconnect autenticó `lobby-2` en una generación nueva (`generation 5` observada) y recuperó `HEALTHY | Autenticado: Sí`.

Player flows:

- Auth -> Lobby: PASS;
- `/theosfera transfer skyblock`: PASS;
- UX aprobada restaurada (`Enviándote a Skyblock...`) sin exponer requestId interno;
- `/hub` Skyblock -> Lobby: PASS;
- kick real de `lobby-1` después de perder control: PASS hacia `lobby-2`;
- `PLAYER_SERVER_READY` confirmó la llegada a `lobby-2`.

Estado final del failover:

```text
lobby-1  STALE   | Autenticado: No | players=0
lobby-2  HEALTHY | Autenticado: Sí | players=1
```

Redis después del handoff:

```text
redis-cli --scan --pattern "*backend-capacity*"
```

Resultado: sin salida; cero residuos de reserva de capacidad.

## Carrera runtime corregida

La validación descubrió que Velocity podía entregar el kick inmediatamente después de que el control channel del backend origen ya hubiera sido revocado. El failover intentaba clasificar el origen mediante la identidad live, encontraba vacío y desconectaba al jugador con `Server closed`.

Corrección final:

- origen del kick: clasificación mediante `BackendAuthorizationPolicy`;
- destino: sigue requiriendo identidad control live + `HEALTHY` + capacidad Redis;
- no se conserva identidad stale;
- no se retrasa la revocación de autorización;
- fail-closed permanece intacto.

Runtime posterior al fix:

```text
Backend lobby-1 perdió su sesión de control
HarriOcho kicked from lobby-1: Server closed
HarriOcho -> lobby-2 connected
Jugador ... listo en lobby-2
```

## Artefactos runtime validados

Proxy:

- `TheosferaProxy-0.1.0-SNAPSHOT.jar`
- size: `8,653,101` bytes
- SHA-256: `3553128737313787A4507C54872FFF0183C54D1474276619471810C568674ADA`

Core:

- `TheosferaCore-0.1.0-SNAPSHOT.jar`
- size: `574,901` bytes
- SHA-256: `99F46F72DED02F8E6C774BB120A5796AB4B79D79D782FF3DEC9EEDCB5824C6FA`

TLS dev certificate usado en la aceptación final:

- certificate SHA-256 fingerprint: `F1:96:6F:0C:BE:89:FE:CF:37:11:17:3C:05:DA:41:2E:A3:7C:78:70:7C:49:E9:F1:69:4E:B8:CC:60:D3:04:4C`
- SAN: `localhost`, `127.0.0.1`
- truststore SHA-256: `A52C47823D567B0A74C04FC5E775BFD7C2AEB6F7522F9B7F51551FED5E35BC20`

No se documentan ni versionan contraseñas ni secretos HMAC.

## CI final

- TheosferaProtocol Build #28: SUCCESS;
- TheosferaCore Build #42: SUCCESS;
- TheosferaProxy Build #156: SUCCESS.

Durante el cierre se detectó que los workflows Core/Proxy estaban mezclando la rama E con Protocol `main` v1. Se corrigió el checkout condicionado para que los PR coordinados validaran contra Protocol E y `main` continúe validando contra Protocol `main` después del merge.

## Resultado final

Increment E está cerrado:

```text
BACKEND_HELLO              RETIRED
BACKEND_HELLO_ACK          RETIRED
player-carried identity    RETIRED
TLS/HMAC control identity  AUTHORITATIVE
Protocol                   v2
zero-player identity       PASS
control loss               PASS
reconnect/fencing          PASS
Auth -> Lobby              PASS
transfer / hub             PASS
kick failover              PASS
Redis capacity cleanup     PASS
CI                         PASS
```

El siguiente trabajo debe partir de este estado post-merge y no reintroducir identidad backend mediante Plugin Messaging.