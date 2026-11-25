# Audio independiente de Pantalla, auto-ajuste horizontal, Control Remoto Web con QR, Multistreaming/Vertical 9:16 y VTuber (`.veadomini` / PNG / Live2D / VRM): fase 11 verificada

23/11/2026, Samsung SM-S931B (`RFCY71VX7YV`, Adreno 830, Android 17 / API 37, SELinux `Enforcing`) conectado por ADB Wi-Fi.
- APK archivado: `artifacts/phase11/obs-android-phase11-arm64-debug.apk` (96.127.041 bytes / 91,67 MB), SHA256 `18FDA322C0049AC25B0153F9D01D1EF4893AB1F95964C84FD028EB821DF81D10`.

## Capacidades implementadas

### 1. Audio independiente en `Pantalla (Android)` y auto-ajuste horizontal automático
- `android/app/src/main/cpp/android-sources.cpp`, `ScreenController.java`, `CaptureService.java`, `QtObsActivity.java`:
  - `android_screen` declara `OBS_SOURCE_ASYNC_VIDEO | OBS_SOURCE_AUDIO`, creando su propio canal independiente en el **Mezclador de audio** (`Pantalla (Android)`) con volumen, silencio y filtros separados de `Mic/Aux` y `Audio del escritorio`.
  - Propiedad `capture_audio` activada por defecto y conmutable en caliente (`CaptureService.SCREEN_AUDIO`) sin cortar la captura de video.
  - Propiedades `auto_fit` (activada por defecto) y `fit_mode` (*Ajustar completo sin recortar* / *Rellenar pantalla horizontal*) en **`Pantalla (Android)`** y **`Cámara (Android)`**, aplicando `OBS_BOUNDS_SCALE_INNER` / `OBS_BOUNDS_SCALE_OUTER` al tamaño exacto del lienzo horizontal del Programa ante cualquier cambio de resolución u orientación.
  - `CameraController.java` compensa la orientación real de pantalla en horizontal (`computeDisplayRelativeRotation`) para mantener derechas la cámara trasera y frontal.

### 2. Control Remoto Web con generador de QR nativo, copiar y enviar enlace
- `android/app/src/main/java/org/obsandroid/port/WebRemoteServer.java` y `QrCodeGenerator.java`:
  - Servidor HTTP/JSON embebido en el puerto `4455` (`http://<IP-WiFi>:4455/?token=...`) con interfaz web táctil para cualquier navegador (cambiar escenas, Transición en Modo Estudio, Iniciar/Detener transmisión y grabación, visibilidad de fuentes y mezclador de audio completo).
  - Generador ISO/IEC 18004 QR Code puro en Java sin dependencias externas (`QrCodeGenerator.java`).
  - Diálogo nativo en **Herramientas → Control Remoto Web (QR)...** con QR escaneable, **Copiar enlace**, **Enviar enlace** (`Intent.ACTION_SEND`) y **Nuevo QR** (rotación de token).
- Puente C++/Qt en `frontend/utility/android-tools.cpp` y `obs-session.cpp` (`obs_android_register_remote_handlers`).

### 3. Multistreaming RTMP/RTMPS simultáneo y Transmisión Vertical 9:16 independiente
- `android/app/src/main/cpp/android-multistream.h`, `android-multistream.cpp` y `frontend/utility/android-tools.cpp`:
  - Accesible desde **Herramientas → Multistreaming y Vertical (9:16)...**.
  - **Multistreaming RTMP/RTMPS:** permite gestionar y guardar múltiples destinos (`android-multistream.json`), iniciar/detener individualmente o todos en paralelo con `android_mediacodec_h264` + `android_mediacodec_aac` + `rtmp_output`.
  - **Transmisión y Grabación Vertical 9:16 independiente:** crea un lienzo secundario real mediante `obs_view_create()` + `obs_view_add2()` (`720×1280` o `1080×1920`), con opción de seguir automáticamente la escena de Programa o usar una escena vertical dedicada, encuadre *Rellenar 9:16* o *Ajustar completo*, transmisión RTMP/RTMPS vertical independiente y grabación vertical `.mp4` indexada en Galería.

