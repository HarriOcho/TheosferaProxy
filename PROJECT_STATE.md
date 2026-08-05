# TheosferaProxy — Project State

> Fuente de verdad de continuidad técnica y funcional de TheosferaProxy.
>
> Antes de proponer o implementar cambios, revisar `AGENTS.md`,
> `CONTRIBUTING.md`, este archivo y el estado real del repositorio.

## 1. Identidad

- Proyecto: `TheosferaProxy`.
- Repositorio: `HarriOcho/TheosferaProxy`.
- Propietario: HarriOcho.
- Plataforma: Velocity.
- Java objetivo: Java 21.
- Build: Gradle Kotlin DSL mediante Gradle Wrapper.
- Package raíz: `com.theosfera.proxy`.
- Versión actual: `0.1.0-SNAPSHOT`.
- Plugin ID: `theosferaproxy`.

TheosferaProxy es el proxy y coordinador global de la network Theosfera.

## 2. Topología inicial

La primera etapa de la network contiene:

```text
Theosfera Network
├── Proxy
├── auth-1
├── lobby-1
└── skyblock-1
```

Tipos de backend definidos:

- `AUTH`;
- `LOBBY`;
- `SKYBLOCK`.

La arquitectura debe permitir añadir servidores y modalidades sin
introducir su lógica específica dentro del proxy.

Topología runtime ampliada validada en el checkpoint de balanceo y retry:

```text
Proxy:      127.0.0.1:25565
lobby-1:    127.0.0.1:25566
skyblock-1: 127.0.0.1:25567
auth-1:     127.0.0.1:25568
lobby-2:    127.0.0.1:25569
```

Durante la validación final de `9d0ea02`, `lobby-1` estuvo configurado
pero apagado, mientras `proxy`, `skyblock-1`, `auth-1` y `lobby-2`
estuvieron activos. La topología inicial se conserva como contexto
histórico; el estado actual ya incluye una segunda instancia Lobby.

## 3. Separación de responsabilidades

TheosferaProxy coordina o coordinará:

- autenticación confirmada por Auth;
- sesiones autenticadas;
- presencia global;
- backend actual;
- movimientos entre servidores;
- comunicación distribuida;
- amigos;
- parties;
- escuadrones;
- invitaciones;
- eventos globales.

No pertenecen al proxy:

- lógica Bukkit o Paper;
- mundos, entidades o inventarios;
- menús de inventario;
- misiones específicas;
- mecánicas de Skyblock;
- almacenamiento de ítems;
- integración directa con SuperiorSkyblock2.

Paper y Velocity permanecen separados. Las clases dependientes de
plataforma no se comparten entre plugins.

## 4. Fundación Velocity

La fundación Velocity está implementada y probada.

Confirmado:

- Velocity API `3.5.0-SNAPSHOT` como `compileOnly`;
- annotation processor de Velocity;
- metadata generada mediante `@Plugin`;
- inyección de `ProxyServer`, `Logger` y `@DataDirectory`;
- inicialización mediante `ProxyInitializeEvent`;
- apagado mediante `ProxyShutdownEvent`;
- Gradle Wrapper;
- Java 21;
- configuración cache de Gradle;
- GitHub Actions;
- pruebas con JUnit 5 y Mockito.

No existe un `velocity-plugin.json` mantenido manualmente.

## 5. Dependencia de TheosferaProtocol

El contrato compartido vive en el repositorio independiente:

`HarriOcho/TheosferaProtocol`

TheosferaProxy lo consume mediante:

- dependencia Gradle;
- composite build local con el repositorio hermano;
- resolución publicada para GitHub Actions;
- empaquetado runtime mediante Shadow.

TheosferaProtocol es Java puro y no depende de Velocity, Paper o Bukkit.

No duplicar contratos del protocolo dentro de TheosferaProxy.

## 6. Empaquetado runtime

El JAR ejecutable se genera mediante Shadow.

Confirmado:

- el JAR normal está desactivado;
- `build` depende de `shadowJar`;
- el artefacto conserva el nombre esperado de TheosferaProxy;
- TheosferaProtocol se incluye en runtime;
- Gson se incluye y se relocaliza bajo el namespace privado del proxy;
- las APIs proporcionadas por Velocity no se empaquetan;
- las firmas de dependencias se excluyen.

El JAR fue probado en una instancia local real de Velocity.

## 7. Canal de protocolo

Canal oficial:

```text
theosfera:network
```

La clase `ProtocolChannelRegistration` controla su ciclo de vida.

Durante la inicialización:

1. se crea la infraestructura de mensajería;
2. se registra el canal;
3. se registra el listener de mensajes;
4. se registran los listeners de ciclo de jugador.

Durante el apagado:

1. se desregistran los listeners;
2. se limpian los registros temporales;
3. se desregistra el canal.

## 8. Recepción y decodificación

`ProtocolMessageListener` recibe mensajes de Plugin Messaging.

Comportamiento confirmado:

- solo procesa `theosfera:network`;
- solo acepta como origen un `ServerConnection`;
- consume los mensajes del protocolo sin reenviarlos;
- rechaza mensajes sobredimensionados;
- decodifica envelopes registrados;
- aplica autorización antes del dispatch;
- no permite que payloads arbitrarios controlen su clase de
  deserialización.

`ProtocolMessageDecoder` encapsula la decodificación registrada.

`ProtocolJsonCodec.decodeRegistered()` resuelve el payload mediante
`ProtocolMessageRegistry`.

## 9. Dispatch y envío

La infraestructura incluye:

- `ProtocolMessageContext`;
- `ProtocolMessageHandler`;
- `ProtocolMessageDispatcher`;
- `ProtocolMessageSender`.

El dispatcher:

- registra un único handler por tipo;
- rechaza handlers duplicados;
- entrega mensajes mediante su tipo registrado;
- conserva el origen mediante `ProtocolMessageContext`.

El sender:

- codifica envelopes;
- usa el canal oficial;
- informa si el backend aceptó el envío.

## 10. Heartbeat y health checking periódico

`PingMessageHandler` implementa:

```text
PING → PONG
```

El handler:

- responde al mismo backend;
- conserva el `requestId`;
- utiliza `ProtocolMessageSender`;
- está compuesto en el dispatcher principal.

Existen pruebas unitarias y una prueba integral del flujo heartbeat.

El health checking periódico y correlacionado está fusionado en `main`:

- `fa2dccc feat: integrate backend health checking (#36)`.

Implementa su integración con la resolución de destinos:

- el Proxy emite `PING` periódicos hacia los backends registrados;
- cada comprobación conserva correlación entre solicitud y respuesta;
- los `PONG` recibidos se validan antes de actualizar el estado;
- el estado de salud y su frescura se mantienen en memoria;
- los timeouts y respuestas inválidas fallan cerrado;
- el lifecycle del scheduler y del estado temporal se limpia al apagar el
  plugin;
- `TransferTargetResolver` exige estado `HEALTHY` para resolver como jugable
  un backend autenticado con jugadores conectados;
- un backend autenticado cuya salud dejó de estar fresca ya no se selecciona
  como destino jugable normal;
- un destino vacío elegible puede conservar la resolución
  `BOOTSTRAP_REQUIRED`, sin ser confundido con un backend actualmente sano.

La implementación está cubierta por pruebas automatizadas y fue validada en
runtime con pérdida de frescura, exclusión segura, fallo controlado y
recuperación mediante el flujo de bootstrap.

## 11. Handshake seguro de backends

El handshake de backends está implementado.

Componentes:

- `BackendAuthorizationPolicy`;
- `BackendPolicyConfigLoader`;
- `BackendPolicyEntry`;
- `BackendIdentity`;
- `BackendIdentityRegistry`;
- `BackendRegistrationResult`;
- `BackendHelloMessageHandler`;
- `BackendMessageAuthorizer`.

Archivo runtime:

```text
plugins/theosferaproxy/backends.properties
```

Formato vigente:

```text
nombre=TIPO,capacidad,preferencia
```

Configuración actual validada:

```properties
auth-1=AUTH,1,100
lobby-1=LOBBY,100,90
lobby-2=LOBBY,100,80
skyblock-1=SKYBLOCK,200,80
```

El archivo se crea con valores predeterminados si no existe.

Cada entrada define tipo, capacidad y preferencia. La capacidad debe ser
mayor que cero y la preferencia no puede ser negativa. La carga valida
estrictamente la estructura de tres campos, el tipo declarado y los valores
numéricos antes de construir la política.

La política autoriza identidades y provee capacidad/preferencia para la
selección, pero no sustituye el health checking: identidad registrada,
estado `HEALTHY` y frescura vigente siguen siendo conceptos separados.

Flujo:

1. un backend envía `BACKEND_HELLO`;
2. su nombre debe coincidir con el origen Velocity;
3. nombre y tipo deben estar autorizados por la política;
4. la identidad se registra de forma concurrente;
5. el proxy responde `BACKEND_HELLO_ACK`;
6. los mensajes posteriores se autorizan según la identidad registrada.

Reglas actuales:

- `BACKEND_HELLO`: permitido antes del registro para ejecutar el
  handshake;
- `PING` y `PONG`: permitidos para cualquier backend registrado;
- `PLAYER_AUTHENTICATED`: únicamente `AUTH`;
- `PLAYER_SERVER_READY`: únicamente `LOBBY` o `SKYBLOCK`;
- `TRANSFER_REQUEST`: permitido desde `AUTH`, `LOBBY` o `SKYBLOCK`,
  con reglas de origen y destino específicas;
- mensajes reservados de respuesta no se aceptan arbitrariamente como
  entrada.

## 12. Sesiones autenticadas

Las sesiones autenticadas siguen respaldadas por memoria local del proceso, pero
el flujo runtime ya pasa por una frontera de coordinación asíncrona antes de
confirmar la autenticación.

Componentes:

- `AuthenticatedPlayerSession`;
- `PlayerSessionRegistrationResult`;
- `AuthenticatedPlayerSessionRegistry`;
- `PlayerSessionCoordinator`;
- `LocalPlayerSessionCoordinator`;
- `RedisPlayerSessionCoordinator`;
- `ProxyInstanceIdentity`;
- `ProxyInstanceIdentityConfigLoader`;
- `PlayerSessionLease`;
- `PlayerSessionLeaseRequest`;
- `PlayerSessionAcquireResult`;
- `PlayerSessionLeaseBindingRegistry`;
- `PlayerSessionReleaseService`;
- `PlayerAuthenticatedMessageHandler`.

Flujo actual:

1. `auth-1` autentica al jugador;
2. envía `PLAYER_AUTHENTICATED`;
3. el authorizer confirma que el origen registrado es `AUTH`;
4. el handler valida que el jugador portador coincide con el UUID y nombre del
   payload;
5. el handler inicia una adquisición asíncrona mediante
   `PlayerSessionCoordinator`;
6. solo después de obtener un lease compatible intenta vincularlo a la
   conexión actual y responde el ACK terminal correspondiente.

La sesión contiene:

- UUID del jugador;
- nombre validado;
- instante de autenticación.

El adaptador runtime actual sigue siendo `LocalPlayerSessionCoordinator`.
`RedisPlayerSessionCoordinator` existe como adaptador distribuido aislado y
probado bajo:

```text
com.theosfera.proxy.coordination.distributed.redis
```

`TheosferaProxy` todavía no crea conexiones Redis ni referencia
`RedisPlayerSessionCoordinator` en su composición runtime; por tanto Redis no
es todavía la autoridad runtime y no hay exclusión real entre múltiples
procesos Proxy.

`ProxyInstanceIdentity` ahora separa:

- `proxyName`: identidad lógica estable configurada de la instancia Proxy;
- `incarnationId`: UUID efímero nuevo en cada ejecución del proceso.

La identidad se carga durante la inicialización mediante
`ProxyInstanceIdentityConfigLoader`, no mediante I/O en el constructor.
Archivo runtime:

```text
plugins/theosferaproxy/proxy-instance.properties
```

Propiedad:

```properties
proxy-name=proxy-1
```

Semántica confirmada:

- reiniciar la misma instancia lógica conserva `proxyName`;
- cada arranque genera un `incarnationId` nuevo;
- distintas instancias Proxy deben usar distintos `proxyName`;
- `incarnationId` no se persiste;
- no se genera un nombre lógico aleatorio;
- una configuración inválida falla temprano;
- `proxy-name` acepta ASCII lowercase, números y guiones, de 1 a 32
  caracteres, sin iniciar ni terminar en guion.

El registro local histórico distingue:

- `REGISTERED`;
- `ALREADY_REGISTERED`;
- `CONFLICT`.

Un conflicto no reemplaza silenciosamente la sesión existente.

La adquisición y el binding actuales añaden protecciones de runtime local:

- ownership exacto ligado al objeto `Player`, conexión, `requestId`,
  `attemptId`, sesión y lease;
- generaciones de conexión para distinguir conexiones OLD/NEW del mismo UUID;
- fencing token y floors históricos para impedir regresiones locales;
- replay pendiente y replay terminal exacto por identidad de solicitud;
- ACK terminal cacheado cuando corresponde;
- replay exitoso condicionado a que el binding vivo siga existiendo;
- reutilización conflictiva de `requestId` con otro payload falla cerrada;
- timeouts de adquisición mediante
  `VelocityPlayerSessionAcquisitionTimeoutScheduler`;
- cada retry o ronda posterior usa un `attemptId` nuevo;
- callbacks tardíos o timeouts de intentos anteriores no mutan operaciones
  posteriores;
- solicitudes superseded que deben permanecer silenciosas no crean
  `TerminalRequest` replayable.

La limpieza por desconexión usa binding exacto:

- un disconnect OLD no debe afectar un `Player` NEW con el mismo UUID;
- la autenticación local se revoca solo cuando coincide la sesión del lease
  exacto;
- una conexión NEW pendiente no hereda accidentalmente autenticación por UUID
  de una conexión OLD;
- una desconexión OLD tardía no revoca auth de una NEW ya vinculada;
- el lease exacto puede liberarse aunque la sesión local ya haya sido
  revocada;
- un lease stale no puede liberar el lease vigente.

## 13. Presencia en backends

La presencia del jugador está implementada en memoria.

Componentes:

- `PlayerServerPresence`;
- `PlayerPresenceUpdateResult`;
- `PlayerServerPresenceRegistry`;
- `PlayerServerReadyMessageHandler`.

Flujo:

1. el jugador debe tener una sesión autenticada;
2. Lobby o Skyblock envía `PLAYER_SERVER_READY`;
3. el backend declarado debe coincidir con el origen Velocity;
4. el registro actualiza la presencia global.

Resultados posibles:

- `RECORDED`;
- `ALREADY_RECORDED`;
- `UPDATED`;
- `NOT_AUTHENTICATED`;
- `STALE`;
- `CONFLICT`.

Los eventos anteriores al estado actual no reemplazan presencia nueva.

Dos estados diferentes con el mismo timestamp se consideran conflicto.

## 14. Transferencias seguras de jugadores

La coordinación de transferencias está implementada en memoria.

Contratos utilizados desde TheosferaProtocol:

- `TransferRequestPayload`;
- `TransferResultPayload`;
- `TransferResultStatus`;
- `TRANSFER_REQUEST`;
- `TRANSFER_RESULT`.

Componentes:

- `PendingPlayerTransfer`;
- `PlayerTransferRegistrationResult`;
- `PendingPlayerTransferRegistry`;
- `TransferTargetResolutionStatus`;
- `TransferTargetResolution`;
- `TransferTargetResolver`;
- `PlayerTransferCompletion`;
- `PlayerTransferExecutor`;
- `TransferResultSender`;
- `PlayerTransferTargetAllocationService`;
- `BackendLoadCandidate`;
- `BackendLoadSelector`;
- `BackendCapacityReservation`;
- `BackendCapacityReservationRegistry`;
- `BackendBootstrapReservation`;
- `BackendBootstrapRegistry`;
- `PlayerTransferRetryCoordinator`;
- `TransferTargetResolutionContractViolationException`;
- `TransferRequestMessageHandler`;
- `LobbyCommand`;
- `LobbyCommandRegistration`;
- `LobbyTransferService`.

