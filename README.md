# OBS nativo para Android — port experimental ARM64

Base oficial fijada: [`obsproject/obs-studio`](https://github.com/obsproject/obs-studio/tree/0c790bd0151fb9f574867e025ecf57e6eedc58e2), commit `0c790bd0151fb9f574867e025ecf57e6eedc58e2` de `master`, observado el 23 de noviembre de 2026. No es una versión estable etiquetada ni una versión Android de OBS.

El proyecto compila el **libobs original completo** para Android ARM64 y adapta su backend OpenGL a EGL/GLES 3.2. La aplicación JNI utiliza escenas, fuentes, mezcla de audio y transiciones originales. El checkout oficial permanece sin modificaciones en `upstream/`; los parches se aplican en `out/src/obs-studio`.

El APK experimental abre el frontend Qt 6.9.3 y admite edición de escenas, Modo Estudio (Program/Preview), grabación MP4/ISO, Repetición Instantánea, salidas RTMP/SRT/NDI/HDMI, llamadas WebRTC con invitados, Control PTZ (VISCA/ONVIF/Pelco-D + mando PlayStation), fuentes Android, gráficos animados, VTuber, quita-fondos IA GPU y control remoto web. Las compilaciones locales requieren Android ARM64 con EGL/GLES 3.2.

## Módulos de producción en vivo (`Herramientas`)

El menú **Herramientas** está organizado en 4 categorías:

- **Producción en Vivo:**
  - **Director Deck (Control táctil en vivo):** switcher en vivo de un toque con cuadrícula de escenas (Programa verde / Vista previa ámbar), Transición/Corte, Transmitir, Grabar, Replay 15s (0.5x), Título al aire, Silenciar Micrófono y telemetría térmica/batería.
  - **Control VTuber en Vivo (Gestos y Expresiones):** panel táctil (dock independiente y diálogo) para disparar gestos 1 toque, activar toggles Live2D (`.cdi3.json`), cambiar encuadre (`Busto sin pies` / `Primer plano` / `Cuerpo completo`) y estados `.veadomini`.
  - **Plugins de producción integrados (ARM64/GLES 3.2):** `obs-composite-blur` (desenfoque GPU Gaussian/Kawase/Tilt-Shift/Pixelate), `obs-source-clone` (clonar fuentes con filtros independientes), `obs-downstream-keyer` (capas DSK siempre encima de todas las escenas), `obs-advanced-masks`, `obs-stroke-glow-shadow`, `OMT` y `Advanced Scene Switcher`.
  - **Llamadas e Invitados en Vivo (WebRTC):** sala automática en 1 toque (QR / WhatsApp, conserva fondo transparente) o invitados individuales con captura **GPU zero-copy (`HardwareBuffer` → `EGLImageKHR`)** y disposiciones automáticas (`Pantalla completa`, `Lado a lado 640×720`, `Cuadrícula 2×2`, `PIP`).
  - **Generador Avanzado de Títulos Animados:** motor 30 FPS en GPU (`AndroidRgbaFrame`) con 8 plantillas, 7 animaciones de entrada, 4 de salida, selectores de color visuales (`QColorDialog`), shimmer, ticker y presets.
  - **Control de Gráficos:** Marcador Deportivo, Lower Third, Cronómetro/Temporizador, Biblia RV1909 offline y fuente **Fecha, Hora y Clima** (Open-Meteo, 6 diseños, auto-tamaño horizontal/vertical).
  - **Control PTZ:** VISCA sobre IP (UDP/TCP), ONVIF Profile S (SOAP + WS-Discovery), Pelco-D y soporte nativo para mando **PlayStation DualShock 4 / DualSense** (Bluetooth/USB).
  - **Captura Vertical 9:16 y Backtrack:** lienzo 9:16 independiente con emisión, grabación y clips instantáneos.
  - **Grabación ISO (Multi-fuente y Mosaico 2×2):** hasta 4 pistas MP4 independientes con codificador AAC compartido, o **Modo Mosaico 2×2** en un solo codificador GPU (75% menos carga térmica/MediaCodec), con finalización asíncrona en segundo plano.
  - **Repetición Instantánea (Instant Replay):** ring buffer de hasta 60 s con exportación MP4 asíncrona y reproducción en cámara lenta al aire.
- **Salidas y Red:** Salida SRT (Caller/Listener Wi-Fi o datos móviles), Salida NDI en red, Salida HDMI / Pantalla Wi-Fi (Miracast) y Control Remoto Web (QR en puerto 4455 con token efímero por sesión).
- **Audio y Color:** Audio Pro (cadena de voz limpia en 1 toque) y Ruedas de color (LGG). *(El filtro GPU **Quitar Fondo / Bokeh IA** con MediaPipe GPU Delegate + PBO doble asíncrono se agrega desde **Filtros** en cualquier fuente de video).*
- **Sistema y Seguridad:** Modo Directo Seguro (bloqueo táctil en vivo), Botón flotante de escenas, Diagnóstico de rendimiento/térmica, Respaldar / Restaurar configuración asíncrono (`.zip` con redacción automática de claves RTMP/SRT/PTZ) y Recuperar paneles en pantalla. *(Los codificadores MediaCodec aplican además un gobernador térmico dinámico de bitrate en niveles térmicos elevados).*

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

Cámara, pantalla, fuentes de navegador opacas (incluidos invitados individuales WebRTC), presentación, **VTuber WebGL (captura `HardwareBuffer` side-by-side alpha-pack 60 FPS sin `glReadPixels`)** y codificación disponen de rutas GPU zero-copy con `AHardwareBuffer` y MediaCodec Surface; USB UVC NV12 se entrega directo a libobs sin conversión CPU. El motor **VTuber Pro v2** integra seguimiento facial local **MediaPipe Face Landmarker (52 ARKit Blendshapes)** y **Hand Landmarker (2 manos + dedos)**, animaciones **`.vrma` (`@pixiv/three-vrm-animation`)**, 6 gestos rápidos, marionetas abiertas **Inochi2D (`.inp`/`.inx`)**, protocolo **VMC completo** (`UDP :39539`) y **API VTube Studio WebSocket (`:8001`)**. Las fuentes de navegador con transparencia alfa (overlays y Sala Automática) y PNGTuber usan entregas acotadas por `AndroidRgbaFrame` (ver [informe de rendimiento](docs/VERIFICACION-RENDIMIENTO-2026-11-25.md) y [auditoría EGM v2](docs/AUDITORIA-EGM-2026-11-26.md)). GLES 3.2 y las extensiones sRGB requeridas se verifican al arrancar. No se presupone compatibilidad universal entre fabricantes.

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
- La salida RTMP desde **Transmitir desde libobs** permite AAC estéreo a 128–320 kbps en pasos de 32. Se valida el formato contra los codificadores AAC del teléfono antes de iniciar; si el teléfono no anuncia soporte para la tasa elegida, selecciona una menor. Una tasa mayor aumenta el uso de datos y no garantiza por sí sola una mejora audible.
- Para Control Remoto, abre **Herramientas → Salidas y Red → Control Remoto Web (QR)...** y conecta el navegador en la misma red al puerto TCP 4455 (token efímero por sesión). El diálogo permite volver a desactivar el acceso LAN. Usa una red Wi-Fi de confianza, ya que el transporte es HTTP.
- Para descubrir NDI, el teléfono y los emisores deben compartir una red que permita mDNS en UDP 5353. Importa el runtime ARM64 por separado desde el selector de la aplicación.
- Para enviar el Programa por NDI, abre **Herramientas → Salidas y Red → Salida NDI en red…**, asigna el nombre visible y pulsa **Iniciar salida**. La función transmite video RGBA y audio estéreo. El runtime ARM64 importado debe incluir `NDIlib_send_create`, `NDIlib_send_send_video_v2` y `NDIlib_send_send_audio_v2`; un runtime únicamente receptor no basta. La salida se detiene desde ese mismo diálogo.
- Concede permisos de cámara, micrófono, USB y captura de pantalla al activar las fuentes correspondientes.

## Reproducir la fase 1 en PowerShell

Desde esta carpeta, con Git y el SDK/NDK indicados en [dependencias](docs/DEPENDENCIAS.md):

```powershell
& .\scripts\fetch-source.ps1
& .\scripts\bootstrap-tools.ps1
& .\scripts\audit-phase1.ps1 -SdkRoot $env:ANDROID_SDK_ROOT
```

Los scripts no borran ni resetean un checkout existente. Una discrepancia de commit o una modificación local interrumpe la auditoría. `fetch-source.ps1 -VerifyOnly` comprueba las fuentes sin descargar. La primera ejecución de bootstrap descarga CMake oficial a `tools/` y verifica un SHA256 fijado; no instala herramientas globalmente.

El resultado completo queda en `out/phase1/report.json`, con comandos y errores originales en `out/phase1/logs/`. Un `PASS` significa que se reprodujeron los bloqueos esperados y funcionó el preflight del NDK; **no significa que OBS compile o se ejecute en Android**.

## Documentación

- [Prueba de viabilidad iOS (requiere macOS/Xcode)](ios/README.md).
- [Arquitectura y bloqueos verificados](docs/ARQUITECTURA.md).
- [Dependencias, herramientas y comandos](docs/DEPENDENCIAS.md).
- [Plan de adaptación y criterios por fase](docs/PLAN-ANDROID.md).
- [Clasificación de todos los plugins](docs/plugins.csv): 1 = cambios menores; 2 = adaptación; 3 = implementación actual incompatible. Las categorías son análisis de fuentes, no resultados de ejecución.
- [Validación observada](docs/VERIFICACION-FASE1.md).
- [Compilación nativa y gráficos comprobados](docs/VERIFICACION-FASES2-3.md).
- [Capturas y mezclador comprobados](docs/VERIFICACION-FASE4.md).
- [Navegador GPU y video real de VDO.Ninja](docs/VERIFICACION-NAVEGADOR-VDO-NINJA.md).
- [Ahorro térmico de capturas y vistas previas](docs/VERIFICACION-AHORRO-TERMICO.md).
- [Rendimiento, codificadores compartidos y ciclo de vida](docs/VERIFICACION-RENDIMIENTO-2026-11-25.md).
- [Auditoría técnica EGM v2 (post-remediación y hoja de ruta v3)](docs/AUDITORIA-EGM-2026-11-26.md).
- [Director automático: plugin original y prueba Android](docs/VERIFICACION-DIRECTOR-AUTOMATICO.md).
- [MediaCodec y RTMP comprobados](docs/VERIFICACION-FASE5.md).
- [Licencias y distribución](docs/LICENCIAS.md).

La interfaz actual controla el motor OBS dentro del proceso Android. Los informes documentan por separado implementación y pruebas observadas de cada ruta; los documentos de fases iniciales conservan su contexto histórico.
