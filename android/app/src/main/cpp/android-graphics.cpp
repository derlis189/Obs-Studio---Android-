#include "android-session-gate.h"
// SPDX-License-Identifier: GPL-2.0-or-later
#include "android-graphics.h"
#include "android-rgba-frame.h"
#include <obs-module.h>
#include <graphics/vec2.h>
#include <graphics/vec3.h>
#include <graphics/vec4.h>
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstring>
#include <mutex>
#include <sstream>
#include <string>
#include <unordered_map>

namespace {
std::mutex g_gfx_mutex;
JavaVM *g_gfx_jvm = nullptr;
jclass g_gfx_host_class = nullptr;
jmethodID g_mid_gfx_update = nullptr;
jmethodID g_mid_gfx_destroy = nullptr;
jmethodID g_mid_gfx_set_visible = nullptr;
jmethodID g_mid_gfx_open_panel = nullptr;
jmethodID g_mid_gfx_get_state = nullptr;
jmethodID g_mid_gfx_exec = nullptr;
jmethodID g_mid_gfx_books = nullptr;
jmethodID g_mid_gfx_search = nullptr;
std::atomic<int64_t> g_gfx_seq{0};
bool g_gfx_registered = false;

struct GfxInstance {
    int64_t id{};
    obs_source_t *source{};
    std::string kind; // "scoreboard", "lowerthird", "timer", "bible", "datetime_weather", "advanced_title"
    std::atomic<int> width{1280};
    std::atomic<int> height{720};
    std::string config_json;
    AndroidRgbaFrame frame;
};

std::unordered_map<int64_t, GfxInstance *> g_gfx_instances;

std::string json_escape(const char *s)
{
    if (!s) return "";
    std::string out;
    for (const unsigned char *p = reinterpret_cast<const unsigned char *>(s); *p; ++p) {
        if (*p == '"' || *p == '\\') { out.push_back('\\'); out.push_back(char(*p)); }
        else if (*p == '\n') out += "\\n";
        else if (*p == '\r') out += "\\r";
        else if (*p >= 32) out.push_back(char(*p));
    }
    return out;
}

void push_gfx_config(const GfxInstance *inst)
{
    JavaVM *jvm = nullptr; jclass cls = nullptr; jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_gfx_mutex);
        jvm = g_gfx_jvm; cls = g_gfx_host_class; mid = g_mid_gfx_update;
    }
    if (!jvm || !cls || !mid) return;
    JNIEnv *env = nullptr; bool detach = false;
    int st = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) { if (jvm->AttachCurrentThread(&env, nullptr) != 0) return; detach = true; }
    else if (st != JNI_OK) return;

    jstring jKind = env->NewStringUTF(inst->kind.c_str());
    jstring jCfg = env->NewStringUTF(inst->config_json.c_str());
    env->CallStaticVoidMethod(cls, mid, jlong(inst->id), jKind, jCfg, jint(inst->width.load()), jint(inst->height.load()));
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(jKind);
    env->DeleteLocalRef(jCfg);
    if (detach) jvm->DetachCurrentThread();
}

void set_gfx_visible_java(int64_t id, bool visible)
{
    JavaVM *jvm = nullptr; jclass cls = nullptr; jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_gfx_mutex);
        jvm = g_gfx_jvm; cls = g_gfx_host_class; mid = g_mid_gfx_set_visible;
    }
    if (!jvm || !cls || !mid) return;
    JNIEnv *env = nullptr; bool detach = false;
    int st = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) { if (jvm->AttachCurrentThread(&env, nullptr) != 0) return; detach = true; }
    else if (st != JNI_OK) return;

    env->CallStaticVoidMethod(cls, mid, jlong(id), jboolean(visible ? JNI_TRUE : JNI_FALSE));
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (detach) jvm->DetachCurrentThread();
}

void destroy_gfx_java(int64_t id)
{
    JavaVM *jvm = nullptr; jclass cls = nullptr; jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_gfx_mutex);
        jvm = g_gfx_jvm; cls = g_gfx_host_class; mid = g_mid_gfx_destroy;
    }
    if (!jvm || !cls || !mid) return;
    JNIEnv *env = nullptr; bool detach = false;
    int st = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) { if (jvm->AttachCurrentThread(&env, nullptr) != 0) return; detach = true; }
    else if (st != JNI_OK) return;

    env->CallStaticVoidMethod(cls, mid, jlong(id));
    if (env->ExceptionCheck()) env->ExceptionClear();
    if (detach) jvm->DetachCurrentThread();
}

bool open_panel_for_kind(const char *kind)
{
    JavaVM *jvm = nullptr; jclass cls = nullptr; jmethodID mid = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_gfx_mutex);
        jvm = g_gfx_jvm; cls = g_gfx_host_class; mid = g_mid_gfx_open_panel;
    }
    if (!jvm || !cls || !mid) return false;
    JNIEnv *env = nullptr; bool detach = false;
    int st = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) { if (jvm->AttachCurrentThread(&env, nullptr) != 0) return false; detach = true; }
    else if (st != JNI_OK) return false;

    jstring jKind = env->NewStringUTF(kind ? kind : "scoreboard");
    env->CallStaticVoidMethod(cls, mid, jKind);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(jKind);
    if (detach) jvm->DetachCurrentThread();
    return false;
}

bool on_open_scoreboard_panel(obs_properties_t *, obs_property_t *, void *) { return open_panel_for_kind("scoreboard"); }
bool on_open_lowerthird_panel(obs_properties_t *, obs_property_t *, void *) { return open_panel_for_kind("lowerthird"); }
bool on_open_timer_panel(obs_properties_t *, obs_property_t *, void *) { return open_panel_for_kind("timer"); }
bool on_open_bible_panel(obs_properties_t *, obs_property_t *, void *) { return open_panel_for_kind("bible"); }

