# Prueba nativa iOS

Este target es un spike para evaluar en iPhone/iPad la captura de cámara de AVFoundation y la codificación H.264 de VideoToolbox antes de integrarlas con libobs. Muestra cuadros capturados/codificados, resolución y si VideoToolbox informa que usa hardware. No cambia la app Android ni pretende ser todavía la app OBS para iOS.

## Requisitos

- macOS con Xcode y sus iOS SDKs instalados.
- Qt 6.9.3 para iOS y su Qt host correspondiente.
- Un simulador para validar que compila y abre; un iPhone/iPad es necesario para validar cámara y encoder hardware.

Qt genera un proyecto Xcode desde CMake para iOS; la compilación y ejecución requieren Xcode en macOS. Configura el kit de Qt y ejecuta:

```sh
export QT_IOS_ROOT="$HOME/Qt/6.9.3/ios"
bash ./ios/build-probe.sh
```

Abre `out/ios-probe/TCStudioIOSProbe.xcodeproj` con Xcode, selecciona un destino iOS y ejecuta `tcstudio-ios-probe`. En un dispositivo acepta el permiso de cámara. La ventana muestra contadores de entrada y salida; `Encoder hardware: no / no informado` significa que el sistema no confirmó aceleración o que la consulta no estuvo disponible, no que se haya usado CPU con certeza.

## Alcance de este spike

- Lee el `CVPixelBuffer` nativo de `AVCaptureVideoDataOutput` y lo envía directamente a `VTCompressionSession`.
- Rellena `VideoFrameMetadata` del contrato compartido, sin copiar el cuadro a un buffer CPU propio.
- No inicializa libobs, no inicia una salida RTMP, no captura la pantalla completa y no implementa todavía el frontend OBS.

El siguiente paso, después de compilarlo y observarlo en un dispositivo Apple, es integrar el buffer nativo y los paquetes H.264 al encoder/source OBS, portar el backend gráfico Metal de libobs a iOS y preparar el target de Broadcast Upload Extension para captura de pantalla. ReplayKit de pantalla completa requiere validar ese flujo de extensión por separado.
