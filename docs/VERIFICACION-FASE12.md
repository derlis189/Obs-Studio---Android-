# Monitor del Programa en servidor web, Gráficos de Emisión (Marcador / Lower Third / Cronómetro / Biblia), Gestor de Escenas Verticales, Botón Flotante y Persistencia: fase 12 verificada

24/11/2026, Samsung SM-S931B (`RFCY71VX7YV`, Adreno 830, Android 17 / API 37, SELinux `Enforcing`).
- APK archivado: `artifacts/phase12/obs-android-phase12-arm64-debug.apk` (87.062.413 bytes / 83,03 MB), SHA256 `B4975165768E130476AD08FC1AE904D90E2F2162ED08821364783DFF1CF35DC7`.
- Parche de frontend/renderer: `patches/obs-studio/0011-android-phase12.patch` (10 archivos en `out/src/obs-studio`).

El inventario de artefactos de este informe corresponde a la fase 12 archivada. El APK actual excluye los runtimes NDI y Live2D Cubism Core.

## Capacidades implementadas

### 1. Monitor en vivo del Programa en el Servidor Web Remoto (sin emojis)
- `android/app/src/main/cpp/obs-session.cpp` y `WebRemoteServer.java`:
  - Nuevo endpoint `GET /api/program.jpg`: captura bajo demanda del video de salida del Programa mediante `obs_add_raw_video_callback` (`480×270`, `VIDEO_FORMAT_RGBA`) convertido a JPEG y refrescado en el navegador a ~3 FPS (con botón *Pausar vista / Reanudar vista*).
  - Interfaz web limpia y profesional con **0 emojis** en todos los archivos Java, C++ y HTML.
  - Endpoints `GET/POST /api/graphics`, `GET /api/bible/books` y `GET /api/bible/search?q=...` para controlar en vivo Marcador, Lower Third, Cronómetro, Biblia y Escenas Verticales desde cualquier navegador.

### 2. Cuatro nuevas fuentes nativas de emisión con controles en vivo
- `android/app/src/main/cpp/android-graphics.h`, `android-graphics.cpp`, `LiveGraphicsManager.java` y `BibleDatabase.java`:
  - **Marcador Deportivo (`android_scoreboard_source`)**: equipos local/visitante, colores, puntajes `+1/-1`, periodo, etiqueta de torneo y reloj de partido (iniciar/pausar/reiniciar).
  - **Lower Third / Zócalo (`android_lowerthird_source`)**: etiqueta superior, título, subtítulo, 9 estilos visuales, animación suave de entrada/salida, mostrar/ocultar y presets guardados.
  - **Cronómetro / Temporizador (`android_timer_source`)**: modo cuenta regresiva o cronómetro ascendente, barra de progreso, etiqueta, mensaje al finalizar y controles Iniciar/Pausar/Reiniciar/+1 min/-1 min.
  - **Biblia (`android_bible_source`)**: base de datos completa en español 100 % offline (`assets/bible/rv1909.tsv`, 66 libros, 31.102 versículos Reina-Valera), selector libro/capítulo/rango de versículos, navegación Anterior/Siguiente, buscador por palabra clave o cita.
  - Estado persistido en `files/obs-studio/android-graphics.json` y sincronizado entre propiedades de OBS, **4 paneles dock independientes de OBS** (`Paneles → Control: Marcador / Control: Lower Third / Control: Cronometro / Control: Biblia`) con **24 estilos en total (5 de Marcador, 9 de Lower Third, 5 de Cronómetro y 5 de Biblia)**, el diálogo **Herramientas → Control de Graficos** y el **Control Remoto Web**.

