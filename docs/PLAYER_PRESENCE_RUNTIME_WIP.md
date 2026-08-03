# Redis Player Presence Runtime — WIP Checkpoint

Este documento registra el estado de trabajo en curso del runtime distribuido de presencia de jugadores. No representa un milestone cerrado ni un PR listo para fusionar.

## Base

- Base de `main`: `d9e65dd` — `feat: add Redis player presence coordinator (#56)`.
- Rama activa: `feature/redis-player-presence-runtime`.
- HEAD funcional previo a este checkpoint documental: `8e912db` — `fix: use existing local presence update API`.
- PR `#55` definió la política distributed-required por contrato y retiró `CoordinationMode`.
- PR `#56` fusionó el Redis Player Presence Coordinator aislado.

## Estado implementado

- `RedisCoordinationRuntime` expone `RedisPlayerPresenceCoordinator` reutilizando la conexión Lettuce existente.
- No se crea un segundo `RedisClient`.
- `VelocityRedisCoordinationBootstrap` delega la creación del coordinador de presencia.
- `PlayerPresenceRuntimeService` centraliza la frontera runtime de presencia distribuida.
- `PLAYER_SERVER_READY` publica presencia distribuida usando el `PlayerSessionLease` exacto de la conexión.
- `PlayerServerPresenceRegistry` se conserva temporalmente como mirror local operativo durante esta transición.
- Existe renovación periódica mediante `PlayerPresenceRenewalScheduler` y `VelocityPlayerPresenceRenewalScheduler`.
- Disconnect intenta retirar presencia Redis antes de liberar el lease de sesión.
- Shutdown intenta retirar presencia antes de liberar las sesiones.
- Si la retirada Redis no puede confirmarse, TTL permanece como fallback.
- Transferencias, capacidad y bootstrap distribuidos continúan fuera de scope.

## Correcciones realizadas durante el wiring

- Se resolvió la ambigüedad de constructores de `PlayerServerReadyMessageHandler` en tests.
- `TheosferaProxyLifecycleTest` fue alineado con `PlayerPresenceCoordinator` y `PlayerPresenceRuntimeService`.
- Se restauraron los contratos históricos exactos de logging requeridos por los tests.
- Se corrigió el camino legado para usar `PlayerServerPresenceRegistry.update(...)` en lugar del método inexistente `record(...)`.

## Validación confirmada

Último gate ejecutado sobre `8e912db`:

```powershell
.\gradlew.bat test --no-daemon
```

Resultado:

```text
BUILD SUCCESSFUL
```

El working tree local estaba limpio y sincronizado con `origin/feature/redis-player-presence-runtime` antes de crear este checkpoint documental.

## Punto exacto de reanudación

1. Añadir cobertura específica de `PlayerPresenceRuntimeService`.
2. Cubrir publicación fenced desde `PLAYER_SERVER_READY`.
3. Cubrir renovación periódica y callbacks o leases stale.
4. Cubrir eliminación de presencia antes del release durante disconnect.
5. Cubrir drain de presencia durante shutdown y fallback por TTL.
6. Reforzar lifecycle/wiring para exigir también el runtime distribuido de presencia.
7. Ejecutar `./gradlew.bat test --no-daemon`.
8. Ejecutar `./gradlew.bat clean build --no-daemon`.
9. Ejecutar `git diff main...HEAD --check`.
10. Confirmar `git status` limpio.
11. Auditar el diff final y las blocking calls Redis antes de abrir PR.

No abrir PR ni fusionar esta rama hasta completar esas pruebas, gates y revisión final.
