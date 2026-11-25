// SPDX-License-Identifier: GPL-2.0-or-later
#include "android-frame-style-filter.h"
#include <obs-module.h>
#include <graphics/vec2.h>
#include <graphics/vec4.h>
#include <algorithm>
#include <cmath>
#include <cstdint>

namespace {

constexpr const char *kFrameStyleEffectSrc = R"EFFECT(
uniform float4x4 ViewProj;
uniform texture2d image;

uniform float2 src_size;
uniform float2 out_size;
uniform int shape_mode;       // 0=RoundedRect, 1=Circle, 2=Ellipse, 3=Capsule
uniform float corner_radius;  // px
uniform float border_width;   // px
uniform float4 border_color;
uniform float4 border_color2;
uniform float border_gradient;
uniform float2 grad_dir;
uniform float shadow_enabled;
uniform float shadow_opacity;
uniform float shadow_blur;    // px
uniform float2 shadow_offset; // px
uniform float4 shadow_color;
uniform float feather_px;
uniform float2 blur_step;     // (dx, dy) in UV space for separable blur

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

float sdShape(float2 q, float2 half_sz, int mode, float radius_px)
{
	if (mode == 1) {
		float r = min(half_sz.x, half_sz.y);
		return length(q) - r;
	}
	if (mode == 2) {
		float2 inv_h = float2(1.0 / max(half_sz.x, 1.0), 1.0 / max(half_sz.y, 1.0));
		float2 en = q * inv_h;
		float k0 = length(en);
		float k1 = length(en * inv_h);
		if (k0 < 0.0001) {
			return -min(half_sz.x, half_sz.y);
		}
		return k0 * (k0 - 1.0) / max(k1, 0.0001);
	}
	float r = (mode == 3) ? min(half_sz.x, half_sz.y) : min(radius_px, min(half_sz.x, half_sz.y));
	float2 d = abs(q) - (half_sz - float2(r, r));
	return length(max(d, float2(0.0, 0.0))) + min(max(d.x, d.y), 0.0) - r;
}

float4 PSBlur1D(VertData v_in) : TARGET
{
	// 13-tap Gaussian kernel (7 linear-interpolated taps)
	float4 sum = image.Sample(linearSampler, v_in.uv) * 0.19648255;
	sum += image.Sample(linearSampler, v_in.uv + blur_step * 1.4117647) * 0.29690696;
	sum += image.Sample(linearSampler, v_in.uv - blur_step * 1.4117647) * 0.29690696;
	sum += image.Sample(linearSampler, v_in.uv + blur_step * 3.2941176) * 0.09447040;
	sum += image.Sample(linearSampler, v_in.uv - blur_step * 3.2941176) * 0.09447040;
	sum += image.Sample(linearSampler, v_in.uv + blur_step * 5.1764706) * 0.01038136;
	sum += image.Sample(linearSampler, v_in.uv - blur_step * 5.1764706) * 0.01038136;
	return sum;
}

float4 PSFrameStyle(VertData v_in) : TARGET
{
	float2 half_src = src_size * 0.5;
	float2 p = v_in.uv * out_size - out_size * 0.5;
	float2 src_uv = (p + half_src) / max(src_size, float2(1.0, 1.0));

	float aa = max(0.75, feather_px);
	float dist = sdShape(p, half_src, shape_mode, corner_radius);

	// Outer shape mask
	float outer_cov = 1.0 - smoothstep(-aa, aa, dist);

	// Sample source (clamped; masked by outer_cov)
	float4 src_pm = image.Sample(linearSampler, saturate(src_uv));
	float3 src_rgb = (src_pm.a > 0.001) ? (src_pm.rgb / src_pm.a) : float3(0.0, 0.0, 0.0);
	float src_a = src_pm.a;

	// Compute border color (solid or 2-color gradient)
	float3 b_rgb = border_color.rgb;
	if (border_gradient > 0.5) {
		float2 norm_p = p / max(half_src, float2(1.0, 1.0));
		float g_t = saturate(dot(norm_p, grad_dir) * 0.5 + 0.5);
		b_rgb = lerp(border_color.rgb, border_color2.rgb, g_t);
	}

	float border_mix = 0.0;
	if (border_width > 0.05) {
		border_mix = smoothstep(-border_width - 0.75, -border_width + 0.75, dist);
	}

	// Blend solid border over content inside shape (handles transparent content cleanly)
	float fg_a = lerp(src_a, 1.0, border_mix) * outer_cov;
	float3 fg_pm_rgb = lerp(src_rgb * src_a, b_rgb, border_mix) * outer_cov;

	// Analytic soft drop shadow
	float4 out_pm = float4(fg_pm_rgb, fg_a);
	if (shadow_enabled > 0.5 && shadow_opacity > 0.001) {
		float s_dist = sdShape(p - shadow_offset, half_src, shape_mode, corner_radius);
		float s_blur = max(shadow_blur, 1.0);
		float s_cov = 1.0 - smoothstep(-s_blur * 0.25, s_blur, s_dist);
		s_cov = s_cov * s_cov;
		float sh_a = saturate(shadow_opacity * s_cov);
		float3 sh_pm_rgb = shadow_color.rgb * sh_a;
		// Porter-Duff Over: fg over shadow
		out_pm.rgb = fg_pm_rgb + sh_pm_rgb * (1.0 - fg_a);
		out_pm.a = fg_a + sh_a * (1.0 - fg_a);
	}

	return out_pm;
}

technique Blur1D
{
	pass
	{
		vertex_shader = VSDefault(v_in);
		pixel_shader  = PSBlur1D(v_in);
	}
}

technique DrawFrame
{
	pass
	{
		vertex_shader = VSDefault(v_in);
		pixel_shader  = PSFrameStyle(v_in);
	}
}
)EFFECT";

