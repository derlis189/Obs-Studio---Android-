# Base común para Android e iOS

La interfaz gráfica actual se conserva. Qt sigue siendo la capa de interfaz y libobs el motor compartido; este paso prepara la frontera entre el motor y las APIs nativas de cada sistema para que un futuro cliente iOS pueda reutilizar la misma experiencia y el mismo flujo de medios.

## Contrato de medios

`android/app/src/main/cpp/mobile-media-contract.h` define descriptores independientes del sistema operativo para:

- Metadatos de video: tamaño, timestamp en nanosegundos, formato, rango y matriz de color, rotación y espejo.
- Metadatos de audio: frecuencia, canales, cantidad de muestras por canal, timestamp y formato de muestra.
- Capacidades de codificación: límites de tamaño/FPS, cantidad de instancias, aceleración por hardware y aceptación de superficies nativas.

El contrato no incluye cabeceras ni tipos de Android o Apple y no posee buffers. Cada adaptador debe conservar la propiedad y el ciclo de vida de sus recursos nativos. En Android, las rutas JNI de cámara y captura de pantalla ya rellenan los metadatos de video; la ruta con `AHardwareBuffer` conserva el buffer Android y lo entrega al camino GPU existente. El camino de respaldo que recibe planos YUV/RGBA reutiliza ahora un buffer CPU reciclable para evitar asignar memoria nueva en cada cuadro. La conversión YUV de ese respaldo sigue en CPU; el camino habitual en dispositivos compatibles es el de `AHardwareBuffer`/GPU.

## Adaptadores por plataforma

| Área | Android actual | Adaptación futura para iOS |
| --- | --- | --- |
| Cámara | Camera2/JNI y `AHardwareBuffer` o planos YUV | AVFoundation, `CVPixelBuffer` y sincronización de timestamps |
| Captura de pantalla | MediaProjection y buffer GPU/RGBA | ReplayKit y `CVPixelBuffer` |
| Codificación | MediaCodec y capacidades Android | VideoToolbox y capacidades del dispositivo |
| Audio | Captura Android y audio de libobs | AVAudioSession/AVAudioEngine mapeados al contrato de audio |
| Interfaz y escenas | Qt/libobs | Reutilizar Qt/libobs donde el port y las dependencias de iOS lo permitan |

La tabla describe el límite de integración previsto. `ios/` ahora contiene una prueba nativa separada de cámara/VideoToolbox; no es todavía un target de producto OBS/libobs ni conecta esas APIs al motor. La diferencia de APIs y formatos se resuelve dentro de cada adaptador antes de entregar los datos al motor común.

## Siguiente etapa técnica

1. Ejecutar `ios/tcstudio-ios-probe` en Xcode y dispositivo, registrando permisos, cuadros/segundo y uso de encoder hardware.
2. Crear un target iOS para Qt/libobs y adaptar su backend gráfico a Metal; el target Android/EGL no se puede reutilizar tal cual en iOS.
3. Conectar la captura AVFoundation/ReplayKit y VideoToolbox al source/encoder OBS, traduciendo `CVPixelBuffer`, paquetes y timestamps a las APIs compartidas.
4. Medir CPU, GPU, memoria y temperatura en cada plataforma antes de decidir si se sustituye otra ruta compartida, como la decodificación FFmpeg.

Este contrato es una base inicial: no cambia la GUI de Android y todavía no estandariza el control del ciclo de vida de los adaptadores. La prueba iOS es solo de viabilidad de captura/codificación y no sustituye la interfaz OBS.
