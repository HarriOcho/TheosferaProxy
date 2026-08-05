# TheosferaClient Integration Architecture — Preliminary Direction

> Estado: nota arquitectónica preliminar para no perder la dirección del proyecto.
> 
> Este documento NO define todavía el diseño final de TheosferaClient ni congela el ejemplo de pantalla de inicio. Debe revisarse formalmente cuando llegue el milestone específico de integración con TheosferaClient.

## Propósito

TheosferaClient debe ampliar la experiencia de Theosfera sin convertirse en una autoridad de seguridad ni en un requisito para jugar.

Todos los plugins de Theosfera deben diseñarse para poder integrarse con TheosferaClient cuando corresponda, manteniendo un funcionamiento completo y seguro para jugadores sin el mod.

## Principio de opcionalidad del cliente

Ninguna funcionalidad esencial de Theosfera debe depender obligatoriamente de TheosferaClient.

Cuando una sesión compatible con TheosferaClient esté presente, los servicios pueden exponer interfaces visuales, flujos de navegación y capacidades interactivas adicionales mediante contratos versionados.

La autoridad permanece siempre server-side:

- autenticación;
- permisos;
- estado de sesión;
- ownership y fencing;
- selección real de backend;
- capacidad distribuida;
- persistencia;
- parties y restricciones sociales;
- validación de modalidad;
- transferencias;
- cualquier operación sensible.

TheosferaClient puede solicitar una acción, pero nunca decidir por sí mismo que una operación está autorizada.

## Integración orientada a capacidades

Los plugins no deberían depender de una comprobación simple del tipo `clientPresent`.

La dirección preferida es un handshake versionado y orientado a capacidades, por ejemplo:

```text
CLIENT_HELLO
CLIENT_CAPABILITIES
CLIENT_SESSION_READY
CLIENT_ACTION
CLIENT_ACTION_RESULT
```

Una sesión podría anunciar capacidades como:

```text
START_SCREEN
PROFILE_UI
PARTY_UI
KEYBIND_UI
MISSION_UI
CUSTOM_HUD
SERVER_BROWSER
```

Los componentes server-side deberían preguntar si la sesión soporta una capacidad concreta, no asumir comportamiento por una versión específica del cliente.

Esto permite compatibilidad gradual entre versiones antiguas y nuevas de TheosferaClient.

## Regla de autoridad para acciones del cliente

Una acción del cliente debe expresar intención, no autoridad.

Ejemplo correcto:

```text
SELECT_GAME_MODE: SKYBLOCK
```

El Proxy recibe la intención y vuelve a validar todo lo necesario antes de decidir el destino real.

Ejemplo incorrecto:

```text
TRANSFER_TO: skyblock-2
```

TheosferaClient no debe escoger directamente un backend productivo ni saltarse el routing seguro del Proxy.

## Ejemplo exploratorio: pantalla de inicio

Este ejemplo es deliberadamente preliminar y debe rediseñarse cuando llegue el milestone correspondiente.

### Sin TheosferaClient

Jugador premium:

```text
Conexión -> autenticación/sesión segura -> Lobby
```

Jugador no premium:

```text
Conexión -> registro/login -> autenticación confirmada -> Lobby
```

### Con TheosferaClient

Jugador premium:

```text
Conexión -> autenticación/sesión segura -> pantalla de inicio
                                      -> Lobby o modalidad
```

Jugador no premium:

```text
Conexión -> registro/login -> autenticación confirmada
                             -> pantalla de inicio
                             -> Lobby o modalidad
```

La pantalla de inicio no debe significar que el cliente puede saltarse Auth, ownership, fencing o capacidad.

Puede ser una representación visual de un estado seguro de sesión, no una ruta privilegiada.

## Estado lógico tentativo

Una posible máquina de estados futura podría distinguir:

```text
PRE_AUTH
   |
AUTHENTICATED
   |
START_SCREEN
   |
TRANSFER_PENDING
   |
PLAYING
```

`START_SCREEN` sería un estado lógico de navegación o presentación, no un permiso para transferirse sin validación.

## Lobby como experiencia, no necesariamente como paso obligatorio

Con TheosferaClient podría evaluarse que el Lobby deje de ser un paso visual obligatorio para algunas sesiones.

Ejemplo tentativo:

```text
Start Screen
 |- Lobby
 |- Skyblock
 |- otras modalidades
 |- jugados recientemente
 |- Party
 `- Perfil
