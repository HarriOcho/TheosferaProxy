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

## 10. Heartbeat

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

## 11. Handshake seguro de backends

El handshake de backends está implementado.

Componentes:

- `BackendAuthorizationPolicy`;
- `BackendPolicyConfigLoader`;
- `BackendIdentity`;
- `BackendIdentityRegistry`;
- `BackendRegistrationResult`;
- `BackendHelloMessageHandler`;
- `BackendMessageAuthorizer`.

Archivo runtime:

```text
plugins/theosferaproxy/backends.properties
```

Configuración inicial:

```properties
auth-1=AUTH
lobby-1=LOBBY
skyblock-1=SKYBLOCK
```

El archivo se crea con valores predeterminados si no existe.

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
- solo `TransferTargetResolutionStatus.RESOLVED` es aceptable;
- `NOT_CONFIGURED`, `NOT_AUTHENTICATED` y `BOOTSTRAP_REQUIRED` fallan
  cerrados;
- los comandos no inician bootstrap intencionalmente;
- si el jugador ya está conectado al Lobby resuelto, no se crea una
  conexión;
- se reutiliza `PendingPlayerTransferRegistry` para impedir operaciones
  simultáneas;
- se utiliza `PlayerTransferExecutor` y su timeout;
- no se modifica presencia de forma anticipada;
- `PLAYER_SERVER_READY` continúa siendo la confirmación autoritativa de
  llegada;
- no existen schedulers, reintentos ni bloqueos síncronos para estos
  comandos;
- no se implementaron failover, selección de modalidades ni
  mantenimiento.

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

La selección entre varias instancias autenticadas del mismo tipo es
determinista por nombre hasta introducir una estrategia de balanceo.

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
No constituye todavía balanceo por carga, health checking periódico ni
failover multiinstancia basado en métricas de frescura.

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
4. identidades de backends.

## 16. Pruebas confirmadas

Existen pruebas para:

- registro y ciclo del canal;
- recepción segura;
- decodificación registrada;
- dispatcher y contexto;
- sender;
- heartbeat;
- política de backends;
- carga de `backends.properties`;
- registro de identidades;
- autorización por rol;
- handshake;
- sesiones autenticadas;
- presencia de jugadores;
- handlers de ciclo de jugador;
- limpieza por desconexión;
- registro de transferencias pendientes;
- resolución segura del backend destino;
- ejecución asíncrona, rechazo, fallo y timeout;
- correlación de `TRANSFER_RESULT`;
- validaciones del handler de transferencia;
- conservación segura de presencia durante carreras;
- flujo integral de transferencia;
- pruebas negativas Auth→Lobby;
- comandos públicos `/hub` y `/lobby`;
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

## 17. Prueba runtime confirmada

TheosferaProxy fue instalado en Velocity `3.5.0-SNAPSHOT` y quedaron
validados el circuito runtime real Auth→Lobby y los comandos públicos
`/hub` y `/lobby`.

Confirmado:

- carga correcta del plugin;
- carga de tres backends autorizados;
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

Último JAR desplegado de TheosferaProxy:

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

Limitación observada de identidad y frescura:

- `BackendIdentityRegistry` conserva identidades en memoria hasta apagar
  Velocity;
- actualmente `PingMessageHandler` responde `PING` con `PONG`, pero
  TheosferaCore no emite heartbeats periódicos;
- una identidad registrada históricamente no constituye por sí sola una
  prueba de salud actual;
- la indisponibilidad real queda contenida por el resultado de
  `ConnectionRequest`, que falla cerrado;
- antes de implementar failover multiinstancia o selección por carga
  debe definirse una política explícita de frescura y salud de backends;
- esto no fue un error durante la prueba: el comportamiento observado
  fue seguro y recuperable;
- no existe health checking periódico.

Topología validada:

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
- Fail-Closed Backend Kick Failover.

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
- TheosferaAuth:
  `b6ae696 Merge pull request #4 from HarriOcho/fix/auth-transfer-handoff-lifecycle`.

Los cambios importantes se realizan mediante ramas y Pull Requests con
squash merge.

Estado Git al crear este checkpoint:

- `main` sincronizada con `origin/main` en `d862c78`;
- PR `#34` fusionado en `main`;
- árbol de archivos rastreados limpio antes de crear la rama documental;
- los cuatro archivos auxiliares de diagnóstico permanecen sin rastrear y
  no forman parte del proyecto ni del checkpoint;
