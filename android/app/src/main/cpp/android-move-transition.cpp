// SPDX-License-Identifier: GPL-2.0-or-later
#include "android-move-transition.h"
#include "move-motion.h"
#include <obs-module.h>
#include <obs.hpp>
#include <graphics/matrix4.h>
#include <graphics/vec2.h>
#include <graphics/vec4.h>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <string>
#include <vector>

namespace {

constexpr const char *kMoveEffectSrc = R"EFFECT(
uniform float4x4 ViewProj;
uniform texture2d image;
uniform float alpha_val;
uniform float2 uv_min;
uniform float2 uv_max;

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

float4 PSDrawAlphaCrop(VertData v_in) : TARGET
{
	float2 uv = lerp(uv_min, uv_max, v_in.uv);
	float4 c = image.Sample(linearSampler, uv);
	return c * alpha_val;
}

technique DrawAlphaCrop
{
	pass
	{
		vertex_shader = VSDefault(v_in);
		pixel_shader  = PSDrawAlphaCrop(v_in);
	}
}
)EFFECT";

constexpr const char *kCrossfadeEffectSrc = R"EFFECT(
uniform float4x4 ViewProj;
uniform texture2d tex_a;
uniform texture2d tex_b;
uniform float fade_val;

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

float4 PSCrossfade(VertData v_in) : TARGET
{
	float4 a = tex_a.Sample(linearSampler, v_in.uv);
	float4 b = tex_b.Sample(linearSampler, v_in.uv);
	return lerp(a, b, fade_val);
}

technique Crossfade
{
	pass
	{
		vertex_shader = VSDefault(v_in);
		pixel_shader  = PSCrossfade(v_in);
	}
}
)EFFECT";

struct MoveItemSnapshot {
	OBSSource source;
	std::string name;
	float src_w{0.0f};
	float src_h{0.0f};
	float crop_l{0.0f};
	float crop_r{0.0f};
	float crop_t{0.0f};
	float crop_b{0.0f};
	float eff_w{0.0f};
	float eff_h{0.0f};
	float center_x{0.0f};
	float center_y{0.0f};
	float scale_x{1.0f};
	float scale_y{1.0f};
	float shear{0.0f};
	float rot_deg{0.0f};
	float z_index{0.0f};
	bool normal_blend{true};
};

struct RenderOp {
	obs_source_t *source{nullptr};
	float src_w{0.0f};
	float src_h{0.0f};
	float crop_l{0.0f};
	float crop_r{0.0f};
	float crop_t{0.0f};
	float crop_b{0.0f};
	float center_x{0.0f};
	float center_y{0.0f};
	float scale_x{1.0f};
	float scale_y{1.0f};
	float shear{0.0f};
	float rot_deg{0.0f};
	float alpha{1.0f};
	float z_order{0.0f};
};

struct MoveTransitionData {
	obs_source_t *source{nullptr};
	gs_effect_t *item_effect{nullptr};
	gs_eparam_t *p_image{nullptr};
	gs_eparam_t *p_alpha{nullptr};
	gs_eparam_t *p_uv_min{nullptr};
	gs_eparam_t *p_uv_max{nullptr};

	gs_effect_t *fade_effect{nullptr};
	gs_eparam_t *p_tex_a{nullptr};
	gs_eparam_t *p_tex_b{nullptr};
	gs_eparam_t *p_fade{nullptr};

	gs_texrender_t *scratch_tr{nullptr};

	int easing_mode{0};      // 0=EaseInOutCubic, 1=EaseOutCubic, 2=BackOut, 3=QuintInOut, 4=Linear
	bool match_by_name{true};
	int unmatched_style{1};  // 0=Fade, 1=Zoom+Fade, 2=SlideUp+Fade, 3=SlideSide+Fade
	float arc_amount{0.0f};  // -1.0 .. +1.0
	bool nested_items{true};
};

float eval_easing(int mode, float t)
{
	return move_motion::easing(mode, t);
}

float shortest_angle_delta(float from_deg, float to_deg)
{
	float d = std::fmod(to_deg - from_deg, 360.0f);
	if (d > 180.0f) d -= 360.0f;
	if (d < -180.0f) d += 360.0f;
	return d;
}

struct CollectContext {
	std::vector<MoveItemSnapshot> *items;
	matrix4 parent;
	bool nested;
	unsigned depth{0};
};