vec4 obs_color_to_vec4(uint32_t abgr)
{
	vec4 c{};
	c.x = float(abgr & 0xFFu) / 255.0f;
	c.y = float((abgr >> 8) & 0xFFu) / 255.0f;
	c.z = float((abgr >> 16) & 0xFFu) / 255.0f;
	c.w = 1.0f;
	return c;
}

struct FrameStyleFilter {
	obs_source_t *context{nullptr};
	gs_effect_t *effect{nullptr};

	gs_eparam_t *p_image{nullptr};
	gs_eparam_t *p_src_size{nullptr};
	gs_eparam_t *p_out_size{nullptr};
	gs_eparam_t *p_shape_mode{nullptr};
	gs_eparam_t *p_corner_radius{nullptr};
	gs_eparam_t *p_border_width{nullptr};
	gs_eparam_t *p_border_color{nullptr};
	gs_eparam_t *p_border_color2{nullptr};
	gs_eparam_t *p_border_gradient{nullptr};
	gs_eparam_t *p_grad_dir{nullptr};
	gs_eparam_t *p_shadow_enabled{nullptr};
	gs_eparam_t *p_shadow_opacity{nullptr};
	gs_eparam_t *p_shadow_blur{nullptr};
	gs_eparam_t *p_shadow_offset{nullptr};
	gs_eparam_t *p_shadow_color{nullptr};
	gs_eparam_t *p_feather_px{nullptr};
	gs_eparam_t *p_blur_step{nullptr};

	gs_texrender_t *src_tr{nullptr};
	gs_texrender_t *blur_h_tr{nullptr};
	gs_texrender_t *blur_v_tr{nullptr};

	int shape_mode{0};
	float corner_pct{14.0f};
	float border_width{6.0f};
	vec4 border_color{0.23f, 0.51f, 0.96f, 1.0f};
	bool border_gradient{false};
	vec4 border_color2{0.93f, 0.28f, 0.60f, 1.0f};
	float border_angle_deg{45.0f};

	bool shadow_enabled{true};
	float shadow_opacity{0.55f};
	float shadow_blur{18.0f};
	float shadow_offset_x{0.0f};
	float shadow_offset_y{10.0f};
	vec4 shadow_color{0.0f, 0.0f, 0.0f, 1.0f};