### 3. Interfaz de Audio USB, Cámara USB (UVC), Calidad de Cámara, Posición Persistente, Botón Deshacer y Paneles Táctiles
- **Audio USB (`AudioCaptureController.java`, `CaptureService.java`, `android-sources.cpp`)**: enumeración ampliada de entradas externas (`USB_DEVICE`, `USB_HEADSET`, `USB_ACCESSORY`, `LINE_ANALOG/DIGITAL`, `HDMI`, `AUX_LINE`), preferencia automática a interfaz USB conectada en modo predeterminado, negociación de frecuencia nativa (`48/44.1/32–96 kHz` con remuestreo automático en `libobs`) y reconexión en caliente mediante `AudioDeviceCallback`.
- **Cámara USB UVC (`UsbUvcController.java`, `android-uvc.cpp`)**: reclamo forzado de interfaces `USB_CLASS_VIDEO` desde Java antes de `usbdevfs`, negociación UVC 1.1 (`34`/`26` bytes), selección del menor `alt-setting` compatible con `dwMaxPayloadTransferSize` con retroceso automático ante `ENOSPC`, soporte `MJPEG` + `YUY2` + `NV12` y framing `ISO`/`BULK` corregido.
- **Calidad de cámara seleccionable (`CameraController.java`, `UsbUvcController.java`, `android-sources.cpp`)**: selector `cam_quality` (`480p`, `720p`, `1080p`, `1440p/4K` en cámara integrada; `480p`/`720p`/`1080p` en UVC).
- **Posición y tamaño de cámara preservados (`android-sources.cpp`)**: `auto_fit_enum_items()` respeta cualquier posición, escala o recuadro personalizado que el usuario haya configurado al reiniciar la app.
- **Botón Deshacer y redimensionado táctil de paneles (`frontend/utility/android-tools.cpp`)**: botón **Deshacer** añadido directamente debajo de **Ajustes** en el panel Controles (vinculado a `actionMainUndo`), separadores `QMainWindow::separator` / `QSplitter::handle` afinados a `7px` para un aspecto más limpio y compacto, paneles desbloqueados automáticamente y `centralWidget->setMinimumHeight(165)` para impedir que los docks inferiores aplasten el lienzo.

### 4. Estabilidad del Programa / Vista Previa (Normal y Modo Estudio), Corrección de Color/Exposición y Logos de Equipos en Marcador
- **Programa y Vista Previa estables al redimensionar (`display-helpers.hpp`, `OBSQTDisplay.hpp/cpp`, `OBSBasic_Preview.cpp`, `OBSBasic_StudioMode.cpp`)**:
  - `GetScaleAndCenterPos()` fija límites seguros (`baseCX/CY >= 1`, `windowCX/CY >= 16`) evitando escalas negativas o colapsadas al achicar paneles.
  - `OBSQTDisplay` implementa `resizeGL()`, aplica `glViewport(0, 0, fbW, fbH)` explícito en `paintGL()`, sincroniza `obs_display_resize()` al detectar cambios de tamaño y mantiene relación de aspecto centrada cuando llega un fotograma AHB del tamaño anterior durante el redimensionamiento.
  - `OBSBasic::RenderMain` y `OBSBasic::RenderProgram` recalculan cada fotograma el encuadre centrado desde el tamaño real de `obs_display_size()` (con margen compacto de `4px`), y en Modo Estudio se limita `programOptions->setMaximumWidth(138)` para que Vista Previa y Programa conserven espacio útil en pantalla horizontal.
- **Corrección de imagen "quemada" en Cámara y Programa (`CameraController.java`, `libobs-opengl/gl-android-egl.c`)**:
  - `CameraController.java`: los fotogramas `YUV_420_888` de Android Camera2 se tratan por defecto en rango completo `VIDEO_RANGE_FULL` (`0..255`), usando rango limitado solo cuando `DataSpace.getRange()` reporta explícitamente `RANGE_LIMITED` (evitando el estiramiento +16,4 % que recortaba altas luces a blanco).
  - `gl-android-egl.c`: `device_present()` desactiva temporalmente `GL_FRAMEBUFFER_SRGB` durante el `glBlitFramebuffer` desde el pbuffer sRGB hacia el FBO lineal `AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM`, eliminando la doble curva gamma en Vista Previa y Programa.
- **Logos de equipos en Marcador Deportivo (`LiveGraphicsManager.java`, `android-graphics.cpp`, `android-tools.cpp`)**:
  - Nuevos selectores `home_logo` / `away_logo` en propiedades de la fuente y botones **Logo Local...**, **Logo Visita...** y **Quitar logos** en el dock **Control: Marcador**, persistidos en `android-graphics.json` y renderizados con caché de `Bitmap` y relación de aspecto centrada en los **5 diseños** del marcador.
