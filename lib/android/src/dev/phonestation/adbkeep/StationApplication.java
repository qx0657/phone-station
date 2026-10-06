package dev.phonestation.adbkeep;

import android.app.Application;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONObject;

public final class StationApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        try (java.io.InputStream input = getAssets().open("ui-en.json")) {
            java.io.ByteArrayOutputStream buffer = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            for (int count; (count = input.read(chunk)) != -1;) { buffer.write(chunk, 0, count); }
            JSONObject json = new JSONObject(buffer.toString(StandardCharsets.UTF_8.name()));
            Map<String, String> entries = new HashMap<>();
            java.util.Iterator<String> keys = json.keys();
            while (keys.hasNext()) { String key = keys.next(); entries.put(key, json.getString(key)); }
            StationText.install(new StationText(entries), () -> StationAppearance.english(this));
        } catch (java.io.IOException | org.json.JSONException error) {
            throw new IllegalStateException("Missing UI translations", error);
        }
    }
}
