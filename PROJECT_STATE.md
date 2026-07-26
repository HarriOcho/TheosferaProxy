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

Las sesiones autenticadas están implementadas en memoria.

Componentes:

- `AuthenticatedPlayerSession`;
- `PlayerSessionRegistrationResult`;
- `AuthenticatedPlayerSessionRegistry`;
- `PlayerAuthenticatedMessageHandler`.

Flujo:

1. `auth-1` autentica al jugador;
2. envía `PLAYER_AUTHENTICATED`;
3. el authorizer confirma que el origen registrado es `AUTH`;
4. el handler registra la sesión global.

La sesión contiene:

- UUID del jugador;
- nombre validado;
- instante de autenticación.

El registro es concurrente y distingue:

- `REGISTERED`;
- `ALREADY_REGISTERED`;
- `CONFLICT`.

Un conflicto no reemplaza silenciosamente la sesión existente.

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

Orden de limpieza:

1. eliminar transferencia pendiente;
2. eliminar presencia del backend;
3. eliminar sesión autenticada.

El listener:

- se registra durante `ProxyInitializeEvent`;
- se desregistra durante `ProxyShutdownEvent`;
- evita mantener sesiones o presencias fantasma.

Durante el apagado también se limpian:

1. transferencias pendientes;
2. presencias;
3. sesiones;
4. identidades de backends;
5. comprobaciones de salud pendientes;
6. reservas temporales de bootstrap y capacidad.

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

Validación automatizada del checkpoint actual:

```powershell
.\gradlew.bat clean test --no-daemon
.\gradlew.bat clean build --no-daemon
```

Resultado:

```text
BUILD SUCCESSFUL
416 tests, 0 failures, 0 errors, 0 skipped
```

El conteo actual fue calculado desde los XML de `build/test-results/test`.

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
- Alternate Backend Transfer Retry.

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

Estado Git del checkpoint actual de balanceo y retry:

- `main` sincronizada con `origin/main` en `9d0ea02`;
- PR `#38` fusionado en `main`;
- rama documental actual:
  `docs/backend-load-balancing-runtime-checkpoint`;
- árbol limpio antes de editar `PROJECT_STATE.md`.

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
- el estado de salud, reservas, sesiones, presencia y transferencias sigue
  siendo local al proceso de Proxy;
- no existe Redis ni coordinación entre múltiples proxies;
- el inventario observado inicialmente en `lobby-2` se debía a que `lobby-2`
  fue clonado desde `lobby-1`; no constituye sincronización cross-server y no
  debe presentarse como evidencia del Proxy.

Todavía no existen:

- base de datos;
- Redis;
- recuperación tras reinicio;
- replicación entre múltiples proxies;
- perfiles persistentes;
- amigos;
- parties;
- escuadrones;
- invitaciones;
- permisos;
- localización propia.

La base de datos será la fuente permanente.

Redis coordinará estado temporal y eventos cuando sea introducido.

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
retry alternativo de destinos y el failover fail-closed ante kicks de backends
para jugadores autenticados.

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

Limitaciones actuales:

- el estado continúa siendo local al proceso;
- no existe Redis ni coordinación entre múltiples proxies;
- la distribución proporcional no fue validada directamente en runtime con
  tres jugadores simultáneos;
- `TIMED_OUT` terminal no fue provocado deliberadamente en runtime.

Trabajo futuro, sin implementar todavía:

- persistencia o coordinación distribuida del estado temporal;
- observabilidad operacional de salud, reservas y transferencias;
- modo mantenimiento.

La selección de modalidades pertenece a TheosferaLobby, no a
TheosferaProxy.

Siguiente hito técnico recomendado:

- acordar si el próximo hito será diseñar una capa de coordinación global
  distribuida para múltiples proxies o reforzar observabilidad y operación
  sobre salud, reservas y transferencias.

Redis y persistencia temporal siguen siendo decisiones futuras, pero no
son el siguiente paso inmediato de este checkpoint.

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
Health checking, capacidad, preferencia, selección proporcional y retry
alternativo están implementados y cubiertos por pruebas automatizadas. El
retry alternativo Auth→Lobby y `/hub` fue además validado en runtime. La
distribución proporcional entre dos Lobbies activos continúa pendiente de
una prueba runtime con tres jugadores simultáneos, y `TIMED_OUT` terminal
no fue provocado deliberadamente en runtime. El siguiente incremento
requiere acordar primero si se priorizará coordinación global distribuida u
observabilidad operacional.
