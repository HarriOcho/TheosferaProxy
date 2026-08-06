# Raw Velocity `/server` Hardening — Runtime Checkpoint

## Estado

Checkpoint final del milestone `Raw Velocity /server Hardening` sobre:

```text
feature/raw-server-command-hardening
```

Base del milestone:

```text
main @ 0b8d656326df18ce4b407b9357fcc87dedaffe33
feat: add distributed lobby instance switching (#66)
```

HEAD funcional validado antes de documentar:

```text
67dff13e27712842572ee4c982109da65d9cf35f
```

Comparación confirmada antes del checkpoint:

```text
3 commits ahead de main
0 commits behind de main
```

---

## Objetivo del milestone

Eliminar el comando raw de Velocity `/server` como bypass de las superficies de
transferencia controladas por Theosfera.

La intención no es reemplazar `/server` por otra forma de seleccionar backends
físicos, sino impedir que un jugador pueda saltarse policy, health, routing,
capacity, ownership, fencing y coordinación distribuida.

Las superficies oficiales preservadas son:

```text
/lobby
/hub
/lobby switch
/hub switch
/theosfera transfer ...
```

No existe excepción especial para staff u operadores.

---

## Implementación

### `RawServerCommandHardening`

Se añadió un componente dedicado que aplica dos defensas complementarias:

1. desregistra el alias raw `server` del `CommandManager` de Velocity;
2. registra un `CommandExecuteEvent` guard que deniega el root token `server`
   cuando la fuente es un jugador.

La segunda defensa evita que el comando quede disponible como ruta de ejecución o
forwarding aunque el alias raw haya sido retirado.

El guard:

- bloquea `server` sin argumentos;
- bloquea `server <backend>`;
- compara el root token case-insensitive;
- tolera whitespace inicial y separadores whitespace;
- no confunde `serverlist` u otros comandos cuyo prefijo contiene `server`;
- inspecciona también un comando que un listener previo haya reescrito hacia
  `server ...`;
- no consulta permisos, por lo que un jugador con permisos elevados tampoco
  obtiene bypass;
- no bloquea fuentes no-player como consola.

Mensaje visible:

```text
Los cambios directos de servidor están desactivados en Theosfera.
Usa /lobby, /lobby switch o los comandos oficiales de Theosfera.
```

### Lifecycle

`TheosferaProxy` crea una única instancia del hardening.

Se instala al inicio de `ProxyInitializeEvent`, antes de adquirir membership Redis
y antes de activar la superficie operativa distribuida.

Esto mantiene el raw `/server` cerrado incluso si la coordinación distribuida no
alcanza a inicializarse correctamente.

Durante `ProxyShutdownEvent`, el listener del hardening se desregistra de forma
explícita.

---

## Cobertura automatizada

`RawServerCommandHardeningTest` cubre:

- instalación idempotente;
- retiro del alias `server` exactamente una vez;
- registro del listener exactamente una vez;
- uninstall idempotente;
- uninstall antes de install sin efecto;
- bloqueo para jugador incluso cuando `hasPermission(...)` devuelve `true`;
- mixed case y whitespace inicial;
- bloqueo de un comando reescrito por un listener anterior a `server ...`;
- no bloqueo de `serverlist`;
- no bloqueo para una fuente de consola;
- parser exacto del root token;
- preservación de `theosfera transfer skyblock` y `lobby switch` como comandos no
  raw.

Gates pre-runtime ejecutados localmente:

```text
./gradlew.bat test --tests "*RawServerCommandHardeningTest" --no-daemon
BUILD SUCCESSFUL in 1m 31s

./gradlew.bat test --no-daemon
BUILD SUCCESSFUL in 23s

./gradlew.bat clean build --no-daemon
BUILD SUCCESSFUL in 17s

git diff --check origin/main...HEAD
<sin salida>

git status
nothing to commit, working tree clean
```

---

## Incidente de despliegue detectado antes del runtime válido

El primer intento manual de `/server lobby-2` todavía permitió el movimiento.

La comparación de hashes demostró que no era un defecto del nuevo hardening: los
dos proxies seguían ejecutando el artefacto anterior correspondiente al milestone
`Lobby Instance Switching`.

Artefacto nuevo en `build/libs` durante ese diagnóstico:

```text
SHA-256: E7C71FE13FF5D9A0B8A03249D24DA80E98C37AE310FD1312520599CAAA0BCFCC
```

