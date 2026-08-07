# TheosferaProxy

Proxy Velocity y coordinador global de la network Theosfera.

## Estado actual

TheosferaProxy ya no es una fundación inicial. El runtime productivo incluye coordinación distribuida y superficies cross-server reales.

Capacidades principales fusionadas en `main`:

- TheosferaProtocol v2;
- Backend Control Channel persistente TLS 1.3 + HMAC-SHA256;
- identidad live de backends desde la sesión de control autenticada;
- health PING/PONG sobre Control Channel, sin player carrier;
- Redis Coordination Runtime;
- Proxy membership con TTL, renew y fencing;
- player-session ownership Redis;
- player presence Redis;
- occupancy global por backend;
- capacity reservations Redis atómicas;
- transferencias distribuidas con retry y exact handoff;
- Auth → Lobby;
- `/hub` y `/lobby`;
- `/hub switch` y `/lobby switch`;
- backend kick failover fail-closed;
- hardening de raw Velocity `/server`;
- Distributed Backend Bootstrap Foundation A.1–A.8, fusionado mediante PR `#74`;
- observabilidad administrativa mediante `/theosferaproxy status`.

Rama técnica activa:

```text
feature/backend-orchestration-provider
```

Milestone activo: `Backend Orchestration Provider`.

Estado incremental:

```text
B.1 provider contracts                    VALIDATED
B.2 fenced provider / actuator strategy   PENDING LOCAL GATE
B.3 startup operation lifecycle           NEXT
```

Todavía no se arrancan procesos reales ni se altera el flujo productivo de cold startup.

## Arquitectura

Principios centrales:

- fail-closed;
- ownership y fencing explícitos;
- cleanup exact-match;
- Redis como coordinación temporal, no base durable de perfiles/progreso;
- sin fallback local silencioso cuando Redis es autoridad;
- sin I/O bloqueante de red/Redis en threads de Velocity;
- identidad, health, capacity, bootstrap ownership y process state son conceptos separados;
- Plugin Messaging queda reservado para tráfico player-scoped;
- backend identity y health pertenecen al Control Channel autenticado;
- no introducir lógica Paper/Bukkit o específica de una modalidad en el Proxy.

Invariante útil:

```text
TCP connected
    != authenticated control identity
    != HEALTHY
    != bootstrap ownership
    != process-start accepted
    != capacity reserved
    != player ready
```

Para orchestration, además:

```text
fencing comparison
+ process-start side-effect acceptance
= atomic actuator/orchestrator decision
```

Un pre-check remoto separado seguido de un start unfenced no es una implementación válida.

## Tecnología

- Java 21
- Gradle Kotlin DSL
- Gradle Wrapper
- Velocity API 3.5.0-SNAPSHOT
- Lettuce Redis
- JUnit 5 / Mockito
- Testcontainers para integración Redis
- GitHub Actions
- Shadow JAR

## Construcción

Windows PowerShell:

```powershell
.\gradlew.bat test --no-daemon
.\gradlew.bat clean build --no-daemon
git diff --check
```

Linux/macOS:

```bash
./gradlew test --no-daemon
./gradlew clean build --no-daemon
git diff --check
```

Artefacto:

```text
build/libs/TheosferaProxy-0.1.0-SNAPSHOT.jar
```

## Documentación

Antes de modificar el proyecto:

1. `AGENTS.md`
2. `CONTRIBUTING.md`
3. `PROJECT_STATE.md`
4. `docs/README.md`
5. checkpoint/diseño del milestone que se va a tocar

`PROJECT_STATE.md` describe el estado consolidado vigente. Durante un milestone activo, `docs/README.md`, el diseño específico de la rama y el código real deben revisarse también antes de asumir el punto incremental exacto.

Diseños activos/futuros relevantes:

- `docs/BACKEND_ORCHESTRATION_PROVIDER_DESIGN.md` — milestone técnico activo;
- `docs/ADMINISTRATIVE_PLAYER_TRANSFER_DESIGN.md` — feature futura aprobada para recordar: raw `/send` hardening + `/theosfera send <player> <BackendType>` con routing automático, autenticación obligatoria y TAB permission-aware/stealth.

## Responsabilidades previstas a futuro

Theosfera contempla sistemas globales como Maintenance/Drain, Administrative Player Transfer, amigos, parties, escuadrones, matchmaking y otras operaciones cross-server, pero su aparición en la visión del proyecto **no significa que su implementación interna esté terminada**.

Cada sistema importante debe planificarse primero: owner, source of truth, persistencia, coordinación, fallos, seguridad, dependencias y runtime acceptance.

## Seguridad

No versionar:

- secretos HMAC;
- claves privadas;
- passwords de keystore/truststore;
- credenciales Redis productivas;
- tokens de providers/orchestrators;
- datos sensibles de jugadores.
