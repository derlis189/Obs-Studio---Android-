// SPDX-License-Identifier: GPL-2.0-or-later
#include "android-bg-removal-filter.h"
#include <obs-module.h>
#include <graphics/vec2.h>
#include <graphics/vec4.h>
#include <jni.h>
#include <android/log.h>
#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>
#include <unordered_map>
#include <vector>

namespace {

constexpr int kInferW = 256;
constexpr int kInferH = 144;

constexpr const char *kBgRemovalEffectSrc = R"EFFECT(
uniform float4x4 ViewProj;
uniform texture2d image;
uniform texture2d mask_tex;
uniform texture2d prev_mask_tex;
uniform texture2d blur_tex;

uniform float2 texel_size;
uniform float2 blur_step;
uniform float temporal_alpha;
uniform int bg_mode;        // 0=Transparent, 1=Bokeh, 2=SolidColor
uniform float threshold;
uniform float feather;
uniform float despill;
uniform float4 bg_color;

sampler_state linearSampler {
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
	VertData v_out;
	v_out.pos = mul(float4(v_in.pos.xyz, 1.0), ViewProj);
	v_out.uv  = v_in.uv;
	return v_out;
}

float4 PSSmoothMask(VertData v_in) : TARGET
{
	// Gentle 3x3 tent filter on raw ML mask + temporal exponential smoothing
	float2 d = texel_size;
	float m = mask_tex.Sample(linearSampler, v_in.uv).r * 0.25;
	m += mask_tex.Sample(linearSampler, v_in.uv + float2(d.x, 0.0)).r * 0.125;
	m += mask_tex.Sample(linearSampler, v_in.uv - float2(d.x, 0.0)).r * 0.125;
	m += mask_tex.Sample(linearSampler, v_in.uv + float2(0.0, d.y)).r * 0.125;
	m += mask_tex.Sample(linearSampler, v_in.uv - float2(0.0, d.y)).r * 0.125;
	m += mask_tex.Sample(linearSampler, v_in.uv + float2(d.x, d.y)).r * 0.0625;
	m += mask_tex.Sample(linearSampler, v_in.uv + float2(-d.x, d.y)).r * 0.0625;
	m += mask_tex.Sample(linearSampler, v_in.uv + float2(d.x, -d.y)).r * 0.0625;
	m += mask_tex.Sample(linearSampler, v_in.uv + float2(-d.x, -d.y)).r * 0.0625;

	float prev = prev_mask_tex.Sample(linearSampler, v_in.uv).r;
	float out_m = lerp(m, prev, temporal_alpha);
	return float4(out_m, out_m, out_m, 1.0);
}

float4 PSBlur1D(VertData v_in) : TARGET
{
	float4 sum = image.Sample(linearSampler, v_in.uv) * 0.19648255;
	sum += image.Sample(linearSampler, v_in.uv + blur_step * 1.4117647) * 0.29690696;
	sum += image.Sample(linearSampler, v_in.uv - blur_step * 1.4117647) * 0.29690696;
	sum += image.Sample(linearSampler, v_in.uv + blur_step * 3.2941176) * 0.09447040;
	sum += image.Sample(linearSampler, v_in.uv - blur_step * 3.2941176) * 0.09447040;
	sum += image.Sample(linearSampler, v_in.uv + blur_step * 5.1764706) * 0.01038136;
	sum += image.Sample(linearSampler, v_in.uv - blur_step * 5.1764706) * 0.01038136;
	return sum;
}

float4 PSComposite(VertData v_in) : TARGET
{
	float4 src_pm = image.Sample(linearSampler, v_in.uv);
	float m = mask_tex.Sample(linearSampler, v_in.uv).r;

	float half_w = max(0.02, feather * 0.35);
	float center = saturate(threshold + despill * 0.12);
	float lo = saturate(center - half_w);
	float hi = saturate(center + half_w);
	float fg = smoothstep(lo, max(lo + 0.01, hi), m);

	if (bg_mode == 0) {
		return float4(src_pm.rgb * fg, src_pm.a * fg);
	}
	if (bg_mode == 1) {
		float4 bg_pm = blur_tex.Sample(linearSampler, v_in.uv);
		return lerp(bg_pm, src_pm, fg);
	}
	// Mode 2: solid virtual background color (premultiplied)
	float4 bg_pm = float4(bg_color.rgb, 1.0);
	return lerp(bg_pm, src_pm, fg);
}

technique SmoothMask
{
	pass
	{
		vertex_shader = VSDefault(v_in);
		pixel_shader  = PSSmoothMask(v_in);
	}
}

technique Blur1D
{
	pass
	{
		vertex_shader = VSDefault(v_in);
		pixel_shader  = PSBlur1D(v_in);
	}
}

technique Composite
{
	pass
	{
		vertex_shader = VSDefault(v_in);
		pixel_shader  = PSComposite(v_in);
	}
}
)EFFECT";

