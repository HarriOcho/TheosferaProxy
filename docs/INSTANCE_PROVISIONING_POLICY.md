# Theosfera Instance Provisioning Policy

Status: operational policy baseline for naming, backend capacity/preference derivation, and Proxy identity uniqueness.

This document records conventions that provisioning skills/runbooks may derive automatically. It does not replace runtime validation, security material provisioning, or architecture-specific configuration.

## 1. Instance naming

Backend and Proxy instances use a logical name with an ordinal suffix:

```text
<role-or-modality>-<ordinal>
```

Examples:

```text
proxy-1
proxy-2
lobby-1
lobby-2
skyblock-1
skyblock-2
```

The final numeric ordinal is part of the logical identity and is used to derive backend preference when the policy below applies.

## 2. Backend preference by ordinal

For backend instances, preference is derived from the ordinal:

```text
*-1 -> 100
*-2 -> 90
*-3 -> 80
*-4 -> 70
*-5 -> 60
...
```

Canonical rule:

```text
preference = max(0, 110 - (ordinal * 10))
```

The ordinal must be a positive integer. Provisioning must reject names whose suffix cannot be interpreted unambiguously when automatic preference derivation is required.

This policy applies consistently across backend modalities unless a newer explicit policy overrides it.

## 3. Capacity classes

Capacity is a property of the backend workload class, not of the ordinal.

### Persistent gameplay class

Persistent modalities use the same capacity for every instance:

```text
PERSISTENT -> 200
```

Examples include modalities such as:

```text
SKYBLOCK
SURVIVAL
```

Additional persistent modalities may be mapped to this class explicitly when they are introduced.

The term `PERSISTENT` is an operational capacity class. It does not automatically become a `BackendType` protocol enum.

### Existing non-persistent classes

Current baseline values that remain valid unless superseded by a later policy:

```text
AUTH  -> 1
LOBBY -> 100
```

Capacity for future backend types such as PRE_GAME must be explicitly defined before automatic provisioning treats them as known.

## 4. Examples

```text
skyblock-1
TYPE        = SKYBLOCK
class       = PERSISTENT
capacity    = 200
preference  = 100

skyblock-2
TYPE        = SKYBLOCK
class       = PERSISTENT
capacity    = 200
preference  = 90

lobby-3
TYPE        = LOBBY
capacity    = 100
preference  = 80
```

When the requested backend type has a known capacity class and the instance name has a valid ordinal, provisioning should derive capacity and preference automatically rather than asking the operator to remember them.

## 5. Proxy logical identity uniqueness

`proxyName` is a stable logical identity used by distributed coordination. `incarnationId` identifies one concrete process execution.

Therefore, within the same Theosfera network / coordination domain:

```text
VPS-A -> proxy-1
VPS-B -> proxy-2
```

is valid, while two simultaneously active processes both declaring:

```text
proxy-name=proxy-1
```

is prohibited, even when they run on different VPS hosts.

Physical host identity and Proxy logical identity are separate concepts. Multiple uniquely named Proxy processes may run on one VPS, and different VPS hosts may run different Proxy identities.

## 6. Proxy migration exception

A logical Proxy identity may be moved to another host only when the old process is no longer active/authoritative.

Example:

```text
VPS-A: proxy-1 stopped / fenced
VPS-B: proxy-1 starts with a new incarnationId
```

This is a migration of the same logical Proxy, not two concurrent `proxy-1` instances.

Provisioning/operations tooling must distinguish `allow-proxy` from a future explicit Proxy migration workflow and must fail closed when the requested Proxy name is already active elsewhere.

## 7. Provisioning interface intent

The intended human-facing operations are:

```text
/allow-backend <name> <TYPE>
/allow-proxy <name>
```

For `/allow-backend`, tooling should derive known values from this policy and request only information that cannot be established safely from current authoritative configuration.

Example:

```text
/allow-backend skyblock-2 SKYBLOCK

=> class=PERSISTENT
=> capacity=200
=> ordinal=2
=> preference=90
```

The provisioning workflow must still perform the full security/config/runtime gates required by `BACKEND_CONTROL_CHANNEL_RUNBOOK.md`; derivation of capacity/preference does not imply that the backend is ready.

## 8. Production automation direction

The long-term production model is:

```text
Skill/workflow
    -> canonical policy + runbook
    -> deterministic provisioning scripts/services
    -> runtime validation
```

Secrets must remain outside Git and outside skill text. Normal process startup must reuse already provisioned material; TLS/HMAC provisioning is not repeated on every backend or Proxy restart.
