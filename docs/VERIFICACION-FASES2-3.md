# OBS Android: fases 2 y 3, 23/11/2026

Fuente oficial: commit `0c790bd0151fb9f574867e025ecf57e6eedc58e2`. El checkout `upstream/obs-studio` permanece intacto; los parches se aplican en `out/src/obs-studio`.

## Fase 2: compilación y ejecución nativas aprobadas

Se construyó el target **libobs original completo**, con escenas, fuentes, mezcla, encoder/output/service y las utilidades originales. No es una biblioteca alternativa con funciones de relleno. Compila C17/C++17 para Bionic/ARM64/API 29 con NDK r29, CMake 3.30.8 y enlace `--no-undefined`.

Dependencias compiladas desde los commits de `dependencies-lock.json`: FFmpeg 8.0.1, jansson 2.14; headers SIMDe 0.8.2 y Uthash 2.3.0. Libcaption sigue siendo el código incluido por OBS; zlib procede del NDK. FFmpeg y jansson se enlazan estáticamente dentro de libobs; sus fuentes/licencias y opciones deben acompañar la distribución. No se habilitaron componentes nonfree ni GPL opcionales de FFmpeg. Avfilter/avdevice no son necesarios para este core mínimo y permanecen pendientes de la integración completa de medios.

En el Samsung SM-S931B conectado mediante ADB Wi-Fi se ejecutó `obs-android-core-test`: tres ciclos de arranque/cierre, escenas anidadas reales, búsqueda por UUID v4, volumen/mute, JSON y arranque del motor de audio estéreo a 48 kHz. No equivale a captura de micrófono ni a una medición de mezcla acústica. El test de core sin gráficos emite mensajes de depuración sobre ausencia de contexto al liberar recursos; no reclama renderizado.

También se compiló, instaló y abrió un APK de diagnóstico JNI. Su primer empaquetado omitía `libobs.so`: el `UnsatisfiedLinkError` real motivó añadir explícitamente las bibliotecas a `jniLibs`. La versión corregida cargó el motor, creó una escena y cerró correctamente dentro del proceso de aplicación.

Evidencias: `out/phase2/report.json`, `out/phase2/logs/`, APK de diagnóstico conservado en `artifacts/phase2/obs-android-core-arm64-debug.apk`. El hash de fase 2 corresponde a ese momento: las bibliotecas cambian al incorporar la fase 3.

## Fase 3: composición, PNG, transiciones y superficies verificadas

El backend adapta los archivos originales de `libobs-opengl`; conserva el compositor, el parser de efectos y sus módulos. Añade EGL/ANativeWindow, carga real de funciones GLES y traducción GLSL ES. La implementación inicial exige **GLES 3.2**, extensiones sRGB decode/write-control y superficies EGL sRGB; no se presenta como soporte para todos los teléfonos GLES 3.0.

Resultados nativos observados en Adreno 830:

```text
PIXELS red=255,0,0 blue=0,0,255 image=240,243,248
IMAGE_COMPOSITE_PASS PNG=decoded sRGB=verified
FADE_PASS original_obs_transition=500ms
CUT_PASS original_obs_transition_set=verified
GRAPHICS_TEST_PASS real_obs_scene=2_sources EGL=android GLES=3.2 pixels=verified
```

El mensaje final menciona dos fuentes de color; la prueba ampliada añade además una fuente PNG y una escena destino para corte/fundido. La comprobación mide muestras de composición y canales sRGB, no todos los píxeles ni todos los filtros.

El APK muestra dos superficies reales Program/Preview. Se observó el PNG decodificado por `image-source`, se arrastró la imagen en Preview con entrada ADB y se pasó esa escena a Program usando el fundido original. Se capturaron imágenes antes/después. Se giró la Activity a paisaje y retrato y se volvió desde Inicio: ambas superficies recuperaron la escena y la posición editada. La ampliación con pellizco está implementada, pero no se ha verificado aquí mediante un gesto físico de dos dedos.

Capturas: `out/phase3/studio-landscape.png`, `studio-after-fade.png` y `studio-resumed.png`. Logs de GPU y ciclo de vida en `out/phase3/logs/`.

## Errores reales corregidos

- La detección de FFmpeg utilizaba el SO anfitrión y buscaba DLL; se cambió a selección por destino para Android.
- La compilación FFmpeg en Git Bash/Windows necesitó rutas 8.3 sin espacios y convertir los paths de los makefiles generados. El `echo` de listas largas se truncaba antes de escribir un response file: se usa la función `file` de GNU make. La instalación usa `V=1` para evitar el mismo límite del shell al copiar muchos headers. No se modifica el checkout FFmpeg.
- El enlace de rutinas NEON estáticas contra símbolos interponibles falló con relocaciones AArch64. `--exclude-libs,ALL` mantiene internos los símbolos de los archivos estáticos; los símbolos públicos de libobs conservan su visibilidad.
- GLSL ES rechazó conversiones implícitas de vectores, lod/bias enteros y dos divisiones por literal entero. Se añadieron conversiones explícitas conservando los efectos originales.
- `sampler2DRect` no existe en GLES: no se carga el efecto exclusivo de texturas rectangulares de escritorio. Las imágenes y superficies Android usan texturas 2D; OES de captura es una ampliación posterior.
- GLES no permite la combinación BGRA/sRGB de escritorio. Las texturas almacenan RGBA con sRGB correcto; las cargas y lecturas CPU BGRA intercambian canales explícitamente. La composición permanece en GPU. Esta ruta inicial de lectura CPU requiere optimización cuando se integre el encoder de superficie.
- El cargador original añade la extensión al path sin extensión; el adaptador inicial pasaba `.so` dos veces. El cargador Android ahora proporciona paths exactos al callback original y conserva el nombre lógico del módulo sin prefijo `lib`.

Los logs anteriores se conservan con sufijo `.previous-*`; no se convierten fallos esperados en éxitos de ejecución.

## Reproducir

```powershell
& .\scripts\fetch-source.ps1 -VerifyOnly
& .\scripts\bootstrap-tools.ps1
& .\scripts\build-dependencies.ps1
& .\scripts\prepare-source.ps1
& .\scripts\build-libobs.ps1 -CoreOnly
& .\scripts\test-core-device.ps1 -Serial '<serial ADB autorizado>'
& .\scripts\build-libobs.ps1
& .\scripts\test-graphics-device.ps1 -Serial '<serial ADB autorizado>'
& .\scripts\build-apk.ps1
```

`build-libobs.ps1 -CoreOnly` reproduce el objetivo mínimo; sin ese switch añade renderer y módulos de fase 3. El APK actual requiere estos últimos. El build APK utiliza el Gradle 8.13 instalado o el indicado mediante `-GradlePath`, JDK 21 de Android Studio y AGP 8.13.2. Todavía falta bootstrap autónomo de Gradle para una máquina nueva.

## Estado restante

Estas verificaciones no prueban captura Camera2, micrófono/audio interno, MediaProjection, encoder MediaCodec, RTMP/RTMPS/SRT, grabación, persistencia de una colección editada entre procesos, duración/batería ni varios fabricantes. Las fases 4–9 deben validarse de forma independiente. El APK de esta etapa es un port funcional del motor y la composición, todavía experimental.
