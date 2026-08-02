# Redis Coordination Runtime Checkpoint

> Checkpoint autoritativo posterior a los PR #50, #51 y #52.
>
> Si una sección histórica de `PROJECT_STATE.md` contradice este documento respecto de Redis, proxy membership o player-session ownership, este checkpoint representa el estado más reciente hasta que `PROJECT_STATE.md` sea consolidado.

## Estado Git

Estado integrado en `main`:

```text
d6095bd feat: activate Redis player session runtime (#52)
7c4f65a feat: add Redis coordination lifecycle (#51)
5e16d15 feat: add Redis proxy membership coordinator (#50)
f89f8c2 docs: checkpoint Redis player session coordination (#49)
e2fe4f1 feat: add Redis player session coordinator (#48)
d4c06d9 feat: configure stable proxy instance identity (#47)
```

Commit autoritativo de `main` al abrir este checkpoint:

```text
d6095bd274ae7dfb61da7962ada78eac69e2c663
```

Rama documental:

```text
docs/redis-runtime-checkpoint
```

## Afirmaciones históricas superseded

Quedan superseded las afirmaciones anteriores que indiquen que:

- `LocalPlayerSessionCoordinator` sigue siendo la autoridad runtime de producción;
- `RedisPlayerSessionCoordinator` existe únicamente como adaptador aislado;
- TheosferaProxy todavía no crea ni administra una conexión Redis;
- no existe `ProxyMembershipCoordinator` distribuido;
- no existe renovación runtime de membership;
- no existe renovación runtime de player-session leases;
- no existe coordinación runtime entre procesos Proxy para membership o ownership de sesiones autenticadas.

## Proxy instance identity

`ProxyInstanceIdentity` separa:

- `proxyName`: identidad lógica estable configurada;
- `incarnationId`: UUID efímero nuevo en cada arranque.

Configuración:

```text
plugins/theosferaproxy/proxy-instance.properties
```

Ejemplo:

```properties
proxy-name=proxy-1
```

Distintas instancias Proxy deben usar distintos `proxyName`. Un reinicio conserva el nombre lógico, pero genera una nueva incarnation.

## Redis proxy membership

La membership distribuida ya es real y autoritativa en Redis.

La implementación incluye:

- adquisición atómica por `proxyName`;
- TTL autoritativo;
- renovación exact-match;
- release exact-match;
- fencing monotónico;
- exclusión de dos incarnations intentando poseer simultáneamente el mismo `proxyName`;
- rechazo fail-closed de estado Redis corrupto;
- operaciones críticas atómicas mediante Lua/EVAL.

Estados operacionales vigentes:

- `STARTING`;
- `HEALTHY`;
- `DEGRADED`;
- `FENCED`;
- `STOPPING`.

El Proxy no activa su superficie operacional hasta adquirir correctamente su membership Redis. Cuando coordinación no está `HEALTHY`, el trabajo de protocolo queda bloqueado fail-closed. La pérdida definitiva de ownership de membership provoca fencing de la incarnation actual.

## Redis coordination runtime

Configuración runtime:

```text
plugins/theosferaproxy/redis-coordination.properties
```

Valores predeterminados vigentes:

```properties
redis-uri=redis://127.0.0.1:6379
membership-ttl-seconds=15
membership-renew-seconds=5
player-session-ttl-seconds=30
player-session-renew-seconds=10
```

Cada intervalo de renovación debe ser positivo y menor que su TTL correspondiente.

`RedisCoordinationRuntime` posee el lifecycle del cliente y conexión Lettuce. Player sessions reutilizan esa misma conexión: no se crea un segundo `RedisClient`.

Regla crítica aprendida durante PR #51: ninguna callback del event loop de Lettuce debe ejecutar cierre bloqueante de conexión/cliente. Los cierres derivados de callbacks deben salir del event loop.

Integration tests Redis usan:

```text
redis:7.4.2-alpine
```

Política Testcontainers:

- sin Docker local: integration tests pueden omitirse;
- con `CI=true`: Docker/Testcontainers es obligatorio y su ausencia debe fallar explícitamente.

## Redis player-session runtime

`RedisPlayerSessionCoordinator` es ahora la autoridad runtime de ownership de sesiones autenticadas.

`LocalPlayerSessionCoordinator` permanece como adaptador local/arquitectónico y para pruebas, pero ya no es compuesto por `TheosferaProxy` como autoridad de producción.

Orden de inicialización relevante:

```text
ProxyInstanceIdentity
→ Redis runtime
→ adquirir membership
→ CoordinationState HEALTHY
→ crear RedisPlayerSessionCoordinator
→ crear release/disconnect services
→ crear PlayerSessionRenewalService
→ componer protocolo y comandos
→ activar superficie operacional
→ iniciar renovación de sesiones
```

Una autenticación positiva exige primero adquirir un lease Redis compatible.

El lease conserva:

- sesión autenticada;
- `proxyName`;
- `incarnationId`;
- fencing token.

Redis mantiene el ownership global. El proceso conserva localmente únicamente las responsabilidades necesarias para seguridad y carreras:

- binding del lease a la conexión exacta;
- generaciones OLD/NEW;
- deduplicación y replay;
- `attemptId`;
- fencing floors locales;
- quarantines de release;
- deadlines de renovación;
- mirror local necesario para reconciliar callbacks exactos.

`AuthenticatedPlayerSessionRegistry` continúa existiendo localmente, pero no sustituye la autoridad Redis de ownership distribuido.

## Active player-session renewal

Valores predeterminados:

```text
TTL: 30 segundos
renew interval: 10 segundos
```

Semántica:

```text
RENEWED
→ actualizar deadline local de autoridad

COORDINATION_UNAVAILABLE
→ conservar temporalmente solo mientras el último lease confirmado
  permanezca dentro de su deadline

NOT_FOUND / NOT_OWNER / CONFLICT
→ pérdida terminal de autoridad
→ invalidar binding exacto
→ revocar autenticación local exacta
→ desconectar al jugador de forma controlada
```

No se permiten renovaciones superpuestas del mismo lease. Un callback tardío de un lease anterior no puede revocar, renovar ni desconectar una conexión o lease posterior.

Una interrupción breve de Redis no provoca por sí sola un kick inmediato si el Proxy todavía puede demostrar que el último lease confirmado no venció. Al agotarse el deadline sin renovación confirmada, vuelve a aplicarse fail-closed.

## Shutdown y session drain

El shutdown detiene primero admisión y superficie operacional. La renovación de sesiones se detiene antes de cerrar Redis.

Antes de limpiar estado local se realiza un drain explícito de leases Redis vinculados.

La frontera sobre `PlayerSessionLeaseBindingRegistry` es atómica:

```text
adquirir lock del registry
→ capturar snapshot de leases vinculados
→ limpiar/fencear bindings y adquisiciones pendientes bajo el mismo lock
→ liberar en Redis los leases capturados
```

Esto evita que un acquire ya en vuelo pueda vincular un lease nuevo después del snapshot de shutdown.

Si un acquire Redis termina después del fence local, su resultado deja de ser reclamable y un lease exitoso no reclamado entra por la ruta segura de release.

El drain espera como máximo cinco segundos. Si Redis no confirma todas las liberaciones dentro del límite, shutdown continúa y el TTL Redis actúa como último mecanismo de expiración.

Después se limpia el estado runtime local, se libera membership mediante exact-match y se cierra el runtime Redis.

## Estado que todavía permanece local

PR #52 distribuye únicamente:

- membership del Proxy;
- ownership y renovación de player sessions.

Continúan siendo locales a cada proceso Proxy:

- identidad autenticada de backends;
- health/freshness de backends;
- `PING` pendientes;
- presencia de jugadores;
- transferencias pendientes;
- reservas temporales de capacidad;
- reservas bootstrap;
- failovers pendientes;
- observabilidad administrativa `/theosferaproxy status`;
- callbacks y `ConnectionRequest` de Velocity.

