# Dependencias y comandos de compilación

## Entorno actual de compilación (ARM64 Android + Qt 6.9.3)

| Herramienta | Versión fijada / requerida | Uso |
| --- | --- | --- |
| Git | 2.40+ | Checkout de fuentes oficiales (`upstream/`) y aplicación de parches |
| Android SDK | `compileSdk = 36`, `targetSdk = 36`, `minSdk = 29` | Compilación y empaquetado de `android/app/build.gradle.kts` (`arm64-v8a`) |
| Android Build Tools | `36.0.0` | Empaquetado APK |
| Android NDK | `29.0.14206865` (Clang 21) | Compilación nativa C17/C++17 (`aarch64-linux-android29`) |
| JDK | OpenJDK 21 (ej. `jbr` de Android Studio) | Gradle 8.13 y compilación Java |
| CMake portable | `3.30.8` (descargado en `tools/` con SHA256 verificado) | Cumple el mínimo 3.28 de `obs-studio` |
| Ninja | `1.10.2+` (incluido en SDK `cmake/3.22.1/bin/ninja.exe`) | Generador rápido de compilación |
| Qt 6 Android + Host | `6.9.3` (`android_arm64_v8a` + `mingw_64` en `tools/qt6/6.9.3/`) | Frontend completo de OBS en `QtObsActivity` |
| .NET 8 SDK *(opcional)* | 8.0+ | Solo necesario si se compila con soporte opcional `-WithOmt` |

Todas las versiones y revisiones exactas de las bibliotecas de terceros compiladas para ARM64 están fijadas en `source-lock.json` y `dependencies-lock.json`.

## Dependencias compiladas e integradas (`dependencies-lock.json`)

| Dependencia | Versión fijada | Rol en el port Android ARM64 |
| --- | --- | --- |
| FFmpeg | `8.0.1` (LGPL base sin `--enable-nonfree`) | Decodificación, muxer MP4/RTMP, resampler de audio |
| jansson | `2.14` | Persistencia JSON del núcleo `libobs` |
| SIMDe | `0.8.2` | Traducción NEON ARM64 de intrínsecos SIMD |
| Uthash | `2.3.0` | Tablas hash y listas internas de `libobs` |
| Mbed TLS | `3.6.8` | TLS/RTMPS para `obs-outputs` |
| Haivision SRT | `1.5.4` | Fuente y salida SRT Caller/Listener |
| curl | `8.11.1` | Cliente HTTP/HTTPS y actualizaciones de servicios |
| nlohmann/json | `3.11.3` | Mensajería JNI y configuración de módulos Android |
| Qt | `6.9.3` | Interfaz principal OBS (`QtObsActivity`) y paneles táctiles |
| LibVLC Android *(opcional)* | `3.7.8` | Reproducción multimedia adicional (`vlc-video`) |
| MediaPipe Tasks Vision | `0.10.35` (`Delegate.GPU` + fallback CPU) | Segmentación IA para Quitar Fondo / Bokeh |
| AndroidX WebKit | `1.14.0` | `WebMessageCompat` binario (`ArrayBuffer`) para WebView |
| `obs-advanced-masks` / `obs-stroke-glow-shadow` | `v1.6.1` | Filtros GPU de máscaras, bordes y sombras |
| OMT (`omtplugin`, `libomtnet`, `libvmx`) *(opcional)* | Fijadas en `dependencies-lock.json` | Fuente y salida Open Media Transport |

## Variables de entorno y compilación completa (PowerShell)

Configura `ANDROID_SDK_ROOT` y `JAVA_HOME` apuntando a tu instalación local de Android SDK y JDK 21:

```powershell
$sdkRoot = $env:ANDROID_SDK_ROOT
& "$sdkRoot\cmdline-tools\latest\bin\sdkmanager.bat" '--licenses'
& "$sdkRoot\cmdline-tools\latest\bin\sdkmanager.bat" 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0' 'ndk;29.0.14206865' 'cmake;3.22.1'

& .\scripts\fetch-source.ps1
& .\scripts\bootstrap-tools.ps1
& .\scripts\build-dependencies.ps1
& .\scripts\build-stream-dependencies.ps1
& .\scripts\build-frontend-dependencies.ps1
& .\scripts\build-srt.ps1
& .\scripts\build-vlc-dependencies.ps1
& .\scripts\prepare-source.ps1
& .\scripts\build-libobs.ps1 -Frontend
& .\scripts\build-apk.ps1
```

- Hash SHA256 verificado de `cmake-3.30.8-windows-x86_64.zip`: `ec0de3ecbc1e02ed0304d42dccdbf8f36b705af74f1783b07d612b4a02ae2808`.
- Verificación de integridad de parches y manifiesto: `python scripts/verify-patch-manifest.py`.
- Reproducción histórica del diagnóstico de Fase 1 sobre `upstream/obs-studio` sin parches: `& .\scripts\audit-phase1.ps1 -SdkRoot $env:ANDROID_SDK_ROOT`.