Artefacto antiguo todavía desplegado en `proxy-1` y `proxy-2`:

```text
SHA-256: B1B673622F38CCDB1533A3EB213EBF202E72A4AEBB26FEA4A165DF93D72DFCCC
```

Después de sustituir ambos JARs y reiniciar los proxies, el runtime válido comenzó
con el artefacto correcto.

Este intento previo no cuenta como fallo funcional del hardening.

---

## Runtime validado

### Raw `/server` bloqueado

Con el artefacto correcto desplegado, un jugador ejecutó el raw `/server` y
recibió el mensaje de bloqueo de Theosfera.

El jugador no fue movido por el comando raw.

Resultado:

```text
PASS
```

La secuencia runtime solicitada y confirmada incluyó también las variantes de
`/server` sin destino y mixed case, mientras la cobertura automatizada valida el
parsing exacto correspondiente.

### Superficies oficiales preservadas

Después del bloqueo de `/server`, se ejecutaron las rutas oficiales previstas.
La evidencia runtime confirmó:

```text
/lobby switch                -> cambio de Lobby correcto
/hub switch                  -> cambio de Lobby correcto
/theosfera transfer skyblock -> llegada a Skyblock correcta
/lobby desde Skyblock        -> regreso al Lobby correcto
```

Resultado:

```text
PASS
```

Esto demuestra que el hardening del raw command no rompe las superficies de
transferencia controladas por Theosfera.

### Capacidad Redis sin residuos

Después de los intentos bloqueados y de la matriz de transferencias oficiales se
ejecutó:

```bash
redis-cli --scan --pattern "theosfera:coordination:backend-capacity:*"
```

Resultado:

```text
<sin salida>
```

Por tanto, el comando raw bloqueado no generó reservas de capacidad ni dejó
residuos, y las transferencias oficiales completaron su lifecycle de capacidad
sin residuos persistentes.

Resultado:

```text
PASS
```

---

## Matriz final

```text
/server lobby-2              -> BLOQUEADO                         PASS
/server                      -> BLOQUEADO                         PASS
/SeRvEr lobby-2              -> BLOQUEADO                         PASS
/lobby switch                -> transferencia oficial funcional  PASS
/hub switch                  -> transferencia oficial funcional  PASS
/theosfera transfer skyblock -> transferencia oficial funcional  PASS
/lobby desde Skyblock        -> transferencia oficial funcional  PASS
backend-capacity:*           -> vacío                             PASS
```

No se conserva bypass especial para staff.

---

## Artefacto runtime congelado

Artefacto exacto validado:

```text
TheosferaProxy-0.1.0-SNAPSHOT.jar
Length: 8,602,659 bytes
SHA-256: E7C71FE13FF5D9A0B8A03249D24DA80E98C37AE310FD1312520599CAAA0BCFCC
```

El SHA-256 coincide exactamente entre:

```text
C:\Theosfera\Plugins\TheosferaProxy\build\libs\TheosferaProxy-0.1.0-SNAPSHOT.jar
C:\Theosfera\Network\dev\proxy-1\plugins\TheosferaProxy-0.1.0-SNAPSHOT.jar
C:\Theosfera\Network\dev\proxy-2\plugins\TheosferaProxy-0.1.0-SNAPSHOT.jar
```

Cualquier modificación posterior de código invalida este freeze y exige un nuevo
build/hash antes de afirmar equivalencia con el artefacto runtime validado.

---

## Límites del milestone

Este cambio cierra únicamente el bypass voluntario expuesto por raw Velocity
`/server` para jugadores.

No modifica:

- routing distribuido existente;
- selección por BackendType;
- Redis backend capacity;
- sesiones/presencia/leases;
- kick failover;
- backend bootstrap;
- health checking;
- protocolo Core–Proxy.

La limitación conocida donde ciertas señales backend mediante Plugin Messaging
pueden depender de un player carrier sigue siendo un problema separado y no debe
mezclarse con este milestone.

---

## Cierre

`Raw Velocity /server Hardening` queda funcionalmente implementado, cubierto por
pruebas automatizadas y validado en runtime con el artefacto congelado indicado.

El siguiente milestone debe seleccionarse explícitamente después de fusionar este
cambio; este checkpoint no amplía por sí mismo las fronteras de control, health o
bootstrap distribuido.