Flujo:

1. Auth, Lobby o Skyblock envía `TRANSFER_REQUEST`;
2. el UUID solicitado debe coincidir con el jugador propietario del
   `ServerConnection`;
3. el jugador debe poseer una sesión autenticada;
4. la identidad del backend, la conexión actual y el origen deben
   coincidir;
5. el destino debe estar autorizado por la política;
6. el destino debe existir en Velocity;
7. el destino debe haber completado un handshake válido;
8. el jugador no puede ser enviado a Auth ni al backend actual;
9. la solicitud se registra como transferencia pendiente;
10. Velocity ejecuta la conexión de forma asíncrona;
11. la operación expira después de diez segundos;
12. el proxy responde `TRANSFER_RESULT` conservando el `requestId`;
13. una transferencia exitosa elimina la presencia anterior únicamente
    si todavía pertenece al backend de origen.

El balanceo por capacidad está fusionado en `main`:

- `e94a9fc feat: add capacity-aware backend load balancing (#37)`.

La resolución actual selecciona entre instancias elegibles del mismo
`BackendType`. Los candidatos jugables con jugadores conectados deben estar
autenticados, `HEALTHY` y frescos. `BackendLoadSelector` compara la carga
proporcional respecto de la capacidad configurada, excluye backends llenos o
por encima de capacidad, usa la preferencia como desempate y finalmente el
nombre del servidor como desempate determinista.

La selección de destinos fríos o de bootstrap está separada de la selección
de candidatos jugables activos. Un backend vacío puede ser elegible para un
intento de conexión mediante la ruta de bootstrap, pero `BOOTSTRAP_REQUIRED`
no afirma que el proceso remoto esté encendido. Una reserva bootstrap tampoco
inicia un proceso remoto.

Las transferencias pendientes reservan capacidad de forma correlacionada
antes de ejecutar `ConnectionRequest`. Esa reserva protege frente a
sobreasignación concurrente y se libera en éxito, rechazo, fallo, timeout,
desconexión o apagado mediante coincidencia exacta. Si una carrera de
capacidad devuelve `NO_CAPACITY`, el backend se excluye y se intenta otro
candidato. Las exclusiones iniciales se copian defensivamente y
`TransferTargetResolutionContractViolationException` protege contra un
resolver que devuelva un destino ya excluido.

El retry alternativo de destinos está fusionado en `main`:

- `9d0ea02 fix: retry alternate backend after transfer failure (#38)`.

`LobbyTransferService` y `TransferRequestMessageHandler` usan
`PlayerTransferRetryCoordinator` como coordinador compartido del ciclo:

```text
asignar → reservar bootstrap → conectar → limpiar → excluir → reintentar
```

Semántica confirmada:

- `SUCCESS`: resultado terminal exitoso; conserva la reserva bootstrap
  ganadora hasta el handshake normal;
- `FAILED`: limpia el intento exacto, excluye el backend fallido e intenta
  otro destino elegible;
- `REJECTED`: limpia el intento exacto, excluye el backend rechazado e intenta
  otro destino elegible;
- `TARGET_BUSY` durante bootstrap: no elimina la reserva ajena, excluye ese
  destino e intenta otro backend;
- `TIMED_OUT`: es terminal, limpia los recursos exactos y no intenta otro
  backend para evitar una segunda conexión mientras la operación subyacente
  de Velocity podría continuar;
- `REQUEST_ID_CONFLICT` y `ALREADY_RESERVED`: son terminales y no realizan
  retries entre destinos.

Durante los reintentos se conserva el mismo `requestId` lógico, las
exclusiones se acumulan, existe guard anti-loop si el resolver devuelve un
destino excluido, y la limpieza usa garantías exact-match para no eliminar
estado posterior. Los callbacks tardíos no emiten doble resultado ni eliminan
transferencias, capacidad o bootstrap de intentos nuevos. Cada jugador u
origen recibe un único mensaje o `TRANSFER_RESULT` terminal.

Reglas especiales confirmadas para el handoff Auth→Lobby:

- `AUTH` solo puede solicitar destino `LOBBY`;
- las transferencias hacia `AUTH` se rechazan;
- una solicitud procedente de `AUTH` no exige
  `PLAYER_SERVER_READY` previo en `auth-1`;
- la comprobación de presencia jugable se conserva para `LOBBY` y
  `SKYBLOCK`;
- TheosferaProxy valida y ejecuta la transferencia;
- el Lobby confirma la llegada final mediante `PLAYER_SERVER_READY`.

Resultados del protocolo:

- `SUCCESS`;
- `REJECTED`;
- `FAILED`;
- `TIMED_OUT`.

Protecciones actuales:

- solicitudes simultáneas para el mismo jugador son rechazadas;
- conflictos de `requestId` son rechazados;
- los dos índices del registro pendiente se actualizan atómicamente;
- `PendingPlayerTransferRegistry.removeIfMatches()` elimina ambos
  índices solo si la transferencia esperada coincide exactamente;
- un resultado tardío no altera una transferencia ya retirada ni otra
  transferencia que reutilice el mismo `requestId`;
- un evento `PLAYER_SERVER_READY` adelantado del destino no es
  eliminado por el callback de la transferencia;
- fallos síncronos y asíncronos de Velocity se convierten en resultados
  controlados;
- los detalles internos de excepciones no se exponen a los backends.

Comandos públicos de retorno al Lobby:

- `/hub` es el comando principal de Velocity;
- `/lobby` es alias del mismo comando y comportamiento;
- solo jugadores pueden ejecutarlos;
- el jugador debe poseer una sesión autenticada;
- ambos resuelven exclusivamente `BackendType.LOBBY`;
- pueden usar destinos `RESOLVED` y `BOOTSTRAP_REQUIRED` mediante el
  coordinador compartido;
- `BOOTSTRAP_REQUIRED` no se trata como prueba de disponibilidad;
- los comandos no inician deliberadamente un proceso remoto;
- pueden reintentar otro Lobby después de `FAILED`, `REJECTED` o
  `TARGET_BUSY` durante bootstrap;
- `TIMED_OUT` es terminal y no intenta otro backend;
- no envían al jugador hacia Auth;
- si el jugador ya está conectado al Lobby resuelto, no se crea una
  conexión;
- se reutiliza `PendingPlayerTransferRegistry` para impedir operaciones
  simultáneas;
- se utiliza `PlayerTransferExecutor` y su timeout;
- no se modifica presencia de forma anticipada;
- `PLAYER_SERVER_READY` continúa siendo la confirmación autoritativa de
  llegada;
- no existen bloqueos síncronos para estos comandos;
- no se implementaron selección de modalidades ni mantenimiento.

Flujo confirmado de comando:

```text
skyblock-1
  → /hub o /lobby
  → validación de sesión
  → resolución LOBBY
  → registro pendiente
  → ConnectionRequest
  → lobby-1
  → limpieza correlacionada
  → PLAYER_SERVER_READY
```

Flujo fail-closed confirmado:

```text
skyblock-1
  → /hub con lobby-1 apagado
  → ConnectionRequest fallido
  → mensaje seguro
  → limpieza del pending
  → jugador permanece en skyblock-1
```

Flujo multiinstancia validado posteriormente:

```text
skyblock-1
  → /hub con lobby-1 apagado y lobby-2 activo
  → coordinación de destino LOBBY con retry alternativo
  → lobby-2
  → PLAYER_SERVER_READY
```

El flujo histórico con `lobby-1` se conserva como evidencia previa; el estado
actual ya incluye balanceo por capacidad y retry alternativo entre instancias.

### Failover ante kicks de backends

El failover provocado por `KickedFromServerEvent` está implementado con
política fail-closed.

Componentes principales:

- `BackendKickFailoverListener`;
- `BackendKickFailoverService`;
- `BackendKickFailoverResolution`;
- `BackendKickFailoverResolutionStatus`.

El contrato de resolución es explícito:

- `IGNORED`: el evento no pertenece al flujo de failover controlado;
- `REDIRECT`: existe un destino jugable seguro y Velocity puede redirigir;
- `DISCONNECT`: no existe un destino seguro y el jugador debe ser
  desconectado conservando la razón original del kick.

Reglas de seguridad confirmadas:

- el failover automático se aplica únicamente a jugadores autenticados;
- Auth nunca es un destino de recuperación para un jugador autenticado;
- el backend que produjo el kick queda excluido;
- el servidor actual del jugador no puede resolverse como destino;
- solo se aceptan backends jugables autorizados, utilizables y actualmente
  activos;
- el destino debe resolverse con
  `TransferTargetResolutionStatus.RESOLVED`;
- `BOOTSTRAP_REQUIRED` no es un destino válido para failover;
- el failover no inicia ni reserva un backend apagado;
- una resolución ambigua o la ausencia de destino seguro termina en
  desconexión explícita;
- no se permite que un resultado vacío sea interpretado silenciosamente
  como una redirección válida;
- se evitan bucles cuando el Lobby resuelto ya coincide con el servidor
  actual;
- si la redirección segura no puede realizarse, se conserva la razón
  original proporcionada por el backend.

Este failover protege fallos de conexión o kicks de backends existentes.
Si el Lobby está apagado y no existe otro destino jugable activo, el
jugador es desconectado de forma controlada. Velocity no recibe una
redirección hacia el Lobby inactivo ni puede improvisar un retorno hacia
Auth.
El failover reutiliza `TransferTargetResolver`, por lo que los destinos
resueltos como jugables participan de la selección por capacidad, preferencia
y nombre, pero la política de kicks continúa aceptando únicamente destinos
`RESOLVED` seguros. Un backend autenticado pero `STALE` no puede convertirse
en destino normal de recuperación, y `BOOTSTRAP_REQUIRED` continúa
rechazándose para failover.

## 15. Limpieza por desconexión

`PlayerDisconnectListener` escucha `DisconnectEvent`.

Flujo vigente de limpieza local inmediata:

1. obtiene el `Player` y su UUID desde el evento;
2. elimina una transferencia pendiente mediante
   `PendingPlayerTransferRegistry.removeByPlayer(...)`;
3. si existía transferencia pendiente, limpia la reserva de capacidad asociada
   mediante `BackendCapacityReservationRegistry.removeByRequest(...)` usando el
   `requestId` de esa transferencia;
4. elimina la presencia local mediante `PlayerServerPresenceRegistry.remove(...)`;
5. entra en una sección sincronizada sobre `PlayerSessionLeaseBindingRegistry`;
6. usa `find(player)` para obtener únicamente el binding exacto de ese objeto
   `Player`;
7. ejecuta `removeForDisconnect(player)`;
8. revoca la autenticación local solo con
   `AuthenticatedPlayerSessionRegistry.removeIfMatches(ownedLease.session())`
   cuando el binding exacto encontrado pertenecía a esa sesión.

El disconnect ya no debe describirse como una simple eliminación UUID-level de
sesión. Un `Player` OLD no debe borrar la autenticación de un `Player` NEW con
el mismo UUID, y una desconexión OLD tardía no debe revocar una sesión NEW ya
vinculada.

Liberación asíncrona posterior:

- si `removeForDisconnect(player)` devuelve un lease liberable, el listener
  llama a `PlayerSessionReleaseService.releaseIfUnbound(...)`;
- esa liberación es exact-match y asíncrona respecto de la limpieza local;
- si el lease ya no coincide con la propiedad vigente, no libera el lease
  actual.

El listener:

- se registra durante `ProxyInitializeEvent`;
- se desregistra durante `ProxyShutdownEvent`;
- evita mantener transferencias, reservas, presencias o sesiones locales
  fantasma sin debilitar el ownership exacto del lease coordinado.

Durante el apagado también se limpian:

1. reservas temporales de bootstrap;
2. failovers pendientes;
3. reservas temporales de capacidad;
4. transferencias pendientes;
5. presencias;
6. `PlayerSessionReleaseService`;
7. bindings de leases de sesión;
8. sesiones autenticadas;
9. comprobaciones de salud pendientes;
10. estado de salud;
11. identidades de backends.

## 16. Pruebas confirmadas

Existen pruebas para:

- registro y ciclo del canal;
- recepción segura;
- decodificación registrada;
- dispatcher y contexto;
- sender;
- heartbeat;
- emisión periódica de health checks;
- correlación y validación de `PONG`;
- transición y frescura del estado de salud de backends;
- timeout y limpieza del health checking;
- política de backends;
- carga de `backends.properties`;
- capacidad y preferencia en la política de backends;
- registro de identidades;
- autorización por rol;
- handshake;
- sesiones autenticadas;
- presencia de jugadores;
- handlers de ciclo de jugador;
- limpieza por desconexión;
- registro de transferencias pendientes;
- resolución segura del backend destino;
- selección por carga proporcional, preferencia y nombre;
- exclusión de backends llenos o por encima de capacidad;
- reservas de capacidad durante transferencias pendientes;
- retry ante carreras de capacidad;
- ejecución asíncrona, rechazo, fallo y timeout;
- correlación de `TRANSFER_RESULT`;
- validaciones del handler de transferencia;
- conservación segura de presencia durante carreras;
- flujo integral de transferencia;
- pruebas negativas Auth→Lobby;
- comandos públicos `/hub` y `/lobby`;
- retry alternativo de `/hub`, `/lobby` y `TRANSFER_REQUEST`;
- `TARGET_BUSY` durante bootstrap con fallback alternativo;
- `REQUEST_ID_CONFLICT` y `ALREADY_RESERVED` terminales;
- `TIMED_OUT` terminal sin fallback;
- guard anti-loop ante destinos excluidos;
- rechazo de fuente no jugador;
- jugador no autenticado;
- jugador sin conexión actual;
- Lobby `NOT_CONFIGURED`;
- Lobby `NOT_AUTHENTICATED`;
- Lobby `BOOTSTRAP_REQUIRED`;
- jugador ya conectado al Lobby;
- transferencia pendiente o jugador ocupado;
- resultados `SUCCESS`, `REJECTED`, `FAILED` y `TIMED_OUT`;
- finalización excepcional;
- limpieza del pending;
- callback tardío con el mismo `requestId`;
- registro y desregistro de `/hub` y `/lobby`;
- resolución del failover ante kicks de backends;
- exclusión de Auth, del backend fallido y del servidor actual;
- redirección únicamente hacia destinos jugables seguros;
- desconexión fail-closed cuando no existe un destino seguro;
- conservación de la razón original del kick;
- contratos de coordinación local de sesión;
- adquisición asíncrona de sesión autenticada;
- binding exacto de leases a conexión, `requestId`, `attemptId` y sesión;
- replay pendiente y replay terminal;
- conflictos por reutilización de `requestId`;
- fencing de intentos y callbacks tardíos;
- generaciones OLD/NEW de conexión;
- carreras de desconexión OLD/NEW;
- timeouts de adquisición;
- release compartido de leases;
- `RELEASE_PENDING` y retry posterior con `attemptId` nuevo;
- timeouts propios de release poseído;
- release exact-match y rechazo de releases stale;
- quarantines exactas por operación;
- retención acotada de quarantines;
- capacidad acotada de estructuras de sesión;
- cierre fail-closed por capacidad;
- `PlayerSessionLeaseBindingResult.CAPACITY_EXHAUSTED`;
- cleanup exacto de leases recién adquiridos pero rechazados;
- precedencia semántica `STALE` generation > `CAPACITY_EXHAUSTED`;
- carrera CURRENT→SUPERSEDED antes de terminalizar;
- `clear()` de lifecycle;
- scheduler throw/null;
- cancelación excepcional de scheduler;
- handles de scheduler retornados tarde;
- identidad referencial de `CompletionStage` externa donde participa en
  ownership;
- lifecycle del plugin;
- eliminación atómica correlacionada.

