# OBS nativo para Android — port experimental ARM64

Base oficial fijada: [`obsproject/obs-studio`](https://github.com/obsproject/obs-studio/tree/0c790bd0151fb9f574867e025ecf57e6eedc58e2), commit `0c790bd0151fb9f574867e025ecf57e6eedc58e2` de `master`, observado el 23 de noviembre de 2026. No es una versión estable etiquetada ni una versión Android de OBS.

El proyecto compila el **libobs original completo** para Android ARM64 y adapta su backend OpenGL a EGL/GLES 3.2. La aplicación JNI utiliza escenas, fuentes, mezcla de audio y transiciones originales. El checkout oficial permanece sin modificaciones en `upstream/`; los parches se aplican en `out/src/obs-studio`.

El APK experimental abre el frontend Qt y admite edición de escenas, Program/Preview, grabación, salidas RTMP y SRT, fuentes Android, gráficos, VTuber y control remoto. Las pruebas históricas y sus límites están en los informes por fase. Las compilaciones locales requieren Android ARM64 con EGL/GLES 3.2.

## Compilar y probar el port actual

Requiere Git, Android SDK 36, NDK `29.0.14206865`, JDK 21, Ninja y Gradle 8.13. Instala Qt 6.9.3 para Android ARM64 y su Qt anfitrión MinGW en `tools/qt6/6.9.3/android_arm64_v8a` y `tools/qt6/6.9.3/mingw_64`. En Windows puedes configurar `ANDROID_SDK_ROOT`, `JAVA_HOME` y, si es necesario, `GIT_BASH`. CMake 3.30.8 se descarga con checksum mediante bootstrap. Las versiones de fuentes están fijadas en `source-lock.json` y `dependencies-lock.json`.

```powershell
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
& .\scripts\test-core-device.ps1 -Serial '<serial ADB>'
& .\scripts\test-graphics-device.ps1 -Serial '<serial ADB>'
```

El APK queda en `android/app/build/outputs/apk/debug/app-debug.apk`. Instalar mediante `adb -s '<serial>' install -r <apk>` y abrir `org.obsandroid.port/.QtObsActivity`. En **Fuentes** se habilitan cámara/micrófono/pantalla; MediaProjection requiere consentimiento nuevo en el teléfono. Una vez habilitada pantalla + audio interno, `scripts/test-capture-device.ps1 -Serial '<serial>'` reproduce un tono y comprueba píxeles GPU y mezcla real. No transmite a ningún servidor.

La ruta inicial de capturas usa buffers CPU hacia las fuentes asíncronas originales y composición GPU; todavía no implementa texturas externas/encoder de superficie sin copias. GLES 3.2 y las extensiones sRGB requeridas se verifican al arrancar. No se presupone compatibilidad universal entre fabricantes.

## OMT en Android ARM64

La compilación opcional incorpora la fuente y la salida de [OMT para OBS](https://github.com/openmediatransport/omtplugin). Requiere una versión mantenida del SDK .NET 8. El script admite `-DotnetPath` para usar un SDK portable y reutiliza el NDK del proyecto.

```powershell
& .\scripts\build-omt.ps1
& .\scripts\build-libobs.ps1 -Frontend
& .\scripts\build-apk.ps1 -WithOmt
```

- Para recibir, agrega **OMT Source** e ingresa `omt://IP:puerto` en **Dirección OMT**. El primer puerto de envío suele ser TCP 6400.
- Para enviar el Programa, usa **Herramientas → OMT Output Settings**. El receptor puede indicar la IP del teléfono y su puerto OMT si admite conexión directa.
- Esta compilación permite direcciones directas. También conserva la opción `DiscoveryServer` de OMT en `files/omt/settings.xml`. El descubrimiento automático por mDNS requiere una adaptación adicional para Android.

## Operación en red

- Para SRT con datos móviles, usa **Caller** hacia un receptor alcanzable. **Listener** requiere un puerto UDP entrante accesible, algo que CGNAT generalmente impide.
- Para Control Remoto, abre su diálogo QR desde Herramientas y conecta el navegador en la misma red al puerto TCP 4455. El diálogo permite volver a desactivar el acceso LAN. Usa una red de confianza, ya que el transporte es HTTP.
- Para descubrir NDI, el teléfono y los emisores deben compartir una red que permita mDNS en UDP 5353. Importa el runtime ARM64 por separado desde el selector de la aplicación.
- Concede permisos de cámara, micrófono, USB y captura de pantalla al activar las fuentes correspondientes.

## Reproducir la fase 1 en PowerShell

Desde esta carpeta, con Git y el SDK/NDK indicados en [dependencias](docs/DEPENDENCIAS.md):

```powershell
& .\scripts\fetch-source.ps1
& .\scripts\bootstrap-tools.ps1
& .\scripts\audit-phase1.ps1 -SdkRoot 'C:\Users\derli\AppData\Local\Android\Sdk'
```

Los scripts no borran ni resetean un checkout existente. Una discrepancia de commit o una modificación local interrumpe la auditoría. `fetch-source.ps1 -VerifyOnly` comprueba las fuentes sin descargar. La primera ejecución de bootstrap descarga CMake oficial a `tools/` y verifica un SHA256 fijado; no instala herramientas globalmente.

El resultado completo queda en `out/phase1/report.json`, con comandos y errores originales en `out/phase1/logs/`. Un `PASS` significa que se reprodujeron los bloqueos esperados y funcionó el preflight del NDK; **no significa que OBS compile o se ejecute en Android**.

## Documentación

- [Arquitectura y bloqueos verificados](docs/ARQUITECTURA.md).
- [Dependencias, herramientas y comandos](docs/DEPENDENCIAS.md).
- [Plan de adaptación y criterios por fase](docs/PLAN-ANDROID.md).
- [Clasificación de todos los plugins](docs/plugins.csv): 1 = cambios menores; 2 = adaptación; 3 = implementación actual incompatible. Las categorías son análisis de fuentes, no resultados de ejecución.
- [Validación observada](docs/VERIFICACION-FASE1.md).
- [Compilación nativa y gráficos comprobados](docs/VERIFICACION-FASES2-3.md).
- [Capturas y mezclador comprobados](docs/VERIFICACION-FASE4.md).
- [Navegador GPU y video real de VDO.Ninja](docs/VERIFICACION-NAVEGADOR-VDO-NINJA.md).
- [Ahorro térmico de capturas y vistas previas](docs/VERIFICACION-AHORRO-TERMICO.md).
- [Director automático: plugin original y prueba Android](docs/VERIFICACION-DIRECTOR-AUTOMATICO.md).
- [MediaCodec y RTMP comprobados](docs/VERIFICACION-FASE5.md).
- [Licencias y distribución](docs/LICENCIAS.md).

La interfaz actual controla el motor OBS dentro del proceso Android. Las fases siguientes incorporarán codificadores hardware y salidas originales, con pruebas independientes antes de avanzar.