- **Estabilidad de los paneles `Control: Lower Third` y `Control: Biblia` (`frontend/utility/android-tools.cpp`)**:
  - Contenedor `DockScrollArea` con bloqueo de ancho al viewport (`ScrollBarAlwaysOff` horizontal), `QComboBox` acotados (`AdjustToMinimumContentsLengthWithIcon`, `setMaxVisibleItems(8)`, `combobox-popup: 0`) y docks acoplados por defecto en el área derecha (`setFloating(false)`) en lugar de ventanas flotantes Android.
  - En **Control: Biblia**, se reemplazó `QLabel(wordWrap)` por un `QPlainTextEdit` de solo lectura y altura fija (`74px`) para evitar explosión horizontal al cambiar de versículo, junto con grilla compacta `C:`/`V:`/`a:` y lista de resultados de altura fija (`120px`).
  - En **Control: Lower Third**, botones reorganizados en grilla 2×2, lista de presets de altura fija (`112px`) con sincronización diferencial (preservando selección) y doble toque para lanzar.
- **Pellizco con dos dedos (pinch-to-scale + arrastre) en el Preview (`OBSBasicPreview.hpp`, `OBSBasicPreview.cpp`)**:
  - Soporte nativo `QTouchEvent` (`Qt::WA_AcceptTouchEvents`) con gesto de pellizco de 2 dedos para agrandar/achicar y mover simultáneamente la fuente seleccionada (o autoseleccionar la fuente bajo los dedos), compatible tanto con fuentes por escala (`OBS_BOUNDS_NONE`) como con recuadro (`OBS_BOUNDS_*`), centrado sin saltos al soltar un dedo y con soporte completo de **Deshacer / Rehacer**, además de ampliar el radio táctil de las esquinas (`HANDLE_SEL_RADIUS = 18.0f`).

### 5. Multistreaming y Gestor de Escenas Verticales (9:16) con vinculación
- `android/app/src/main/cpp/android-multistream.h`, `android-multistream.cpp` y `frontend/utility/android-tools.cpp`:
  - Cada destino de **Multistreaming RTMP** permite elegir si transmite el **Programa Horizontal (16:9)** o el **Lienzo Vertical (9:16)** (`obs_android_multistream_start_target_ex`).
  - Nueva pestaña **Gestor Escenas Verticales**: crear escenas verticales dedicadas (`[V] ...`), duplicar desde la escena principal actual, activar en 9:16, eliminar y **vincular cada escena principal con su escena vertical correspondiente** (cambio automático en `OBS_FRONTEND_EVENT_SCENE_CHANGED`).

### 6. Botón flotante de escenas fuera de la app (`SYSTEM_ALERT_WINDOW`)
- `android/app/src/main/java/org/obsandroid/port/FloatingSceneOverlay.java` y `AndroidManifest.xml`:
  - Activación desde **Herramientas → Boton flotante de escenas (activar / desactivar)**.
  - Aparece automáticamente como píldora arrastrable `OBS` al pasar OBS a segundo plano y se oculta al volver a OBS.
  - Al tocarlo despliega selector de escenas en 1 toque, estado `EN VIVO / GRABANDO`, controles rápidos (`En Vivo`, `Grabar`, `Lower Third`, `Abrir OBS`).

### 7. Persistencia garantizada de ajustes al salir y volver a entrar
- `QtObsActivity.java`, `obs-session.cpp` y `frontend/utility/android-tools.cpp`:
  - `SaveAllObsStateOnGuiThread()` ejecuta `obs_frontend_save()`, `SaveProject()`, `SaveService()`, `config_save_safe()` sobre `basic.ini`, `global.ini` y `user.ini` (marcando `FirstRun=false`).
  - Invocado automáticamente en `onPause()`, `onStop()`, `onDestroy()` (antes de cerrar el proceso), al cambiar escenas/perfiles/colecciones y mediante autoguardado periódico cada 15 segundos, además de persistir `android-multistream.json` y `android-graphics.json`.

