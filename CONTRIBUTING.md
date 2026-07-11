# Contribuir a TheosferaCore

Gracias por contribuir a TheosferaCore.

Este proyecto sigue un flujo de trabajo orientado a estabilidad, revisión y trazabilidad. Incluso cuando una sola persona desarrolla el proyecto, los cambios importantes deben realizarse mediante ramas y Pull Requests.

## Requisitos del entorno

- Java 21
- Gradle Wrapper incluido en el repositorio
- IntelliJ IDEA recomendado
- Paper o Purpur 1.21.11 para pruebas
- Git configurado correctamente

## Antes de comenzar

1. Actualiza la rama principal:

   ```bash
   git switch main
   git pull origin main
   ```

2. Crea una rama nueva desde `main`:

   ```bash
   git switch -c tipo/nombre-del-cambio
   ```

3. No desarrolles funcionalidades importantes directamente sobre `main`.

## Convención de ramas

Usa nombres claros y en minúsculas.

- `feature/` para nuevas funcionalidades
- `fix/` para correcciones
- `refactor/` para reorganización interna
- `docs/` para documentación
- `chore/` para configuración y mantenimiento
- `test/` para pruebas

Ejemplos:

```text
feature/keybind-edit-menu
fix/duplicate-key-validation
refactor/menu-action-handlers
docs/update-command-guide
chore/github-project-foundation
```

## Convención de commits

Usa commits pequeños y descriptivos siguiendo este formato:

```text
tipo: descripción breve
```

Tipos recomendados:

- `feat`
- `fix`
- `refactor`
- `docs`
- `test`
- `chore`
- `build`
- `ci`

Ejemplos:

```text
feat: add keybind edit menu
fix: reject duplicate key assignments
refactor: split menu action handlers
docs: update keybind command documentation
ci: add Gradle build workflow
```

Evita mensajes genéricos como:

```text
cambios
arreglo
prueba
final
ahora sí
```

## Estilo y arquitectura

Antes de modificar el proyecto, lee `AGENTS.md`.

Principios obligatorios:

- Mantener separación de responsabilidades.
- Evitar clases excesivamente grandes.
- Preferir inyección por constructor.
- No duplicar mensajes, colores o lógica.
- Mantener protegidos los comandos administrativos.
- No ejecutar trabajo pesado innecesario en el hilo principal.
- No introducir asincronía sin una razón clara.
- Mantener compatibilidad con Java 21 y Paper/Purpur 1.21.11.
- Evitar cambios no relacionados con la tarea actual.

## Mensajes y localización

Los mensajes visibles para jugadores deben usar el sistema centralizado de mensajes.

Cuando corresponda:

- añadir texto en `lang/es.yml`;
- añadir texto equivalente en `lang/en.yml`;
- evitar mensajes hardcodeados;
- no mostrar detalles internos ni excepciones a jugadores normales.

## Permisos

Las funciones administrativas deben comprobar permisos tanto en ejecución como en tab completion.

El permiso administrativo principal actual es:

```text
theosfera.admin
```

Los usuarios sin permiso no deben recibir información que revele comandos administrativos o detalles internos del plugin.

## Configuración y persistencia

- Mantener los archivos YAML legibles.
- Validar entradas antes de guardar.
- No informar éxito si la persistencia falla.
- Conservar compatibilidad con configuraciones existentes cuando sea razonable.
- Documentar cambios incompatibles o migraciones necesarias.

## Compilación

Antes de crear un Pull Request, ejecuta:

```bash
./gradlew clean build
```

En Windows PowerShell también puede utilizarse:

```powershell
.\gradlew.bat clean build
```

El build debe finalizar correctamente antes de fusionar cambios.

## Pruebas

Según el cambio, verifica:

- carga y apagado del plugin;
- comandos y tab completion;
- permisos;
- mensajes en español e inglés;
- menús e inventarios;
- persistencia y recarga;
- comportamiento sin dependencias opcionales;
- compatibilidad con datos existentes;
- ausencia de listeners o tareas duplicadas.

## Pull Requests

Cada Pull Request debe:

- tener un propósito claro;
- contener únicamente cambios relacionados;
- explicar qué se modificó y por qué;
- indicar cómo se probó;
- mencionar riesgos o pendientes;
- mantener el proyecto compilable.

No se debe fusionar un Pull Request si:

- el build falla;
- contiene cambios ajenos a la tarea;
- rompe compatibilidad sin documentarlo;
- expone funciones administrativas;
- introduce errores conocidos sin justificación.

## Revisión y fusión

Antes de fusionar:

1. Revisa el diff completo.
2. Confirma que GitHub Actions haya terminado correctamente.
3. Comprueba que se cumplan los criterios de aceptación.
4. Realiza pruebas en servidor cuando corresponda.
5. Fusiona preferiblemente mediante `Squash and merge` para mantener un historial limpio.

## Versionado

TheosferaCore utiliza versionado semántico:

```text
MAJOR.MINOR.PATCH
```

Durante el desarrollo temprano se utilizarán versiones `0.x.x`.

- `PATCH`: correcciones compatibles.
- `MINOR`: nuevas funciones compatibles.
- `MAJOR`: cambios incompatibles.

## Seguridad

Nunca subas al repositorio:

- contraseñas;
- tokens;
- claves privadas;
- archivos `.env`;
- datos sensibles del servidor;
- direcciones privadas innecesarias;
- copias de mundos;
- bases de datos de producción;
- archivos generados del servidor.

Si un secreto se publica por error, debe revocarse inmediatamente. Eliminarlo de un commit posterior no lo invalida.
