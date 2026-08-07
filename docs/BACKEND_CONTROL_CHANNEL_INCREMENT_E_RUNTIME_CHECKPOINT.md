# Backend Control Channel — Increment E Runtime Checkpoint

## Estado

Increment E queda validado en código, build y runtime.

Objetivo cerrado:

> Retirar la identidad de backend transportada por jugadores (`BACKEND_HELLO` / `BACKEND_HELLO_ACK`) y convertir la sesión de control TLS/HMAC autenticada en la autoridad live de identidad y autorización del backend.

La identidad de backend ya no depende de la presencia de un jugador ni de Plugin Messaging.

## Ramas coordinadas

TheosferaProtocol:

```text
feature/backend-control-identity
validated head: e8286fd0d7377f3da2d3e9d93d95a0efa59427ed
```

TheosferaCore:

```text
feature/backend-control-identity
validated runtime-code head: 3ec5c9a761a0a4e85add6ae91d36f1255ff2ec55
```

TheosferaProxy:

```text
feature/backend-control-identity
validated runtime-code head: bc17c5fdb6dbefcf59f6ef12b68ca72085c9be31
```

## Protocol v2

`ProtocolVersion.CURRENT = 2`.

Retirados del contrato:

```text
BACKEND_HELLO
BACKEND_HELLO_ACK
BackendHelloPayload
BackendHelloAckPayload
```

Superficie v2 registrada:

```text
CONTROL_AUTH_CHALLENGE
CONTROL_AUTH_RESPONSE
CONTROL_AUTH_RESULT
PLAYER_AUTHENTICATED
PLAYER_AUTHENTICATED_ACK
PLAYER_SERVER_READY
TRANSFER_REQUEST
TRANSFER_RESULT
PING
PONG
```

`PING/PONG` pertenece al control channel persistente para health. Plugin Messaging queda reservado para tráfico player-scoped.

Protocol v2 es un cambio coordinado y breaking: no se deben mezclar artefactos v1 y v2.

## Autoridad de identidad live

Proxy proyecta identidad directamente desde la sesión de control autenticada actual.

Semántica:

```text
current TLS/HMAC control session
        -> BackendControlSessionRegistry
        -> BackendControlIdentityProvider
        -> live backend authorization
```

Perder la sesión current elimina inmediatamente la autorización live.

Una desconexión stale de una generación anterior no puede revocar una generación más nueva.

Se eliminaron de producción:

```text
BackendIdentityRegistry
BackendRegistrationResult
BackendHelloMessageHandler
```

Core eliminó:

```text
BackendHandshakeService
BackendHandshakeStatus
```

y dejó de emitir/consumir el handshake legacy.

## Bootstrap lifecycle

Antes de Increment E, `BACKEND_HELLO` era también una señal de lifecycle para limpiar una reserva cold/bootstrap.

Ese efecto fue migrado al control channel:

```text
successful control authentication
        -> session registered
        -> accepted CONTROL_AUTH_RESULT written
        -> authenticated identity listener
        -> bootstrapRegistry.removeByTarget(serverName)
```

Si falla el write del resultado aceptado, la sesión se revierte y el callback no se ejecuta.

## Fix runtime descubierto durante E

### Carrera control-loss -> kick

Runtime encontró una carrera real:

```text
lobby-1 pierde su sesión de control
        -> identidad live se revoca inmediatamente
        -> Velocity genera KickedFromServerEvent: Server closed
```

La primera versión E intentaba clasificar el backend origen consultando la identidad live que acababa de desaparecer, por lo que aplicaba disconnect fail-closed antes de llegar al coordinator Redis.

Corrección final:

- el backend ORIGEN del kick se clasifica con `BackendAuthorizationPolicy`;
- la policy solamente responde qué backend configurado/tipo produjo el kick;
- NO conserva ni restaura autorización live;
- los DESTINOS siguen exigiendo:
  - identidad control live exacta;
  - tipo correcto;
  - `HEALTHY`;
  - capacidad distribuida Redis;
  - `PlayerSessionLease` exacto;
  - fencing y reserva atómica.

