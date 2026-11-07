package com.mtplugin.aieditor;

import android.content.SharedPreferences;

/**
 * Plugin config read/write (stored in SharedPreferences of PluginContext.getPreferences()).
 * Note: Keys in the Settings interface (PluginPreference) must match this file's Keys exactly,
 * so values modified by the user in the Settings interface can be read directly by the dialog.
 */
public final class Prefs {

    public static final String KEY_PROVIDER = "provider";
    public static final String KEY_PROTOCOL = "protocol";
    public static final String KEY_BASE_URL = "base_url";
    public static final String KEY_API_KEY = "api_key";
    public static final String KEY_MODEL = "model";
    public static final String KEY_TEMPERATURE = "temperature";
    public static final String KEY_MAX_TOKENS = "max_tokens";
    public static final String KEY_TIMEOUT_SEC = "timeout_sec";
    public static final String KEY_SYSTEM_PROMPT = "system_prompt";
    public static final String KEY_SHOW_TOKENS = "show_tokens_usage";
    /** Current config profile in use: p1 / p2 / p3 (p1 reuses the old global key) */
    public static final String KEY_ACTIVE_PROFILE = "active_profile";
    /** Work mode: plan (read-only analysis) / act (execute write), default plan */
    public static final String KEY_MODE = "mode";
    public static final String MODE_PLAN = "plan";
    public static final String MODE_ACT = "act";
    /** Export settings: directory (auto-remembered) + filename format (supports {title}/{date}/{time} placeholders) */
    public static final String KEY_EXPORT_DIR = "export_dir";
    public static final String KEY_EXPORT_NAME = "export_name";
    public static final String DEFAULT_EXPORT_NAME = "conversation_{title}_{date}_{time}.txt";

    public static final String DEFAULT_SYSTEM_PROMPT =
            "You are a professional technical editing assistant, skilled at modifying code and various texts."
                    + "Please strictly follow the user's instructions, output only the edited content itself, "
                    + "do not output any explanation, preamble, or code block fences.";

    private Prefs() {
    }

    public static boolean getBoolean(SharedPreferences p, String key, boolean def) {
        if (p == null) {
            return def;
        }
        try {
            return p.getBoolean(key, def);
        } catch (Exception ignored) {
            return def;
        }
    }

    public static String getString(SharedPreferences p, String key, String def) {
        return p == null ? def : p.getString(key, def);
    }

    public static int getInt(SharedPreferences p, String key, int def) {
        if (p == null) {
            return def;
        }
        try {
            String v = p.getString(key, null);
            return v == null ? def : Integer.parseInt(v.trim());
        } catch (Exception ignored) {
            return def;
        }
    }

    public static float getFloat(SharedPreferences p, String key, float def) {
        if (p == null) {
            return def;
        }
        try {
            String v = p.getString(key, null);
            return v == null ? def : Float.parseFloat(v.trim());
        } catch (Exception ignored) {
            return def;
        }
    }

    public static void putString(SharedPreferences p, String key, String value) {
        if (p != null) {
            p.edit().putString(key, value).apply();
        }
    }

    public static void putInt(SharedPreferences p, String key, int value) {
        if (p != null) {
            p.edit().putString(key, String.valueOf(value)).apply();
        }
    }

    public static void putFloat(SharedPreferences p, String key, float value) {
        if (p != null) {
            p.edit().putString(key, String.valueOf(value)).apply();
        }
    }
}