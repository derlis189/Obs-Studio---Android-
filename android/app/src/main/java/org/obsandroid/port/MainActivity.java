package org.obsandroid.port;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Real libobs lifecycle harness. Production UI is a later milestone. */
public final class MainActivity extends Activity {
    private final ExecutorService engine = Executors.newSingleThreadExecutor();
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(32, 64, 32, 32);
        TextView status = new TextView(this);
        status.setTextSize(18);
        status.setText("Diagnóstico de libobs nativo ARM64\nComprobando motor original…");
        layout.addView(status);
        Button run = new Button(this);
        run.setText("Repetir comprobación del motor");
        layout.addView(run);
        setContentView(layout);
        View.OnClickListener inspect = ignored -> {
            run.setEnabled(false);
            engine.execute(() -> {
                String result;
                try {
                    File data = new File(getFilesDir(), "libobs");
                    if (!data.isDirectory() && !data.mkdirs()) throw new IllegalStateException("Cannot prepare data directory");
                    result = NativeBridge.inspectCore(getFilesDir().getAbsolutePath(), data.getAbsolutePath(), getApplicationInfo().nativeLibraryDir);
                } catch (Throwable error) { result = "ERROR: " + error; }
                String finalResult = result;
                Log.i("ObsAndroidPort", finalResult);
                runOnUiThread(() -> { status.setText(finalResult); run.setEnabled(true); });
            });
        };
        run.setOnClickListener(inspect);
        run.post(() -> inspect.onClick(run));
    }
    @Override public void onDestroy() { engine.shutdown(); super.onDestroy(); }
}
