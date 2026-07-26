# TheosferaProxy — Frontera de coordinación global distribuida

**Estado:** propuesta arquitectónica previa a implementación
**Repositorio base:** `HarriOcho/TheosferaProxy`
**Checkpoint base:** `6ab6ee9` — `docs: checkpoint proxy operational observability (#42)`
**Alcance:** múltiples instancias de Velocity/TheosferaProxy
**Decisión sobre Redis:** pendiente; este documento define primero el contrato que cualquier transporte deberá cumplir.

---

## 1. Objetivo

Definir una frontera modular y fail-closed para coordinar estado temporal entre múltiples proxies sin acoplar el dominio de TheosferaProxy a Redis, a un cliente concreto ni a una topología específica.

El diseño debe permitir:

- exclusión global de sesiones duplicadas;
- presencia global con propietario verificable;
- una sola transferencia activa por jugador;
- reservas de capacidad atómicas entre proxies;
- coordinación exclusiva de bootstrap;
- recuperación segura tras expiraciones, reinicios y particiones;
- observabilidad explícita de la capa distribuida;
- conservar el comportamiento actual en modo de proxy único.

Este hito **no implementa Redis**, base de datos, parties, amigos, escuadrones ni persistencia permanente.

---

## 2. Principios no negociables

1. **No confundir estado remoto con evidencia local.**
   La identidad, salud y conectividad de un backend observadas por un proxy no prueban que otro proxy pueda alcanzarlo.

2. **No usar Pub/Sub como fuente de verdad.**
   Los eventos serán avisos o invalidaciones. Toda decisión autoritativa deberá consultar o mutar estado mediante una operación coordinada.

3. **No hacer fallback silencioso a memoria local en modo distribuido.**
   Si la coordinación global está configurada como obligatoria y deja de estar disponible, las operaciones que necesitan exclusión global deben fallar cerradas.

4. **No usar locks sin fencing.**
   Toda propiedad temporal deberá incluir un token o versión que permita rechazar a un propietario anterior después de expirar o perder su lease.

5. **No informar éxito antes de adquirir la autoridad necesaria.**
   Autenticación, transferencia, reserva de capacidad o bootstrap solo se considerarán aceptados después de una operación atómica exitosa en la capa autoritativa.

6. **Las operaciones distribuidas serán asíncronas.**
   Ningún acceso de red deberá bloquear hilos de Velocity.

7. **La base de datos futura seguirá siendo la fuente permanente.**
   La capa de coordinación solo gestionará propiedad temporal, exclusión, presencia efímera, leases, reservas y eventos.

---

## 3. Identidad de una instancia de Proxy

Cada proceso deberá identificarse mediante:

- `proxyName`: identificador estable configurado, por ejemplo `proxy-1`;
- `incarnationId`: UUID aleatorio generado en cada arranque;
- `startedAt`: instante del proceso;
- `fencingToken`: token monotónico otorgado al adquirir la membresía.

La identidad efectiva será:

```text
ProxyInstanceId = proxyName + incarnationId + fencingToken
```

Dos procesos con el mismo `proxyName` no podrán operar simultáneamente como propietarios válidos. Un proceso nuevo solo podrá adquirir la membresía cuando la anterior haya sido liberada o haya expirado.

---

## 4. Matriz de propiedad del estado

| Estado | Autoridad | Consistencia | TTL | Uso en routing |
|---|---|---:|---:|---|
| Política de backends | configuración local | carga estricta | no aplica | sí |
| Registro Velocity del backend | proxy local | local | ciclo del proceso | sí |
| Identidad por handshake | proxy local | local | ciclo/conexión | sí |
| Salud y `PING` pendientes | proxy local | local y fresca | umbral actual | sí |
| Membresía del proxy | distribuida | lease linealizable | 15 s | prerrequisito |
| Propiedad de sesión autenticada | distribuida | CAS/lease linealizable | 30 s | sí |
| Presencia global del jugador | distribuida y fenced | escritura condicional | 30 s | consultas globales |
| Transferencia activa por jugador | distribuida | exclusión linealizable | 20 s | sí |
| Resultado terminal/deduplicación | distribuida temporal | idempotente | 60 s | correlación |
| Jugadores conectados por proxy/backend | reporte distribuido por lease | suma de reportes frescos | 15 s | sí |
| Reserva de capacidad | distribuida | incremento atómico | 20 s | sí |
| Reserva bootstrap | distribuida | exclusión por backend | 30 s | sí |
| Failover en ejecución | local, protegido por lease global del jugador | local + fencing | duración del evento | sí |
| Snapshot operacional | derivado | best effort | no autoritativo | no |

