# TheosferaProxy

Proxy y coordinador global de la network Theosfera.

## Propósito

TheosferaProxy es el componente Velocity encargado de coordinar servicios
globales entre Auth, Lobby, Skyblock y futuras modalidades.

Responsabilidades previstas:

- presencia global;
- servidor y modalidad actual;
- sesiones autenticadas;
- movimiento entre servidores;
- comunicación con TheosferaCore;
- amigos;
- parties;
- escuadrones;
- eventos distribuidos.

La lógica específica de Paper, mundos o modalidades no pertenece a este
repositorio.

## Tecnología

- Java 21
- Gradle Kotlin DSL
- Gradle Wrapper
- Velocity API 3.5.0-SNAPSHOT
- GitHub Actions
- Gradle Configuration Cache

## Construcción

### Windows PowerShell

```powershell
.\gradlew.bat clean build --no-daemon
```

### Linux o macOS

```bash
./gradlew clean build --no-daemon
```

El JAR se genera en:

```text
build/libs/
```

## Flujo de desarrollo

1. Actualizar `main`.
2. Crear una rama enfocada.
3. Implementar un único cambio.
4. Ejecutar el build.
5. Revisar el diff.
6. Crear un commit descriptivo.
7. Hacer push.
8. Abrir un Pull Request.
9. Esperar GitHub Actions.
10. Fusionar mediante Squash and merge.

Consulta `AGENTS.md` y `CONTRIBUTING.md` antes de modificar el
proyecto.

## Estado inicial

La fundación Velocity contiene únicamente:

- metadata mediante `@Plugin`;
- inyección de `ProxyServer`, logger y directorio de datos;
- eventos de inicialización y apagado;
- build compatible con Java 21.

Redis, base de datos, comunicación Core–Proxy y sistemas sociales se
implementarán en ramas independientes.
