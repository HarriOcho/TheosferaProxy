# Contribuir a TheosferaProxy

TheosferaProxy sigue un flujo de desarrollo basado en ramas, Pull
Requests, verificación automática y pruebas proporcionales al riesgo.

## Requisitos

- Java 21
- Gradle Wrapper
- IntelliJ IDEA recomendado
- Velocity compatible con la API configurada
- Git

## Antes de comenzar

```bash
git switch main
git pull origin main
git switch -c tipo/nombre-del-cambio
```

No implementar cambios importantes directamente sobre `main`.

## Ramas

- `feature/`: funcionalidades
- `fix/`: correcciones
- `refactor/`: reorganización interna
- `docs/`: documentación
- `chore/`: mantenimiento
- `test/`: pruebas

## Commits

Formato:

```text
tipo: descripción breve
```

Ejemplos:

```text
chore: initialize Velocity foundation
feat: track global player presence
fix: reject unauthenticated server transfers
docs: define Core proxy protocol
```

## Arquitectura

- Leer `AGENTS.md` antes de modificar el proyecto.
- Mantener separadas las APIs Velocity y Paper.
- No bloquear hilos del proxy con I/O.
- Validar operaciones globales en el proxy.
- Preferir inyección por constructor.
- Evitar estado global mutable.
- Mantener contratos de comunicación versionados.
- No mezclar cambios no relacionados.

## Construcción

Windows:

```powershell
.\gradlew.bat clean build --no-daemon
```

Linux o macOS:

```bash
./gradlew clean build --no-daemon
```

Antes de crear un Pull Request:

```bash
git diff --check
```

## Pruebas

Según el cambio, verificar:

- carga y apagado del plugin;
- registro de eventos;
- comandos y permisos;
- conexiones y desconexiones;
- movimientos entre servidores;
- autenticación;
- fallos de Redis o base de datos;
- reconexiones;
- ausencia de tareas o suscripciones duplicadas.

## Pull Requests

Cada PR debe:

- tener un único propósito;
- explicar qué cambió y por qué;
- indicar cómo se verificó;
- documentar riesgos;
- mantener el proyecto compilable;
- esperar el resultado de GitHub Actions.

Fusionar preferiblemente mediante **Squash and merge**.

## Seguridad

Nunca subir:

- contraseñas;
- tokens;
- claves privadas;
- archivos `.env`;
- bases de datos;
- direcciones internas innecesarias;
- configuraciones reales de producción.

Si un secreto se publica, debe revocarse inmediatamente.