### 8. Diseños de Noticiero en Lower Third, Filtro de Máscara de Forma (GPU), Fuente y Salida SRT (Wi-Fi / 4G-5G), `libndi.so` integrado, Medidor GPU, Estabilidad y Carpeta de Grabaciones
- **4 diseños de Noticiero en Lower Third (`LiveGraphicsManager.java`, `android-graphics.cpp`, `android-tools.cpp`, `WebRemoteServer.java`)**: añadidos *Noticiero CNN / Ultimo Momento*, *Noticiero 24h Doble Banda (con reloj HH:MM en vivo)*, *Noticiero Editorial / Estudio* y *Noticiero Alerta Roja (Ticker)* (9 estilos totales).
- **Filtro nativo GPU `Mascara de Forma (Circulo / Redondeada / PIP)` (`android_shape_mask_filter` en `android-graphics.cpp`)**: shader GLES 3.2 con 6 formas (*Circulo perfecto*, *Ovalo*, *Rectangulo redondeado*, *Rombo*, *Vineta suave*, *Recorte diagonal PIP*), escala, redondeo de esquinas, suavizado de borde, desplazamiento X/Y, inversión y borde de color opcional (además del filtro `Imagen Máscara/Mezcla` nativo de `obs-filters`).
- **Fuente SRT y Salida SRT en Herramientas (`android-srt.cpp`, `libsrt.a` Haivision SRT v1.5.4 + MbedTLS + FFmpeg MPEG-TS)**:
  - **Fuente SRT (`android_srt_source`)**: modos *Caller* (Wi-Fi y datos móviles 4G/5G) y *Listener* (puerto local), cifrado AES opcional, Stream ID, audio + video y reconexión automática.
  - **Herramientas → `Salida SRT (Wi-Fi / Datos moviles)...` (`android_srt_output`)**: usa los codificadores hardware MediaCodec H.264 + AAC de OBS en paquetes MPEG-TS de `1316` bytes (`7×188`), modos *Caller* y *Listener*, descubrimiento automático de IPs locales (Wi-Fi, Hotspot, USB/Ethernet, VPN, Datos móviles), persistencia en `android-srt-output.json` y telemetría en vivo (kbps, RTT ms, paquetes perdidos/retransmitidos).
- **Runtime NDI integrado (`libndi.so`)**: empaquetado `libndi.so` ARM64 (5,46 MB, alineado a 16 KB `0x4000`) desde `TC-STUDIO-main` directamente en `lib/arm64-v8a/libndi.so` para que `Fuente NDI™` funcione de inmediato.
- **Medidor GPU junto a CPU (`OBSBasicStatusBar.cpp`)**: lee `/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage` (verificado en Adreno 830 bajo SELinux Enforcing, con respaldo Mali/Exynos y tiempo de render OBS) mostrando `CPU: X.X% | GPU: XX%` en la barra de estado.
- **Corrección de cierres inesperados (`CaptureService.java`, `QtObsActivity.java`, `android-sources.cpp`, `android-ndi.cpp`, `OBSQTDisplay.hpp/cpp`)**:
  - Eliminado el crash `FORTIFY: pthread_mutex_lock called on a destroyed mutex` mediante conteo de referencias `obs_source_get_ref`/`obs_source_release` fuera de `source_mutex` en entregas de cámara, pantalla, UVC, audio y NDI, además de `std::atomic<bool> destroying` y `QPointer<OBSQTDisplay>` en callbacks de presentación.
  - Eliminado el ANR de `CaptureService` (`waited 30001ms / 115580ms`) moviendo todas las paradas bloqueantes (`camera/microphone/screen/uvc.stop()`) a un ejecutor de fondo (`OBS-CaptureStop`), evitando iniciar `CaptureService` cuando `enabled=false` sin capturas activas y desregistrando `AudioDeviceCallback` en `onDestroy()`.
- **Carpeta visible de grabaciones (`QtObsActivity.java`, `android-tools.cpp`)**: ruta predeterminada y migración automática desde rutas raíz/internas hacia `/storage/emulated/0/Movies/OBS Studio`, con publicación automática vía `MediaStore.Video.Media` (si el almacenamiento restringido bloquea escritura directa) e indexado inmediato con `MediaScannerConnection.scanFile()` para visualización instantánea en Galería y Mis Archivos.

## Evidencia verificada
- **Alineación ELF 16 KB (`0x4000`)**: **29/29** bibliotecas `.so` empaquetadas alineadas (incluyendo `libndi.so` y `libobs-android-jni.so` con `libsrt.a`, `unaligned: 0`).
- **Activos empaquetados**: `assets/bible/rv1909.tsv` (4.189.403 bytes, 31.102 versículos) y `assets/vtuber/live2dcubismcore.min.js` (150.389 bytes) presentes en el APK.
- **Cero emojis**: escaneo Unicode en todo Java/C++/HTML confirmó `EMOJIS_REMAINING=0`.
- **Reproducibilidad**: `upstream/obs-studio` limpio (`git status --porcelain` vacío), `patches/obs-studio/0011-android-phase12.patch` y `android/source-manifest.json` actualizados.
