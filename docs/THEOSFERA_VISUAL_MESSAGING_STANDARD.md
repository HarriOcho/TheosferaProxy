# Theosfera — Estándar visual de mensajería

Este documento fija la paleta oficial y la regla global de color para los
mensajes visibles al jugador producidos por plugins oficiales de Theosfera.

## Regla obligatoria

Todo mensaje visible al jugador generado por un plugin oficial de Theosfera
debe utilizar exclusivamente la paleta oficial definida en este documento.

La combinación, jerarquía y distribución de colores puede adaptarse al
contexto del mensaje, pero no deben introducirse colores arbitrarios fuera de
esta paleta.

La regla aplica, entre otros, a:

- mensajes de comandos;
- confirmaciones;
- errores y advertencias;
- estados operacionales;
- transferencias y redirecciones;
- títulos y subtítulos;
- action bars;
- mensajes de menús u otras interfaces textuales;
- cualquier otro texto visible al jugador generado por Theosfera.

Los mensajes externos que deban preservarse por razones funcionales o de
seguridad —por ejemplo, un motivo original de desconexión emitido por un
backend— pueden conservar su componente original cuando modificarlo alteraría
la semántica o eliminaría información necesaria.

## Claridad para jugadores y staff

Los mensajes normales de juego deben describir la acción, el estado o lo que
el usuario puede hacer a continuación con lenguaje claro y directo.

No deben exponer detalles internos de coordinación salvo en herramientas de
diagnóstico o administración que los requieran expresamente. En particular,
evitar en mensajes ordinarios:

- `requestId`, `attemptId`, fencing tokens u otros identificadores internos;
- nombres de clases, excepciones o componentes de infraestructura;
- enums crudos como `SKYBLOCK` cuando pueda mostrarse `Skyblock`;
- referencias a Redis, Plugin Messaging, leases o coordinación distribuida;
- mensajes que obliguen al jugador a conocer la arquitectura de Theosfera.

Los detalles técnicos deben conservarse donde sean útiles para operar o
depurar el sistema: logs, métricas, comandos administrativos de diagnóstico o
trazas controladas.

## Paleta oficial

| Nombre | Hex |
| --- | --- |
| Oro principal | `#E8B85B` |
| Oro luminoso | `#F8E798` |
| Ámbar | `#C46C19` |
| Bronce | `#8E5B29` |
| Marfil cálido | `#F2E4C5` |
| Texto secundario | `#B89A79` |
| Marrón profundo | `#3D1F10` |
| Negro de fondo | `#0B0503` |

## Criterio de uso

No existe una combinación única obligatoria para todos los mensajes. La
jerarquía visual debe elegirse según el contexto y mantener coherencia con la
identidad bíblico-futurista de Theosfera.

Como criterio general:

- `Oro principal` puede comunicar la acción o el elemento principal;
- `Oro luminoso` puede destacar nombres, destinos o información de máxima
  atención positiva;
- `Ámbar` y `Bronce` pueden aportar énfasis cálido, advertencia o contraste;
- `Marfil cálido` funciona como texto principal de alta legibilidad;
- `Texto secundario` se reserva para elementos subordinados;
- `Marrón profundo` y `Negro de fondo` pertenecen principalmente a fondos,
  sombras o composiciones donde exista contraste suficiente.

Estos criterios son orientativos. La restricción obligatoria es permanecer
dentro de la paleta oficial.

## Implementación en TheosferaProxy

TheosferaProxy centraliza estos colores en:

`com.theosfera.proxy.ui.TheosferaPalette`

Los mensajes nuevos del Proxy deben reutilizar esa clase en lugar de declarar
hexadecimales o colores `NamedTextColor` ad hoc.

Los demás plugins oficiales deben adoptar la misma paleta como fuente visual
autoritativa dentro de su propia capa de presentación, sin acoplar lógica de
plataforma a TheosferaProtocol.
