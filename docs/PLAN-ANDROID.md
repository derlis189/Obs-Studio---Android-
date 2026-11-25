# Plan de adaptación por fases

Este es el plan de implementación, separado del código ya entregado. La fase 1 está validada; las fases 2–9 no se han iniciado. No se eliminan funciones del motor por no estar en el objetivo mínimo: se habilitarán conforme se comprueben sus dependencias y adaptadores.

## Fase 1 — entregada

Resultado: checkout oficial fijado e intacto, arquitectura descrita, 44 plugins clasificados, herramientas inventariadas y diagnóstico reproducible.

Archivos creados: `source-lock.json`, `scripts/{common,fetch-source,bootstrap-tools,audit-phase1}.ps1`, `android/preflight/`, documentación de esta carpeta, `README.md`, `.gitignore` y `COPYING`. No se ha modificado ningún archivo original de OBS.

Criterio de cierre: auditoría PASS, evidencia de cada bloqueo, comandos registrados, herramientas ARM64 verificadas y ninguna afirmación de APK/libobs funcional. Los logs de fallos esperados forman parte del diagnóstico; no se silencian ni se cambian por mensajes de éxito.

## Fase 2 — compilar libobs real ARM64

La salida mínima será una biblioteca compartida con las API públicas originales, escenas, mezcla y utilidades; **sin reset de video ni renderizador fingido**. Compilar no equivale a ejecutar. Una comprobación JNI de arranque/cierre podrá añadirse como harness de diagnóstico, sin interfaz de producción; la escena visible empieza en la fase 3.

Orden concreto:

1. Fijar releases/commits/hashes reales de FFmpeg 8.x, jansson, SIMDe y Uthash y preparar sus scripts de compilación. Elegir shell de FFmpeg y comprobar toolchain correspondiente antes de instalarlo. Conservar todos los logs y opciones. No usar binarios obs-deps de escritorio.
2. Preparar un árbol de trabajo derivado del checkout fijado, conservando `upstream/` limpio. Aplicar una serie de parches en `patches/obs-studio/` con comprobación del commit y `git apply --check`; no copiar una lista recortada de fuentes como sustituto del target original.
3. Añadir selección Android explícita al CMake original, compilando el target `libobs` y sus dependencias originales con C17/C++17. Mantener root source en el árbol OBS: varios CMake utilizan `CMAKE_SOURCE_DIR` para `deps/`, así que un simple `add_subdirectory` desde otro proyecto rompe esas rutas si no se adaptan.
4. Reutilizar POSIX compatible con cambios localizados. Implementar rutas sandbox, UUID seguro, loader, hotkeys y monitoreo temporalmente no disponibles con estados honestos. No devolver éxito para funciones que no existen.
5. Enlazar con `--no-undefined`; inspeccionar arquitectura, símbolos, SONAME, NEEDED y alineación 16 KB de **cada** dependencia. Unir una única `libc++_shared.so` del NDK si es necesaria. Conservar los símbolos públicos OBS.
6. Prueba de carga/arranque/cierre en Android o emulador ARM64 cuando exista harness. Mantener aparte el resultado de compilación y el de ejecución. Crear una escena solo después de comprobar si esa ruta necesita estado gráfico inicializado; no forzar la API para aparentar una escena funcional.

### Archivos exactos previstos para modificar

Rutas relativas al árbol OBS derivado. **Todavía no modificados.**