Esto permite revocar el backend muerto inmediatamente y aun así rescatar al jugador de forma segura.

Commits del fix:

```text
9f0b8fe fix: classify failed backend from policy after control loss
bc17c5f fix: wire kick source classification to backend policy
```

Se añadió cobertura específica para el caso donde el backend fallido sigue configurado en policy pero su live control identity ya desapareció.

## UX restaurada

Durante la validación se detectó que la UX player-facing previamente aprobada había quedado en una rama histórica no fusionada.

Se portaron únicamente los cambios visuales sobre Core Increment E, sin traer networking legacy.

Resultado runtime:

```text
Enviándote a Skyblock...
Has llegado a tu destino.
Redireccionando a Lobby-2...
```

No se vuelve a mostrar al jugador `requestId`, enums internos ni detalles de infraestructura.

## Validación automatizada

### TheosferaProtocol

```text
grep production legacy Hello -> limpio
full test suite             -> PASS
clean build                 -> PASS
working tree                -> clean
```

### TheosferaProxy

Gate final posterior al fix de kick:

```text
BackendKickFailoverServiceTest
BackendKickFailoverListenerTest
DistributedBackendKickFailoverCoordinatorTest
-> BUILD SUCCESSFUL

full test suite
-> BUILD SUCCESSFUL

clean build
-> BUILD SUCCESSFUL

working tree
-> clean
```

### TheosferaCore

Gate final posterior a la restauración UX:

```text
NetworkTransferCommandHandlerTest
PlayerTransferServiceTest
-> BUILD SUCCESSFUL

full test suite
-> BUILD SUCCESSFUL

clean build
-> BUILD SUCCESSFUL

working tree
-> clean
```

## Artefactos runtime finales

### TheosferaProxy

```text
TheosferaProxy-0.1.0-SNAPSHOT.jar
Size: 8,653,101 bytes
SHA-256: 3553128737313787A4507C54872FFF0183C54D1474276619471810C568674ADA
```

### TheosferaCore

```text
TheosferaCore-0.1.0-SNAPSHOT.jar
Size: 574,901 bytes
SHA-256: 99F46F72DED02F8E6C774BB120A5796AB4B79D79D782FF3DEC9EEDCB5824C6FA
```

## TLS runtime actualizado

Se regeneró el certificado dev después de perderse la contraseña histórica del keystore.

No se almacenan contraseñas ni secretos en Git.

Certificado de control:

```text
Subject: CN=localhost, OU=Theosfera, O=Theosfera, C=EC
RSA: 3072 bits
SAN: localhost, 127.0.0.1
Valid until: 2036-08-03
SHA-256 fingerprint:
F1:96:6F:0C:BE:89:FE:CF:37:11:17:3C:05:DA:41:2E:A3:7C:78:70:7C:49:E9:F1:69:4E:B8:CC:60:D3:04:4C
```

Truststore distribuido a los cuatro Core:

```text
SHA-256: A52C47823D567B0A74C04FC5E775BFD7C2AEB6F7522F9B7F51551FED5E35BC20
```

La contraseña TLS se conserva fuera del repositorio. Los HMAC por backend permanecen en el archivo runtime de secretos del Proxy y nunca deben registrarse en logs, documentación o Git.

## Runtime PASS — zero-player identity

Con cero jugadores conectados:

```text
auth-1       HEALTHY | Autenticado: Sí | connected=0
lobby-1      HEALTHY | Autenticado: Sí | connected=0
lobby-2      HEALTHY | Autenticado: Sí | connected=0
skyblock-1   HEALTHY | Autenticado: Sí | connected=0
```

Esto prueba que identidad y health ya no necesitan player carrier.

## Runtime PASS — control loss

Se detuvo `lobby-2`.

