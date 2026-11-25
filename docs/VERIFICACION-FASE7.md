# Interfaz original de OBS Studio (C++/Qt 6.9.3, 16 KB, zero-copy y RTMPS): fase 7 verificada

23/11/2026, Samsung SM-S931B (`RFCY71VX7YV`, Adreno 830, Android 15) conectado mediante ADB. APK `artifacts/phase7/obs-android-qt6-arm64-debug.apk` (68.874.120 bytes), SHA256 `CE1FCB0C6CF986C592A7A07AFA40875802DE0BD7C7F348AEFA32D32A1FE441FA`.

Se compila, enlaza, empaqueta y verifica en dispositivo real la interfaz original completa de **OBS Studio (`frontend/`, C++ y Qt 6.9.3)** sobre Android ARM64 (`arm64-v8a`), 100 % alineada a páginas de **16 KB**, con presentación GPU zero-copy mediante `AHardwareBuffer`, controles táctiles adaptados a Android 15, grabación local MP4 y transmisión **RTMPS 1080p en vivo**.

## Dependencias y toolchain

- **Qt 6.9.3** (`tools/qt6/6.9.3/mingw_64` + `tools/qt6/6.9.3/android_arm64_v8a`, incluyendo `Qt6OpenGL` y `Qt6OpenGLWidgets`).
- **Alineación ELF 16 KB (`0x4000`)**: verificada con `llvm-readelf -l` sobre las **26 bibliotecas `.so` empaquetadas** (`0` bibliotecas desalineadas; desaparece el aviso de compatibilidad de 16 KB de Android 15).
- **`curl` (`curl-8_11_1`)** enlazado estáticamente con MbedTLS 3.6.8 y **`nlohmann_json` (`v3.11.3`)**, compilados por `scripts/build-frontend-dependencies.ps1` hacia `out/deps/arm64-v8a`.
- Cadena de parches `patches/obs-studio/0001`–`0005` (`0005-android-frontend.patch`, 76.231 bytes) verificada sobre un clon limpio de `upstream/obs-studio`; 60 archivos registrados en `android/source-manifest.json` y validados por `scripts/prepare-source.ps1`.

## Arquitectura de integración Qt 6 / Android

1. **Presentación GPU zero-copy con `AHardwareBuffer` (`libobs-opengl` -> `OBSQTDisplay`):**
   - `libobs/graphics/graphics.h`, `libobs-opengl/gl-android-loader.c` (`glBlitFramebuffer`, `glCheckFramebufferStatus`, `glFinish`, `glPixelStorei`) y `libobs-opengl/gl-android-egl.c` implementan un doble búfer `AHardwareBuffer` (`AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM`) respaldado por `EGLImageKHR` y FBO GLES 3.2, con blit directo en VRAM (`glBlitFramebuffer`) y fallback automático a `glReadPixels` si algún GPU no soporta la extensión.
   - `OBSQTDisplay` (`frontend/widgets/OBSQTDisplay.hpp` / `.cpp`) hereda de `QOpenGLWidget` en Android, importa cada `AHardwareBuffer` como textura GLES mediante `eglGetNativeClientBufferANDROID` + `eglCreateImageKHR` + `glEGLImageTargetTexture2DOES` y dibuja un quad sin copias a CPU. El uso de CPU bajó de ~16,5–20,4 % (readback CPU) a ~7,5–8,9 % a `30.00 / 30.00 FPS` incluso en Modo Estudio con doble visor.
