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
- `TRANSFER_REQUEST`: únicamente `LOBBY` o `SKYBLOCK`;
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
- `TransferRequestMessageHandler`.

Flujo:

1. Lobby o Skyblock envía `TRANSFER_REQUEST`;
2. el UUID solicitado debe coincidir con el jugador propietario del
   `ServerConnection`;
3. el jugador debe poseer una sesión autenticada;
4. la presencia y conexión actual deben coincidir con el backend de
   origen;
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

Resultados del protocolo:

- `SUCCESS`;
- `REJECTED`;
- `FAILED`;
- `TIMED_OUT`.

Protecciones actuales:

- solicitudes simultáneas para el mismo jugador son rechazadas;
- conflictos de `requestId` son rechazados;
- los dos índices del registro pendiente se actualizan atómicamente;
- un resultado tardío no altera una transferencia ya retirada;
- un evento `PLAYER_SERVER_READY` adelantado del destino no es
  eliminado por el callback de la transferencia;
- fallos síncronos y asíncronos de Velocity se convierten en resultados
  controlados;
- los detalles internos de excepciones no se exponen a los backends.

La selección entre varias instancias autenticadas del mismo tipo es
determinista por nombre hasta introducir una estrategia de balanceo.


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
- flujo integral de transferencia.

Flujos integrales confirmados:

```text
BACKEND_HELLO → BACKEND_HELLO_ACK
PING → PONG
auth-1 → PLAYER_AUTHENTICATED
lobby-1 → PLAYER_SERVER_READY
lobby-1 → TRANSFER_REQUEST → skyblock-1 → TRANSFER_RESULT
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
.\gradlew.bat clean build --no-daemon
```

Resultado:

```text
BUILD SUCCESSFUL
```

## 17. Prueba runtime confirmada

TheosferaProxy fue instalado en Velocity `3.5.0-SNAPSHOT`.

Confirmado:

- carga correcta del plugin;
- carga de tres backends autorizados;
- registro de `theosfera:network`;
- inicio correcto;
- apagado correcto;
- desregistro del canal;
- ausencia de errores del plugin.

Las advertencias sobre acceso nativo, mutación reflectiva y forwarding
pertenecen al entorno o a Velocity y no impidieron la prueba.

Player information forwarding deberá configurarse y verificarse antes
de conectar backends reales.

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
- Player Transfers.

Bloques de contrato fusionados en TheosferaProtocol:

- Foundation;
- Handshake and Heartbeat Payloads;
- Player Lifecycle Payloads;
- Transfer Payloads;
- Message Registry;
- Registered Message Decoding;
- Contract Checkpoint.

Los cambios importantes se realizan mediante ramas y Pull Requests con
squash merge.

## 19. Estado transitorio y persistencia

Actualmente son únicamente memoria local del proceso:

- identidades de backends;
- sesiones autenticadas;
- presencia de jugadores;
- transferencias pendientes.

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
- Los contratos Core–Proxy deben permanecer versionados.
- Seguridad e integridad tienen prioridad sobre estética.

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

El handshake, heartbeat, autenticación, presencia, desconexión y
coordinación segura de transferencias están implementados.

Flujo de transferencia confirmado:

```text
Lobby o Skyblock
  → TRANSFER_REQUEST
  → validación de origen
  → validación de sesión y presencia
  → resolución de destino autenticado
  → registro pendiente
  → ConnectionRequest de Velocity
  → TRANSFER_RESULT correlacionado
```

Limitaciones actuales:

- el estado continúa siendo local al proceso;
- no existe Redis ni coordinación entre múltiples proxies;
- no existe selección por carga entre varias instancias;
- falta validación runtime con backends reales y TheosferaCore.

El siguiente bloque debe definirse después del checkpoint. Las opciones
arquitectónicas inmediatas son:

1. integración de transferencias desde TheosferaCore;
2. recuperación y confirmación runtime de presencia entre backends;
3. definición de persistencia temporal y Redis;
4. inicio del perfil global de jugador.

No introducir parties, amigos o escuadrones sin definir primero su
persistencia y consistencia distribuida.
