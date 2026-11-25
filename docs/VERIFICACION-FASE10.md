# Fuente de vídeo VLC (`vlc_source`) y Fuente NDI™ (`ndi_source`): fase 10 verificada

23/11/2026, Samsung SM-S931B (`RFCY71VX7YV`, Adreno 830, Android 17 / API 37, SELinux `Enforcing`) conectado por ADB Wi-Fi.
- APK archivado: `artifacts/phase10/obs-android-phase10-arm64-debug.apk` (88.549.181 bytes / 84,45 MB), SHA256 `594F0677CEB337081EC4E4341E4B779FF8243DF81F3AEE7EF39B579D2BAE8773`.

## 1. Fuente de vídeo VLC (`vlc_source`) — Plugin oficial `plugins/vlc-video` + LibVLC 3.7.8

- **Runtime LibVLC real de VideoLAN:** `org.videolan.android:libvlc-all:3.7.8` (Maven Central, `libvlc.so` ARM64, 45,88 MB), con **3/3 segmentos `LOAD` alineados a 16 KB (`0x4000`)** y **46/46 símbolos ABI** requeridos por `plugins/vlc-video`.
- **Encabezados oficiales VLC 3.0.x (LGPL):** instalados en `out/deps/arm64-v8a/include/vlc/`.
- **Parche reproducible (`patches/obs-studio/0008-android-vlc-video.patch`, 2.477 bytes):**
  - `CMakeLists.txt`: habilita `plugins/vlc-video` como módulo oficial Android (`libvlc-video.so`) cuando `OBS_ANDROID_EDITOR=ON`.
  - `plugins/vlc-video/vlc-video-plugin.c`: carga `libvlc.so` en Android (con respaldo a `obs_android_libraries_path()`).
  - `plugins/vlc-video/vlc-video-source.c`: resuelve URIs `content://` de Android mediante `obs_android_resolve_content_uri` en `create_media_from_file()`.
- `scripts/build-libobs.ps1` y `scripts/build-apk.ps1`: compilan `vlc-video`, empaquetan `libvlc-video.so`, sus archivos de idioma `data/locale/*` y `libvlc.so` dentro del APK (sello de extracción actualizado a `.obs-qt-assets-v3`).
- Incluye listas de reproducción de archivos/carpetas/URLs, bucle, aleatorio, pista de audio, subtítulos y la barra nativa de controles multimedia (`MediaControls`) de OBS Studio.

## 2. Fuente NDI™ (`ndi_source`) — Descubrimiento mDNS + Receptor NDI v5/v6 ABI + Instalador de `libndi.so`

- **Descubrimiento mDNS nativo (`android/app/src/main/java/org/obsandroid/port/NdiDiscoveryManager.java`):**
  - Añade permisos `ACCESS_WIFI_STATE` y `CHANGE_WIFI_MULTICAST_STATE` en `AndroidManifest.xml`.
  - Adquiere `WifiManager.MulticastLock` (`obs-ndi-mdns`) y descubre emisores `_ndi._tcp.` mediante `android.net.nsd.NsdManager`, resolviendo nombre, IP y puerto.
- **Receptor NDI nativo (`android/app/src/main/cpp/android-ndi.h` y `android-ndi.cpp`):**
  - Registrado en OBS con ID **`ndi_source`** (`"Fuente NDI™"`, `OBS_SOURCE_ASYNC_VIDEO | OBS_SOURCE_AUDIO | OBS_SOURCE_DO_NOT_DUPLICATE`).
  - Carga dinámicamente los símbolos C planos `NDIlib_*` (`NDIlib_initialize`, `NDIlib_find_create_v2`, `NDIlib_recv_create_v3`, `NDIlib_recv_capture_v2`, etc.) desde `libndi.so` (compatible con NDI v5/v6).
  - Soporta recepción de video `UYVY`, `UYVA`, `BGRA`, `BGRX`, `RGBA`, `RGBX`, `NV12`, `I420`/`YV12` y audio planar `AUDIO_FORMAT_FLOAT_PLANAR` (1 a 8 canales), modos de ancho de banda (*Alta calidad*, *Baja calidad / Proxy*, *Solo audio*) y sincronización (*Interna* / *Red*).
- **Instalador integrado de `libndi.so` (ARM64):**
  - Debido a que `libndi.so` es una biblioteca propietaria sujeta al EULA del **NDI SDK for Android** de Vizrt, no se redistribuye dentro del APK.
  - En las Propiedades de `Fuente NDI™`, el botón **"Instalar librería libndi.so (ARM64)..."** (o copiar el archivo a `/storage/emulated/0/Android/media/org.obsandroid.port/ndi/libndi.so`) valida que el binario sea ELF64 `arm64-v8a` (incluso extrayéndolo desde un `.zip`/`.aar`/`.apk`), lo instala con permisos `0444` y recarga el runtime NDI en caliente (`NativeBridge.reloadNdiRuntime`) sin recompilar ni reiniciar OBS.

## Evidencia verificada

- **Alineación ELF 16 KB (`0x4000`)**: `llvm-readelf -l` confirmó **28/28 bibliotecas `.so` empaquetadas alineadas a `0x4000`** (`unaligned: 0`), incluyendo `libvlc.so` y `libvlc-video.so`.
- **Arranque real en Samsung SM-S931B (`files/obs-studio/logs/2026-11-23 15-51-00.txt`):**
  - `[vlc-video]: VLC 3.0.25 Vetinari found, VLC video source enabled`
  - `Loaded Modules:` incluye `libvlc-video.so`, `libobs-colorbars.so`, `libobs-android-jni.so`, `libobs-ffmpeg.so`, `libobs-filters.so`, `libobs-outputs.so`, `libobs-transitions.so` y `libimage-source.so`.
  - Logcat confirmó `ObsNdiDiscovery: mDNS NDI discovery started: _ndi._tcp.`.
  - Captura de pantalla real en dispositivo: `out/phase10/p10_add_source.png`.
- **Cadena de parches y reproducibilidad:**
  - `patches/obs-studio/0001`–`0008` aplicados limpiamente desde cero sobre un clon temporal de `upstream/obs-studio`.
  - `upstream/obs-studio` intacto (`git status --porcelain` vacío).
  - `android/source-manifest.json` actualizado con **67 archivos** y validado por `scripts/prepare-source.ps1`.

## Comandos de compilación reproducible

```powershell
& .\scripts\prepare-source.ps1
& .\scripts\build-frontend-dependencies.ps1
& .\scripts\build-libobs.ps1 -Frontend
& .\scripts\build-apk.ps1
```