Flujos integrales confirmados:

```text
BACKEND_HELLO → BACKEND_HELLO_ACK
PING → PONG
auth-1 → PLAYER_AUTHENTICATED
lobby-1 → PLAYER_SERVER_READY
lobby-1 → TRANSFER_REQUEST → skyblock-1 → TRANSFER_RESULT
auth-1 → TRANSFER_REQUEST → lobby-1 → PLAYER_SERVER_READY
skyblock-1 → /hub o /lobby → lobby-1 → PLAYER_SERVER_READY
DisconnectEvent → eliminación de presencia y sesión
```

El flujo integral de jugador atraviesa:

- codec;
- listener;
- autorización;
- dispatcher;
- handlers;
- registros;
- limpieza por desconexión.

Última validación local confirmada:

```powershell
git diff --check
.\gradlew.bat clean test --no-daemon
.\gradlew.bat clean build --no-daemon
```

Resultado:

```text
BUILD SUCCESSFUL
```

La rama `feature/backend-health-checking` alcanzó 318 pruebas automatizadas
exitosas en su validación histórica confirmada.

Validación automatizada histórica del checkpoint de observabilidad operacional:

```powershell
.\gradlew.bat clean test --no-daemon
.\gradlew.bat clean build --no-daemon
```

Resultado:

```text
BUILD SUCCESSFUL
416 tests, 0 failures, 0 errors, 0 skipped
```

Ese conteo fue calculado desde los XML de `build/test-results/test` en el
checkpoint de observabilidad operacional.

Validación confirmada para el PR `#45`:

- GitHub Actions Build `#101`: `SUCCESS` sobre
  `ba0ff6eb0dd1e3f5c268faed702313ab961028be`;
- clean build local final: `BUILD SUCCESSFUL`;
- `git diff --cached --check` previo al commit final: limpio;
- fresh review remoto final: sin findings `P0/P1/P2`;
- todos los review threads quedaron resueltos.

Para el checkpoint posterior a `#45` no se registra un total nuevo de tests en
este documento. El conteo de `416 tests` queda como evidencia histórica del
checkpoint anterior, no como conteo total actualizado del PR `#45`.

## 17. Prueba runtime confirmada

TheosferaProxy fue instalado en Velocity `3.5.0-SNAPSHOT` y quedaron
validados el circuito runtime real Auth→Lobby y los comandos públicos
`/hub` y `/lobby`.

Confirmado:

- carga correcta del plugin;
- carga de tres backends autorizados en la topología inicial;
- registro de `theosfera:network`;
- inicio correcto;
- apagado correcto;
- desregistro del canal;
- ausencia de errores del plugin;
- registro de sesión autenticada desde `auth-1`;
- ACK correlacionado `PLAYER_AUTHENTICATED_ACK`;
- solicitud segura de transferencia `LOBBY`;
- conexión del jugador a `lobby-1`;
- desconexión normal de `auth-1` durante el cambio de backend;
- llegada confirmada por `PLAYER_SERVER_READY` desde `lobby-1`;
- comandos `/hub` y `/lobby` registrados y operativos;
- retorno desde `skyblock-1` hacia `lobby-1`;
- fallo cerrado cuando `lobby-1` no está disponible;
- limpieza del pending tras fallo de conexión;
- ausencia de la advertencia falsa antigua de transferencia fallida.

Artefacto final validado para el checkpoint actual:

```text
JAR: TheosferaProxy-0.1.0-SNAPSHOT.jar
Commit main: 9d0ea02
Tamaño: 504351 bytes
SHA-256: 6CCA10788FEA205E4621FF476BC37701C3B92E3514887B09B9A45CB8749EC29E
Ruta instalada: C:\Theosfera\Network\dev\proxy\plugins\TheosferaProxy-0.1.0-SNAPSHOT.jar
Backup previo: C:\Theosfera\Network\dev\_runtime-jar-backup-20260725-032722
```

El hash del JAR generado coincidió exactamente con el instalado y con las
copias conservadas como evidencia.

JAR desplegado de TheosferaProxy en el checkpoint histórico anterior:

```text
SHA256: 2E1F2C211DD3F703B251872126B8F0D8857DDC95D3D788237CEBE9CDD1F622FA
```

Circuito validado:

```text
Jugador
  → Velocity
  → auth-1
  → nLogin AuthenticateEvent
  → TheosferaAuth
  → TheosferaCore
  → PLAYER_AUTHENTICATED
  → TheosferaProxy
  → PLAYER_AUTHENTICATED_ACK
  → solicitud segura de transferencia LOBBY
  → lobby-1
  → PLAYER_SERVER_READY
```

Evidencia observada:

```text
[nlogin]: The user HarriOcho has successfully logged in.
[theosferaproxy]: Sesión autenticada registrada para HarriOcho desde auth-1.
[TheosferaAuth]: TheosferaProxy confirmó la sesión autenticada.
[TheosferaAuth]: La solicitud segura de transferencia al Lobby fue entregada a TheosferaCore.
[server connection] HarriOcho -> lobby-1 has connected
[server connection] HarriOcho -> auth-1 has disconnected
[theosferaproxy]: Jugador ... listo en lobby-1.
```

Matriz runtime confirmada para `/hub` y `/lobby`:

1. Auth→Lobby continuó funcionando después del despliegue.
2. `/hub` ejecutado estando ya en `lobby-1` respondió:
   `Ya estás en el Lobby.`
   No creó reconexión ni errores.
3. `/lobby` estando ya en `lobby-1` produjo el mismo comportamiento.
4. Desde `skyblock-1`, `/hub` conectó correctamente hacia `lobby-1`.
5. Desde `skyblock-1`, `/lobby` conectó correctamente hacia `lobby-1`.
6. Ambos mostraron:
   `Te enviamos al Lobby.`
7. Proxy confirmó en ambos casos:
   - conexión hacia `lobby-1`;
   - desconexión normal de `skyblock-1`;
   - `PLAYER_SERVER_READY` desde `lobby-1`.
8. Con `lobby-1` apagado, `/hub` falló cerrado y mostró:
   `No se pudo enviarte al Lobby.`
9. Dos intentos consecutivos con Lobby apagado produjeron el mismo fallo
   seguro:
   - el jugador permaneció en `skyblock-1`;
   - no apareció `Ya tienes una transferencia pendiente.`;
   - se confirmó la limpieza del pending tras cada fallo.
10. Después de reiniciar `lobby-1`, `/hub` volvió a funcionar sin
    reiniciar Velocity:
    - conexión desde `skyblock-1` hacia `lobby-1`;
    - handshake aceptado;
    - `PLAYER_SERVER_READY`;
    - mensaje de éxito.
11. nLogin impide usar comandos antes de autenticarse mediante su
    interfaz obligatoria.
12. La protección interna equivalente de TheosferaProxy está cubierta
    por pruebas automatizadas.
13. No hubo errores de TheosferaProxy ni advertencias falsas de
    transferencia fallida.

Matriz runtime confirmada para retry multiinstancia Auth→Lobby:

1. `lobby-1` estuvo apagado y `lobby-2` activo.
2. El jugador HarriOcho se conectó mediante Velocity.
3. `auth-1` autenticó al jugador.
4. TheosferaProxy registró la sesión.
5. Se reservó bootstrap inicialmente para `lobby-1`.
6. La conexión a `lobby-1` no prosperó.
7. Se reservó bootstrap para `lobby-2`.
8. Se conservó el mismo `requestId`:
   `f892913d-04d4-447c-98a1-33079dd2bd15`.
9. El jugador conectó a `lobby-2`.
10. `auth-1` se desconectó normalmente.
11. `lobby-2` completó handshake.
12. TheosferaProxy recibió `PLAYER_SERVER_READY`.
13. El jugador quedó listo en `lobby-2`.

Evidencia preservada en:

```text
C:\Theosfera\Network\dev\_runtime-failover-retry-success-20260725-033324
```

Incluye:

- `auth-latest.log`;
- `lobby-2-latest.log`;
- `proxy-latest.log`;
- `skyblock-1-latest.log`;
- `listening-ports.txt`;
- `jar-sha256.txt`;
- `TheosferaProxy-0.1.0-SNAPSHOT.jar`.

Matriz runtime confirmada para `/hub` con retry hacia `lobby-2`:

1. El jugador fue enviado manualmente desde `lobby-2` hacia `skyblock-1`.
2. TheosferaProxy recibió `PLAYER_SERVER_READY` desde `skyblock-1`.
3. `lobby-1` continuó apagado y `lobby-2` continuó activo.
4. El jugador ejecutó `/hub`.
5. El jugador conectó correctamente a `lobby-2`.
6. `skyblock-1` se desconectó normalmente.
7. TheosferaProxy recibió `PLAYER_SERVER_READY` desde `lobby-2`.
8. El jugador recibió `Te enviamos al Lobby.`

Evidencia preservada en:

```text
C:\Theosfera\Network\dev\_runtime-hub-retry-success-20260725-033635
```

Incluye:

- `proxy-latest.log`;
- `lobby-2-latest.log`;
- `skyblock-1-latest.log`;
- `listening-ports.txt`;
- `jar-sha256.txt`;
- `validation-summary.txt`;
- `TheosferaProxy-0.1.0-SNAPSHOT.jar`.

Los logs de `/hub` muestran la llegada efectiva a `lobby-2`.
`listening-ports.txt` confirma que `lobby-1` estaba apagado en la topología de
puertos. No se afirma una línea explícita de log del fallo a `lobby-1` si esa
línea no existe; el resultado valida el fallback alternativo dentro del
escenario configurado por observación directa de la llegada final y por
inferencia basada en la configuración y el código.

Semántica confirmada:

- `PlayerTransferRequestStatus.SUBMITTED` significa que
  TheosferaAuth entregó de forma segura la solicitud a TheosferaCore
  para publicarla hacia el Proxy;
- `SUBMITTED` no significa que el jugador haya llegado al destino;
- TheosferaAuth no espera el resultado final de la transferencia;
- TheosferaProxy es la autoridad que valida y ejecuta la transferencia;
- el backend Lobby confirma la llegada mediante `PLAYER_SERVER_READY`;
- el cambio de backend provoca `PlayerQuitEvent` en Auth, por lo que
  Auth no es el dueño adecuado de una espera local de `TRANSFER_RESULT`;
- `optionalCompletion()` permanece únicamente en
  `PlayerAuthenticationRequest` para esperar el
  `PLAYER_AUTHENTICATED_ACK` correlacionado;
- no debe restaurarse en TheosferaAuth una espera local de
  `TRANSFER_RESULT` para este handoff.

Estado de identidad, salud y frescura:

- `BackendIdentityRegistry` conserva las identidades autenticadas en memoria
  durante el proceso actual de Velocity;
- una identidad registrada históricamente no constituye por sí sola una prueba
  de salud actual;
- el Proxy emite comprobaciones periódicas y correlacionadas `PING`→`PONG`;
- los `PONG` válidos actualizan el estado fresco del backend;
- los timeouts y las respuestas inválidas no mantienen artificialmente sano al
  destino;
- `TransferTargetResolver` exige salud `HEALTHY` y frescura vigente para
  resolver como jugable un backend autenticado con jugadores conectados;
- un backend cuya salud se vuelve `STALE` deja de resolverse como destino
  jugable normal;
- un destino vacío elegible puede resolverse como `BOOTSTRAP_REQUIRED`, sin
  ser confundido con un backend actualmente sano;
- el failover ante kicks únicamente acepta destinos `RESOLVED` seguros;
- `/hub` y `/lobby` pueden usar la ruta de bootstrap coordinada para intentar
  conexión a un Lobby alternativo, pero no inician procesos remotos ni tratan
  `BOOTSTRAP_REQUIRED` como prueba de disponibilidad;
- las solicitudes explícitas de transferencia pueden utilizar la resolución
  `BOOTSTRAP_REQUIRED` y registrar temporalmente una reserva;
- una reserva de bootstrap no enciende por sí sola el proceso remoto: el Proxy
  intenta la conexión mediante `PlayerTransferExecutor`;
- si el backend continúa apagado, la conexión falla de forma controlada, se
  eliminan la transferencia pendiente y la reserva, y Core recibe
  `TRANSFER_RESULT` con estado `FAILED`;
- la presencia del backend de origen no se elimina ante una transferencia
  fallida;
- después de encender `skyblock-1`, una nueva solicitud por la ruta de
  bootstrap consiguió conectar al jugador y fue confirmada posteriormente por
  `PLAYER_SERVER_READY`;
- durante esa recuperación todavía se observó `Bootstrap reservado`, por lo
  que no se afirma una transición autónoma `STALE`→`FRESH` observada mediante
  un `PONG` previo a la transferencia;
- el comportamiento runtime fue seguro, recuperable y coherente con la
  política fail-closed.

Matriz runtime histórica confirmada para salud, frescura y bootstrap:

1. Con `skyblock-1` inicialmente operativo, el backend participó normalmente
   en el flujo de red.
2. Después de apagarlo y esperar la pérdida de frescura, dejó de resolverse
   como destino jugable normal.
3. Una transferencia explícita hacia Skyblock se resolvió mediante
   `BOOTSTRAP_REQUIRED`.
4. El Proxy registró una reserva temporal asociada al `requestId`.
5. Como `skyblock-1` continuaba apagado, `ConnectionRequest` falló de forma
   controlada.
6. Core recibió primero la confirmación `SUBMITTED` y después el resultado
   final `FAILED`; ambos mensajes representan fases distintas de la misma
   solicitud.
7. `completeTransfer()` retiró la transferencia pendiente mediante
   `removeIfMatches()`.
8. El resultado distinto de `SUCCESS` eliminó la reserva mediante
   `bootstrapRegistry.removeByRequest()`.
9. El jugador permaneció seguro en `lobby-1` y su presencia de origen no fue
   eliminada.
10. Después de encender `skyblock-1`, una nueva solicitud volvió a registrar
    el bootstrap y consiguió conectar al jugador.
11. Velocity confirmó la conexión a `skyblock-1` y la desconexión normal de
    `lobby-1`.
12. TheosferaProxy recibió posteriormente `PLAYER_SERVER_READY` desde
    `skyblock-1`.

Topología histórica validada para los checkpoints Auth→Lobby y health checking:

- Proxy: `127.0.0.1:25565`;
- Lobby-1: `127.0.0.1:25566`;
- Auth-1: `127.0.0.1:25568`;
- nLogin instalado en Proxy y Auth, no en Lobby;
- LuckPerms para permisos de nLogin en Proxy;
- TheosferaCore instalado en Auth, Lobby y Skyblock;
- TheosferaAuth instalado solo en Auth;
- TheosferaProxy instalado solo en Velocity;
- backends enlazados únicamente a `127.0.0.1`.

Último JAR desplegado de TheosferaAuth:

```text
SHA256: B052F03C33F741EECC39B27756B22787E79EB39DB08473CA4E51859C6A349475
```

Las advertencias sobre acceso nativo, mutación reflectiva y forwarding
pertenecen al entorno o a Velocity y no impidieron la prueba.

## 18. Git y ramas fusionadas

Bloques principales fusionados en TheosferaProxy:

- Foundation;
- Protocol Dependency;
- Runtime Packaging;
- Channel Registration;
- Message Receiver;
- Message Decoding;
- Message Dispatch;
- Message Sender;
- Heartbeat Handler;
- Backend Handshake;
- Player Sessions;
- Player Transfers;
- Authenticated Lobby Transfer Requests;
- Auth Transfers Without Playable Presence;
- Negative Auth Lobby Transfer Cases;
- Secure Lobby Commands;
- Backend Carrier Freshness Foundation;
- Backend Kick Failover;
- Failover Target Exclusions;
- Current Server Failover Protection;
- Fail-Closed Backend Kick Failover;
- Backend Health Checking;
- Capacity-Aware Backend Load Balancing;
- Alternate Backend Transfer Retry;
- Proxy Operational Observability;
- Proxy Status Command Formatting;
- Local Player Session Coordination Contracts;
- Runtime Player Session Coordination;
- Stable Proxy Instance Identity;
- Redis Player Session Coordinator.

