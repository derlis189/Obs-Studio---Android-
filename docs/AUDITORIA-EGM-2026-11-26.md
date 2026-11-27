# Auditoría Técnica EGM v2 — Post-Remediación y Optimizaciones Avanzadas

- **Fecha:** 26–27 de noviembre de 2026
- **Commit auditado:** `0664118` (`main`, 11 parches OBS + 111 entradas verificadas en `android/source-manifest.json`)
- **Alcance:** Auditoría integral de 5 perspectivas (*Codebase Details*, *Hostile Design*, *Zoom-Out Architecture*, *Comprehension & UX/Docs*, *Inventive Catalyst / TRIZ*) tras la remediación completa de EGM #1 (`0178efa`) y las optimizaciones avanzadas (`0664118`).

---

## 1. Resumen Ejecutivo y Métricas del Repositorio (`HEAD 0664118`)

| Capa | Archivos | Líneas totales | Mayores módulos |
| --- | ---: | ---: | --- |
| Frontend Qt Android (`out/src/obs-studio/frontend/utility/android-tools.cpp`) | 1 | **11.251** | Monolito UI + paneles + Director Deck + WebRTC + ISO/Replay |
| Capa Nativa C++ (`android/app/src/main/cpp/`) | 39 | **15.991** | `obs-session.cpp` (1.666), `android-sources.cpp` (1.644), `android-multistream.cpp` (1.483), `android-graphics.cpp` (1.283), `android-srt.cpp` (1.191), `android-iso-replay.cpp` (988), `android-encoders.cpp` (948), `android-browser.cpp` (825) |
| Capa Android Java (`android/app/src/main/java/org/obsandroid/port/`) | 28 | **13.833** | `LiveGraphicsManager.java` (3.129), `QtObsActivity.java` (1.459), `PtzControlManager.java` (1.027), `BrowserSourceManager.java` (818), `VtuberSourceManager.java` (815), `WebRemoteServer.java` (661) |
| Documentación (`docs/*.md`) | 21 | — | Incluye fases históricas 1–12, arquitectura, licencias, rendimiento |

### Estado respecto a EGM #1

| Categoría | EGM #1 (pre-`0178efa`) | EGM #2 (`0664118`) | Balance |
| --- | :---: | :---: | --- |
| **P0 Críticos** | 12 | **2** (regresiones acotadas de los cambios nuevos) | −83 % |
| **P1 Altos (Estabilidad / Seguridad / Docs)** | 18 | **6** | −67 % |
| **P2 Medios (Arquitectura / UX / Accesibilidad)** | 14 | **8** | Deuda estructural y pulido táctil |
| **Avances TRIZ de próxima generación** | — | **6** | Hoja de ruta v3 (GPU total y reloj único) |

> [!NOTE]
> **Verificados como cerrados de EGM #1:** UAF/carreras JNI en `obs-session.cpp` y `android-session-gate.h`, ciclo de vida `CaptureService` + `WakeLock`, validación Zip-Slip/Zip-Bomb/ELF en `PluginInstaller`, aislamiento `WebRemoteServer` loopback + token efímero por sesión, endurecimiento WebView (`MIXED_CONTENT_NEVER_ALLOW`, `setAllowFileAccess(false)`, `onPermissionRequest`), `AndroidRgbaFrame` en `android-graphics.cpp`, PBO doble asíncrono en `android-bg-removal-filter.cpp`, mutex por instancia en `android-ndi.cpp`, `tex_cache` acotado en `android-encoders.cpp`, redacción de secretos en respaldos, y sincronización A/V en `android-iso-replay.cpp`.

---

## 2. Hallazgos Críticos (P0 — Resolver antes de producción)

