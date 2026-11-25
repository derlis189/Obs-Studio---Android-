# Escenas, filtros y medios originales: fase 6 verificada

23/11/2026, Samsung SM-S931B conectado mediante ADB. APK `artifacts/phase6/obs-android-editor-arm64-debug.apk`, SHA256 `B603981FC02930791C3A6A29E8A72C08DE1D06C6C077A752950FBB45CDB786F9`.

Se compilan y enlazan los módulos originales `obs-filters` (`libobs-filters.so`, incluyendo RNNoise para supresión de ruido, corrección de color, recorte/pad, máscara/mezcla, compresor, limitador, expansor, puerta antirruido y ganancia) y `obs-ffmpeg` (`libobs-ffmpeg.so` + `shared/media-playback`, registrando `ffmpeg_source` para reproducción de medios locales/remotos).

## Hallazgos y correcciones técnicas en motor gráfico GLES 3.2 y `media-playback`

1. **Conversión GPU YUV -> RGB en GLES 3.2 (`libobs-opengl`):**
   - En Android OpenGL ES 3.2, `GS_BGRX` utiliza el formato interno `GL_SRGB8_ALPHA8` (4 canales), ya que `GL_SRGB8` de 3 canales no es renderizable como adjunto de color (`EXT_sRGB`).
   - Los shaders de conversión YUV sin canal alfa en `libobs/data/format_conversion.effect` (`PSPlanar420_Reverse`, `PSNV12_Reverse`, `PSPlanar422_Reverse`, `PSPlanar444_Reverse`) devuelven `float3 : TARGET`. En `gl-shaderparser.c` (`#ifdef __ANDROID__`), las salidas `float3 : TARGET` de pixel shaders se expanden a `out vec4` con `vec4(_main_wrap(...), 1.0)`, y en `gl-subsystem.c` (`apply_swizzle`) se fija `GL_TEXTURE_SWIZZLE_A = GL_ONE` para `GS_BGRX` / `GS_BGRX_UNORM`.
2. **Búsqueda inicial al comienzo del archivo en `shared/media-playback` (`media.c`):**
   - En `seek_to(mp_media_t *m, int64_t pos)`, el código buscaba siempre sobre el stream `0` (`m->fmt->streams[0]`). En contenedores FLV donde la cabecera de secuencia de audio precede a la de video, `streams[0]` es el stream de audio y su primer paquete de datos (`Tag 4`) está desplazado detrás del primer keyframe H.264 (`Tag 3`, `dts = 0`), provocando que `av_seek_frame` saltara el primer keyframe de video hasta `t = 2,000 s`.
   - Al seleccionar `AVStream *stream = m->has_video ? m->v.stream : m->fmt->streams[0]` y pasar `stream->index` a `av_seek_frame`, la preparación inicial posiciona el demuxer en el primer keyframe de video (`v_pts = 0`, `a_pts = 0`) y emite video desde el primer ciclo.

## Resultado de `EditorProbeActivity` en dispositivo real

Evidencia: `out/phase6/editor-probe.json` (`"pass": true`):
- Creación, selección, duplicación (`Duplicado verificable`) y eliminación de escenas (`obs_scene_create`, `obs_scene_duplicate`).
- Fuente `color_source_v3` (`Magenta verificable`, `1280×720`) verificada en composición GPU (`probe_matches > 20`).
- Filtro original `color_filter_v2` (`obs-filters`) con `brightness = 0.2`, verificado por cambio de píxel leído desde la composición GPU (`probe_pixel = 16086246`).
- Filtro original `crop_filter` (`obs-filters`) con recorte relativo `left=100, right=100, top=20, bottom=20`, redimensionando la fuente a `1080×680`.
- Transformación de `obs_sceneitem_t` (`pos`, `scale`, `rot`, `obs_sceneitem_crop` con `crop_left=16, crop_right=8`).
- Fuente `ffmpeg_source` (`Medio verificable`) reproduciendo `test-media.flv` (`1280×720`, H.264 + AAC) y componiendo sobre el lienzo GPU (`probe_pixel = 2311026`, `R=35, G=67, B=114`, `probe_matches = 61`).
