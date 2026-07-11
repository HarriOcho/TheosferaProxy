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
- Versión inicial: `0.1.0-SNAPSHOT`.
- Plugin ID: `theosferaproxy`.

TheosferaProxy es el proxy y coordinador global de la network Theosfera.

## 2. Topología prevista

La primera etapa de la network contiene:

```text
Theosfera Network
├── Proxy
├── Auth
├── Lobby
└── Skyblock
```

La arquitectura debe permitir añadir nuevas modalidades sin introducir
su lógica específica dentro del proxy.

## 3. Responsabilidades previstas

TheosferaProxy coordinará:

- presencia global;
- servidor y modalidad actual;
- sesiones;
- autenticación habilitada por el flujo de Auth;
- movimientos entre servidores;
- comunicación con TheosferaCore;
- amigos;
- parties;
- escuadrones;
- invitaciones;
- eventos distribuidos.

No pertenecen al proxy:

- lógica Bukkit o Paper;
- mundos, entidades o inventarios;
- menús de inventario;
- misiones específicas;
- mecánicas de Skyblock;
- almacenamiento de ítems;
- integración directa con SuperiorSkyblock2.

## 4. Fundación Velocity

La plantilla Paper fue reemplazada por una fundación Velocity.

Cambios confirmados:

- Paper API eliminada;
- Velocity API `3.5.0-SNAPSHOT` añadida como `compileOnly`;
- annotation processor de Velocity añadido;
- proyecto renombrado a `TheosferaProxy`;
- package cambiado a `com.theosfera.proxy`;
- clase Paper eliminada;
- `plugin.yml` eliminado;
- metadata definida mediante `@Plugin`;
- JAR denominado `TheosferaProxy`;
- README, AGENTS y CONTRIBUTING adaptados.

La metadata de Velocity se genera mediante el procesador de anotaciones.
No existe un `velocity-plugin.json` mantenido manualmente.

## 5. Clase principal

Clase:

`com.theosfera.proxy.TheosferaProxy`

Dependencias inyectadas:

- `ProxyServer`;
- `Logger`;
- directorio de datos mediante `@DataDirectory`.

La clase conserva referencias mediante inyección por constructor.

No registra servicios, listeners, comandos o tareas desde el
constructor.

## 6. Ciclo de vida

Inicialización:

`ProxyInitializeEvent`

Apagado:

`ProxyShutdownEvent`

Mensajes confirmados:

```text
TheosferaProxy iniciado correctamente.
TheosferaProxy apagado correctamente.
```

El constructor pertenece únicamente a la fase de construcción.

Las operaciones que requieren la API de Velocity deben comenzar durante
o después de `ProxyInitializeEvent`.

## 7. Build confirmado

Comando utilizado:

```powershell
.\gradlew.bat build --no-daemon
```

Resultado:

```text
BUILD SUCCESSFUL in 9s
2 actionable tasks: 1 executed, 1 from cache
Configuration cache entry stored.
```

También se verificó:

```powershell
git diff --check
```

El comando no reportó errores de formato.

## 8. Prueba real confirmada

TheosferaProxy fue instalado en una instancia local de Velocity
`3.5.0-SNAPSHOT`.

Pruebas aprobadas:

- Velocity inició correctamente;
- TheosferaProxy apareció en `velocity plugins`;
- el evento de inicialización se ejecutó;
- el directorio `plugins/theosferaproxy` fue creado;
- no aparecieron errores de Bukkit, Paper o `JavaPlugin`;
- no aparecieron errores de metadata o `@Plugin`;
- el evento de apagado se ejecutó;
- no se registraron stack traces relacionados.

## 9. Advertencias conocidas

### Native access

Java mostró una advertencia de acceso nativo relacionada con Jansi.

No pertenece a TheosferaProxy y no afectó la carga del plugin.

### Player information forwarding

Velocity informó que player information forwarding está desactivado.

Este estado es aceptable para la prueba aislada.

Antes de conectar servidores backend reales se debe configurar y
verificar forwarding seguro.

### Windows y OneDrive

OneDrive o IntelliJ pueden bloquear temporalmente carpetas dentro de:

- `build`;
- referencias internas de `.git`;
- carpetas vacías de packages eliminados.

Procedimiento utilizado:

```powershell
.\gradlew.bat --stop
Remove-Item -Recurse -Force .\build
.\gradlew.bat build --no-daemon
```

Los avisos de eliminación no implican pérdida de cambios si `git status`
termina limpio.

## 10. Git y GitHub

La fundación profesional heredada incluye:

- GitHub Actions;
- plantilla de Pull Request;
- plantillas de issues;
- `.gitignore`;
- Gradle Wrapper;
- `AGENTS.md`;
- `CONTRIBUTING.md`;
- `README.md`.

Los cambios importantes deben realizarse mediante ramas y Pull Requests.

## 11. Elementos no implementados

Todavía no existen:

- comunicación Core–Proxy;
- protocolo versionado;
- plugin messaging;
- presencia global;
- mapa de servidores y modalidades;
- estado de autenticación;
- base de datos;
- Redis;
- perfiles;
- amigos;
- parties;
- escuadrones;
- comandos;
- permisos;
- configuración propia del plugin;
- sistema de mensajes o localización.

No asumir que alguno de estos sistemas está disponible.

## 12. Decisiones arquitectónicas

- Paper y Velocity permanecen separados.
- No compartir clases dependientes de plataforma.
- El proxy valida operaciones globales.
- Auth es un estado restringido.
- No permitir movimientos sociales antes de autenticar.
- La base de datos será la fuente permanente.
- Redis coordinará estado temporal y eventos.
- Un fallo de Redis no debe causar pérdida de perfiles o progreso.
- Los contratos Core–Proxy deben ser versionados.
- Seguridad e integridad tienen prioridad sobre estética.

## 13. Punto exacto de reanudación

La fundación Velocity está compilada y probada en runtime.

El siguiente paso es diseñar el contrato versionado entre
TheosferaCore y TheosferaProxy antes de implementar presencia, Redis o
sistemas sociales.

La fase de diseño debe definir:

1. responsabilidades de cada lado;
2. transporte inicial;
3. formato de mensajes;
4. versión del protocolo;
5. correlación de solicitudes y respuestas;
6. validación de origen;
7. manejo de errores y timeouts;
8. compatibilidad futura;
9. comportamiento durante Auth;
10. pruebas del canal.

No implementar parties, amigos, perfiles o escuadrones antes de disponer
de una comunicación Core–Proxy validada.

Nombre de rama sugerido para documentar el contrato:

`docs/core-proxy-protocol`
