# Dependencias y comandos

## Entorno observado el 23/11/2026

| Herramienta | Estado observado | Uso |
| --- | --- | --- |
| Git | 2.53.0.windows.2 | Fuentes oficiales y submódulos fijados. |
| Android Studio | Instalado en `C:\Program Files\Android\Android Studio` | IDE; esta fase no necesitó interacción gráfica. |
| JDK incluido con Studio | OpenJDK 21.0.10 | Usar `jbr`, evitando el `java.exe` Oracle del PATH que no respondió durante el inventario. No se ejecutó Gradle en esta fase. |
| SDK platforms | 34, 35, 36 y 36.1 instaladas | Propuesta inicial: compileSdk 36; targetSdk se fijará y verificará al crear la aplicación. |
| Build Tools | 34.0.0, 35.0.0, 36.0.0 y 36.1.0 instalados | Empaquetado en fases posteriores. |
| NDK | 27.2.12479018, 28.2.13676358 y **29.0.14206865** instalados | Se fija r29 para el diagnóstico, Clang 21, destino `aarch64-linux-android29`. |
| CMake SDK | 3.22.1 | Insuficiente para OBS. Se conserva su Ninja 1.10.2 para el preflight. |
| CMake proyecto | **3.30.8** | Descargado de Kitware y validado por SHA256; cumple el mínimo 3.28. |
| Dispositivo | No utilizado | No se reclama instalación, ejecución ni pruebas multimedia. |

Base propuesta: `arm64-v8a`, minSdk 29 (Android 10), C17/C++17. API 29 permite planificar audio interno y estadísticas térmicas; no significa que toda aplicación permita capturar su audio. No se cambia todavía un manifiesto ni se establece una garantía de compatibilidad.

## Instalar lo necesario para repetir la fase 1

En este equipo SDK/NDK/Ninja ya estaban instalados. Solo se descargaron las fuentes y CMake portable. En un equipo sin esos paquetes, instalar Android Studio y Command-line Tools; después ejecutar desde PowerShell, aceptando las licencias de Android como corresponda:

```powershell
$sdkRoot = 'C:\Users\derli\AppData\Local\Android\Sdk'
$env:JAVA_HOME = 'C:\Program Files\Android\Android Studio\jbr'
& "$sdkRoot\cmdline-tools\latest\bin\sdkmanager.bat" '--licenses'
& "$sdkRoot\cmdline-tools\latest\bin\sdkmanager.bat" 'platform-tools' 'platforms;android-36' 'build-tools;36.0.0' 'ndk;29.0.14206865' 'cmake;3.22.1'
& .\scripts\bootstrap-tools.ps1
& .\scripts\fetch-source.ps1
& .\scripts\audit-phase1.ps1 -SdkRoot $sdkRoot
```

El preflight usa Ninja del SDK, pero CMake 3.30.8 del proyecto. No se propone bajar el mínimo exigido por OBS para hacer pasar la configuración. No hace falta instalar Qt, FFmpeg Windows, Visual Studio ni un nuevo Windows SDK para reproducir el diagnóstico.

