# Redis Player Presence Runtime — Final Checkpoint

Este documento registra el estado final y autoritativo del runtime distribuido de presencia de jugadores después de la fusión del PR `#57`.

## Base final

- Base original del milestone: `main` @ `d9e65dd` — `feat: add Redis player presence coordinator (#56)`.
- Rama de implementación: `feature/redis-player-presence-runtime`.
- HEAD validado antes del PR: `f72a85e`.
- PR `#57`: `feat: integrate Redis player presence runtime`.
- Estado post-merge: `main` @ `18a7713`.

Los PR `#55` y `#56` establecieron respectivamente la política distributed-required por contrato y el coordinador Redis de presencia aislado. El PR `#57` completa su integración en el runtime productivo del Proxy.

## Estado final implementado

- `RedisCoordinationRuntime` expone `RedisPlayerPresenceCoordinator` reutilizando la conexión Lettuce existente.
- No se crea un segundo `RedisClient` ni una segunda conexión Redis para presencia.
- `VelocityRedisCoordinationBootstrap` delega la creación del coordinador de presencia.
- `PlayerPresenceRuntimeService` centraliza publicación, renovación y retirada de presencia distribuida.
- `PLAYER_SERVER_READY` publica presencia usando el `PlayerSessionLease` exacto de la conexión portadora.
- El fencing token de la sesión forma parte de la presencia distribuida y protege ownership frente a callbacks o propietarios obsoletos.
- `PlayerServerPresenceRegistry` se conserva como mirror local operativo; Redis es la frontera distribuida para presencia.
- La presencia se renueva periódicamente mediante `PlayerPresenceRenewalScheduler` y `VelocityPlayerPresenceRenewalScheduler`.
- La renovación solo publica presencias cuyo jugador sigue conectado localmente y conserva un lease vinculado válido.
- Disconnect intenta retirar primero la presencia Redis mediante `removeIfOwned` y solo después libera el lease de sesión.
- Shutdown drena presencia antes de iniciar la liberación de las sesiones vinculadas.
- Si Redis no puede confirmar la retirada, el TTL permanece como fallback de limpieza.
- El lifecycle operacional exige que los runtimes distribuidos de sesión y presencia estén correctamente inicializados antes de activar listeners, comandos y schedulers.
- `presenceRuntimeService.start()` forma parte de la activación operacional y `stop()` ocurre antes del cleanup de registries.

## Orden autoritativo

Publicación:

```text
PLAYER_SERVER_READY
        ↓
validación del mirror local
        ↓
PlayerSessionLease exacto de la conexión
        ↓
Redis presence + session fencing token
```

Salida:

```text
disconnect / shutdown
        ↓
remove presence if owned
        ↓
session lease release
        ↓
TTL como fallback si Redis no confirma
```

Una ausencia temporal de Redis no autoriza a reemplazar ownership distribuido por estado local permisivo. La política general continúa siendo fail-closed.

## Cobertura añadida

El milestone añadió cobertura específica para:

- `PlayerPresenceRuntimeService`:
  - publicación inicial;
  - lease exacto y fencing token;
  - renovación periódica;
  - jugador desconectado;
  - binding ausente;
  - callbacks `STALE`;
  - `removeIfOwned`;
  - lifecycle del scheduler;
  - validación de identidades y configuración;
- wiring `PLAYER_SERVER_READY -> PlayerPresenceRuntimeService`;
- orden `presence removal -> session release` durante disconnect;
- orden de drain de presencia durante shutdown;
- fallback cuando Redis reporta `COORDINATION_UNAVAILABLE`;
- lifecycle de `TheosferaProxy` para start/stop del runtime de presencia.

## Validación final

Sobre `feature/redis-player-presence-runtime` @ `f72a85e`:

```powershell
.\gradlew.bat test --no-daemon
```

Resultado:

```text
BUILD SUCCESSFUL
723 tests completed, 22 skipped
```

Gate completo:

```powershell
.\gradlew.bat clean build --no-daemon
git diff main...HEAD --check
git status
```

Resultados confirmados:

- `clean build`: `BUILD SUCCESSFUL`;
- `git diff main...HEAD --check`: sin salida;
- working tree limpio y sincronizado con `origin/feature/redis-player-presence-runtime`.

GitHub Actions del PR `#57` también finalizó correctamente:

- workflow `Build`;
- job `Gradle Build`;
- conclusión `success`.

## Auditoría Redis

Los caminos nuevos de publicación, renovación y retirada de presencia usan APIs asíncronas Lettuce y `CompletionStage`.

El runtime de presencia no introduce una segunda conexión Redis ni nuevos `join()` en esos caminos. Los `join()` de inicialización/apagado y el `RedisClient.connect()` síncrono observados pertenecen al lifecycle Redis existente previamente en `main` y no fueron introducidos por este milestone.

## Política temporal actual

La presencia utiliza actualmente:

- `playerSessionTtl` como TTL de presencia;
- `playerSessionRenewInterval` como intervalo de renovación.

Separar estos parámetros en `playerPresenceTtl` y `playerPresenceRenewInterval` queda como mejora futura si se requiere una política de expiración independiente. No es blocker para el runtime fusionado.

## Fuera de scope conservado

Este milestone no introduce:

- coordinación distribuida de transferencias;
- capacidad o reservas distribuidas de backends;
- bootstrap distribuido de backends;
- parties, amigos o escuadrones.

## Estado de continuidad

El milestone `Redis Player Presence Runtime` está cerrado y fusionado.

La fuente autoritativa de código es `main` @ `18a7713` después del PR `#57`.

El siguiente incremento debe comenzar desde esta base y definir explícitamente cuál será la próxima frontera distribuida antes de escribir implementación. Transferencias, capacidad y bootstrap continúan siendo candidatos separados y no deben mezclarse en un mismo cambio sin una decisión arquitectónica previa.