Bloques de contrato fusionados en TheosferaProtocol:

- Foundation;
- Handshake and Heartbeat Payloads;
- Player Lifecycle Payloads;
- Transfer Payloads;
- Message Registry;
- Registered Message Decoding;
- Contract Checkpoint.

Commits relevantes ya integrados para el circuito Auth→Lobby:

- TheosferaProtocol:
  `253d22e feat: add player authentication acknowledgement (#12)`;
- TheosferaCore:
  `040c7cd feat: expose secure backend transfer publisher (#13)`;
- TheosferaProxy:
  `967785f feat: allow authenticated lobby transfer requests (#19)`;
- TheosferaProxy:
  `943f3de fix: allow auth transfers without playable presence (#20)`;
- TheosferaProxy:
  `fc53b2e test: cover negative auth lobby transfer cases (#22)`;
- TheosferaProxy:
  `d2af094 feat: add secure lobby commands (#23)`;
- TheosferaProxy:
  `f9b58f4 fix: make backend kick failover fail closed (#30)`;
- TheosferaProxy:
  `dc68788 fix: handle backend kicks from established connections (#32)`;
- TheosferaProxy:
  `c3fc274 fix: support failover to cold lobby targets (#33)`;
- TheosferaProxy:
  `d862c78 fix: restrict failover to live targets (#34)`;
- TheosferaProxy:
  `fa2dccc feat: integrate backend health checking (#36)`;
- TheosferaProxy:
  `e94a9fc feat: add capacity-aware backend load balancing (#37)`;
- TheosferaProxy:
  `9d0ea02 fix: retry alternate backend after transfer failure (#38)`;
- TheosferaProxy:
  `6efacee feat: add proxy operational status observability (#40)`;
- TheosferaProxy:
  `e07eed1 feat: improve proxy status command formatting (#41)`;
- TheosferaProxy:
  `7e5bd7a feat: add local player session coordination contracts (#44)`;
- TheosferaProxy:
  `87ea7d4 feat: integrate player session coordination at runtime (#45)`;
- TheosferaProxy:
  `d4c06d9 feat: configure stable proxy instance identity (#47)`;
- TheosferaProxy:
  `e2fe4f1 feat: add Redis player session coordinator (#48)`;
- TheosferaAuth:
  `b6ae696 Merge pull request #4 from HarriOcho/fix/auth-transfer-handoff-lifecycle`.

Los cambios importantes se realizan mediante ramas y Pull Requests con
squash merge.

Estado Git del checkpoint histórico de failover:

- `main` sincronizada con `origin/main` en `d862c78`;
- PR `#34` fusionado en `main`;
- árbol de archivos rastreados limpio antes de crear la rama documental;
- los cuatro archivos auxiliares de diagnóstico permanecen sin rastrear y
  no forman parte del proyecto ni del checkpoint;
- ramas locales y referencias remotas obsoletas eliminadas;
- únicamente `main` permanecía como rama local antes del checkpoint;
- rama actual del checkpoint:
  `docs/failover-runtime-checkpoint`.

Estado Git del checkpoint histórico de balanceo y retry:

- `main` sincronizada con `origin/main` en `9d0ea02`;
- PR `#38` fusionado en `main`;
- rama documental:
  `docs/backend-load-balancing-runtime-checkpoint`;
- árbol limpio antes de editar `PROJECT_STATE.md`.

Estado Git del checkpoint actual de observabilidad operacional:

- `main` sincronizada con `origin/main` en `e07eed1`;
- PR `#40` fusionado en `main`;
- PR `#41` fusionado en `main`;
- ramas locales de funcionalidad eliminadas después de los squash merge;
- referencias remotas obsoletas eliminadas mediante `fetch --prune`;
- rama documental actual:
  `docs/proxy-operational-observability-checkpoint`;
- árbol limpio antes de editar `PROJECT_STATE.md`;
- validación runtime del comando administrativo completada.

Estado Git del checkpoint actual de coordinación runtime de sesiones:

- `main` sincronizada con `origin/main` en `87ea7d4`;
- base confirmada: `87ea7d4484cac2b31738484e6ab4e403ecdbdbc4`;
- PR `#45` fusionado en `main` mediante squash;
- último HEAD de `feature/session-coordination-runtime` antes del squash:
  `ba0ff6eb0dd1e3f5c268faed702313ab961028be`;
- working tree limpio antes de crear la rama documental;
- rama documental actual:
  `docs/session-coordination-runtime-checkpoint`.

Estado Git del checkpoint actual de Redis Player Session Coordinator:

- `main` sincronizada con `origin/main` en `e2fe4f1`;
- base confirmada: `main` @ `e2fe4f1`;
- PR `#47` fusionado en `main` mediante squash;
- PR `#48` fusionado en `main` mediante squash;
- working tree limpio antes de crear la rama documental;
- rama documental actual:
  `docs/redis-player-session-coordinator-checkpoint`.

Estado posterior de desarrollo histórico del checkpoint de health checking:

- rama activa: `feature/backend-health-checking`;
- último avance confirmado de la rama: `2f9ea16`;
- health checking periódico y correlacionado implementado;
- estado explícito de salud y frescura integrado en
  `TransferTargetResolver`;
- 318 pruebas automatizadas exitosas;
- build local exitoso mediante `.\gradlew.bat build --no-daemon`;
- validación runtime completada con pérdida de frescura, exclusión segura,
  fallo controlado y recuperación mediante el flujo de bootstrap;
- siguiente incremento entonces pendiente: selección entre múltiples
  instancias `HEALTHY` mediante métricas de carga. Ese incremento ya fue
  implementado y fusionado en `e94a9fc`.

El health checking, su integración con la resolución de destinos y el failover
fail-closed están cubiertos por pruebas automatizadas, build local exitoso y
validación runtime específica.

Evidencia runtime confirmada para salud, frescura y bootstrap:

- `skyblock-1` dejó de resolverse como destino jugable normal después de perder
  frescura;
- una transferencia explícita se resolvió mediante `BOOTSTRAP_REQUIRED`;
- con el backend todavía apagado, la conexión falló de forma controlada;
- Core recibió `SUBMITTED` y posteriormente `FAILED` para la misma solicitud;
- la transferencia pendiente y su reserva temporal fueron eliminadas;
- el jugador permaneció seguro en `lobby-1` y su presencia de origen se
  conservó;
- después de encender `skyblock-1`, una nueva solicitud mediante bootstrap
  consiguió conectar al jugador;
- TheosferaProxy recibió posteriormente `PLAYER_SERVER_READY`;
- no se afirma haber observado directamente una transición autónoma
  `STALE`→`FRESH` mediante un `PONG` previo a esa transferencia.

Artefacto validado en el checkpoint histórico de health checking:

- JAR: `TheosferaProxy-0.1.0-SNAPSHOT.jar`;
- tamaño: `474108` bytes;
- SHA-256:
  `C2AE302A6F3420BFD7CA76E02EC8D031F6873DA8E66705FF139F1F76283C1D16`;
- el hash del artefacto generado en `build/libs` coincide exactamente con el
  JAR instalado y validado en
  `C:\Theosfera\Network\dev\proxy\plugins`;
- la copia existente en `C:\Theosfera\Plugins\VelocityTest\plugins` corresponde
  a un artefacto anterior y no formó parte de esta validación runtime.

Esta evidencia es independiente de las pruebas runtime ya confirmadas de
Auth→Lobby, de los comandos `/hub` y `/lobby`, y del failover con el Lobby
apagado.

## 19. Estado transitorio y persistencia

Actualmente son únicamente memoria local del proceso:

- identidades de backends;
- sesiones autenticadas;
- leases locales de sesión autenticada;
- bindings locales de lease por conexión;
- replays temporales de solicitudes de autenticación;
- quarantines y fencing floors locales de release;
- presencia de jugadores;
- transferencias pendientes;
- estado de salud y frescura de backends;
- comprobaciones `PING` pendientes;
- reservas temporales de bootstrap;
- reservas temporales de capacidad.

Las identidades registradas indican que un backend completó correctamente el
handshake durante el proceso actual, pero no constituyen por sí solas una
prueba de disponibilidad presente. El health checking periódico mantiene por
separado el estado de salud y su frescura. `TransferTargetResolver` consume
esa información y exige un destino `HEALTHY` y fresco cuando debe resolver
como jugable un backend autenticado con jugadores conectados.

Un backend que pierde frescura deja de ser un destino jugable normal. Si se
trata de un destino vacío elegible, una transferencia explícita todavía puede
obtener `BOOTSTRAP_REQUIRED`; esa resolución no afirma que el proceso remoto
esté encendido. La conexión final continúa bajo `ConnectionRequest`, que falla
cerrado, y cualquier transferencia o reserva fallida se limpia de forma
correlacionada.

Limitaciones honestas del checkpoint actual:

- la selección proporcional está cubierta por pruebas automatizadas;
- no fue validada directamente en runtime con tres jugadores simultáneos, uno
  manteniendo actividad en cada Lobby y un tercero como solicitante;
- con un único jugador no puede demostrarse experimentalmente la distribución
  proporcional entre dos instancias activas;
- `TIMED_OUT` terminal está cubierto por pruebas automatizadas, pero no fue
  provocado deliberadamente en runtime;
- el estado de salud, reservas, sesiones, leases, bindings, replays,
  quarantines, presencia y transferencias sigue siendo local al proceso de
  Proxy;
- existe un adaptador Redis distribuido para sesiones autenticadas, pero no
  está conectado al runtime;
- no existe coordinación runtime entre múltiples proxies;
- `LocalPlayerSessionCoordinator` es el adaptador runtime actual;
- `RedisPlayerSessionCoordinator` existe y está probado aisladamente;
- `ProxyInstanceIdentity` ya usa `proxyName` estable configurado e
  `incarnationId` efímero por arranque;
- el inventario observado inicialmente en `lobby-2` se debía a que `lobby-2`
  fue clonado desde `lobby-1`; no constituye sincronización cross-server y no
  debe presentarse como evidencia del Proxy.

Todavía no existen:

- base de datos;
- configuración/lifecycle Redis del plugin;
- activación de Redis como autoridad runtime;
- recuperación/HA Redis validada;
- persistencia/monotonicidad del contador de fencing ante restart/failover
  Redis;
- `ProxyMembershipCoordinator` distribuido;
- renovación runtime de leases Redis;
- wiring de `DISTRIBUTED_REQUIRED`;
- coordinación Redis para presencia global, transferencias, capacidad o
  bootstrap;
- observabilidad runtime Redis;
- replicación runtime entre múltiples proxies;
- perfiles persistentes;
- amigos;
- parties;
- escuadrones;
- invitaciones;
- permisos;
- localización propia.

La base de datos será la fuente permanente.

Redis existe ya como adaptador probado para `PlayerSessionCoordinator`, pero
todavía no coordina estado temporal ni eventos en runtime.

Un fallo de Redis no debe causar pérdida de perfiles o progreso.

## 20. Restricciones arquitectónicas

- El proxy valida operaciones globales.
- Auth es un estado restringido.
- No permitir acciones sociales antes de autenticar.
- No confiar en nombres o roles declarados sin validar su origen.
- No aceptar presencia para jugadores no autenticados.
- No duplicar clases de TheosferaProtocol.
- No introducir dependencias de Paper o Bukkit en el proxy.
- No implementar lógica específica de modalidad en el proxy.
- La selección de modalidades pertenece a TheosferaLobby, no a
  TheosferaProxy.
- Los contratos Core–Proxy deben permanecer versionados.
- Seguridad e integridad tienen prioridad sobre estética.
- Una identidad autenticada no debe confundirse con salud actual.
- La resolución jugable de un backend con jugadores conectados debe exigir
  salud `HEALTHY` y frescura vigente.
- `BOOTSTRAP_REQUIRED` no debe tratarse como disponibilidad actual.
- El failover ante kicks debe seguir aceptando únicamente destinos `RESOLVED`
  seguros.
- La selección por carga, capacidad, preferencia y retry alternativo no deben
  debilitar el comportamiento fail-closed.

## 21. Punto exacto de reanudación

La infraestructura Core–Proxy básica está operativa:

```text
Backend
  → Plugin Messaging
  → ProtocolMessageListener
  → Registered Decoding
  → BackendMessageAuthorizer
  → ProtocolMessageDispatcher
  → ProtocolMessageHandler
```

El handshake, la autenticación, la presencia, la desconexión y la coordinación
segura de transferencias están implementados. También están implementados el
health checking periódico y correlacionado, la política explícita de frescura,
su integración con `TransferTargetResolver`, el balanceo por capacidad, el
retry alternativo de destinos, el failover fail-closed ante kicks de backends
para jugadores autenticados y la observabilidad operacional administrativa.
La autenticación de jugadores ya no registra directamente la sesión y responde:
primero adquiere un lease mediante `PlayerSessionCoordinator` y luego vincula
ese lease a la conexión exacta.
La identidad estable del proxy está configurada mediante `proxy-name` y el
adaptador `RedisPlayerSessionCoordinator` existe como implementación
distribuida probada, aunque el runtime sigue en modo `LOCAL`.

La política fue validada en runtime con pérdida de frescura, exclusión del
destino como backend jugable normal, resolución `BOOTSTRAP_REQUIRED`, fallo
seguro con el backend apagado, limpieza correlacionada y recuperación mediante
una nueva transferencia después de encenderlo. El retry multiinstancia fue
validado en runtime con `lobby-1` apagado y `lobby-2` activo.

Flujos de transferencia confirmados:

```text
Lobby o Skyblock
  → TRANSFER_REQUEST
  → validación de origen
  → validación de sesión y presencia
  → resolución de destino autenticado
  → registro pendiente
  → ConnectionRequest de Velocity
  → TRANSFER_RESULT correlacionado

Auth
  → TRANSFER_REQUEST targeting LOBBY
  → validación de identidad, sesión, UUID, conexión actual y origen
  → resolución de lobby autenticado o ruta bootstrap
  → ConnectionRequest de Velocity
  → lobby-1 o fallback a lobby-2
  → PLAYER_SERVER_READY

Jugador autenticado en skyblock-1
  → /hub o /lobby
  → validación de sesión
  → resolución exclusiva de LOBBY
  → registro pendiente
  → ConnectionRequest de Velocity
  → lobby-1 o fallback a lobby-2
  → limpieza correlacionada
  → PLAYER_SERVER_READY
```

El circuito Auth→Lobby está operativo y validado con TheosferaAuth,
TheosferaCore y backends reales. La desconexión de `auth-1` durante el
cambio hacia `lobby-1` es parte normal del ciclo de vida, no un fallo.

Los comandos `/hub` y `/lobby` están implementados, probados y validados
en runtime real. Ambos son públicos para jugadores autenticados,
comparten el mismo comportamiento, resuelven únicamente `LOBBY`, fallan
cerrados cuando no existe un Lobby disponible y pueden reintentar otra
instancia después de `FAILED`, `REJECTED` o `TARGET_BUSY`. `TIMED_OUT` sigue
siendo terminal. `BOOTSTRAP_REQUIRED` permite un intento de conexión por la
ruta de bootstrap, pero no prueba disponibilidad ni inicia procesos remotos.

`/theosferaproxy status` está implementado, protegido por
`theosferaproxy.admin`, cubierto por pruebas y validado en runtime.
Presenta una vista administrativa de solo lectura de los backends
autorizados. La vista es local y de mejor esfuerzo; no reemplaza los
registros internos ni participa en routing, balanceo, reservas, health
checking o failover.

Limitaciones actuales:

- el estado continúa siendo local al proceso;
- existe un adaptador Redis distribuido para sesiones, pero no está conectado
  al runtime;