std::mutex g_bg_host_mutex;
JavaVM *g_bg_jvm = nullptr;
jclass g_bg_host_class = nullptr;
jmethodID g_mid_bg_submit = nullptr;
jmethodID g_mid_bg_destroy = nullptr;
jmethodID g_mid_bg_destroy_all = nullptr;
std::atomic<int64_t> g_bg_seq{0};

struct BgRemovalFilter;
std::mutex g_bg_inst_mutex;
std::unordered_map<int64_t, BgRemovalFilter *> g_bg_instances;

vec4 obs_color_to_vec4(uint32_t abgr)
{
	vec4 c{};
	c.x = float(abgr & 0xFFu) / 255.0f;
	c.y = float((abgr >> 8) & 0xFFu) / 255.0f;
	c.z = float((abgr >> 16) & 0xFFu) / 255.0f;
	c.w = 1.0f;
	return c;
}

int64_t now_ms_steady()
{
	return std::chrono::duration_cast<std::chrono::milliseconds>(
		std::chrono::steady_clock::now().time_since_epoch()).count();
}

struct BgRemovalFilter {
	int64_t id{0};
	obs_source_t *context{nullptr};
	gs_effect_t *effect{nullptr};

	gs_eparam_t *p_image{nullptr};
	gs_eparam_t *p_mask_tex{nullptr};
	gs_eparam_t *p_prev_mask_tex{nullptr};
	gs_eparam_t *p_blur_tex{nullptr};
	gs_eparam_t *p_texel_size{nullptr};
	gs_eparam_t *p_blur_step{nullptr};
	gs_eparam_t *p_temporal_alpha{nullptr};
	gs_eparam_t *p_bg_mode{nullptr};
	gs_eparam_t *p_threshold{nullptr};
	gs_eparam_t *p_feather{nullptr};
	gs_eparam_t *p_despill{nullptr};
	gs_eparam_t *p_bg_color{nullptr};

	gs_texrender_t *src_tr{nullptr};
	gs_texrender_t *small_tr{nullptr};
	gs_stagesurf_t *stage_surf{nullptr};
	gs_texrender_t *blur_h_tr{nullptr};
	gs_texrender_t *blur_v_tr{nullptr};
	gs_texrender_t *smooth_tr[2]{nullptr, nullptr};
	int smooth_idx{0};
	bool smooth_initialized{false};

	gs_texture_t *raw_mask_tex{nullptr};
	uint32_t raw_mask_w{0};
	uint32_t raw_mask_h{0};

	std::vector<uint8_t> readback_rgba;
	int64_t last_infer_ms{0};

	// Incoming mask from Java ML Kit thread (or test harness)
	std::mutex mask_mutex;
	std::vector<uint8_t> pending_mask;
	uint32_t pending_w{0};
	uint32_t pending_h{0};
	bool has_pending_mask{false};
	bool has_any_mask{false};

	// Settings
	int bg_mode{0};          // 0=Transparent, 1=Bokeh, 2=SolidColor
	float threshold{0.50f};
	float feather{0.35f};
	float temporal_smooth{0.45f};
	float blur_strength{0.70f};
	float despill{0.20f};
	vec4 bg_color{0.067f, 0.094f, 0.153f, 1.0f}; // #111827
	int infer_fps{24};
};

