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
- observabilidad administrativa mediante `/theosferaproxy status`.

Rama activa:

```text
feature/distributed-backend-bootstrap
```

En esa rama está implementado el foundation A.1–A.8 de ownership distribuido para bootstrap de backends. Ese foundation todavía no arranca procesos reales; el siguiente milestone después de su merge es diseñar el `Backend Orchestration Provider`.

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
    != capacity reserved
    != player ready
```

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
5. checkpoint del milestone que se va a tocar

`PROJECT_STATE.md` describe el estado vigente. Los checkpoints bajo `docs/` conservan evidencia runtime, decisiones, hashes y contexto histórico específico.

## Responsabilidades previstas a futuro

Theosfera contempla sistemas globales como Maintenance/Drain, amigos, parties, escuadrones, matchmaking y otras operaciones cross-server, pero su aparición en la visión del proyecto **no significa que su diseño interno esté aprobado**.

Cada sistema importante debe planificarse primero: owner, source of truth, persistencia, coordinación, fallos, seguridad, dependencias y runtime acceptance.

## Seguridad

No versionar:

- secretos HMAC;
- claves privadas;
- passwords de keystore/truststore;
- credenciales Redis productivas;
- tokens de providers/orchestrators;
- datos sensibles de jugadores.
