# Arquitectura de OBS para Android

## Arquitectura actual implementada (Qt 6.9.3 + libobs + JNI)

El frontend principal es `QtObsActivity` (`org.obsandroid.port`), una `QtActivity` que aloja el frontend completo de OBS compilado con **Qt 6.9.3** para Android ARM64. Qt posee el ciclo de vida de `libobs`, mientras que `CaptureService` mantiene los controladores de captura y el servicio en primer plano (`ForegroundService`) cuando hay captura, emisión, grabación, ISO, Repetición Instantánea, SRT o NDI activos.

```text
QtObsActivity (Qt 6.9.3 Frontend + Paneles táctiles)
        │
        ├── libobs original + backend EGL / GLES 3.2
        │     ├── Fuentes nativas GPU: Camera2 / MediaProjection (AHardwareBuffer),
        │     │     Browser/WebRTC & Gráficos (AndroidRgbaFrame premultiplicado),
        │     │     VTuber (PNG / Live2D / VRM WebGL2), SRT, NDI, OMT
        │     ├── Filtros GPU: Ruedas de color (LGG), Máscara de forma, Perspectiva 3D,
        │     │     Quitar Fondo / Bokeh IA (PBO doble buffer asíncrono + MediaPipe)
        │     ├── Codificadores: MediaCodec H.264 / HEVC (EGL Surface zero-copy) y AAC compartido
        │     └── Salidas: RTMP/RTMPS, MP4, Vertical 9:16 + Backtrack, ISO Multi-fuente,
        │           Instant Replay (ring buffer zero-copy), SRT, NDI, HDMI/Miracast
        │
        └── Capa Java / JNI (obs-android-jni)
              ├── CaptureService (FGS + WakeLock + Audio hotplug)
              ├── ThermalController (presupuesto térmico unificado Java + C++ tier 0..3)
              ├── BrowserSourceManager (VirtualDisplay + Presentation + pool PixelCopy paralelo)
              ├── LiveGraphicsManager (Marcador, Lower Third, Cronómetro, Biblia, Clima, Títulos)
              ├── PtzControlManager (VISCA UDP/TCP, ONVIF SOAP + WS-Discovery, Pelco-D, Gamepad PS)
              ├── WebRemoteServer (HTTP loopback/LAN con token + validación Host)
              └── ExternalDisplayManager / PluginInstaller (ELF ARM64 + límites anti Zip-Slip/Zip-Bomb)
```

El servidor local `WebRemoteServer` permanece ligado a `127.0.0.1` hasta habilitar el control remoto en LAN mediante QR; Web Remote y los assets locales VTuber usan tokens separados y validación de cabecera `Host`. El cierre ordenado (`obs_android_prepare_shutdown` / `android_session_close`) y la reapertura en caliente (`NativeBridge.reopenSession`) evitan bloqueos o carreras JNI entre sesiones.

---

## Diagnóstico histórico del port (Fase 1)

Análisis del commit fijado en `source-lock.json`. Las rutas de esta página son relativas a `upstream/obs-studio/`. `audit-phase1.ps1` vuelve a localizar los puntos críticos y guarda sus líneas reales en `out/phase1/source-evidence.json`; falla si cambian las firmas examinadas.

## Componentes reutilizables