- no existe coordinación runtime entre múltiples proxies;
- `LocalPlayerSessionCoordinator` sigue siendo el adaptador runtime;
- `ProxyInstanceIdentity` ya usa `proxyName` estable configurado e
  `incarnationId` efímero por arranque;
- no existe todavía configuración/lifecycle Redis del plugin;
- no existe `ProxyMembershipCoordinator` distribuido;
- no existe renovación runtime de leases Redis;
- no existe wiring de `DISTRIBUTED_REQUIRED`;
- presencia global, transferencias, capacidad y bootstrap siguen sin
  coordinación Redis;
- no existe recuperación/HA Redis validada;
- persistencia/monotonicidad del contador de fencing ante restart/failover
  Redis sigue pendiente;
- no existe todavía observabilidad runtime Redis;
- la vista administrativa no es transaccional entre registros;
- no existen métricas históricas, series temporales ni auditoría durable;
- el comando no muestra todavía el detalle de cada transferencia pendiente;
- la distribución proporcional no fue validada directamente en runtime con
  tres jugadores simultáneos;
- `TIMED_OUT` terminal no fue provocado deliberadamente en runtime.

Trabajo futuro, sin implementar todavía:

- configuración/lifecycle Redis, membresía distribuida y activación controlada
  de `DISTRIBUTED_REQUIRED`;
- persistencia o coordinación distribuida activa del estado temporal;
- observabilidad detallada de transferencias, métricas e historia;
- modo mantenimiento.

La selección de modalidades pertenece a TheosferaLobby, no a
TheosferaProxy.

Siguiente hito técnico recomendado:

1. diseñar e implementar `RedisProxyMembershipCoordinator` o un
   `ProxyMembershipCoordinator` distribuido respetando la frontera existente;
2. adquirir membresía de forma atómica por `proxyName`;
3. usar un lease de membresía con TTL inicial de diseño de 15 segundos;
4. renovar mediante exact-match;
5. liberar mediante exact-match;
6. mantener fencing monotónico;
7. demostrar que dos procesos con el mismo `proxyName` no pueden ser
   propietarios válidos simultáneamente;
8. mantener todavía el runtime sin activar `DISTRIBUTED_REQUIRED` hasta
   completar lifecycle, configuración y estado operacional;
9. después integrar configuración/lifecycle Redis, `CoordinationState`,
   renovación de membresía, wiring de `DISTRIBUTED_REQUIRED` y renovación
   runtime de sesiones;
10. después avanzar hacia presencia, transferencia, capacidad y bootstrap
    distribuidos.

La observabilidad operacional básica ya no es trabajo pendiente. La frontera de
coordinación distribuida ya fue diseñada. PR `#44` introdujo los contratos
asíncronos y el adaptador local de sesiones; PR `#45` integró esa frontera al
runtime de autenticación y materializó el hardening de binding, replay,
release, timeouts, quarantines, capacidad y lifecycle. PR `#47` resolvió la
identidad estable de Proxy. PR `#48` implementó el adaptador Redis de sesiones,
pero no lo conectó al runtime ni convirtió Redis en autoridad operacional.

No introducir parties, amigos o escuadrones sin definir primero su
persistencia y consistencia distribuida.

## 22. Diseño futuro Core–Client para keybinds

Idea registrada, todavía no implementada:

- cada keybind conserva un identificador estable y una tecla
  predeterminada definida por el servidor;
- un jugador sin TheosferaClient utiliza `/key <tecla-predeterminada>`;
- TheosferaClient permite conservar la tecla predeterminada o reasignarla
  localmente desde su menú;
- al pulsarla, el cliente envía el identificador estable de la keybind, no
  la tecla física como fuente de autoridad;
- TheosferaCore valida existencia, permisos, contexto, cooldown y demás
  condiciones antes de ejecutar las acciones;
- TheosferaProtocol deberá definir los mensajes de sincronización,
  activación y prompts contextuales;
- el cliente podrá mostrar mensajes como `Presiona [G] para hablar`,
  resolviendo automáticamente la tecla personalizada del jugador;
- los jugadores sin mod recibirán un fallback compatible con comandos,
  chat o action bar;
- la personalización podrá almacenarse localmente al inicio y, más
  adelante, sincronizarse entre dispositivos.

Este diseño pertenece principalmente a TheosferaCore,
TheosferaProtocol y TheosferaClient. TheosferaProxy no debe ejecutar ni
autorizar acciones de keybind por sí solo salvo que un contrato futuro
requiera coordinación global explícita.

## 23. Visión aprobada de Core y modalidades

Las siguientes decisiones están aprobadas como visión arquitectónica futura.
No representan funciones ya implementadas y cada apartado importante deberá
planificarse y aprobarse con el propietario antes de escribir código.

### Regla de planificación

Antes de implementar comandos, perfiles, progreso, amigos, parties,
escuadrones, modalidades u otro sistema importante, se definirá conjuntamente:

- alcance y exclusiones;
- plugin responsable;
- comandos, permisos y configuración;
- experiencia de usuario y menús;
- persistencia y sincronización cross-server;
- casos límite, seguridad y comportamiento ante fallos;
- incrementos de implementación;
- pruebas automatizadas y validación runtime.

Que una función aparezca en esta visión significa que está prevista, no que su
diseño interno ya esté cerrado.

### Identidad de los backends

El diseño futuro separará dos conceptos:

- modalidad: `GENERAL`, `SKYWARS`, `BEDWARS`, `SKYBLOCK` y futuras
  modalidades;
- rol: `AUTH`, `LOBBY`, `PRE_GAME`, `GAME` y `PERSISTENT`.

`PRE_GAME` identifica las salas de espera o preparación. Las salas de espera y
las partidas activas serán backends independientes, con procesos, RAM,
configuración, lifecycle e identidad propios.

TheosferaCore cargará únicamente los módulos e integraciones compatibles con la
modalidad y el rol del backend. Un backend Skyblock no inicializará
integraciones de SkyWars o BedWars. La forma exacta de representar estos
conceptos en contratos y configuración se decidirá durante su planificación.

### Perfil y progreso general

`/profile` será un menú general disponible únicamente en backends con rol
`LOBBY` o `PRE_GAME`. Permanecerá bloqueado en `AUTH`, `GAME` y `PERSISTENT`.
La política concreta, sus permisos y su configuración se diseñarán antes de
implementarla.

Cada plugin de modalidad será autoridad de su propio progreso, misiones,
logros y estadísticas. TheosferaCore consumirá proveedores de esos plugins,
agregará los resultados y expondrá datos generales como nivel, avance, logros,
misiones y estadísticas globales. TheosferaLobby presentará esa identidad
global al jugador y funcionará como modalidad general de la network.

### Reemplazo de Essentials y responsabilidades externas

TheosferaCore reemplazará completamente a Essentials dentro de la network, pero
no replicará funciones que pertenezcan a sistemas especializados:

- ChatControl será responsable de chat, mensajes privados, canales, filtros,
  formato y comunicación;
- LiteBans será responsable de sanciones y moderación;
- TheosferaSkyblockAddons será responsable de `/storage`, maletas, colecciones,
  recetas, crafteos y estaciones especiales de Skyblock.

`/enderchest` será sustituido en Skyblock por `/storage`. El diseño previsto
incluye páginas persistentes desbloqueadas por rango y espacios para maletas
físicas con identidad y contenido persistentes. Antes de implementarlo deberán
definirse una única fuente de verdad, prevención de duplicaciones, guardado
ante fallos, maletas anidadas, pérdida o retiro de ítems y reducción de espacios
al vencer un rango.

`/workbench` tampoco pertenecerá a TheosferaCore. El crafteo, colecciones,
recetas desbloqueables, player vaults, encantamiento, reforja y futuras
estaciones serán parte del ecosistema de TheosferaSkyblockAddons.

### Integraciones previstas

- `FancyNpcs` sustituye a Citizens como integración de NPCs.
- TheosferaCore podrá integrarse de forma selectiva con PlaceholderAPI,
  DecentHolograms, ItemsAdder, ModelEngine, Lunar Client y los plugins
  correspondientes a cada modalidad.
- SuperiorSkyblock2 y las reglas propias de Skyblock permanecerán bajo
  TheosferaSkyblockAddons; Core recibirá únicamente los datos expuestos por su
  proveedor de progreso.

Estas decisiones no alteran el incremento técnico actual de TheosferaProxy.
Health checking, capacidad, preferencia, selección proporcional, retry
alternativo y observabilidad operacional administrativa están implementados y
cubiertos por pruebas automatizadas. El retry alternativo Auth→Lobby, `/hub`
y `/theosferaproxy status` fueron además validados en runtime.
distribución proporcional entre dos Lobbies activos continúa pendiente de
una prueba runtime con tres jugadores simultáneos, y `TIMED_OUT` terminal
no fue provocado deliberadamente en runtime. La recomendación de diseñar la
frontera de coordinación global distribuida antes de introducir Redis
corresponde al estado histórico de este checkpoint y quedó superseded: la
frontera distribuida fue diseñada posteriormente y
`RedisPlayerSessionCoordinator` fue implementado en PR `#48`. El siguiente
hito actual se define en las secciones posteriores, especialmente en el punto
exacto de reanudación y en el checkpoint `#27`.

## 24. Checkpoint de observabilidad operacional

La observabilidad operacional básica de TheosferaProxy está implementada,
fusionada y validada en runtime mediante:

- `6efacee feat: add proxy operational status observability (#40)`;
- `e07eed1 feat: improve proxy status command formatting (#41)`.

Comando administrativo:

`/theosferaproxy status`

Permiso requerido:

`theosferaproxy.admin`

La implementación incluye:

- `BackendOperationalSnapshot`;
- `BackendOperationalSnapshotService`;
- `ProxyStatusCommand`;
- `ProxyStatusCommandRegistration`;
- captura inmutable de las reservas bootstrap registradas;
- registro del comando durante la inicialización;
- desregistro del comando durante el apagado.

El comando muestra únicamente backends autorizados por la política y expone:

- nombre y tipo del backend;
- presencia del servidor en Velocity;
- identidad backend autenticada;
- estado de salud;
- jugadores conectados;
- capacidad reservada;
- carga total frente a la capacidad configurada;
- preferencia;
- presencia de una entrada en el registro bootstrap;
- última actividad saludable.

La captura es de solo lectura y de mejor esfuerzo. Consulta varios registros
independientes del proceso, por lo que no constituye una instantánea
transaccional global. No participa en routing, balanceo, reservas, health
checking ni failover, y no modifica ninguno de esos estados.

La presencia de una entrada bootstrap significa únicamente que existe un
registro observable en `BackendBootstrapRegistry`; no demuestra por sí sola
vigencia, disponibilidad o salud del backend.

La salida administrativa usa la identidad visual aprobada de Theosfera:

- oro principal `#E8B85B`;
- oro luminoso `#F8E798`;
- ámbar `#C46C19`;
- bronce `#8E5B29`;
- marfil `#F2E4C5`;
- texto secundario `#B89A79`;
- aqua `#55FFFF` para identificadores técnicos;
- verde para `HEALTHY`;
- ámbar para `STALE`;
- gris para `UNKNOWN`.

Cada backend se envía como un único componente multilínea para evitar que el
cliente agrupe líneas repetidas mediante indicadores como `[x2]`. La última
actividad saludable se presenta de forma relativa, por ejemplo `ahora`,
`hace 30 s`, `hace 4 min`, `hace 2 h`, `hace 3 d` o `nunca`. La línea
bootstrap solo aparece cuando existe una entrada registrada.

Validación confirmada:

1. la prueba específica de `ProxyStatusCommandTest` terminó correctamente;
2. la suite completa terminó en `BUILD SUCCESSFUL`;
3. una fuente sin `theosferaproxy.admin` no pudo ejecutar el comando;
4. después de conceder el permiso mediante LuckPerms, el comando fue accesible;
5. se observaron estados `HEALTHY`, `STALE` y `UNKNOWN`;
6. se comprobaron registro Velocity, autenticación, carga, conectados,
   reservados, capacidad, preferencia y última salud;
7. se observó un backend `STALE` con la última salud expresada mediante tiempo
   relativo;
8. el formato multilínea eliminó el agrupamiento visual `[x2]`;
9. la operación existente de Auth, Lobby y health checking permaneció funcional;
10. no se observaron errores de TheosferaProxy durante esta validación.

Limitaciones honestas de ese checkpoint histórico:

- toda la información continúa siendo local al proceso de Velocity;
- la vista no es transaccional entre registros;
- en ese momento no existían Redis, métricas históricas, series temporales ni
  auditoría durable; Redis quedó parcialmente superseded por PR `#48` como
  adaptador de sesiones no conectado al runtime;
- el comando no reemplaza una futura capa de monitoreo;
- no se validó la distribución proporcional con tres jugadores simultáneos;
- `TIMED_OUT` terminal no fue provocado deliberadamente en runtime.

Punto exacto de continuación histórico después de este checkpoint:

- diseñar la frontera de coordinación global distribuida para múltiples
  proxies;
- definir propiedad del estado, consistencia, TTL, recuperación y degradación;
- conservar comportamiento fail-closed ante pérdida de la capa distribuida;
- decidir después si Redis será el transporte temporal adecuado; PR `#48`
  seleccionó Redis para el adaptador de sesiones, pero no lo activó como
  transporte runtime general;
- no introducir parties, amigos o escuadrones sin una fuente de verdad y una
  estrategia explícita de consistencia distribuida.

## 25. Diseño de frontera de coordinación distribuida

La frontera arquitectónica para coordinar múltiples instancias de
TheosferaProxy fue definida antes de introducir Redis o cualquier estado
compartido.

Documento de diseño:

```text
docs/DISTRIBUTED_COORDINATION_BOUNDARY.md
```

El diseño conserva una separación explícita entre estado local y estado
distribuido.

Permanecen como autoridad local de cada proxy:

- el registro de servidores de Velocity;
- las identidades autenticadas mediante handshake;
- el estado de salud y frescura de los backends;
- las comprobaciones `PING` pendientes;
- los callbacks y operaciones `ConnectionRequest`;
- los listeners de conexión, desconexión y kick.

La salud observada por una instancia de Proxy no constituye evidencia de que
otra instancia pueda alcanzar el mismo backend. Un destino remoto no puede
volverse elegible únicamente porque otro proxy lo reportó como saludable.

Requieren coordinación global:

- membresía e identidad de cada proceso Proxy;
- propiedad temporal de sesiones autenticadas;
- presencia global de jugadores;
- exclusión de una transferencia activa por jugador;
- reservas globales de capacidad;
- reservas exclusivas de bootstrap;
- deduplicación temporal de resultados terminales.

La coordinación futura será independiente del transporte mediante contratos
asíncronos y adaptadores. La lógica de dominio no dependerá directamente de
Redis ni de un cliente concreto.

El primer incremento local de esa frontera está repartido entre PR `#44` y PR `#45`:

- PR `#44` introdujo `PlayerSessionCoordinator` como contrato asíncrono;
- PR `#44` introdujo `PlayerSessionLeaseRequest`, `PlayerSessionLease`,
  `PlayerSessionAcquireResult`, `PlayerSessionRenewResult` y
  `ProxyInstanceIdentity`;
- PR `#44` introdujo `LocalPlayerSessionCoordinator` como adaptador local;
- PR `#44` añadió `CoordinationMode`, `CoordinationState` y
  `removeIfMatches(...)` para exact-match local;
- PR `#45` integró esa frontera al runtime: la autenticación adquiere y vincula
  un lease antes de responder el ACK.

Esto no convirtió al runtime en distribuido. `LocalPlayerSessionCoordinator`
continúa respaldado por memoria local. La frase histórica de este checkpoint
indicaba que `RedisPlayerSessionCoordinator` no existía todavía; quedó
superseded por PR `#48`, que lo añadió como adaptador aislado sin conectarlo al
runtime.

Modos definidos:

- `LOCAL`;
- `DISTRIBUTED_REQUIRED`.

