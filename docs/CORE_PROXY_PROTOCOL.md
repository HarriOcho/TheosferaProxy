# Theosfera — Contrato Core–Proxy

## 1. Propósito

Este documento define el contrato versionado de comunicación entre
TheosferaCore, instalado en servidores Paper, y TheosferaProxy,
instalado en Velocity.

El contrato describe mensajes y validaciones. El transporte puede
evolucionar sin cambiar el significado de los mensajes.

## 2. Responsabilidades

### TheosferaCore

- conoce el contexto Paper del jugador;
- produce eventos confirmados por una modalidad;
- solicita operaciones globales al proxy;
- recibe instrucciones válidas del proxy;
- no decide movimientos globales de forma autoritativa.

### TheosferaProxy

- conoce conexiones, servidores y modalidades;
- valida identidad, autenticación y origen;
- autoriza movimientos entre servidores;
- coordina estado global;
- rechaza solicitudes inválidas;
- actúa como autoridad para operaciones de red.

## 3. Transporte inicial

El transporte inicial será plugin messaging mediante el canal:

```text
theosfera:network
```

El canal se registra en Velocity y en los servidores Paper.

Plugin messaging se utilizará inicialmente para mensajes relacionados
con jugadores conectados. No debe asumirse como transporte disponible
cuando un backend no tiene jugadores.

Los eventos que deban existir sin jugadores conectados utilizarán en el
futuro Redis u otro transporte de servidor a servidor.

## 4. Evolución de transporte

Los servicios no deben depender directamente de Velocity
`PluginMessageEvent` ni de la API de mensajería Bukkit.

Se prevé una abstracción conceptual:

```text
NetworkTransport
├── PluginMessageTransport
└── RedisTransport
```

El transporte entrega y recibe envelopes. Los handlers consumen
mensajes del protocolo, no objetos específicos de plataforma.

## 5. Envelope

Cada mensaje usa un envelope JSON UTF-8.

Campos obligatorios:

```json
{
  "protocolVersion": 1,
  "type": "BACKEND_HELLO",
  "requestId": "UUID",
  "timestamp": 1783730000000,
  "source": "skyblock-01",
  "playerId": "UUID",
  "payload": {}
}
```

### Campos

- `protocolVersion`: versión mayor del protocolo.
- `type`: tipo de mensaje registrado.
- `requestId`: UUID para correlación; puede ser nulo en eventos.
- `timestamp`: epoch milliseconds del emisor.
- `source`: ID lógico configurado del servidor emisor.
- `playerId`: UUID del jugador cuando el mensaje es player-scoped.
- `payload`: objeto específico del tipo.

No incluir clases Java serializadas, nombres internos de packages,
stack traces, secretos ni objetos dependientes de Paper o Velocity.

## 6. Versión

Versión inicial:

```text
protocolVersion = 1
```

Reglas:

- agregar un campo opcional compatible no incrementa la versión mayor;
- eliminar o reinterpretar campos requiere nueva versión mayor;
- un receptor debe rechazar versiones mayores desconocidas;
- una versión incompatible devuelve `PROTOCOL_UNSUPPORTED` cuando sea
  posible;
- los mensajes desconocidos se rechazan sin ejecutar fallback;
- nunca interpretar un tipo desconocido como otro tipo.

## 7. Límites

- tamaño máximo del envelope: 32 KiB;
- profundidad JSON limitada;
- cantidad de campos limitada por esquema;
- strings con longitudes máximas por tipo;
- payload vacío cuando el mensaje no requiere datos;
- timestamps fuera de una tolerancia razonable se rechazan o registran;
- nunca aceptar UUIDs malformados.

No usar plugin messaging para archivos, inventarios completos, blobs,
bases de datos o grandes lotes.

## 8. Seguridad de canal

Al recibir `PluginMessageEvent` en Velocity:

