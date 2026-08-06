# Backend Control Channel — Operational Runbook

Status: canonical operational procedure for provisioning, extending, validating and recovering the Theosfera backend control channel.

This document is the operational companion to `BACKEND_CONTROL_CHANNEL_DESIGN.md`.

Use this runbook when:

- adding a new backend instance;
- provisioning a fresh local development network;
- deploying the control channel to a VPS;
- rotating TLS material;
- rotating one backend HMAC secret;
- recovering after process-local environment variables were lost;
- validating zero-player backend health;
- troubleshooting control authentication, TLS or health.

Do not use chat history as the source of truth for these procedures. Update this runbook whenever the production configuration contract changes.

---

## 1. Security invariants

Never commit or log:

- backend HMAC secret values;
- keystore passwords;
- truststore passwords;
- private keys;
- `control-server.p12`;
- runtime secret files containing real values.

The following names may be committed because they are references, not secret values:

```text
THEOSFERA_CONTROL_KEYSTORE_PASSWORD
THEOSFERA_CONTROL_TRUSTSTORE_PASSWORD
THEOSFERA_CONTROL_BACKEND_SECRET
```

Each backend identity must have a distinct HMAC secret.

The Proxy private keystore must never be copied to a backend. Backends receive only trust material needed to verify the Proxy certificate.

A connected TCP socket is not authentication. TLS is not backend authentication. Backend health requires a fresh correlated PONG over the current authenticated control session.

---

## 2. Current transport boundary

As of Increment D:

```text
TheosferaCore
    |
    | persistent TLS 1.3 + HMAC authenticated control connection
    v
TheosferaProxy

PING/PONG health -> control channel
player-scoped protocol traffic -> Plugin Messaging
```

Backend control authentication is independent of connected Minecraft players.

The legacy player-carried backend identity handshake remains until the separate Increment E retirement work is completed. Do not silently reintroduce Plugin Messaging as a fallback for backend health.

Redis remains Proxy-to-Proxy coordination infrastructure. TheosferaCore does not use Redis for backend control health.

---

## 3. Runtime files

### Proxy data directory

Typical local path:

```text
C:\Theosfera\Network\dev\proxy-1\plugins\theosferaproxy\
```

Control files:

```text
control.properties
control-secrets.properties
control-server.p12
control-server.crt        # provisioning/export artifact; public certificate
```

Canonical `control.properties` development shape:

```properties
enabled=true
bind-host=127.0.0.1
bind-port=25590
authentication-timeout-seconds=5
keystore-path=control-server.p12
keystore-password-env=THEOSFERA_CONTROL_KEYSTORE_PASSWORD
secrets-file=control-secrets.properties
```

`control-secrets.properties` must contain exactly one valid secret for every backend authorized by the runtime `backends.properties` policy.

The provider is fail-closed:

- missing authorized backend secret -> rejected;
- secret for unauthorized backend -> rejected;
- duplicate backend entry -> rejected;
- malformed Base64URL -> rejected;
- decoded secret shorter than 32 bytes or longer than 128 bytes -> rejected.

### Core data directory

Typical local path for backend `<backend>`:

```text
C:\Theosfera\Network\dev\<backend>\plugins\TheosferaCore\
```

Files:

```text
config.yml
control-truststore.p12
control-server.crt        # optional retained public provisioning artifact
```

Critical YAML invariant: `control:` is nested under `network:`.

Correct shape:

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

Do not place `control:` at the YAML root. The loader reads `network.control`.

---

## 4. Current local development identities

This list is illustrative and must always be checked against the live `backends.properties` file before provisioning:

```text
auth-1      AUTH
lobby-1     LOBBY
lobby-2     LOBBY
skyblock-1  SKYBLOCK
```

Current local control endpoint:

```text
proxy-1 -> 127.0.0.1:25590
```

When the backend policy changes, update the authorized secret set accordingly. Never assume this four-backend list is permanently correct.

---

## 5. Adding a new backend

Example only:

```text
new backend: lobby-3
backend type: LOBBY
```

### Step 1 — Register the backend in normal network policy

Add the backend to Velocity/runtime configuration and to the authoritative `backends.properties` policy using the current policy format.

Current policy shape:

```text
backend-name=TYPE,capacity,preference
```

Example:

```text
lobby-3=LOBBY,100,90
```

Use the real capacity/preference intended for the backend; do not copy example numbers blindly.

### Step 2 — Generate one unique backend secret

PowerShell-compatible helper:

```powershell
function New-Base64UrlSecret([int]$Bytes = 32) {
    $buffer = New-Object byte[] $Bytes
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try {
        $rng.GetBytes($buffer)
    }
    finally {
        $rng.Dispose()
    }

    [Convert]::ToBase64String($buffer).TrimEnd('=').Replace('+', '-').Replace('/', '_')
}
```