El modo `LOCAL` conservará la semántica actual para una sola instancia.
`DISTRIBUTED_REQUIRED` exigirá una capa de coordinación disponible y nunca
degradará silenciosamente hacia memoria local, porque eso permitiría
split-brain, sesiones duplicadas y sobreasignación de capacidad.

Toda propiedad temporal distribuida deberá utilizar:

- TTL;
- renovación explícita cuando corresponda;
- operaciones atómicas;
- liberación exact-match;
- idempotencia por `requestId`;
- fencing tokens para invalidar propietarios anteriores.

Estados operacionales previstos:

- `STARTING`;
- `HEALTHY`;
- `DEGRADED`;
- `FENCED`;
- `STOPPING`.

Política fail-closed definida:

- una pérdida de coordinación bloquea nuevas autenticaciones globales;
- no se inician nuevas transferencias ni reservas;
- no se realiza fallback silencioso hacia registros locales;
- un jugador puede permanecer temporalmente en su backend actual mientras el
  proxy todavía pueda demostrar la vigencia de su lease;
- cuando la instancia pierde definitivamente la propiedad, el jugador se
  desconecta de forma controlada;
- Auth no se utiliza como destino improvisado de recuperación;
- los callbacks tardíos no pueden completar ni limpiar operaciones posteriores.

La carga global de un backend se calculará a partir de:

```text
jugadores conectados reportados por proxies con lease fresco
+ reservas globales de capacidad vigentes
```

El conteo local de `RegisteredServer.getPlayersConnected()` no es suficiente
para múltiples proxies, porque cada proceso Velocity observa únicamente sus
propias conexiones.

Los eventos distribuidos serán avisos o mecanismos de invalidación y no
constituirán una fuente de verdad. Toda decisión autoritativa deberá consultar
o modificar el estado coordinado mediante operaciones atómicas.

Históricamente, en este diseño Redis era únicamente un candidato. Tras PR
`#48`, Redis fue seleccionado para el primer adaptador distribuido de sesiones,
pero todavía no es autoridad runtime ni está validado para todas las piezas de
la frontera. Los criterios que siguen permanecen vigentes para la activación
operacional y para los siguientes coordinadores:

- atomicidad multi-clave;
- TTL autoritativo;
- fencing monotónico;
- liberación exact-match;
- deduplicación;
- cliente Java 21 asíncrono;
- timeouts y reconexión controlada;
- comportamiento seguro ante particiones y reinicios;
- observabilidad operacional.

Redis Pub/Sub por sí solo no cumple la frontera definida.

Primer incremento de implementación recomendado en el diseño histórico:

1. introducir contratos de coordinación asíncronos;
2. añadir adaptadores locales respaldados por los registros actuales;
3. conservar exactamente la semántica runtime vigente;
4. añadir pruebas de equivalencia;
5. no introducir todavía Redis ni I/O de red;
6. crear posteriormente un simulador multi-proxy compartido únicamente para
   pruebas de exclusión, TTL, fencing y degradación.

Estado histórico posterior a PR `#45`: PR `#44` materializó los puntos 1, 2 y
la base de equivalencia local para sesiones autenticadas; PR `#45` conectó esa
frontera con el flujo runtime y añadió las garantías de coordinación, binding,
replay, release y lifecycle descritas en este checkpoint. Ese estado quedó
parcialmente superseded por PR `#47` y PR `#48`: la identidad estable y el
adaptador Redis de sesiones ya existen, mientras el runtime sigue en `LOCAL` y
la coordinación global de membresía, presencia, transferencia, capacidad y
bootstrap sigue pendiente.

No introducir parties, amigos, escuadrones ni otras operaciones sociales antes
de implementar una fuente de verdad persistente y una estrategia distribuida
coherente con esta frontera.

## 26. Checkpoint histórico de coordinación runtime de sesiones

PR que cierra este checkpoint:

- `87ea7d4 feat: integrate player session coordination at runtime (#45)`.

Este checkpoint conserva evidencia histórica de PR `#45`. Las afirmaciones de
esta sección sobre ausencia de Redis y falta de identidad estable quedaron
superseded por PR `#47` y PR `#48`; el estado actual está en el checkpoint
posterior.

Antecedente directo ya fusionado:

- `7e5bd7a feat: add local player session coordination contracts (#44)`.

Evidencia del PR `#45`:

- último HEAD de `feature/session-coordination-runtime` antes del squash:
  `ba0ff6eb0dd1e3f5c268faed702313ab961028be`;
- GitHub Actions Build `#101`: `SUCCESS` sobre `ba0ff6e`;
- clean build local final: `BUILD SUCCESSFUL`;
- `git diff --cached --check` previo al commit final: limpio;
- fresh review remoto final: sin findings `P0/P1/P2`;
- todos los review threads del PR quedaron resueltos;
- PR final antes del squash: 12 commits, 20 archivos modificados, 24096
  inserciones y 287 eliminaciones.

Arquitectura actual:

- PR `#44` introdujo `PlayerSessionCoordinator` como contrato asíncrono para
  adquirir, renovar y liberar leases de sesión;
- PR `#44` introdujo `PlayerSessionLease`, `PlayerSessionLeaseRequest`,
  `PlayerSessionAcquireResult`, `PlayerSessionRenewResult`,
  `ProxyInstanceIdentity`, `CoordinationMode` y `CoordinationState`;
- PR `#44` introdujo `LocalPlayerSessionCoordinator` como adaptador local y
  `removeIfMatches(...)` para exact-match local;
- `LocalPlayerSessionCoordinator` sigue siendo el adaptador runtime actual;
- `PlayerAuthenticatedMessageHandler` coordina/adquiere el lease antes de
  confirmar autenticación como parte de PR `#45`;
- PR `#45` añadió el hardening runtime de binding, replay,
  attempt/generation fencing, disconnect, releases, timeouts, quarantines,
  capacity fail-closed y lifecycle races;
- `PlayerSessionLeaseBindingRegistry` vincula el lease exacto a la conexión y
  a la solicitud;
- `PlayerSessionReleaseService` centraliza releases exact-match y timeouts de
  release.

Garantías importantes confirmadas en código:

- binding exacto por `Player`, UUID, sesión, `requestId`, `attemptId`, lease y
  fencing token;
- generaciones de conexión para separar OLD/NEW;
- replay pendiente, replay terminal exacto y ACK terminal cacheado cuando
  corresponde;
- replay positivo solo si existe binding vivo;
- reutilización conflictiva de `requestId` falla cerrada;
- solicitudes superseded silenciosas no crean `TerminalRequest`;
- cada retry/ronda usa `attemptId` nuevo y vuelve obsoletos callbacks previos;
- adquisición asíncrona con timeout mediante
  `VelocityPlayerSessionAcquisitionTimeoutScheduler`;
- release exact-match mediante `PlayerSessionReleaseService`;
- `RELEASE_PENDING` espera la liberación previa y reintenta con `attemptId`
  nuevo cuando corresponde;
- watchdog de release independiente mediante
  `VelocityPlayerSessionReleaseTimeoutScheduler`;
- quarantines exactas por operación, múltiples por UUID sin reemplazo
  silencioso, TTL/capacidad y retención acotada;
- eviction de capacidad falla cerrado;
- fencing floors históricos monotónicos/no regresivos según el registro local;
- late completions se reconcilian solo por identidad exacta;
- `PlayerSessionLeaseBindingResult.CAPACITY_EXHAUSTED` existe y activa cleanup
  exacto de leases recién adquiridos pero rechazados;
- una solicitud CURRENT afectada por capacidad recibe failure terminal
  replayable;
- una generación realmente STALE permanece silenciosa;
- precedencia semántica: `STALE` generation > `CAPACITY_EXHAUSTED`;
- en la carrera CURRENT→SUPERSEDED antes de `completeTerminalRequest`, la OLD
  no publica ACK terminal, no guarda `TerminalRequest`, elimina su
  `ActiveRequest` por coincidencia exacta y deja intacta la NEW;
- `PlayerDisconnectListener` usa binding exacto, revoca auth local solo para el
  lease/sesión exactos y no permite que un disconnect OLD afecte un `Player`
  NEW del mismo UUID;
- un lease exacto puede liberarse aunque la sesión local ya haya sido revocada;
- un lease stale no puede liberar el lease vigente;
- `PlayerSessionReleaseService.clear()` usa epoch de lifecycle y
  `ReentrantReadWriteLock` para impedir que callbacks/timeouts de un lifecycle
  anterior muten uno nuevo;
- handles retornados tarde por scheduler se cancelan;
- `schedule` throw/null y cancel excepcional fallan cerrados;
- donde el registro compara una `CompletionStage` externa para ownership de
  release/quarantine, la identidad efectiva es referencial y no depende de
  `equals()`.

Orden de apagado verificado en `TheosferaProxy.java` para esta parte:

1. se detiene `healthCheckScheduler`;
2. se desregistran listener de protocolo, comandos y listeners de jugador/kick;
3. se limpian bootstrap, failover, capacidad, transferencias y presencia;
4. se ejecuta `releaseService.clear()`;
5. se ejecuta `sessionLeaseBindingRegistry.clear()`;
6. se ejecuta `sessionRegistry.clear()`;
7. se limpian pings, health, identidades y se desregistra el canal.

Validación confirmada por familias de pruebas:

- adquisición, binding y replay;
- conflictos de `requestId`;
- attempt fencing y callbacks tardíos;
- generaciones OLD/NEW;
- carreras de disconnect/auth;
- acquisition timeouts;
- pending release y owned release timeouts;
- quarantines, retención y capacidad;
- lifecycle clear;
- scheduler throw/null y cancel excepcional;
- exact release;
- `CAPACITY_EXHAUSTED`;
- terminalización CURRENT vs SUPERSEDED.

Limitaciones en ese checkpoint histórico:

- el runtime sigue usando `LocalPlayerSessionCoordinator`;
- en ese momento histórico `RedisPlayerSessionCoordinator` no existía todavía;
  desde PR `#48` existe como adaptador aislado y probado, aunque no está
  conectado al runtime;
- no existe coordinación real entre múltiples procesos Proxy;
- en ese momento histórico `ProxyInstanceIdentity` usaba `proxyName` local
  `theosfera-proxy-local` y una `incarnationId` aleatoria por arranque; esa
  limitación quedó superseded por PR `#47`, que introdujo `proxyName` estable
  configurado e `incarnationId` efímero;
- en ese momento histórico no se implementaron Redis, Testcontainers,
  simulador multi-proxy ni pruebas runtime de dos proxies; PR `#48` añadió
  Redis/Testcontainers para el adaptador de sesiones, pero no pruebas runtime
  reales de dos proxies;
- el estado temporal de sesiones sigue siendo local y no persistente.

Punto exacto de reanudación histórico, superseded por PR `#47` y PR `#48`:

1. definir/configurar una identidad estable y segura de cada instancia Proxy;
2. después diseñar/implementar `RedisPlayerSessionCoordinator` respetando
   `PlayerSessionCoordinator`;
3. validar atomicidad, TTL, fencing, exact-match release e idempotencia;
4. añadir Redis/Testcontainers;
5. cubrir pruebas multi-proxy;
6. probar restart, partition, timeout y late callbacks;
7. añadir observabilidad de coordinación distribuida;
8. retirar sobrecargas legacy cuando sea seguro.

No se implementó Redis dentro de ese checkpoint documental; se implementó
después en PR `#48` como adaptador aislado.

## 27. Checkpoint de identidad estable y Redis Player Session Coordinator

PRs que cierran este checkpoint:

- `d4c06d9 feat: configure stable proxy instance identity (#47)`;
- `e2fe4f1 feat: add Redis player session coordinator (#48)`.

Evidencia Git del checkpoint:

- `main` sincronizada con `origin/main` en `e2fe4f1`;
- PR `#47` fusionado en `main`;
- PR `#48` fusionado en `main`;
- working tree limpio antes de crear la rama documental;
- rama documental:
  `docs/redis-player-session-coordinator-checkpoint`.

### Stable Proxy Identity

`ProxyInstanceIdentity` separa `proxyName` e `incarnationId`.

Configuración runtime:

```text
plugins/theosferaproxy/proxy-instance.properties
```

```properties
proxy-name=proxy-1
```

`proxyName` es la identidad lógica estable de la instancia Proxy.
`incarnationId` es un UUID efímero generado en cada ejecución del proceso.
Reiniciar la misma instancia lógica conserva `proxyName`; cada arranque genera
un `incarnationId` nuevo. Distintas instancias Proxy deben usar distintos
`proxyName`.

`incarnationId` no se persiste, no se genera un nombre lógico aleatorio y una
configuración inválida falla temprano. El formato aceptado para `proxy-name` es
ASCII lowercase, números y guiones, de 1 a 32 caracteres, sin iniciar ni
terminar en guion. La carga ocurre durante inicialización, no mediante I/O en
el constructor.

### Redis Player Session Coordinator

`RedisPlayerSessionCoordinator` existe bajo:

```text
com.theosfera.proxy.coordination.distributed.redis
```

Implementa `PlayerSessionCoordinator` y fue probado aisladamente. El runtime
sigue en modo `LOCAL`: `TheosferaProxy` compone todavía
`LocalPlayerSessionCoordinator`, no crea conexiones Redis y no referencia
`RedisPlayerSessionCoordinator` en su composición runtime. No existe fallback
silencioso hacia `LocalPlayerSessionCoordinator`; simplemente el adaptador
Redis aún no está conectado.

Modelo Redis de sesión:

```text
theosfera:coordination:player-session:<playerUuid>
theosfera:coordination:player-session:fencing
```

El lease Redis es un hash explícito con:

- `player-id`;
- `player-name`;
- `authenticated-at`;
- `proxy-name`;
- `incarnation-id`;
- `fencing-token`.

TTL inicial de diseño: 30 segundos. El TTL es autoritativo en Redis e
inyectable en el coordinador.

El contador de fencing usa Redis `INCR`, no depende de un contador Java local y
actualmente no expira. Un `acquire` nuevo obtiene fencing nuevo;
`ALREADY_OWNED` conserva fencing; `renew` conserva fencing; `release` +
reacquire produce fencing mayor; expiración + reacquire produce fencing mayor.
No está validado todavía que restart/HA de Redis preserve correctamente la
monotonicidad bajo todos los escenarios.

`LettuceRedisPlayerSessionStore` implementa operaciones Lua/EVAL atómicas.

`ACQUIRE` cubre:

- creación;
- idempotencia exacta;
- detección de otro owner;
- conflicto de sesión;
- generación de fencing;
- TTL.

`RENEW` cubre:

- exact-match;
- conservación de fencing;
- extensión del TTL.

`RELEASE` cubre:

- exact-match completo;
- eliminación solo si sesión, owner, incarnation y fencing coinciden.

No existe patrón inseguro `GET` -> decisión Java -> `SET` para estas
operaciones.

Política fail-closed e invariantes:

- estructura Redis corrupta no se sobrescribe silenciosamente;
- key con tipo incorrecto falla cerrada;
- hashes incompletos fallan cerrados;
- fencing inválido falla cerrado;
- lease sin TTL válido falla cerrado;
- contador fencing corrupto no crea una sesión nueva;
- estado Redis inválido se propaga como fallo/invariante, no se disfraza
  automáticamente como coordinación no disponible.

Solo fallos operativos Redis reconocidos actualmente se traducen a
`COORDINATION_UNAVAILABLE` en `acquire`/`renew` cuando corresponde:

- `RedisConnectionException`;
- `RedisCommandTimeoutException`.

`releaseIfOwned` devuelve `false` para mismatch o ausencia de lease. Un fallo
Redis completa excepcionalmente.

`RedisPlayerSessionCoordinator` mantiene ownership local exacto:

```text
UUID -> PlayerSessionLease
```

