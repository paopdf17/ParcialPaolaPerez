# Cambios realizados – Jornadas del Sur

## Etapa 0 · Compilación

- **Problema:** `ServicioLocal.kt` usa `toMediaType` / `toResponseBody`, que son del API de OkHttp 4. OkHttp no estaba declarado y llegaba de forma transitiva por Retrofit en una versión sin ese API (error `Unresolved reference: Companion`).
- **Riesgo:** el proyecto no compila.
- **Cambio:** `app/build.gradle.kts`: se agrega `implementation("com.squareup.okhttp3:okhttp:4.12.0")`. OkHttp es la capa de transporte y Retrofit solo la usa (Retrofit, §7.2).
- **Verificación:** Gradle Sync + Rebuild: compila.

## Etapa 1 · Datos: marcas, retiradas y edición descargada (pedido 42)

- **Problema:**
  1. `OnConflictStrategy.REPLACE` con `Charla(favorita = false)` pisaba la marca de cada charla en cada sincronización.
  2. `sincronizar()` solo insertaba: nunca borraba las charlas retiradas de la edición (la 11 en v3, todo en v4).
  3. No existía forma de saber si ya se había descargado una edición (una v4 vacía era indistinguible de "nunca descargó").
- **Riesgo:** pérdida de datos del usuario , agenda con charlas que ya no existen y lista vacía ambigua.
- **Cambio:** `data/Programa.kt`, `data/AgendaRepository.kt`, `Servicios.kt`.
  - `@Transaction reemplazarPrograma(fichas)`: lee las marcas **al momento de escribir** (se respeta una marca hecha durante la petición), borra lo que no vino (`borrarTodas()` si la edición viene vacía), guarda conservando la marca y registra la edición. Una charla retirada que vuelve, vuelve sin marca.
  - Nueva entidad `Edicion` (su existencia = "ya hubo una edición", aunque venga vacía) y `hayEdicion()` como `Flow`.
  - Base en `version = 2` con `Migration(1, 2)` que crea la tabla y registra la edición si ya había charlas guardadas. **No se usa `fallbackToDestructiveMigration`**: se conservan los datos del usuario.
- **Concepto:** REPLACE borra e inserta y pisa datos; escrituras relacionadas en una transacción ; cambiar el esquema exige subir la versión y registrar migración ; Room como fuente de verdad .
- **Verificación :** v1 → marcar charlas (incluida la 11) → v2 + Actualizar: la 11 cambia a «Compose en producción avanzada» / «Sala Puerto» y conserva la marca → v3: la 11 desaparece de catálogo y agenda, entra la 90 y las otras marcas siguen → volver a v2: la 11 regresa sin marca.
  Además, con la demora larga activada: una marca agregada (o quitada) en una charla vigente mientras corre la petición se respeta cuando llega la respuesta.

## Etapa 2 · Una sola instancia de servicios (pedido 44, base)

- **Problema:** `ProgramaModel` hacía `Servicios(app)`: cada ViewModel nuevo abría otra Room, otro Retrofit/OkHttp y otro `ServicioLocal` (el contador de la Mesa volvía a 0 sin morir el proceso).
- **Riesgo:** varias instancias de base y de cliente de red (anti-patrón de Retrofit), contador incorrecto e imposibilidad de limitar la carga automática a una por proceso.
- **Cambio:** nuevo `JornadasApp.kt` (`Application` con `servicios by lazy`), `AndroidManifest.xml` (`android:name=".JornadasApp"`), `ui/ProgramaModel.kt` (toma `servicios` de la `Application`).
- **Concepto:** una única instancia de Room y de Retrofit/OkHttp ; dependencias en `Application` o contenedor explícito ; `Application` como contexto, nunca una Activity .
- **Verificación :** salir con Back y reabrir con el proceso vivo: el contador de la Mesa continúa, no vuelve a 0.

## Etapa 3 · Control de descargas (pedido 44)