bool collect_scene_item_cb(obs_scene_t *, obs_sceneitem_t *item, void *param)
{
	auto &ctx = *static_cast<CollectContext *>(param);
	auto *list = ctx.items;
	if (!item || !obs_sceneitem_visible(item))
		return true;

	obs_source_t *src = obs_sceneitem_get_source(item);
	if (!src)
		return true;

	uint32_t sw = obs_source_get_width(src);
	uint32_t sh = obs_source_get_height(src);
	if (sw == 0 || sh == 0)
		return true;

	obs_sceneitem_crop crop{};
	obs_sceneitem_get_render_crop(item, &crop);

	matrix4 local{}, mat{};
	obs_sceneitem_get_draw_transform(item, &local);
	matrix4_mul(&mat, &local, &ctx.parent);
	obs_scene_t *nested_scene = obs_sceneitem_is_group(item) ? obs_sceneitem_group_get_scene(item)
							      : obs_scene_from_source(src);
	// Preserve container clipping, filters and unusual blending as a single image.
	if (ctx.nested && nested_scene && ctx.depth < 16 && obs_source_filter_count(src) == 0 &&
	    crop.left == 0 && crop.right == 0 && crop.top == 0 && crop.bottom == 0 &&
	    obs_sceneitem_get_blending_mode(item) == OBS_BLEND_NORMAL &&
	    obs_sceneitem_get_blending_method(item) == OBS_BLEND_METHOD_DEFAULT) {
		CollectContext child{list, mat, true, ctx.depth + 1};
		obs_scene_enum_items(nested_scene, collect_scene_item_cb, &child);
		return true;
	}

	float cl = float(crop.left);
	float cr = float(crop.right);
	float ct = float(crop.top);
	float cb = float(crop.bottom);
	float eff_w = std::max(2.0f, float(sw) - cl - cr);
	float eff_h = std::max(2.0f, float(sh) - ct - cb);

	constexpr float kDegToRad = 3.14159265358979323846f / 180.0f;
	float rad = std::atan2(mat.x.y, mat.x.x);
	float rot_deg = rad / kDegToRad;
	float c = std::cos(rad);
	float s = std::sin(rad);

	// QR decomposition preserves shear introduced by rotated children of
	// non-uniformly scaled containers, as well as mirrored transforms.
	const auto scale = move_motion::decompose({mat.x.x, mat.x.y, mat.y.x, mat.y.y}, c, s);

	// Local center of cropped source is (eff_w * 0.5, eff_h * 0.5)
	float hx = eff_w * 0.5f;
	float hy = eff_h * 0.5f;
	float cx = mat.t.x + mat.x.x * hx + mat.y.x * hy;
	float cy = mat.t.y + mat.x.y * hx + mat.y.y * hy;

	MoveItemSnapshot snap;
	snap.source = src;
	const char *nm = obs_source_get_name(src);
	snap.name = nm ? nm : "";
	snap.src_w = float(sw);
	snap.src_h = float(sh);
	snap.crop_l = cl;
	snap.crop_r = cr;
	snap.crop_t = ct;
	snap.crop_b = cb;
	snap.eff_w = eff_w;
	snap.eff_h = eff_h;
	snap.center_x = cx;
	snap.center_y = cy;
	snap.scale_x = scale.x;
	snap.scale_y = scale.y;
	snap.shear = scale.shear;
	snap.rot_deg = rot_deg;
	snap.z_index = float(list->size());
	snap.normal_blend = obs_sceneitem_get_blending_mode(item) == OBS_BLEND_NORMAL &&
			    obs_sceneitem_get_blending_method(item) == OBS_BLEND_METHOD_DEFAULT;
	list->push_back(std::move(snap));
	return true;
}

std::vector<MoveItemSnapshot> collect_scene(obs_scene_t *scene, bool nested)
{
	std::vector<MoveItemSnapshot> items;
	if (!scene)
		return items;
	items.reserve(16);
	CollectContext ctx{&items, {}, nested};
	matrix4_identity(&ctx.parent);
	obs_scene_enum_items(scene, collect_scene_item_cb, &ctx);
	return items;
}