El mirror evita que un release tardío de lease A, seguido por reacquire de
lease B para la misma `AuthenticatedPlayerSession` con fencing mayor, elimine
la sesión local correspondiente a B. Un release solo limpia
`AuthenticatedPlayerSessionRegistry` cuando el lease local vigente sigue
siendo exactamente el esperado. `AuthenticatedPlayerSessionRegistry` por sí
solo no es autoridad del fencing distribuido.

Cliente Redis:

```text
io.lettuce:lettuce-core:7.6.0.RELEASE
```

La razón arquitectónica es su API asíncrona, compatible con el requisito de no
bloquear hilos de Velocity. En código Redis de producción no se usan `sync()`,
`join()`, `get()`, `await()` ni `Thread.sleep`.

### Validación

Testcontainers:

```text
org.testcontainers:junit-jupiter:1.21.4
redis:7.4.2-alpine
```

Validación local confirmada antes de PR:

- `RedisPlayerSessionCoordinatorTest`: 17 tests, 0 skipped, 0 failures,
  0 errors;
- `LocalPlayerSessionCoordinatorTest`: 13 tests;
- `PlayerAuthenticatedMessageHandlerTest`: 51 tests;
- `PlayerSessionReleaseServiceTest`: 18 tests;
- clean build local: `BUILD SUCCESSFUL`;
- `git diff --cached --check` previo al commit: limpio.

Las integration tests Redis locales fueron skipped porque Docker no estaba
disponible en esa máquina. La suite de integración contiene 10 métodos `@Test`.
El gate de CI usa `CI=true`; si Docker no está disponible en CI, `@BeforeAll`
lanza `IllegalStateException` en vez de usar `Assumption`. GitHub Actions Build
`#107` terminó `SUCCESS` sobre PR `#48`, por lo que no ocurrió la ruta conocida
de skip por Docker unavailable. No se documenta aquí un conteo JUnit del CI
porque no hay evidencia directa de ese conteo.

### Shading Redis

El JAR Shadow relocaliza dependencias Redis bajo namespaces privados:

```text
io.lettuce -> com.theosfera.proxy.libs.lettuce
io.netty -> com.theosfera.proxy.libs.netty
reactor -> com.theosfera.proxy.libs.reactor
org.reactivestreams -> com.theosfera.proxy.libs.reactivestreams
redis.clients.authentication -> com.theosfera.proxy.libs.redisauth
```

`META-INF/services` se fusiona mediante `mergeServiceFiles()`.

Las pruebas de empaquetado verifican:

- ausencia de clases bajo namespaces originales;
- presencia de namespaces privados;
- service descriptors coherentes;
- ausencia de binarios nativos inesperados `.so`, `.dll` o `.dylib`.

El JAR local validado durante el incremento fue de aproximadamente 8,417,506
bytes. Ese tamaño es evidencia local del build del incremento, no un artefacto
runtime desplegado.

### Estado transitorio

Estado runtime actual:

- modo operacional: `LOCAL`;
- Redis adapter presente pero no conectado;
- `LocalPlayerSessionCoordinator` sigue siendo la autoridad runtime;
- Redis no es todavía la autoridad runtime.

Limitaciones honestas:

- no existe configuración/lifecycle Redis del plugin;
- no existe `ProxyMembershipCoordinator` distribuido;
- no existe renovación runtime de leases Redis;
- no existe wiring de `DISTRIBUTED_REQUIRED`;
- presencia global, transferencias, capacidad y bootstrap siguen sin
  coordinación Redis;
- no existe recuperación/HA Redis validada;
- persistencia/monotonicidad del fencing counter ante restart/failover Redis
  sigue pendiente;
- no existe observabilidad runtime Redis;
- no se validó runtime real multi-proxy.

Decisiones cerradas para el adaptador Redis de sesiones:

- fail-closed;
- no fallback silencioso;
- fencing;
- TTL;
- exact-match;
- atomicidad Lua/EVAL;
- cliente async;
- shading privado.

Riesgos futuros:

- restart Redis;
- HA Redis;
- persistencia del fencing counter;
- membresía distribuida;
- lifecycle/configuración Redis;
- renew periódico de membresía y sesiones;
- runtime real multi-proxy.

Punto exacto de reanudación:

1. diseñar e implementar `RedisProxyMembershipCoordinator` o un
   `ProxyMembershipCoordinator` distribuido respetando la frontera existente;
2. adquisición atómica de membresía por `proxyName`;
3. lease de membresía con TTL inicial de diseño de 15 segundos;
4. renovación exact-match;
5. release exact-match;
6. fencing monotónico;
7. demostrar que dos procesos con el mismo `proxyName` no pueden ser
   propietarios válidos simultáneamente;
8. mantener todavía el runtime sin activar `DISTRIBUTED_REQUIRED` hasta
   completar lifecycle/configuración y estado operacional;
9. después integrar configuración/lifecycle Redis, `CoordinationState`,
   renovación de membresía, wiring `DISTRIBUTED_REQUIRED` y renovación runtime
   de sesiones;
10. después avanzar hacia presencia, transferencia, capacidad y bootstrap
    distribuidos.

No introducir parties, amigos o escuadrones en el siguiente incremento.

## 28. Checkpoint vigente de Redis Coordination Runtime

El estado autoritativo posterior a los PR `#50`, `#51` y `#52` está documentado en `docs/REDIS_RUNTIME_CHECKPOINT.md`.

Ese documento supersede las afirmaciones históricas anteriores que indiquen que Redis no está conectado al runtime, que `LocalPlayerSessionCoordinator` sigue siendo la autoridad productiva, que no existe membership distribuida o que no existe renovación runtime de sesiones.

Estado base de este checkpoint: `main` @ `d6095bd` (`feat: activate Redis player session runtime (#52)`).

El siguiente hito es definir con precisión la semántica de `CoordinationMode` y la activación explícita de `DISTRIBUTED_REQUIRED`, manteniendo fuera de scope por ahora presencia, transferencias, capacidad y bootstrap distribuidos.

## 29. Checkpoint final - Redis Player Presence Runtime

El runtime distribuido de presencia de jugadores quedó completado y fusionado mediante el PR `#57` (`feat: integrate Redis player presence runtime`).

El checkpoint autoritativo está documentado en `docs/PLAYER_PRESENCE_RUNTIME_WIP.md`, cerrado como Final Checkpoint.

Estado post-merge: `main` @ `18a7713`.

Estado confirmado:

- `RedisCoordinationRuntime` expone `RedisPlayerPresenceCoordinator` reutilizando la conexión Lettuce existente;
- `PLAYER_SERVER_READY` publica presencia distribuida usando el `PlayerSessionLease` exacto de la conexión y su fencing token;
- `PlayerPresenceRuntimeService` centraliza publicación, renovación y retirada;
- existe renovación periódica de presencia;
- disconnect ejecuta retirada de presencia antes de liberar el lease de sesión;
- shutdown drena presencia antes de liberar sesiones;
- TTL permanece como fallback cuando Redis no puede confirmar la retirada;
- la política general continúa siendo fail-closed;
- el lifecycle operacional exige runtime distribuido de sesiones y presencia.

Validación final:

- `723` tests ejecutados, `22` skipped y `0` fallos;
- `./gradlew.bat clean build --no-daemon` -> `BUILD SUCCESSFUL`;
- `git diff main...HEAD --check` limpio;
- GitHub Actions `Gradle Build` del PR `#57` -> `success`.

La presencia utiliza actualmente `playerSessionTtl` y `playerSessionRenewInterval` como política temporal. Separar TTL e intervalo propios de presencia queda como mejora futura y no constituye deuda bloqueante.

Transferencias, capacidad/reservas y bootstrap distribuidos continúan fuera de scope y deben abordarse como fronteras independientes.

El siguiente hito es decidir explícitamente cuál de esas fronteras distribuidas debe implementarse primero, manteniendo arquitectura modular, ownership explícito y política fail-closed.

## 30. Checkpoint final - Redis Backend Capacity Foundation

El foundation de capacidad distribuida para backends quedó completado y fusionado mediante el PR `#59` (`feat: add Redis backend capacity foundation`).

El checkpoint autoritativo está documentado en `docs/REDIS_BACKEND_CAPACITY_DESIGN.md`, cerrado como Final Checkpoint.

Estado post-merge funcional: `main` @ `4080f03` (`4080f0364f2821af7e5cad15b4e5e5a5edb64702`).

Estado confirmado:

- `BackendOccupancyCoordinator` define la lectura agregada y fail-closed de ocupación por backend;
- la presencia Redis mantiene un índice global por backend mediante sorted sets sin utilizar `SCAN`;
- cada miembro del índice de presencia usa `playerId` y un `expiresAt` calculado con `Redis TIME`;
- publish, update, movimiento, renovación y remove de presencia mantienen el índice dentro de la misma decisión Lua fenced;
- las lecturas podan miembros vencidos antes de contar;
- `BackendCapacityCoordinator` y `BackendCapacityReserveRequest` introducen la frontera distribuida de reservas;
- cada reserva exige el `PlayerSessionLease` exacto del jugador;
- Redis valida sesión, owner, incarnation y fencing token antes de crear o renovar capacidad;
- la reserva ejecuta de forma atómica pruning de ocupación y reservas, conteo, comparación contra capacidad y creación del lease temporal;
- las reservas utilizan hash exacto con TTL y sorted set por backend;
- `releaseIfOwned()` exige coincidencia exacta de request, jugador, backend, owner, incarnation y fencing;
- fallos Redis no degradan silenciosamente hacia autoridad local;
- `RedisCoordinationRuntime` y `VelocityRedisCoordinationBootstrap` exponen factories reutilizando la conexión Lettuce existente;
- `TransferTargetResolver` todavía no consume capacidad Redis;
- `BackendCapacityReservationRegistry` continúa siendo la ruta productiva local de transferencias;
- no existe todavía una política/configuración productiva definitiva para `reservationTtl`.

Validación final del PR `#59`:

- `.\gradlew.bat test --no-daemon` -> `BUILD SUCCESSFUL`;
- `.\gradlew.bat clean build --no-daemon` -> `BUILD SUCCESSFUL`;
- `git diff main...HEAD --check` limpio;
- working tree limpio antes de publicar;
- GitHub Actions Build `#131` -> `success`.

Barrera de rollout aprobada:

1. desplegar el foundation en todos los procesos Proxy;
2. permitir que las renovaciones de presencia pueblen y calienten el índice global;
3. validar en runtime multi-proxy ocupación, movimientos, renewals, disconnect y expiración;
4. confirmar que no quedan proxies antiguos escribiendo presencia sin mantener el índice;
5. definir una política explícita de `reservationTtl`;
6. solo después migrar el flujo productivo de selección/reserva hacia `BackendCapacityCoordinator`.

Distributed transfer coordination y distributed backend bootstrap coordination permanecen como fronteras independientes posteriores.

Punto exacto de reanudación:

- validar el índice global de ocupación en runtime multi-proxy después de su calentamiento;
- definir `reservationTtl` productivo;
- diseñar después el wiring de `TransferTargetResolver` / allocation flow hacia capacidad Redis sin introducir fallback local silencioso;
- conservar ownership explícito, atomicidad, fencing y política fail-closed.

No introducir parties, amigos o escuadrones dentro del siguiente incremento.

## 31. Checkpoint - Redis Backend Capacity Runtime Rollout Validation

La barrera de rollout definida en el checkpoint `#30` quedo completada
documentalmente despues de una validacion runtime real multi-proxy.

Este checkpoint supersede las afirmaciones del `#30` que indicaban que la
validacion runtime multi-proxy del indice global y la politica productiva de
`reservationTtl` seguian pendientes. El `#30` se conserva como checkpoint
historico del foundation; este checkpoint cierra la evidencia posterior al
rollout.

Validacion runtime confirmada:

- Redis Open Source `7.4.2` estuvo operativo en `127.0.0.1:6379`;
- se ejecutaron simultaneamente dos procesos Velocity:
  - `proxy-1` en `127.0.0.1:25565`;
  - `proxy-2` en `127.0.0.1:25564`;
- ambos adquirieron membresias Redis independientes y renovables;
- dos jugadores distintos quedaron simultaneamente en `lobby-1` mediante
  proxies distintos, uno propiedad de `proxy-1` y otro de `proxy-2`;
- el indice global
  `theosfera:coordination:backend-presence:lobby-1` alcanzo `ZCARD = 2`;
- los hashes `player-presence` confirmaron ownership distinto por jugador:
  jugador A -> `proxy-1` y jugador B -> `proxy-2`;
- ambos scores del sorted set de presencia avanzaron despues de esperar mas de
  un intervalo de renovacion, confirmando renovacion activa desde ambos
  proxies;
- no aparecieron warnings de `PLAYER_SERVER_READY` no autorizado ni `PONG` no
  autorizado;
- despues del fix de TheosferaCore, cada Proxy registro independientemente
  `auth-1` y `lobby-1` mediante su propio carrier.

Durante la validacion se descubrio un blocker real fuera de TheosferaProxy:
`BackendHandshakeService` en TheosferaCore mantenia autorizacion global por
backend, por lo que un carrier conectado mediante `proxy-2` podia heredar
autorizacion obtenida originalmente mediante `proxy-1`.

Prerequisite/fix confirmado:

- TheosferaCore PR `#17`;
- merge commit `bd29cfe`;
- `fix(network): scope backend handshake authorization by carrier (#17)`;
- el fix cambio la autorizacion a carrier-scoped y permitio que cada Proxy
  registrara los backends por su propio carrier.

Movimiento multi-proxy validado:

- inicialmente `lobby-1` tenia occupancy `2`;
- el jugador de `proxy-2` se movio de `lobby-1` a `skyblock-1`;
- despues del movimiento:
  - `lobby-1` quedo con `ZCARD = 1`;
  - `skyblock-1` quedo con `ZCARD = 1`;
- cada indice contenia exactamente al jugador esperado.

Clean disconnect del jugador de `proxy-2`:

- `lobby-1` permanecio en `1`;
- `skyblock-1` paso a `0`;
- `player-presence` del jugador desconectado dejo de existir;
- `player-session` del jugador desconectado dejo de existir.

Crash abrupto de `proxy-1`:

- antes del crash, `lobby-1` tenia raw `ZCARD = 1`, la player session existia y
  la membership de `proxy-1` tenia TTL positivo;
- despues del crash, la membership de `proxy-1` expiro y la player session
  expiro por TTL;
- el raw `ZCARD` permanecio temporalmente en `1`, como se esperaba porque un
  ZSET no elimina miembros por score automaticamente.

Despues de superar el TTL, la lectura/pruning equivalente al algoritmo
autoritativo ejecuto:

```text
Redis TIME
ZREMRANGEBYSCORE -inf nowMillis
ZCARD
```

Resultado:

- `1` miembro stale eliminado;
- occupancy resultante `0`;
- `ZCARD` posterior `0`;
- `ZRANGE` posterior vacio.

Conclusiones del rollout:

- un `ZCARD` crudo no es autoridad despues de crashes;
- la lectura con `Redis TIME` + pruning si produce occupancy autoritativa;
- en la topologia dev validada solo participaron `proxy-1` y `proxy-2`;
- ambos proxies ejecutaban el foundation moderno;
- no participo ningun Proxy legacy que escribiera presencia sin mantener el
  indice global.

Decision cerrada de `reservationTtl`:

```text
reservationTtl productivo inicial = 20 segundos
```

Justificacion:

- `PlayerTransferExecutor.DEFAULT_TIMEOUT` actual es `10` segundos;
- una reserva debe vivir mas que el intento de conexion que protege;
- `20` segundos da un margen de `10` segundos sobre el timeout maximo normal;
- `PlayerTransferRetryCoordinator` libera la reserva de capacidad del intento
  terminado antes de iniciar un retry alternativo;
- por tanto `reservationTtl` no necesita cubrir toda una cadena de retries:
  protege una reserva de un intento/backend concreto;
- el TTL es fallback de recuperacion ante crash, no sustituto del release
  exact-match;