Resultado:

```text
lobby-2 [LOBBY] — STALE
Velocity: Sí
Autenticado: No
Conectados en este proxy: 0
```

Los demás backends permanecieron `HEALTHY` y autenticados.

La autorización live desapareció al perder la sesión de control; health expiró según la freshness policy.

## Runtime PASS — reconnect

`lobby-2` volvió a iniciar y se observó:

```text
Backend lobby-2 autenticado en control channel (generation 5).
```

Luego:

```text
lobby-2 [LOBBY] — HEALTHY
Autenticado: Sí
Conectados en este proxy: 0
```

La salud se mantuvo fresca en verificaciones posteriores.

## Runtime PASS — Auth -> Lobby

Secuencia observada:

```text
HarriOcho -> auth-1
nLogin login success
Sesión autenticada registrada desde auth-1
HarriOcho -> lobby-1
HarriOcho -> auth-1 disconnected
Jugador ... listo en lobby-1
```

Snapshot posterior:

```text
auth-1  connected=0
lobby-1 connected=1
```

## Runtime PASS — transfer UX + /hub

Secuencia final:

```text
lobby-1 -> skyblock-1
Jugador ... listo en skyblock-1
```

Cliente:

```text
Enviándote a Skyblock...
```

Luego `/hub`:

```text
skyblock-1 -> lobby-1
Jugador ... listo en lobby-1
```

## Runtime PASS — kick failover después de revocar control identity

Escenario final:

```text
HarriOcho -> lobby-1
lobby-2 HEALTHY + authenticated
stop lobby-1
```

Evidencia Proxy:

```text
Backend lobby-1 perdio su sesion de control (generation 2).
HarriOcho: kicked from server lobby-1: Server closed
HarriOcho -> lobby-1 disconnected
HarriOcho -> lobby-2 connected
Jugador ... listo en lobby-2
```

Cliente:

```text
Redireccionando a Lobby-2...
```

Snapshot final:

```text
auth-1      HEALTHY | Autenticado: Sí | 0
lobby-1     STALE   | Autenticado: No | 0
lobby-2     HEALTHY | Autenticado: Sí | 1
skyblock-1  HEALTHY | Autenticado: Sí | 0
```

Esto prueba que:

- el backend muerto pierde autorización antes del kick;
- la revocación fail-closed no bloquea el failover;
- el origen se clasifica exclusivamente desde policy;
- el destino exige autoridad live del control channel;
- la reserva distribuida sigue funcionando después de retirar Hello.

## Redis residue gate

Después del failover y del `PLAYER_SERVER_READY` del destino:

```text
redis-cli --scan --pattern "*backend-capacity*"
```

Resultado:

```text
<sin salida>
```

No quedaron reservas `backend-capacity:*` residuales.

## Resultado final

```text
DESIGN                    PASS
PROTOCOL v2               PASS
PROXY CODE/BUILD          PASS
CORE CODE/BUILD           PASS
LEGACY HELLO RETIREMENT   PASS
ZERO-PLAYER IDENTITY      PASS
ZERO-PLAYER HEALTH        PASS
CONTROL LOSS              PASS
RECONNECT                 PASS
AUTH -> LOBBY             PASS
PLAYER TRANSFER           PASS
/HUB                      PASS
KICK FAILOVER             PASS
REDIS RELEASE/RESIDUE     PASS
```

Increment E queda `RUNTIME ACCEPTED`.

## Siguiente paso

Abrir/validar los PR coordinados en este orden contractual:

```text
1. TheosferaProtocol v2
2. TheosferaCore Increment E
3. TheosferaProxy Increment E
```

Antes de mergear:

- revisar CI de los tres PR;
- resolver cualquier review pendiente;
- no introducir más refactors de arquitectura en E;
- mantener deployment coordinado v2.

Después del merge, actualizar `PROJECT_STATE.md` con el checkpoint final y definir el siguiente Increment del backend control plane.
