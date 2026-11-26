// SPDX-License-Identifier: GPL-2.0-or-later
#include "android-vmix-color-filter.h"
#include <obs-module.h>
#include <graphics/vec3.h>
#include <algorithm>
#include <cmath>

namespace {

constexpr const char *kVmixColorEffectSrc = R"EFFECT(
uniform float4x4 ViewProj;
uniform texture2d image;

uniform float3 lift_rgb;
uniform float3 gamma_inv;
uniform float3 gain_rgb;
uniform float hue_cos;
uniform float hue_sin;
uniform float saturation;
uniform float temperature;
uniform float tint;
uniform float split_pos;

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

float4 PSVmixColor(VertData v_in) : TARGET
{
	float4 orig = image.Sample(linearSampler, v_in.uv);
	if (split_pos > 0.001) {
		if (abs(v_in.uv.x - split_pos) < 0.002) {
			return float4(1.0, 1.0, 1.0, 1.0);
		}
		if (v_in.uv.x < split_pos) {
			return orig;
		}
	}

	float3 c = orig.rgb;

	// 1. Lift (shadows) & Gain (highlights): black -> lift_rgb, white -> gain_rgb
	c = c * (gain_rgb - lift_rgb) + lift_rgb;
	c = max(c, float3(0.0, 0.0, 0.0));

	// 2. Gamma (midtones)
	c = pow(c, gamma_inv);

	// 3. White balance trim (temperature & tint)
	c.r *= (1.0 + temperature * 0.25);
	c.b *= (1.0 - temperature * 0.25);
	c.g *= (1.0 - tint * 0.20);

	// 4. Hue rotation around luminance axis (Rodrigues formula)
	float3 k = float3(0.57735027, 0.57735027, 0.57735027);
	c = c * hue_cos + cross(k, c) * hue_sin + k * dot(k, c) * (1.0 - hue_cos);

	// 5. Saturation (Rec.709 luma)
	float luma = dot(c, float3(0.2126, 0.7152, 0.0722));
	c = lerp(float3(luma, luma, luma), c, saturation);

	return float4(saturate(c), orig.a);
}

technique Draw
{
	pass
	{
		vertex_shader = VSDefault(v_in);
		pixel_shader  = PSVmixColor(v_in);
	}
}
)EFFECT";

struct VmixColorFilter {
	obs_source_t *context = nullptr;
	gs_effect_t *effect = nullptr;

	gs_eparam_t *p_lift = nullptr;
	gs_eparam_t *p_gamma_inv = nullptr;
	gs_eparam_t *p_gain = nullptr;
	gs_eparam_t *p_hue_cos = nullptr;
	gs_eparam_t *p_hue_sin = nullptr;
	gs_eparam_t *p_saturation = nullptr;
	gs_eparam_t *p_temperature = nullptr;
	gs_eparam_t *p_tint = nullptr;
	gs_eparam_t *p_split = nullptr;

	vec3 lift_rgb{};
	vec3 gamma_inv{1.0f, 1.0f, 1.0f};
	vec3 gain_rgb{1.0f, 1.0f, 1.0f};
	float hue_cos = 1.0f;
	float hue_sin = 0.0f;
	float saturation = 1.0f;
	float temperature = 0.0f;
	float tint = 0.0f;
	float split_pos = 0.0f;
};

const char *vmix_color_get_name(void *)
{
	return "Corrección de color (Ruedas LGG)";
}