- `20` segundos limita cuanto tiempo puede permanecer capacidad fantasma
  despues de una caida sin arriesgar expiracion durante el intento normal;
- Redis sigue siendo fail-closed;
- no existe fallback silencioso a `BackendCapacityReservationRegistry`;
- no se introduce todavia una propiedad de configuracion ni se fija el nombre
  de esa property; eso pertenece al wiring productivo siguiente.

Punto exacto de reanudacion:

Disenar el wiring productivo de capacidad distribuida.

Restricciones para el siguiente milestone:

- `TransferTargetResolver` no debe conocer Redis, claves Redis, Lua ni Lettuce;
- `BackendCapacityCoordinator` es asincrono; no forzar llamadas bloqueantes para
  encajarlo artificialmente en el resolver sincrono actual;
- separar seleccion/candidatos de allocation/reservation si es necesario;
- una reserva distribuida debe recibir el `PlayerSessionLease` exacto;
- no usar connected player count local como autoridad;
- mapear explicitamente:
  - `RESERVED`;
  - `ALREADY_RESERVED`;
  - `REQUEST_ID_CONFLICT`;
  - `NO_CAPACITY`;
  - `SESSION_NOT_FOUND`;
  - `NOT_SESSION_OWNER`;
  - `OCCUPANCY_UNAVAILABLE`;
  - `COORDINATION_UNAVAILABLE`;
- Redis/coordination unavailable debe fallar cerrado;
- jamas fallback automatico a `BackendCapacityReservationRegistry` en modo
  distribuido;
- preservar release exact-match en success, failure, rejection, timeout, retry,
  disconnect/lifecycle apropiado;
- distributed transfer coordination y distributed backend bootstrap continuan
  siendo fronteras posteriores e independientes;
- no introducir parties, friends ni squads.

## 32. Checkpoint - Productive Redis Transfer Capacity Runtime Validation

El wiring productivo de capacidad distribuida para `TRANSFER_REQUEST` quedo
implementado y validado en runtime real sobre la rama
`feature/redis-backend-capacity-runtime`.

Este checkpoint supersede el punto de reanudacion del `#31` que indicaba que el
wiring productivo de capacidad distribuida seguia pendiente. El checkpoint
`#31` se conserva como evidencia del rollout previo del foundation y de la
decision de `reservationTtl = 20 segundos`.

### Estado de codigo y validacion automatizada

HEAD funcional validado antes de este checkpoint:

```text
519639d584cc900870e7a775e5b68180732a647f
519639d test: register pending transfer in distributed test runtime
```

La rama incorporo la ruta productiva distribuida mediante:

- `DistributedBackendCapacityReleaseService` para release exacto por
  `BackendCapacityReserveRequest`;
- `DistributedPlayerTransferAttemptLifecycle` para cleanup exacto de pending,
  bootstrap y reserva distribuida;
- `DistributedPlayerTransferRetryCoordinator` como state machine asincrona;
- `DistributedPlayerTransferTargetAllocationService` como frontera de
  allocation/reservation distribuida;
- handoff de reserva exitosa mediante `BackendCapacityHandoffService`;
- `TransferRequestMessageHandler` conectado al retry coordinator distribuido;
- composition root de `TheosferaProxy` usando el mismo
  `BackendCapacityCoordinator` para reserve y release;
- una ruta `candidates()` de `TransferTargetResolver` para candidatos
  distribuidos sin usar connected player count local como autoridad.

Semantica productiva confirmada en codigo:

- `TRANSFER_REQUEST` ya no usa la reserva local de capacidad como autoridad;
- Redis no degrada silenciosamente hacia
  `BackendCapacityReservationRegistry` para `TRANSFER_REQUEST`;
- `SUCCESS` no libera inmediatamente la reserva: primero registra handoff y
  espera la confirmacion de presencia del destino;
- fallo o rechazo limpia el intento exacto y solo permite retry si el release
  distribuido fue confirmado;
- `TIMED_OUT` limpia de forma exacta y es terminal;
- conflicto, mismatch o incertidumbre de cleanup falla cerrado;
- los callbacks tardios no pueden liberar capacidad perteneciente a un intento
  posterior;
- los status de capacidad distribuida se mapean explicitamente, incluidos
  `NO_CAPACITY`, `REQUEST_ID_CONFLICT`, `SESSION_NOT_FOUND`,
  `NOT_SESSION_OWNER`, `OCCUPANCY_UNAVAILABLE` y
  `COORDINATION_UNAVAILABLE`.

La ruta sincrona historica `TransferTargetResolver.resolve()` permanece para
consumidores legacy. En este incremento solo `TRANSFER_REQUEST` fue migrado a
capacidad distribuida. `/hub`, `/lobby` y el failover ante kicks conservan su
ruta productiva local de capacidad y no deben describirse todavia como
consumidores Redis.

Validacion automatizada ejecutada sobre `519639d`:

```powershell
.\gradlew.bat test `
  --tests "*ProtocolColdBackendBootstrapFlowTest" `
  --tests "*ProtocolPlayerTransferFlowTest" `
  --no-daemon
```

Resultado: `BUILD SUCCESSFUL`.

Tambien se ejecuto:

```powershell
.\gradlew.bat test `
  --tests "*DistributedBackendCapacityReleaseServiceTest" `
  --tests "*DistributedPlayerTransferAttemptLifecycleTest" `
  --tests "*DistributedPlayerTransferRetryCoordinatorTest" `
  --tests "*TransferRequestMessageHandlerTest" `
  --tests "*TransferTargetResolverDistributedCandidatesTest" `
  --tests "*TransferTargetResolverTest" `
  --no-daemon
```

Resultado: `BUILD SUCCESSFUL`.

Finalmente:

```powershell
.\gradlew.bat test --no-daemon
.\gradlew.bat clean build --no-daemon
git diff --check
git status
```

Resultado confirmado:

- suite completa: `BUILD SUCCESSFUL`;
- clean build: `BUILD SUCCESSFUL`;
- `git diff --check`: sin salida;
- working tree limpio y sincronizado antes de la validacion runtime.

### Topologia runtime de la validacion

Redis Open Source `7.4.2` compilado localmente en WSL estuvo operativo en
`127.0.0.1:6379`.

Topologia utilizada:

```text
proxy-1     127.0.0.1:25565
proxy-2     127.0.0.1:25564
lobby-1     127.0.0.1:25566
skyblock-1  127.0.0.1:25567
auth-1      127.0.0.1:25568
```

TheosferaCore estuvo instalado en Auth, Lobby y Skyblock; TheosferaProxy solo
en Velocity. La prueba explicita de transferencia utilizo
`/theosfera transfer <lobby|skyblock>` para producir `TRANSFER_REQUEST`.
`/server` de Velocity no se utilizo como evidencia porque no atraviesa la ruta
productiva de allocation distribuida de Theosfera.

### SUCCESS: reservation -> connection -> presence -> exact release

Con un jugador autenticado en `lobby-1`, antes de transferir se observo:

- session Redis propiedad de `proxy-1`;
- presence Redis en `lobby-1`;
- occupancy global de `lobby-1 = 1`;
- occupancy global de `skyblock-1 = 0`;
- cero keys `backend-capacity:*`.

La primera transferencia exitosa hacia Skyblock creo una reserva con
`requestId`:

```text
b75985ee-8c0a-48a7-9ca9-cff0afdd9724
```

Mientras la reserva estaba viva se observo:

```text
backend-name          = skyblock-1
proxy-name            = proxy-1
session-fencing-token = 11
PTTL                   ~= 19942 ms
lobby occupancy       = 1
skyblock occupancy    = 0
```

Esto demuestra que la plaza quedo protegida antes de contabilizar presencia en
el destino.

Despues de `PLAYER_SERVER_READY`:

- `player-presence` paso a `skyblock-1`;
- el indice de `lobby-1` quedo vacio;
- el indice de `skyblock-1` quedo con el jugador;
- no quedaron keys de capacidad residuales.

La prueba posterior de last-slot capturo ademas el release explicito de una
reserva exitosa: despues de publicar presencia en `skyblock-1`, Redis ejecuto
la validacion exacta del hash de reservation y posteriormente:

```text
DEL  theosfera:coordination:backend-capacity:reservation:<requestId>
ZREM theosfera:coordination:backend-capacity:backend:skyblock-1 <requestId>
```

Por tanto el handoff exitoso no depende solamente del TTL para recuperar la
capacidad.

### Connection failure: exact release antes del retry

Para provocar un fallo fisico, `skyblock-1` se apago y se disparo
inmediatamente un `TRANSFER_REQUEST` mientras aun era candidato localmente.

Request validado:

```text
f565f98d-88ff-4f93-a4b7-c5456ebd285a
```

Redis observo en orden:

```text
HSET reservation:f565f98d...
PEXPIRE ... 20000
ZADD backend-capacity:backend:skyblock-1 ...
```

Aproximadamente 13.5 ms despues, tras fallar la conexion, el release exacto
leyo la reserva y ejecuto:

```text
DEL  reservation:f565f98d...
ZREM backend-capacity:backend:skyblock-1 f565f98d...
```

Estado final:

- el jugador permanecio en `lobby-1`;
- presence continuo en `lobby-1`;
- occupancy de `skyblock-1 = 0`;
- no quedaron keys `backend-capacity:*`;
- al no existir otro backend SKYBLOCK, el intento termino con fallo controlado.

Esto confirma que `FAILED` no inicia un retry alternativo antes de demostrar el
release exacto de la reserva del intento fallido.

### Redis outage sostenido y fencing

Con el jugador en `lobby-1`, Redis se detuvo mediante `SHUTDOWN SAVE`.
Durante la caida se ejecuto `/theosfera transfer skyblock` y el jugador no fue
movido a `skyblock-1`; no se observo fallback local de capacidad.

La caida se prolongo mas alla de la ventana de lease. El runtime registro:

```text
Estado de coordinacion distribuida: HEALTHY -> FENCED.
El Proxy fue fenced; se desconectaran 1 jugadores para evitar autoridad distribuida obsoleta.
```

El cliente fue desconectado de forma controlada con el mensaje:

```text
Este Proxy perdio su autoridad distribuida. Reconecta en unos momentos.
```

Despues de restaurar Redis, la membership, session y player-presence antiguas
ya habian expirado. El sorted set de `backend-presence:lobby-1` conservaba un
miembro stale por score, como estaba previsto. El pruning autoritativo con
`Redis TIME` + `ZREMRANGEBYSCORE -inf now` elimino exactamente un miembro y
dejó occupancy `0`.

No se afirma que en esta prueba se haya observado directamente una respuesta
terminal especifica `COORDINATION_UNAVAILABLE` del handler de transferencia.
La evidencia runtime demostrada es mas amplia: con Redis no autoritativo no se
realizo la transferencia, no hubo fallback local y una perdida sostenida de
autoridad termino en fencing y desconexion controlada.

### Multi-proxy last-slot: cero overcommit

Para validar contencion global real se configuro temporalmente en ambos
proxies:

```properties
skyblock-1=SKYBLOCK,1,80
```

Dos jugadores autenticados quedaron simultaneamente en `lobby-1`:

- jugador A propiedad de `proxy-1`;
- jugador B propiedad de `proxy-2`;
- `lobby-1` global `ZCARD = 2`;
- `skyblock-1` global `ZCARD = 0`;
- cero reservations iniciales.

Los dos procesos enviaron casi consecutivamente
`/theosfera transfer skyblock`.

El primer request:

```text
2da414e7-ec6f-46f2-b014-2272406004a0
```

obtuvo:

```text
HSET reservation:2da414e7...
PEXPIRE ... 20000
ZADD backend-capacity:backend:skyblock-1 2da414e7...
```

El segundo request:

```text
11f14213-d08f-44a0-9da4-9f8702653d0a
```

alcanzo el mismo Lua autoritativo y ejecuto pruning, `EXISTS`, `ZCARD` de
occupancy y `ZCARD` de reservations, pero no produjo `HSET`, `PEXPIRE` ni
`ZADD` para una segunda reserva.

En ese instante el primer jugador todavia no habia publicado presencia en
Skyblock, por lo que la proteccion contra overcommit dependio directamente de
contar la reserva vigente:

```text
occupied + reserved = 0 + 1 = 1
capacity = 1
```

Posteriormente el ganador publico presencia en `skyblock-1` y su reserva fue
liberada explicitamente mediante `DEL + ZREM`.

Estado final observado:

```text
lobby-1 occupancy    = 1
skyblock-1 occupancy = 1
backend-capacity:*   = vacio
```

El jugador de `proxy-1` quedo en `skyblock-1` y el jugador de `proxy-2`
permanecio en `lobby-1`. Nunca se observo occupancy `2` en un backend con
capacidad configurada `1`.

Esta prueba confirma que dos procesos Proxy independientes consumen una unica
capacidad global atomica y que una reserva in-flight evita el race de ultima
plaza antes de que exista presencia del destino.

### Recuperacion y restauracion del entorno

Despues del outage se reiniciaron ambos proxies y adquirieron nuevas
incarnations/fencing. Tras la restauracion final de la configuracion runtime se
observaron memberships activas con fencing `15` y `16`, TTL positivos y cero
keys residuales `backend-capacity:*`.

La capacidad experimental se restauro en ambos archivos runtime a:

```properties
skyblock-1=SKYBLOCK,200,80
```

Durante esa restauracion, Windows PowerShell `Set-Content -Encoding utf8`
introdujo un BOM UTF-8 al inicio de `backends.properties`; el loader fallo
cerrado al interpretar `U+FEFF#` como una entrada. El incidente fue exclusivo
del archivo runtime de desarrollo y no un defecto del loader. Ambos archivos
se reescribieron como UTF-8 sin BOM y los proxies arrancaron correctamente.

Al terminar la validacion:

- Redis estaba saludable;
- `proxy-1` y `proxy-2` tenian memberships nuevas y renovables;
- no habia reservations de capacidad residuales;
- `skyblock-1` habia vuelto a capacidad `200` en ambos proxies;
- la network podia apagarse sin conservar estado de prueba pendiente.

### Decision de superficie de transferencia

Decision arquitectonica aprobada durante este checkpoint:

- el comando `/server` de Velocity no sera una ruta valida de transferencia en
  Theosfera;
- no existira excepcion de uso para staff;
- las transferencias explicitas deben pasar por la superficie de Theosfera,
  actualmente `/theosfera transfer ...`, para no saltarse routing, health,
  policy, capacity, fencing ni coordinacion distribuida;
- este bloqueo de `/server` es hardening futuro aprobado y no fue implementado
  dentro de esta rama.

### Estado actual y punto exacto de reanudacion

`TRANSFER_REQUEST` ya es un consumer productivo de capacidad Redis y quedo
validado automatica y operacionalmente con success, failure, outage/fencing y
contencion multi-proxy.

Todavia permanecen en la ruta local de capacidad:

- `LobbyTransferService`, que respalda `/hub` y `/lobby`;
- el failover ante kicks de backends.

`BackendCapacityReservationRegistry` sigue siendo necesario para esos consumers
legacy; no debe confundirse con fallback de `TRANSFER_REQUEST`.

Punto exacto de reanudacion:

1. migrar el siguiente consumer productivo, empezando por
   `LobbyTransferService` / `/hub` / `/lobby`, hacia allocation y reservation
   distribuidas sin fallback local silencioso;
2. preservar `PlayerSessionLease` exacto, fencing, release exact-match,
   semantics de retry y `TIMED_OUT` terminal;
3. despues revisar/migrar la capacidad usada por el failover ante kicks sin
   debilitar su politica `RESOLVED`-only y fail-closed;
4. implementar posteriormente el hardening que elimina `/server` de Velocity
   como bypass de transferencias;
5. mantener distributed transfer coordination y distributed backend bootstrap
   como fronteras independientes hasta su milestone explicito;
6. no introducir parties, friends ni squads en este incremento.