std::string build_gfx_json(const std::string &kind, obs_data_t *s)
{
    std::ostringstream js;
    js << "{";
    if (kind == "scoreboard") {
        js << "\"homeName\":\"" << json_escape(obs_data_get_string(s, "home_name")) << "\","
           << "\"awayName\":\"" << json_escape(obs_data_get_string(s, "away_name")) << "\","
           << "\"league\":\"" << json_escape(obs_data_get_string(s, "league")) << "\","
           << "\"homeColor\":\"" << json_escape(obs_data_get_string(s, "home_color")) << "\","
           << "\"awayColor\":\"" << json_escape(obs_data_get_string(s, "away_color")) << "\","
           << "\"homeLogo\":\"" << json_escape(obs_data_get_string(s, "home_logo")) << "\","
           << "\"awayLogo\":\"" << json_escape(obs_data_get_string(s, "away_logo")) << "\","
           << "\"style\":" << obs_data_get_int(s, "sb_style") << ","
           << "\"showClock\":" << (obs_data_get_bool(s, "show_clock") ? "true" : "false") << ","
           << "\"applyText\":" << (obs_data_get_bool(s, "sync_from_props") ? "true" : "false");
    } else if (kind == "lowerthird") {
        js << "\"localContent\":" << (obs_data_get_bool(s, "lt_local_content") ? "true" : "false") << ","
           << "\"badge\":\"" << json_escape(obs_data_get_string(s, "lt_badge")) << "\","
           << "\"title\":\"" << json_escape(obs_data_get_string(s, "lt_title")) << "\","
           << "\"subtitle\":\"" << json_escape(obs_data_get_string(s, "lt_subtitle")) << "\","
           << "\"accentColor\":\"" << json_escape(obs_data_get_string(s, "accent_color")) << "\","
           << "\"style\":" << obs_data_get_int(s, "lt_style") << ","
           << "\"applyText\":" << (obs_data_get_bool(s, "sync_from_props") ? "true" : "false");
    } else if (kind == "timer") {
        js << "\"mode\":\"" << json_escape(obs_data_get_string(s, "timer_mode")) << "\","
           << "\"label\":\"" << json_escape(obs_data_get_string(s, "timer_label")) << "\","
           << "\"doneText\":\"" << json_escape(obs_data_get_string(s, "timer_done")) << "\","
           << "\"accentColor\":\"" << json_escape(obs_data_get_string(s, "accent_color")) << "\","
           << "\"style\":" << obs_data_get_int(s, "tm_style") << ","
           << "\"durationSec\":" << obs_data_get_int(s, "timer_seconds") << ","
           << "\"applyText\":" << (obs_data_get_bool(s, "sync_from_props") ? "true" : "false");
    } else if (kind == "bible") {
        js << "\"accentColor\":\"" << json_escape(obs_data_get_string(s, "accent_color")) << "\","
           << "\"style\":" << obs_data_get_int(s, "bible_style") << ","
           << "\"fontSize\":" << obs_data_get_int(s, "font_size");
    } else if (kind == "datetime_weather") {
        js << "\"contentMode\":" << obs_data_get_int(s, "dtw_content") << ","
           << "\"layout\":" << obs_data_get_int(s, "dtw_layout") << ","
           << "\"style\":" << obs_data_get_int(s, "dtw_style") << ","
           << "\"timeFormat24\":" << (obs_data_get_bool(s, "dtw_24h") ? "true" : "false") << ","
           << "\"showSeconds\":" << (obs_data_get_bool(s, "dtw_seconds") ? "true" : "false") << ","
           << "\"dateFormat\":" << obs_data_get_int(s, "dtw_date_fmt") << ","
           << "\"city\":\"" << json_escape(obs_data_get_string(s, "dtw_city")) << "\","
           << "\"showCity\":" << (obs_data_get_bool(s, "dtw_show_city") ? "true" : "false") << ","
           << "\"tempUnit\":\"" << json_escape(obs_data_get_string(s, "dtw_unit")) << "\","
           << "\"autoWeather\":" << (obs_data_get_bool(s, "dtw_auto_weather") ? "true" : "false") << ","
           << "\"manualTemp\":" << obs_data_get_int(s, "dtw_manual_temp") << ","
           << "\"manualCondition\":" << obs_data_get_int(s, "dtw_manual_cond") << ","
           << "\"accentColor\":\"" << json_escape(obs_data_get_string(s, "accent_color")) << "\"";
    } else if (kind == "advanced_title") {
        js << "\"localMode\":" << (obs_data_get_bool(s, "at_local_mode") ? "true" : "false") << ","
           << "\"template\":" << obs_data_get_int(s, "at_template") << ","
           << "\"animIn\":" << obs_data_get_int(s, "at_anim_in") << ","
           << "\"animOut\":" << obs_data_get_int(s, "at_anim_out") << ","
           << "\"inMs\":" << obs_data_get_int(s, "at_in_ms") << ","
           << "\"outMs\":" << obs_data_get_int(s, "at_out_ms") << ","
           << "\"autoHideSec\":" << obs_data_get_int(s, "at_auto_hide_sec") << ","
           << "\"shimmer\":" << (obs_data_get_bool(s, "at_shimmer") ? "true" : "false") << ","
           << "\"badge\":\"" << json_escape(obs_data_get_string(s, "at_badge")) << "\","
           << "\"title\":\"" << json_escape(obs_data_get_string(s, "at_title")) << "\","
           << "\"subtitle\":\"" << json_escape(obs_data_get_string(s, "at_subtitle")) << "\","
           << "\"ticker\":\"" << json_escape(obs_data_get_string(s, "at_ticker")) << "\","
           << "\"accentColor\":\"" << json_escape(obs_data_get_string(s, "at_accent_color")) << "\","
           << "\"bgColor\":\"" << json_escape(obs_data_get_string(s, "at_bg_color")) << "\","
           << "\"textColor\":\"" << json_escape(obs_data_get_string(s, "at_text_color")) << "\","
           << "\"align\":" << obs_data_get_int(s, "at_align") << ","
           << "\"scalePct\":" << obs_data_get_int(s, "at_scale") << ","
           << "\"logoPath\":\"" << json_escape(obs_data_get_string(s, "at_logo")) << "\"";
    }
    js << "}";
    return js.str();
}

void compute_dtw_auto_size(obs_data_t *s, int &out_w, int &out_h)
{
    int cm = int(obs_data_get_int(s, "dtw_content"));
    int layout = int(obs_data_get_int(s, "dtw_layout"));
    int style = int(obs_data_get_int(s, "dtw_style"));
    bool fmt24 = obs_data_get_bool(s, "dtw_24h");
    bool showSec = obs_data_get_bool(s, "dtw_seconds");
    int dateFmt = int(obs_data_get_int(s, "dtw_date_fmt"));
    bool showCity = obs_data_get_bool(s, "dtw_show_city");
    const char *cityStr = obs_data_get_string(s, "dtw_city");
    size_t cityLen = cityStr ? std::strlen(cityStr) : 0;
    bool hasCity = showCity && cityLen > 0;

    bool showDate = (cm == 0 || cm == 2 || cm == 3 || cm == 5);
    bool showTime = (cm == 0 || cm == 1 || cm == 3 || cm == 4);
    bool showWeather = (cm == 0 || cm == 1 || cm == 2 || cm == 6);
    int sections = (showDate ? 1 : 0) + (showTime ? 1 : 0) + (showWeather ? 1 : 0);

    if (layout >= 1) {
        // Vertical stacked card: genuinely compact width, height grows with stacked rows
        int w = 300;
        if (showDate && dateFmt == 2) w = 404;
        else if (showDate && dateFmt == 0) w = 344;
        else if (showTime && (showSec || !fmt24)) w = 344;
        if (showWeather && hasCity && cityLen > 12) w = std::max(w, 368);

        int h = 44;
        if (showTime) h += 124;
        if (showDate) h += 76;
        if (showWeather) h += (hasCity ? 132 : 104);
        if (sections <= 1) h = std::max(h, 136);

        out_w = (std::clamp(w, 260, 640) + 3) & ~3;
        out_h = (std::clamp(h, 128, 720) + 3) & ~3;
        return;
    }

    // Horizontal layout (layout == 0)
    if (style == 2 && sections >= 2) {
        // Style 2: 2-line Broadcast Card
        int w = (showTime && showWeather) ? 560 : 480;
        if (showSec || !fmt24 || (showDate && dateFmt == 2)) w += 64;
        out_w = (std::clamp(w, 380, 920) + 3) & ~3;
        out_h = 188;
        return;
    }

    int w = 64;
    if (showTime) {
        if (showSec && !fmt24) w += 284;
        else if (showSec || !fmt24) w += 248;
        else w += 196;
    }
    if (showDate) {
        if (dateFmt == 2) w += 360;
        else if (dateFmt == 0) w += 272;
        else if (dateFmt == 1) w += 212;
        else w += 168;
    }
    if (showWeather) {
        w += hasCity ? (cityLen > 12 ? 284 : 252) : 188;
    }
    if (style == 1) w += 32; // extra pill end-cap padding

    out_w = (std::clamp(w, 260, 1360) + 3) & ~3;
    out_h = 136;
}

void *gfx_create_common(const char *kind, obs_data_t *s, obs_source_t *source)
{
    auto *inst = new GfxInstance{};
    inst->id = ++g_gfx_seq;
    inst->source = source;
    inst->kind = kind;
    int w = 1280, h = 720;
    if (inst->kind == "datetime_weather" && obs_data_get_bool(s, "dtw_auto_size")) {
        compute_dtw_auto_size(s, w, h);
    } else {
        w = int(obs_data_get_int(s, "width"));
        h = int(obs_data_get_int(s, "height"));
    }
    if (w < 256) w = 1280;
    if (h < 120) h = 720;
    inst->width.store(w, std::memory_order_release);
    inst->height.store(h, std::memory_order_release);
    inst->config_json = build_gfx_json(inst->kind, s);
    {
        std::lock_guard<std::mutex> lock(g_gfx_mutex);
        g_gfx_instances[inst->id] = inst;
    }
    push_gfx_config(inst);
    set_gfx_visible_java(inst->id, obs_source_showing(source));
    return inst;
}

void gfx_show_common(void *data)
{
    auto *inst = static_cast<GfxInstance *>(data);
    if (inst) set_gfx_visible_java(inst->id, true);
}

void gfx_hide_common(void *data)
{
    auto *inst = static_cast<GfxInstance *>(data);
    if (inst) set_gfx_visible_java(inst->id, false);
}

uint32_t gfx_get_width(void *data)
{
    auto *inst = static_cast<GfxInstance *>(data);
    if (!inst) return 0;
    uint32_t fw = inst->frame.width();
    return fw > 0 ? fw : uint32_t(inst->width.load(std::memory_order_acquire));
}

uint32_t gfx_get_height(void *data)
{
    auto *inst = static_cast<GfxInstance *>(data);
    if (!inst) return 0;
    uint32_t fh = inst->frame.height();
    return fh > 0 ? fh : uint32_t(inst->height.load(std::memory_order_acquire));
}

