# Capturas Android: verificación de fase 4

23/11/2026, Samsung SM-S931B ARM64, Android SDK 37, ADB Wi-Fi autorizado. APK verificado: SHA256 `530D633260B9EAC9559ABFA856CEADD7E78A7A1E7466DA1CC6CB1A904A9EC56B`, conservado en `artifacts/phase4/obs-android-capture-arm64-debug.apk`.

## Resultado observado

- Camera2 frontal/trasera abrieron salidas YUV 1280×720. En la prueba de 16 segundos llegaron 485 fotogramas, mientras otra Activity cubría StudioActivity. El cambio frontal/trasera y regreso a Inicio se comprobaron también en la versión final.
- AudioRecord capturó micrófono mono a 48 kHz; el contador de muestras y el nivel no nulo se observaron en el motor. No se guardó una grabación del micrófono.
- MediaProjection se inició con consentimiento real nuevo del usuario. Generó RGBA 590×1280, ajustado al lienzo 1280×720 dentro de las escenas originales. La fuente de cámara permanece como superposición.
- Una Activity **solo del build debug** mostró un patrón RGB(200,20,180) y reprodujo un tono PCM 440 Hz mediante AudioTrack, marcado USAGE_MEDIA y capturable. Se comprobó la salida final de OBS mediante su callback de video y lectura GPU: **478 coincidencias en 483 frames**.
- AudioPlaybackCapture capturó ese tono. Pico PCM = **0,25**; pico de salida del mezclador OBS = **0,25**. Al silenciar la fuente real de OBS, la salida fue **0**, mientras la entrada siguió conteniendo el tono. El micrófono se silenció durante esta comprobación para aislar la mezcla.
- Servicio en primer plano con tipos camera/microphone/mediaProjection y wake lock parcial activo durante captura. Tras «Detener todas las capturas», las cuatro fuentes quedaron deshabilitadas, el servicio liberó el wake lock y el compositor siguió disponible.
- La primera versión registró EGL_BAD_SURFACE al ocultar StudioActivity. Se corrigió esperando la destrucción del display OBS dentro de `surfaceDestroyed`, antes de que Android abandone la cola. Repetida prueba de ocultar/volver y captura en segundo plano en el APK final: no se observaron errores EGL ni excepciones Android del proceso.

Las adaptaciones están en `android/app/src/main/cpp/android-sources.cpp`, `obs-session.cpp`, los controladores Java CameraController/AudioCaptureController/ScreenController, CaptureService, StudioActivity y el manifest. El audio y el video entran en las fuentes originales de libobs; la composición y la mezcla no se sustituyen por otro motor.

## Reproducir

```powershell
& .\scripts\build-apk.ps1
adb -s '<serial>' install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s '<serial>' shell am start -n org.obsandroid.port/.StudioActivity
# En Fuentes: cámara, micrófono y pantalla + audio interno; aprobar en el teléfono.
& .\scripts\test-capture-device.ps1 -Serial '<serial>'
```

Evidencia: `out/phase4/report.json`, `capture-probe.json`, `logs/capture-device.log`, `lifecycle-before-stop.log`, `stopped-state.log`. La prueba reproduce un tono breve; usa audio interno de la propia aplicación con política compatible y no depende de internet. CaptureProbeActivity está excluida del build release.

## Límites de esta fase

Se aprueba la integración mínima de capturas en este dispositivo. No se han medido sincronía labial, latencia audiovisual exacta, deriva prolongada, estabilidad térmica/batería ni otros fabricantes. No se prueba captura de audio de aplicaciones que la impiden, DRM o contenido protegido. Los timestamps de Camera2 se convierten desde BOOTTIME a MONOTONIC; AudioRecord usa su timestamp MONOTONIC; pantalla usa llegada monotónica hasta medir su latencia específica.

La primera ruta hace copias de planos YUV/RGBA hacia fuentes asíncronas y limita pantalla a un máximo de 30 FPS. No es una ruta OES sin copias. Cámara selecciona el formato más cercano a 720p/30 dentro de 1080p; selección manual de resolución/FPS, gestos sobre cámara/pantalla, foco manual y orientación de cámara durante giro siguen pendientes de la interfaz completa. Zoom y compensación de exposición se envían a Camera2 según capacidades, pero su efecto óptico no se midió. Monitoreo AAudio/Oboe, filtros y recuperación avanzada pertenecen a fases posteriores.

La fase 5 puede comenzar con estas capturas mínimas comprobadas; no se declara aprobado ningún encoder, protocolo ni grabación.
