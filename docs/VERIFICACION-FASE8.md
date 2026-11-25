# Integración multimedia Android, ícono oficial, controles Camera2/USB Audio y capturadoras UVC: fase 8 verificada

23/11/2026, Samsung SM-S931B (`RFCY71VX7YV`, Adreno 830, Android 15) conectado mediante ADB Wi-Fi (`adb-RFCY71VX7YV-5eAo2b._adb-tls-connect._tcp`). APK `artifacts/phase8/obs-android-phase8-arm64-debug.apk` (54.563.495 bytes / 52,04 MB), SHA256 `B66649DCD60CBFE6698FE247C25B528CF141E0292444E02FF515703F44E4FAC4`.

Se completan e integran sobre el port nativo de **OBS Studio C++/Qt 6.9.3** para Android ARM64 (`arm64-v8a`, 100 % alineado a páginas de 16 KB) las cuatro capacidades de producción móvil de la fase 8:

1. **Resolución nativa de URIs `content://` de Android** para abrir fotos y videos locales en **Imagen** (`image_source`), **Galería de imágenes** (`slideshow`), **Multimedia** (`ffmpeg_source`) y el diálogo de **Faltan archivos**.
2. **Ícono oficial adaptativo de OBS Studio** y pantalla de inicio oscura (`Theme.ObsStudio`).
3. **Controles avanzados de cámara (`Camera2`)** (enumeración completa de lentes traseros/frontales/externos con distancia focal, **Zoom en vivo `0.6x–10.0x`** y **Exposición EV `-6..+6`**) y **selección explícita de interfaces de audio USB** (`AudioRecord.setPreferredDevice` + captura estéreo `UNPROCESSED`).
4. **Driver nativo USB UVC** (`Cámara / Capturadora USB (UVC)`, ID `android_usb_camera`) sobre `UsbManager` + `usbdevfs` para webcams USB y capturadoras HDMI-a-USB en MJPEG y YUY2.

## Cambios técnicos principales

### 1. Resolución de URIs `content://` (`patches/obs-studio/0006-android-content-uri.patch`, 7.015 bytes)

- `android/app/src/main/java/org/obsandroid/port/QtObsActivity.java`: `resolveContentUri(String uriStr)` consulta `OpenableColumns.DISPLAY_NAME`/`SIZE`, deduce la extensión desde `MimeTypeMap` si falta, copia el flujo SAF a `<filesDir>/obs-media-cache/<sha1_12>_<nombre>` y reutiliza la caché si coincide el tamaño.
- `android/app/src/main/cpp/obs-session.cpp`: exporta el símbolo C `obs_android_resolve_content_uri(const char *uri, char *out_buf, size_t out_cap)` (`00000000002f99a4 T obs_android_resolve_content_uri`).
- `shared/qt/wrappers/qt-wrappers.cpp`: `ResolveAndroidContentUri()` envuelve `OpenFile()` y `OpenFiles()` invocando `obs_android_resolve_content_uri` vía `dlopen("libobs-android-jni.so", RTLD_NOW | RTLD_NOLOAD)` con respaldo mediante `QFile`/`QAndroidContentFileEngine`.
- `frontend/utility/MissingFilesPathItemDelegate.cpp`: migrado de `QFileDialog::getOpenFileName` a `OpenFile(...)`.
- `libobs/graphics/image-file.c` y `shared/media-playback/media-playback/media.c`: respaldo automático en tiempo de carga antes de abrir imágenes o invocar `avformat_open_input` si una escena guardada contenía una URI `content://`.

### 2. Ícono oficial de OBS Studio y pantalla de inicio

- Recursos generados a partir de `frontend/cmake/linux/icons/obs-logo-512.png`:
  - `android/app/src/main/res/mipmap-{mdpi,hdpi,xhdpi,xxhdpi,xxxhdpi}/ic_launcher.png`
  - `android/app/src/main/res/drawable-xxxhdpi/ic_launcher_foreground.png`
  - `android/app/src/main/res/drawable-nodpi/obs_splash_logo.png`
  - `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` y `ic_launcher_round.xml`
  - `android/app/src/main/res/drawable/obs_splash.xml` y `res/values/styles.xml` (`Theme.ObsStudio`, fondo `#1D1F27`).
- `android/app/src/main/AndroidManifest.xml`: `android:icon`, `android:roundIcon`, `android:theme="@style/Theme.ObsStudio"` y `<uses-feature android:name="android.hardware.usb.host" android:required="false" />`.
- `CaptureService.java`: notificación en primer plano actualizada con `R.mipmap.ic_launcher` e intent hacia `QtObsActivity`.

### 3. Controles Camera2 (lentes, zoom y exposición) e interfaces de audio USB

- `android/app/src/main/java/org/obsandroid/port/CameraController.java`: `listCameras(Context)` enumera todos los IDs de `CameraManager` indicando orientación (trasera, frontal, externa) y distancia focal; aplica `CaptureRequest.CONTROL_ZOOM_RATIO` (API 30+, respaldo `SCALER_CROP_REGION`) y `CONTROL_AE_EXPOSURE_COMPENSATION` en caliente sin reiniciar la sesión de cámara.
- `android/app/src/main/java/org/obsandroid/port/AudioCaptureController.java`: `listAudioInputs(Context)` prioriza dispositivos USB (`TYPE_USB_DEVICE`, `TYPE_USB_HEADSET`, `TYPE_USB_ACCESSORY`) junto a micrófonos integrados, cableados y Bluetooth; enruta con `AudioRecord.setPreferredDevice()` y selecciona captura estéreo `AudioSource.UNPROCESSED` (con respaldos automáticos) para interfaces USB.
- `android/app/src/main/cpp/android-sources.cpp` y `CaptureService.java`: exponen el selector dinámico de dispositivos (vía `QtObsActivity.enumerateDevices(kind)`), los deslizadores en vivo `Zoom` (`0.6–10.0`) y `Exposición (EV)` (`-6..+6`), y las acciones `CAMERA_CONFIG`, `MIC_CONFIG` y `UVC_CONFIG`.
- `android/app/src/main/cpp/android-encoders.cpp`: reinicio automático de `AMediaCodec` si cambia la resolución de salida en un codificador reutilizado.

