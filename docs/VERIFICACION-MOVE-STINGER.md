# Move Transition y Stinger — 24/11/2026

## Move Transition

- Zoom y desplazamientos usan la curva elegida. Back Out conserva su sobrepaso geométrico; opacidad, recorte y orden de capas quedan acotados.
- Las transiciones nuevas usan Zoom + Fundido y 900 ms. Los valores explícitos guardados conservan su prioridad.
- Las propiedades explican cómo compartir una fuente mediante **Agregar existente**. La prueba permite elegir dos escenas por UUID, ajustar su duración y consultar fuentes compartidas, salientes y entrantes con las mismas reglas del renderizador.
- La prueba usa un clon privado de la transición y conserva la escena del Programa. La demostración incluye una fuente compartida que cambia de posición y tamaño.
- Se recorren grupos y escenas anidadas sin recorte ni filtros, hasta 16 niveles. Las matrices padre se componen con las de sus hijos. La reconstrucción conserva escala no uniforme, rotación, espejo y cizallamiento.
- Los contenedores con recorte o filtros conservan su composición como una sola fuente. Las mezclas especiales, filtros de escena y lienzos de distinto tamaño usan fundido de la composición completa.
- `obs_sceneitem_get_render_crop` expone el recorte efectivo, incluyendo el producido por **Recortar al recuadro**, para mantener la geometría durante Move.
- Se utiliza mezcla premultiplicada y muestreo sRGB, siguiendo la composición normal de OBS. Su resultado visual en el dispositivo sigue pendiente.

## Stinger y formatos

- Stinger conserva el reproductor FFmpeg nativo. MOV y MP4 ya estaban en el filtro original: se amplió la lista y el selector Android ahora permite seleccionar videos aunque el proveedor no les asigne un MIME reconocido.
- Selector: MOV, MP4, M4V, MKV, WebM, AVI, TS, MTS, M2TS, MPG, MPEG, WMV, FLV, GIF, APNG y OGV, además de todos los archivos. La extensión no garantiza que su códec sea compatible.
- En Android, las nuevas instancias prefieren decodificación por software para preservar alfa. MOV con **ProRes 4444**, **QuickTime Animation/QTRLE** o **PNG** es una opción para transparencia; ProRes 422 y MP4/H.264 suelen ser opacos. WebM con alfa requiere un decodificador adicional como libvpx, ausente en esta compilación; no se afirma soporte de alfa WebM.
- La vista previa utiliza los ajustes recién editados incluso cuando las propiedades difieren la actualización hasta Aceptar. Incluye información de disponibilidad del archivo y dimensiones decodificadas.
- Se protege la duración desconocida/negativa y el conteo de frames cero para que no produzcan divisiones inválidas ni duraciones enormes. También se corrige la propagación inicial del modo track matte.

## Compilación

Motor `libobs`, frontend Qt y módulo `obs-transitions`: compilados y enlazados para Android ARM64. La JNI también se compiló y enlazó con la API de recorte nueva.

```powershell
& .\tools\cmake-3.30.8-windows-x86_64\bin\cmake.exe --build out/libobs-arm64 --target libobs obs-studio obs-transitions --parallel 6
& .\scripts\build-apk.ps1
```

Logs: `out/move-stinger-checks/native-build.log`, `native-build-final.log`, `jni-build.log` y `out/android/logs/assemble-debug.log`.

APK resultante: `artifacts/move-stinger/obs-android-move-stinger-arm64-debug.apk` (114.524.583 bytes).

- SHA256: `EAE97B7A650AA3E324E005D47B8810392B900BC46C7F394BC55FE95DE429828D`.
- Firma de depuración verificada por `apksigner`, esquema v2.
- ZIP íntegro; implementación Move, interfaz de prueba, API de recorte y ayuda Stinger presentes.
- Las 29 bibliotecas nativas son ARM64 con segmentos de carga alineados a 16 KB como mínimo.
- Evidencia: `out/move-stinger-checks/apk-verification.json`.

## Pruebas locales

```powershell
& .\scripts\test-move-motion.ps1
python .\scripts\create-transition-test-media.py
```

- `MOVE_MOTION_PASS`: pruebas del código de producción para las cinco curvas, extremos y sobrepaso, aplicación de curvas a Zoom/Deslizar, límites de opacidad y reconstrucción de matrices con espejo/cizallamiento.
- Inventario del módulo ARM64 FFmpeg: presentes los decodificadores H.264, HEVC, ProRes, QTRLE, VP8, VP9, PNG, FFV1 y GIF. Evidencia: `out/move-stinger-checks/packaged-decoder-inventory.log`.
- Se generaron seis archivos de un segundo en `out/move-stinger-checks/media/`: MOV Animation, MOV ProRes 4444, MP4 H.264, WebM VP9, MKV FFV1 y AVI PNG.
- Los archivos se decodificaron en la **PC**. Los cuatro archivos con alfa conservaron valores 0 y 255 en las mitades transparente y opaca; los dos opacos devolvieron 255 en ambas. Evidencia: `media/fixtures.json`.
- La generación/decodificación de archivos de prueba en la PC y el inventario de decodificadores no verifican la reproducción ni la composición en Android.

## Instalación y comprobación visual pendientes

`adb devices -l` y `adb mdns services` no detectaron teléfonos. No se instaló ni abrió este APK en un dispositivo, y no se verificó captura, grabación o transmisión con estas transiciones.

Cuando se conecte el teléfono:

1. Instalar el APK conservando los datos de la app. Abrir Move y probar la demostración a 900 ms con Suave, Lineal y Rebote.
2. Elegir dos escenas reales con una fuente compartida, en posiciones y tamaños diferentes. Comprobar que la lista de coincidencias sea correcta y que el Programa conserve su escena durante la prueba.
3. Repetir con un grupo girado/escalado, una escena anidada, espejo, recorte manual y recorte al recuadro. Comparar extremos de la transición con cada escena original.
4. Probar semitransparencias, orden de capas y filtros. Comprobar el fundido conservador para composiciones especiales.
5. Crear Stinger, elegir sucesivamente los seis archivos y usar Vista previa de transición. En los cuatro con alfa debe verse la escena bajo la mitad izquierda. En MP4/WebM opacos debe verse el video completo.
6. Probar punto de cambio por tiempo y por frames, repetir varias veces, cancelar las propiedades y abrirlas de nuevo. Elegir un archivo inválido para comprobar el diagnóstico y la finalización del cambio de escena.

## Fuentes y reproducción de cambios

- Implementación Android: `android/app/src/main/cpp/android-move-transition.cpp` y `move-motion.h`.
- Cambios del árbol OBS adaptado: `patches/obs-studio/0024-android-move-stinger.patch`, generado contra la copia local anterior a esta tarea y verificado con `git apply --check --reverse`.
- Entradas afectadas actualizadas en `android/source-manifest.json`.
- Las modificaciones anteriores del usuario se conservaron; la copia previa de los archivos intervenidos está en `out/move-stinger-checks/baseline/`. `upstream/obs-studio` permaneció limpio.
