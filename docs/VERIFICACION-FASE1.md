# Verificación de fase 1 — 23 de noviembre de 2026

Resultado observado: **PASS de la auditoría de fase 1**, sobre Windows y destino Android ARM64. Commit oficial `0c790bd0151fb9f574867e025ecf57e6eedc58e2`, cuatro submódulos fijados. Las fuentes de OBS y los submódulos permanecen sin cambios.

## Qué se comprobó

- Descarga real del repositorio oficial con submódulos y comprobación de commits/remoto/checkout limpio.
- CMake 3.30.8 portable verificado con el SHA256 oficial. Ninja 1.10.2 y NDK r29/Clang 21 ejecutados.
- Catorce puntos críticos de las fuentes localizados con archivo/línea, incluyendo CMake, FFmpeg 8.0, SIMDe, Qt, X11, UUID, GLSL y helper FFmpeg.
- Los 44 directorios de plugins con `CMakeLists.txt` aparecen una sola vez en `plugins.csv`.
- Configuración y compilación/enlace reales del **preflight de herramientas**, C17 y C++17, `arm64-v8a`, API 29. Headers JNI, EGL, GLES, Media NDK, cámara, AAudio y Pthreads disponibles al compilar; esto no prueba sus operaciones en un teléfono.
- ELF de `out/phase1/ndk-toolchain/libandroid-toolchain-probe.so`: ELF64/AArch64, segmentos LOAD con alineación `0x4000`, dependencias del NDK y `libc++_shared.so`. No es libobs.
- Errores de incompatibilidad reproducidos y conservados con códigos de salida no cero, como se detalla abajo.

## Errores reales

Con CMake 3.22.1 del SDK, sobre el CMake original de OBS:

```text
CMake Error at CMakeLists.txt:1 (cmake_minimum_required):
  CMake 3.28 or higher is required.  You are running version
  3.22.1-g37088a8-dirty
```

Con CMake 3.30.8 y toolchain Android, el compilador se identifica como Clang 21 y pasa sus comprobaciones. Después falla el código de selección de plataforma de OBS:

```text
CMake Error at cmake/windows/compilerconfig.cmake:52 (message):
  OBS requires Windows SDK version 10.0.20348.0 or more recent.
```

Dos comprobaciones pequeñas reproducen incompatibilidades puntuales encontradas en las fuentes. **Son pruebas de headers/API, no una compilación de los archivos completos de libobs**:

```text
fatal error: 'uuid/uuid.h' file not found
error: call to undeclared function 'glGetTexImage'; ISO C99 and later do not support implicit function declarations
```

Los logs completos y sus argumentos exactos están en `out/phase1/logs/`. El script exige estos fallos esperados y falla si los síntomas cambian, para obligar a revisar el diagnóstico en vez de declarar que OBS funciona.

## Alcance y límites

`out/phase1/report.json` contiene 26 comprobaciones aprobadas y `libobsBuild: NOT_STARTED_PHASE_2`. El reporte de evidencia está en `out/phase1/source-evidence.json`.

No se ha compilado libobs, sus dependencias multimedia ni un backend Android. No hay harness JNI de OBS, interfaz táctil, APK, instalación, ejecución en dispositivo, prueba de GPU, MediaCodec hardware, capturas, sincronía, emisión RTMP/RTMPS/SRT, grabación ni medición térmica. Esos resultados requieren sus fases posteriores.

Se añadieron scripts de fetch, bootstrap y auditoría, no un script de compilación libobs falsamente funcional. El comando y las modificaciones necesarias para la fase 2 están identificados como pendientes en `PLAN-ANDROID.md`.

## Repetir

```powershell
& .\scripts\fetch-source.ps1 -VerifyOnly
& .\scripts\bootstrap-tools.ps1
& .\scripts\audit-phase1.ps1 -SdkRoot 'C:\Users\derli\AppData\Local\Android\Sdk'
Get-Content .\out\phase1\report.json
```

Los comandos pueden requerir permisos de red al descargar fuentes/herramientas. En este entorno la clonación inicial falló por restricciones de red y funcionó tras la aprobación correspondiente. `git submodule status` también necesitó ejecutar su shell fuera del sandbox; la auditoría verifica cada submódulo mediante Git directo y funciona con las restricciones actuales.