### 4. Driver nativo USB UVC (`Cámara / Capturadora USB (UVC)`)

- `android/app/src/main/java/org/obsandroid/port/UsbUvcController.java`: detecta dispositivos `USB_CLASS_VIDEO` (`0x0E`) mediante `UsbManager`, solicita permiso USB (`RECEIVER_NOT_EXPORTED`), escucha desconexiones `USB_DEVICE_DETACHED` y transfiere el descriptor de archivo (`fd`) y los descriptores USB crudos a JNI.
- `android/app/src/main/cpp/android-uvc.h` y `android-uvc.cpp`: analiza descriptores UVC (interfaz VideoStreaming, formatos MJPEG/YUY2, endpoints isócronos y bulk), negocia `VS_PROBE_CONTROL`/`VS_COMMIT_CONTROL`, procesa URBs asíncronos mediante `USBDEVFS_SUBMITURB`/`USBDEVFS_REAPURB`, entrega YUY2 directo (`VIDEO_FORMAT_YUY2`) y decodifica MJPEG con `libavcodec` + `libswscale` hacia `VIDEO_FORMAT_I420`.
- `android/app/src/main/cpp/CMakeLists.txt`: enlaza `android-uvc.cpp` con `libavcodec.a`, `libswresample.a`, `libswscale.a`, `libavutil.a` y `z` manteniendo `-Wl,--exclude-libs,ALL -Wl,--no-undefined -Wl,-z,max-page-size=16384`.

## Evidencia verificada

- **Alineación ELF 16 KB (`0x4000`)**: `llvm-readelf -l` sobre el APK archivado confirmó **26/26 bibliotecas `.so` empaquetadas alineadas a `0x4000`** (`unaligned: 0`).
- **Símbolos nativos exportados (`llvm-nm -D libobs-android-jni.so`)**: confirmados `obs_android_resolve_content_uri`, `_Z24android_uvc_deliver_i420PKhS0_S0_jjjjjm`, `_Z24android_uvc_deliver_yuy2PKhjjm` y `_Z20android_uvc_stop_allv`.
- **Metadatos del APK (`aapt2 dump badging`)**: confirmados `application-label:'OBS Studio'`, `application-icon-*:'res/mipmap-anydpi-v26/ic_launcher.xml'` y `uses-feature-not-required: name='android.hardware.usb.host'`.
- **Cadena de parches y reproducibilidad**:
  - `patches/obs-studio/0001`–`0006` aplicados limpiamente desde cero sobre un clon temporal de `upstream/obs-studio`.
  - `upstream/obs-studio` permanece completamente intacto (`git status --porcelain` vacío).
  - `android/source-manifest.json` actualizado con **63 archivos** y verificado por `scripts/prepare-source.ps1` (`Verified already prepared Android source`).
- **Verificación real en Samsung SM-S931B (sesión `files/obs-studio/logs/2026-11-23 15-11-32.txt` y `out/phase8/p8_2_add_source_uvc.png`)**:
  - Arranque en Modo Estudio con **doble fuente simultánea** (`Cámara (Android)` PiP + `Pantalla (Android)`) a `30.00 / 30.00 FPS` con solo **`CPU: 6.0%`**.
  - Resolución real de `content://` en **`Imagen` (`image_source`)**: archivo cacheado `files/obs-media-cache/71d82a2f3213_Screenshot_20261123_135154.jpg` (449.761 bytes).
  - Resolución real de `content://` en **`Multimedia` (`ffmpeg_source`)**: video MP4 local cacheado `files/obs-media-cache/00a188f7ac92_Iglesia_discipulos_de_cristo_-_luque_20261119_144500_0000.mp4` (30.421.327 bytes), reproducido en bucle y conmutado en vivo en Modo Estudio sin errores.
  - Cambio en vivo de cámara trasera (`Camera 0`) a frontal (`Camera 1`) registrado por `CameraManagerGlobal`.
  - Transmisión **RTMPS 720p@30fps 6 Mbps en vivo hacia Facebook Live** (`rtmps://live-api-s.facebook.com:443/rtmp/`, `default-avc hardware=1 1280x720 stride=1280 slice_height=720`, `default-aac 160000`), manteniéndose activa en segundo plano mientras otra aplicación ocupa el primer plano.

## Alcance de pruebas con hardware USB físico

- El soporte de **cámaras/capturadoras USB UVC** (`android_usb_camera`, `UsbUvcController.java`, `android-uvc.cpp`) y de **interfaces de audio USB** (`AudioCaptureController.java`) está compilado, enlazado (con `libavcodec` + `libswscale` + `libswresample`), registrado en OBS Studio y verificado a nivel de manifiesto/permisos/símbolos en el APK instalado.
- Como el teléfono permaneció sin periféricos USB externos conectados durante esta validación (conectado por ADB Wi-Fi), la captura real desde una capturadora HDMI-a-USB o interfaz de audio USB física queda lista para prueba directa al conectar el dispositivo OTG/USB-C al teléfono.

## Comandos de compilación reproducible

```powershell
& .\scripts\prepare-source.ps1
& .\scripts\build-frontend-dependencies.ps1
& .\scripts\build-libobs.ps1 -Frontend
& .\scripts\build-apk.ps1
```
