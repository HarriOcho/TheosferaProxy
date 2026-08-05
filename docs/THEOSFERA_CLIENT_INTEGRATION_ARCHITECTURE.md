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
12. parties, perfil, keybinds, misiones y HUD;
13. threat model específico del cliente;
14. pruebas de bypass y degradación segura;
15. compatibilidad cruzada de todos los plugins Theosfera.

## Recordatorio de reentrada

Cuando el roadmap llegue al diseño o implementación de TheosferaClient, esta nota debe abrirse antes de programar el flujo de entrada o cualquier UI privilegiada.

El ejemplo de pantalla de inicio aquí documentado es solo una referencia de intención; no debe asumirse como diseño final sin una revisión arquitectónica específica.
