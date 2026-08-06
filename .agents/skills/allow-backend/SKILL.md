---
name: allow-backend
description: Add or authorize a Theosfera backend for the secure backend control channel. Use when the user asks to add, allow, provision, authorize, register, or connect a new backend such as auth-2, lobby-3, skyblock-2, or another backend instance. Always follow the canonical backend control runbook and current runtime policy; never guess security material or silently weaken fail-closed behavior.
---

# Allow Backend

Provision one backend identity into Theosfera's secure backend control channel without duplicating or bypassing the canonical runtime procedure.

## Invocation

Native Codex invocation:

```text
$allow-backend <backend-name>
```

Project conversational shorthand may also be written as:

```text
/allow-backend <backend-name>
```

Treat the first positional value as the requested logical backend name.

## Canonical sources

Before proposing or performing any change, read the current versions of:

1. `docs/BACKEND_CONTROL_CHANNEL_RUNBOOK.md`.
2. `docs/BACKEND_CONTROL_CHANNEL_DESIGN.md` when an architectural decision is involved.
3. `backends.properties` from the active Proxy runtime or its authoritative repository/config source.
4. The target backend's `plugins/TheosferaCore/config.yml` when runtime access is available.
5. Current `TheosferaCore` network/control configuration defaults when the runtime file is missing or ambiguous.

The runbook and current production code/configuration outrank this skill if they differ. Do not preserve an obsolete step merely because it appears here.

## Required safety invariants

- Never print, log, commit, paste into chat, or place in command arguments any HMAC secret, keystore password, truststore password, private key, or raw authentication proof.
- Never commit `control-secrets.properties`, PKCS#12 stores, private keys, or runtime secret values.
- Each backend identity receives its own distinct HMAC secret.
- The Proxy private keystore is never copied to a backend.
- Reuse the existing trusted Proxy certificate/truststore when adding a backend. Do not rotate TLS merely because a backend is added.
- Do not create a machine-wide shared `THEOSFERA_CONTROL_BACKEND_SECRET` for multiple backends.
- Preserve fail-closed behavior. Do not add Plugin Messaging or local-state fallback for backend-level identity/health.
- Do not edit or commit directly on `main`. Respect the repository's active feature/checkpoint workflow.
- Do not infer a backend type, capacity, preference, endpoint, or runtime path when the current policy/configuration cannot establish it safely.

## Backend-name validation

The logical backend name must satisfy the same control-secret naming rule as the Proxy implementation:

```text
^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$
```

Reject invalid names before generating or provisioning any secret.

## Workflow

### 1. Inspect current state

Determine whether the requested backend already exists in the authoritative backend policy.

If it already exists:

- preserve its configured backend type, capacity, and preference;
- verify whether its control secret, Core control configuration, truststore, and runtime environment are already provisioned;
- perform only the missing steps.

If it does not exist:

- do not invent policy values;
- resolve the required backend type, capacity, and preference from explicit project requirements or ask only for fields that cannot be derived;
- add the backend to the normal backend policy before treating its control secret as authorized.

Remember that the Proxy secret provider validates the control-secret file against the exact authorized backend-name set. Missing expected secrets and secrets for unauthorized backends are fail-closed errors.

### 2. Update versioned configuration when required

When adding a genuinely new logical backend to repository-controlled policy/configuration:

- use a feature/ops branch, never `main`;
- make the smallest change needed;
- preserve the current `name=TYPE,capacity,preference` policy contract if that remains the active format;
- run the repository's relevant tests and `git diff --check` before considering the code/config gate complete.

Do not commit runtime secrets.

### 3. Provision the backend HMAC secret

Generate a cryptographically random secret with at least 32 decoded bytes using the procedure in `docs/BACKEND_CONTROL_CHANNEL_RUNBOOK.md`.

When a runtime secret file already exists:

- preserve all valid existing backend secrets;
- add exactly one entry for the new authorized backend;
- do not regenerate unrelated backend secrets;
- write the file in the encoding required by the runbook;
- never display the resulting secret value.

If the requested backend already has a valid entry, reuse it rather than rotating it unless the user explicitly requests secret rotation.

### 4. Provision Core trust and configuration

For the target backend runtime:

- deploy the approved TheosferaCore artifact for the current milestone;
- copy only the trusted public certificate/truststore material required by the current runbook;
- never copy the Proxy private keystore;
- configure the backend's exact `network.backend-name` and `network.backend-type`;
- ensure `control:` is nested under `network:` according to the current Core configuration schema;
- configure the intended Proxy endpoint(s), truststore path, environment-variable names, timeouts, and reconnect policy from the runbook/current defaults.

If the deployment has multiple Proxy endpoints, configure each intended endpoint explicitly and validate trust/SAN requirements for the actual hostnames or private IPs.

### 5. Prepare process-local environment

Before backend startup, make the current truststore password and this backend's own HMAC secret available through the environment variable names declared in Core configuration.

Do not reveal their values. Verify only presence when diagnostics are needed.

For local Windows development, keep process inheritance semantics in mind: each backend process must inherit its own `THEOSFERA_CONTROL_BACKEND_SECRET` at launch time.

For production/VPS deployment, use the project's approved persistent secret/service mechanism rather than depending on an interactive shell remaining open.

### 6. Runtime acceptance

Validate the new backend with zero Minecraft players whenever possible.

Minimum acceptance evidence:

```text
Proxy accepts the expected backend identity over authenticated control
Core authenticates to the intended Proxy endpoint(s)
0 players + repeated valid control PONG -> HEALTHY
backend identity/type match policy exactly
no unauthorized/fallback health path is used
```

If health does not become fresh, classify the failure by layer rather than guessing:

```text
TLS trust / SAN
Proxy listener
HMAC secret
backend name/type authorization
post-auth control session
PING/PONG correlation/freshness
runtime configuration nesting/path
```

### 7. Report outcome

Return a compact result containing:

- requested backend;
- policy status;
- control-secret status without secret value;
- Core trust/config status;
- artifact/deployment status when relevant;
- runtime authentication/health status;
- exact remaining blocker or next action.

Use `PASS`, `PARTIAL`, or `FAIL` explicitly. Never call provisioning complete before runtime evidence exists when runtime validation is part of the requested operation.

## Important boundary

This skill provisions/authorizes an existing or newly defined backend instance for Theosfera networking. It does not design a new gameplay modality, choose capacity/preference values without project input, create server worlds, bootstrap infrastructure, or rotate the entire network's TLS/HMAC material unless the user explicitly requests that separate operation.