| Archivo | Modificación prevista |
| --- | --- |
| `CMakeLists.txt` | Rama Android; no invocar `cmake/windows/architecture.cmake` para Android; opción explícita de core mínimo que no añada backend desktop, frontend, scripting ni test-input. El camino de escritorio conserva su comportamiento. |
| `cmake/common/osconfig.cmake` | Seleccionar el destino Android antes de las ramas por host. Asegurar que Android se conoce antes de `bootstrap`: el script de configuración pasará `-DCMAKE_SYSTEM_NAME=Android` junto al toolchain oficial. |
| `cmake/android/compilerconfig.cmake` — nuevo | Incluir políticas comunes C17/C++17, PIC y opciones Clang adecuadas sin flags MSVC. |
| `cmake/android/defaults.cmake` — nuevo | Prefijos de instalación/staging, assets y rutas Android; sin CPack, XDG ni paquetes desktop. |
| `cmake/android/helpers.cmake` — nuevo | Adaptar propiedades/export de targets y empaquetado; conservar nombres y metadatos compatibles con el cargador Android. |
| `libobs/CMakeLists.txt` | Añadir rama `OS_ANDROID`, loader Android y selección de fuentes; conservar las listas originales del motor y FFmpeg. Evitar SONAME versionado incompatible con empaquetado APK. |
| `libobs/cmake/os-android.cmake` — nuevo | Pthreads Bionic, dl/m/log/android, utilidades POSIX compatibles y adaptadores Android; sin X11/Wayland/LibUUID. |
| `libobs/obs-android.c` — nuevo | Información de plataforma, localización de recursos y hotkeys de plataforma declaradas no disponibles inicialmente. Registrar explícitamente esa limitación del core mínimo. |
| `libobs/obs-core-modules-loader-android.c` — nuevo | Resolver módulos y datos dentro del paquete/rutas suministradas por la aplicación; al principio lista vacía de plugins, no carga simulada. |
| `libobs/util/platform-nix.c` | Guardas Android mínimas para UUID, rutas y funciones de escritorio; extraer solo las operaciones necesarias a archivo Android si eso reduce el parche. No duplicar toda la capa POSIX. |
| `libobs/util/platform-android.c` y cabecera — nuevos | Rutas privadas y de bibliotecas proporcionadas por JNI, UUID v4 con entropía segura y funciones exclusivas Android. |
| `libobs/util/threading-posix.c`, `libobs/util/pipe-posix.c` | Reutilizar y parchear únicamente si las compilaciones reales muestran incompatibilidades. No se afirma que todos sus símbolos fallen. No depender de `/bin/sh` para operaciones normales. |
| `libobs/cmake/obs-core-modules.cmake` y metadatos relacionados | Conservar el target y manifest de módulos original; generar lista mínima sin enganchar plugins de escritorio. |

Para la primera compilación se puede seleccionar `audio-monitoring/null/null-audio-monitoring.c` original: devuelve explícitamente indisponibilidad; AAudio se integra posteriormente. Eso no deshabilita el mezclador. Deshabilitar hotkeys globales es una limitación de plataforma documentada; los controles táctiles siguen siendo posibles.

### Configuración de destino que deberá producir el script de fase 2

Este es el contrato previsto, **no un comando ejecutado ni funcional sobre el checkout actual**. Requiere los parches, las dependencias y el árbol `out/src/obs-studio` que se crearán en esa fase. `OBS_ANDROID_CORE_ONLY` es una opción nueva propuesta, no una opción que upstream ya tenga.

```powershell
$sdkRoot = 'C:\Users\derli\AppData\Local\Android\Sdk'
$nativePrefix = "$PWD/out/deps/arm64-v8a"
& .\tools\cmake-3.30.8-windows-x86_64\bin\cmake.exe `
  -S .\out\src\obs-studio -B .\out\libobs-arm64 -G Ninja `
  "-DCMAKE_MAKE_PROGRAM=$sdkRoot/cmake/3.22.1/bin/ninja.exe" `
  "-DCMAKE_TOOLCHAIN_FILE=$sdkRoot/ndk/29.0.14206865/build/cmake/android.toolchain.cmake" `
  '-DCMAKE_SYSTEM_NAME=Android' '-DANDROID_ABI=arm64-v8a' `
  '-DANDROID_PLATFORM=android-29' '-DANDROID_STL=c++_shared' `
  "-DCMAKE_PREFIX_PATH=$nativePrefix" "-DCMAKE_FIND_ROOT_PATH=$nativePrefix" `
  '-DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY' `
  '-DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=ONLY' '-DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=ONLY' `
  '-DCMAKE_FIND_ROOT_PATH_MODE_PROGRAM=NEVER' '-DCMAKE_POSITION_INDEPENDENT_CODE=ON' `
  '-DCMAKE_BUILD_TYPE=RelWithDebInfo' '-DOBS_ANDROID_CORE_ONLY=ON' `
  '-DENABLE_FRONTEND=OFF' '-DENABLE_SCRIPTING=OFF' '-DENABLE_CORE_MODULES=OFF' `
  '-DOBS_VERSION_OVERRIDE=0.0.1-android-dev' '-DOBS_BUILD_NUMBER=1'
& .\tools\cmake-3.30.8-windows-x86_64\bin\cmake.exe --build .\out\libobs-arm64 --target libobs --verbose
```

