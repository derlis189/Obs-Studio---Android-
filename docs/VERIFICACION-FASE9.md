# Grabaciones en carpetas personalizadas, fuente Navegador (WebView) y soporte real de Plugins ARM64: fase 9 verificada

23/11/2026, Samsung SM-S931B (`RFCY71VX7YV`, Adreno 830, Android 17 / API 37, SELinux `Enforcing`) conectado por ADB Wi-Fi.
- APK archivado: `artifacts/phase9/obs-android-phase9-arm64-debug.apk` (65.675.138 bytes / 62,63 MB), SHA256 `DA986D063C513B08999CF69CE1E1F43CC145F16CC5CAD874C31785F3CC89730C`.
- Plugin externo ARM64 de demostración y prueba: `artifacts/phase9/obs-colorbars-arm64.zip` (3.683 bytes), SHA256 `61A300E490AC9A05A38145E65DC85A4F9B2F1F650888857AEE76788837B208C0`.

## Capacidades implementadas

### 1. Grabación en cualquier carpeta del teléfono/SD y aparición automática en Galería
- `android/app/src/main/AndroidManifest.xml`: agrega permiso `MANAGE_EXTERNAL_STORAGE` (con respaldo automático sin permiso especial hacia `/storage/emulated/0/Android/media/org.obsandroid.port/Recordings`).
- `android/app/src/main/java/org/obsandroid/port/QtObsActivity.java`:
  - `getDefaultRecordingPath()`: selecciona `/storage/emulated/0/Movies/OBS Studio` cuando está activo el acceso a archivos, o `/storage/emulated/0/Android/media/org.obsandroid.port/Recordings` (visible en *Mis Archivos* y *MediaStore*) en caso contrario.
  - `pickDirectory()`: abre el selector nativo de carpetas de Android (`Intent.ACTION_OPEN_DOCUMENT_TREE`), ofrece un acceso directo para conceder permiso de almacenamiento si hace falta, traduce URIs `content://.../tree/primary:...` y tarjetas SD/USB (`/storage/<UUID>/...`) a rutas POSIX directas si hay permiso de escritura, o activa **exportación automática SAF** desde `Recordings/SAF-Export` al detener la grabación.
  - `onRecordingFinished()`: exporta automáticamente grabaciones SAF si corresponde y ejecuta siempre `MediaScannerConnection.scanFile()` para indexar cada `.mp4`/`.mkv` de inmediato en la **Galería** de Android.
- `shared/qt/wrappers/qt-wrappers.cpp` (`SelectDirectory`), `frontend/utility/platform-android.cpp` (`GetDefaultVideoSavePath`) y `frontend/widgets/OBSBasic_Recording.cpp` (`RecordingStop`) conectados mediante símbolos exportados en `obs-session.cpp` (`obs_android_get_default_recording_path`, `obs_android_pick_directory`, `obs_android_on_recording_finished`).

### 2. Fuente Navegador (`browser_source`) nativa sobre Chromium / Android WebView
- `android/app/src/main/java/org/obsandroid/port/BrowserSourceManager.java` y `android/app/src/main/cpp/android-browser.cpp`:
  - Registrada con el ID estándar **`browser_source`** (`"Navegador"`, `OBS_ICON_TYPE_BROWSER`), lo que habilita automáticamente la barra contextual nativa `BrowserToolbar` de OBS (**Actualizar** sin caché), arrastre de URLs y compatibilidad con escenas existentes.
  - Propiedades completas: **URL** o **Archivo local** (`.html`/`.htm` usando el resolvedor `content://`), **Ancho**, **Alto**, **FPS** (`1–60`), **Fondo transparente (RGBA)**, **CSS personalizado** (inyectado en `onPageFinished` y al editar propiedades), **Desactivar fuente cuando no esté visible** (`shutdown`), **Actualizar caché de la página actual** (`refreshnocache`) e **Interactuar con la página** (`interact`).
  - Contenedor `WebView` adjunto detrás de la vista principal (índice `0`, `alpha = 0.01`, ignorando toques) para evitar que Chromium pause `requestAnimationFrame` o animaciones CSS, con despremultiplicación alfa en `pushBrowserFrame` hacia `VIDEO_FORMAT_RGBA`.
  - Modo **Interactuar**: abre un diálogo modal nativo reutilizando la misma instancia viva de `WebView` (conservando sesión/cookies/DOM) para iniciar sesión o tocar botones, y la devuelve al compositor al cerrar.