### P0-1. Captura por referencia de lambdas del stack en hilos desacoplados (`OpenBackupRestoreDialog`)
- **Ubicación:** `out/src/obs-studio/frontend/utility/android-tools.cpp:5631-5645, 5658-5681, 5697-5759`
- **Causa raíz:** Los 3 `std::thread(...).detach()` (Crear respaldo, Exportar `.zip`, Restaurar) capturan `&setBusy` y `&refreshBackups` **por referencia** desde el frame del stack de `OpenBackupRestoreDialog`, y además `refreshBackups` captura `[&]` (referencias implícitas a `list`, `backupRoot`, `btnRestore`). Aunque se deshabilita `btnClose`, el botón físico/gesto **Atrás** de Android (o el cierre de la ventana principal) dispara `QDialog::reject()`, saliendo de `dlg.exec()` y destruyendo las variables locales mientras el hilo de trabajo o el evento `Qt::QueuedConnection` sigue pendiente → **Use-After-Free / Undefined Behavior**.
- **Remediación:**
  1. Reemplazar las capturas `&setBusy` / `&refreshBackups` por lambdas con captura **exclusivamente por valor** usando `QPointer<QListWidget>`, `QPointer<QPushButton>`, `QPointer<QLabel>` y `QString backupRoot`.
  2. Instalar un `eventFilter` o subclase mínima de `QDialog` que ignore `QEvent::Close` y `Key_Escape` mientras `isBusy == true` (o esperar la finalización antes de aceptar el cierre).

### P0-2. Fuga de handle `AMediaCodec` y contador de presupuesto en fallos de `start_codec()`
- **Ubicación:** `android/app/src/main/cpp/android-encoders.cpp:465-522`
- **Causa raíz:** En la ruta fallback por buffer (`start_codec()`), las líneas `465-469` incrementan `active_codec_counts[codec_name]` y marcan `budget_reserved = true`. Si luego falla la validación de formato (`color_format != 19 && color_format != 21`, `:492-495`), `AMediaCodec_configure` (`:521`) o `AMediaCodec_start` (`:522`), la función retorna `fail(...)` **sin invocar `reset_codec()`**. El códec creado queda huérfano hasta el destructor y el cupo de concurrencia queda retenido artificialmente si el codificador se reconfigura o reintenta.
- **Remediación:** Invocar `reset_codec()` antes de retornar `fail(...)` en las 3 ramas de error de `start_codec()` (exactamente como ya hace `start_codec_surface()`).

---

## 3. Hallazgos Altos (P1 — Estabilidad, Concurrencia, Seguridad y Documentación)

### P1-1. Condiciones de carrera en `BrowserSourceManager.java` (zero-copy multi-hilo)
- **Ubicación:** `android/app/src/main/java/org/obsandroid/port/BrowserSourceManager.java:78-83, 98, 240, 251-278`
- **Causa raíz:**
  1. `surfaceZeroCopy` (`:98`) es un `boolean` no `volatile`, escrito en el hilo Main (`ensurePresentation`) y leído sin sincronización desde `captureHandlerFor(id)` (`:251`).
  2. `activeVisibleBrowserCount()` (`:78-83`) itera sobre `sInstances.values()` (un `HashMap` mutado en el hilo Main por `createOrUpdate`/`destroy`) directamente desde el hilo de captura (`:256`), exponiendo `ConcurrentModificationException` al añadir o eliminar invitados mientras otro invitado está en vivo.
- **Remediación:** Declarar `volatile boolean surfaceZeroCopy;` y migrar `sInstances` a `ConcurrentHashMap<Long, BrowserInstance>` (o mantener un `AtomicInteger sVisibleCount`).

### P1-2. Limpieza incompleta de recursos GL y caché `EGLImageKHR` en `android-browser.cpp`
- **Ubicación:** `android/app/src/main/cpp/android-browser.cpp:209, 674-699`
- **Causa raíz:**
  1. `android_browser_shutdown()` (`:674-699`) ejecuta `g_instances.clear()` y destruye `g_browser_oes.prog`/`vao` sin liberar `hw.clear_pending()` ni `hw.destroy_gl_resources()` de instancias que aún vivan en el mapa.
  2. `BrowserHwState::img_cache` (`:209`) permite hasta 9 entradas (`> 8`) sin etiqueta de generación (`reader_gen`), mientras que `ImageReader` usa `maxImages = 3`. Si `ImageReader` se recrea (p. ej. al volver de `interact()` o cambiar transparencia) conservando dimensiones iguales, los `AHardwareBuffer` anteriores quedan retenidos en `img_cache` hasta rebasar el umbral.