El script debe bloquear paquetes del host y aislar `PKG_CONFIG_LIBDIR`/sysroot de FFmpeg, no solamente añadir un prefix. La versión `0.0.1-android-dev` debe identificarse como derivación experimental y acompañarse del commit original, sin usarla como etiqueta oficial OBS. Los fallos nuevos de compilador/enlazador determinarán los parches siguientes; no se ocultarán con símbolos de relleno.

Criterio de cierre de fase 2: compilación reproducible de libobs original ARM64 y dependencias, símbolos completos y logs conservados. Documentar por separado si se logró carga/arranque en dispositivo. No pasar al renderizador con símbolos faltantes o sin haber verificado el binario nativo.

## Fase 3 — EGL/GLES y escena visible

Adaptar `libobs-opengl/CMakeLists.txt`, `gl-subsystem.{c,h}`, `gl-shaderparser.c`, `gl-stagesurf.c`, `gl-texture2d.c` y `gl-helpers.c`; añadir `gl-android-egl.c`. Mantener efectos y compositor originales.

- Contexto EGL ES 3.x y pbuffer/contexto persistente para supervivencia a cambios de Activity. Superficies `ANativeWindow` para Preview/Program, referencias y destrucción en el hilo correcto.
- GLSL ES 300/310 con qualifiers de precisión y equivalencias por capacidad, sin sustituir ciegamente la cadena `#version`.
- Readback mediante FBO y `glReadPixels`, PBO/fences y `glMapBufferRange` cuando corresponda; no `glGetTexImage`.
- Validar BGRA, sRGB, mezclas, clamp-to-border y formatos HDR por capacidades; usar alternativas correctas o declarar un efecto no soportado.
- Escena real con color e imagen, dos fuentes transformadas, corte/fundido y comparación Preview/Program. Habilitar solo los módulos originales necesarios para esta prueba, aunque el catálogo completo llegue en fase 6.

Cierre: imagen compuesta por libobs en pantalla real, capturas/resultados de comparación, resize, giro y recreación de Surface sin pantalla negra persistente. Vulkan queda aplazado hasta este punto.

## Fase 4 — captura y audio

Añadir módulos OBS Android separados: Camera2 frontal/trasera con capacidades consultadas, AudioRecord para micrófono/playback capture y MediaProjection para pantalla. Configuraciones de resolución/FPS/enfoque/exposición/zoom solo si el dispositivo las ofrece. CameraX puede usarse como capa de ciclo de vida sin sustituir el contrato de fuente OBS.

Video: evaluar primero frames asíncronos originales de OBS; integrar textura externa OES y sincronización al backend si reduce copias de forma medible. Audio: alimentar `obs_source_output_audio` con timestamp monotónico y formato declarado; conservar volumen/mute/medidores del mezclador y probar resampling. Monitoreo AAudio/Oboe posterior con buffers y manejo de desconexión.