2. **Controles táctiles, diálogos y teclado/portapapeles nativo en Android 15:**
   - **Editor de texto nativo Android (`QtObsActivity.openNativeTextInput` + `AndroidTouchHelper`)**: al tocar cualquier `QLineEdit` editable (`Servidor`, `Clave de retransmisión`, nombres de escenas/fuentes, URLs), intercepta `MouseButtonPress` / `RequestSoftwareInputPanel` y abre un `AlertDialog` nativo de Android con `EditText` real (`IME_FLAG_NO_EXTRACT_UI`), botones de 1 toque **Pegar** y **Borrar todo**, y soporte nativo del teclado Samsung (escribir, borrar y abrir **últimos copiados** del portapapeles).
   - **Gesto/botón Atrás en Android 15 (`OnBackInvokedCallback`)**: puenteado por JNI (`NativeBridge.handleAndroidBack`, resuelto vía `dlopen(..., RTLD_NOW | RTLD_NOLOAD)`) al hilo GUI de Qt para cerrar primero popups/menús, luego diálogos (`Ajustes`, `Añadir fuente`, `Propiedades`, `Filtros`, `Faltan archivos`) y teclado sin salir ni dejar atrapado al usuario.
   - **Ajuste automático de diálogos y gestos (`frontend/utility/platform-android.cpp`)**: todos los `QDialog` se limitan y centran dentro de `availableGeometry()` con margen por encima de la barra gestual; `QScroller` habilita desplazamiento con un dedo (`MousePressEventDelay = 0.0`, `DragStartDistance = 0.012`) sin perder toques en botones; pulsación larga (550 ms) sintetiza `QContextMenuEvent` (clic derecho).
3. **Codificadores hardware `AMediaCodec` y RTMPS en Android 15:**
   - `frontend/settings/OBSBasicSettings.cpp`, `OBSBasicSettings_Stream.cpp` y `OBSBasic.cpp` exponen `Hardware (Android MediaCodec H.264)` (`android_mediacodec_h264`) y `AAC` (`android_mediacodec_aac`) en Salida Sencilla con fallback automático en perfiles existentes, eliminando el aviso `CodecCompat.CodecMissingOnExit`.
   - `android/app/src/main/cpp/android-encoders.cpp` drena continuamente la cola de salida de `AMediaCodec` antes de esperar buffers de entrada (con cola FIFO interna y reintentos hasta 300 ms) y respeta `stride` y `slice-height` devueltos por `AMediaCodec_getInputFormat` para 1080p estable en hardware Qualcomm.
   - `plugins/obs-outputs/librtmp/rtmp.c` carga los certificados CA raíz de Android desde `/apex/com.android.conscrypt/cacerts/` (Android 14/15+) con fallback a `/system/etc/security/cacerts/` para conexiones **RTMPS** verificadas por TLS.

## Evidencia verificada en dispositivo real (`SM-S931B`)

- **Alineación 16 KB**: `Checked 26 packaged .so libraries. Unaligned count: 0`.
- **Arranque Qt 6.9.3 + zero-copy**: `Qt Version: 6.9.3 (runtime), 6.9.3 (compiled)`, `Android zero-copy AHardwareBuffer presentation: enabled`, `30.00 / 30.00 FPS` con CPU ~7,5–8,9 % (`out/phase7/v1_main.png`, `out/phase7/v2_dialog.png`).
- **Grabación local MP4 desde el botón `Iniciar Grabación`**: archivo `out/phase7/recorded-sample.mp4` (16.386.706 bytes, `probe_score=100`), verificado con `ffprobe`:
  - Video: `h264` (`android_mediacodec_h264`), `1280×720`, `30/1 fps`, `bt709`, `747` frames (`24,900 s`, `5,097 Mbps`).
  - Audio: `aac (LC)`, `48000 Hz`, estéreo, `1167` frames (`24,896 s`, `160 kbps`), sincronía A/V de 4 ms.
- **Transmisión RTMPS 1080p en vivo desde el botón `Iniciar Transmisión`**: conexión TLS real contra `rtmps://live-api-s.facebook.com:443/rtmp/` (`57.144.206.149`) exitosa (`==== Streaming Start ====`), `1920×1080 @ 30 FPS`, `6000 kbps` H.264 hardware (`stride=1920 slice_height=1080`) + `160 kbps` AAC estéreo sin errores de TLS ni de codificador (`files/obs-studio/logs/2026-11-23 14-32-35.txt`).
- **Ajustes, editor de texto nativo y Modo Estudio**: edición/pegado de servidor y clave + guardado de **Ajustes** (`Settings changed (stream 1, outputs)`), cierre de diálogos con **Atrás** y composición en vivo de `Cámara (Android)` (`out/phase7/final_rtmp_live.png`, `out/phase7/final_1_settings.png`, `out/phase7/final_2_salida.png`).

## Comandos de compilación reproducible

```powershell
& .\scripts\prepare-source.ps1
& .\scripts\build-frontend-dependencies.ps1
& .\scripts\build-libobs.ps1 -Frontend
& .\scripts\build-apk.ps1
```