void dispatch_frame_to_java(int64_t id, const uint8_t *rgba, int w, int h)
{
	JavaVM *jvm = nullptr;
	jclass cls = nullptr;
	jmethodID mid = nullptr;
	{
		std::lock_guard<std::mutex> lock(g_bg_host_mutex);
		jvm = g_bg_jvm;
		cls = g_bg_host_class;
		mid = g_mid_bg_submit;
	}
	if (!jvm || !cls || !mid || !rgba || w <= 0 || h <= 0)
		return;

	JNIEnv *env = nullptr;
	bool detach = false;
	int st = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
	if (st == JNI_EDETACHED) {
		if (jvm->AttachCurrentThread(&env, nullptr) != 0)
			return;
		detach = true;
	} else if (st != JNI_OK) {
		return;
	}

	const jsize len = jsize(w * h * 4);
	jbyteArray arr = env->NewByteArray(len);
	if (arr) {
		env->SetByteArrayRegion(arr, 0, len, reinterpret_cast<const jbyte *>(rgba));
		env->CallStaticVoidMethod(cls, mid, jlong(id), arr, jint(w), jint(h));
		env->DeleteLocalRef(arr);
	}
	if (env->ExceptionCheck())
		env->ExceptionClear();
	if (detach)
		jvm->DetachCurrentThread();
}

void destroy_java_instance(int64_t id)
{
	JavaVM *jvm = nullptr;
	jclass cls = nullptr;
	jmethodID mid = nullptr;
	{
		std::lock_guard<std::mutex> lock(g_bg_host_mutex);
		jvm = g_bg_jvm;
		cls = g_bg_host_class;
		mid = g_mid_bg_destroy;
	}
	if (!jvm || !cls || !mid)
		return;
	JNIEnv *env = nullptr;
	bool detach = false;
	int st = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
	if (st == JNI_EDETACHED) {
		if (jvm->AttachCurrentThread(&env, nullptr) != 0)
			return;
		detach = true;
	} else if (st != JNI_OK) {
		return;
	}
	env->CallStaticVoidMethod(cls, mid, jlong(id));
	if (env->ExceptionCheck())
		env->ExceptionClear();
	if (detach)
		jvm->DetachCurrentThread();
}

const char *bg_get_name(void *)
{
	return "Quitar Fondo / Bokeh IA (GPU)";
}

void bg_get_defaults(obs_data_t *s)
{
	obs_data_set_default_int(s, "bg_mode", 0);
	obs_data_set_default_int(s, "threshold", 50);
	obs_data_set_default_int(s, "feather", 35);
	obs_data_set_default_int(s, "temporal_smooth", 45);
	obs_data_set_default_int(s, "blur_strength", 70);
	obs_data_set_default_int(s, "despill", 20);
	obs_data_set_default_int(s, "bg_color", 0xFF271811); // #111827
	obs_data_set_default_int(s, "infer_fps", 24);
}

bool on_bg_mode_modified(obs_properties_t *props, obs_property_t *, obs_data_t *s)
{
	int mode = int(obs_data_get_int(s, "bg_mode"));
	obs_property_set_visible(obs_properties_get(props, "blur_strength"), mode == 1);
	obs_property_set_visible(obs_properties_get(props, "bg_color"), mode == 2);
	return true;
}