La [documentación MediaProjection](https://developer.android.com/media/grow/media-projection) exige consentimiento por sesión y reglas específicas para su token/servicio. El audio interno depende de permiso, versión y política de la aplicación emisora. No se captura contenido protegido eludiendo esas restricciones.

Cierre: fuentes mezcladas dentro de la misma escena, sincronía medida, pérdida/revocación de permisos, cambio de cámara y cierre de captura sin fugas. Medir micrófono, audio interno y monitoreo por separado.

## Fase 5 — MediaCodec y RTMP/RTMPS

Crear encoder OBS MediaCodec para H.264/AAC; HEVC condicionado a capacidades de dispositivo y destino. Integrar superficie EGL del encoder con el modelo de entrega de frames OBS; convertir codec-config y paquetes a los headers/extradata que las salidas OBS esperan, con PTS/DTS y keyframe correctos. Evitar readback CPU como ruta final si la GPU puede escribir directamente en la Surface del encoder.

Reutilizar `obs-outputs` y servicio personalizado: endpoint, clave, bitrate, keyframes, resolución y FPS validados. Consultar soporte hardware y modos CBR/VBR, no interpretar disponibilidad del nombre de códec como aceleración garantizada. Reconexión y límites de cola de paquetes; claves fuera de logs.

Servicio foreground con los tipos y permisos aplicables a cámara/micrófono/pantalla, iniciado desde un flujo permitido. Android impone requisitos distintos según tipo: [documentación oficial](https://developer.android.com/develop/background-work/services/fgs/service-types). Declarar tipos no garantiza ejecución ilimitada de cualquier transmisión.

Cierre: recibir una transmisión real en un servidor RTMP y RTMPS de prueba, inspeccionar resolución/FPS/códecs/keyframes, sincronía y reconexión tras caída de red. Hardware confirmado por capacidades/runtime, sin confundir conexión TCP con streaming válido.

## Fase 6 — ampliar funciones originales

Escenas persistentes, fuentes, transformaciones/recortes, filtros de audio y GPU, imágenes/texto/medios y catálogo de transiciones. Reutilizar guardado/carga de datos OBS donde exista; mapear propiedades a controles Android. Validar efectos uno por uno. No prometer stinger funcional sin decodificación y sincronización de medios.

Cierre: restauración de una colección real, ajustes retenidos, filtros sin distorsión y transiciones comprobadas en Program y salida codificada.

## Fase 7 — UI táctil de producción

Kotlin/Compose con vistas de superficie y JNI tipado: escenas, fuentes, mezclador, Preview/Program, modo estudio, transiciones, ajustes y controles de transmisión/grabación disponibles según estado. Motor y recursos pertenecen al servicio; UI se suscribe a señales/estadísticas.

Gestos calculados en coordenadas de canvas y aplicados a `obs_sceneitem_set_pos`, escala, crop y orden mediante API OBS. Serializar comandos y gestionar referencias para no usar fuentes destruidas. Evitar trabajo bloqueante en hilo UI y callbacks JNI por cada frame.

Cierre: edición por gestos verificada en teléfono/tableta, rotación y vuelta desde segundo plano preservando sesión y control real del motor.

## Fase 8 — grabación, SRT y estabilidad

MP4/otros contenedores compatibles sin depender de un helper de escritorio no empaquetado; evaluar el mux original de OBS antes de crear un adaptador adicional. SRT con libsrt y dependencias de la ruta seleccionada, transmisión/grabación simultáneas compartiendo encoder cuando el contrato lo permita.

Persistencia de archivos mediante rutas/FD y publicación con MediaStore/SAF según destino. Métricas de memoria, frames perdidos, colas, carga GPU/CPU, batería y estado térmico; adaptación gradual de bitrate/FPS/resolución con límites de capacidades. Pruebas largas, pérdida de red, Bluetooth/USB y fabricantes distintos.

Cierre: archivos reproducibles y recuperables tras interrupción; recepción SRT real; simultaneidad y perfiles térmicos medidos. No declarar optimización solo por elegir un códec hardware.

## Fase 9 — APK y validación de entrega

Gradle/AGP/SDK fijados en versiones compatibles, ARM64, manifiesto y permisos mínimos por función, assets y todas las bibliotecas compartidas/avisos incluidos. Revisar alineación ELF y ZIP para [páginas de 16 KB](https://developer.android.com/guide/practices/page-sizes), firmar y registrar hashes de artefactos.

Separar evidencias: compilación; instalación/arranque; interacción visual; cámara/audio/pantalla; transmisión real; grabación; duración/temperatura; recuperación. Probar en más de un fabricante y Android relevante. Entregar APK verificable más código correspondiente, parches, scripts y licencias; conservar los fallos/limitaciones observados.

Un APK instalable de diagnóstico en una fase temprana no sustituye esta validación funcional final.