1. comprobar el identificador del canal;
2. marcar inmediatamente el evento como `handled()`;
3. comprobar que el origen sea el tipo esperado;
4. validar tamaño;
5. decodificar;
6. validar envelope;
7. validar tipo y payload;
8. validar identidad y autorización;
9. ejecutar.

No reenviar mensajes del canal Theosfera.

Esta regla evita que clientes participen o suplanten al proxy.

## 9. Validación de origen

### Backend hacia Proxy

Aceptar únicamente mensajes cuyo origen sea `ServerConnection`.

Validar:

- que el backend esté registrado;
- que el ID `source` coincida con la conexión real;
- que la modalidad configurada corresponda al backend;
- que el jugador portador sea coherente con `playerId`;
- que el servidor tenga permiso para emitir ese tipo;
- que Auth sea el único origen permitido para confirmar autenticación.

### Proxy hacia Backend

El backend debe:

- escuchar únicamente el canal registrado;
- ejecutarse detrás de un proxy confiable;
- usar forwarding seguro;
- validar versión, tipo, jugador y estado;
- no aceptar instrucciones equivalentes desde clientes.

Plugin messaging no sustituye seguridad de red ni forwarding.

## 10. Auth

Antes de autenticación, la sesión se considera restringida.

Operaciones permitidas:

- negociación de protocolo;
- estado mínimo de sesión;
- confirmación de autenticación desde Auth;
- traslado autorizado hacia Lobby.

Operaciones prohibidas:

- party warp;
- party tpauto;
- amigos;
- escuadrones;
- perfiles completos;
- traslado hacia modalidades no autorizado;
- publicación de presencia global como jugador autenticado.

El mensaje de autenticación solo puede aceptarse desde backends
configurados con rol `AUTH`.

## 11. Solicitudes y respuestas

Las solicitudes incluyen `requestId`.

La respuesta reutiliza el mismo `requestId`.

Estados conceptuales:

- `SUCCESS`;
- `REJECTED`;
- `NOT_FOUND`;
- `UNAUTHORIZED`;
- `INVALID_STATE`;
- `PROTOCOL_UNSUPPORTED`;
- `TIMEOUT`;
- `INTERNAL_ERROR`.

El emisor mantiene solicitudes pendientes durante un tiempo limitado.

Timeout inicial recomendado:

```text
3 segundos
```

Al expirar:

- eliminar la solicitud pendiente;
- no aplicar éxito por defecto;
- informar un error seguro;
- registrar diagnóstico técnico sin datos sensibles.

Las respuestas tardías se ignoran.

## 12. Idempotencia

Las operaciones sensibles deben soportar deduplicación por
`requestId`.

Ejemplos:

- transferencias;
- creación o aceptación de invitaciones;
- cambios de liderazgo;
- operaciones de party;
- mutaciones sociales.

Reintentar una solicitud no debe ejecutar la mutación dos veces.

## 13. Tipos iniciales

### `BACKEND_HELLO`

Backend hacia Proxy.

Payload:

```json
{
  "coreVersion": "0.1.0-SNAPSHOT",
  "supportedProtocolVersions": [1],
  "serverRole": "SKYBLOCK",
  "modalityId": "skyblock"
}
```

Objetivo:

- anunciar capacidad;
- verificar configuración;
- negociar versión.

### `BACKEND_HELLO_ACK`

Proxy hacia Backend.

Payload:

```json
{
  "acceptedProtocolVersion": 1,
  "proxyVersion": "0.1.0-SNAPSHOT",
  "serverId": "skyblock-01",
  "status": "SUCCESS"
}
```

### `PLAYER_AUTHENTICATED`

Auth hacia Proxy.

Payload:

```json
{
  "authenticated": true
}
```

Restricciones:

- solo rol `AUTH`;
- UUID debe coincidir con el jugador portador;
- una confirmación duplicada debe ser idempotente.

### `PLAYER_SERVER_READY`

Backend hacia Proxy.

Payload:

```json
{
  "modalityId": "skyblock",
  "ready": true
}
```