std::vector<int> match_items(const std::vector<MoveItemSnapshot> &a,
			     const std::vector<MoveItemSnapshot> &b, bool by_name)
{
	std::vector<int> matches(a.size(), -1);
	std::vector<bool> used(b.size(), false);
	for (int pass = 0; pass < (by_name ? 2 : 1); ++pass) {
		for (size_t i = 0; i < a.size(); ++i) {
			if (matches[i] >= 0) continue;
			for (size_t j = 0; j < b.size(); ++j) {
				bool same = pass == 0 ? a[i].source == b[j].source
						     : !a[i].name.empty() && a[i].name == b[j].name;
				if (!used[j] && same) {
					matches[i] = int(j);
					used[j] = true;
					break;
				}
			}
		}
	}
	return matches;
}

// Used by the properties dialog too: diagnostics use exactly the renderer's matching rules.
void describe_matches(void *data, calldata_t *cd)
{
	auto *tr = static_cast<MoveTransitionData *>(data);
	auto *a = static_cast<obs_source_t *>(calldata_ptr(cd, "scene_a"));
	auto *b = static_cast<obs_source_t *>(calldata_ptr(cd, "scene_b"));
	auto ia = collect_scene(obs_scene_from_source(a), tr->nested_items);
	auto ib = collect_scene(obs_scene_from_source(b), tr->nested_items);
	auto matches = match_items(ia, ib, tr->match_by_name);
	std::vector<bool> used(ib.size(), false);
	std::string shared, outgoing, incoming;
	auto append = [](std::string &text, const std::string &name) {
		if (!text.empty()) text += ", ";
		text += name;
	};
	for (size_t i = 0; i < ia.size(); ++i) {
		if (matches[i] >= 0) {
			used[size_t(matches[i])] = true;
			append(shared, ia[i].name);
		} else append(outgoing, ia[i].name);
	}
	for (size_t j = 0; j < ib.size(); ++j)
		if (!used[j]) append(incoming, ib[j].name);
	calldata_set_string(cd, "shared", shared.c_str());
	calldata_set_string(cd, "outgoing", outgoing.c_str());
	calldata_set_string(cd, "incoming", incoming.c_str());
}

void draw_render_op(MoveTransitionData *tr, const RenderOp &op)
{
	if (!op.source || op.alpha <= 0.002f)
		return;
	uint32_t sw = uint32_t(std::max(1.0f, std::round(op.src_w)));
	uint32_t sh = uint32_t(std::max(1.0f, std::round(op.src_h)));
	float eff_w = std::max(2.0f, op.src_w - op.crop_l - op.crop_r);
	float eff_h = std::max(2.0f, op.src_h - op.crop_t - op.crop_b);

	bool has_crop = (op.crop_l > 0.5f || op.crop_r > 0.5f || op.crop_t > 0.5f || op.crop_b > 0.5f);
	bool needs_offscreen = has_crop || (op.alpha < 0.998f);

	constexpr float kDegToRad = 3.14159265358979323846f / 180.0f;
	matrix4 mat{};
	matrix4_identity(&mat);
	const float radians = op.rot_deg * kDegToRad;
	const auto basis = move_motion::compose({op.scale_x, op.scale_y, op.shear}, std::cos(radians), std::sin(radians));
	mat.x.x = basis.xx;
	mat.x.y = basis.xy;
	mat.y.x = basis.yx;
	mat.y.y = basis.yy;
	mat.t.x = op.center_x - mat.x.x * eff_w * 0.5f - mat.y.x * eff_h * 0.5f;
	mat.t.y = op.center_y - mat.x.y * eff_w * 0.5f - mat.y.y * eff_h * 0.5f;

	if (!needs_offscreen) {
		gs_matrix_push();
		gs_matrix_mul(&mat);
		gs_blend_state_push();
		gs_blend_function(GS_BLEND_ONE, GS_BLEND_INVSRCALPHA);
		obs_source_video_render(op.source);
		gs_blend_state_pop();
		gs_matrix_pop();
		return;
	}

	if (!tr->scratch_tr) {
		tr->scratch_tr = gs_texrender_create(GS_RGBA, GS_ZS_NONE);
	}
	if (!tr->scratch_tr || !tr->item_effect)
		return;

	gs_texrender_reset(tr->scratch_tr);
	if (!gs_texrender_begin(tr->scratch_tr, sw, sh))
		return;

	vec4 clear_color{};
	vec4_zero(&clear_color);
	gs_clear(GS_CLEAR_COLOR, &clear_color, 0.0f, 0);
	gs_ortho(0.0f, float(sw), 0.0f, float(sh), -100.0f, 100.0f);

	gs_blend_state_push();
	gs_blend_function(GS_BLEND_ONE, GS_BLEND_INVSRCALPHA);
	obs_source_video_render(op.source);
	gs_blend_state_pop();

	gs_texrender_end(tr->scratch_tr);

	gs_texture_t *tex = gs_texrender_get_texture(tr->scratch_tr);
	if (!tex)
		return;

	vec2 uv_min{std::clamp(op.crop_l / op.src_w, 0.0f, 0.99f),
		    std::clamp(op.crop_t / op.src_h, 0.0f, 0.99f)};
	vec2 uv_max{std::clamp(1.0f - (op.crop_r / op.src_w), uv_min.x + 0.005f, 1.0f),
		    std::clamp(1.0f - (op.crop_b / op.src_h), uv_min.y + 0.005f, 1.0f)};

	gs_matrix_push();
	gs_matrix_mul(&mat);

	const bool prev_srgb = gs_framebuffer_srgb_enabled();
	gs_enable_framebuffer_srgb(true);
	gs_blend_state_push();
	gs_blend_function(GS_BLEND_ONE, GS_BLEND_INVSRCALPHA);

	gs_effect_set_texture_srgb(tr->p_image, tex);
	gs_effect_set_float(tr->p_alpha, std::clamp(op.alpha, 0.0f, 1.0f));
	gs_effect_set_vec2(tr->p_uv_min, &uv_min);
	gs_effect_set_vec2(tr->p_uv_max, &uv_max);

	while (gs_effect_loop(tr->item_effect, "DrawAlphaCrop")) {
		gs_draw_sprite(tex, 0, uint32_t(std::max(1.0f, std::round(eff_w))),
			       uint32_t(std::max(1.0f, std::round(eff_h))));
	}

	gs_blend_state_pop();
	gs_enable_framebuffer_srgb(prev_srgb);
	gs_matrix_pop();
}