### 3. Sistema e instalador de Plugins ARM64 (`.zip` / `.so`) en OBS Studio
- `frontend/plugin-manager/PluginModuleLoader_Android.cpp`: reemplaza el stub previo por carga real mediante `loadPluginsByInfo` en:
  - `$OBS_PLUGINS_PATH/%module%/`
  - `<filesDir>/obs-studio/plugins/%module%/` (y `bin/arm64-v8a/`)
  - `<filesDir>/obs-studio/plugins/*.so` (modo legado)
- `android/app/src/main/java/org/obsandroid/port/PluginInstaller.java`:
  - Valida encabezados ELF64 `EM_AARCH64` (183) y alineación `PT_LOAD` de 16 KB antes de instalar; informa con mensajes claros en español si se intenta cargar un `.dll` de Windows o un `.so` de PC (`x86_64`).
  - Instala archivos `.so` o paquetes `.zip` (preservando `data/`, `manifest.json`, `locale/`, efectos) con permisos `0444` de solo lectura (compatibles con Android 14–17 DCL).
  - Sincroniza automáticamente plugins colocados por el usuario en `/storage/emulated/0/Android/media/org.obsandroid.port/plugins/`.
- `frontend/plugin-manager/PluginManagerWindow.cpp`: añade el botón **"Instalar plugin (.zip / .so)..."** directamente dentro de **Herramientas → Gestor de Plugins**, con carga en vivo (`obs_open_module` + `obs_init_module` + `obs_post_load_modules`) y persistencia inmediata en `modules.json`.

## Evidencia verificada

- **Alineación ELF 16 KB (`0x4000`)**: `llvm-readelf -l` sobre el APK archivado confirmó **26/26 `.so` empaquetadas alineadas a `0x4000`** (`unaligned: 0`) y **4/4 segmentos `LOAD`** del plugin `libobs-colorbars.so` alineados a `0x4000`.
- **Símbolos nativos exportados (`llvm-nm -D libobs-android-jni.so`)**: confirmados `obs_android_get_default_recording_path`, `obs_android_pick_directory`, `obs_android_on_recording_finished`, `obs_android_install_plugin`, `Java_org_obsandroid_port_NativeBridge_onDirectoryPicked`, `Java_org_obsandroid_port_NativeBridge_registerBrowserHost` y `Java_org_obsandroid_port_NativeBridge_pushBrowserFrame`.
- **Carga dinámica real de plugin externo en Android 17 SELinux `Enforcing`** (`files/obs-studio/logs/2026-11-23 15-31-19.txt`):
  - `obs-colorbars-arm64.zip` depositado en `/storage/emulated/0/Android/media/org.obsandroid.port/plugins/` fue validado, extraído y cargado dinámicamente por `dlopen`:
    - `[obs-colorbars] External Android ARM64 plugin loaded successfully!`
    - `Loaded Modules: libobs-colorbars.so`
  - Registrado automáticamente por el **Gestor de Plugins** en `files/obs-studio/plugin_manager/modules.json` (`display_name: "Barras SMPTE (Plugin externo ARM64)"`, `sources: ["android_demo_colorbars_plugin"]`, `version: "1.0.0"`).
- **Cadena de parches y reproducibilidad**:
  - `patches/obs-studio/0001`–`0007` (`0007-android-phase9.patch`, 13.414 bytes) aplicados limpiamente desde cero sobre un clon temporal de `upstream/obs-studio`.
  - `upstream/obs-studio` intacto (`git status --porcelain` vacío).
  - `android/source-manifest.json` actualizado con **65 archivos** y verificado por `scripts/prepare-source.ps1`.

## Comandos de compilación reproducible

```powershell
& .\scripts\prepare-source.ps1
& .\scripts\build-frontend-dependencies.ps1
& .\scripts\build-libobs.ps1 -Frontend
& .\scripts\build-apk.ps1
```
