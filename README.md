# TheosferaPluginTemplate

Professional foundation template for Minecraft plugins developed for
Theosfera.

## Technology

-   Java 21
-   Gradle Kotlin DSL
-   Paper API 1.21.11
-   GitHub Actions
-   Gradle Configuration Cache compatible

## Creating a new plugin

Create a new repository from this template using GitHub's **Use this
template** option.

After creating the repository, replace the template-specific values:

-   Project name in `settings.gradle.kts`
-   Maven group and version in `build.gradle.kts`
-   JAR name in `build.gradle.kts`
-   Java package under `src/main/java`
-   Main class name and package
-   Plugin metadata in `src/main/resources/plugin.yml`
-   Project-specific architecture and rules in `AGENTS.md`

## Building

### Windows PowerShell

``` powershell
.\gradlew.bat build
```

### Linux or macOS

``` bash
./gradlew build
```

The compiled JAR is generated inside:

``` text
build/libs/
```

## Development workflow

1.  Update `main`.
2.  Create a dedicated branch.
3.  Implement one focused change.
4.  Run the build.
5.  Commit using a descriptive message.
6.  Push the branch.
7.  Open a Pull Request.
8.  Wait for GitHub Actions.
9.  Review the complete diff.
10. Merge using **Squash and merge**.

See `CONTRIBUTING.md` for the complete workflow.

## Template scope

This repository is a generic foundation.

Do not add plugin-specific systems, commands, menus, integrations, or
business logic directly to this template.

Reusable project-wide conventions may be added when they are appropriate
for all Theosfera plugins.