void vmix_color_update(void *data, obs_data_t *settings)
{
	auto *f = static_cast<VmixColorFilter *>(data);
	const float lift_y = float(obs_data_get_double(settings, "lift_y"));
	const float lift_r = float(obs_data_get_double(settings, "lift_r"));
	const float lift_g = float(obs_data_get_double(settings, "lift_g"));
	const float lift_b = float(obs_data_get_double(settings, "lift_b"));

	const float gamma_y = float(obs_data_get_double(settings, "gamma_y"));
	const float gamma_r = float(obs_data_get_double(settings, "gamma_r"));
	const float gamma_g = float(obs_data_get_double(settings, "gamma_g"));
	const float gamma_b = float(obs_data_get_double(settings, "gamma_b"));

	const float gain_y = float(obs_data_get_double(settings, "gain_y"));
	const float gain_r = float(obs_data_get_double(settings, "gain_r"));
	const float gain_g = float(obs_data_get_double(settings, "gain_g"));
	const float gain_b = float(obs_data_get_double(settings, "gain_b"));

	f->lift_rgb.x = std::clamp(lift_r + lift_y, -1.0f, 1.0f);
	f->lift_rgb.y = std::clamp(lift_g + lift_y, -1.0f, 1.0f);
	f->lift_rgb.z = std::clamp(lift_b + lift_y, -1.0f, 1.0f);

	auto to_inv_gamma = [](float g) {
		float val = std::clamp(1.0f + g, 0.15f, 4.0f);
		return 1.0f / val;
	};
	f->gamma_inv.x = to_inv_gamma(gamma_r + gamma_y);
	f->gamma_inv.y = to_inv_gamma(gamma_g + gamma_y);
	f->gamma_inv.z = to_inv_gamma(gamma_b + gamma_y);

	const float gy = std::clamp(gain_y, 0.0f, 3.0f);
	f->gain_rgb.x = std::clamp(gain_r * gy, 0.0f, 4.0f);
	f->gain_rgb.y = std::clamp(gain_g * gy, 0.0f, 4.0f);
	f->gain_rgb.z = std::clamp(gain_b * gy, 0.0f, 4.0f);

	const float hue_deg = float(obs_data_get_double(settings, "hue"));
	const float hue_rad = hue_deg * 3.1415926535f / 180.0f;
	f->hue_cos = std::cos(hue_rad);
	f->hue_sin = std::sin(hue_rad);

	f->saturation = std::clamp(1.0f + float(obs_data_get_double(settings, "saturation")), 0.0f, 3.0f);
	f->temperature = std::clamp(float(obs_data_get_double(settings, "temperature")), -1.0f, 1.0f);
	f->tint = std::clamp(float(obs_data_get_double(settings, "tint")), -1.0f, 1.0f);
	f->split_pos = obs_data_get_bool(settings, "split_compare") ? 0.5f : 0.0f;
}

void vmix_color_get_defaults(obs_data_t *settings)
{
	obs_data_set_default_double(settings, "lift_r", 0.0);
	obs_data_set_default_double(settings, "lift_g", 0.0);
	obs_data_set_default_double(settings, "lift_b", 0.0);
	obs_data_set_default_double(settings, "lift_y", 0.0);

	obs_data_set_default_double(settings, "gamma_r", 0.0);
	obs_data_set_default_double(settings, "gamma_g", 0.0);
	obs_data_set_default_double(settings, "gamma_b", 0.0);
	obs_data_set_default_double(settings, "gamma_y", 0.0);

	obs_data_set_default_double(settings, "gain_r", 1.0);
	obs_data_set_default_double(settings, "gain_g", 1.0);
	obs_data_set_default_double(settings, "gain_b", 1.0);
	obs_data_set_default_double(settings, "gain_y", 1.0);

	obs_data_set_default_double(settings, "hue", 0.0);
	obs_data_set_default_double(settings, "saturation", 0.0);
	obs_data_set_default_double(settings, "temperature", 0.0);
	obs_data_set_default_double(settings, "tint", 0.0);
	obs_data_set_default_bool(settings, "split_compare", false);
}

obs_properties_t *vmix_color_get_properties(void *)
{
	obs_properties_t *props = obs_properties_create();
	obs_properties_add_text(
		props, "tip",
		"Tip: Para usar las 3 ruedas de color interactivas (Lift / Gamma / Gain), abre Herramientas → Ruedas de color (LGG).",
		OBS_TEXT_INFO);

	obs_properties_add_float_slider(props, "lift_y", "Lift (Sombras) · Luminancia Y", -0.5, 0.5, 0.01);
	obs_properties_add_float_slider(props, "lift_r", "Lift · Rojo (R)", -0.5, 0.5, 0.01);
	obs_properties_add_float_slider(props, "lift_g", "Lift · Verde (G)", -0.5, 0.5, 0.01);
	obs_properties_add_float_slider(props, "lift_b", "Lift · Azul (B)", -0.5, 0.5, 0.01);

	obs_properties_add_float_slider(props, "gamma_y", "Gamma (Medios tonos) · Luminancia Y", -0.8, 0.8, 0.01);
	obs_properties_add_float_slider(props, "gamma_r", "Gamma · Rojo (R)", -0.8, 0.8, 0.01);
	obs_properties_add_float_slider(props, "gamma_g", "Gamma · Verde (G)", -0.8, 0.8, 0.01);
	obs_properties_add_float_slider(props, "gamma_b", "Gamma · Azul (B)", -0.8, 0.8, 0.01);

	obs_properties_add_float_slider(props, "gain_y", "Gain (Altas luces) · Luminancia Y", 0.0, 2.0, 0.01);
	obs_properties_add_float_slider(props, "gain_r", "Gain · Rojo (R)", 0.0, 2.0, 0.01);
	obs_properties_add_float_slider(props, "gain_g", "Gain · Verde (G)", 0.0, 2.0, 0.01);
	obs_properties_add_float_slider(props, "gain_b", "Gain · Azul (B)", 0.0, 2.0, 0.01);

	obs_properties_add_float_slider(props, "hue", "Matiz (Hue °)", -180.0, 180.0, 1.0);
	obs_properties_add_float_slider(props, "saturation", "Saturación", -1.0, 1.0, 0.01);
	obs_properties_add_float_slider(props, "temperature", "Temperatura (Frío / Cálido)", -1.0, 1.0, 0.01);
	obs_properties_add_float_slider(props, "tint", "Tinte (Verde / Magenta)", -1.0, 1.0, 0.01);
	obs_properties_add_bool(props, "split_compare", "Comparar Antes / Después (mitad de pantalla)");
	return props;
}

