# Licencias y procedencia

OBS declara GPL versión 2 o posterior en su [README oficial](https://github.com/obsproject/obs-studio/blob/0c790bd0151fb9f574867e025ecf57e6eedc58e2/README.rst); su texto GPL se conserva en `upstream/obs-studio/COPYING` y se copia sin cambios a `COPYING` en esta raíz. Los scripts y código original de diagnóstico añadidos aquí usan `SPDX-License-Identifier: GPL-2.0-or-later`.

Los archivos de terceros conservan sus propios avisos y licencias. No se atribuye a este proyecto la autoría del código OBS. Las modificaciones posteriores deben quedar identificadas en parches separados, con su código fuente correspondiente y scripts de compilación al distribuir binarios.

FFmpeg es LGPL 2.1 o posterior para su configuración base; ciertas opciones lo convierten en GPL y otras combinaciones pueden impedir redistribución. Ver [licencia y condiciones oficiales de FFmpeg](https://ffmpeg.org/legal.html). La compilación actual conserva la configuración exacta sin `--enable-gpl` ni `--enable-nonfree` y documenta las licencias efectivamente habilitadas; no se presupone que activar FDK-AAC u otra dependencia sea compatible con cualquier configuración.

Las dependencias efectivamente compiladas están fijadas por commit en `dependencies-lock.json`: FFmpeg 8.0.1 (configuración base sin `--enable-gpl` ni `--enable-nonfree`), jansson 2.14 (MIT), SIMDe 0.8.2 (MIT) y Uthash 2.3.0 (BSD revisada). FFmpeg y jansson se enlazan estáticamente dentro de libobs; SIMDe y Uthash aportan headers. Libcaption y GLAD conservan los avisos originales del árbol OBS; zlib y libc++ proceden del NDK. Los archivos `LICENSE`, `COPYING*` y avisos por archivo de las fuentes fijadas son la referencia para preparar los avisos de distribución. La configuración exacta de FFmpeg queda en `out/build/ffmpeg-arm64/ffbuild/config.mak` y el script `scripts/build-ffmpeg.sh`.

CMake portable incluye los avisos originales de Kitware dentro de `tools/`. El NDK y el SDK conservan sus licencias de instalación; no se redistribuyen aquí como parte de una aplicación. Los APK debug locales son artefactos de desarrollo; el paquete de distribución pública con fuentes y avisos aún debe prepararse y verificarse.

El APK final deberá ir acompañado de su código fuente correspondiente, parches, scripts/configuraciones y avisos de todas las dependencias distribuidas conforme a sus licencias. No se promete compatibilidad de plugins binarios Windows ni se presenta esta derivación como un producto Android oficial de obsproject.


## Dependencias añadidas después de la fase 2

| Componente | Licencia o procedencia a conservar |
| --- | --- |
| Mbed TLS 3.6.8 | Apache-2.0 según el aviso de la versión fijada |
| Haivision SRT 1.5.4 | MPL-2.0 |
| Qt 6.9.3 | Avisos LGPLv3/GPLv3 de los módulos concretos de la instalación |
| LibVLC Android 3.7.8, cuando se incorpora | Avisos de VideoLAN y de los componentes del AAR |
| curl 8.11.1 | Licencia curl |
| omtplugin, libomtnet y libvmx, cuando se incluye OMT | MIT. Revisiones fijadas en `dependencies-lock.json`, avisos en `android/app/src/main/assets/licenses/omt/` (empaquetados en `assets/licenses/omt/` del APK) |
| nlohmann/json 3.11.3 | MIT |
| androidx.webkit:webkit 1.14.0 | Apache-2.0 (`android/app/build.gradle.kts`) |
| obs-advanced-masks v1.6.1 | GPL-2.0-or-later (revisión fijada en `dependencies-lock.json`) |
| obs-stroke-glow-shadow v1.6.1 | GPL-2.0-or-later (revisión fijada en `dependencies-lock.json`) |
| Three.js, PixiJS, `@pixiv/three-vrm` y `@pixiv/three-vrm-animation` 3.1.2 | MIT; avisos de las versiones y archivos incluidos en los assets (`vtuber/lib/`) |
| MediaPipe Tasks Vision 0.10.35 | Apache-2.0; aviso incluido en `android/app/src/main/assets/licenses/mediapipe/` |
| MediaPipe Selfie Segmenter, Face Landmarker y Hand Landmarker | Modelos Apache-2.0, SHA-256 y procedencia registrados en `android/app/src/main/assets/licenses/mediapipe/NOTICE.txt` |
| Inochi2D (compatibilidad de lectura `.inp` / `.inx`) | Lector propio implementado en JavaScript/Canvas2D para el formato abierto Inochi2D; no se redistribuye código de terceros de Inochi2D |
| Biblia RV1909 | Texto Reina-Valera 1909. Verificar la procedencia de la transcripción y sus avisos antes de publicar |
| Open-Meteo (API externa opcional) | Datos meteorológicos bajo CC BY 4.0 cuando el usuario habilita clima automático en la fuente Fecha, Hora y Clima |
| VDO.Ninja (servicio externo opcional) | Utilizado únicamente como URL remota configurable en el navegador integrado para llamadas WebRTC; no se redistribuye código fuente de VDO.Ninja |
| Advanced Scene Switcher (cuando se compila) | GPL-2.0-or-later |

El empaquetado excluye el runtime NDI y Live2D Cubism Core. Los respectivos selectores permiten importarlos en el dispositivo. Antes de distribuir, revisa los avisos efectivos de la compilación, los requisitos de Qt y de las dependencias Apache-2.0, y la versión GPL aplicable a la obra combinada. La exclusión de los runtimes no sustituye esa revisión.
