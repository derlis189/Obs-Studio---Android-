# Rendimiento, recursos y ciclo de vida — 25/11/2026

Port experimental OBS Android ARM64. Este informe describe esta intervención; los informes por fase conservan su evidencia histórica. Compilar, instalar, observar el Programa, decodificar una transmisión y probar duración son comprobaciones distintas.

## Cambios implementados

### Cierre y entregas nativas

- `android-session-gate.h` rechaza callbacks nuevos y espera las entregas en curso antes de destruir fuentes. Cubre cámara, pantalla, audio, navegador, VTuber, gráficos y segmentación.
- El frontend prepara el cierre antes de `ClearSceneData`; la Activity también cierra la entrada de callbacks antes de finalizar. Las salidas auxiliares se detienen mientras video/audio de OBS siguen disponibles.
- Las consultas remotas usan respuestas con vida compartida y espera acotada, evitando referencias a variables de pila pendientes de una llamada bloqueante al hilo Qt durante la salida.
- Una creación MediaCodec fallida libera su reserva de concurrencia antes de permitir un reintento.

### Codificadores compartidos y lienzo vertical

- Multistream y las salidas verticales reutilizan H.264 para el mismo `video_t`, resolución, FPS, formato, espacio/rango de color y bitrate. AAC comparte la mezcla estéreo de 48 kHz a 128 kbps.
- El registro conserva referencias débiles: no mantiene vivo un codificador después de liberar sus consumidores. Un destino con bitrate distinto recibe otra instancia.
- Se consultan códecs hardware Surface para la configuración solicitada y se comprueba el límite de instancias publicado por Android. Es un límite orientativo: la creación real todavía puede fallar por recursos disponibles.
- Una salida vertical que conecta o reconecta conserva su lienzo. Iniciar otra con tamaño, escena o encuadre incompatibles se rechaza antes de liberar o modificar el lienzo compartido.

### Navegador y VTuber

- `AndroidRgbaFrame` limita la entrega a tres buffers. La CPU escribe solamente buffers libres y los fences GPU evitan reutilizarlos mientras siguen en uso.
- Navegador: `PixelCopy` conserva video y transparencia; el Bitmap se copia a un AHardwareBuffer, que se importa como textura. Se eliminaron el ByteBuffer Java intermedio, la entrega asíncrona de video OBS y el recorrido CPU para dividir alfa. Existe respaldo de carga de textura cuando falla la importación.
- La captura de ventana no incluyó correctamente el canvas WebGL del VTuber en la prueba. Se sustituyó esa ruta por lectura mediante PBO/fence en WebGL2 y mensajes binarios `ArrayBuffer` de AndroidX WebKit 1.14.0, limitados al origen loopback y al frame principal.
- VTuber mantiene una sola entrega pendiente hasta recibir respuesta de Java. El volteo de filas se combina con la copia nativa; la corrección de alfa se realiza en shader. Si WebMessage no está disponible se conserva el transporte HTTP local anterior; WebGL1 conserva lectura síncrona.
- PNGTuber entrega directamente su Bitmap a la misma cola. Al ocultar el avatar se suspenden sus capturas, seguimiento y página; al volver se respeta el FPS solicitado y el límite térmico.

Esto **no convierte el navegador ni VTuber WebGL en una ruta totalmente sin copias CPU**. WebGL todavía necesita lectura de píxeles; PBO reduce las esperas y el puente binario evita el envío HTTP en WebViews compatibles. Las fuentes multimedia FFmpeg/VLC/NDI conservan sus rutas respectivas; no se convierten automáticamente al tipo de fuente de video hardware.

### Audio de cada página

Opción **Capturar audio de esta página (Web Audio)**, desactivada por defecto para conservar el comportamiento de las fuentes existentes. Mezcla los elementos HTML5/WebRTC accesibles del documento en un único flujo PCM estéreo por fuente, con volumen, silencio y filtros OBS independientes.

- No pide MediaProjection ni captura el audio de otras aplicaciones.
- Los medios de otro origen necesitan permiso CORS; los iframes de otro origen y los grafos Web Audio que la página controla por su cuenta no se capturan automáticamente.
- Un elemento que ya tiene dueño Web Audio se conserva. No se toma control de un recurso extranjero sin CORS porque eso silenciaría su reproducción normal.
- La inyección repetida no duplica el grafo y los cambios de URL reutilizan el nodo del elemento y retiran los listeners anteriores.
- Evitar mezclar la misma señal también mediante Audio interno para no duplicarla.

### Salida NDI desde Herramientas