Example process-local variable:

```powershell
$LOBBY3_SECRET = New-Base64UrlSecret
```

Do not print it.

### Step 3 — Add the secret to Proxy

Add exactly:

```text
lobby-3=<Base64URL secret>
```

to the runtime `control-secrets.properties` file.

Because the Proxy validates the secret file against the authorized backend set, the policy file and secret file must be updated consistently.

### Step 4 — Provision Core config

Configure the backend identity and control client under `network:`:

```yaml
network:
  enabled: true
  backend-name: "lobby-3"
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

### Step 5 — Install trust material

If the new backend trusts the same Proxy certificate as existing backends, copy the existing `control-truststore.p12` into its `plugins/TheosferaCore/` directory.

Comparing SHA-256 with a known-good backend is a useful deployment check.

Do not copy `control-server.p12` to Core.

### Step 6 — Launch with process-local secrets

The backend process needs:

```text
THEOSFERA_CONTROL_TRUSTSTORE_PASSWORD
THEOSFERA_CONTROL_BACKEND_SECRET
```

The second value must be the unique secret for that backend identity.

### Step 7 — Validate before player traffic

Keep the backend empty. After enough health cycles to exceed the freshness window, verify that the backend is authenticated and HEALTHY with zero connected players.

Then restart only that backend and verify a new control generation authenticates and health returns through the new current session.

---

## 6. Local Windows secret handling

Development secrets are intentionally process-local unless explicitly stored in a secure mechanism.

Recommended PowerShell password helper compatible with Windows PowerShell:

```powershell
function New-SecureRandomPassword {
    $bytes = New-Object byte[] 32
    $rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()

    try {
        $rng.GetBytes($bytes)
    }
    finally {
        $rng.Dispose()
    }

    [Convert]::ToBase64String($bytes)
}
```

Generate independent TLS passwords:

```powershell
$KEYSTORE_PASSWORD = New-SecureRandomPassword
$TRUSTSTORE_PASSWORD = New-SecureRandomPassword

$env:THEOSFERA_CONTROL_KEYSTORE_PASSWORD = $KEYSTORE_PASSWORD
$env:THEOSFERA_CONTROL_TRUSTSTORE_PASSWORD = $TRUSTSTORE_PASSWORD
```

Never print them merely to confirm presence. Check booleans instead.

If a PowerShell window containing process-local secret variables is closed, those variables are lost.

Backend HMAC values can be reloaded from the protected runtime `control-secrets.properties` if that file still exists. A forgotten PKCS#12 password cannot be recovered from this project; for local development, rotate/recreate the TLS material instead of weakening security or committing passwords.

---

## 7. Recover backend HMAC variables without printing values

Example for the current local four-backend topology:

```powershell
$secretsFile = "C:\Theosfera\Network\dev\proxy-1\plugins\theosferaproxy\control-secrets.properties"
$secretMap = @{}

Get-Content $secretsFile | ForEach-Object {
    $line = $_.Trim()

    if ($line -and -not $line.StartsWith("#") -and $line.Contains("=")) {
        $parts = $line -split "=", 2
        $secretMap[$parts[0].Trim()] = $parts[1].Trim()
    }
}

$AUTH_SECRET     = $secretMap["auth-1"]
$LOBBY_SECRET    = $secretMap["lobby-1"]
$LOBBY2_SECRET   = $secretMap["lobby-2"]
$SKYBLOCK_SECRET = $secretMap["skyblock-1"]

$secretMap.Clear()
```

This example is topology-specific. For a future backend, load its entry into its own process-local variable instead of reusing another backend's secret.

---

## 8. Locate JDK keytool on Windows

The project runtime target and the JDK used for provisioning are separate concerns.

Theosfera projects remain Java 21-targeted. A newer installed JDK may be used only for `keytool` provisioning.

Oracle `javapath` may be a shim rather than the real JDK directory. Resolve the actual `java.home`:

```powershell
$javaHomeLine = & java.exe -XshowSettings:properties -version 2>&1 |
    Select-String '^\s*java\.home\s*=' |
    Select-Object -First 1

$realJavaHome = (($javaHomeLine -split '=', 2)[1]).Trim()
$keytool = Join-Path $realJavaHome "bin\keytool.exe"

[PSCustomObject]@{
    JavaHome = $realJavaHome
    Keytool  = $keytool
    Exists   = Test-Path $keytool
}