- ramas locales y referencias remotas obsoletas eliminadas;
- únicamente `main` permanecía como rama local antes del checkpoint;
- rama actual del checkpoint:
  `docs/failover-runtime-checkpoint`.

El failover fail-closed está cubierto por pruebas automatizadas, build local
exitoso y validación runtime específica.

Evidencia runtime confirmada con el Lobby apagado:

- un jugador autenticado conectado a un backend establecido fue sometido al
  flujo de kick;
- no existía otro destino jugable actualmente activo;
- el destino frío se descartó porque su resolución requería
  `BOOTSTRAP_REQUIRED`;
- el jugador fue desconectado de forma controlada;
- Velocity no intentó conectarlo al Lobby apagado;
- el failover no inició ni reservó el Lobby;
- el jugador no fue redirigido hacia Auth.

Artefacto validado:

- JAR: `TheosferaProxy-0.1.0-SNAPSHOT.jar`;
- tamaño: `456944` bytes;
- SHA-256:
  `9D5330AC09CD10F5E500D38DE4660F8C6BA2D6762C5C21A61B8FCE39696B7975`;
- el hash del artefacto generado en `build/libs` coincide exactamente con el
  hash del JAR instalado en la carpeta `plugins` de Velocity.

Esta evidencia es independiente de las pruebas runtime ya confirmadas de
Auth→Lobby y de los comandos `/hub` y `/lobby`.

## 19. Estado transitorio y persistencia

Actualmente son únicamente memoria local del proceso:

- identidades de backends;
- sesiones autenticadas;
- presencia de jugadores;
- transferencias pendientes.

Las identidades de backends registradas en memoria indican que un
backend completó handshake en algún momento del proceso actual. No
constituyen por sí solas una prueba de salud o frescura actual.
`PingMessageHandler` responde `PING` con `PONG`, pero TheosferaCore no
emite heartbeats periódicos todavía. La indisponibilidad actual de un
destino queda contenida por `ConnectionRequest`, que falla cerrado.

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
- Antes de implementar failover multiinstancia o selección por carga
  debe definirse una política explícita de salud y frescura de backends.
- No afirmar que existe health checking periódico hasta que sea
  implementado.

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

El handshake, la autenticación, la presencia, la desconexión y la
coordinación segura de transferencias están implementados. También está
implementado y validado en runtime el failover fail-closed ante kicks de
backends para jugadores autenticados, incluyendo el caso en que el Lobby está
apagado y no existe otro destino jugable activo. En heartbeat,
lo implementado actualmente es la respuesta de protocolo `PING`→`PONG`,
no un emisor periódico ni health checking periódico.

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
  → resolución de lobby autenticado
  → ConnectionRequest de Velocity
  → lobby-1
  → PLAYER_SERVER_READY

Jugador autenticado en skyblock-1
  → /hub o /lobby
  → validación de sesión
  → resolución exclusiva de LOBBY
  → registro pendiente
  → ConnectionRequest de Velocity
  → lobby-1
  → limpieza correlacionada
  → PLAYER_SERVER_READY
```

El circuito Auth→Lobby está operativo y validado con TheosferaAuth,
TheosferaCore y backends reales. La desconexión de `auth-1` durante el
cambio hacia `lobby-1` es parte normal del ciclo de vida, no un fallo.

Los comandos `/hub` y `/lobby` están implementados, probados y validados
en runtime real. Ambos son públicos para jugadores autenticados,
comparten el mismo comportamiento, resuelven únicamente `LOBBY`, fallan
cerrados cuando el Lobby no está disponible, no aceptan
`TransferTargetResolutionStatus.BOOTSTRAP_REQUIRED` y no reservan
bootstrap explícitamente desde el flujo del comando.

Limitaciones actuales:

- el estado continúa siendo local al proceso;
- no existe Redis ni coordinación entre múltiples proxies;
- no existe selección por carga entre varias instancias;
- no existe política explícita de salud/frescura de backends;
- no existe health checking periódico.

Trabajo futuro, sin implementar todavía:

- health checking periódico y una política completa de frescura;
- failover multiinstancia basado en salud o carga;
- modo mantenimiento.

La selección de modalidades pertenece a TheosferaLobby, no a
TheosferaProxy.

Siguiente hito técnico recomendado:

- definir e implementar health checking periódico antes de extender el
  failover hacia selección multiinstancia por salud o carga.

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