La descarga CMake usa [el release oficial Kitware 3.30.8](https://github.com/Kitware/CMake/releases/tag/v3.30.8). Hash fijado de `cmake-3.30.8-windows-x86_64.zip`:

```text
ec0de3ecbc1e02ed0304d42dccdbf8f36b705af74f1783b07d612b4a02ae2808
```

No se cambia la política global de ejecución de PowerShell. Si la organización bloquea scripts, usar su procedimiento autorizado.

## Dependencias para la fase 2 — identificadas, no compiladas ni instaladas aún

| Dependencia | Exigencia real en las fuentes | Preparación ARM64 |
| --- | --- | --- |
| FFmpeg | `libobs/CMakeLists.txt`: **8.0 mínimo**, avformat/avutil/swscale/swresample y avcodec. Avcodec aparece opcional en discovery pero se enlaza y utiliza; incluirlo. | Construir avcodec, avformat, avutil, swscale, swresample para Bionic/ARM64; PIC, threads y sin ejecutables. No se puede usar un paquete Windows o Linux/glibc. |
| jansson | `find_package(jansson REQUIRED)` | Biblioteca C JSON; construir con toolchain NDK, PIC y export CMake `jansson::jansson`. Versión exacta y hash se fijarán antes de descargar en fase 2. |
| SIMDe | `find_package(SIMDe REQUIRED)` | Headers `simde/x86/sse2.h`; validar traducción NEON y no forzar SSE x86. Fijar commit/hash al preparar dependencia. |
| Uthash | `find_package(Uthash REQUIRED)` | Headers uthash/utlist usados por el core; conservar detección de versión. Fijar release/hash antes de construir. |
| zlib | `find_package(ZLIB REQUIRED)` | Preferir `libz`/headers del NDK si satisfacen el finder; no descargar zlib por defecto. |
| Threads | `Threads::Threads` | Pthreads de Bionic; no descargar win-pthreads. |
| libcaption | `deps/libcaption/` incluido | Conservar el CMake y fuentes originales; ya usa PIC. |
| libuuid | Dependencia de la implementación POSIX de escritorio | No traer X11/util-linux completos. Adaptar UUID v4 con aleatoriedad segura NDK y formato original; validar unicidad/formato, nunca devolver identificadores fijos. |
| EGL/GLES/Android/log/dl/m | Bibliotecas del NDK | Requeridas para backend/plataforma; no convertirlas en reemplazo de libobs. |

No se especifican hashes ficticios ni se declara resuelta la cadena de dependencias. Antes de compilar libobs se añadirá un lock real de fuentes, opciones y hashes de cada biblioteca descargada, y se comprobará la ABI con `llvm-readelf`.

Dependencias por ampliación:

- RTMP/RTMPS: `plugins/obs-outputs` exige MbedTLS **3.x, menor que 4**, zlib, jansson y utilidades incluidas. Verificar confianza TLS/hostname y reconexión en red real.
- `rtmp-services`: jansson y `shared/file-updater`, que requiere revisar curl y actualizaciones/rutas. Para el primer envío basta un servicio personalizado correctamente configurado.
- Filtros: SpeexDSP/RNNoise opcionales; revisar cada opción en `plugins/obs-filters/cmake/`. No condicionar la compilación mínima a estos filtros.
- Texto: FreeType más selección de fuentes Android; no arrastrar Fontconfig de escritorio por forzar Linux.
- Medios: avfilter/avdevice y `shared/media-playback` adicionales para `obs-ffmpeg`. Aunque su fichero de dependencias menciona FFmpeg 6.1, el core y helper de esta revisión exigen 8.0.
- SRT: libsrt; la ruta nueva MPEG-TS de `obs-ffmpeg` también comprueba librist. Aislar esa opción hasta la fase 8.
- UI: decisión inicial Kotlin/Compose con JNI para evitar arrastrar el frontend Qt. Qt Android es una alternativa de UI futura, no un requisito del motor ni una solución a los bloqueos gráficos.

## Comando real de diagnóstico de OBS original

Es equivalente al registrado por `audit-phase1.ps1`. **Falla en el árbol original**, de forma esperada:

```powershell
$sdkRoot = 'C:\Users\derli\AppData\Local\Android\Sdk'
& .\tools\cmake-3.30.8-windows-x86_64\bin\cmake.exe `
  -S .\upstream\obs-studio -B .\out\manual-upstream-android -G Ninja `
  "-DCMAKE_MAKE_PROGRAM=$sdkRoot/cmake/3.22.1/bin/ninja.exe" `
  "-DCMAKE_TOOLCHAIN_FILE=$sdkRoot/ndk/29.0.14206865/build/cmake/android.toolchain.cmake" `
  '-DANDROID_ABI=arm64-v8a' '-DANDROID_PLATFORM=android-29' `
  '-DENABLE_FRONTEND=OFF' '-DENABLE_SCRIPTING=OFF' '-DENABLE_CORE_MODULES=OFF' `
  '-DOBS_VERSION_OVERRIDE=0.0.1-android-audit' '-DOBS_BUILD_NUMBER=1'
```

El override de versión solo evita que un clon superficial sin tag produzca un error de versión antes de llegar al bloqueo Android. No representa una versión oficial. `OBS_BUILD_NUMBER=1` evita que upstream escriba `cmake/.CMakeBuildNumber`.

No se entrega todavía un comando `--build ... --target libobs` como si pudiera funcionar. Su preparación, los scripts de FFmpeg y los parches Android corresponden a la fase 2. Para FFmpeg hará falta un shell POSIX y make, además del NDK: decidir entre MSYS2 con el NDK Windows o WSL/Linux con su **propio NDK Linux** al iniciar esa fase; no mezclar sysroots ni prometer una compilación que no se ejecutó.
