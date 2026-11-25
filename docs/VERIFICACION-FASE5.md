# MediaCodec y RTMP: fase 5 verificada

23/11/2026, Samsung SM-S931B conectado mediante ADB Wi-Fi. APK `artifacts/phase5/obs-android-rtmp-arm64-debug.apk`, SHA256 `5DA6E503444AB1C0E5040C85C4566D11C1EC350545E00D30E6D291D8B801E51A`.

Se compila el módulo original `obs-outputs` y se registra su servicio original `rtmp_custom` sin el catálogo de proveedores/curl del escritorio. Mbed TLS 3.6.8 se compila desde el commit y submódulo fijados en `dependencies-lock.json`. La integración Android añade adaptadores `obs_encoder_info` que entregan paquetes de MediaCodec al encoder/output/interleaver originales de libobs. La UI permite servidor, clave, bitrate e intervalo de keyframes. El core mantiene reconexión configurada a 20 intentos cada 2 s.

## Resultado de la prueba con cámara y micrófono

- Codificador H.264 **hardware** `c2.qti.avc.encoder`; NV12, Baseline, 1280×720/30, CBR solicitado 2500 kbps, keyframes de 2 s, sin B frames.
- AAC LC `c2.android.aac.encoder`, **software del sistema**, estéreo 48 kHz/160 kbps. No apareció un AAC hardware compatible en la selección de este dispositivo. No se presenta como aceleración hardware de audio.
- Salida original RTMP: 771 frames, 0 descartados informados por OBS, 2.473.558 bytes enviados. El receptor local almacenó 2.473.980 bytes FLV. El servicio continuó mientras el teléfono estaba en Inicio.
- Decodificación real del archivo recibido usando FFmpeg fijado: **771 frames H.264**, **1.233.920 muestras AAC**; 13 keyframes, separación máxima 2,000 s; **0 errores**. Duración video 25,666 s, audio 25,707 s; RMS de audio 0,0074302, con micrófono real. La diferencia de duración no mide sincronía labial.
- El fundido original cambió la escena a mitad del envío: el píxel de fondo decodificado pasó de luminancia 71 a 101. La cámara PIP y el micrófono entraron en las escenas durante la prueba; no se guardan capturas privadas en esta documentación.

Evidencias: `out/phase5/report.json`, `stream-probe-camera-mic.json`, `decoded-camera-mic.json`, `received-camera-mic.flv`. Una prueba previa con audio silencioso también pasó. Se conservó un fallo real del ejecutable de análisis por faltar el enlace a swresample; se corrigió añadiendo la biblioteca, sin ocultar símbolos ni ignorar errores del enlazador.

## Reproducir

```powershell
& .\scripts\build-stream-dependencies.ps1
& .\scripts\prepare-source.ps1
& .\scripts\build-libobs.ps1 -Streaming
& .\scripts\build-apk.ps1
adb -s '<serial>' install -r android/app/build/outputs/apk/debug/app-debug.apk
# Abrir StudioActivity y conceder cámara/micrófono si se probarán esas entradas.
npm ci --prefix scripts/rtmp-receiver --ignore-scripts
& .\scripts\test-stream-device.ps1 -Serial '<serial>' -WithCaptures
```

El receptor de diagnóstico Node Media Server 2.7.4, fijado mediante package-lock, escucha solamente en 127.0.0.1. `adb reverse tcp:1935 tcp:1935` transporta RTMP hacia ese receptor. El teléfono ejecuta motor/capturas/encoder/RTMP; el ordenador actúa únicamente como destino de prueba. Para transmitir desde el teléfono de forma autónoma se configura un servidor RTMP accesible en su red. No se ha probado aquí una plataforma pública ni internet móvil.

## Límites

Esta ruta inicial realiza lectura/conversión CPU de la composición GPU hacia buffers YUV; el video se codifica por hardware. El encoder de superficie sin copias, HEVC, 1080p/60, control térmico y bitrate dinámico siguen pendientes. La negociación CBR del codec no demuestra un bitrate constante medido en todas las escenas.

RTMPS está bloqueado en la UI/JNI de esta fase: el código original busca certificados Unix en `/etc/ssl/certs`; falta integrar y verificar el almacén Android conservando validación TLS. No se desactiva la validación. SRT y grabación son fases posteriores. La reconexión está configurada, pero aún no se ha sometido a cortes de red. El servicio usa tipos camera/microphone/mediaProjection para capturas y specialUse declarado para transmisión de escenas sin captura en API 34+; la aceptación por una tienda y ejecución durante horas no se han validado.