```

Aunque el jugador no visite visualmente el Lobby, cualquier traslado debe seguir pasando por TheosferaProxy y por las mismas garantías de routing, autoridad distribuida, capacidad y fail-closed.

## Idea exploratoria: backend de espera para START_SCREEN y AFK

Cuando llegue el milestone de TheosferaClient, debe evaluarse formalmente que un jugador situado en la pantalla de inicio no permanezca físicamente en un backend `LOBBY`, `PRE_GAME` ni en una modalidad jugable.

La dirección conceptual es separar:

```text
estado de experiencia del jugador
            !=
backend físico que mantiene la conexión
```

TheosferaClient podría mostrar una verdadera `START_SCREEN` mientras el jugador permanece conectado a un backend dedicado, extremadamente liviano y aislado de los backends donde se desarrolla la experiencia jugable.

Nombre puramente tentativo para esa clase de backend:

```text
HOLDING
```

No debe asumirse todavía que `HOLDING` será el nombre definitivo ni que necesariamente será un nuevo `BackendType`.

Conceptualmente podría soportar más de un estado lógico:

```text
HOLDING
 |- START_SCREEN
 `- AFK
```

El backend físico podría compartirse entre ambos estados, mientras que `START_SCREEN` y `AFK` seguirían siendo estados de experiencia distintos.

### Flujo tentativo con TheosferaClient

Jugador premium:

```text
Conexión
 -> sesión/autorización segura
 -> backend de espera
 -> START_SCREEN
 -> selección de Lobby o modalidad
 -> validación server-side
 -> routing/capacidad/fencing
 -> backend jugable
```

Jugador no premium:

```text
Conexión
 -> AUTH
 -> registro/login
 -> autenticación confirmada
 -> backend de espera
 -> START_SCREEN
 -> selección de Lobby o modalidad
 -> validación server-side
 -> backend jugable
```

`AUTH` continúa significando demostrar la identidad del jugador. El backend de espera significaría que el jugador ya se encuentra dentro de Theosfera, pero todavía no está participando en Lobby, Pregame o una modalidad.

### Integración tentativo con AFK

El sistema AFK planificado para `LOBBY` y `PRE_GAME` puede integrarse con esta idea.

Para una sesión compatible con TheosferaClient, una posibilidad futura es:

```text
LOBBY / PRE_GAME
      |
   timeout AFK
      |
backend de espera
      |
     AFK
      |
actividad del jugador
      |
START_SCREEN
      |
Lobby o modalidad elegida por el jugador
```

Esto permitiría que el jugador que vuelve de AFK no tenga que regresar automáticamente al Lobby si desea entrar directamente a otra modalidad.

Para jugadores sin TheosferaClient puede conservarse un flujo vanilla distinto, por ejemplo volver al backend previo o a otro backend seguro del mismo tipo. Esta diferencia de UX no debe crear diferencias de autoridad o seguridad.

### Objetivo operativo tentativo del backend de espera

Si esta idea se adopta, el backend debería tender a ser muy ligero y no actuar como una modalidad jugable. Podría limitarse a responsabilidades como:

- mantener la conexión Minecraft;
- mantener la sesión y presencia necesarias;
- transportar protocolo Theosfera;
- sostener de forma segura estados como `START_SCREEN` o `AFK`;
- evitar carga innecesaria de Lobby, Pregame o modalidades mientras el jugador no participa en ellas.

No deben fijarse todavía detalles como mundo, invisibilidad entre jugadores, límites de movimiento, plugins cargados, capacidad, escalado, número de instancias, health checking o política de bootstrap. Todo eso queda pendiente del diseño formal.

### Regla de seguridad

Estar en `START_SCREEN`, `AFK` o en un backend de espera nunca debe convertirse en un bypass.

Una acción visual como:

```text
SELECT_GAME_MODE: SKYBLOCK
```

debe seguir recorriendo la autoridad server-side normal:

```text
TheosferaClient
 -> intención
 -> TheosferaProxy
 -> sesión/ownership/fencing
 -> permisos/estado
 -> routing
 -> health
 -> capacidad distribuida
 -> backend seleccionado por el servidor
```

El cliente no debe poder indicar un backend físico ni convertir el backend de espera en una ruta privilegiada.

### Decisión aplazada deliberadamente

