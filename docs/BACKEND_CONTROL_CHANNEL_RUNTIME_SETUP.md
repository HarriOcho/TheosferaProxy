# Backend Control Channel — Runtime Setup

Status: local runtime provisioning guide for the feature branches.

This guide provisions the first end-to-end runtime validation of the secure backend control channel without committing certificates, private keys, passwords, or HMAC secrets.

## Scope of the first runtime

Use one Proxy instance and one empty backend:

```text
proxy-1  <---- TLS 1.3 + HMAC ----  lobby-1
                                  0 players
```

This validates authenticated persistent control connectivity only. PING/PONG health migration is a later increment, so an empty backend may still remain UNKNOWN after authentication until Increment D is implemented.

## Runtime contract

Proxy defaults:

```properties
enabled=false
bind-host=127.0.0.1
bind-port=25590
authentication-timeout-seconds=5
keystore-path=control-server.p12
keystore-password-env=THEOSFERA_CONTROL_KEYSTORE_PASSWORD
secrets-file=control-secrets.properties
```

Core endpoint for the first runtime:

```yaml
network:
  enabled: true
  backend-name: "lobby-1"
  backend-type: "LOBBY"
  control:
    enabled: true
    proxies:
      proxy-1:
        host: "127.0.0.1"
        port: 25590
    trust-store: "control-truststore.p12"
    trust-store-password-env: "THEOSFERA_CONTROL_TRUSTSTORE_PASSWORD"
    backend-secret-env: "THEOSFERA_CONTROL_BACKEND_SECRET"
    connect-timeout-ms: 3000
    authentication-timeout-ms: 3000
    reconnect:
      initial-delay-ms: 1000
      max-delay-ms: 30000
```

## Security material

Generate all material locally. Never commit these files or values.

### Authorized backend set is runtime-derived

`control-secrets.properties` must contain one distinct valid Base64URL secret for **every backend currently authorized by the runtime `backends.properties` file**. Do not rely on repository defaults or an old list copied into documentation.

Before provisioning, inspect the actual Proxy data directory:

```powershell
Get-Content .\backends.properties
```

For the current local development runtime observed on 2026-08-06, the authorized identities are:

```text
auth-1
lobby-1
lobby-2
skyblock-1
```

That list is illustrative only. If `backends.properties` changes later, the secret file must change with it. Missing or extra unauthorized backend secrets are rejected fail-closed.

### PowerShell helper

```powershell
function New-Base64UrlSecret([int]$Bytes = 32) {
    $buffer = New-Object byte[] $Bytes
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($buffer)
    } finally {
        $rng.Dispose()
    }

    return [Convert]::ToBase64String($buffer).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}
```

Generate one distinct secret per authorized backend plus two independent local passwords for the Proxy keystore and Core truststore. Do not print these values into logs or commit them.

For the current four-backend development policy:

```powershell
$AUTH_SECRET = New-Base64UrlSecret
$LOBBY_SECRET = New-Base64UrlSecret
$LOBBY2_SECRET = New-Base64UrlSecret
$SKYBLOCK_SECRET = New-Base64UrlSecret
$KEYSTORE_PASSWORD = New-Base64UrlSecret
$TRUSTSTORE_PASSWORD = New-Base64UrlSecret
```

## Proxy provisioning

Run these commands from the `plugins/theosferaproxy` data directory of proxy-1.

Create a TLS server keypair with SAN entries matching the Core endpoint host:

```powershell
$env:THEOSFERA_CONTROL_KEYSTORE_PASSWORD = $KEYSTORE_PASSWORD

keytool -genkeypair `
  -alias theosfera-control `
  -keyalg RSA `
  -keysize 3072 `
  -sigalg SHA256withRSA `
  -validity 3650 `
  -storetype PKCS12 `
  -keystore control-server.p12 `
  -storepass $env:THEOSFERA_CONTROL_KEYSTORE_PASSWORD `
  -dname "CN=Theosfera Local Control, OU=Development, O=Theosfera, C=EC" `
  -ext "SAN=ip:127.0.0.1,dns:localhost" `
  -ext "EKU=serverAuth"
