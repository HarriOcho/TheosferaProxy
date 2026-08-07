---
name: pending-works-theosfera
description: Reconstruct the current outstanding work for the Theosfera plugin/network project from authoritative project state, checkpoints, branches, and runtime evidence. Use when the user asks what is pending, what comes next, what remains unfinished, what milestone is active, or whether work was already completed. Never rely on stale conversational memory when current repository evidence is available.
---

# Pending Works — Theosfera

Determine what actually remains to be done across Theosfera without resurrecting completed milestones or treating an unvalidated change as finished.

## Invocation

Native Codex invocation:

```text
$pending-works-theosfera
```

Project conversational shorthand may also be written as:

```text
/pending-works-theosfera
```

No arguments are required.

## Source-of-truth order

Reconstruct state from the freshest authoritative evidence available, in this priority order:

1. Current repository working state and branch/commit information for the active work.
2. `PROJECT_STATE.md` in Theosfera repositories.
3. The newest relevant checkpoint/runbook documents under `docs/`.
4. Open and recently merged pull requests when GitHub access is available.
5. Build/test evidence tied to a concrete commit.
6. Runtime evidence tied to a concrete deployed artifact/hash.
7. Current conversation context only for work performed after the latest committed checkpoint; label this as provisional/uncheckpointed.

Never let an older memory, old PR description, or superseded checkpoint override newer repository/runtime evidence.

## Repositories to inspect

At minimum consider these repositories when they are available:

```text
TheosferaProxy
TheosferaCore
TheosferaProtocol
TheosferaAuth
```

Also inspect another `Theosfera*` repository when a current checkpoint explicitly makes it part of the active milestone.

Do not assume every repository has a `PROJECT_STATE.md`. Missing state files are not evidence that a project has no pending work.

## Workflow

### 1. Establish the current baseline

For each relevant repository:

- identify current branch and HEAD;
- determine whether the tree is clean when local access exists;
- identify divergence from `main` when relevant;
- read `PROJECT_STATE.md` if present;
- inspect the latest checkpoint documents relevant to the current milestone;
- inspect open PRs and recent merges when GitHub access exists.

Prefer content timestamps and explicit milestone text over file modification metadata alone.

### 2. Separate implementation state from validation state

Never collapse these into one status.

Use distinctions such as:

```text
DESIGN
CODE
TEST/BUILD
DEPLOYMENT
RUNTIME
CHECKPOINT/DOCS
MERGE
```

Examples:

- code merged but runtime not validated -> implementation complete, runtime pending;
- tests pass but JAR not deployed -> build gate PASS, deployment pending;
- runtime passes but checkpoint/PR not closed -> runtime PASS, milestone not administratively closed;
- a design document exists with no code -> design complete only.

### 3. Resolve contradictions

When sources disagree:

- prefer the newer authoritative source;
- verify with code/branch/PR/runtime evidence where possible;
- explain the contradiction briefly;
- do not silently choose the more convenient version.

If evidence is insufficient, classify the item as `UNCERTAIN` and state exactly what must be checked.

### 4. Build the pending queue

Group remaining work by urgency and dependency, not by arbitrary age.

Use these categories:

```text
NOW
NEXT
LATER
BLOCKED / NEEDS EVIDENCE
```

`NOW` is the exact continuation point of the active milestone.

`NEXT` contains work that should begin immediately after `NOW` closes and whose dependency is already known.

`LATER` contains approved roadmap items that are real but should not interrupt the active chain.

`BLOCKED / NEEDS EVIDENCE` contains work whose status cannot be proven from the current sources.

Do not include already closed work merely as historical context unless it is needed to explain a dependency.

### 5. Preserve Theosfera architecture boundaries

When ranking pending work, preserve current architectural rules documented by the repositories. In particular:

- TheosferaProxy owns global network coordination/routing concerns.
- TheosferaCore must not gain Redis coordination responsibilities merely for convenience.
- player-scoped Plugin Messaging and backend-level control-plane responsibilities must not be conflated.
- fail-closed, fencing, exact lease/release, and distributed-authority guarantees must not be weakened to make a pending task easier.
- build success and runtime proof are different gates.

If a proposed pending item conflicts with a newer architectural decision, flag it as superseded rather than recommending it.

### 6. Report format

Start with one-line classification:

```text
CURRENT MILESTONE: <name> — <status>
```

Then report compactly:

```text
NOW
- exact next action(s)

NEXT
- immediate successor milestone(s)

LATER
- approved deferred work

BLOCKED / NEEDS EVIDENCE
- only if applicable
```

For each `NOW` item, include the repository/branch or checkpoint that establishes it when available.

If the user asks only "qué falta", keep the response concise. If they ask for a roadmap/audit, include evidence and reasoning in more detail.

## Closure rule

Do not declare a milestone `CLOSED` unless the acceptance criteria required by its current checkpoint are satisfied. If the project convention requires runtime proof, checkpoint documentation, PR merge, or a clean/synchronized branch, explicitly identify whichever closure gate is still missing.

## Security rule

While inspecting pending work, never surface stored secrets, environment-variable values, private keys, raw HMAC material, or credential files. It is acceptable to report that required secret/configuration material is present or missing without showing the value.
