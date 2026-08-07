---
name: allow-backend
description: Add or authorize a Theosfera backend for the secure backend control channel. Use when the user asks to add, allow, provision, authorize, register, or connect a new backend such as auth-2, lobby-3, skyblock-2, or another backend instance. Derive known capacity/preference values from the canonical provisioning policy, then follow the backend control runbook and current runtime policy. Never guess security material or silently weaken fail-closed behavior.
---

# Allow Backend

Provision one backend identity into Theosfera's secure backend control channel without duplicating or bypassing the canonical runtime procedure.

## Invocation

Native Codex invocation:

```text
$allow-backend <backend-name> <BACKEND_TYPE>
```

Project conversational shorthand:

```text
/allow-backend <backend-name> <BACKEND_TYPE>
```

Example:

```text
/allow-backend skyblock-2 SKYBLOCK
```

The backend type may be omitted only when the current authoritative configuration already proves it unambiguously.

## Canonical sources

Before proposing or performing any change, read the current versions of:

1. `docs/INSTANCE_PROVISIONING_POLICY.md`.
2. `docs/BACKEND_CONTROL_CHANNEL_RUNBOOK.md`.
3. `docs/BACKEND_CONTROL_CHANNEL_DESIGN.md` when an architectural decision is involved.
4. `backends.properties` from the active Proxy runtime or its authoritative repository/config source.
5. The target backend's `plugins/TheosferaCore/config.yml` when runtime access is available.
6. Current `TheosferaCore` network/control configuration defaults when the runtime file is missing or ambiguous.

Current production code/configuration and a newer explicit project policy outrank this skill if they differ. Do not preserve an obsolete step merely because it appears here.

## Required safety invariants

- Never print, log, commit, paste into chat, or place in command arguments any HMAC secret, keystore password, truststore password, private key, or raw authentication proof.
- Never commit `control-secrets.properties`, PKCS#12 stores, private keys, or runtime secret values.
- Each backend identity receives its own distinct HMAC secret.
- The Proxy private keystore is never copied to a backend.
- Reuse the existing trusted Proxy certificate/truststore when adding a backend. Do not rotate TLS merely because a backend is added.
- Do not create a machine-wide shared `THEOSFERA_CONTROL_BACKEND_SECRET` for multiple backends.
- Preserve fail-closed behavior. Do not add Plugin Messaging or local-state fallback for backend-level identity/health.
- Do not edit or commit directly on `main`. Respect the repository's active feature/checkpoint workflow.
- Do not infer endpoint, runtime path, or an unknown capacity class when current policy/configuration cannot establish it safely.

## Backend-name and ordinal validation

The logical backend name must satisfy the control-secret naming rule:

```text
^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$
```

For automatic preference derivation, the name must also have an unambiguous positive integer ordinal suffix:

```text
<role-or-modality>-<ordinal>
```

Examples:

```text
lobby-1
lobby-2
skyblock-3
```

Reject invalid or ambiguous names before provisioning security material.

## Policy derivation

Use `docs/INSTANCE_PROVISIONING_POLICY.md` to derive values that the operator should not have to memorize.

Current baseline:

```text
preference = max(0, 110 - (ordinal * 10))
```

Examples:

```text
*-1 -> 100
*-2 -> 90
*-3 -> 80
```

Known capacity classes currently include:

```text
AUTH       -> 1
LOBBY      -> 100
PERSISTENT -> 200
```

`SKYBLOCK` and `SURVIVAL` are examples of modalities mapped to the operational `PERSISTENT` capacity class. `PERSISTENT` is not automatically a protocol `BackendType`.

When backend type + naming policy determine capacity and preference unambiguously, derive them automatically. Ask only for values that cannot be established from the current authoritative policy.

Example:

```text
/allow-backend skyblock-2 SKYBLOCK

=> class=PERSISTENT
=> capacity=200
=> ordinal=2
=> preference=90
```

## Workflow

### 1. Inspect current state

Determine whether the requested backend already exists in the authoritative backend policy.

If it already exists:

- preserve its configured backend type, capacity, and preference unless an explicit migration is requested;
- verify whether its control secret, Core control configuration, truststore, artifact, service/runtime environment, and endpoint configuration are already provisioned;
- perform only missing steps.

If it does not exist:

- validate the requested backend type;
- derive capacity/preference from `INSTANCE_PROVISIONING_POLICY.md` when defined;
- ask only for any unresolved policy fields;
- add the backend to normal network policy before treating its control secret as authorized.

The Proxy secret provider validates the secret file against the exact authorized backend-name set. Missing expected secrets and secrets for unauthorized backends are fail-closed errors.

### 2. Update versioned configuration when required

When adding a genuinely new logical backend to repository-controlled policy/configuration:

- use a focused feature/ops branch, never `main`;
- make the smallest change needed;
- preserve the current `name=TYPE,capacity,preference` contract while it remains active;
- run relevant tests and `git diff --check` before considering the code/config gate complete.

Never commit runtime secrets.

### 3. Provision the backend HMAC secret

Generate one cryptographically random secret using the canonical runbook.

When a runtime secret file already exists:

- preserve all valid existing backend secrets;
- add exactly one entry for the new authorized backend;
- do not regenerate unrelated backend secrets;
- never display the resulting value.

If the requested backend already has a valid entry, reuse it unless secret rotation is explicitly requested.

### 4. Provision Core trust and configuration

For the target backend runtime:

- deploy the approved TheosferaCore artifact;
- copy only trusted public certificate/truststore material required by the runbook;
- never copy the Proxy private keystore;
- configure exact `network.backend-name` and `network.backend-type`;
- ensure `control:` is nested under `network:`;
- configure every intended Proxy endpoint explicitly;
- configure truststore path, environment-variable names, timeouts, and reconnect policy from current authoritative defaults.

For multiple Proxy endpoints, validate trust/SAN requirements against actual private IPs or hostnames.

### 5. Prepare startup/service secrets

For local Windows development, each backend process must inherit its own backend secret plus required truststore password at process creation.

For production/VPS deployment, use the approved persistent service/secret mechanism. Normal backend restarts must reuse already provisioned TLS/HMAC material; provisioning is not repeated for every start.

### 6. Runtime acceptance

Validate the backend with zero Minecraft players whenever possible.

Minimum acceptance evidence:

```text
backend exists in normal routing/policy configuration
Proxy accepts expected backend identity over authenticated control
Core authenticates to intended Proxy endpoint(s)
0 players + repeated valid control PONG -> HEALTHY
backend identity/type match policy exactly
no unauthorized/fallback health path is used
```

Classify failures by layer rather than guessing:

```text
policy/routing registration
TLS trust / SAN
Proxy listener
HMAC secret
backend name/type authorization
post-auth control session
PING/PONG correlation/freshness
runtime configuration nesting/path
service/startup environment
```

### 7. Report outcome

Return a compact result including:

- requested backend and type;
- derived capacity/preference and source policy;
- policy/routing status;
- control-secret status without value;
- Core trust/config status;
- artifact/service/deployment status when relevant;
- runtime control authentication and health status;
- exact blocker or next action.

Use `PASS`, `PARTIAL`, or `FAIL` explicitly. Never call provisioning complete before required runtime evidence exists.

## Important boundary

This skill provisions/authorizes a backend instance for Theosfera networking. It does not design a new gameplay modality, invent capacity classes, create worlds, bootstrap a fresh VPS, provision a new Proxy identity, migrate a Proxy between hosts, or rotate network-wide TLS/HMAC material unless the user explicitly requests those separate operations.