obs_properties_t *bg_get_properties(void *)
{
	obs_properties_t *props = obs_properties_create();

	obs_property_t *mode = obs_properties_add_list(
		props, "bg_mode", "Modo de fondo IA", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
	obs_property_list_add_int(mode, "Fondo transparente (Recorte sin croma)", 0);
	obs_property_list_add_int(mode, "Modo Retrato / Bokeh (Fondo borroso)", 1);
	obs_property_list_add_int(mode, "Fondo de color sólido", 2);
	obs_property_set_modified_callback(mode, on_bg_mode_modified);

	obs_properties_add_int_slider(props, "threshold", "Umbral de persona (%)", 10, 90, 1);
	obs_properties_add_int_slider(props, "feather", "Suavizado de bordes (%)", 0, 100, 1);
	obs_properties_add_int_slider(props, "temporal_smooth", "Estabilización anti-parpadeo (%)", 0, 90, 5);
	obs_properties_add_int_slider(props, "despill", "Recorte fino de halo (%)", 0, 100, 5);
	obs_properties_add_int_slider(props, "blur_strength", "Intensidad de Bokeh (%)", 10, 100, 5);
	obs_properties_add_color(props, "bg_color", "Color de fondo virtual");

	obs_property_t *fps = obs_properties_add_list(
		props, "infer_fps", "Frecuencia de segmentación IA", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
	obs_property_list_add_int(fps, "15 FPS (ahorro de batería)", 15);
	obs_property_list_add_int(fps, "24 FPS (equilibrado)", 24);
	obs_property_list_add_int(fps, "30 FPS (máxima fluidez)", 30);

	return props;
}

void bg_update(void *data, obs_data_t *s)
{
	auto *f = static_cast<BgRemovalFilter *>(data);
	f->bg_mode = std::clamp(int(obs_data_get_int(s, "bg_mode")), 0, 2);
	f->threshold = std::clamp(float(obs_data_get_int(s, "threshold")) / 100.0f, 0.05f, 0.95f);
	f->feather = std::clamp(float(obs_data_get_int(s, "feather")) / 100.0f, 0.0f, 1.0f);
	f->temporal_smooth = std::clamp(float(obs_data_get_int(s, "temporal_smooth")) / 100.0f, 0.0f, 0.92f);
	f->blur_strength = std::clamp(float(obs_data_get_int(s, "blur_strength")) / 100.0f, 0.05f, 1.0f);
	f->despill = std::clamp(float(obs_data_get_int(s, "despill")) / 100.0f, 0.0f, 1.0f);
	f->bg_color = obs_color_to_vec4(uint32_t(obs_data_get_int(s, "bg_color")));
	f->infer_fps = std::clamp(int(obs_data_get_int(s, "infer_fps")), 10, 30);
}

void *bg_create(obs_data_t *settings, obs_source_t *context)
{
	auto *f = new BgRemovalFilter();
	f->id = ++g_bg_seq;
	f->context = context;
	f->readback_rgba.resize(size_t(kInferW) * kInferH * 4);

	obs_enter_graphics();
	char *err = nullptr;
	f->effect = gs_effect_create(kBgRemovalEffectSrc, "android_bg_removal.effect", &err);
	if (err) {
		blog(LOG_ERROR, "[BgRemovalFilter] effect error: %s", err);
		bfree(err);
	}
	if (f->effect) {
		f->p_image = gs_effect_get_param_by_name(f->effect, "image");
		f->p_mask_tex = gs_effect_get_param_by_name(f->effect, "mask_tex");
		f->p_prev_mask_tex = gs_effect_get_param_by_name(f->effect, "prev_mask_tex");
		f->p_blur_tex = gs_effect_get_param_by_name(f->effect, "blur_tex");
		f->p_texel_size = gs_effect_get_param_by_name(f->effect, "texel_size");
		f->p_blur_step = gs_effect_get_param_by_name(f->effect, "blur_step");
		f->p_temporal_alpha = gs_effect_get_param_by_name(f->effect, "temporal_alpha");
		f->p_bg_mode = gs_effect_get_param_by_name(f->effect, "bg_mode");
		f->p_threshold = gs_effect_get_param_by_name(f->effect, "threshold");
		f->p_feather = gs_effect_get_param_by_name(f->effect, "feather");
		f->p_despill = gs_effect_get_param_by_name(f->effect, "despill");
		f->p_bg_color = gs_effect_get_param_by_name(f->effect, "bg_color");
	}
	obs_leave_graphics();

	if (!f->effect) {
		delete f;
		return nullptr;
	}

	{
		std::lock_guard<std::mutex> lock(g_bg_inst_mutex);
		g_bg_instances[f->id] = f;
	}

	bg_update(f, settings);
	return f;
}

void bg_destroy(void *data)
{
	auto *f = static_cast<BgRemovalFilter *>(data);
	if (!f)
		return;
	{
		std::lock_guard<std::mutex> lock(g_bg_inst_mutex);
		g_bg_instances.erase(f->id);
	}
	destroy_java_instance(f->id);

	obs_enter_graphics();
	if (f->src_tr) gs_texrender_destroy(f->src_tr);
	if (f->small_tr) gs_texrender_destroy(f->small_tr);
	if (f->stage_surf) gs_stagesurface_destroy(f->stage_surf);
	if (f->blur_h_tr) gs_texrender_destroy(f->blur_h_tr);
	if (f->blur_v_tr) gs_texrender_destroy(f->blur_v_tr);
	if (f->smooth_tr[0]) gs_texrender_destroy(f->smooth_tr[0]);
	if (f->smooth_tr[1]) gs_texrender_destroy(f->smooth_tr[1]);
	if (f->raw_mask_tex) gs_texture_destroy(f->raw_mask_tex);
	if (f->effect) gs_effect_destroy(f->effect);
	obs_leave_graphics();

	delete f;
}

void upload_and_smooth_mask_if_ready(BgRemovalFilter *f)
{
	std::vector<uint8_t> local_mask;
	uint32_t mw = 0, mh = 0;
	{
		std::lock_guard<std::mutex> lock(f->mask_mutex);
		if (!f->has_pending_mask || f->pending_w == 0 || f->pending_h == 0)
			return;
		local_mask.swap(f->pending_mask);
		mw = f->pending_w;
		mh = f->pending_h;
		f->has_pending_mask = false;
		f->has_any_mask = true;
	}

	if (!f->raw_mask_tex || f->raw_mask_w != mw || f->raw_mask_h != mh) {
		if (f->raw_mask_tex)
			gs_texture_destroy(f->raw_mask_tex);
		f->raw_mask_tex = gs_texture_create(mw, mh, GS_R8, 1, nullptr, GS_DYNAMIC);
		f->raw_mask_w = mw;
		f->raw_mask_h = mh;
		f->smooth_initialized = false;
	}
	if (!f->raw_mask_tex)
		return;

	gs_texture_set_image(f->raw_mask_tex, local_mask.data(), mw, false);

	if (!f->smooth_tr[0]) f->smooth_tr[0] = gs_texrender_create(GS_RGBA, GS_ZS_NONE);
	if (!f->smooth_tr[1]) f->smooth_tr[1] = gs_texrender_create(GS_RGBA, GS_ZS_NONE);

	int next_idx = 1 - f->smooth_idx;
	gs_texture_t *prev_tex = f->smooth_initialized
		? gs_texrender_get_texture(f->smooth_tr[f->smooth_idx])
		: f->raw_mask_tex;
	if (!prev_tex)
		prev_tex = f->raw_mask_tex;

	gs_texrender_reset(f->smooth_tr[next_idx]);
	if (gs_texrender_begin(f->smooth_tr[next_idx], mw, mh)) {
		const bool prev_srgb = gs_framebuffer_srgb_enabled();
		gs_enable_framebuffer_srgb(false);
		vec4 zero{};
		vec4_zero(&zero);
		gs_clear(GS_CLEAR_COLOR, &zero, 0.0f, 0);
		gs_ortho(0.0f, float(mw), 0.0f, float(mh), -100.0f, 100.0f);
		gs_blend_state_push();
		gs_blend_function(GS_BLEND_ONE, GS_BLEND_ZERO);

		vec2 tsz{1.0f / float(mw), 1.0f / float(mh)};
		gs_effect_set_texture(f->p_mask_tex, f->raw_mask_tex);
		gs_effect_set_texture(f->p_prev_mask_tex, prev_tex);
		gs_effect_set_vec2(f->p_texel_size, &tsz);
		gs_effect_set_float(f->p_temporal_alpha, f->smooth_initialized ? f->temporal_smooth : 0.0f);
		while (gs_effect_loop(f->effect, "SmoothMask")) {
			gs_draw_sprite(f->raw_mask_tex, 0, mw, mh);
		}

		gs_blend_state_pop();
		gs_enable_framebuffer_srgb(prev_srgb);
		gs_texrender_end(f->smooth_tr[next_idx]);
		f->smooth_idx = next_idx;
		f->smooth_initialized = true;
	}
}

void bg_video_render(void *data, gs_effect_t *)
{
	auto *f = static_cast<BgRemovalFilter *>(data);
	obs_source_t *target = obs_filter_get_target(f->context);
	if (!target || !f->effect) {
		obs_source_skip_video_filter(f->context);
		return;
	}

	uint32_t sw = obs_source_get_base_width(target);
	uint32_t sh = obs_source_get_base_height(target);
	if (sw == 0 || sh == 0) {
		obs_source_skip_video_filter(f->context);
		return;
	}

	if (!obs_source_process_filter_begin(f->context, GS_RGBA, OBS_NO_DIRECT_RENDERING))
		return;

	const bool outer_srgb = gs_framebuffer_srgb_enabled();
	const bool outer_linear = gs_get_linear_srgb();

	if (!f->src_tr) f->src_tr = gs_texrender_create(GS_RGBA, GS_ZS_NONE);
	gs_texrender_reset(f->src_tr);
	if (!gs_texrender_begin(f->src_tr, sw, sh)) {
		obs_source_process_filter_end(f->context, obs_get_base_effect(OBS_EFFECT_DEFAULT), sw, sh);
		return;
	}
	gs_enable_framebuffer_srgb(false);
	gs_set_linear_srgb(false);
	vec4 zero{};
	vec4_zero(&zero);
	gs_clear(GS_CLEAR_COLOR, &zero, 0.0f, 0);
	gs_ortho(0.0f, float(sw), 0.0f, float(sh), -100.0f, 100.0f);
	gs_blend_state_push();
	gs_blend_function(GS_BLEND_ONE, GS_BLEND_ZERO);
	obs_source_process_filter_end(f->context, obs_get_base_effect(OBS_EFFECT_DEFAULT), sw, sh);
	gs_blend_state_pop();
	gs_set_linear_srgb(outer_linear);
	gs_enable_framebuffer_srgb(outer_srgb);
	gs_texrender_end(f->src_tr);

	gs_texture_t *src_tex = gs_texrender_get_texture(f->src_tr);
	if (!src_tex)
		return;

	// Periodically downsample & submit 256x144 RGBA frame to ML Kit background worker
	int64_t now = now_ms_steady();
	int64_t min_interval = std::max<int64_t>(28, 1000 / std::max(10, f->infer_fps));
	if (now - f->last_infer_ms >= min_interval) {
		bool host_ready = false;
		{
			std::lock_guard<std::mutex> lock(g_bg_host_mutex);
			host_ready = (g_bg_jvm && g_bg_host_class && g_mid_bg_submit);
		}
		if (host_ready) {
			f->last_infer_ms = now;
			if (!f->small_tr) f->small_tr = gs_texrender_create(GS_RGBA, GS_ZS_NONE);
			if (!f->stage_surf) f->stage_surf = gs_stagesurface_create(kInferW, kInferH, GS_RGBA);

			gs_texrender_reset(f->small_tr);
			if (gs_texrender_begin(f->small_tr, kInferW, kInferH)) {
				gs_enable_framebuffer_srgb(false);
				gs_clear(GS_CLEAR_COLOR, &zero, 0.0f, 0);
				gs_ortho(0.0f, float(kInferW), 0.0f, float(kInferH), -100.0f, 100.0f);
				gs_blend_state_push();
				gs_blend_function(GS_BLEND_ONE, GS_BLEND_ZERO);
				gs_effect_t *def = obs_get_base_effect(OBS_EFFECT_DEFAULT);
				gs_effect_set_texture(gs_effect_get_param_by_name(def, "image"), src_tex);
				while (gs_effect_loop(def, "Draw")) {
					gs_draw_sprite(src_tex, 0, kInferW, kInferH);
				}
				gs_blend_state_pop();
				gs_enable_framebuffer_srgb(outer_srgb);
				gs_texrender_end(f->small_tr);

				if (gs_texture_t *small_tex = gs_texrender_get_texture(f->small_tr)) {
					gs_stage_texture(f->stage_surf, small_tex);
					uint8_t *mapped = nullptr;
					uint32_t linesize = 0;
					if (gs_stagesurface_map(f->stage_surf, &mapped, &linesize)) {
						const uint32_t row_bytes = uint32_t(kInferW) * 4;
						for (int y = 0; y < kInferH; ++y) {
							std::memcpy(f->readback_rgba.data() + size_t(y) * row_bytes,
								    mapped + size_t(y) * linesize, row_bytes);
						}
						gs_stagesurface_unmap(f->stage_surf);
						dispatch_frame_to_java(f->id, f->readback_rgba.data(), kInferW, kInferH);
					}
				}
			}
		}
	}

	// Upload & temporally smooth newest ML Kit mask if one arrived
	upload_and_smooth_mask_if_ready(f);

	gs_texture_t *mask_tex = f->smooth_initialized
		? gs_texrender_get_texture(f->smooth_tr[f->smooth_idx])
		: nullptr;

	// If no mask has arrived yet (first ~30ms), draw source cleanly unmodified
	if (!mask_tex) {
		gs_enable_framebuffer_srgb(false);
		gs_effect_t *def = obs_get_base_effect(OBS_EFFECT_DEFAULT);
		gs_effect_set_texture(gs_effect_get_param_by_name(def, "image"), src_tex);
		gs_blend_state_push();
		gs_blend_function(GS_BLEND_ONE, GS_BLEND_INVSRCALPHA);
		while (gs_effect_loop(def, "Draw")) {
			gs_draw_sprite(src_tex, 0, sw, sh);
		}
		gs_blend_state_pop();
		gs_enable_framebuffer_srgb(outer_srgb);
		return;
	}

	// If Portrait Bokeh mode, compute 2-pass separable Gaussian blur at half-res (strictly non-sRGB to avoid gamma washout!)
	gs_texture_t *blur_tex = src_tex;
	if (f->bg_mode == 1) {
		if (!f->blur_h_tr) f->blur_h_tr = gs_texrender_create(GS_RGBA, GS_ZS_NONE);
		if (!f->blur_v_tr) f->blur_v_tr = gs_texrender_create(GS_RGBA, GS_ZS_NONE);
		uint32_t bw = std::max(16u, sw / 2);
		uint32_t bh = std::max(16u, sh / 2);
		float spread = 0.8f + f->blur_strength * 3.8f;

		gs_texrender_reset(f->blur_h_tr);
		if (gs_texrender_begin(f->blur_h_tr, bw, bh)) {
			gs_enable_framebuffer_srgb(false);
			gs_clear(GS_CLEAR_COLOR, &zero, 0.0f, 0);
			gs_ortho(0.0f, float(bw), 0.0f, float(bh), -100.0f, 100.0f);
			gs_blend_state_push();
			gs_blend_function(GS_BLEND_ONE, GS_BLEND_ZERO);
			vec2 step_h{spread / float(bw), 0.0f};
			gs_effect_set_texture(f->p_image, src_tex);
			gs_effect_set_vec2(f->p_blur_step, &step_h);
			while (gs_effect_loop(f->effect, "Blur1D")) {
				gs_draw_sprite(src_tex, 0, bw, bh);
			}
			gs_blend_state_pop();
			gs_enable_framebuffer_srgb(outer_srgb);
			gs_texrender_end(f->blur_h_tr);
		}
		if (gs_texture_t *h_tex = gs_texrender_get_texture(f->blur_h_tr)) {
			gs_texrender_reset(f->blur_v_tr);
			if (gs_texrender_begin(f->blur_v_tr, bw, bh)) {
				gs_enable_framebuffer_srgb(false);
				gs_clear(GS_CLEAR_COLOR, &zero, 0.0f, 0);
				gs_ortho(0.0f, float(bw), 0.0f, float(bh), -100.0f, 100.0f);
				gs_blend_state_push();
				gs_blend_function(GS_BLEND_ONE, GS_BLEND_ZERO);
				vec2 step_v{0.0f, spread / float(bh)};
				gs_effect_set_texture(f->p_image, h_tex);
				gs_effect_set_vec2(f->p_blur_step, &step_v);
				while (gs_effect_loop(f->effect, "Blur1D")) {
					gs_draw_sprite(h_tex, 0, bw, bh);
				}
				gs_blend_state_pop();
				gs_enable_framebuffer_srgb(outer_srgb);
				gs_texrender_end(f->blur_v_tr);
			}
			if (gs_texture_t *v_tex = gs_texrender_get_texture(f->blur_v_tr))
				blur_tex = v_tex;
		}
	}

	// Final GPU composite onto OBS target (output is already sRGB-encoded)
	gs_enable_framebuffer_srgb(false);
	gs_effect_set_texture(f->p_image, src_tex);
	gs_effect_set_texture(f->p_mask_tex, mask_tex);
	gs_effect_set_texture(f->p_blur_tex, blur_tex);
	gs_effect_set_int(f->p_bg_mode, f->bg_mode);
	gs_effect_set_float(f->p_threshold, f->threshold);
	gs_effect_set_float(f->p_feather, f->feather);
	gs_effect_set_float(f->p_despill, f->despill);
	gs_effect_set_vec4(f->p_bg_color, &f->bg_color);

	gs_blend_state_push();
	gs_blend_function(GS_BLEND_ONE, GS_BLEND_INVSRCALPHA);
	while (gs_effect_loop(f->effect, "Composite")) {
		gs_draw_sprite(src_tex, 0, sw, sh);
	}
	gs_blend_state_pop();
	gs_enable_framebuffer_srgb(outer_srgb);
}

void push_mask_internal(int64_t instance_id, const uint8_t *data, int w, int h)
{
	if (!data || w <= 0 || h <= 0)
		return;
	std::lock_guard<std::mutex> lock(g_bg_inst_mutex);
	auto it = g_bg_instances.find(instance_id);
	if (it == g_bg_instances.end() || !it->second)
		return;
	BgRemovalFilter *f = it->second;
	std::lock_guard<std::mutex> mlock(f->mask_mutex);
	size_t n = size_t(w) * size_t(h);
	f->pending_mask.resize(n);
	std::memcpy(f->pending_mask.data(), data, n);
	f->pending_w = uint32_t(w);
	f->pending_h = uint32_t(h);
	f->has_pending_mask = true;
}

} // namespace

void android_bg_removal_filter_register()
{
	static obs_source_info info{};
	info.id = "android_bg_removal_filter";
	info.type = OBS_SOURCE_TYPE_FILTER;
	info.output_flags = OBS_SOURCE_VIDEO;
	info.get_name = bg_get_name;
	info.create = bg_create;
	info.destroy = bg_destroy;
	info.get_defaults = bg_get_defaults;
	info.get_properties = bg_get_properties;
	info.update = bg_update;
	info.video_render = bg_video_render;
	obs_register_source(&info);
}

void android_bg_removal_filter_shutdown()
{
	JavaVM *jvm = nullptr;
	jclass cls = nullptr;
	jmethodID mid = nullptr;
	{
		std::lock_guard<std::mutex> lock(g_bg_host_mutex);
		jvm = g_bg_jvm;
		cls = g_bg_host_class;
		mid = g_mid_bg_destroy_all;
	}
	if (!jvm || !cls || !mid)
		return;
	JNIEnv *env = nullptr;
	bool detach = false;
	int st = jvm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
	if (st == JNI_EDETACHED) {
		if (jvm->AttachCurrentThread(&env, nullptr) != 0)
			return;
		detach = true;
	} else if (st != JNI_OK) {
		return;
	}
	env->CallStaticVoidMethod(cls, mid);
	if (env->ExceptionCheck())
		env->ExceptionClear();
	if (detach)
		jvm->DetachCurrentThread();
}

extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_registerBgSegmenterHost(JNIEnv *env, jclass, jclass hostClass)
{
	std::lock_guard<std::mutex> lock(g_bg_host_mutex);
	env->GetJavaVM(&g_bg_jvm);
	if (g_bg_host_class) {
		env->DeleteGlobalRef(g_bg_host_class);
		g_bg_host_class = nullptr;
	}
	if (hostClass) {
		g_bg_host_class = static_cast<jclass>(env->NewGlobalRef(hostClass));
		g_mid_bg_submit = env->GetStaticMethodID(g_bg_host_class, "submitFrame", "(J[BII)V");
		if (env->ExceptionCheck()) { env->ExceptionClear(); g_mid_bg_submit = nullptr; }
		g_mid_bg_destroy = env->GetStaticMethodID(g_bg_host_class, "destroyInstance", "(J)V");
		if (env->ExceptionCheck()) { env->ExceptionClear(); g_mid_bg_destroy = nullptr; }
		g_mid_bg_destroy_all = env->GetStaticMethodID(g_bg_host_class, "destroyAll", "()V");
		if (env->ExceptionCheck()) { env->ExceptionClear(); g_mid_bg_destroy_all = nullptr; }
	}
}

extern "C" JNIEXPORT void JNICALL
Java_org_obsandroid_port_NativeBridge_pushBgMask(JNIEnv *env, jclass, jlong instanceId,
						 jbyteArray maskBytes, jint width, jint height)
{
	if (!maskBytes || width <= 0 || height <= 0)
		return;
	jsize len = env->GetArrayLength(maskBytes);
	if (len < jsize(width * height))
		return;
	jbyte *ptr = env->GetByteArrayElements(maskBytes, nullptr);
	if (!ptr)
		return;
	push_mask_internal(int64_t(instanceId), reinterpret_cast<const uint8_t *>(ptr), int(width), int(height));
	env->ReleaseByteArrayElements(maskBytes, ptr, JNI_ABORT);
}

// Helper for native on-device GPU test harness (pushes synthetic mask to all active filter instances)
extern "C" __attribute__((visibility("default"))) void obs_android_bg_removal_inject_mask_all(
	const uint8_t *mask, int width, int height)
{
	if (!mask || width <= 0 || height <= 0)
		return;
	std::vector<int64_t> ids;
	{
		std::lock_guard<std::mutex> lock(g_bg_inst_mutex);
		for (const auto &kv : g_bg_instances)
			ids.push_back(kv.first);
	}
	for (int64_t id : ids)
		push_mask_internal(id, mask, width, height);
}