void move_render_callback(void *data, gs_texture_t *a, gs_texture_t *b, float t, uint32_t cx, uint32_t cy)
{
	auto *tr = static_cast<MoveTransitionData *>(data);
	if (!tr)
		return;

	float e = eval_easing(tr->easing_mode, t);
	// Geometry can overshoot for Back Out; opacity and layer ordering cannot.
	float e_alpha = std::clamp(e, 0.0f, 1.0f);

	obs_source_t *src_a = obs_transition_get_source(tr->source, OBS_TRANSITION_SOURCE_A);
	obs_source_t *src_b = obs_transition_get_source(tr->source, OBS_TRANSITION_SOURCE_B);
	obs_scene_t *scene_a = src_a ? obs_scene_from_source(src_a) : nullptr;
	obs_scene_t *scene_b = src_b ? obs_scene_from_source(src_b) : nullptr;
	auto items_a = collect_scene(scene_a, tr->nested_items);
	auto items_b = collect_scene(scene_b, tr->nested_items);
	auto special_blend = [](const MoveItemSnapshot &item) { return !item.normal_blend; };

	if (!scene_a || !scene_b || obs_source_filter_count(src_a) || obs_source_filter_count(src_b) ||
	    std::any_of(items_a.begin(), items_a.end(), special_blend) ||
	    std::any_of(items_b.begin(), items_b.end(), special_blend) ||
	    obs_source_get_width(src_a) != cx || obs_source_get_height(src_a) != cy ||
	    obs_source_get_width(src_b) != cx || obs_source_get_height(src_b) != cy ||
	    !tr->item_effect) {
		// Fallback to smooth eased crossfade when transitioning non-scene sources
		const bool previous_srgb = gs_framebuffer_srgb_enabled();
		gs_enable_framebuffer_srgb(true);
		if (tr->fade_effect && a && b) {
			gs_effect_set_texture_srgb(tr->p_tex_a, a);
			gs_effect_set_texture_srgb(tr->p_tex_b, b);
			gs_effect_set_float(tr->p_fade, e_alpha);
			while (gs_effect_loop(tr->fade_effect, "Crossfade")) {
				gs_draw_sprite(nullptr, 0, cx, cy);
			}
		} else if (a || b) {
			gs_effect_t *def = obs_get_base_effect(OBS_EFFECT_DEFAULT);
			gs_texture_t *pick = (e_alpha < 0.5f && a) ? a : (b ? b : a);
			if (def && pick) {
				gs_effect_set_texture_srgb(gs_effect_get_param_by_name(def, "image"), pick);
				while (gs_effect_loop(def, "Draw")) {
					gs_draw_sprite(pick, 0, cx, cy);
				}
			}
		}
		gs_enable_framebuffer_srgb(previous_srgb);
		if (src_a) obs_source_release(src_a);
		if (src_b) obs_source_release(src_b);
		return;
	}

	auto match_b_for_a = match_items(items_a, items_b, tr->match_by_name);
	std::vector<bool> b_matched(items_b.size(), false);
	for (int j : match_b_for_a)
		if (j >= 0) b_matched[size_t(j)] = true;

	std::vector<RenderOp> ops;
	ops.reserve(items_a.size() + items_b.size());

	float norm_a = items_a.size() > 1 ? 1.0f / float(items_a.size() - 1) : 1.0f;
	float norm_b = items_b.size() > 1 ? 1.0f / float(items_b.size() - 1) : 1.0f;

	// Build ops from Scene A (matched + unmatched outgoing)
	for (size_t i = 0; i < items_a.size(); ++i) {
		const auto &ia = items_a[i];
		int bj = match_b_for_a[i];
		if (bj >= 0) {
			const auto &ib = items_b[size_t(bj)];
			RenderOp op;
			op.source = ib.source;
			op.src_w = ib.src_w;
			op.src_h = ib.src_h;
			op.crop_l = ia.crop_l + (ib.crop_l - ia.crop_l) * e_alpha;
			op.crop_r = ia.crop_r + (ib.crop_r - ia.crop_r) * e_alpha;
			op.crop_t = ia.crop_t + (ib.crop_t - ia.crop_t) * e_alpha;
			op.crop_b = ia.crop_b + (ib.crop_b - ia.crop_b) * e_alpha;

			float dx = ib.center_x - ia.center_x;
			float dy = ib.center_y - ia.center_y;
			float arc = 4.0f * e * (1.0f - e) * tr->arc_amount * 0.35f;
			// Perpendicular offset for smooth Bézier curve motion
			op.center_x = ia.center_x + dx * e - dy * arc;
			op.center_y = ia.center_y + dy * e + dx * arc;

			op.scale_x = ia.scale_x + (ib.scale_x - ia.scale_x) * e;
			op.scale_y = ia.scale_y + (ib.scale_y - ia.scale_y) * e;
			op.shear = ia.shear + (ib.shear - ia.shear) * e;
			op.rot_deg = ia.rot_deg + shortest_angle_delta(ia.rot_deg, ib.rot_deg) * e;
			op.alpha = 1.0f;
			float za = ia.z_index * norm_a;
			float zb = ib.z_index * norm_b;
			op.z_order = za * (1.0f - e_alpha) + zb * e_alpha;
			ops.push_back(op);
		} else {
			RenderOp op;
			op.source = ia.source;
			op.src_w = ia.src_w;
			op.src_h = ia.src_h;
			op.crop_l = ia.crop_l;
			op.crop_r = ia.crop_r;
			op.crop_t = ia.crop_t;
			op.crop_b = ia.crop_b;
			op.center_x = ia.center_x;
			op.center_y = ia.center_y;
			op.scale_x = ia.scale_x;
			op.scale_y = ia.scale_y;
			op.shear = ia.shear;
			op.rot_deg = ia.rot_deg;
			op.alpha = 1.0f - e_alpha;
			const auto motion = move_motion::unmatched(tr->unmatched_style, false, e, float(cx), float(cy));
			op.scale_x *= motion.scale;
			op.scale_y *= motion.scale;
			op.shear *= motion.scale;
			op.center_x += motion.offset_x;
			op.center_y += motion.offset_y;
			op.z_order = ia.z_index * norm_a - 0.01f;
			ops.push_back(op);
		}
	}

	// Build ops for unmatched incoming Scene B items
	for (size_t j = 0; j < items_b.size(); ++j) {
		if (b_matched[j])
			continue;
		const auto &ib = items_b[j];
		RenderOp op;
		op.source = ib.source;
		op.src_w = ib.src_w;
		op.src_h = ib.src_h;
		op.crop_l = ib.crop_l;
		op.crop_r = ib.crop_r;
		op.crop_t = ib.crop_t;
		op.crop_b = ib.crop_b;
		op.center_x = ib.center_x;
		op.center_y = ib.center_y;
		op.scale_x = ib.scale_x;
		op.scale_y = ib.scale_y;
		op.shear = ib.shear;
		op.rot_deg = ib.rot_deg;
		op.alpha = e_alpha;
		const auto motion = move_motion::unmatched(tr->unmatched_style, true, e, float(cx), float(cy));
		op.scale_x *= motion.scale;
		op.scale_y *= motion.scale;
		op.shear *= motion.scale;
		op.center_x += motion.offset_x;
		op.center_y += motion.offset_y;
		op.z_order = ib.z_index * norm_b + 0.005f;
		ops.push_back(op);
	}

	std::stable_sort(ops.begin(), ops.end(), [](const RenderOp &l, const RenderOp &r) {
		return l.z_order < r.z_order;
	});

	for (const auto &op : ops) {
		draw_render_op(tr, op);
	}

	obs_source_release(src_a);
	obs_source_release(src_b);
}

