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

## 14. Limpieza por desconexión

`PlayerDisconnectListener` escucha `DisconnectEvent`.

Orden de limpieza:

1. eliminar presencia del backend;
2. eliminar sesión autenticada.

El listener:

- se registra durante `ProxyInitializeEvent`;
- se desregistra durante `ProxyShutdownEvent`;
- evita mantener sesiones o presencias fantasma.

Durante el apagado también se limpian:

1. presencias;
2. sesiones;
3. identidades de backends.

## 15. Pruebas confirmadas

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
- limpieza por desconexión.

Flujos integrales confirmados:

```text
BACKEND_HELLO → BACKEND_HELLO_ACK
PING → PONG
auth-1 → PLAYER_AUTHENTICATED
lobby-1 → PLAYER_SERVER_READY
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

## 16. Prueba runtime confirmada

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

## 17. Git y ramas fusionadas

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
- Player Sessions.

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

## 18. Estado transitorio y persistencia

Actualmente son únicamente memoria local del proceso:

- identidades de backends;
- sesiones autenticadas;
- presencia de jugadores.

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

## 19. Restricciones arquitectónicas

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

## 20. Punto exacto de reanudación

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

El handshake, heartbeat, autenticación, presencia y desconexión ya están
implementados.

El siguiente bloque es la coordinación de transferencias de jugadores.

Contratos disponibles en TheosferaProtocol:

- `TransferRequestPayload`;
- `TransferResultPayload`;
- `TransferResultStatus`;
- `TRANSFER_REQUEST`;
- `TRANSFER_RESULT`.

La siguiente fase debe definir e implementar:

1. validación de sesión autenticada;
2. validación del backend de origen;
3. resolución del backend destino en Velocity;
4. protección contra solicitudes repetidas;
5. estado de transferencia pendiente;
6. ejecución mediante la API de conexión de Velocity;
7. resultado correlacionado mediante `requestId`;
8. respuesta `TRANSFER_RESULT`;
9. manejo de destino inexistente o no disponible;
10. manejo de rechazo o fallo de conexión;
11. actualización segura de presencia;
12. pruebas unitarias e integrales.

No introducir todavía Redis, base de datos, parties, amigos o perfiles.

Nombre sugerido para la siguiente rama:

```text
feature/protocol-player-transfer
```