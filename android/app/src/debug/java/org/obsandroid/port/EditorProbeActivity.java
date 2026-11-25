// SPDX-License-Identifier: GPL-2.0-or-later
package org.obsandroid.port;
import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;
import android.graphics.Color;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

public final class EditorProbeActivity extends Activity {
    private JSONObject edit(JSONObject command) throws Exception {
        JSONObject result = new JSONObject(EngineRuntime.EXECUTOR.submit(() -> NativeBridge.edit(command.toString())).get());
        if (!result.optString("edit_error").isEmpty()) throw new IllegalStateException(result.getString("edit_error"));
        return result;
    }
    private JSONObject state() throws Exception { return new JSONObject(EngineRuntime.EXECUTOR.submit(() -> NativeBridge.state()).get()); }
    private void require(boolean value, String message) { if (!value) throw new IllegalStateException(message); }
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        TextView label = new TextView(this); label.setTextColor(Color.WHITE); label.setBackgroundColor(Color.rgb(13,18,26));
        label.setTextSize(18); label.setPadding(40,100,40,0); label.setText("Verificando escenas, filtros y medios originales…"); setContentView(label);
        new Thread(() -> {
            JSONObject report = new JSONObject();
            try {
                JSONObject state = edit(new JSONObject().put("op","scene_add").put("name","Prueba de edición"));
                int sceneIndex = state.getInt("preview_index");
                state = edit(new JSONObject().put("op","source_add").put("type","color_source_v3").put("name","Magenta verificable")
                    .put("settings",new JSONObject().put("color",0xffb414c8L).put("width",1280).put("height",720)));
                long item = state.getLong("selected_item");
                EngineRuntime.EXECUTOR.submit(() -> { NativeBridge.transition(false); NativeBridge.probe(true); }).get();
                Thread.sleep(1500); state = state(); report.put("color",state);
                require(state.getLong("probe_matches") > 20,"El color no llegó a la composición GPU");
                edit(new JSONObject().put("op","scene_select").put("index",sceneIndex));
                edit(new JSONObject().put("op","source_select").put("id",item));
                edit(new JSONObject().put("op","filter").put("type","color_filter_v2").put("enabled",true).put("settings",new JSONObject().put("brightness",0.2)));
                Thread.sleep(1000); state = state(); report.put("color_filter",state);
                int pixel = state.getInt("probe_pixel");
                require(((pixel>>16)&255)>210 && ((pixel>>8)&255)>60 && (pixel&255)>180,"Filtro de color no produjo el píxel esperado");
                edit(new JSONObject().put("op","filter").put("type","color_filter_v2").put("enabled",false));
                edit(new JSONObject().put("op","filter").put("type","crop_filter").put("enabled",true).put("settings",new JSONObject().put("left",100).put("right",100).put("top",20).put("bottom",20).put("relative",true)));
                Thread.sleep(500); state = state(); report.put("crop_filter",state);
                JSONObject cropped = state.getJSONArray("items").getJSONObject(0);
                require(cropped.getInt("width")==1080 && cropped.getInt("height")==680,"Crop original no cambió el tamaño");
                edit(new JSONObject().put("op","filter").put("type","crop_filter").put("enabled",false));
                state = edit(new JSONObject().put("op","source_transform").put("x",640).put("y",360).put("scale_x",1).put("scale_y",1).put("rotation",0).put("crop_left",16).put("crop_right",8));
                require(state.getJSONArray("items").getJSONObject(0).getInt("crop_left")==16,"Crop de item no aplicado");
                state = edit(new JSONObject().put("op","scene_duplicate").put("name","Duplicado verificable"));
                require(state.getJSONArray("items").length()==1,"Duplicación no conservó las fuentes");
                report.put("duplicated",state);
                edit(new JSONObject().put("op","scene_remove"));
                edit(new JSONObject().put("op","scene_select").put("index",sceneIndex));
                state = edit(new JSONObject().put("op","source_add").put("type","ffmpeg_source").put("name","Medio verificable")
                    .put("settings",new JSONObject().put("local_file",new File(getFilesDir(),"test-media.flv").getAbsolutePath())));
                long mediaItem = state.getLong("selected_item");
                Thread.sleep(2000); state = state(); report.put("media",state);
                JSONObject media = null;
                for(int i=0;i<state.getJSONArray("items").length();++i) { JSONObject entry=state.getJSONArray("items").getJSONObject(i); if(entry.getLong("id")==mediaItem) media=entry; }
                require(media != null && media.getInt("width")==1280 && media.getInt("height")==720,"FFmpeg original no decodificó el medio");
                pixel=state.getInt("probe_pixel"); require(((pixel>>16)&255)<90 && (pixel&255)>70 && (pixel&255)<160,"Medio decodificado no llegó a la composición final");
                edit(new JSONObject().put("op","source_remove"));
                edit(new JSONObject().put("op","scene_select").put("index",0));
                EngineRuntime.EXECUTOR.submit(() -> NativeBridge.transition(false)).get();
                edit(new JSONObject().put("op","scene_select").put("index",sceneIndex)); edit(new JSONObject().put("op","scene_remove"));
                report.put("pass",true);
            } catch(Exception error) { try { report.put("pass",false); report.put("error",error.toString()); } catch(Exception ignored){} }
            finally { try { EngineRuntime.EXECUTOR.submit(() -> NativeBridge.probe(false)).get(); } catch(Exception ignored){} }
            try(FileOutputStream out = new FileOutputStream(new File(getFilesDir(),"editor-probe.json"))) { out.write(report.toString(2).getBytes(StandardCharsets.UTF_8)); }
            catch(Exception error) { android.util.Log.e("ObsEditorProbe","Report",error); }
            android.util.Log.i("ObsEditorProbe",report.toString());
            runOnUiThread(() -> label.setText("Prueba edición: "+report.optBoolean("pass")+"\n"+report.optString("error")));
        },"OBS-EditorProbe").start();
    }
}