### 4.1 Estado que permanece local

Los siguientes elementos no deben convertirse en una verdad global de routing:

- `BackendIdentityRegistry`;
- `BackendHealthRegistry`;
- `PendingBackendPingRegistry`;
- registro de servidores de Velocity;
- callbacks y futures de `ConnectionRequest`;
- detalles internos del intento de conexión;
- listeners de kick, conexión y desconexión.

Un backend solo será candidato en un proxy cuando cumpla simultáneamente:

1. está autorizado por la política local;
2. existe en Velocity local;
3. completó handshake válido con ese proxy;
4. su salud local es `HEALTHY` y fresca, o participa en la ruta explícita de bootstrap;
5. la coordinación global permite la operación;
6. existe capacidad global disponible.

Un `HEALTHY` publicado por otro proxy nunca sustituirá los puntos 2, 3 o 4.

---

## 5. Frontera modular propuesta

La lógica de dominio no dependerá de Redis. Dependerá de puertos pequeños y asíncronos.

```text
com.theosfera.proxy.coordination
├── CoordinationMode
├── CoordinationState
├── CoordinationAvailability
├── ProxyInstanceIdentity
├── ProxyMembershipCoordinator
├── PlayerSessionCoordinator
├── PlayerPresenceCoordinator
├── PlayerTransferCoordinator
├── BackendLoadCoordinator
├── BackendCapacityCoordinator
├── BackendBootstrapCoordinator
├── local
│   └── implementaciones compatibles con el runtime actual
└── distributed
    └── adaptadores futuros del transporte seleccionado
```

### 5.1 Modos

```text
LOCAL
DISTRIBUTED_REQUIRED
```

- `LOCAL`: preserva el comportamiento actual de una sola instancia.
- `DISTRIBUTED_REQUIRED`: exige membresía y coordinación disponible. Nunca degrada automáticamente a `LOCAL`.

No se propone un modo híbrido automático, porque una transición silenciosa de distribuido a local produciría split-brain.

### 5.2 Estado operacional

```text
STARTING
HEALTHY
DEGRADED
FENCED
STOPPING
```

- `STARTING`: todavía no existe autoridad para aceptar operaciones globales.
- `HEALTHY`: membresía vigente y transporte operativo.
- `DEGRADED`: fallo temporal; no se aceptan nuevas mutaciones globales.
- `FENCED`: la instancia perdió o no pudo demostrar su propiedad.
- `STOPPING`: no se aceptan operaciones nuevas y se liberan leases exactos.

---

## 6. Contratos mínimos

Las firmas exactas se decidirán durante la implementación, pero deben mantener estas propiedades.

### 6.1 Membresía

```java
CompletionStage<ProxyMembershipAcquireResult> acquire(
        ProxyInstanceIdentity identity
);

CompletionStage<ProxyMembershipRenewResult> renew(
        ProxyMembershipLease expected
);

CompletionStage<Boolean> releaseIfOwned(
        ProxyMembershipLease expected
);
```

Requisitos:

- adquisición atómica;
- renovación exact-match;
- liberación exact-match;
- fencing token monotónico;
- el proceso deja de mutar estado global al perder la membresía.

### 6.2 Sesión autenticada

```java
CompletionStage<PlayerSessionAcquireResult> acquire(
        PlayerSessionLeaseRequest request
);

CompletionStage<PlayerSessionRenewResult> renew(
        PlayerSessionLease expected
);

CompletionStage<Boolean> releaseIfOwned(
        PlayerSessionLease expected
);
```

La clave de exclusión será el UUID del jugador.

Resultados mínimos:

```text
ACQUIRED
ALREADY_OWNED
OWNED_BY_OTHER_PROXY
CONFLICT
COORDINATION_UNAVAILABLE
```

La confirmación de autenticación no podrá emitirse como exitosa hasta obtener `ACQUIRED` o un `ALREADY_OWNED` exacto de la misma encarnación.

### 6.3 Presencia

La presencia deberá incluir:

- UUID del jugador;
- backend;
- proxy propietario;
- `sessionFencingToken`;
- versión o secuencia monotónica;
- instante observado;
- expiración.

Solo el propietario vigente de la sesión podrá escribir o eliminar la presencia. Una presencia con token anterior será rechazada aunque su timestamp sea posterior.

### 6.4 Transferencia

La transferencia global deberá garantizar:

- una sola operación activa por jugador;
- idempotencia por `requestId`;
- conflicto si el mismo `requestId` contiene otro payload;
- propietario explícito;
- `sessionFencingToken`;
- expiración superior al timeout local;
- limpieza exact-match;
- resultado terminal deduplicado temporalmente.

Solo el proxy propietario ejecutará `ConnectionRequest`. Otro proxy podrá observar el estado, pero no completar ni limpiar la operación.

### 6.5 Carga y capacidad

El conteo global no podrá basarse únicamente en:

```java
RegisteredServer.getPlayersConnected().size()
```

Cada proxy solo observa sus propias conexiones.

La carga global de un backend será:

```text
SUMA(reportes frescos de jugadores conectados por proxy)
+ SUMA(reservas globales vigentes)
```

Cada reporte de carga deberá estar ligado a:

- backend;
- proxy e encarnación;
- fencing token de membresía;
- cantidad no negativa;
- TTL.

La reserva deberá ser atómica:

```text
si connectedGlobal + reservedGlobal < capacity
    reservar requestId
si no
    NO_CAPACITY
```

Resultados mínimos:

```text
RESERVED
ALREADY_RESERVED
NO_CAPACITY
REQUEST_ID_CONFLICT
COORDINATION_UNAVAILABLE
```

Una reserva expirada no podrá seguir contando. Una liberación tardía solo podrá retirar la reserva exacta que creó.

### 6.6 Bootstrap

La reserva bootstrap será una exclusión global por backend:

```text
backendName -> requestId + owner + fencing + expiresAt
```

No enciende procesos remotos y no demuestra salud.

Resultados mínimos:

```text
RESERVED
ALREADY_RESERVED
TARGET_BUSY
REQUEST_ID_CONFLICT
COORDINATION_UNAVAILABLE
```

El TTL inicial puede conservar los 30 segundos actuales. La liberación seguirá siendo exact-match.

---

## 7. TTL y renovación

Valores iniciales de diseño:

| Lease/registro | Renovación | TTL |
|---|---:|---:|
| Membresía del proxy | cada 5 s | 15 s |
| Sesión del jugador | cada 10 s | 30 s |
| Presencia | cada 10 s o actualización relevante | 30 s |
| Reporte de carga | cada 5 s | 15 s |
| Transferencia | no requerida normalmente | 20 s |
| Reserva de capacidad | ligada a transferencia | 20 s |
| Bootstrap | sin renovación inicial | 30 s |
| Dedupe de resultado terminal | no aplica | 60 s |

Reglas:

1. El TTL debe ser al menos tres veces el intervalo de renovación para leases periódicos.
2. El lease de transferencia y capacidad debe superar el timeout local de `ConnectionRequest`, actualmente 10 segundos.
3. Renovar nunca cambia el fencing token.
4. Reacquirir después de expirar genera un token nuevo.
5. Los relojes locales no decidirán por sí solos la propiedad. La expiración autoritativa pertenece al coordinador.
6. Los timestamps sirven para observabilidad y orden lógico, no sustituyen CAS ni fencing.

---

## 8. Política de degradación fail-closed

### 8.1 Pérdida antes de una operación

Si la capa distribuida no está disponible:

- no adquirir una nueva sesión;
- no confirmar autenticación global;
- no iniciar una nueva transferencia;
- no reservar capacidad;
- no reservar bootstrap;
- no mover parties ni ejecutar acciones sociales futuras;
- devolver un resultado seguro o mantener al jugador en el backend actual.

### 8.2 Pérdida durante una sesión

Mientras el lease local todavía sea demostrablemente vigente:

- el jugador puede permanecer en su backend actual;
- se bloquean nuevas mutaciones globales;
- no se inicia routing nuevo;
- se intenta renovar dentro del período vigente.

Cuando la instancia ya no puede demostrar propiedad o el lease expira:

- estado `FENCED`;
- detener renovaciones y publicaciones;
- ignorar callbacks que pretendan mutar estado global;
- desconectar al jugador de forma controlada;
- no redirigirlo automáticamente hacia Auth;
- conservar razones de kick cuando corresponda.

### 8.3 Pérdida durante una transferencia

Antes de ejecutar `ConnectionRequest`, el proxy comprobará:

- membresía vigente;
- sesión vigente;
- lease de transferencia vigente;
- reserva de capacidad exacta;
- reserva bootstrap exacta, cuando aplique.

Si la coordinación se pierde antes del side effect, la operación termina de forma segura sin conectar.

Si el side effect ya fue enviado a Velocity:

- no iniciar un segundo destino;
- `TIMED_OUT` continúa siendo terminal;
- el callback deberá comprobar fencing y exact-match;
- un callback tardío no podrá liberar o completar estado perteneciente a una operación posterior;
- si ya no existe autoridad, se realizará la limpieza local segura y no se publicará éxito global.

### 8.4 Failover ante kick

En modo distribuido, un redirect seguro exige:

- candidato local `RESOLVED`;
- salud local fresca;
- membresía vigente;
- sesión vigente;
- admisión global de capacidad.

Si no se puede comprobar cualquiera de estas condiciones, el resultado será `DISCONNECT`, preservando la razón original. No se usará un dato de carga cacheado como autorización.

---

## 9. Recuperación y split-brain

### 9.1 Arranque

1. generar `incarnationId`;
2. inicializar adaptador de coordinación;
3. adquirir membresía;
4. iniciar renovaciones;
5. habilitar operaciones globales;
6. publicar reportes de carga;
7. registrar estado en observabilidad.

En `DISTRIBUTED_REQUIRED`, el plugin puede cargar sus componentes locales, pero las operaciones globales permanecerán deshabilitadas hasta alcanzar `HEALTHY`.

### 9.2 Reinicio del proxy

Un proceso nuevo no reutilizará tokens del anterior.

Los jugadores conectados al proceso anterior se desconectarán por el reinicio de Velocity. El nuevo proceso no eliminará leases ajenos por nombre; esperará la expiración o utilizará una adquisición condicional segura.

### 9.3 Partición de red

La instancia que no pueda renovar:

1. entra en `DEGRADED`;
2. bloquea operaciones nuevas;
3. conserva únicamente operaciones permitidas durante la vigencia demostrable;
4. entra en `FENCED` al perder la propiedad;
5. desconecta sesiones que ya no puede poseer.

### 9.4 Pérdida total del estado temporal

El transporte seleccionado deberá permitir detectar una nueva generación de coordinación o garantizar que los fencing tokens no retroceden después de reinicios/failover.

Requisito mínimo:

```text
un token emitido antes de perder el estado nunca debe volver a ser válido
frente a una adquisición posterior.
```

Si el transporte no puede garantizarlo, no será apto para esta frontera sin una fuente durable adicional de generación/fencing.

---

## 10. Eventos distribuidos

Los eventos se publicarán solo después de una mutación autoritativa exitosa.

Ejemplos futuros:

- sesión adquirida o liberada;
- presencia actualizada;
- transferencia iniciada o terminada;
- carga actualizada;
- proxy degradado o fenced.

Reglas:

- entrega al menos una vez es aceptable;
- consumidores idempotentes;
- cada evento lleva identificador, versión y fencing;
- recibir un evento no autoriza una operación;
- ante pérdida o reordenamiento, el consumidor vuelve a consultar la fuente de verdad;
- Pub/Sub simple no reemplaza leases, CAS ni registros con TTL.

---

## 11. Integración con la arquitectura actual

La transición será incremental.

### Fase A — Contratos y adaptadores locales

- introducir el paquete `coordination`;
- definir interfaces asíncronas;
- implementar adaptadores `local` usando los registros actuales;
- sustituir dependencias concretas en servicios de dominio por puertos estrechos;
- conservar exactamente la semántica y las pruebas actuales;
- mantener `CoordinationMode.LOCAL` como valor inicial.

No se añade I/O ni cambia el runtime.

### Fase B — Lifecycle y observabilidad

- añadir `ProxyInstanceIdentity`;
- añadir `CoordinationState`;
- componer un `CoordinationModule`;
- mostrar modo, estado, proxyName, incarnation y lease en `/theosferaproxy status`;
- probar lifecycle, renovación, shutdown y fencing con dobles en memoria.

### Fase C — Simulador multi-proxy

Antes de Redis, implementar un coordinador compartido únicamente para pruebas que permita levantar dos composiciones lógicas en el mismo test.

Debe demostrar:

- exclusión de sesión;
- capacidad global;
- bootstrap exclusivo;
- expiración;
- renovación;
- fencing;
- callbacks tardíos;
- degradación.

Este simulador no será un transporte de producción.

### Fase D — Contratos de protocolo necesarios

Revisar TheosferaProtocol para representar de forma explícita:

- rechazo temporal de autenticación por coordinación no disponible;
- propiedad/fencing cuando un mensaje futuro lo requiera;
- estados distribuidos sin exponer detalles internos.