| Subsistema | Fuentes originales | Reutilización prevista |
| --- | --- | --- |
| Inicialización, registro y ciclo de vida | `libobs/obs.c`, `obs-module.c`, `obs-data.c`, `obs-properties.c` | Mantener el núcleo; adaptar rutas, carga de módulos y recursos al sandbox Android. |
| Escenas, fuentes, canvas, transformaciones | `libobs/obs-scene.c`, `obs-source.c`, `obs-canvas.c`, `obs-view.c` | Conservar escenas, orden de capas, escalado, recorte, señales y referencias. La UI llamará estas API por JNI. |
| Mezclador de audio | `libobs/obs-audio.c`, `obs-audio-controls.c`, `media-io/audio-io.c`, `audio-resampler-ffmpeg.c` | Conservar mezcla y sincronización. Captura y monitoreo son adaptadores de plataforma distintos. |
| Composición de video | `libobs/obs-video.c`, `obs-display.c`, `graphics/graphics.c`, `graphics/effect*.c`, `obs-video-gpu-encode.c` | Mantener el compositor; implementar las operaciones del backend gráfico para EGL/GLES. |
| Filtros y transiciones | `obs-source-transition.c`, `plugins/obs-filters/`, `plugins/obs-transitions/` | Reutilizar lógica y formatos de efectos. Validar la traducción de shaders a GLSL ES antes de declararlos compatibles. |
| Codificadores, servicios y salidas | `obs-encoder.c`, `obs-output.c`, `obs-service.c` | Conservar el contrato de módulos y paquetes. MediaCodec se registrará como encoder OBS. |
| RTMP, TLS y grabación MP4 | `plugins/obs-outputs/rtmp-stream.c`, `librtmp/`, `flv-mux.c`, `mp4-output.c`, `mp4-mux.c` | Adaptar el código original, con MbedTLS 3.x; verificar headers de códec, red y almacenamiento. |
| Medios, decodificación y conversión | `plugins/obs-ffmpeg/`, `shared/media-playback/`, `libobs/graphics/graphics-ffmpeg.c`, `media-io/*ffmpeg.c` | FFmpeg sigue siendo necesario incluso usando MediaCodec para codificar. |
| UI de escritorio | `frontend/`, `shared/qt/`, `frontend/cmake/ui-qt.cmake` | Qt Widgets/Network/Svg/Xml y frontend de escritorio quedan fuera del objetivo mínimo. No son dependencias de libobs. |
| Otros elementos | `deps/`, `shared/`, `cmake/`, `test/`, `docs/sphinx/` | Utilidades, código incluido, configuración de compilación, pruebas y documentación API. |

La lógica del modo estudio también vive en el frontend: conservar libobs no porta automáticamente la gestión Preview/Program, persistencia de perfiles, docks ni herramientas Qt. Una UI táctil deberá gestionar dos vistas/escenas utilizando las API originales, sin duplicar el compositor.

## Bloqueos confirmados