- **Problema:** había dos disparadores automáticos: `MainActivity.onStart()` (se repite al rotar y al volver del fondo) y `LaunchedEffect(Unit)` en `Catalogo` (corre cada vez que el composable entra a la composición: rotar, abrir una ficha, cambiar de pestaña). `recargar()` no tenía guarda: dos toques lanzaban dos peticiones.
- **Riesgo:** peticiones innecesarias (red, batería, servidor), UI bloqueada por cargas repetidas y contador distinto del esperado.
- **Cambio:** `Servicios.kt` (`reclamarCargaAutomatica()`, verdadero una sola vez por proceso), `ui/ProgramaModel.kt` (`init` dispara la carga automática solo si la reclama; `recargar()` usa `cargando.compareAndSet(false, true)` y ignora toques mientras hay una petición en curso), `MainActivity.kt` (se elimina `onStart`), `ui/Catalogo.kt` (se elimina el `LaunchedEffect`; **Actualizar** y **Reintentar** se deshabilitan mientras carga; los filtros siguen editables).
- **Concepto:** el ViewModel es dueño de la operación y la UI solo envía la acción (UDF, §3.3); `onStart` se repite al rotar ; `LaunchedEffect(Unit)` corre al entrar a la composición .
- **Verificación :** con el proceso recién iniciado, abrir (+1); rotar, entrar y salir de una ficha y cambiar de pestaña no suman; dos toques seguidos en Actualizar suman uno.

## Etapa 4 · Modo sin señal (pedido 41)

- **Problema:** `Catalogo` ocultaba la lista cuando había error; `aviso: String` y `cargando: Boolean` eran flags sueltos; `stateIn(..., emptyList())` confundía "todavía no cargó" con "sin charlas"; no se distinguía una v4 vacía de "nunca descargó".
- **Riesgo:** el usuario sin señal perdía de vista datos que sí tenía; un error se veía como lista vacía; una edición vacía válida no se reconocía.
- **Cambio:** `ui/ProgramaModel.kt` (`sealed interface ProgramaEstado`: `Cargando`, `SinEdicion`, `Disponible(charlas, usandoGuardados)`, armado con `combine` + `stateIn(WhileSubscribed(5_000))`), `ui/Catalogo.kt` (aviso «Sin conexión: se están usando datos guardados.» con la lista visible; «Sin charlas programadas.»; «Ninguna charla coincide con los filtros.»; reintento solo sin descarga previa), `ui/Portada.kt` (mensaje si la ficha abierta ya no existe), `MainActivity.kt` (la bandera `servicio_disponible` se aplica sobre `servicios.mesa`, antes de que arranque la carga automática, para evitar una condición de carrera).
- **Concepto:** fallo remoto con datos locales previos = información + aviso, con caché visible y reintento, sin loading infinito ni error convertido en lista vacía ; `sealed interface` en vez de booleanos sueltos ; Repository emite `Flow`, ViewModel expone `StateFlow` .
- **Verificación :**
  - Sin descarga previa (`pm clear` + `--ez servicio_disponible false`): se muestra el mensaje con **Reintentar**; al prender el servicio y reintentar, carga el programa.
  - Con edición previa y sin señal: la lista y las marcas se conservan, con el aviso de datos guardados.
  - v4 exitosa: «Sin charlas programadas.»; al reabrir sin señal sigue siendo una edición válida.

## Etapa 5 · Volver al punto de lectura (pedido 43)

- **Problema:** `tema` era un `remember`: se perdía al rotar y al abrir una ficha (el catálogo sale de la composición). `dia` vivía en el ViewModel pero no sobrevivía a que Android matara el proceso.
- **Riesgo:** el usuario vuelve de una ficha o de segundo plano y pierde el punto de lectura.
- **Cambio:** `ui/ProgramaModel.kt` (recibe `SavedStateHandle`; `dia` y `tema` con `getStateFlow`, `elegirDia()` / `elegirTema()`), `ui/Catalogo.kt` (selectores leen y escriben en el ViewModel), `ui/Portada.kt` (el mensaje de charla retirada solo aparece si tampoco figura en la lista de `estado`, para no parpadear al recuperar la ficha). `detalle` y `seccion` ya usaban `rememberSaveable`.
- **Concepto:** dónde vive cada estado: `remember` solo recomposiciones, `rememberSaveable` giros, ViewModel búsqueda y filtros ; el ViewModel no sobrevive a process death y `SavedStateHandle` guarda valores pequeños .
- **Verificación:**
  - Día 2 + Datos → abrir «Cachés para viajeros» → Volver → rotar: siguen los dos resultados.
  - Con la ficha abierta, app al fondo, `adb shell am kill ar.jornadas` y regreso desde Recientes: vuelve la ficha y, al volver, siguen ambos filtros.
  - Tarea nueva (cerrar desde Recientes y abrir desde el ícono): arranca sin filtros.
