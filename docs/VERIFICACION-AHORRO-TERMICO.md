# Ahorro de capturas y vistas previas — 24/11/2026

## Cambios aplicados

El ajuste reduce trabajo auxiliar de CPU, GPU y memoria. No mueve la composición de video a un lienzo software ni modifica los FPS, resolución o codificador configurados para transmitir/grabar.

### Navegador

- Las copias `PixelCopy` se solicitan por avisos de nuevos frames de la superficie, respetando el máximo de FPS de la fuente y el límite térmico. Una página quieta no mantiene un temporizador de capturas periódicas.
- `BrowserFrameGate` conserva la revisión pendiente cuando llega una imagen nueva durante una copia en curso. Solo se marca entregada la revisión que realmente se copió; una copia fallida se reintenta con espera.
- Solo puede haber una copia pendiente por fuente. Se agrupan los avisos para evitar acumular tareas en el hilo principal.
- Al ocultar una fuente se detienen sus copias y se oculta la ventana privada, aunque la opción antigua `shutdown` esté desmarcada. Puede completarse una entrega que ya hubiera comenzado.
- La opción se presenta como **Pausar también la página cuando no esté visible**: desmarcada conserva la sesión, marcada también llama a la pausa de WebView. No se garantiza detener toda la decodificación/red de Chromium conservando la sesión.
- Al mostrar la fuente o abrir Interactuar se solicita un frame nuevo. Interactuar utiliza avisos de dibujo del diálogo; al cerrar vuelve a la superficie privada.

### Vistas previas y estado térmico

El límite se aplica en `render_display`, antes de dibujar y presentar cada display auxiliar. Las vistas de OBS no vuelven a aplicar un segundo límite que pudiera reducir accidentalmente la frecuencia por jitter. Un cambio de tamaño/color fuerza un refresco inmediato.

| Estado Android | Máximo de vistas previas | Máximo de captura de navegador |
|---|---:|---:|
| Normal (0) | 15 FPS | 60 FPS |
| Leve (1) | 15 FPS | 30 FPS |
| Moderado (2) | 12 FPS | 24 FPS |
| Severo (3) | 10 FPS | 15 FPS |
| Crítico o superior (4–6) | 5 FPS | 10 FPS |

El navegador siempre respeta un FPS solicitado inferior al máximo térmico. La carga baja en cuanto aumenta el estado térmico; la recuperación espera 20 segundos sin un nuevo cambio de estado para evitar oscilaciones. La opción de FPS de la fuente no se sobrescribe.

El controlador escucha a `PowerManager` usando el contexto de aplicación. La interfaz de salud informa los límites efectivos y explica en su tooltip que la temperatura mostrada es la de batería. Las transiciones y gráficos conservan su contenido y sus ajustes.

## Compilación e instalación

```powershell
& .\tools\cmake-3.30.8-windows-x86_64\bin\cmake.exe --build out/libobs-arm64 --target libobs obs-studio --parallel 6
& .\scripts\build-apk.ps1
& .\scripts\test-thermal-policy.ps1
```

- Motor, frontend, JNI y Java compilados; APK generado correctamente.
- Instalado por ADB con `install -r` y abierto en Samsung SM-S931B, Android API 37, conservando datos.
- APK: `artifacts/thermal/obs-android-thermal-arm64-debug.apk` (114.525.681 bytes).
- SHA256: `82DE8AC2DF8187EC19506319D42768D513F7ACB1F2E6B963F88604945F117D19`.
- Integridad ZIP y firma de depuración v2 verificadas. Las 29 bibliotecas ARM64 mantienen alineación mínima de 16 KB; las clases térmicas y la API de límites están empaquetadas.

## Pruebas locales

- `THERMAL_POLICY_PASS`: límites para estados 0–6, respeto a FPS solicitados, clamping y comportamiento de imágenes quietas.
- Notificaciones concurrentes: 20.000 avisos desde dos hilos sin pérdida de revisiones.
- Regresión de una imagen recibida durante `PixelCopy`: la entrega anterior conserva pendiente la imagen nueva.
- `PREVIEW_PACING_PASS`: motor simulado a 30/60 Hz, vistas a 5/10/12/15 Hz, jitter, cambios de tamaño y recuperación tras una pausa sin ráfagas de frames atrasados.
- Los tests compilan el código de política/compuerta y el helper de pacing utilizados por producción.

## Prueba de dispositivo con escena fija

Con colaboración del usuario se mantuvo **Camara +biblia** durante 31,9 segundos, con siete muestras:

- Motor: 30 FPS informados en todas las muestras; sin grabación ni transmisión activas.
- Estado térmico: moderado (2). El controlador aplicó vistas hasta 12 FPS y captura de navegador hasta 24 FPS.
- VDO.Ninja: video activo, 1334×768, `readyState=4`, sin error del elemento video. Los frames decodificados avanzaron de 232 a 396; se mantuvo la recepción durante el periodo completo.
- Los contadores de copias GPU progresaron aproximadamente al ritmo del contenido recibido, por debajo de los 30 FPS configurados de la fuente.
- GPU: lecturas del driver entre 17 y 19 %. CPU del medidor de la app: media 22,4 %. Estas lecturas no atribuyen todo el consumo del dispositivo a la app ni incluyen necesariamente todos los procesos Chromium en el porcentaje CPU.
- Batería: 44,6 → 45,0 °C. **Esta prueba no demuestra una reducción térmica sostenida.** La muestra anterior mezcló escenas y no sirve para calcular un porcentaje controlado de mejora; además el teléfono estaba conectado por USB.

En el primer arranque, mientras se cambiaban escenas, los registros comprobaron pausas y reanudaciones del navegador conservando la sesión (`pageKept=true`). Durante los intervalos ocultos el contador se mantuvo estable, salvo una entrega ya iniciada antes de ocultar la fuente.

Evidencia: `out/thermal-checks/host-tests.log`, `native-build.log`, `apk-build.log`, `runtime-initial.log`, `fixed-scene-runtime.log`, `fixed-scene-verification.json`, `device-summary.json` y `apk-verification.json`.

## Límites y seguimiento

- Se requiere una prueba más larga, con la misma escena, condiciones de carga y ambiente comparables, para cuantificar consumo y temperatura.
- No se inició una transmisión/grabación real ni se forzaron estados térmicos del sistema. Los estados adicionales se verificaron mediante las pruebas de política; el estado moderado sí se observó en Android.
- Interactuar, sesiones largas en segundo plano, muchas fuentes simultáneas y cambios de tamaño no tienen una nueva prueba de dispositivo completa en esta tarea.
- Al retirar la app desde Recientes se registraron mensajes `FORTIFY` durante la salida voluntaria del proceso. Ese comportamiento también aparece en versiones anteriores a estos ajustes y queda fuera de la corrección térmica; no se afirma resuelto.

## Reproducción del árbol adaptado

`patches/obs-studio/0025-android-thermal-preview-pacing.patch` contiene únicamente el cambio sobre los archivos OBS intervenidos y el helper nuevo. Se verificó con `git apply --check --reverse`. Se actualizaron sus entradas en `android/source-manifest.json`; los cambios Android están directamente en `android/app/src/main/`.

La copia anterior de los archivos intervenidos está en `out/thermal-checks/baseline/`. Se conservaron los cambios previos del usuario y las mejoras de Move, Stinger y VDO.Ninja de esta sesión.
