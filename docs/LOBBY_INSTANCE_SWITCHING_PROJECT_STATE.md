# Lobby Instance Switching — Project State Delta

Este documento complementa temporalmente `PROJECT_STATE.md` para el cierre del
milestone `Lobby Instance Switching` y evita reescribir el historial extenso del
archivo principal dentro de la misma rama funcional.

El estado completo y la evidencia runtime autoritativa están en:

```text
docs/LOBBY_INSTANCE_SWITCHING_RUNTIME_CHECKPOINT.md
```

## Estado vigente

El milestone aprobado en `PROJECT_STATE.md` sección 35 ya no está pendiente:
quedó implementado, cubierto por pruebas automatizadas y validado en runtime.

Superficie productiva final:

```text
/lobby switch
/hub switch
```

Garantías confirmadas:

- solo desde identidad backend autorizada de tipo `LOBBY`;
- Lobby actual excluido desde el primer intento;
- ningún nombre físico de backend seleccionable por el jugador;
- routing/preferencia/capacidad Redis existentes;
- `PlayerSessionLease` exacto y fencing;
- reservation -> connection -> presence -> handoff -> exact release;
- `NO_CAPACITY` sin overcommit;
- `TIMED_OUT` terminal;
- Redis unavailable fail-closed;
- cero fallback local silencioso;
- non-Lobby `switch` rechazado sin convertirse en `/lobby` normal;
- único Lobby elegible falla de forma controlada sin self-reconnect.

Artefacto runtime congelado:

```text
TheosferaProxy-0.1.0-SNAPSHOT.jar
Size: 8,599,980 bytes
SHA-256: B1B673622F38CCDB1533A3EB213EBF202E72A4AEBB26FEA4A165DF93D72DFCCC
```

El hash coincide con los JAR desplegados en `proxy-1` y `proxy-2`.

Matriz runtime cerrada:

```text
lobby-1 -> /lobby switch -> lobby-2             PASS
lobby-2 -> /hub switch -> lobby-1               PASS
non-Lobby -> /lobby switch -> no transfer       PASS
/lobby normal desde non-Lobby                   PASS
único Lobby elegible -> fallo controlado        PASS
destino lleno -> no overcommit                  PASS
switch success -> presencia -> cero residuos    PASS
Redis unavailable -> no transfer                PASS
Redis unavailable -> no local fallback          PASS
outage sostenido -> HEALTHY -> FENCED            PASS
FENCED -> disconnect controlado                 PASS
recuperación -> PONG + restart + scan vacío     PASS
```

## Punto exacto de reanudación

El siguiente milestone aprobado es el hardening de raw Velocity `/server`.

Objetivo:

- eliminar `/server` como bypass de transferencias Theosfera;
- no conservar excepción especial para staff;
- forzar las transferencias voluntarias a superficies controladas que respeten
  policy, health, routing, capacity, ownership, fencing y coordinación
  distribuida;
- preservar `/lobby`, `/hub`, `/lobby switch`, `/hub switch` y
  `/theosfera transfer ...` según su semántica.

Distributed transfer coordination y distributed backend bootstrap coordination
siguen siendo fronteras independientes.
