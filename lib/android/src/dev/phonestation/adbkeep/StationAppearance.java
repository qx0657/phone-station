package dev.phonestation.adbkeep;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.os.LocaleList;
import java.util.Locale;

/** Display preferences never change service, feature or authorization preferences. */
final class StationAppearance {
    static final String SYSTEM = "system";
    static final String LANGUAGE = "language";
    static final String THEME = "theme";
    private static SharedPreferences store(Context context) { return context.getSharedPreferences("appearance", Context.MODE_PRIVATE); }
    static String choice(Context context, String key) {
        String value = store(context).getString(key, SYSTEM);
        return (LANGUAGE.equals(key) ? "zh".equals(value) || "en".equals(value)
                : "light".equals(value) || "dark".equals(value)) ? value : SYSTEM;
    }
    static String signature(Context context) { return choice(context, LANGUAGE) + ":" + choice(context, THEME); }
    static boolean save(Context context, String key, String value) { return store(context).edit().putString(key, value).commit(); }
    static boolean english(Context context) {
        String selected = choice(context, LANGUAGE);
        return "en".equals(selected) || SYSTEM.equals(selected)
                && !"zh".equals(android.content.res.Resources.getSystem().getConfiguration().getLocales().get(0).getLanguage());
    }
    static Context wrap(Context context) {
        Configuration config = new Configuration(context.getResources().getConfiguration());
        config.setLocales(new LocaleList(english(context) ? Locale.ENGLISH : Locale.SIMPLIFIED_CHINESE));
        String theme = choice(context, THEME);
        int night = SYSTEM.equals(theme)
                ? android.content.res.Resources.getSystem().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK
                : "dark".equals(theme) ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO;
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | night;
        return context.createConfigurationContext(config);
    }
    static Context notificationContext(Context context) {
        Configuration config = new Configuration(wrap(context).getResources().getConfiguration());
        // Notification surfaces are owned by System UI; match its background contrast.
        config.uiMode = (config.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (android.content.res.Resources.getSystem().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK);
        return context.createConfigurationContext(config);
    }
    private StationAppearance() {}
}