const char *move_get_name(void *)
{
	return "Mover (Move Transition)";
}

void move_get_defaults(obs_data_t *s)
{
	obs_data_set_default_int(s, "easing", 0);
	obs_data_set_default_bool(s, "match_by_name", true);
	obs_data_set_default_int(s, "unmatched_style", 1);
	obs_data_set_default_int(s, "arc_path", 0);
	obs_data_set_default_bool(s, "nested_items", true);
}

obs_properties_t *move_get_properties(void *)
{
	obs_properties_t *props = obs_properties_create();
	obs_properties_add_text(props, "move_help",
		"Usa Agregar existente para compartir una fuente entre escenas. Cambia su posición o tamaño "
		"en la segunda escena para animarla. Prueba con 700–1000 ms y el botón Vista previa de transición.",
		OBS_TEXT_INFO);

	obs_property_t *easing = obs_properties_add_list(
		props, "easing", "Curva de movimiento", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
	obs_property_list_add_int(easing, "Suave (Ease In-Out)", 0);
	obs_property_list_add_int(easing, "Desacelerar al llegar (Ease Out)", 1);
	obs_property_list_add_int(easing, "Rebote suave (Back Out)", 2);
	obs_property_list_add_int(easing, "Cinemático (Quint In-Out)", 3);
	obs_property_list_add_int(easing, "Lineal", 4);

	obs_properties_add_bool(props, "match_by_name", "Emparejar fuentes con el mismo nombre");

	obs_property_t *unmatched = obs_properties_add_list(
		props, "unmatched_style", "Fuentes que entran o salen", OBS_COMBO_TYPE_LIST, OBS_COMBO_FORMAT_INT);
	obs_property_list_add_int(unmatched, "Fundido suave (Fade)", 0);
	obs_property_list_add_int(unmatched, "Zoom + Fundido", 1);
	obs_property_list_add_int(unmatched, "Deslizar vertical + Fundido", 2);
	obs_property_list_add_int(unmatched, "Deslizar lateral + Fundido", 3);

	obs_properties_add_int_slider(props, "arc_path", "Curvatura de trayectoria (%)", -100, 100, 5);
	obs_properties_add_bool(props, "nested_items", "Animar fuentes dentro de grupos y escenas anidadas");
	obs_properties_add_text(props, "nested_help",
		"Los contenedores con recorte o filtros se conservan completos. Con filtros de escena, mezclas "
		"especiales o lienzos diferentes se usa un fundido para respetar la composición.", OBS_TEXT_INFO);
	return props;
}

void move_update(void *data, obs_data_t *s)
{
	auto *tr = static_cast<MoveTransitionData *>(data);
	tr->easing_mode = std::clamp(int(obs_data_get_int(s, "easing")), 0, 4);
	tr->match_by_name = obs_data_get_bool(s, "match_by_name");
	tr->unmatched_style = std::clamp(int(obs_data_get_int(s, "unmatched_style")), 0, 3);
	tr->arc_amount = std::clamp(float(obs_data_get_int(s, "arc_path")) / 100.0f, -1.0f, 1.0f);
	tr->nested_items = obs_data_get_bool(s, "nested_items");
}

void *move_create(obs_data_t *settings, obs_source_t *source)
{
	auto *tr = new MoveTransitionData();
	tr->source = source;

	obs_enter_graphics();
	char *err1 = nullptr;
	tr->item_effect = gs_effect_create(kMoveEffectSrc, "android_move_item.effect", &err1);
	if (err1) {
		blog(LOG_ERROR, "[MoveTransition] item effect error: %s", err1);
		bfree(err1);
	}
	char *err2 = nullptr;
	tr->fade_effect = gs_effect_create(kCrossfadeEffectSrc, "android_move_fade.effect", &err2);
	if (err2) {
		blog(LOG_ERROR, "[MoveTransition] fade effect error: %s", err2);
		bfree(err2);
	}
	if (tr->item_effect) {
		tr->p_image = gs_effect_get_param_by_name(tr->item_effect, "image");
		tr->p_alpha = gs_effect_get_param_by_name(tr->item_effect, "alpha_val");
		tr->p_uv_min = gs_effect_get_param_by_name(tr->item_effect, "uv_min");
		tr->p_uv_max = gs_effect_get_param_by_name(tr->item_effect, "uv_max");
	}
	if (tr->fade_effect) {
		tr->p_tex_a = gs_effect_get_param_by_name(tr->fade_effect, "tex_a");
		tr->p_tex_b = gs_effect_get_param_by_name(tr->fade_effect, "tex_b");
		tr->p_fade = gs_effect_get_param_by_name(tr->fade_effect, "fade_val");
	}
	obs_leave_graphics();

	move_update(tr, settings);
	proc_handler_add(obs_source_get_proc_handler(source),
		"void describe_matches(in ptr scene_a, in ptr scene_b, out string shared, out string outgoing, out string incoming)",
		describe_matches, tr);
	return tr;
}

void move_destroy(void *data)
{
	auto *tr = static_cast<MoveTransitionData *>(data);
	if (!tr)
		return;
	obs_enter_graphics();
	if (tr->scratch_tr)
		gs_texrender_destroy(tr->scratch_tr);
	if (tr->item_effect)
		gs_effect_destroy(tr->item_effect);
	if (tr->fade_effect)
		gs_effect_destroy(tr->fade_effect);
	obs_leave_graphics();
	delete tr;
}

void move_video_render(void *data, gs_effect_t *)
{
	auto *tr = static_cast<MoveTransitionData *>(data);
	const bool previous = gs_set_linear_srgb(true);
	obs_transition_video_render2(tr->source, move_render_callback, nullptr);
	gs_set_linear_srgb(previous);
}

float move_mix_a(void *, float t) { return 1.0f - t; }
float move_mix_b(void *, float t) { return t; }

bool move_audio_render(void *data, uint64_t *ts_out, struct obs_source_audio_mix *audio, uint32_t mixers,
		       size_t channels, size_t sample_rate)
{
	auto *tr = static_cast<MoveTransitionData *>(data);
	return obs_transition_audio_render(tr->source, ts_out, audio, mixers, channels, sample_rate,
					   move_mix_a, move_mix_b);
}

enum gs_color_space move_get_color_space(void *data, size_t count, const enum gs_color_space *preferred)
{
	auto *tr = static_cast<MoveTransitionData *>(data);
	const enum gs_color_space ts = obs_transition_video_get_color_space(tr->source);
	enum gs_color_space space = ts;
	for (size_t i = 0; i < count; ++i) {
		space = preferred[i];
		if (space == ts)
			break;
	}
	return space;
}

} // namespace

void android_move_transition_register()
{
	static obs_source_info info{};
	info.id = "android_move_transition";
	info.type = OBS_SOURCE_TYPE_TRANSITION;
	info.get_name = move_get_name;
	info.create = move_create;
	info.destroy = move_destroy;
	info.get_defaults = move_get_defaults;
	info.get_properties = move_get_properties;
	info.update = move_update;
	info.video_render = move_video_render;
	info.audio_render = move_audio_render;
	info.video_get_color_space = move_get_color_space;
	obs_register_source(&info);
}