- **Herramientas → Salida NDI en red…** transmite el Programa mediante una salida nativa de OBS, con video RGBA y audio estéreo flotante planar. El diálogo permite definir el nombre anunciado y detener la salida.
- Requiere importar una biblioteca `libndi.so` para Android ARM64 que incluya las funciones de envío `NDIlib_send_*`; el runtime receptor por sí solo no habilita la emisión. Video/audio usan los formatos y el stride planar documentados por el SDK de NDI ([tipos de frames](https://docs.ndi.video/all/developing-with-ndi/sdk/frame-types), [NDI Send](https://docs.ndi.video/all/developing-with-ndi/sdk/ndi-send)).
- El menú y el diálogo quedaron visibles en el Samsung, y el APK compiló y se instaló. No se inició una emisión NDI en red: el runtime con funciones sender no estaba disponible en el dispositivo, así que la detección por receptores y la recepción de audio/video siguen pendientes de validación real.

### Temperatura, calidad y diagnóstico

- Pronóstico térmico a 10 segundos consultado cada 15 segundos, junto al estado Android y ahorro de batería. La recuperación espera 20 segundos para evitar oscilaciones.
- Se limita primero trabajo auxiliar: vistas, navegador, render/tracking del VTuber. Los ajustes de FPS y resolución de transmisión/grabación se conservan.
- El asistente prueba el Programa real durante 2 segundos de calentamiento y 12 de medida por candidato. Exige fotogramas codificados y FPS del motor al menos al 95 % del objetivo, hasta 1 % de retrasos y estado térmico inferior a severo. Una configuración que no inicia o no sostiene el contenido ya no se acepta por ser la última alternativa. Esta prueba inicial no sustituye una sesión larga.
- Las capacidades ausentes no se rellenan suponiendo soporte H.264 1080p60/Surface.
- **Herramientas → Diagnóstico de rendimiento** muestra render, codificadores hardware/CPU, retrasos, temperatura y memoria, y permite copiar un resumen sin claves de transmisión.
- La barra muestra utilización GPU cuando el controlador la expone; el tiempo de render se identifica como tiempo, no como porcentaje GPU.

## Comprobaciones locales

| Comprobación | Resultado/evidencia |
|---|---|
| Motor/frontend C++ y APK JNI/Java | Compilaciones correctas; `out/performance-checks/native-build.log`, `final-apk-build.log` y `out/android/logs/assemble-debug.log` |
| Política térmica y pacing de vistas | `THERMAL_POLICY_PASS`, `PREVIEW_PACING_PASS`; incluye pronóstico desconocido, anticipación, estado real más caliente y ahorro de batería |
| Audio del navegador | `BROWSER_AUDIO_PASS`: PCM16, canales estéreo, límites de origen, reinyección y cambio de medio |
| Sintaxis JavaScript y controlador Python | Comprobada con Node y `compile()` |
| Reproducción de fuentes OBS | `prepare-source.ps1` verifica manifest; `0001` + `0002` aplican desde una copia limpia de upstream en `out/performance-repro` |
| Paquete | ZIP íntegro, firma de depuración v2 válida, 29 bibliotecas ARM64 con segmentos LOAD de al menos 16 KB; `apk-integrity.json`, `apk-signature.log`, `elf-alignment.json` |

## Pruebas breves en Samsung SM-S931B

Android API 37, Adreno 830, conexión USB. Los destinos fueron exclusivamente el receptor local mediante `adb reverse tcp:1935 tcp:1935`; no se inició una emisión pública.

- **Patrón RGBA y audio:** rojo opaco, rojo semitransparente sobre azul y zona transparente llegaron al Programa. Píxeles observados aproximados: `(250,1,0)`, `(182,1,182)` y `(0,0,249)`. El tono de la página alcanzó su medidor alrededor de −12,25 dB; la recepción H.264/AAC se decodificó sin errores.
- **Uso compartido:** dos RTMP y MP4 con un codificador H.264 Surface; al detener el primero, el segundo y la grabación continuaron. Un tercer destino a 2000 kbps usó una segunda instancia; al detenerlo se volvió a una.
- **Visibilidad y tamaño:** cambios 640×360/1280×720 y ocultar/mostrar conservaron la composición; durante la ocultación el patrón desapareció y después regresó. No hubo respaldo de carga CPU ni descartes por buffers ocupados en esas pruebas.
- **VTuber:** avatar procedural 3D visible en el Programa mediante el puente binario; evidencia `vtuber-bridge-program.jpg`. No es una nueva validación de archivos Live2D o VRM externos ni del seguimiento físico de la cara.
- **Vertical:** dos RTMP horizontales, dos verticales 720×1280 y grabación horizontal con dos codificadores H.264. Se rechazó un tercer lienzo vertical incompatible 1080×1920. Cinco archivos recibidos/grabados se decodificaron sin errores; motor a 30 FPS durante 18 segundos. Evidencia: `out/performance-checks/vertical-20261125-102544/`.

## Duración, recuperación y cierre

La prueba controlada de 1800 segundos se ejecuta sobre la escena existente **Camara +biblia**. Su configuración actual contiene multimedia FFmpeg, cámara Android y una fuente UVC; el dispositivo USB no estaba conectado. Se conservó el Programa/Vista Previa anterior para restaurarlo al finalizar. Hay dos RTMP locales y MP4 simultáneos; el receptor cierra las conexiones al segundo 60 para comprobar reconexión y continuidad de la grabación.

La prueba completó 1800 segundos: 30.000 FPS mínimos, un codificador hardware, cero codificación de video por CPU y temperatura de batería de 42,7 a 44,9 °C con el teléfono conectado a carga. El control local interrumpió ambos destinos a los 60 segundos; se observaron reconexión y grabación continua. FFmpeg decodificó completamente el MP4 (54.188 cuadros, 1806,2 s) y los cuatro segmentos RTMP; audio y video estuvieron sincronizados, sin errores de decodificación. Estado térmico máximo Android: 3. No atribuir el aumento de temperatura exclusivamente a OBS ni inferir una mejora frente a una línea base inexistente. Evidencia: `out/performance-checks/real-20261125-102859/`.

Las pruebas de cierre mediante Archivo → Salir pasaron tres ciclos con capturas remotas concurrentes. Para el cierre desde Recientes se corrigieron dos carreras: el hilo de `AudioRecord` ya no hace `join()` mientras retiene el bloqueo que necesita su limpieza, y `CaptureService` detiene micrófono y captura de pantalla/audio antes de que Qt cierre OBS. El servicio rechaza nuevas órdenes de captura cuando empezó el cierre, para que una orden pendiente no arranque AudioRecord tras destruirse las escenas.

En el Samsung SM-S931B, tres cierres controlados desde Recientes terminaron sin el mensaje `FORTIFY: pthread_mutex_lock called on a destroyed mutex`. En cada cierre se detuvieron los productores antes de que Qt terminara de vaciar las escenas; dos pasadas tenían micrófono y audio de pantalla activos, y otra tenía el micrófono activo. El proceso terminó en los tres casos. Esta comprobación cubre esas dos configuraciones en un dispositivo; conviene repetir con cámara, USB y más dispositivos antes de considerar cerrado todo el ciclo de vida. Los registros históricos de la regresión permanecen en `out/performance-checks/recents-runtime2.log` y `out/performance-checks/recents-runtime.log`.

## Artefactos y reproducción

- APK de la prueba de duración: `artifacts/performance/obs-android-performance-endurance-arm64-debug.apk`, SHA256 `0c05a26ec30452dc57cfaf36a7cb961b76ebe5fedb453a1fc4a53aa27cfca80c`.
- APK actualizado con salida NDI y cierre ordenado de micrófono/audio de pantalla: `artifacts/performance/obs-android-performance-arm64-debug.apk`; su SHA256 se registra en `out/performance-checks/apk-integrity.json`. Instalada en el Samsung. La emisión NDI real sigue sin validarse porque falta el runtime NDI sender ARM64 en el dispositivo.
- Son compilaciones de depuración; el tamaño y SHA de cada una están registrados en `out/performance-checks/apk-integrity.json`. No se publicaron ni se cambió el almacén de firma.

```powershell
& .\scripts\prepare-source.ps1
& .\tools\cmake-3.30.8-windows-x86_64\bin\cmake.exe --build out/libobs-arm64 --target obs-studio obs-android-media-probe --parallel 6
& .\scripts\build-apk.ps1
& .\scripts\test-thermal-policy.ps1
node android/native-tests/browser-audio-test.cjs
python scripts/test-performance-device.py --serial '<serial ADB>' --mode smoke --seconds 20
python scripts/test-performance-device.py --serial '<serial ADB>' --mode real --seconds 1800 --interrupt-at 60
```

Instalar y abrir el APK debug antes de ejecutar el controlador. El teléfono debe estar desbloqueado; los tests no sortean el bloqueo ni permisos Android. El controlador no instala ni detiene una emisión existente; la prueba rechaza salidas ya activas. Su perfil de validación actual espera 1920×1080 a 30 FPS. `npm ci --prefix scripts/rtmp-receiver --ignore-scripts` prepara el receptor. La prueba local se detiene si Android informa estado térmico crítico.

Las condiciones de USB/carga y ambiente impiden atribuir un porcentaje de mejora térmica a estos cambios. No se han repetido servidores públicos RTMPS/SRT, cambios Wi-Fi/datos móviles, MediaProjection prolongada, USB físico ni otros fabricantes.

## Referencias primarias

- [Concurrencia MediaCodec: límite orientativo](https://developer.android.com/reference/android/media/MediaCodecInfo.CodecCapabilities#getMaxSupportedInstances()).
- [API térmica Android](https://developer.android.com/games/optimize/adpf/thermal).
- [WebMessageCompat y ArrayBuffer](https://developer.android.com/reference/androidx/webkit/WebMessageCompat).
- [Web Audio y restricciones CORS](https://www.w3.org/TR/webaudio/).