### 4. Fuente VTuber (`vtuber_source` — `.veadomini`, PNGTuber, Live2D y VRM 3D con seguimiento facial)
- `android/app/src/main/java/org/obsandroid/port/VeadominiParser.java`, `VtuberSourceManager.java`, `FaceTrackerManager.java`, `android/app/src/main/cpp/android-vtuber.cpp` y `android/app/src/main/assets/vtuber/index.html`:
  - **PNGTuber y `.veadomini` (Veadotube Mini):** motor 100 % nativo en `Bitmap`/`Canvas` (cero latencia de WebView, transparencia RGBA real) compatible con `.veadomini` v1.4 (ZIP + YAML), `.veadomini` v1.3c (GZIP/binario con PNG/WebP embebidos), selector de estados/expresiones (`veadomini_state`), PNGs individuales, rebote al hablar, parpadeo automático/facial y lip-sync por micrófono.
  - **VRM 3D (`.vrm`):** motor offline `Three.js` + `@pixiv/three-vrm` (MIT) con `android:usesCleartextTraffic="true"` en `AndroidManifest.xml`, captura directa GPU `gl.readPixels` → `POST /vtuber-frame/<id>` (transparencia RGBA real), iluminación de 3 puntos + filtrado anisotrópico, pose natural relajada (brazos abajo + respiración suave), saludo animado con la mano (`hand_tracking`), SpringBone y seguimiento de cabeza reforzado (`head_sensitivity`).
  - **Live2D (`.zip` / `.model3.json` / `.moc3`):** `live2dcubismcore.min.js` integrado directamente en `assets/vtuber/` con prioridad absoluta junto con `PixiJS` (mipmaps + filtrado anisotrópico) + `pixi-live2d-display` (Cubism 4) en un canvas WebGL2 dedicado (`#cl2d`), resolución de rutas relativas por segmentos en `/vtuber-file/...`, autodescubrimiento de parámetros de saludo desde `.cdi3.json` (`hand wave` / `hand wave toggle` + `ParamArm*`/`ParamHand*`) y enganche `beforeModelUpdate` para seguimiento facial reforzado, saludo con la mano, parpadeo, respiración, física y lip-sync en vivo.
  - **Calidad de renderizado y seguimiento en vivo:** selector `vt_quality` (**Alta 768p** por defecto, **Ultra HD 1024p** hasta `1280×1280`, Estándar 512p), `CameraController.java` + `FaceTrackerManager.java` con `STATISTICS_FACE_DETECT_MODE_FULL`, seguimiento frontal en segundo plano, detector YUV de saludo con la mano (`onFrontCameraYuv`), receptor UDP **VMC/OSC en puerto `39539`** y nivel RMS de micrófono (`getMicLevel`).

## Evidencia verificada

- **Alineación ELF 16 KB (`0x4000`)**: `llvm-readelf -l` sobre el APK archivado confirmó **28/28 bibliotecas `.so` empaquetadas alineadas a `0x4000`** (`unaligned: 0`).
- **Verificación en vivo en SM-S931B (`task-1686.log` y `logcat`):**
  - Servidor web remoto activo en LAN: `ObsWebRemote: Web Remote HTTP server listening on http://192.168.15.246:4455/?token=9A15E68C`.
  - Respuesta JSON real de `GET /api/state`: devolvió `fps: 30.0`, `studioMode: true`, lista de escenas/fuentes y **`Pantalla (Android)` listada como canal independiente en el Mezclador de audio** junto a `Mic/Aux`, `Multimedia` y `Fuente NDI™`.
  - `GET /` y `GET /vtuber/index.html` respondieron HTTP `200 OK`.
  - **VTuber Live2D Cubism 4 verificado visualmente en vivo (`out/phase11/p11_live2d_ok.png`)**: modelo real `ASUKA.zip` (`Asuka.moc3` + textura `Asuka.4096/texture_00.png`) renderizado a **30.00 FPS** con transparencia RGBA real, seguimiento facial y medidor de audio independiente de `Pantalla (Android)` activo.
  - **VTuber VRM 3D verificado visualmente en vivo (`out/phase11/p11_vrm_relaxed_pose.png`)**: modelo `.vrm` en pose natural relajada con brazos abajo, respiración suave y seguimiento facial frontal en segundo plano.
- **Cadena de parches y reproducibilidad:**
  - `patches/obs-studio/0001`–`0010` (`0009-android-phase11.patch` y `0010-android-picker-wildcard.patch`) aplicados limpiamente sobre clon limpio de `upstream/obs-studio`.
  - `upstream/obs-studio` intacto (`git status --porcelain` vacío).
  - `android/source-manifest.json` actualizado con **68 archivos** y validado por `scripts/prepare-source.ps1`.