	float source_blur_pct{0.0f};
	float feather_px{1.0f};
	int padding_px{24};

	uint32_t last_src_w{0};
	uint32_t last_src_h{0};
};

int effective_padding(const FrameStyleFilter *f)
{
	if (!f->shadow_enabled && f->padding_px <= 0)
		return 0;
	return std::clamp(f->padding_px, 0, 96);
}

const char *frame_get_name(void *)
{
	return "Marco, Esquinas, Sombra y Desenfoque (GPU)";
}

void frame_get_defaults(obs_data_t *s)
{
	obs_data_set_default_int(s, "shape", 0);
	obs_data_set_default_int(s, "corner_radius", 14);
	obs_data_set_default_int(s, "border_width", 6);
	obs_data_set_default_int(s, "border_color", 0xFFF6823B); // #3B82F6 blue
	obs_data_set_default_bool(s, "border_gradient", false);
	obs_data_set_default_int(s, "border_color2", 0xFF9948EC); // #EC4899 pink
	obs_data_set_default_int(s, "border_angle", 45);

	obs_data_set_default_bool(s, "shadow_enabled", true);
	obs_data_set_default_int(s, "shadow_opacity", 55);
	obs_data_set_default_int(s, "shadow_blur", 18);
	obs_data_set_default_int(s, "shadow_offset_x", 0);
	obs_data_set_default_int(s, "shadow_offset_y", 10);
	obs_data_set_default_int(s, "shadow_color", 0xFF000000);

	obs_data_set_default_int(s, "source_blur", 0);
	obs_data_set_default_int(s, "feather", 1);
	obs_data_set_default_int(s, "padding", 24);
}

bool on_frame_props_modified(obs_properties_t *props, obs_property_t *, obs_data_t *s)
{
	int shape = int(obs_data_get_int(s, "shape"));
	bool grad = obs_data_get_bool(s, "border_gradient");
	bool shadow = obs_data_get_bool(s, "shadow_enabled");

	obs_property_set_visible(obs_properties_get(props, "corner_radius"), shape == 0);
	obs_property_set_visible(obs_properties_get(props, "border_color2"), grad);
	obs_property_set_visible(obs_properties_get(props, "border_angle"), grad);

	obs_property_set_visible(obs_properties_get(props, "shadow_opacity"), shadow);
	obs_property_set_visible(obs_properties_get(props, "shadow_blur"), shadow);
	obs_property_set_visible(obs_properties_get(props, "shadow_offset_x"), shadow);
	obs_property_set_visible(obs_properties_get(props, "shadow_offset_y"), shadow);
	obs_property_set_visible(obs_properties_get(props, "shadow_color"), shadow);
	return true;
}