void *vmix_color_create(obs_data_t *settings, obs_source_t *context)
{
	auto *f = new VmixColorFilter();
	f->context = context;
	obs_enter_graphics();
	char *error = nullptr;
	f->effect = gs_effect_create(kVmixColorEffectSrc, "android_vmix_color_filter", &error);
	if (error) {
		blog(LOG_ERROR, "android_vmix_color_filter shader error: %s", error);
		bfree(error);
	}
	if (f->effect) {
		f->p_lift = gs_effect_get_param_by_name(f->effect, "lift_rgb");
		f->p_gamma_inv = gs_effect_get_param_by_name(f->effect, "gamma_inv");
		f->p_gain = gs_effect_get_param_by_name(f->effect, "gain_rgb");
		f->p_hue_cos = gs_effect_get_param_by_name(f->effect, "hue_cos");
		f->p_hue_sin = gs_effect_get_param_by_name(f->effect, "hue_sin");
		f->p_saturation = gs_effect_get_param_by_name(f->effect, "saturation");
		f->p_temperature = gs_effect_get_param_by_name(f->effect, "temperature");
		f->p_tint = gs_effect_get_param_by_name(f->effect, "tint");
		f->p_split = gs_effect_get_param_by_name(f->effect, "split_pos");
	}
	obs_leave_graphics();
	vmix_color_update(f, settings);
	return f;
}

void vmix_color_destroy(void *data)
{
	auto *f = static_cast<VmixColorFilter *>(data);
	if (!f)
		return;
	if (f->effect) {
		obs_enter_graphics();
		gs_effect_destroy(f->effect);
		obs_leave_graphics();
	}
	delete f;
}

void vmix_color_render(void *data, gs_effect_t *)
{
	auto *f = static_cast<VmixColorFilter *>(data);
	if (!f || !f->effect) {
		obs_source_skip_video_filter(f ? f->context : nullptr);
		return;
	}
	if (!obs_source_process_filter_begin(f->context, GS_RGBA, OBS_ALLOW_DIRECT_RENDERING))
		return;

	gs_effect_set_vec3(f->p_lift, &f->lift_rgb);
	gs_effect_set_vec3(f->p_gamma_inv, &f->gamma_inv);
	gs_effect_set_vec3(f->p_gain, &f->gain_rgb);
	gs_effect_set_float(f->p_hue_cos, f->hue_cos);
	gs_effect_set_float(f->p_hue_sin, f->hue_sin);
	gs_effect_set_float(f->p_saturation, f->saturation);
	gs_effect_set_float(f->p_temperature, f->temperature);
	gs_effect_set_float(f->p_tint, f->tint);
	gs_effect_set_float(f->p_split, f->split_pos);

	obs_source_process_filter_end(f->context, f->effect, 0, 0);
}

} // namespace

void android_vmix_color_filter_register()
{
	static obs_source_info info{};
	info.id = "android_vmix_color_filter";
	info.type = OBS_SOURCE_TYPE_FILTER;
	info.output_flags = OBS_SOURCE_VIDEO;
	info.get_name = vmix_color_get_name;
	info.create = vmix_color_create;
	info.destroy = vmix_color_destroy;
	info.get_defaults = vmix_color_get_defaults;
	info.get_properties = vmix_color_get_properties;
	info.update = vmix_color_update;
	info.video_render = vmix_color_render;
	obs_register_source(&info);
}
