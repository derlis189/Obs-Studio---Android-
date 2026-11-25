# Navegador: captura GPU de VDO.Ninja — 24/11/2026

## Diagnóstico

En Samsung SM-S931B, Android API 37, el enlace configurado ya era de visualización (`view`) y la página recibía video. Mediante depuración local de WebView se observaron `readyState=4`, reproducción activa, 1278×651 y 171 fotogramas decodificados, sin error del elemento video.

La implementación capturaba la página mediante `WebView.draw(Canvas(bitmap))`. Chromium documenta que la captura por software omite funciones como video y WebGL y recomienda una ventana `Presentation` en un `VirtualDisplay` para capturas de WebView: [documentación de Chromium](https://chromium.googlesource.com/chromium/src/+/refs/tags/108.0.5359.153/android_webview/docs/software_draw_deprecated.md).

Los registros también contenían fallos DNS de algunos TURN/STUN. La recepción comprobada de video permite separar esos mensajes del fallo de captura; no se cambiaron DNS, certificados ni el enlace del usuario.

## Cambios

- `BrowserSourceManager.java` utiliza un display virtual privado con una ventana WebView acelerada. No refleja la pantalla física ni requiere MediaProjection.
- El consumidor `ImageReader` se drena en un hilo de captura para mantener fluida la cola del display.
- `PixelCopy` obtiene la superficie de la ventana de WebView antes de que el compositor del display aplaste la transparencia. Los píxeles llegan al mecanismo RGBA asíncrono ya existente de libobs.
- Solo hay una copia pendiente por fuente. Copia del bitmap y entrega a JNI se ejecutan fuera del hilo principal. Los buffers no se reciclan mientras una copia siga pendiente.
- En Interactuar, la copia usa la ventana del diálogo y el rectángulo de WebView; al cerrar se restaura la ventana privada. Cambios de tamaño recrean las superficies y la destrucción libera display, ventana y consumidor.
- Se registran códigos de errores de página/HTTP y líneas de errores JavaScript. Los nuevos mensajes de carga conservan únicamente el host y omiten parámetros de URL.
- Las propiedades explican el uso del enlace `?view=...` y la necesidad de mantener activo el emisor.

Referencia de la copia de ventanas: [PixelCopy de Android](https://developer.android.com/reference/android/view/PixelCopy).

## Compilación e instalación

```powershell
& .\scripts\build-apk.ps1
adb -s RFCY71VX7YV install -r android/app/build/outputs/apk/debug/app-debug.apk
adb -s RFCY71VX7YV shell am start -W -n org.obsandroid.port/.QtObsActivity
```

- Compilación: `BUILD SUCCESSFUL`; JNI y Java compilados.
- Instalación por ADB: `Success`; lanzamiento: `Status: ok`.
- Artefacto: `artifacts/browser-vdoninja/obs-android-browser-gpu-arm64-debug.apk`.
- SHA256: `A69E90E3B29EFF7D1F1A82614868C4783E3392DF0304C464D0135EC7DD1CC860`.
- El APK conserva las mejoras de Move y Stinger de esta sesión. La instalación se realizó con `-r`, sin borrar datos.

## Verificación en dispositivo

- Ventana privada creada a 1280×720 y primer fotograma GPU entregado a JNI.
- El mismo enlace real de VDO.Ninja volvió a recibir video tras reiniciar. La captura física del teléfono muestra el contenido de video de la fuente Navegador dentro del lienzo OBS, junto con la cámara y el fondo existentes.
- En una comprobación posterior, la sesión seguía activa, sin error del elemento video, con resolución del emisor 1334×768 y 2920 fotogramas decodificados. La salida de la fuente conserva su tamaño configurado de 1280×720.
- No aparecieron `FATAL EXCEPTION` ni señales fatales del proceso nuevo durante la comprobación. Los mensajes JavaScript iniciales de la página no impidieron la recepción ni la composición observada.
- No se inició grabación ni transmisión. Estas rutas no quedan verificadas por la inspección del lienzo.

Evidencia local en `out/browser-vdoninja-checks/`: estado de página anterior y posterior, `phone-gpu.png`, `final-runtime.log` y `apk-build.log`. Los archivos privados con enlaces o imágenes del usuario no forman parte del APK.

## Límites

La prueba controlada de alfa semitransparente y WebGL en el lienzo no se completó, por lo que no se declara validada. Tampoco se verificaron interacción con la página, cambios de tamaño, suspensión prolongada, audio hacia el mezclador OBS, varios navegadores simultáneos ni otros dispositivos. El audio de la fuente Navegador continúa fuera del alcance de este cambio de captura de video.
