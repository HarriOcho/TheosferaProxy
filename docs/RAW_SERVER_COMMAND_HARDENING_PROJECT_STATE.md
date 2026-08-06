# Raw Velocity `/server` Hardening — Project State Delta

Este documento complementa `PROJECT_STATE.md` y el delta anterior de Lobby
Instance Switching para registrar el cierre del milestone de hardening del raw
Velocity `/server`.

La evidencia runtime autoritativa está en:

```text
docs/RAW_SERVER_COMMAND_HARDENING_RUNTIME_CHECKPOINT.md
```

## Estado vigente

El milestone que `docs/LOBBY_INSTANCE_SWITCHING_PROJECT_STATE.md` dejó como
siguiente paso ya no está pendiente.

`/server` dejó de ser una superficie válida de movimiento de jugadores.

Garantías vigentes:

- el alias raw `server` de Velocity se retira del `CommandManager`;
- un `CommandExecuteEvent` guard deniega el root token `server` para jugadores;
- no existe excepción especial para staff/permisos elevados;
- mixed case y whitespace no evaden el guard;
- un comando reescrito por un listener anterior hacia `server ...` también queda
  denegado;
- fuentes no-player como consola no son bloqueadas por este guard;
- `/lobby`, `/hub`, `/lobby switch`, `/hub switch` y
  `/theosfera transfer ...` conservan su semántica productiva;
- el hardening se instala antes de la adquisición de membership Redis y de la
  superficie operativa distribuida;
- el raw command bloqueado no crea reservas de capacidad;
- la matriz runtime terminó con `backend-capacity:*` vacío.

## Base y HEAD funcional

```text
base main: 0b8d656326df18ce4b407b9357fcc87dedaffe33
functional HEAD before docs: 67dff13e27712842572ee4c982109da65d9cf35f
```

Antes de documentar:

```text
3 commits ahead de main
0 commits behind de main
```

## Gates

```text
RawServerCommandHardeningTest -> BUILD SUCCESSFUL
full test suite              -> BUILD SUCCESSFUL
clean build                  -> BUILD SUCCESSFUL
git diff --check             -> clean
working tree                 -> clean
```

## Runtime

```text
/server lobby-2              -> blocked                           PASS
/server                      -> blocked                           PASS
/SeRvEr lobby-2              -> blocked                           PASS
/lobby switch                -> official route works              PASS
/hub switch                  -> official route works              PASS
/theosfera transfer skyblock -> official route works              PASS
/lobby from Skyblock         -> official route works              PASS
backend-capacity:*           -> empty                              PASS
```

El primer intento manual que permitió `/server lobby-2` ocurrió con el JAR del
milestone anterior todavía desplegado. La discrepancia se demostró mediante
hashes y desapareció al desplegar el artefacto correcto; no fue un defecto del
hardening validado.

## Artefacto congelado

```text
TheosferaProxy-0.1.0-SNAPSHOT.jar
Length: 8,602,659 bytes
SHA-256: E7C71FE13FF5D9A0B8A03249D24DA80E98C37AE310FD1312520599CAAA0BCFCC
```

El hash coincide entre `build/libs`, `proxy-1` y `proxy-2`.

## Fronteras preservadas

Este milestone no cambia distributed transfer coordination, distributed backend
capacity, kick failover, backend bootstrap, health checking ni los contratos de
TheosferaProtocol.

La dependencia conocida de ciertas señales Plugin Messaging respecto a un player
carrier sigue siendo una frontera separada.

## Punto exacto de reanudación

Después de fusionar este milestone no se declara automáticamente un nuevo
milestone funcional. El siguiente paso debe seleccionarse explícitamente a partir
del estado consolidado de `main`, sin mezclar de forma accidental control/health,
bootstrap o nuevas superficies de gameplay con este hardening ya cerrado.