Indica que el backend terminó de preparar al jugador.

### `TRANSFER_REQUEST`

Core hacia Proxy.

Payload:

```json
{
  "targetType": "MODALITY",
  "target": "skyblock",
  "reason": "PROFILE_RECENT"
}
```

El proxy decide la instancia física y valida el traslado.

### `TRANSFER_RESULT`

Proxy hacia Core.

Payload:

```json
{
  "status": "SUCCESS",
  "targetServer": "skyblock-01",
  "errorCode": null
}
```

Los nombres técnicos solo se usan internamente, no como información
pública del perfil.

### `PING` y `PONG`

Mensajes de diagnóstico player-scoped para validar el canal inicial.

No sustituyen un sistema completo de health checks.

## 14. Registro de tipos

Los tipos deben existir en un registro explícito.

Cada definición contiene:

- nombre;
- dirección permitida;
- si requiere jugador;
- roles autorizados;
- si es solicitud, respuesta o evento;
- esquema de payload;
- límite de tamaño;
- handler.

No usar grandes switches distribuidos en múltiples clases.

## 15. Errores

Los jugadores reciben mensajes genéricos y localizados.

La consola puede registrar:

- tipo;
- requestId;
- servidor;
- código de error;
- versión;
- causa técnica.

No registrar:

- tokens;
- secretos;
- payloads sensibles completos;
- IPs innecesarias;
- contenido privado.

Mensajes malformados se rechazan y se registran con rate limiting.

## 16. Configuración de servidores

TheosferaProxy mantendrá un mapa explícito:

```yaml
servers:
  auth-01:
    role: AUTH
    modality: null

  lobby-01:
    role: LOBBY
    modality: null

  skyblock-01:
    role: MODALITY
    modality: skyblock
```

No inferir roles únicamente por prefijos del nombre.

## 17. Redis

Redis se añadirá después de validar plugin messaging.

Usos previstos:

- eventos sin jugadores conectados;
- presencia compartida;
- invalidación de caché;
- party state;
- invitaciones;
- reconexiones;
- coordinación entre varias instancias de proxy.

Redis reutilizará los envelopes semánticos cuando corresponda, pero
utilizará nombres de canales separados y autenticación propia.

## 18. Pruebas del protocolo

### Seguridad

- mensaje del cliente en canal interno se marca handled y se rechaza;
- source incorrecto se rechaza;
- server ID falso se rechaza;
- Auth falso se rechaza;
- versión desconocida se rechaza;
- tipo desconocido se rechaza;
- payload demasiado grande se rechaza;
- UUID inválido se rechaza.

### Funcionalidad

- BACKEND_HELLO recibe ACK;
- requestId se conserva;
- timeout elimina la solicitud;
- respuesta tardía se ignora;
- duplicado no repite una mutación;
- PING recibe PONG;
- shutdown libera el canal y solicitudes.

### Compatibilidad

- Core funciona si Proxy no responde, mostrando fallo seguro;
- Proxy funciona si un backend no tiene TheosferaCore;
- una versión incompatible no apaga toda la network;
- mensajes desconocidos no rompen handlers existentes.

## 19. Primera implementación

La primera rama funcional debe limitarse a:

1. constante del canal;
2. registro y liberación del canal;
3. envelope versión 1;
4. codec JSON con límite de tamaño;
5. validación básica;
6. tipos `PING` y `PONG`;
7. protección `handled()`;
8. logs con rate limiting;
9. pruebas manuales del canal.

No implementar todavía:

- Redis;
- base de datos;
- presencia;
- perfiles;
- amigos;
- parties;
- escuadrones;
- transferencias reales;
- autenticación real.

## 20. Decisión

El protocolo Core–Proxy se diseña primero y se implementa en una fase
mínima verificable.

La autoridad global reside en TheosferaProxy.

TheosferaCore aporta contexto Paper y solicita operaciones.

Los plugins de modalidad consumen servicios; no sustituyen la autoridad
del proxy.