1. **CMake del SDK demasiado antiguo.** La raíz y libobs exigen `3.28...3.30`; el SDK instalado contiene 3.22.1. El rechazo se reprodujo. Se añadió CMake 3.30.8 portable y verificado dentro del workspace.
2. **Selección de plataforma por anfitrión.** `cmake/common/osconfig.cmake` usa `CMAKE_HOST_SYSTEM_NAME`. En Windows activa `OS_WINDOWS` aunque el destino sea Android. La raíz llama a `cmake/windows/architecture.cmake` por el mismo criterio; el configure con Clang del NDK termina pidiendo Windows SDK. Instalar otro Windows SDK no arregla este port: hay que separar host y target.
3. **No existe rama Android para libobs.** `libobs/CMakeLists.txt` selecciona implementaciones Windows/macOS/Linux/BSD y su loader de módulos mediante `PLATFORM_ID`. Android queda sin implementación de plataforma y sin loader. Forzar `OS_LINUX` arrastra X11/XCB/LibUUID y, opcionalmente, PulseAudio/Wayland; no es una solución.
4. **La capa POSIX no es directamente Android.** `util/platform-nix.c` contiene `<uuid/uuid.h>`, rutas XDG, resolución de datos desde el ejecutable, inhibición de salvapantallas y acceso a `/proc`. El NDK r29 no suministra ese header UUID: se reprodujo el error con el include exacto. Reutilizar operaciones POSIX compatibles y adaptar específicamente las demás; no asumir acceso ilimitado a `/proc`.
5. **OpenGL de escritorio.** `libobs-opengl/gl-subsystem.c` exige OpenGL 3.3 y extensiones de escritorio; `gl-shaderparser.c` genera `#version 330`. `gl-stagesurf.c` usa `glGetTexImage` y `glMapBuffer`, y otras rutas usan BGRA, clamp-to-border y control sRGB que requieren decisiones por capacidad. Se reprodujo el rechazo de `glGetTexImage` con headers GLES. EGL es gestión de contexto/superficie, no traducción de OpenGL a GLES.
6. **Carga dinámica y assets.** `obs-core-modules-loader-nix.c` busca módulos y datos relativos al ejecutable. En Android ese ejecutable es el proceso de aplicación; hay que usar `nativeLibraryDir`, empaquetado `lib/<abi>/lib*.so` y assets/datos extraídos a almacenamiento privado. Los nombres/versionados SONAME deben ser válidos para Android. No descargar código ejecutable a carpetas de datos.
7. **Dependencias binarias de escritorio.** Los paquetes obs-deps para Windows/macOS no son ELF Android. FFmpeg, jansson y las restantes bibliotecas deben compilarse para Bionic/ARM64 con el mismo NDK/API; SIMD se conserva mediante SIMDe. Uthash y SIMDe son headers, no DLL para instalar.
8. **Captura y codificación dependen del SO.** No hay fuente Camera2/MediaProjection ni encoder MediaCodec OBS en esta base. Los plugins de captura Windows/macOS/Linux no implementan esos contratos Android.
9. **Helper FFmpeg de grabación.** `plugins/obs-ffmpeg/obs-ffmpeg-mux.c` inicia `obs-ffmpeg-mux` con `os_process_pipe_create2`. La reutilización requiere resolver empaquetado/ejecución y restricciones Android; se prioriza evaluar el mux MP4 ya incluido en `obs-outputs`, manteniendo las limitaciones de formatos explícitas.
10. **Persistencia y ciclo de vida.** Superficies, contexto EGL, cámara, permisos, audio y pantalla pueden revocarse. La Activity no debe ser propietaria única del motor; el servicio tendrá estados y cierre ordenado. Esto requiere pruebas posteriores, no queda validado por compilar.

## Separación por sistema operativo

| Plataforma | Ejemplos que no deben entrar al destino Android |
| --- | --- |
| Windows | `libobs-d3d11/`, `libobs-winrt/`, `obs-windows.c`, WASAPI, DirectShow, ASIO, GDI y captura con hooks Win32. |
| macOS | `libobs-metal/`, `obs-cocoa.m`, AVFoundation, VideoToolbox, Syphon, CoreAudio y cámara virtual de macOS. |
| Linux/BSD | `obs-nix-x11.c`, GL X11/Wayland, XCB, PulseAudio, ALSA, PipeWire, JACK, V4L2, OSS y sndio de escritorio. |
| Compartido | Escenas, fuentes, señales, datos, mezcla, encoder/output/service, filtros compatibles, RTMP y utilidades portables. |

La categoría 3 de plugins significa que **el módulo actual** no ofrece una implementación Android utilizable, no que su función deba desaparecer. Sus funciones esenciales se proporcionarán mediante módulos Android del propio OBS. Un SDK de fabricante Android futuro permitiría reevaluar módulos de hardware profesional; no se ha demostrado tal soporte aquí.

## Integración Android propuesta, todavía no implementada

```text
Activity táctil → JNI (cola serial de comandos) → libobs original
                       ↑                         ↓
Foreground service     fuentes OBS Android       backend EGL/GLES
(ciclo de vida)         Camera2 / AudioRecord      Preview / Program
                       MediaProjection            Surface de MediaCodec
                                                  ↓
                       MediaCodec encoder OBS → paquetes OBS
                                                  ↓
                              obs-outputs RTMP/RTMPS + grabación
```

Camera2/MediaProjection alimentarán fuentes OBS con `SurfaceTexture`/textura externa o una ruta inicial de frames asíncronos. El compositor seguirá siendo OBS. La ruta de superficie de MediaCodec debe integrar su sincronización con la API de encoder y el hilo gráfico OBS; no basta con registrar una función `encode`. Primero se valida corrección; la eliminación de copias se mide después. Vulkan queda como backend opcional futuro tras GLES funcional, sin afirmar que OBS ya disponga de él.