& $keytool -version
```

Do not continue TLS provisioning unless `Exists` is `True`.

---

## 9. Rotate/recreate local TLS material

Use this when the development keystore/truststore password was lost or the certificate intentionally needs rotation.

This operation does not require changing backend HMAC secrets.

### Step 1 — Back up old material

Back up the existing Proxy keystore, exported public certificate and one known-good truststore outside Git.

### Step 2 — Generate Proxy private identity

Example for localhost:

```powershell
& $keytool -genkeypair `
    -alias theosfera-control `
    -keyalg RSA `
    -keysize 3072 `
    -sigalg SHA256withRSA `
    -validity 3650 `
    -dname "CN=Theosfera Proxy Control, OU=Development, O=Theosfera, C=EC" `
    -ext "SAN=IP:127.0.0.1,DNS:localhost" `
    -ext "EKU=serverAuth" `
    -keystore $proxyKeyStore `
    -storetype PKCS12 `
    -storepass:env THEOSFERA_CONTROL_KEYSTORE_PASSWORD `
    -keypass:env THEOSFERA_CONTROL_KEYSTORE_PASSWORD
```

The SAN must match the hostname/IP configured by Core. Do not blindly use the localhost SAN on a remote VPS.

### Step 3 — Export public certificate

```powershell
& $keytool -exportcert `
    -alias theosfera-control `
    -keystore $proxyKeyStore `
    -storetype PKCS12 `
    -storepass:env THEOSFERA_CONTROL_KEYSTORE_PASSWORD `
    -rfc `
    -file $certificate
```

### Step 4 — Build backend truststore

```powershell
& $keytool -importcert `
    -noprompt `
    -alias proxy-1-control `
    -file $certificate `
    -keystore $trustStore `
    -storetype PKCS12 `
    -storepass:env THEOSFERA_CONTROL_TRUSTSTORE_PASSWORD
```

### Step 5 — Verify

Verify both stores can be opened and confirm certificate SAN contains every host representation Core will actually use.

Example:

```powershell
& $keytool -list `
    -keystore $proxyKeyStore `
    -storepass:env THEOSFERA_CONTROL_KEYSTORE_PASSWORD

& $keytool -list `
    -keystore $trustStore `
    -storepass:env THEOSFERA_CONTROL_TRUSTSTORE_PASSWORD

& $keytool -printcert -v -file $certificate |
    Select-String -Pattern "SubjectAlternativeName", "127.0.0.1", "localhost"
```

### Step 6 — Redistribute truststore

Copy the new truststore to every Core backend that connects to that Proxy identity. All copies should hash identically when they represent the same trust set.

A TLS rotation is not complete until every required Core process has the new trust material and the corresponding truststore password at launch.

---

## 10. Local launch procedure

Prerequisites:

- Redis/coordination dependencies ready;
- Proxy JAR deployed;
- Core JAR deployed to every backend under test;
- `control.properties` enabled;
- exact authorized backend secret set present;
- correct nested `network.control` config on Core;
- truststore distributed;
- process-local secret variables available.

### Proxy

Before launching proxy-1:

```powershell
$env:THEOSFERA_CONTROL_KEYSTORE_PASSWORD = $KEYSTORE_PASSWORD
```

The child Proxy process must inherit that environment variable.

Expected listener evidence:

```text
Backend control TLS listener iniciado en /127.0.0.1:25590.
```

### Backends

Every Core backend receives the common truststore password plus its own HMAC secret:

```text
auth-1      -> $AUTH_SECRET
lobby-1     -> $LOBBY_SECRET
lobby-2     -> $LOBBY2_SECRET
skyblock-1  -> $SKYBLOCK_SECRET
```

Immediately before launching each child process:

```powershell
$env:THEOSFERA_CONTROL_TRUSTSTORE_PASSWORD = $TRUSTSTORE_PASSWORD
$env:THEOSFERA_CONTROL_BACKEND_SECRET = <that backend's process-local secret variable>
```

A child process inherits the environment as it exists when the child is created. Changing the parent shell's backend secret afterward does not alter a previously launched child process.

After all children have started, temporary environment variables in the parent shell may be removed. Removing them from the parent does not retroactively remove the inherited values from already running children.

---

## 11. Increment D runtime acceptance

Minimum zero-player health acceptance for the current development topology:

```text
players = 0