- **Remediación:** Drenar `clear_pending()` + `destroy_gl_resources()` sobre una copia de `g_instances` dentro de `android_browser_shutdown()`, reducir el tope de `img_cache` a `4` y purgarlo al recibir `setVisible(false)` o cambios de modo.

### P1-3. Bloqueo del hilo GUI al detener ISO y falta de drenaje en hilos desacoplados al cerrar OBS
- **Ubicación:** `android/app/src/main/cpp/android-iso-replay.cpp:767-790`, `out/src/obs-studio/frontend/utility/android-tools.cpp:5631, 5658, 5697, 7801, 8057, 9320`
- **Causa raíz:**
  1. Aunque el botón `Detener ISO` ejecuta `StopIsoRecordingNow()` en un `std::thread`, `obs_android_iso_stop()` mantiene tomado `g_iso_mutex` durante el drenaje paralelo (`hasta 1,2 s`). Durante esa ventana, el `QTimer` de 1 s llama `obs_android_iso_get_stats()` en el hilo GUI de Qt y queda bloqueado esperando el mismo `g_iso_mutex`.
  2. Los 6 `std::thread(...).detach()` en `android-tools.cpp` no están contabilizados en una barrera de cierre (`std::atomic<int>` / `OBS_FRONTEND_EVENT_EXIT`), por lo que cerrar la app en medio de un guardado de replay o detención ISO puede correr contra `libobs` en proceso de apagado.
- **Remediación:** En `obs_android_iso_stop()`, mover `g_iso_tracks` a un vector local bajo `g_iso_mutex` y soltar el mutex **antes** de `release_iso_tracks_parallel()` (usando un `std::atomic<bool> g_iso_stopping` para que `get_stats` responda al instante sin bloquear). Añadir un contador atómico de trabajadores activos con guard contra `androidFrontendClosing`.