```

Export the public certificate:

```powershell
keytool -exportcert `
  -alias theosfera-control `
  -keystore control-server.p12 `
  -storetype PKCS12 `
  -storepass $env:THEOSFERA_CONTROL_KEYSTORE_PASSWORD `
  -rfc `
  -file control-server.crt
```

Create the backend secret file without a UTF-8 BOM. The entries must exactly match the current runtime policy. For the current development policy:

```powershell
@"
auth-1=$AUTH_SECRET
lobby-1=$LOBBY_SECRET
lobby-2=$LOBBY2_SECRET
skyblock-1=$SKYBLOCK_SECRET
"@ | Set-Content -Encoding ASCII control-secrets.properties
```

Create or enable `control.properties` and keep the first runtime on `127.0.0.1:25590`:

```properties
enabled=true
bind-host=127.0.0.1
bind-port=25590
authentication-timeout-seconds=5
keystore-path=control-server.p12
keystore-password-env=THEOSFERA_CONTROL_KEYSTORE_PASSWORD
secrets-file=control-secrets.properties
```

## Core provisioning

Copy only the exported public certificate `control-server.crt` to the `plugins/TheosferaCore` data directory of `lobby-1`. Never copy the Proxy private keystore to Core.

From the Core data directory:

```powershell
$env:THEOSFERA_CONTROL_TRUSTSTORE_PASSWORD = $TRUSTSTORE_PASSWORD

keytool -importcert `
  -noprompt `
  -alias proxy-1-control `
  -file control-server.crt `
  -keystore control-truststore.p12 `
  -storetype PKCS12 `
  -storepass $env:THEOSFERA_CONTROL_TRUSTSTORE_PASSWORD
```

Before launching lobby-1 in the same PowerShell process environment:

```powershell
$env:THEOSFERA_CONTROL_BACKEND_SECRET = $LOBBY_SECRET
```

If the Proxy and backend are launched from different PowerShell windows, provision the required process-local environment variables in the corresponding launch window. Do not use one machine-wide backend secret for multiple distinct backend identities.

## Launch order

1. Start Redis and the normal coordination dependencies used by TheosferaProxy.
2. Start proxy-1 with `THEOSFERA_CONTROL_KEYSTORE_PASSWORD` available to its process and control channel enabled.
3. Confirm the Proxy logs:

```text
Backend control TLS listener iniciado en /127.0.0.1:25590.
```

4. Start lobby-1 with both Core variables available:

```text
THEOSFERA_CONTROL_TRUSTSTORE_PASSWORD
THEOSFERA_CONTROL_BACKEND_SECRET
```

5. Keep the backend empty: do not connect a Minecraft player.

## Expected acceptance evidence

Proxy:

```text
Backend lobby-1 autenticado en control channel (generation N).
```

Core:

```text
Backend control client iniciado para 1 Proxy endpoint(s).
Control channel autenticado con proxy-1 (generation N).
```

The connection should remain open while both processes are alive.

Stopping lobby-1 should eventually produce the Proxy-side control-session-loss log. Restarting lobby-1 should authenticate a new generation without requiring any player carrier.

## Expected limitation before Increment D

Authentication independence is the target of this runtime. Backend health is not yet migrated to the control connection.

Therefore:

```text
0 players + authenticated control session -> control AUTHORIZED
0 players + legacy Plugin Messaging health -> may still show UNKNOWN
```

Do not classify `UNKNOWN` after successful control authentication as a failure of Increment C. Increment D will move PING/PONG and remove that carrier dependency from health.

## Failure categories

- Proxy listener does not start: inspect keystore path/password, TLS material, port availability, and required backend secrets.
- TLS handshake rejected: inspect truststore contents and certificate SAN versus configured Core host.
- Control authentication rejected: inspect exact backend name/type and ensure Core uses the matching HMAC secret from the Proxy secret file.
- Connection refused: verify Proxy listener bind host/port and launch order.
- Reconnect loop after accepted authentication: inspect post-auth protocol handling and socket lifetime; do not silently fall back to Plugin Messaging for backend control.