Esto es deliberado. La salud observada por un Proxy continúa siendo local y no debe usarse como prueba de reachability desde otro Proxy.

Presencia, transferencias, capacidad y bootstrap no deben migrarse a Redis dentro del siguiente incremento de `CoordinationMode`.

## CoordinationMode

El enum existente define:

```text
LOCAL
DISTRIBUTED_REQUIRED
```

A fecha de este checkpoint, el enum todavía no está cableado explícitamente a la composición runtime.

Sin embargo, desde PR #51 y PR #52, el comportamiento efectivo de membership y player sessions ya exige Redis para iniciar y operar de forma saludable; no existe fallback productivo silencioso hacia `LocalPlayerSessionCoordinator`.

Por tanto, el siguiente incremento no debe reimplementar Redis ni player sessions. Debe definir y activar explícitamente la semántica de `DISTRIBUTED_REQUIRED` sobre la arquitectura existente.

Antes de escribir ese wiring debe decidirse si `CoordinationMode` representa:

1. el requisito de disponibilidad de la capa distribuida actualmente implementada; o
2. la garantía de que todas las responsabilidades globales definidas en `docs/DISTRIBUTED_COORDINATION_BOUNDARY.md` ya están distribuidas.

No debe activarse `DISTRIBUTED_REQUIRED` con una semántica ambigua.

## Validación del PR #52

Antes del merge se confirmó localmente:

```text
.\gradlew.bat test --no-daemon         → BUILD SUCCESSFUL
.\gradlew.bat clean build --no-daemon → BUILD SUCCESSFUL
git status                              → working tree clean
git diff --check                        → limpio
```

PR #52 fue fusionado mediante squash como:

```text
d6095bd feat: activate Redis player session runtime (#52)
```

La revisión post-merge no encontró comentarios, reviews ni review threads pendientes en el PR. No se afirma un resultado de CI/Codex del PR #52 que no haya sido observado directamente antes del merge.

## Limitaciones vigentes

Todavía quedan pendientes:

- definición/wiring explícito de `CoordinationMode`;
- validación runtime real con dos procesos Velocity simultáneos;
- coordinación distribuida de presencia;
- exclusión distribuida de transferencias;
- capacidad global;
- bootstrap global;
- observabilidad Redis dedicada;
- pruebas de restart y HA Redis;
- garantía de monotonicidad del fencing counter bajo la estrategia futura de persistencia/failover Redis;
- persistencia durable de datos permanentes;
- métricas históricas y auditoría durable.

Redis sigue siendo estado temporal de coordinación. No debe convertirse en la fuente permanente de perfiles, progreso u otros datos durables.

## Punto exacto de reanudación

El siguiente hito es definir la semántica exacta de `CoordinationMode` y activar `DISTRIBUTED_REQUIRED` de forma explícita sobre el runtime Redis ya existente.

Orden recomendado:

1. auditar todos los usos actuales de `CoordinationMode`;
2. decidir qué garantía expresa exactamente `DISTRIBUTED_REQUIRED`;
3. introducir una única fuente de configuración del modo;
4. impedir fallback silencioso cuando el modo exige coordinación distribuida;
5. conectar el modo con startup, admission gate y lifecycle existentes;
6. conservar membership y player sessions sobre Redis;
7. mantener presencia, transferencias, capacidad y bootstrap fuera del scope de este incremento;
8. añadir pruebas que demuestren la diferencia entre `LOCAL` y `DISTRIBUTED_REQUIRED`, o retirar `LOCAL` del runtime productivo si la arquitectura concluye que ya no es un modo soportado;
9. ejecutar suite completa y clean build;
10. después planificar el siguiente estado global distribuido de manera incremental.

No introducir parties, amigos, escuadrones ni MMOProfiles en este incremento.