auth-1      -> authenticated current control session -> HEALTHY
lobby-1     -> authenticated current control session -> HEALTHY
lobby-2     -> authenticated current control session -> HEALTHY
skyblock-1  -> authenticated current control session -> HEALTHY
```

Current health timings:

```text
check interval   = 5 s
freshness window = 15 s
pending timeout  = 10 s
```

Wait long enough for repeated health cycles before classifying the runtime.

Acceptance also includes reconnect/generation fencing:

1. establish HEALTHY state;
2. restart one backend;
3. old control generation must stop being authoritative;
4. new connection authenticates as a new current generation;
5. fresh PONGs on the new session restore/maintain HEALTHY;
6. no player carrier is used to make health work.

Do not declare Increment D runtime PASS from build/test success alone.

---

## 12. Deploying to a real VPS

Do not copy localhost assumptions blindly into production.

### Single VPS

If Proxy and all backend processes run on the same host, binding the control listener to loopback may remain appropriate:

```text
127.0.0.1:25590
```

This keeps the control port off the external network surface.

### Multiple VPS / nodes

If Core and Proxy live on different machines:

- use an internal/private network address or stable internal DNS name;
- issue a certificate whose SAN matches the exact hostname/IP configured in Core;
- restrict the control port with host/network firewall rules to expected nodes;
- never expose the listener broadly unless architecture explicitly requires it;
- consider an internal CA as the Proxy fleet grows instead of manually pinning independent self-signed certificates everywhere.

### Persistent secret management

Production must not depend on an interactive shell remaining open.

For Linux/systemd deployments, a suitable design is one root-readable environment file per service with permissions such as `0600`, referenced by the service manager. Keep Proxy keystore credentials separate from backend credentials and keep every backend HMAC secret distinct.

Exact production service units and secret storage paths must be documented when the VPS deployment is implemented; do not invent them from the localhost layout.

Normal JAR upgrades should reuse existing valid TLS/HMAC material. Do not regenerate certificates or backend secrets merely because a new Theosfera version is deployed.

Rotate credentials only for an explicit operational/security reason or according to the production rotation policy.

---

## 13. Multi-proxy trust

A backend may connect to multiple Proxy control endpoints.

Example:

```yaml
network:
  control:
    proxies:
      proxy-1:
        host: "proxy-1.internal"
        port: 25590
      proxy-2:
        host: "proxy-2.internal"
        port: 25590
```

Each Proxy maintains an independent authenticated control session and generation.

The backend truststore must trust the certificate chain/identity for every configured Proxy endpoint.

One Proxy's healthy control session does not make another Proxy's local session healthy.

---

## 14. Common failures

### `keytool` is not recognized

Resolve the real JDK `java.home` and invoke `bin/keytool.exe` by absolute path. Do not assume Oracle `javapath` is the JDK root.

### Core never starts control client

Check that `control:` is nested under `network:` and `network.control.enabled` is `true`.

### TLS handshake fails

Check:

- Core truststore contents;
- truststore password supplied to the Core process;
- certificate SAN versus configured endpoint host;
- certificate validity;
- Proxy listener certificate/keystore.

### Proxy control listener does not start

Check:

- `control.properties` enabled;
- bind host/port;
- port availability;
- keystore path;
- keystore password environment variable;
- exact secret coverage for the authorized backend policy.

### Backend authentication rejected

Check exact:

- backend name;
- backend type;
- backend secret mapping;
- Proxy authorized backend policy;
- protocol compatibility.

Never solve an authentication failure by sharing one backend's secret with another backend.

### Authenticated but not HEALTHY

For Increment D, inspect control PING/PONG traffic, pending correlation, generation/current-session fencing and freshness. Do not fall back to Plugin Messaging health.

### Works only after a player joins

That is a failure for Increment D backend health. The zero-player control connection must be sufficient for repeated health PING/PONG.

---

## 15. Operational checklist for a new backend

```text
[ ] backend registered in Velocity/runtime server configuration
[ ] backend registered in authoritative backends.properties
[ ] backend-name exact
[ ] backend-type exact
[ ] unique Base64URL HMAC secret generated
[ ] Proxy control-secrets.properties updated with exact authorized set
[ ] Core JAR deployed
[ ] network.enabled = true
[ ] network.control.enabled = true
[ ] control nested under network
[ ] Proxy endpoint host/port correct
[ ] truststore installed
[ ] truststore password available to backend process
[ ] unique backend secret available to backend process
[ ] Proxy listener running
[ ] backend authenticates with zero players
[ ] repeated PING/PONG keeps backend HEALTHY
[ ] reconnect creates a new current generation
[ ] no Plugin Messaging health fallback
```

---

## 16. Maintenance rule

When any of these contracts change, update this runbook in the same milestone/PR:

- control config file keys;
- Core YAML structure;
- environment variable names;
- secret format/length rules;
- certificate/trust model;
- launch/service-management strategy;
- health timing or acceptance semantics;
- multi-proxy behavior;
- backend registration procedure.

The intended future re-entry phrase is simply:

> Open `docs/BACKEND_CONTROL_CHANNEL_RUNBOOK.md` and follow the new-backend procedure.

Architecture rationale remains in `docs/BACKEND_CONTROL_CHANNEL_DESIGN.md`; runtime checkpoints preserve milestone evidence, while this file remains the canonical operations procedure.
