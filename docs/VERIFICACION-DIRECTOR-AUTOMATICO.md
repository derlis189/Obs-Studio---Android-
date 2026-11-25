# Director automático: Advanced Scene Switcher en Android

Fecha: 2026-11-24. Prueba experimental en Samsung SM-S931B, ARM64.

Se adaptó el plugin original Advanced Scene Switcher 1.37.2, revisión
`6b8e666d5826d3c0784d2bd16c1d309b296f1b7b`, con su motor de macros,
extensión base y ventana Qt. No es un reemplazo del motor del plugin.

## Uso

- **Herramientas → Director automático**: interruptor para encender y apagar.
- **Herramientas → Configurar Director automático…**: editar las macros originales.
- **Herramientas → Probar Director automático (9 s)**: demostración temporal A → B → A.

Siempre se carga apagado, incluso si la colección guardó `active=true`.
La carga fuerza `startupBehavior=STOP` y `autoStartEvent=NEVER` en Android.
El asistente inicial de escritorio se omite: abrir configuración no enciende el
motor. Los controles de inicio automático están deshabilitados. Encender el
interruptor activa las reglas configuradas; una lista vacía no cambia escenas.

La demostración exige que no haya macros, transmisión, grabación ni otra
automatización activa. Usa el hilo y las fábricas originales del plugin para
dos condiciones de temporizador y dos acciones de cambio de escena. El
temporizador Qt únicamente observa el resultado y termina la prueba. Después
detiene el motor, restaura Program/Preview, retira sus dos macros y elimina sus
escenas temporales. Si el usuario cambia a otra escena, termina respetando esa
selección. Los nombres de demostración existentes nunca se reemplazan.

## Compilación e instalación

```powershell
./scripts/build-scene-switcher.ps1
./scripts/build-libobs.ps1 -Frontend
./scripts/build-apk.ps1 -WithSceneSwitcher
adb -s RFCY71VX7YV install -r android/app/build/outputs/apk/debug/app-debug.apk
```

El plugin es opcional en el APK. El cargador Android solo lo descubre si existe
su biblioteca nativa. Se empaquetan tres bibliotecas ARM64, con segmentos LOAD
alineados a 16 KB. Se incluyen licencias y referencia al código correspondiente.
Los modelos de OCR y voz se excluyen porque sus extensiones no se compilan.
Los recursos empaquetados se actualizan según `PackageInfo.lastUpdateTime`,
sin escribir en la configuración del usuario `files/obs-studio`.

Archivos reproducibles:

- `android/scene-switcher/`: CMake, plataforma Android y demostración.
- `patches/scene-switcher/0001-android-core-base.patch`: adaptación del plugin.
- `patches/obs-studio/0026-android-scene-switcher-loader.patch`: descubrimiento.
- `docs/SCENE-SWITCHER-SOURCE.txt`: revisión, dependencias y fuentes.

## Resultado de la demostración

La ejecución del dispositivo produjo:

```json
{
  "elapsed_ms": 8595,
  "macro_to_a_runs": 1,
  "macro_to_b_runs": 1,
  "pass": true,
  "reason": "complete",
  "saw_b": true,
  "saw_return_a": true,
  "stopped": true
}
```

El registro confirmó `Android startup: automation=off (manual only)` antes de
la solicitud explícita de demostración. Tras ella, la colección guardó
`active=false`, `startup_behavior=2`, sin macros temporales. Permanecieron las
cinco escenas originales. Evidencias privadas locales en
`out/scene-switcher-checks/`, incluyendo copia previa de configuración, registro
nativo y `demo-result.json`. No publicar las copias de configuración: contienen
los datos particulares del proyecto.

Para repetir mediante ADB, con OBS cerrado, se puede crear
`files/obs-studio/plugin_config/advanced-scene-switcher/android-demo.request`
usando `run-as org.obsandroid.port` y abrir la actividad. La solicitud se consume
una sola vez; el resultado queda en el mismo directorio como
`android-demo-result.json`. El menú permite repetir sin ADB.

## Alcance y límites

Se compiló la extensión base, que contiene reglas de tiempo, audio, medios y
estado de OBS. Esta prueba física verificó los temporizadores y el cambio de
escena; no valida todavía todas las condiciones y acciones de la extensión.
Se excluyen las extensiones externas de OCR, voz, Twitch, MIDI, MQTT, USB,
OpenVR, scripting y otras. Se retiran de las fábricas Android los controles
de ventanas/procesos, inactividad, cursor, ejecución externa, notificaciones de
bandeja y grabación de interacción de escritorio. La API vendor de obs-websocket
no está disponible en esta compilación y registra avisos; no es necesaria para
las macros locales ni para el interruptor.

La primera compilación detectó recursos sin actualizar y una doble espera en
el JSON de demostración compatible con formatos antiguos. Ambos se corrigieron
antes de la prueba positiva. También apareció un ANR de la primera instancia al
interactuar con sus diálogos incompletos; el aviso pertenecía al proceso anterior
a la instalación corregida. No se afirma estabilidad de sesiones largas,
transmisión/grabación real, todas las pantallas Qt ni todos los ciclos de vida.