Antes de implementar esta parte, se debe discutir conjuntamente y decidir al menos:

1. si `START_SCREEN` y `AFK` compartirán el mismo backend físico;
2. si existirán pools separados para pantalla de inicio y AFK;
3. si se introducirá un nuevo `BackendType` o una abstracción distinta;
4. qué estado lógico autoritativo representará `START_SCREEN`;
5. qué estado lógico autoritativo representará `AFK`;
6. cómo se conserva o descarta el backend de retorno tras AFK;
7. cuál será el flujo vanilla equivalente;
8. cómo se integra Auth para jugadores premium y no premium;
9. cómo se escala y balancea el backend de espera;
10. cómo interactúa con presencia Redis, capacidad, health checking, failover y shutdown;
11. qué ocurre cuando TheosferaClient desaparece, se desconecta o cambia de capacidades en mitad de la sesión;
12. qué threat model y pruebas de bypass necesita este flujo.

Estas decisiones NO quedan cerradas por esta nota.

## Alcance transversal

La compatibilidad con TheosferaClient debe contemplarse en toda la familia de plugins cuando aplique.

### TheosferaProxy

- detección/handshake del cliente;
- capabilities por sesión;
- acciones de navegación;
- selector de modalidad;
- routing seguro;
- estado global;
- parties y transferencias;
- nunca confiar en decisiones de backend enviadas por el cliente.

### TheosferaCore

Una misma función puede tener una superficie vanilla y otra enriquecida.

Ejemplo:

```text
Sin cliente: /profile -> GUI de inventario
Con cliente: PROFILE_UI -> interfaz nativa
```

### TheosferaAuth

Puede ofrecer una interfaz visual mejorada, pero las credenciales, autenticación y transición de sesión continúan siendo verificadas server-side.

### Plugins de modalidades

Funciones como almacenamiento, misiones, perfiles, menús, progreso o HUD pueden exponer superficies adicionales cuando la sesión anuncia las capacidades correspondientes.

## Reglas que no deben romperse

1. TheosferaClient es opcional.
2. La experiencia vanilla debe seguir siendo funcional.
3. El cliente nunca es autoridad de autenticación o autorización.
4. El cliente no escoge directamente backends productivos.
5. Toda acción sensible vuelve a validarse server-side.
6. Los contratos cliente-servidor deben ser versionados.
7. La integración debe ser orientada a capacidades, no a suposiciones sobre una versión concreta.
8. Las rutas del cliente deben reutilizar las mismas garantías de seguridad que comandos, NPCs u otras superficies.
9. No crear bypasses de Auth, Redis ownership, fencing, capacidad, presencia ni routing.
10. Una UI más rica nunca debe debilitar la política fail-closed.
11. `START_SCREEN` y `AFK` son conceptos de experiencia que no deben confundirse automáticamente con un backend físico concreto.

## Trabajo futuro cuando llegue el milestone de TheosferaClient

Antes de implementar la primera UI productiva, revisar este documento y definir formalmente:

1. ubicación de los contratos en TheosferaProtocol;
2. handshake y negociación de versión;
3. capability registry por sesión;
4. ciclo de vida de conexión/desconexión del cliente;
5. estados de sesión visibles para el cliente;
6. modelo de acciones cliente -> servidor;
7. respuestas y errores versionados;
8. comportamiento sin mod y con versiones antiguas;
9. interacción con TheosferaAuth;
10. pantalla de inicio definitiva;
11. selección de modalidad y Lobby;
12. backend de espera / holding y su relación con `START_SCREEN`;
13. integración del sistema AFK de Lobby/Pregame con TheosferaClient;
14. parties, perfil, keybinds, misiones y HUD;
15. threat model específico del cliente;
16. pruebas de bypass y degradación segura;
17. compatibilidad cruzada de todos los plugins Theosfera.

## Recordatorio de reentrada

Cuando el roadmap llegue al diseño o implementación de TheosferaClient, esta nota debe abrirse antes de programar el flujo de entrada, AFK integrado con el cliente, backend de espera o cualquier UI privilegiada.

En ese momento NO se debe asumir automáticamente la solución descrita aquí. Primero hay que preguntarle al responsable del proyecto y resolver conjuntamente las decisiones aplazadas de `START_SCREEN`, AFK y backend de espera.

Los ejemplos aquí documentados son referencias de intención y exploración; no constituyen todavía un diseño final aprobado.