void gfx_video_render(void *data, gs_effect_t *)
{
    auto *inst = static_cast<GfxInstance *>(data);
    if (inst) inst->frame.render();
}

void gfx_update_common(void *data, obs_data_t *s)
{
    auto *inst = static_cast<GfxInstance *>(data);
    if (!inst) return;
    int w = 1280, h = 720;
    if (inst->kind == "datetime_weather" && obs_data_get_bool(s, "dtw_auto_size")) {
        compute_dtw_auto_size(s, w, h);
    } else {
        w = int(obs_data_get_int(s, "width"));
        h = int(obs_data_get_int(s, "height"));
    }
    if (w < 256) w = 1280;
    if (h < 120) h = 720;
    inst->width.store(w, std::memory_order_release);
    inst->height.store(h, std::memory_order_release);
    inst->config_json = build_gfx_json(inst->kind, s);
    push_gfx_config(inst);
}

void gfx_destroy_common(void *data)
{
    auto *inst = static_cast<GfxInstance *>(data);
    if (!inst) return;
    int64_t id = inst->id;
    {
        std::lock_guard<std::mutex> lock(g_gfx_mutex);
        g_gfx_instances.erase(id);
    }
    destroy_gfx_java(id);
    delete inst;
}

// ---- 1. Scoreboard ----
const char *sb_get_name(void *) { return "Marcador Deportivo"; }
void sb_get_defaults(obs_data_t *s)
{
    obs_data_set_default_string(s, "home_name", "LOCAL");
    obs_data_set_default_string(s, "away_name", "VISITA");
    obs_data_set_default_string(s, "league", "EN VIVO");
    obs_data_set_default_string(s, "home_color", "#2563EB");
    obs_data_set_default_string(s, "away_color", "#DC2626");
    obs_data_set_default_string(s, "home_logo", "");
    obs_data_set_default_string(s, "away_logo", "");
    obs_data_set_default_int(s, "sb_style", 0);
    obs_data_set_default_bool(s, "show_clock", true);
    obs_data_set_default_bool(s, "sync_from_props", false);
    obs_data_set_default_int(s, "width", 1280);
    obs_data_set_default_int(s, "height", 220);
}
obs_properties_t *sb_get_properties(void *)
{
    obs_properties_t *p = obs_properties_create();
    obs_properties_add_button(p, "open_panel", "Abrir controles en vivo de Marcador...", on_open_scoreboard_panel);
    obs_properties_add_text(p, "info", "Tambien puedes activar el panel independiente en Paneles -> Control: Marcador.", OBS_TEXT_INFO);
    obs_property_t *st = obs_properties_add_list(p, "sb_style", "Diseno del Marcador", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    obs_property_list_add_int(st, "Barra Broadcast Clasica", 0);
    obs_property_list_add_int(st, "Esquina Compacta TV", 1);
    obs_property_list_add_int(st, "Tarjeta Central Torneo", 2);
    obs_property_list_add_int(st, "Esports Neon", 3);
    obs_property_list_add_int(st, "Minimalista Claro", 4);
    obs_properties_add_bool(p, "sync_from_props", "Aplicar nombres/colores de estas propiedades");
    obs_properties_add_text(p, "home_name", "Equipo Local", OBS_TEXT_DEFAULT);
    obs_properties_add_text(p, "away_name", "Equipo Visitante", OBS_TEXT_DEFAULT);
    obs_properties_add_text(p, "league", "Torneo / Etiqueta", OBS_TEXT_DEFAULT);
    obs_properties_add_text(p, "home_color", "Color Local (Hex)", OBS_TEXT_DEFAULT);
    obs_properties_add_text(p, "away_color", "Color Visitante (Hex)", OBS_TEXT_DEFAULT);
    obs_properties_add_path(p, "home_logo", "Logo Equipo Local (PNG/JPG)", OBS_PATH_FILE, "Todos los archivos (*);;Imagenes (*.png *.jpg *.webp)", nullptr);
    obs_properties_add_path(p, "away_logo", "Logo Equipo Visitante (PNG/JPG)", OBS_PATH_FILE, "Todos los archivos (*);;Imagenes (*.png *.jpg *.webp)", nullptr);
    obs_properties_add_bool(p, "show_clock", "Mostrar reloj de partido");
    obs_properties_add_int(p, "width", "Ancho", 480, 1920, 16);
    obs_properties_add_int(p, "height", "Alto", 120, 720, 8);
    return p;
}
void *sb_create(obs_data_t *s, obs_source_t *src) { return gfx_create_common("scoreboard", s, src); }

// ---- 2. Lower Third ----
const char *lt_get_name(void *) { return "Lower Third (Zocalo)"; }
void lt_get_defaults(obs_data_t *s)
{
    obs_data_set_default_string(s, "lt_badge", "EN VIVO");
    obs_data_set_default_string(s, "lt_title", "Nombre del Presentador");
    obs_data_set_default_string(s, "lt_subtitle", "Cargo o Tema del Programa");
    obs_data_set_default_string(s, "accent_color", "#2563EB");
    obs_data_set_default_int(s, "lt_style", 0);
    obs_data_set_default_bool(s, "sync_from_props", false);
    obs_data_set_default_int(s, "width", 1280);
    obs_data_set_default_int(s, "height", 260);
}
obs_properties_t *lt_get_properties(void *)
{
    obs_properties_t *p = obs_properties_create();
    obs_properties_add_button(p, "open_panel", "Abrir controles en vivo de Lower Third...", on_open_lowerthird_panel);
    obs_properties_add_text(p, "info", "Tambien puedes activar el panel independiente en Paneles -> Control: Lower Third.", OBS_TEXT_INFO);
    obs_properties_add_bool(p, "lt_local_content", "Texto y estilo propios de esta fuente");
    obs_properties_add_bool(p, "sync_from_props", "Aplicar textos de estas propiedades");
    obs_properties_add_text(p, "lt_badge", "Etiqueta superior", OBS_TEXT_DEFAULT);
    obs_properties_add_text(p, "lt_title", "Titulo principal", OBS_TEXT_DEFAULT);
    obs_properties_add_text(p, "lt_subtitle", "Subtitulo", OBS_TEXT_DEFAULT);
    obs_properties_add_text(p, "accent_color", "Color de acento (Hex)", OBS_TEXT_DEFAULT);
    obs_property_t *st = obs_properties_add_list(p, "lt_style", "Estilo visual", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    obs_property_list_add_int(st, "Barra Broadcast Moderna", 0);
    obs_property_list_add_int(st, "Tarjeta Elegante", 1);
    obs_property_list_add_int(st, "Minimalista", 2);
    obs_property_list_add_int(st, "Noticiero / Breaking", 3);
    obs_property_list_add_int(st, "Centrado / Conferencia", 4);
    obs_property_list_add_int(st, "Noticiero CNN / Ultimo Momento", 5);
    obs_property_list_add_int(st, "Noticiero 24h Doble Banda (Reloj)", 6);
    obs_property_list_add_int(st, "Noticiero Editorial / Estudio", 7);
    obs_property_list_add_int(st, "Noticiero Alerta Roja (Ticker)", 8);
    obs_properties_add_int(p, "width", "Ancho", 480, 1920, 16);
    obs_properties_add_int(p, "height", "Alto", 140, 720, 8);
    return p;
}
void *lt_create(obs_data_t *s, obs_source_t *src) { return gfx_create_common("lowerthird", s, src); }

// ---- 3. Timer / Stopwatch ----
const char *tm_get_name(void *) { return "Cronometro / Temporizador"; }
void tm_get_defaults(obs_data_t *s)
{
    obs_data_set_default_string(s, "timer_mode", "countdown");
    obs_data_set_default_string(s, "timer_label", "COMENZAMOS EN");
    obs_data_set_default_string(s, "timer_done", "EN VIVO");
    obs_data_set_default_string(s, "accent_color", "#2563EB");
    obs_data_set_default_int(s, "tm_style", 0);
    obs_data_set_default_int(s, "timer_seconds", 300);
    obs_data_set_default_bool(s, "sync_from_props", false);
    obs_data_set_default_int(s, "width", 640);
    obs_data_set_default_int(s, "height", 240);
}
obs_properties_t *tm_get_properties(void *)
{
    obs_properties_t *p = obs_properties_create();
    obs_properties_add_button(p, "open_panel", "Abrir controles en vivo de Cronometro...", on_open_timer_panel);
    obs_property_t *st = obs_properties_add_list(p, "tm_style", "Diseno visual", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    obs_property_list_add_int(st, "Tarjeta Broadcast Oscura", 0);
    obs_property_list_add_int(st, "Pildora Compacta", 1);
    obs_property_list_add_int(st, "Anillo Circular de Progreso", 2);
    obs_property_list_add_int(st, "Digital LED Alto Contraste", 3);
    obs_property_list_add_int(st, "Minimalista Transparente", 4);
    obs_properties_add_bool(p, "sync_from_props", "Aplicar configuracion de estas propiedades");
    obs_property_t *m = obs_properties_add_list(p, "timer_mode", "Modo", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_STRING);
    obs_property_list_add_string(m, "Cuenta regresiva", "countdown");
    obs_property_list_add_string(m, "Cronometro ascendente", "stopwatch");
    obs_properties_add_int(p, "timer_seconds", "Duracion inicial (segundos)", 5, 36000, 5);
    obs_properties_add_text(p, "timer_label", "Etiqueta superior", OBS_TEXT_DEFAULT);
    obs_properties_add_text(p, "timer_done", "Mensaje al finalizar", OBS_TEXT_DEFAULT);
    obs_properties_add_text(p, "accent_color", "Color de acento (Hex)", OBS_TEXT_DEFAULT);
    obs_properties_add_int(p, "width", "Ancho", 320, 1920, 16);
    obs_properties_add_int(p, "height", "Alto", 140, 720, 8);
    return p;
}
void *tm_create(obs_data_t *s, obs_source_t *src) { return gfx_create_common("timer", s, src); }

// ---- 5. Date, Time & Weather ----
const char *dtw_get_name(void *) { return "Fecha, Hora y Clima"; }
bool dtw_on_props_modified(obs_properties_t *props, obs_property_t *, obs_data_t *s)
{
    bool auto_sz = obs_data_get_bool(s, "dtw_auto_size");
    obs_property_t *pw = obs_properties_get(props, "width");
    obs_property_t *ph = obs_properties_get(props, "height");
    if (pw) obs_property_set_visible(pw, !auto_sz);
    if (ph) obs_property_set_visible(ph, !auto_sz);
    if (auto_sz) {
        int aw = 780, ah = 136;
        compute_dtw_auto_size(s, aw, ah);
        obs_data_set_int(s, "width", aw);
        obs_data_set_int(s, "height", ah);
    }
    return true;
}
void dtw_get_defaults(obs_data_t *s)
{
    obs_data_set_default_int(s, "dtw_content", 0); // 0=Todo, 1=Hora+Clima, 2=Fecha+Clima, 3=Fecha+Hora, 4=Hora, 5=Fecha, 6=Clima
    obs_data_set_default_int(s, "dtw_layout", 0);  // 0=Horizontal, 1=Vertical centrado, 2=Vertical izq, 3=Vertical der
    obs_data_set_default_int(s, "dtw_style", 0);
    obs_data_set_default_bool(s, "dtw_24h", true);
    obs_data_set_default_bool(s, "dtw_seconds", false);
    obs_data_set_default_int(s, "dtw_date_fmt", 0);
    obs_data_set_default_string(s, "dtw_city", "Asuncion");
    obs_data_set_default_bool(s, "dtw_show_city", true);
    obs_data_set_default_string(s, "dtw_unit", "C");
    obs_data_set_default_bool(s, "dtw_auto_weather", true);
    obs_data_set_default_int(s, "dtw_manual_temp", 26);
    obs_data_set_default_int(s, "dtw_manual_cond", 0);
    obs_data_set_default_string(s, "accent_color", "#2563EB");
    obs_data_set_default_bool(s, "dtw_auto_size", true);
    obs_data_set_default_int(s, "width", 780);
    obs_data_set_default_int(s, "height", 136);
}
obs_properties_t *dtw_get_properties(void *)
{
    obs_properties_t *p = obs_properties_create();
    obs_property_t *content = obs_properties_add_list(p, "dtw_content", "Elementos a mostrar", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    obs_property_list_add_int(content, "Fecha, Hora y Clima (Todo)", 0);
    obs_property_list_add_int(content, "Solo Hora y Clima", 1);
    obs_property_list_add_int(content, "Solo Fecha y Clima", 2);
    obs_property_list_add_int(content, "Solo Fecha y Hora", 3);
    obs_property_list_add_int(content, "Solo Hora", 4);
    obs_property_list_add_int(content, "Solo Fecha", 5);
    obs_property_list_add_int(content, "Solo Clima", 6);
    obs_property_set_modified_callback(content, dtw_on_props_modified);

    obs_property_t *lay = obs_properties_add_list(p, "dtw_layout", "Disposición", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    obs_property_list_add_int(lay, "Horizontal (uno al lado del otro)", 0);
    obs_property_list_add_int(lay, "Vertical centrado (uno encima del otro)", 1);
    obs_property_list_add_int(lay, "Vertical izquierda (uno encima del otro)", 2);
    obs_property_list_add_int(lay, "Vertical derecha (uno encima del otro)", 3);
    obs_property_set_modified_callback(lay, dtw_on_props_modified);

    obs_property_t *st = obs_properties_add_list(p, "dtw_style", "Diseño visual", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    obs_property_list_add_int(st, "Noticiero TV (Barra compacta)", 0);
    obs_property_list_add_int(st, "Píldora Moderna (Glass)", 1);
    obs_property_list_add_int(st, "Tarjeta Broadcast (2 líneas)", 2);
    obs_property_list_add_int(st, "Minimalista Transparente", 3);
    obs_property_list_add_int(st, "Editorial Claro (Fondo blanco)", 4);
    obs_property_list_add_int(st, "Digital LED Alto Contraste", 5);
    obs_property_set_modified_callback(st, dtw_on_props_modified);

    obs_property_t *p24 = obs_properties_add_bool(p, "dtw_24h", "Formato 24 horas (desmarcar para AM/PM)");
    obs_property_set_modified_callback(p24, dtw_on_props_modified);
    obs_property_t *psec = obs_properties_add_bool(p, "dtw_seconds", "Mostrar segundos en la hora");
    obs_property_set_modified_callback(psec, dtw_on_props_modified);

    obs_property_t *df = obs_properties_add_list(p, "dtw_date_fmt", "Formato de fecha", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    obs_property_list_add_int(df, "Larga (Jue, 26 Nov 2026)", 0);
    obs_property_list_add_int(df, "Corta (26/11/2026)", 1);
    obs_property_list_add_int(df, "Completa (Jueves 26 de Noviembre)", 2);
    obs_property_list_add_int(df, "Compacta (26 NOV)", 3);
    obs_property_set_modified_callback(df, dtw_on_props_modified);

    obs_properties_add_bool(p, "dtw_auto_weather", "Clima automático por internet (Open-Meteo)");
    obs_properties_add_text(p, "dtw_city", "Ciudad (para clima)", OBS_TEXT_DEFAULT);
    obs_property_t *pshowCity = obs_properties_add_bool(p, "dtw_show_city", "Mostrar nombre de la ciudad");
    obs_property_set_modified_callback(pshowCity, dtw_on_props_modified);

    obs_property_t *unit = obs_properties_add_list(p, "dtw_unit", "Unidad de temperatura", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_STRING);
    obs_property_list_add_string(unit, "Celsius (°C)", "C");
    obs_property_list_add_string(unit, "Fahrenheit (°F)", "F");

    obs_properties_add_int(p, "dtw_manual_temp", "Temperatura manual / respaldo", -40, 60, 1);
    obs_property_t *cond = obs_properties_add_list(p, "dtw_manual_cond", "Condición manual / respaldo", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    obs_property_list_add_int(cond, "Soleado / Despejado", 0);
    obs_property_list_add_int(cond, "Parcialmente nublado", 1);
    obs_property_list_add_int(cond, "Nublado", 2);
    obs_property_list_add_int(cond, "Lluvia", 3);
    obs_property_list_add_int(cond, "Tormenta", 4);
    obs_property_list_add_int(cond, "Noche despejada", 5);
    obs_property_list_add_int(cond, "Niebla", 6);

    obs_properties_add_text(p, "accent_color", "Color de acento (Hex)", OBS_TEXT_DEFAULT);
    obs_property_t *pauto = obs_properties_add_bool(p, "dtw_auto_size", "Ajustar tamaño automáticamente al diseño y disposición");
    obs_property_set_modified_callback(pauto, dtw_on_props_modified);
    obs_properties_add_int(p, "width", "Ancho manual", 260, 1920, 16);
    obs_properties_add_int(p, "height", "Alto manual", 120, 1080, 8);
    return p;
}
void *dtw_create(obs_data_t *s, obs_source_t *src) { return gfx_create_common("datetime_weather", s, src); }

// ---- 6. Advanced Animated Title Generator ----
const char *at_get_name(void *) { return "Títulos Avanzados (Animados)"; }
void at_get_defaults(obs_data_t *s)
{
    obs_data_set_default_bool(s, "at_local_mode", false);
    obs_data_set_default_int(s, "at_template", 0);
    obs_data_set_default_int(s, "at_anim_in", 0);
    obs_data_set_default_int(s, "at_anim_out", 0);
    obs_data_set_default_int(s, "at_in_ms", 650);
    obs_data_set_default_int(s, "at_out_ms", 450);
    obs_data_set_default_int(s, "at_auto_hide_sec", 0);
    obs_data_set_default_bool(s, "at_shimmer", true);
    obs_data_set_default_string(s, "at_badge", "EN VIVO");
    obs_data_set_default_string(s, "at_title", "TÍTULO PRINCIPAL DEL PROGRAMA");
    obs_data_set_default_string(s, "at_subtitle", "Subtítulo o descripción en pantalla");
    obs_data_set_default_string(s, "at_ticker", "ÚLTIMO MOMENTO  •  TRANSMISIÓN EN DIRECTO  •  SÍGUENOS EN REDES SOCIALES  •  ");
    obs_data_set_default_string(s, "at_accent_color", "#2563EB");
    obs_data_set_default_string(s, "at_bg_color", "#0F172A");
    obs_data_set_default_string(s, "at_text_color", "#FFFFFF");
    obs_data_set_default_int(s, "at_align", 0);
    obs_data_set_default_int(s, "at_scale", 100);
    obs_data_set_default_string(s, "at_logo", "");
    obs_data_set_default_int(s, "width", 1280);
    obs_data_set_default_int(s, "height", 360);
}
obs_properties_t *at_get_properties(void *)
{
    obs_properties_t *p = obs_properties_create();
    obs_properties_add_text(p, "info", "Controla y lanza títulos animados en vivo desde Herramientas -> Generador Avanzado de Títulos (o el panel Control: Títulos Animados).", OBS_TEXT_INFO);
    obs_properties_add_bool(p, "at_local_mode", "Usar texto y diseño propios de esta fuente (ignorar panel en vivo)");

    obs_property_t *tpl = obs_properties_add_list(p, "at_template", "Plantilla visual", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    obs_property_list_add_int(tpl, "Noticiero Doble Barra", 0);
    obs_property_list_add_int(tpl, "Deportes / Impacto Diagonal", 1);
    obs_property_list_add_int(tpl, "Minimalista Cinematográfico", 2);
    obs_property_list_add_int(tpl, "Glassmorphism / Podcast", 3);
    obs_property_list_add_int(tpl, "Marquesina / Ticker Continuo", 4);
    obs_property_list_add_int(tpl, "Intro Pantalla Completa", 5);
    obs_property_list_add_int(tpl, "Tarjeta Redes Sociales", 6);
    obs_property_list_add_int(tpl, "Cita / Frase Destacada", 7);

    obs_property_t *ain = obs_properties_add_list(p, "at_anim_in", "Animación de entrada", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    obs_property_list_add_int(ain, "Deslizar Izquierda (Broadcast)", 0);
    obs_property_list_add_int(ain, "Revelado Cortina (Wipe)", 1);
    obs_property_list_add_int(ain, "Zoom / Pop Elástico", 2);
    obs_property_list_add_int(ain, "Subir Desde Abajo (Rise)", 3);
    obs_property_list_add_int(ain, "Máquina de Escribir (Typewriter)", 4);
    obs_property_list_add_int(ain, "Desvanecer Cinematográfico", 5);
    obs_property_list_add_int(ain, "Separación Central (Split)", 6);

    obs_property_t *aout = obs_properties_add_list(p, "at_anim_out", "Animación de salida", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    obs_property_list_add_int(aout, "Inversa de la entrada", 0);
    obs_property_list_add_int(aout, "Desvanecer suave", 1);
    obs_property_list_add_int(aout, "Bajar y ocultar", 2);
    obs_property_list_add_int(aout, "Cortina rápida", 3);

    obs_properties_add_int(p, "at_in_ms", "Duración entrada (ms)", 200, 2500, 50);
    obs_properties_add_int(p, "at_out_ms", "Duración salida (ms)", 150, 2000, 50);
    obs_properties_add_int(p, "at_auto_hide_sec", "Auto-ocultar tras (0 = manual, seg)", 0, 120, 1);
    obs_properties_add_bool(p, "at_shimmer", "Brillo animado continuo (Shimmer)");

    obs_properties_add_text(p, "at_badge", "Etiqueta / Badge", OBS_TEXT_DEFAULT);
    obs_properties_add_text(p, "at_title", "Título principal", OBS_TEXT_DEFAULT);
    obs_properties_add_text(p, "at_subtitle", "Subtítulo / Cargo", OBS_TEXT_DEFAULT);
    obs_properties_add_text(p, "at_ticker", "Texto marquesina / extra", OBS_TEXT_DEFAULT);
    obs_properties_add_text(p, "at_accent_color", "Color acento (Hex)", OBS_TEXT_DEFAULT);
    obs_properties_add_text(p, "at_bg_color", "Color fondo (Hex)", OBS_TEXT_DEFAULT);
    obs_properties_add_text(p, "at_text_color", "Color texto (Hex)", OBS_TEXT_DEFAULT);

    obs_property_t *al = obs_properties_add_list(p, "at_align", "Posición en lienzo", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    obs_property_list_add_int(al, "Izquierda", 0);
    obs_property_list_add_int(al, "Centro", 1);
    obs_property_list_add_int(al, "Derecha", 2);

    obs_properties_add_int(p, "at_scale", "Escala de texto (%)", 60, 140, 5);
    obs_properties_add_path(p, "at_logo", "Logo opcional (PNG/JPG)", OBS_PATH_FILE, "Imágenes (*.png *.jpg *.webp);;Todos (*)", nullptr);
    obs_properties_add_int(p, "width", "Ancho", 480, 1920, 16);
    obs_properties_add_int(p, "height", "Alto", 140, 1080, 16);
    return p;
}
void *at_create(obs_data_t *s, obs_source_t *src) { return gfx_create_common("advanced_title", s, src); }

// ---- 4. Bible ----
const char *bb_get_name(void *) { return "Biblia (Versiculos)"; }
void bb_get_defaults(obs_data_t *s)
{
    obs_data_set_default_string(s, "accent_color", "#D97706");
    obs_data_set_default_int(s, "bible_style", 0);
    obs_data_set_default_int(s, "font_size", 32);
    obs_data_set_default_int(s, "width", 1280);
    obs_data_set_default_int(s, "height", 360);
}
obs_properties_t *bb_get_properties(void *)
{
    obs_properties_t *p = obs_properties_create();
    obs_properties_add_button(p, "open_panel", "Abrir selector de Biblia en vivo...", on_open_bible_panel);
    obs_properties_add_text(p, "info", "Incluye la Biblia completa en espanol (66 libros, 31.102 versiculos offline). Activa tambien Paneles -> Control: Biblia.", OBS_TEXT_INFO);
    obs_property_t *st = obs_properties_add_list(p, "bible_style", "Diseno en pantalla", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    obs_property_list_add_int(st, "Zocalo Inferior Elegante", 0);
    obs_property_list_add_int(st, "Tarjeta Centrada Lectura", 1);
    obs_property_list_add_int(st, "Clasico Iglesia / Dorado", 2);
    obs_property_list_add_int(st, "Moderno Minimalista Lateral", 3);
    obs_property_list_add_int(st, "Pantalla Completa Predica", 4);
    obs_properties_add_text(p, "accent_color", "Color de acento (Hex)", OBS_TEXT_DEFAULT);
    obs_properties_add_int(p, "font_size", "Tamano de texto", 20, 64, 2);
    obs_properties_add_int(p, "width", "Ancho", 480, 1920, 16);
    obs_properties_add_int(p, "height", "Alto", 180, 1080, 16);
    return p;
}
void *bb_create(obs_data_t *s, obs_source_t *src) { return gfx_create_common("bible", s, src); }

// ---- 5. Native GPU Shape Mask Filter (Circulo / Ovalo / Redondeada / Rombo / Vineta / Diagonal PIP) ----
const char *kShapeMaskEffect = R"(
uniform float4x4 ViewProj;
uniform texture2d image;
uniform float2 uv_offset;
uniform float2 uv_aspect;
uniform float shape_type;
uniform float mask_scale;
uniform float corner_radius;
uniform float feather;
uniform float invert_mask;
uniform float border_width;
uniform float4 border_color;

sampler_state textureSampler {
    Filter   = Linear;
    AddressU = Clamp;
    AddressV = Clamp;
};

struct VertData {
    float4 pos : POSITION;
    float2 uv  : TEXCOORD0;
};

VertData VSDefault(VertData v_in)
{
    VertData vert_out;
    vert_out.pos = mul(float4(v_in.pos.xyz, 1.0), ViewProj);
    vert_out.uv  = v_in.uv;
    return vert_out;
}

float4 PSShapeMask(VertData v_in) : TARGET
{
    float4 col = image.Sample(textureSampler, v_in.uv);
    float2 p = (v_in.uv - float2(0.5, 0.5) - uv_offset) * 2.0;
    float s = max(mask_scale, 0.05);
    float d = 0.0;

    if (shape_type < 0.5) {
        // 0: Perfect Circle (aspect corrected)
        float2 pc = p * uv_aspect;
        d = length(pc) - s;
    } else if (shape_type < 1.5) {
        // 1: Oval / Ellipse
        d = length(p) - s;
    } else if (shape_type < 2.5) {
        // 2: Rounded Rectangle
        float2 halfSize = float2(s, s) * uv_aspect;
        float r = clamp(corner_radius, 0.0, 0.49) * min(halfSize.x, halfSize.y) * 2.0;
        float2 q = abs(p * uv_aspect) - (halfSize - float2(r, r));
        d = length(max(q, float2(0.0, 0.0))) + min(max(q.x, q.y), 0.0) - r;
    } else if (shape_type < 3.5) {
        // 3: Diamond
        float2 pc = abs(p * uv_aspect);
        d = (pc.x + pc.y) - s;
    } else if (shape_type < 4.5) {
        // 4: Soft Vignette
        d = length(p) - s;
    } else {
        // 5: Diagonal News PIP Cut (parallelogram)
        float2 pc = float2(p.x + p.y * 0.18, p.y) * uv_aspect;
        float2 halfSize = float2(s * uv_aspect.x * 0.92, s * uv_aspect.y);
        float2 q = abs(pc) - halfSize;
        d = length(max(q, float2(0.0, 0.0))) + min(max(q.x, q.y), 0.0);
    }

    float f = max(feather, 0.004);
    float alpha = 1.0 - smoothstep(-f, f, d);
    if (invert_mask > 0.5) {
        alpha = 1.0 - alpha;
    }

    if (border_width > 0.0005 && invert_mask < 0.5) {
        float outerA = 1.0 - smoothstep(-f, f, d);
        float innerA = 1.0 - smoothstep(-f, f, d + border_width);
        float ring = clamp(outerA - innerA, 0.0, 1.0);
        col.rgb = lerp(col.rgb, border_color.rgb * col.a, ring * border_color.a);
    }

    col.a *= alpha;
    col.rgb *= alpha;
    return col;
}

technique Draw
{
    pass
    {
        vertex_shader = VSDefault(v_in);
        pixel_shader  = PSShapeMask(v_in);
    }
}
)";

struct ShapeMaskFilter {
    std::mutex settings_mutex;
    obs_source_t *context{};
    gs_effect_t *effect{};
    gs_eparam_t *p_uv_offset{};
    gs_eparam_t *p_uv_aspect{};
    gs_eparam_t *p_shape{};
    gs_eparam_t *p_scale{};
    gs_eparam_t *p_radius{};
    gs_eparam_t *p_feather{};
    gs_eparam_t *p_invert{};
    gs_eparam_t *p_border_w{};
    gs_eparam_t *p_border_col{};

    int shape = 0;
    float scale = 0.92f;
    float corner_radius = 0.22f;
    float feather = 0.02f;
    float offset_x = 0.0f;
    float offset_y = 0.0f;
    bool invert = false;
    float border_width = 0.0f;
    vec4 border_color{1.0f, 1.0f, 1.0f, 1.0f};
};

const char *mask_get_name(void *) { return "Mascara de Forma (Circulo / Redondeada / PIP)"; }

void mask_update(void *data, obs_data_t *s)
{
    auto *f = static_cast<ShapeMaskFilter *>(data);
    std::lock_guard<std::mutex> lock(f->settings_mutex);
    f->shape = int(obs_data_get_int(s, "shape"));
    f->scale = float(obs_data_get_double(s, "scale")) / 100.0f;
    f->corner_radius = float(obs_data_get_double(s, "corner_radius")) / 100.0f;
    f->feather = float(obs_data_get_double(s, "feather")) / 100.0f;
    f->offset_x = float(obs_data_get_double(s, "offset_x")) / 100.0f;
    f->offset_y = float(obs_data_get_double(s, "offset_y")) / 100.0f;
    f->invert = obs_data_get_bool(s, "invert");
    f->border_width = float(obs_data_get_double(s, "border_width")) / 200.0f;
    uint32_t c = uint32_t(obs_data_get_int(s, "border_color"));
    vec4_from_rgba(&f->border_color, c);
}

void *mask_create(obs_data_t *s, obs_source_t *src)
{
    auto *f = new ShapeMaskFilter();
    f->context = src;
    obs_enter_graphics();
    char *err = nullptr;
    f->effect = gs_effect_create(kShapeMaskEffect, "android_shape_mask", &err);
    if (err) {
        blog(LOG_ERROR, "[android-mask] Effect error: %s", err);
        bfree(err);
    }
    if (f->effect) {
        f->p_uv_offset = gs_effect_get_param_by_name(f->effect, "uv_offset");
        f->p_uv_aspect = gs_effect_get_param_by_name(f->effect, "uv_aspect");
        f->p_shape = gs_effect_get_param_by_name(f->effect, "shape_type");
        f->p_scale = gs_effect_get_param_by_name(f->effect, "mask_scale");
        f->p_radius = gs_effect_get_param_by_name(f->effect, "corner_radius");
        f->p_feather = gs_effect_get_param_by_name(f->effect, "feather");
        f->p_invert = gs_effect_get_param_by_name(f->effect, "invert_mask");
        f->p_border_w = gs_effect_get_param_by_name(f->effect, "border_width");
        f->p_border_col = gs_effect_get_param_by_name(f->effect, "border_color");
    }
    obs_leave_graphics();
    mask_update(f, s);
    return f;
}

void mask_destroy(void *data)
{
    auto *f = static_cast<ShapeMaskFilter *>(data);
    if (!f) return;
    if (f->effect) {
        obs_enter_graphics();
        gs_effect_destroy(f->effect);
        obs_leave_graphics();
    }
    delete f;
}

void mask_get_defaults(obs_data_t *s)
{
    obs_data_set_default_int(s, "shape", 0);
    obs_data_set_default_double(s, "scale", 92.0);
    obs_data_set_default_double(s, "corner_radius", 22.0);
    obs_data_set_default_double(s, "feather", 2.0);
    obs_data_set_default_double(s, "offset_x", 0.0);
    obs_data_set_default_double(s, "offset_y", 0.0);
    obs_data_set_default_bool(s, "invert", false);
    obs_data_set_default_double(s, "border_width", 0.0);
    obs_data_set_default_int(s, "border_color", 0xFFFFFFFF);
}

obs_properties_t *mask_get_properties(void *)
{
    obs_properties_t *p = obs_properties_create();
    obs_property_t *sh = obs_properties_add_list(p, "shape", "Forma de mascara", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
    obs_property_list_add_int(sh, "Circulo perfecto (Webcam / Podcast)", 0);
    obs_property_list_add_int(sh, "Ovalo / Elipse", 1);
    obs_property_list_add_int(sh, "Rectangulo redondeado (PIP)", 2);
    obs_property_list_add_int(sh, "Rombo / Diamante", 3);
    obs_property_list_add_int(sh, "Vineta suave (Cine / Noticiero)", 4);
    obs_property_list_add_int(sh, "Recorte diagonal (Noticiero PIP)", 5);
    obs_properties_add_float_slider(p, "scale", "Escala (%)", 10.0, 100.0, 1.0);
    obs_properties_add_float_slider(p, "corner_radius", "Redondeo esquinas (%)", 0.0, 50.0, 1.0);
    obs_properties_add_float_slider(p, "feather", "Suavizado de borde (%)", 0.0, 35.0, 0.5);
    obs_properties_add_float_slider(p, "offset_x", "Desplazamiento X (%)", -50.0, 50.0, 1.0);
    obs_properties_add_float_slider(p, "offset_y", "Desplazamiento Y (%)", -50.0, 50.0, 1.0);
    obs_properties_add_bool(p, "invert", "Invertir mascara");
    obs_properties_add_float_slider(p, "border_width", "Grosor de borde (0 = sin borde)", 0.0, 24.0, 0.5);
    obs_properties_add_color(p, "border_color", "Color de borde");
    return p;
}

void mask_render(void *data, gs_effect_t *)
{
    auto *f = static_cast<ShapeMaskFilter *>(data);
    if (!f || !f->effect) {
        obs_source_skip_video_filter(f ? f->context : nullptr);
        return;
    }
    obs_source_t *target = obs_filter_get_target(f->context);
    uint32_t w = target ? obs_source_get_base_width(target) : 0;
    uint32_t h = target ? obs_source_get_base_height(target) : 0;
    if (!obs_source_process_filter_begin(f->context, GS_RGBA, OBS_ALLOW_DIRECT_RENDERING))
        return;

    {
        std::lock_guard<std::mutex> lock(f->settings_mutex);
        vec2 off{f->offset_x, f->offset_y};
        vec2 asp{1.0f, 1.0f};
        if (w > 0 && h > 0) {
            if (w >= h) asp.x = float(w) / float(h);
            else asp.y = float(h) / float(w);
        }
        gs_effect_set_vec2(f->p_uv_offset, &off);
        gs_effect_set_vec2(f->p_uv_aspect, &asp);
        gs_effect_set_float(f->p_shape, float(f->shape));
        gs_effect_set_float(f->p_scale, f->scale);
        gs_effect_set_float(f->p_radius, f->corner_radius);
        gs_effect_set_float(f->p_feather, f->shape == 4 ? std::max(f->feather, 0.18f) : f->feather);
        gs_effect_set_float(f->p_invert, f->invert ? 1.0f : 0.0f);
        gs_effect_set_float(f->p_border_w, f->border_width);
        gs_effect_set_vec4(f->p_border_col, &f->border_color);
    }

    gs_blend_state_push();
    gs_blend_function(GS_BLEND_ONE, GS_BLEND_INVSRCALPHA);
    obs_source_process_filter_end(f->context, f->effect, 0, 0);
    gs_blend_state_pop();
}
const char *kPerspectiveEffect = R"(
uniform float4x4 ViewProj;
uniform texture2d image;
uniform float3 axis_x;
uniform float3 axis_y;
uniform float3 normal;
uniform float aspect;
uniform float zoom;

sampler_state linearSampler {
    Filter = Linear;
    AddressU = Clamp;
    AddressV = Clamp;
};

struct VertData {
    float4 pos : POSITION;
    float2 uv : TEXCOORD0;
};

VertData VS(VertData v_in)
{
    VertData vert_out;
    vert_out.pos = mul(float4(v_in.pos.xyz, 1.0), ViewProj);
    vert_out.uv = v_in.uv;
    return vert_out;
}

float4 PS(VertData v_in) : TARGET
{
    float d = 2.5;
    float2 p = (v_in.uv - float2(0.5, 0.5)) * float2(aspect, 1.0) / zoom;
    float3 ray = float3(p, d);
    float divisor = dot(ray, normal);
    if (divisor <= 0.001)
        return float4(0.0, 0.0, 0.0, 0.0);
    float3 point = ray * (d * normal.z / divisor) - float3(0.0, 0.0, d);
    float2 uv = float2(dot(point, axis_x) / aspect, dot(point, axis_y)) + float2(0.5, 0.5);
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0)
        return float4(0.0, 0.0, 0.0, 0.0);
    return image.Sample(linearSampler, uv);
}

technique Draw
{
    pass {
        vertex_shader = VS(v_in);
        pixel_shader = PS(v_in);
    }
}
)";

struct PerspectiveFilter {
    obs_source_t *context{};
    gs_effect_t *effect{};
    std::mutex mutex;
    vec3 x{}, y{}, normal{};
    float zoom = 1.0f;
};

void perspective_update(void *data, obs_data_t *settings)
{
    auto &f = *static_cast<PerspectiveFilter *>(data);
    constexpr float radians = 0.01745329252f;
    const float pitch = float(std::clamp(obs_data_get_double(settings, "pitch"), -65.0, 65.0)) * radians;
    const float yaw = float(std::clamp(obs_data_get_double(settings, "yaw"), -65.0, 65.0)) * radians;
    const float roll = float(std::clamp(obs_data_get_double(settings, "roll"), -180.0, 180.0)) * radians;
    const float cx = std::cos(pitch), sx = std::sin(pitch);
    const float cy = std::cos(yaw), sy = std::sin(yaw);
    const float cz = std::cos(roll), sz = std::sin(roll);
    const std::lock_guard<std::mutex> lock(f.mutex);
    vec3_set(&f.x, cy * cz, cy * sz, -sy);
    vec3_set(&f.y, sx * sy * cz - cx * sz, sx * sy * sz + cx * cz, sx * cy);
    vec3_cross(&f.normal, &f.x, &f.y);
    f.zoom = float(std::clamp(obs_data_get_double(settings, "zoom"), 25.0, 175.0)) / 100.0f;
}

void *perspective_create(obs_data_t *settings, obs_source_t *source)
{
    auto *f = new PerspectiveFilter();
    f->context = source;
    obs_enter_graphics();
    char *error = nullptr;
    f->effect = gs_effect_create(kPerspectiveEffect, "android_perspective", &error);
    obs_leave_graphics();
    if (error) {
        blog(LOG_ERROR, "[android-perspective] %s", error);
        bfree(error);
    }
    if (!f->effect) {
        delete f;
        return nullptr;
    }
    perspective_update(f, settings);
    return f;
}

void perspective_destroy(void *data)
{
    auto *f = static_cast<PerspectiveFilter *>(data);
    obs_enter_graphics();
    gs_effect_destroy(f->effect);
    obs_leave_graphics();
    delete f;
}

void perspective_defaults(obs_data_t *s)
{
    obs_data_set_default_double(s, "zoom", 100.0);
}

obs_properties_t *perspective_properties(void *)
{
    auto *props = obs_properties_create();
    obs_properties_add_float_slider(props, "pitch", "Inclinacion vertical (X)", -65, 65, 1);
    obs_properties_add_float_slider(props, "yaw", "Giro lateral (Y)", -65, 65, 1);
    obs_properties_add_float_slider(props, "roll", "Rotacion (Z)", -180, 180, 1);
    obs_properties_add_float_slider(props, "zoom", "Escala (%)", 25, 175, 1);
    return props;
}

void perspective_render(void *data, gs_effect_t *)
{
    auto &f = *static_cast<PerspectiveFilter *>(data);
    auto *target = obs_filter_get_target(f.context);
    const auto w = target ? obs_source_get_base_width(target) : 0;
    const auto h = target ? obs_source_get_base_height(target) : 0;
    if (!w || !h) {
        obs_source_skip_video_filter(f.context);
        return;
    }
    if (!obs_source_process_filter_begin(f.context, GS_RGBA, OBS_NO_DIRECT_RENDERING))
        return;

    {
        const std::lock_guard<std::mutex> lock(f.mutex);
        gs_effect_set_vec3(gs_effect_get_param_by_name(f.effect, "axis_x"), &f.x);
        gs_effect_set_vec3(gs_effect_get_param_by_name(f.effect, "axis_y"), &f.y);
        gs_effect_set_vec3(gs_effect_get_param_by_name(f.effect, "normal"), &f.normal);
        gs_effect_set_float(gs_effect_get_param_by_name(f.effect, "aspect"), float(w) / float(h));
        gs_effect_set_float(gs_effect_get_param_by_name(f.effect, "zoom"), f.zoom);
    }
    gs_blend_state_push();
    gs_blend_function(GS_BLEND_ONE, GS_BLEND_INVSRCALPHA);
    obs_source_process_filter_end(f.context, f.effect, 0, 0);
    gs_blend_state_pop();
}

} // namespace

void android_graphics_register()
{
    if (g_gfx_registered) return;

    auto reg = [](const char *id, const char *(*get_name)(void *), void *(*create)(obs_data_t *, obs_source_t *),
                  void (*get_defaults)(obs_data_t *), obs_properties_t *(*get_props)(void *)) {
        obs_source_info info{};
        info.id = id;
        info.type = OBS_SOURCE_TYPE_INPUT;
        info.output_flags = OBS_SOURCE_VIDEO | OBS_SOURCE_CUSTOM_DRAW | OBS_SOURCE_SRGB;
        info.get_name = get_name;
        info.create = create;
        info.destroy = gfx_destroy_common;
        info.get_defaults = get_defaults;
        info.get_properties = get_props;
        info.update = gfx_update_common;
        info.show = gfx_show_common;
        info.hide = gfx_hide_common;
        info.get_width = gfx_get_width;
        info.get_height = gfx_get_height;
        info.video_render = gfx_video_render;
        info.icon_type = OBS_ICON_TYPE_TEXT;
        obs_register_source(&info);
    };

    reg("android_scoreboard_source", sb_get_name, sb_create, sb_get_defaults, sb_get_properties);
    reg("android_lowerthird_source", lt_get_name, lt_create, lt_get_defaults, lt_get_properties);
    reg("android_timer_source", tm_get_name, tm_create, tm_get_defaults, tm_get_properties);
    reg("android_bible_source", bb_get_name, bb_create, bb_get_defaults, bb_get_properties);
    reg("android_datetime_weather_source", dtw_get_name, dtw_create, dtw_get_defaults, dtw_get_properties);
    reg("android_advanced_title_source", at_get_name, at_create, at_get_defaults, at_get_properties);

    obs_source_info maskInfo{};
    maskInfo.id = "android_shape_mask_filter";
    maskInfo.type = OBS_SOURCE_TYPE_FILTER;
    maskInfo.output_flags = OBS_SOURCE_VIDEO;
    maskInfo.get_name = mask_get_name;
    maskInfo.create = mask_create;
    maskInfo.destroy = mask_destroy;
    maskInfo.get_defaults = mask_get_defaults;
    maskInfo.get_properties = mask_get_properties;
    maskInfo.update = mask_update;
    maskInfo.video_render = mask_render;
    obs_register_source(&maskInfo);

    obs_source_info perspective{};
    perspective.id = "android_perspective_filter";
    perspective.type = OBS_SOURCE_TYPE_FILTER;
    perspective.output_flags = OBS_SOURCE_VIDEO;
    perspective.get_name = [](void *) { return "Perspectiva 3D (Android)"; };
    perspective.create = perspective_create;
    perspective.destroy = perspective_destroy;
    perspective.update = perspective_update;
    perspective.get_defaults = perspective_defaults;
    perspective.get_properties = perspective_properties;
    perspective.video_render = perspective_render;
    obs_register_source(&perspective);

    g_gfx_registered = true;
}

extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_registerGraphicsHost(JNIEnv *env, jclass, jclass hostClass)
{
    std::lock_guard<std::mutex> lock(g_gfx_mutex);
    if (g_gfx_host_class) return;
    env->GetJavaVM(&g_gfx_jvm);
    g_gfx_host_class = static_cast<jclass>(env->NewGlobalRef(hostClass));
    if (!g_gfx_host_class) return;
    auto get_mid = [&](const char *name, const char *sig) -> jmethodID {
        jmethodID m = env->GetStaticMethodID(g_gfx_host_class, name, sig);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            return nullptr;
        }
        return m;
    };
    g_mid_gfx_update = get_mid("createOrUpdate", "(JLjava/lang/String;Ljava/lang/String;II)V");
    g_mid_gfx_destroy = get_mid("destroy", "(J)V");
    g_mid_gfx_set_visible = get_mid("setSourceVisible", "(JZ)V");
    g_mid_gfx_open_panel = get_mid("openControlPanel", "(Ljava/lang/String;)V");
    g_mid_gfx_get_state = get_mid("getStateJsonStatic", "()Ljava/lang/String;");
    g_mid_gfx_exec = get_mid("executeActionJsonStatic", "(Ljava/lang/String;)Ljava/lang/String;");
    g_mid_gfx_books = get_mid("getBibleBooksJson", "()Ljava/lang/String;");
    g_mid_gfx_search = get_mid("searchBibleJsonStatic", "(Ljava/lang/String;)Ljava/lang/String;");
}

static bool call_gfx_string(jmethodID mid, const char *arg, char *out_buf, size_t out_cap)
{
    if (!out_buf || out_cap == 0) return false;
    out_buf[0] = '\0';
    JavaVM *jvm = nullptr; jclass cls = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_gfx_mutex);
        jvm = g_gfx_jvm; cls = g_gfx_host_class;
    }
    if (!jvm || !cls || !mid) return false;
    JNIEnv *env = nullptr; bool detach = false;
    int st = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
    if (st == JNI_EDETACHED) { if (jvm->AttachCurrentThread(&env, nullptr) != 0) return false; detach = true; }
    else if (st != JNI_OK) return false;

    jstring jArg = arg ? env->NewStringUTF(arg) : nullptr;
    jstring jRes = arg
        ? static_cast<jstring>(env->CallStaticObjectMethod(cls, mid, jArg))
        : static_cast<jstring>(env->CallStaticObjectMethod(cls, mid));
    bool ok = false;
    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    } else if (jRes) {
        const char *utf = env->GetStringUTFChars(jRes, nullptr);
        if (utf) {
            std::snprintf(out_buf, out_cap, "%s", utf);
            env->ReleaseStringUTFChars(jRes, utf);
            ok = true;
        }
        env->DeleteLocalRef(jRes);
    }
    if (jArg) env->DeleteLocalRef(jArg);
    if (detach) jvm->DetachCurrentThread();
    return ok;
}

extern "C" __attribute__((visibility("default"))) bool obs_android_graphics_get_state(char *out_buf, size_t out_cap)
{
    jmethodID mid = nullptr;
    { std::lock_guard<std::mutex> lock(g_gfx_mutex); mid = g_mid_gfx_get_state; }
    return call_gfx_string(mid, nullptr, out_buf, out_cap);
}

extern "C" __attribute__((visibility("default"))) bool obs_android_graphics_execute(const char *action_json, char *out_buf, size_t out_cap)
{
    jmethodID mid = nullptr;
    { std::lock_guard<std::mutex> lock(g_gfx_mutex); mid = g_mid_gfx_exec; }
    return call_gfx_string(mid, action_json ? action_json : "{}", out_buf, out_cap);
}

extern "C" __attribute__((visibility("default"))) bool obs_android_bible_books(char *out_buf, size_t out_cap)
{
    jmethodID mid = nullptr;
    { std::lock_guard<std::mutex> lock(g_gfx_mutex); mid = g_mid_gfx_books; }
    return call_gfx_string(mid, nullptr, out_buf, out_cap);
}

extern "C" __attribute__((visibility("default"))) bool obs_android_bible_search(const char *query, char *out_buf, size_t out_cap)
{
    jmethodID mid = nullptr;
    { std::lock_guard<std::mutex> lock(g_gfx_mutex); mid = g_mid_gfx_search; }
    return call_gfx_string(mid, query ? query : "", out_buf, out_cap);
}

extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_pushGraphicsFrame(
    JNIEnv *env, jclass, jlong id, jobject rgbaBuf, jint width, jint height, jlong)
{
    AndroidCallbackScope callback;
    if (!callback) return;
    if (!rgbaBuf || width <= 0 || height <= 0) return;
    auto *bytes = static_cast<uint8_t *>(env->GetDirectBufferAddress(rgbaBuf));
    jlong cap = env->GetDirectBufferCapacity(rgbaBuf);
    int64_t expected = int64_t(width) * int64_t(height) * 4;
    if (!bytes || cap < expected) return;

    // Submit premultiplied Android Bitmap RGBA directly to GPU AndroidRgbaFrame (zero CPU un-premultiply loop)
    std::lock_guard<std::mutex> lock(g_gfx_mutex);
    auto it = g_gfx_instances.find(int64_t(id));
    if (it != g_gfx_instances.end() && it->second) {
        it->second->frame.submit(bytes, uint32_t(width), uint32_t(height), uint32_t(width) * 4, true);
    }
}