obs_properties_t *frame_get_properties(void *)
{
	obs_properties_t *props = obs_properties_create();

	obs_property_t *sh = obs_properties_add_list(
		props, "shape", "Forma del marco", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
	obs_property_list_add_int(sh, "Rectángulo redondeado", 0);
	obs_property_list_add_int(sh, "Círculo perfecto (1:1)", 1);
	obs_property_list_add_int(sh, "Óvalo / Elipse", 2);
	obs_property_list_add_int(sh, "Cápsula (Pill)", 3);
	obs_property_set_modified_callback(sh, on_frame_props_modified);

	obs_properties_add_int_slider(props, "corner_radius", "Redondeo de esquinas (%)", 0, 50, 1);
	obs_properties_add_int_slider(props, "border_width", "Grosor del borde (px)", 0, 48, 1);
	obs_properties_add_color(props, "border_color", "Color del borde");

	obs_property_t *gr = obs_properties_add_bool(props, "border_gradient", "Borde degradado (2 colores)");
	obs_property_set_modified_callback(gr, on_frame_props_modified);
	obs_properties_add_color(props, "border_color2", "Segundo color del borde");
	obs_properties_add_int_slider(props, "border_angle", "Ángulo del degradado (°)", 0, 360, 5);

	obs_property_t *sw = obs_properties_add_bool(props, "shadow_enabled", "Sombra paralela (Drop Shadow)");
	obs_property_set_modified_callback(sw, on_frame_props_modified);
	obs_properties_add_int_slider(props, "shadow_opacity", "Opacidad de sombra (%)", 0, 100, 1);
	obs_properties_add_int_slider(props, "shadow_blur", "Suavizado de sombra (px)", 1, 48, 1);
	obs_properties_add_int_slider(props, "shadow_offset_x", "Sombra desplazamiento X (px)", -48, 48, 1);
	obs_properties_add_int_slider(props, "shadow_offset_y", "Sombra desplazamiento Y (px)", -48, 48, 1);
	obs_properties_add_color(props, "shadow_color", "Color de sombra");

	obs_properties_add_int_slider(props, "source_blur", "Desenfoque de la fuente (%)", 0, 100, 1);
	obs_properties_add_int_slider(props, "feather", "Suavizado de bordes (px)", 0, 24, 1);
	obs_properties_add_int_slider(props, "padding", "Margen exterior para sombra (px)", 0, 80, 2);

	return props;
}

void frame_update(void *data, obs_data_t *s)
{
	auto *f = static_cast<FrameStyleFilter *>(data);
	f->shape_mode = std::clamp(int(obs_data_get_int(s, "shape")), 0, 3);
	f->corner_pct = std::clamp(float(obs_data_get_int(s, "corner_radius")), 0.0f, 50.0f);
	f->border_width = std::clamp(float(obs_data_get_int(s, "border_width")), 0.0f, 48.0f);
	f->border_color = obs_color_to_vec4(uint32_t(obs_data_get_int(s, "border_color")));
	f->border_gradient = obs_data_get_bool(s, "border_gradient");
	f->border_color2 = obs_color_to_vec4(uint32_t(obs_data_get_int(s, "border_color2")));
	f->border_angle_deg = float(obs_data_get_int(s, "border_angle"));

	f->shadow_enabled = obs_data_get_bool(s, "shadow_enabled");
	f->shadow_opacity = std::clamp(float(obs_data_get_int(s, "shadow_opacity")) / 100.0f, 0.0f, 1.0f);
	f->shadow_blur = std::clamp(float(obs_data_get_int(s, "shadow_blur")), 1.0f, 48.0f);
	f->shadow_offset_x = float(obs_data_get_int(s, "shadow_offset_x"));
	f->shadow_offset_y = float(obs_data_get_int(s, "shadow_offset_y"));
	f->shadow_color = obs_color_to_vec4(uint32_t(obs_data_get_int(s, "shadow_color")));

	f->source_blur_pct = std::clamp(float(obs_data_get_int(s, "source_blur")), 0.0f, 100.0f);
	f->feather_px = std::clamp(float(obs_data_get_int(s, "feather")), 0.0f, 24.0f);
	f->padding_px = std::clamp(int(obs_data_get_int(s, "padding")), 0, 80);
}

void *frame_create(obs_data_t *settings, obs_source_t *context)
{
	auto *f = new FrameStyleFilter();
	f->context = context;

	obs_enter_graphics();
	char *err = nullptr;
	f->effect = gs_effect_create(kFrameStyleEffectSrc, "android_frame_style.effect", &err);
	if (err) {
		blog(LOG_ERROR, "[FrameStyleFilter] effect error: %s", err);
		bfree(err);
	}
	if (f->effect) {
		f->p_image = gs_effect_get_param_by_name(f->effect, "image");
		f->p_src_size = gs_effect_get_param_by_name(f->effect, "src_size");
		f->p_out_size = gs_effect_get_param_by_name(f->effect, "out_size");
		f->p_shape_mode = gs_effect_get_param_by_name(f->effect, "shape_mode");
		f->p_corner_radius = gs_effect_get_param_by_name(f->effect, "corner_radius");
		f->p_border_width = gs_effect_get_param_by_name(f->effect, "border_width");
		f->p_border_color = gs_effect_get_param_by_name(f->effect, "border_color");
		f->p_border_color2 = gs_effect_get_param_by_name(f->effect, "border_color2");
		f->p_border_gradient = gs_effect_get_param_by_name(f->effect, "border_gradient");
		f->p_grad_dir = gs_effect_get_param_by_name(f->effect, "grad_dir");
		f->p_shadow_enabled = gs_effect_get_param_by_name(f->effect, "shadow_enabled");
		f->p_shadow_opacity = gs_effect_get_param_by_name(f->effect, "shadow_opacity");
		f->p_shadow_blur = gs_effect_get_param_by_name(f->effect, "shadow_blur");
		f->p_shadow_offset = gs_effect_get_param_by_name(f->effect, "shadow_offset");
		f->p_shadow_color = gs_effect_get_param_by_name(f->effect, "shadow_color");
		f->p_feather_px = gs_effect_get_param_by_name(f->effect, "feather_px");
		f->p_blur_step = gs_effect_get_param_by_name(f->effect, "blur_step");
	}
	obs_leave_graphics();

	if (!f->effect) {
		delete f;
		return nullptr;
	}

	frame_update(f, settings);
	return f;
}

void frame_destroy(void *data)
{
	auto *f = static_cast<FrameStyleFilter *>(data);
	if (!f)
		return;
	obs_enter_graphics();
	if (f->src_tr) gs_texrender_destroy(f->src_tr);
	if (f->blur_h_tr) gs_texrender_destroy(f->blur_h_tr);
	if (f->blur_v_tr) gs_texrender_destroy(f->blur_v_tr);
	if (f->effect) gs_effect_destroy(f->effect);
	obs_leave_graphics();
	delete f;
}

uint32_t frame_get_width(void *data)
{
	auto *f = static_cast<FrameStyleFilter *>(data);
	obs_source_t *target = obs_filter_get_target(f->context);
	uint32_t w = target ? obs_source_get_base_width(target) : f->last_src_w;
	if (w == 0) return 0;
	return w + uint32_t(effective_padding(f) * 2);
}

uint32_t frame_get_height(void *data)
{
	auto *f = static_cast<FrameStyleFilter *>(data);
	obs_source_t *target = obs_filter_get_target(f->context);
	uint32_t h = target ? obs_source_get_base_height(target) : f->last_src_h;
	if (h == 0) return 0;
	return h + uint32_t(effective_padding(f) * 2);
}

void set_frame_uniforms(FrameStyleFilter *f, uint32_t sw, uint32_t sh, uint32_t ow, uint32_t oh)
{
	vec2 src_sz{float(sw), float(sh)};
	vec2 out_sz{float(ow), float(oh)};
	float min_dim = float(std::min(sw, sh));
	float corner_px = (f->corner_pct / 100.0f) * min_dim;
	constexpr float kDegToRad = 3.14159265358979323846f / 180.0f;
	float rad = f->border_angle_deg * kDegToRad;
	vec2 gdir{std::cos(rad), std::sin(rad)};
	vec2 sh_off{f->shadow_offset_x, f->shadow_offset_y};

	gs_effect_set_vec2(f->p_src_size, &src_sz);
	gs_effect_set_vec2(f->p_out_size, &out_sz);
	gs_effect_set_int(f->p_shape_mode, f->shape_mode);
	gs_effect_set_float(f->p_corner_radius, corner_px);
	gs_effect_set_float(f->p_border_width, f->border_width);
	gs_effect_set_vec4(f->p_border_color, &f->border_color);
	gs_effect_set_vec4(f->p_border_color2, &f->border_color2);
	gs_effect_set_float(f->p_border_gradient, f->border_gradient ? 1.0f : 0.0f);
	gs_effect_set_vec2(f->p_grad_dir, &gdir);
	gs_effect_set_float(f->p_shadow_enabled, f->shadow_enabled ? 1.0f : 0.0f);
	gs_effect_set_float(f->p_shadow_opacity, f->shadow_opacity);
	gs_effect_set_float(f->p_shadow_blur, f->shadow_blur);
	gs_effect_set_vec2(f->p_shadow_offset, &sh_off);
	gs_effect_set_vec4(f->p_shadow_color, &f->shadow_color);
	gs_effect_set_float(f->p_feather_px, f->feather_px);
}

void frame_video_render(void *data, gs_effect_t *)
{
	auto *f = static_cast<FrameStyleFilter *>(data);
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
	f->last_src_w = sw;
	f->last_src_h = sh;

	int pad = effective_padding(f);
	uint32_t ow = sw + uint32_t(pad * 2);
	uint32_t oh = sh + uint32_t(pad * 2);

	if (!obs_source_process_filter_begin(f->context, GS_RGBA, OBS_NO_DIRECT_RENDERING))
		return;

	const bool outer_srgb = gs_framebuffer_srgb_enabled();
	const bool outer_linear = gs_get_linear_srgb();

	// Fast 1-pass path when source_blur is 0
	if (f->source_blur_pct < 0.5f) {
		gs_enable_framebuffer_srgb(false);
		set_frame_uniforms(f, sw, sh, ow, oh);
		gs_blend_state_push();
		gs_blend_function(GS_BLEND_ONE, GS_BLEND_INVSRCALPHA);
		obs_source_process_filter_tech_end(f->context, f->effect, ow, oh, "DrawFrame");
		gs_blend_state_pop();
		gs_enable_framebuffer_srgb(outer_srgb);
		return;
	}

	// Multi-pass path: capture upstream -> 2-pass separable Gaussian blur -> SDF Frame/Shadow
	if (!f->src_tr) f->src_tr = gs_texrender_create(GS_RGBA, GS_ZS_NONE);
	if (!f->blur_h_tr) f->blur_h_tr = gs_texrender_create(GS_RGBA, GS_ZS_NONE);
	if (!f->blur_v_tr) f->blur_v_tr = gs_texrender_create(GS_RGBA, GS_ZS_NONE);

	// Copy upstream filter texture into f->src_tr (non-sRGB to prevent multi-pass gamma washout)
	gs_texrender_reset(f->src_tr);
	if (!gs_texrender_begin(f->src_tr, sw, sh)) {
		obs_source_process_filter_end(f->context, obs_get_base_effect(OBS_EFFECT_DEFAULT), ow, oh);
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

	// Downsample blur buffers by 2x for large blurs -> super smooth & 4x faster on mobile GPU
	uint32_t bw = std::max(16u, sw / 2);
	uint32_t bh = std::max(16u, sh / 2);
	float spread = 0.5f + (f->source_blur_pct / 100.0f) * 3.5f;

	// Horizontal blur pass
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

	gs_texture_t *h_tex = gs_texrender_get_texture(f->blur_h_tr);
	gs_texture_t *final_src = src_tex;
	if (h_tex) {
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
			final_src = v_tex;
	}

	// Final SDF Frame + Border + Shadow pass directly to current OBS render target
	gs_enable_framebuffer_srgb(false);
	set_frame_uniforms(f, sw, sh, ow, oh);
	gs_effect_set_texture(f->p_image, final_src);
	gs_blend_state_push();
	gs_blend_function(GS_BLEND_ONE, GS_BLEND_INVSRCALPHA);
	while (gs_effect_loop(f->effect, "DrawFrame")) {
		gs_draw_sprite(final_src, 0, ow, oh);
	}
	gs_blend_state_pop();
	gs_enable_framebuffer_srgb(outer_srgb);
}

} // namespace

void android_frame_style_filter_register()
{
	static obs_source_info info{};
	info.id = "android_frame_style_filter";
	info.type = OBS_SOURCE_TYPE_FILTER;
	info.output_flags = OBS_SOURCE_VIDEO;
	info.get_name = frame_get_name;
	info.create = frame_create;
	info.destroy = frame_destroy;
	info.get_defaults = frame_get_defaults;
	info.get_properties = frame_get_properties;
	info.update = frame_update;
	info.get_width = frame_get_width;
	info.get_height = frame_get_height;
	info.video_render = frame_video_render;
	obs_register_source(&info);
}
