# TheosferaProxy — Instrucciones para agentes

## Identidad

TheosferaProxy es el plugin Velocity y coordinador global de la network
Theosfera.

- Propietario: HarriOcho.
- Plataforma: Velocity.
- Java: 21.
- Build: Gradle Kotlin DSL.
- API actual: Velocity 3.5.0-SNAPSHOT.
- Package raíz: `com.theosfera.proxy`.

## Propósito

Este componente coordina estado y operaciones globales:

- presencia;
- servidor y modalidad actual;
- sesiones;
- movimientos entre servidores;
- comunicación Core–Proxy;
- amigos;
- parties;
- escuadrones;
- eventos distribuidos.

No introducir lógica de Bukkit/Paper, mundos, inventarios, entidades,
misiones específicas o mecánicas de una modalidad.

## Arquitectura

Mantener la clase `TheosferaProxy` enfocada en ciclo de vida y
composición.

- Preferir inyección por constructor.
- Evitar estado global mutable.
- Separar servicios, modelos, almacenamiento, mensajería e
  integraciones.
- No crear god classes.
- Validar operaciones globales en el proxy.
- No confiar ciegamente en mensajes recibidos desde servidores Paper.

## Ciclo de vida de Velocity

El constructor pertenece a la fase de construcción.

No registrar listeners, comandos, tareas o servicios desde el
constructor.

Usar `ProxyInitializeEvent` para inicialización y
`ProxyShutdownEvent` para liberar recursos.

Cerrar conexiones, pools, suscripciones y tareas durante el apagado.

## Concurrencia

No bloquear hilos de Velocity con:

- consultas de base de datos;
- operaciones Redis lentas;
- acceso de red;
- lectura o escritura pesada.

Definir explícitamente qué operaciones son asíncronas y dónde se
publican sus resultados. Evitar condiciones de carrera, especialmente
en parties, presencia y transferencias.

## Auth y seguridad

Auth es un estado restringido.

Antes de confirmar autenticación:

- no exponer perfiles completos;
- no habilitar comandos sociales;
- no permitir movimientos de party;
- no confiar en la identidad global de la sesión.

Toda instrucción de traslado debe validar destino, estado, permisos,
restricciones y autenticación.

Nunca registrar secretos, tokens, contraseñas, claves privadas,
direcciones internas innecesarias ni datos sensibles de jugadores.

## Persistencia

La base de datos central conserva datos permanentes.

Redis coordina estado temporal, presencia, eventos e invalidación.

Un fallo de Redis no debe provocar pérdida de perfiles o progreso.

No informar éxito cuando una escritura persistente falló.

## Compatibilidad

TheosferaProxy y TheosferaCore deben comunicarse mediante contratos
versionados.

No compartir directamente clases dependientes de Paper o Velocity.

Los cambios incompatibles del protocolo requieren versión, migración y
documentación.

## Flujo Git

No trabajar directamente sobre `main`.

Usar ramas:

- `feature/`
- `fix/`
- `refactor/`
- `docs/`
- `chore/`
- `test/`

Antes de completar un cambio:

1. ejecutar `git diff --check`;
2. ejecutar el build de Gradle;
3. revisar el diff completo;
4. probar runtime cuando corresponda;
5. documentar riesgos y pendientes.

Un build exitoso no sustituye pruebas reales del proxy.

## Estado inicial

La fundación actual incluye:

- Java 21;
- Velocity API;
- metadata mediante `@Plugin`;
- inyección de `ProxyServer`, logger y directorio de datos;
- inicialización mediante `ProxyInitializeEvent`;
- apagado mediante `ProxyShutdownEvent`;
- GitHub Actions.

No asumir que Redis, base de datos, presencia o comunicación Core–Proxy
ya están implementados.
