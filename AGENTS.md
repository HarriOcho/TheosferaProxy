# AGENTS.md

## Purpose

This repository is the generic professional foundation for Minecraft
plugins developed for Theosfera.

Keep this template reusable. Do not introduce systems, commands,
integrations, or business logic that belong to a specific plugin.

## Technology baseline

-   Java 21
-   Gradle Kotlin DSL
-   Gradle Wrapper
-   Paper API 1.21.11
-   UTF-8 source and resource encoding
-   GitHub Actions for continuous integration

Do not lower the Java or server API baseline without an explicit project
decision.

## Working rules

Before modifying a repository created from this template:

1.  Read this file completely.
2.  Read `CONTRIBUTING.md`.
3.  Inspect the current project structure and existing conventions.
4.  Check `git status` and the current branch.
5.  Keep the requested task narrowly scoped.

Do not modify unrelated files merely to clean up, rename, or modernize
them unless the task explicitly requires it.

## Architecture

Prefer clear separation of responsibilities.

-   Keep the main plugin class focused on lifecycle and composition.
-   Prefer constructor injection for required dependencies.
-   Avoid global mutable state.
-   Avoid unnecessary static access.
-   Do not create oversized manager or utility classes that accumulate
    unrelated responsibilities.
-   Extract reusable logic only when a real shared responsibility
    exists.
-   Prefer explicit names over abbreviations.
-   Keep packages aligned with architectural responsibilities.

Do not add abstractions solely for hypothetical future use.

## Bukkit and Paper lifecycle

Respect the server lifecycle.

-   Register listeners, commands, and services deliberately.
-   Do not register the same listener or task more than once.
-   Cancel owned scheduled tasks when required.
-   Close owned resources during shutdown.
-   Avoid blocking I/O or expensive computation on the main server
    thread.
-   Do not introduce asynchronous Bukkit API access unless the API
    operation is documented as thread-safe.
-   Return to the main thread before interacting with non-thread-safe
    server state.

## Commands and permissions

Administrative functionality must be protected.

-   Validate permissions during command execution.
-   Restrict tab completion when command discovery would expose
    administrative functionality.
-   Do not reveal internal plugin details to normal players.
-   Validate command arguments before mutating state.
-   Do not report success when an operation or persistence step failed.

Project-specific commands and permissions must be documented in the
repository created from this template.

## Messages and localization

Player-visible text should use the project's centralized message system
when one exists.

-   Avoid duplicated hardcoded messages.
-   Keep equivalent language files synchronized.
-   Do not expose stack traces, implementation details, or sensitive
    configuration values to normal players.
-   Console diagnostics may be more technical but must remain
    actionable.

If a generated project does not require localization, document that
decision in its project-specific `AGENTS.md`.

## Configuration and persistence

Treat configuration and stored data as compatibility-sensitive.

-   Validate external input before use.
-   Keep YAML files readable when they are intended for administrators.
-   Preserve existing user configuration when adding new default paths
    whenever reasonably possible.
-   Handle malformed files and invalid values deliberately.
-   Document migrations and incompatible changes.
-   Never silently discard production data.
-   Do not claim a save succeeded unless persistence completed
    successfully.

## Optional dependencies

Optional integrations must fail safely.

-   Detect dependency availability before use.
-   Disable only the affected feature when reasonable.
-   Avoid hard crashes for missing optional dependencies.
-   Provide useful console diagnostics.
-   Keep player-facing failure messages generic unless more detail is
    appropriate.

## Performance

Optimize for correctness first, then measured server impact.

-   Avoid repeated full scans when one collected result can be reused.
-   Avoid unnecessary allocations in hot event paths.
-   Cache only when invalidation rules are clear.
-   Do not add asynchronous execution merely because a task can be
    asynchronous.
-   Do not block the main thread with file, network, or database
    operations that may take meaningful time.

## Security and privacy

Never commit or log:

-   passwords;
-   access tokens;
-   private keys;
-   environment secrets;
-   production databases;
-   unnecessary private addresses;
-   player-sensitive data not required for diagnostics.

If a secret is exposed, revoke or rotate it immediately. Removing it in
a later commit is not sufficient.

## Build and verification

Before considering a change complete:

1.  Run `git diff --check`.
2.  Run the Gradle build with the wrapper.
3.  Review the complete diff.
4.  Verify project-specific acceptance criteria.
5.  Perform server testing when runtime behavior changed.

Windows:

``` powershell
.\gradlew.bat build
```

Linux or macOS:

``` bash
./gradlew build
```

A successful compilation does not replace runtime testing when commands,
listeners, menus, persistence, scheduling, or integrations changed.

## Git workflow

Do not implement important changes directly on `main`.

Use focused branches such as:

-   `feature/<name>`
-   `fix/<name>`
-   `refactor/<name>`
-   `docs/<name>`
-   `chore/<name>`
-   `test/<name>`

Use descriptive commits following the repository conventions in
`CONTRIBUTING.md`.

Prefer small, reviewable Pull Requests with one clear purpose.

Before merging:

-   review the full diff;
-   confirm CI passes;
-   confirm acceptance criteria;
-   document known risks or pending work;
-   use **Squash and merge** unless the project has a documented reason
    to preserve individual commits.

## Template customization

When creating a plugin from this template, replace generic template
identifiers and expand this file with project-specific context.

At minimum, document:

-   plugin purpose;
-   root package;
-   supported server version;
-   major modules or services;
-   commands;
-   permissions;
-   configuration files;
-   persistence model;
-   optional dependencies;
-   localization policy;
-   important compatibility constraints;
-   project-specific testing expectations.

Remove template-only instructions that no longer apply after
customization.

## Scope protection

This template must remain generic.

Do not add TheosferaCore keybind logic, Skyblock systems, menu
implementations, client bridges, or other plugin-specific code here.

If a convention is useful only to one plugin, document and implement it
in that plugin instead of this template.