### P1-4. Gobernador térmico de bitrate: latencia inicial y efecto de compresión en Tier 3
- **Ubicación:** `android/app/src/main/cpp/android-encoders.cpp:277-301, 603`
- **Causa raíz:** Al iniciar un codificador cuando el dispositivo ya está en Tier 2 o Tier 3, `start_codec_surface()` configura el bitrate completo y no aplica el factor térmico hasta el frame 60 (~2 s). Además, recortar únicamente bitrate al 65 % en 1080p durante Tier 3 degrada la nitidez (macroblocking) con ahorro energético limitado, ya que la potencia del SoC depende principalmente de la tasa de píxeles (`W × H × FPS`).
- **Remediación:** Evaluar el tier térmico al configurar `start_codec_surface()` y reportar el bitrate efectivo en `obs_android_encoder_stats()`; planificar escalado de tasa de píxeles (60→30 FPS o downscale en Tier 3) en Sprint 6 (ver TRIZ #6).

### P1-5. Exposición de tokens en UI y advertencias faltantes en flujos sensibles
- **Ubicación:** `BrowserSourceManager.java:683`, `WebRemoteServer.java:173-279`, `android-tools.cpp:5690-5693`
- **Causa raíz:**
  1. `BrowserSourceManager.interact()` (`:683`) muestra la URL completa (`inst.url`) en el título de la ventana, exponiendo claves de sala/tokens en pantalla (contradiciendo la política de privacidad del resto del módulo).
  2. `WebRemoteServer.showQrDialog()` genera un QR con `http://127.0.0.1:4455/#token=...` sin advertir al usuario cuando no hay Wi-Fi/LAN activo, no informa que el transporte LAN es HTTP en texto plano, ni confirma con un Toast al pulsar «Desactivar LAN».
  3. `OpenBackupRestoreDialog` redacta claves RTMP/SRT/PTZ al exportar (`:5598-5604`), pero al **restaurar** (`:5690-5693`) no advierte que sobrescribirá las claves actuales con valores vacíos.
- **Remediación:** Mostrar solo `Uri.parse(inst.url).getHost()` con `TruncateAt.END` en `interact()`; detectar `127.0.0.1` en `WebRemoteServer` para mostrar advertencia de Wi-Fi + aviso de red confiable HTTP + Toast al desactivar LAN; advertir explícitamente en la confirmación de Restaurar que las claves de emisión/SRT/PTZ deberán volver a ingresarse.

### P1-6. Contradicciones y desactualizaciones en documentación del repositorio
- **Ubicación:** `docs/ARQUITECTURA.md:34-93`, `docs/DEPENDENCIAS.md:10-86`, `README.md:15, 23-24, 47, 66-67`, `docs/LICENCIAS.md:7, 16-35`, `docs/VERIFICACION-FASE1.md:54`
- **Hallazgos verificados:**
  1. `docs/ARQUITECTURA.md:79-93` conserva una sección titulada *«Integración Android propuesta, todavía no implementada»* en tiempo futuro, contradiciendo las líneas `3-30` del mismo documento.
  2. `docs/DEPENDENCIAS.md` sigue en estado pre-Fase 2 (afirma que no hay `targetSdk`, ni `dependencies-lock.json`, ni Qt Android, y expone la ruta local `C:\Users\derli\AppData\Local\Android\Sdk`).
  3. `README.md:47` todavía afirma que *«Navegador y VTuber usan entregas acotadas y conservan las copias CPU necesarias»*, contradiciendo `README.md:15` (que a su vez debe aclarar que el zero-copy `HardwareBuffer` aplica a fuentes de navegador opacas / **invitados individuales**, mientras que **Sala Automática** conserva transparencia alfa por `PixelCopy`).
  4. `README.md:23-24` ubica el filtro Quitar Fondo IA dentro del submenú *Herramientas → Audio y Color* (cuando es un filtro de fuente de video) y vincula el gobernador térmico al diálogo de Diagnóstico (donde aún no se muestra).
  5. `docs/LICENCIAS.md` omite `obs-advanced-masks v1.6.1`, `obs-stroke-glow-shadow v1.6.1` y `androidx.webkit:webkit:1.14.0`, presentes en `dependencies-lock.json` y `build.gradle.kts`.

---

## 4. Arquitectura Zoom-Out, Mantenibilidad y UX/Accesibilidad (P2)

### 4.1 Deuda estructural y duplicación de código
1. **Triplicación del pipeline GLES `OesBlit` (~250 líneas × 3):** `eglGetNativeClientBufferANDROID` / `eglCreateImageKHR` / `GL_TEXTURE_EXTERNAL_OES` está duplicado casi idénticamente en `android-sources.cpp`, `android-browser.cpp` y `android-hw-video.cpp`, ejecutando además ~13 consultas síncronas `glGet*`/`glIsEnabled` por fuente y fotograma.
2. **Duplicación de `AttachCurrentThread` (39 ocurrencias en 8 archivos C++):** Solo `android-browser.cpp` encapsula `JniAttachGuard`; los otros 7 archivos repiten el boilerplate de attach/detach JNI.
3. **Monolitos de difícil mantenimiento:** `android-tools.cpp` alcanzó **11.251 líneas** y `LiveGraphicsManager.java` **3.129 líneas**. Dividir `android-tools.cpp` en 5–6 unidades (`android-tools-webrtc.cpp`, `android-tools-director.cpp`, `android-tools-iso-replay.cpp`, `android-tools-graphics.cpp`, `android-tools-mobile-ui.cpp`, `android-tools-core.cpp`) aceleraría compilaciones incrementales y reduciría la superficie de conflictos en futuros parches OBS.
4. **Superficies de control superpuestas:** Actualmente coexisten 5 formas distintas de cambiar escenas en vivo (`Modo Interfaz Móvil`, `Director Deck`, `Botón Flotante`, `Control Remoto Web` y `Modo Directo Seguro`). Conviene posicionar **Director Deck** como panel principal de producción táctil en vivo y unificar el acceso rápido desde la barra móvil.

### 4.2 Pulido de UX, Textos en Español y Accesibilidad Táctil
1. **Tildes y símbolos faltantes:** Diálogo SRT (`android-tools.cpp:5140-5298`: `moviles`, `conexion`, `telefono`, `Contrasena`, `parametros`), barra móvil/docks (`android-tools.cpp:9986-11097`: `Movil`, `Menu`, `clasico`, `%1C` sin `°`), `WebRemoteServer.java:415` (`Token invalido`), `QtObsActivity.java:281,308,635` (`moviles`, `Grabacion`).
2. **Jerga de ingeniería expuesta al usuario:** Eliminar `≥48dp` de los títulos visibles del Director Deck (`:9102, 9447`), `GPU zero-copy` (`:8745`), `Puente JNI` (`:7150`), `libndi.so sender / NDIlib_send_*` (`:9426`) y `dlopen()` (`QtObsActivity.java:1015`).
3. **Estados visuales y retroalimentación incompleta:**
   - En **Director Deck** (`:9124, 9248-9255`), `btnMic` aparece en verde como `MIC ACTIVO` aunque no exista ninguna fuente `android_microphone` en la colección.
   - En **Títulos Avanzados** (`:9064-9066`), tras `autoHideSec` el botón cambia a azul `#2563eb` en lugar del verde `#16a34a` original, y los 3 spinboxes (`In / Out / Auto`) carecen de etiquetas individuales y `setSpecialValueText("Manual")`.
   - En **ISO**, el encabezado siempre dice *«archivos MP4 independientes»* aunque esté marcado *Modo Mosaico 2×2*, no muestra contador `X/4`, y `btnStop` permanece habilitado estando detenido.
   - Faltan **estados vacíos** y deshabilitación de botones sin selección en `Director Deck`, `ISO`, `Instant Replay`, `Títulos Avanzados`, `Respaldo/Restaurar` y `Audio Pro`.
4. **Geometría de diálogos y blancos táctiles (<48dp):**
   - `QtObsActivity.showQrDialog()` (`:1271-1334`) no usa `ScrollView`, por lo que el QR de `240dp` se recorta en teléfonos en orientación horizontal.
   - 8 diálogos (`SRT`, `HDMI`, `PTZ`, `ISO`, `Replay`, `WebRTC`, `Títulos`, `Director Deck`) usan tamaños fijos `520×560` sin acotar a `screen()->availableGeometry().size()`.
   - Controles de barra móvil (`28–30px`) y barra de docks web (`20×22px`) son demasiado pequeños para operación táctil confiable en vivo; botones `🎨` y `↻` e imágenes QR carecen de tooltips/`contentDescription`.

---

## 5. Catalizador Inventivo (TRIZ) — 6 Saltos de Arquitectura para v3

| # | Contradicción actual | Solución inventiva (TRIZ) | Impacto esperado |
| :---: | --- | --- | --- |
| **1** | **Zero-Copy GPU vs. Transparencia Alfa** (`LiveGraphicsManager` renderiza en CPU Skia + 2 copias RGBA; `BrowserSourceManager` cae a `PixelCopy` con `transparent=true`). | Renderizar `LiveGraphicsManager` en GPU con `HardwareRenderer` + `RenderNode` (API 29+) hacia `ImageReader(HardwareBuffer)` con alfa; añadir `u_preserve_alpha` a `kBrowserOesFS`; para VTuber WebGL2, empaquetar RGB+Alfa lado a lado en `HardwareBuffer` sin `glReadPixels`. | 0 bytes/s de copia CPU en gráficos 30 FPS y overlays transparentes. |
| **2** | **Doble conversión de color en `encode_texture2`** (`RGBA → NV12` en libobs → `NV12 → RGB` en shader → `RGB → NV12` en ISP MediaCodec + 2× `eglMakeCurrent`/frame). | Pasar RGBA directo al `ANativeWindow` de MediaCodec en modo Surface y mantener el contexto EGL *sticky* en el hilo dedicado del codificador. | −2 pasadas de shader full-res por codificador/frame y menor pérdida cromática 4:2:0. |
| **3** | **Readbacks CPU forzados por WebRemote y Quitar Fondo IA** (`obs_add_raw_video_callback` activa readback 30–60 FPS por 6 s para una vista de 5 FPS; MediaPipe corre en CPU sin `Delegate.GPU` con 4 copias). | Reemplazar `obs_add_raw_video_callback` por captura PBO 5 FPS bajo demanda en `obs_add_main_render_callback` hacia `DirectByteBuffer` reutilizable; activar `Delegate.GPU` + `DirectByteBuffer` ping-pong (o `AHardwareBuffer`) en `BgSegmenterManager`. | −85 % de readbacks GPU en WebRemote y descarga de CPU en segmentación IA. |
| **4** | **Triplicación `OesBlit` y SRT/UVC en CPU** (3 copias de `EGLImageKHR` + 13 `glGet*`/frame; SRT decodifica en 4 hilos FFmpeg CPU; UVC convierte NV12→I420 en CPU). | Unificar en `AndroidAhbTextureSink` con `reader_gen` y sin `glGet*`; reutilizar decodificador `AMediaCodec` → `AImageReader` para SRT H.264/HEVC; entregar `VIDEO_FORMAT_NV12` directo en `android-uvc.cpp`. | Mismo sink zero-copy para Cámara, Pantalla, Navegador, Video HW y SRT; cero `sws_scale` en UVC NV12. |
| **5** | **Multiplicación de `VirtualDisplay` y audio Base64 en WebRTC** (4 invitados = 4 `VirtualDisplay` + 12 superficies + 50 allocs Base64/s por invitado en `PageAudioBridge`). | Modo **Atlas 2×2** en 1 solo `VirtualDisplay` con sub-fuentes UV en `android-browser.cpp`; migrar `PageAudioBridge` de Base64 `@JavascriptInterface` a `WebViewCompat` binario `TYPE_ARRAY_BUFFER`. | −75 % de carga SurfaceFlinger/VRAM en llamadas de 4 invitados y cero GC en audio web. |
| **6** | **Emergencia térmica Tier 3 y 5 dominios de reloj A/V** (recortar solo bitrate en 1080p genera artefactos; `android-hw-video` slow-mo y `android-srt` usan `os_gettime_ns()` desacoplado de PTS). | Gobernador térmico por **tasa de píxeles** (Tier 2: Bokeh 1/4 res + pantalla 720p; Tier 3: decimación 60→30 FPS o downscale IDR en stream); unificar `AndroidSyncClock` con anclaje PTS/PLL y `AudioTimestamp` en audio de pantalla. | Streams estables bajo calor extremo y lip-sync <15 ms incluso en Replay 0.5× y SRT. |

---

## 6. Plan de Remediación Recomendado (Sprints 5–7)

1. **Sprint 5 — Cierre inmediato de P0 + P1 críticos (1–2 sesiones):**
   - Corregir `OpenBackupRestoreDialog` (capturas por valor + bloqueo de `reject()` en ocupación) [**P0-1**].
   - Corregir `reset_codec()` en rutas de error de `start_codec()` [**P0-2**].
   - Hacer `surfaceZeroCopy` `volatile` + `ConcurrentHashMap` en `BrowserSourceManager` [**P1-1**].
   - Drenar instancias GL en `android_browser_shutdown()` + tope `img_cache <= 4` [**P1-2**].
   - Soltar `g_iso_mutex` antes del drenaje en `obs_android_iso_stop()` + guard de cierre para hilos detached [**P1-3**].
   - Redactar host en `BrowserSourceManager.interact()`, advertencia Wi-Fi/HTTP en `WebRemoteServer` y aviso de claves en Restaurar [**P1-5**].
   - Actualizar `README.md`, `docs/ARQUITECTURA.md`, `docs/DEPENDENCIAS.md` y `docs/LICENCIAS.md` [**P1-6**].
2. **Sprint 6 — Pulido UX/A11y + Victorias rápidas TRIZ:**
   - Tildes, eliminar jerga técnica (`≥48dp`, `GPU zero-copy`, `dlopen`), estado real de micrófono en Director Deck, `ScrollView` en QR horizontal de `QtObsActivity`, clamp de diálogos y botones táctiles ≥44–48dp.
   - `android-uvc.cpp` NV12 directo, `BgSegmenterManager` `Delegate.GPU`, `PageAudioBridge` binario `ArrayBuffer`, y WebRemote 5 FPS sin `obs_add_raw_video_callback`.
3. **Sprint 7 — Consolidación Arquitectónica v3:**
   - Extraer `AndroidAhbTextureSink` + `JniAttachGuard` compartido, dividir `android-tools.cpp` en módulos temáticos, GPU `HardwareRenderer` para `LiveGraphicsManager`, `encode_texture2` RGBA directo + EGL sticky, y `AndroidSyncClock`.