No se modificarán contratos hasta identificar una necesidad real en los flujos de integración.

### Fase E — Selección de transporte

Evaluar Redis u otra alternativa con los criterios del apartado 12.

### Fase F — Adaptador distribuido

- implementación aislada bajo `coordination.distributed`;
- scripts/operaciones atómicas;
- timeouts estrictos;
- reconexión controlada;
- métricas;
- pruebas de integración;
- validación runtime con dos proxies.

---

## 12. Criterios para decidir si Redis es apropiado

Redis solo será seleccionado si puede satisfacer:

1. CAS y operaciones multi-clave atómicas;
2. TTL autoritativo;
3. fencing monotónico que no retroceda en failover;
4. liberación exact-match;
5. deduplicación por `requestId`;
6. cliente Java 21 completamente asíncrono;
7. timeouts y cancelación;
8. reconexión sin fallback inseguro;
9. alta disponibilidad y política de persistencia acordes al riesgo;
10. observabilidad de latencia, errores, expiraciones y reconexiones;
11. pruebas reproducibles de partición, reinicio y pérdida de nodo;
12. eventos opcionales que no sean fuente de verdad.

Redis Pub/Sub por sí solo no cumple esta frontera.

Redis Streams puede servir para eventos o auditoría temporal, pero tampoco reemplaza las estructuras autoritativas de leases y reservas.

---

## 13. Observabilidad requerida

La futura salida operacional deberá distinguir:

- `coordinationMode`;
- `coordinationState`;
- `proxyName`;
- `incarnationId`;
- membresía vigente;
- tiempo restante estimado o última renovación;
- latencia del coordinador;
- errores consecutivos;
- sesiones poseídas;
- transferencias activas;
- reservas de capacidad;
- reportes de carga frescos;
- reservas bootstrap;
- expiraciones y fencing observados.

La vista seguirá siendo de solo lectura. No se añadirá un comando de “forzar unlock” sin una política de seguridad y auditoría explícita.

---

## 14. Pruebas de aceptación arquitectónica

La implementación distribuida no se considerará lista hasta cubrir:

1. dos proxies no adquieren simultáneamente la misma sesión;
2. una adquisición repetida exacta es idempotente;
3. un `requestId` reutilizado con otro payload produce conflicto;
4. la suma de jugadores y reservas de varios proxies nunca supera capacidad;
5. reportes de carga expirados dejan de contar;
6. la salud remota no vuelve elegible un backend localmente no saludable;
7. una reserva bootstrap es exclusiva entre proxies;
8. el propietario anterior queda fenced después de expirar;
9. una liberación tardía no elimina el lease del propietario nuevo;
10. un callback tardío no completa una transferencia posterior;
11. la pérdida del coordinador bloquea autenticaciones y transferencias nuevas;
12. el jugador permanece en el backend actual durante una degradación transitoria;
13. al perder definitivamente la sesión, el jugador se desconecta de forma controlada;
14. un reinicio crea una nueva encarnación y no reutiliza autoridad anterior;
15. el modo distribuido nunca cae automáticamente a modo local;
16. el apagado libera únicamente recursos poseídos por la encarnación actual;
17. la recuperación del coordinador exige reacquirir membresía antes de operar;
18. `/theosferaproxy status` muestra claramente `HEALTHY`, `DEGRADED` o `FENCED`.

---

## 15. Decisiones cerradas por este diseño

- La coordinación será transport-agnostic.
- Redis no se introduce todavía.
- Identidad y salud de backends permanecen locales.
- Sesión, transferencia, capacidad y bootstrap requieren autoridad global.
- La carga global será híbrida: reportes frescos por proxy más reservas globales.
- Toda propiedad usa TTL, exact-match y fencing.
- En modo distribuido no existe fallback silencioso a memoria local.
- La pérdida de coordinación bloquea operaciones nuevas.
- Auth no se utiliza como destino improvisado de recuperación.
- Los eventos no son fuente de verdad.
- La implementación comenzará con contratos y adaptadores locales sin cambiar comportamiento.

---

## 16. Primer incremento recomendado

Crear un PR exclusivamente arquitectónico/técnico con:

1. este documento;
2. actualización de `PROJECT_STATE.md` indicando que la frontera fue definida;
3. sin Redis;
4. sin cambios de protocolo;
5. sin cambios de comportamiento runtime.

Después, crear un segundo PR de código para **Fase A: contratos asíncronos y adaptadores locales**, manteniendo todas las pruebas actuales y añadiendo pruebas de equivalencia de semántica.
