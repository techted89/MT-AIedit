package com.mtplugin.aieditor.settings;

import android.content.SharedPreferences;
import android.text.InputType;

import com.mtplugin.aieditor.Prefs;
import com.mtplugin.aieditor.ProviderRegistry;
import com.mtplugin.aieditor.ui.AboutDialog;

import bin.mt.plugin.api.PluginContext;
import bin.mt.plugin.api.preference.PluginPreference;
import bin.mt.plugin.api.ui.PluginUI;

import java.util.List;

/**
 * Plugin main settings interface (PluginPreference).
 *
 * Structure:
 *  - Config profile: which profile to use by default (p1/p2/p3)
 *  - Profile 1/2/3 connection config: Provider + API Key + Endpoint + Model
 *  - Generation parameters: temperature / max_tokens / timeout (global)
 *  - System prompt presets: one-click apply built-in templates
 *
 * All addInput / addList Keys match constants in {@link Prefs},
 * MT framework automatically saves setting values to SharedPreferences, dialog reads directly.
 */
public class AiEditPreference implements PluginPreference {

    @Override
    public void onBuild(PluginContext context, Builder builder) {
        builder.title("{plugin_name}");
        builder.subtitle("Call any AI API in the text editor");

        // ===== Config profiles =====
        builder.addHeader("Config Profiles");
        builder.addList("Default startup mode", Prefs.KEY_MODE)
                .defaultValue(Prefs.MODE_PLAN)
                .summary("Default mode when dialog opens; Plan=read-only analysis, Act=executable write")
                .addItem("Plan (read-only analysis)", Prefs.MODE_PLAN)
                .addItem("Act (executable write)", Prefs.MODE_ACT);
        builder.addList("Default config profile", Prefs.KEY_ACTIVE_PROFILE)
                .defaultValue("p1")
                .summary("Default profile used when dialog opens; also switchable inside dialog")
                .addItem("Profile 1", "p1")
                .addItem("Profile 2", "p2")
                .addItem("Profile 3", "p3");

        // ===== Profile 1 =====
        builder.addHeader("Profile 1 · Connection");
        addProviderList(builder, "Provider", Prefs.KEY_PROVIDER, Prefs.KEY_BASE_URL, context);
        builder.addInput("API Key", Prefs.KEY_API_KEY)
                .hint("sk-... (Ollama local can be left empty)")
                .summary("***********")
                .inputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        builder.addInput("Endpoint", Prefs.KEY_BASE_URL)
                .hint("Example: https://api.deepseek.com/v1")
                .valueAsSummary();
        addModelDropdown(builder, "Default model", Prefs.KEY_MODEL, Prefs.KEY_PROVIDER, "deepseek-chat", context);
        addRefreshButton(builder, "Refresh model list (Profile 1)", context, Prefs.KEY_PROVIDER, Prefs.KEY_API_KEY, Prefs.KEY_BASE_URL);
        addVerifyButton(builder, "Verify connection (Profile 1)", context, Prefs.KEY_PROVIDER, Prefs.KEY_API_KEY, Prefs.KEY_BASE_URL, Prefs.KEY_MODEL);
        builder.addSwitch("Show token usage", Prefs.KEY_SHOW_TOKENS)
                .defaultValue(true)
                .summaryOn("Show input/output tokens for this request in status bar after request ends")
                .summaryOff("Do not show token usage");

        // ===== Profile 2 =====
        builder.addHeader("Profile 2 · Connection");
        addProviderList(builder, "Provider", "p2_provider", "p2_base_url", context);
        builder.addInput("API Key", "p2_api_key")
                .hint("sk-... (Ollama local can be left empty)")
                .summary("***********")
                .inputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        builder.addInput("Endpoint", "p2_base_url")
                .hint("Leave empty to auto-use provider's default endpoint")
                .valueAsSummary();
        addModelDropdown(builder, "Model", "p2_model", "p2_provider", "deepseek-chat", context);
        addRefreshButton(builder, "Refresh model list (Profile 2)", context, "p2_provider", "p2_api_key", "p2_base_url");
        addVerifyButton(builder, "Verify connection (Profile 2)", context, "p2_provider", "p2_api_key", "p2_base_url", "p2_model");

        // ===== Profile 3 =====
        builder.addHeader("Profile 3 · Connection");
        addProviderList(builder, "Provider", "p3_provider", "p3_base_url", context);
        builder.addInput("API Key", "p3_api_key")
                .hint("sk-... (Ollama local can be left empty)")
                .summary("***********")
                .inputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        builder.addInput("Endpoint", "p3_base_url")
                .hint("Leave empty to auto-use provider's default endpoint")
                .valueAsSummary();
        addModelDropdown(builder, "Model", "p3_model", "p3_provider", "deepseek-chat", context);
        addRefreshButton(builder, "Refresh model list (Profile 3)", context, "p3_provider", "p3_api_key", "p3_base_url");
        addVerifyButton(builder, "Verify connection (Profile 3)", context, "p3_provider", "p3_api_key", "p3_base_url", "p3_model");

        // ===== Generation parameters =====
        builder.addHeader("Generation Parameters");
        builder.addInput("Temperature (randomness)", Prefs.KEY_TEMPERATURE)
                .defaultValue("0.2")
                .hint("0~2, smaller is more stable")
                .valueAsSummary();
        builder.addInput("Max output Tokens", Prefs.KEY_MAX_TOKENS)
                .defaultValue("8192")
                .hint("Max tokens AI generates per request; reasoning models suggest increasing")
                .valueAsSummary();
        builder.addInput("Request timeout (seconds)", Prefs.KEY_TIMEOUT_SEC)
                .defaultValue("120")
                .hint("Network request timeout")
                .valueAsSummary();

        // ===== System prompt presets =====
        builder.addHeader("System Prompt Presets");
        builder.addText("Default Editor Assistant")
                .summary("Standard: edit per instruction, output content only")
                .onClick((pluginUI, item) -> applyPreset(pluginUI, "Default Editor Assistant", Prefs.DEFAULT_SYSTEM_PROMPT));
        builder.addText("Code Assistant")
                .summary("Focus on code modification, keep original style")
                .onClick((pluginUI, item) -> applyPreset(pluginUI, "Code Assistant",
                        "You are a senior software engineer, skilled at reading and modifying code. Please strictly follow user instructions to modify code,"
                                + " output only the modified code itself, do not output any explanation, description or code block fences."
                                + "Note: keep original code style and indentation."));
        builder.addText("Translation Assistant")
                .summary("Translate content to target language, output translation only")
                .onClick((pluginUI, item) -> applyPreset(pluginUI, "Translation Assistant",
                        "You are a professional translator. Please translate the content provided by the user into the target language, output only the translation itself,"
                                + " do not output any explanation, description or code block fences."));
        builder.addText("Polishing Assistant")
                .summary("Make expression smoother, professional, concise")
                .onClick((pluginUI, item) -> applyPreset(pluginUI, "Polishing Assistant",
                        "You are a text editor, skilled at polishing and rewriting. Please optimize the text provided by the user, making expression smoother, professional, concise,"
                                + " output only the polished text, do not output any explanation."));
        builder.addText("Summarization Assistant")
                .summary("Keep core information, greatly simplify")
                .onClick((pluginUI, item) -> applyPreset(pluginUI, "Summarization Assistant",
                        "You are a document summarization expert. While keeping core information, simplify the text provided by the user as much as possible,"
                                + " output only the summary result, do not output any explanation."));

        // ===== Prompt =====
        builder.addHeader("Prompt");
        builder.addInput("System Prompt (custom)", Prefs.KEY_SYSTEM_PROMPT)
                .defaultValue(Prefs.DEFAULT_SYSTEM_PROMPT)
                .hint("Overrides preset; leave empty to use default editor assistant")
                .inputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE)
                .valueAsSummary();

        // ===== Notes =====
        builder.addHeader("Notes");
        builder.addText("Usage Instructions").summary("1. After installation, configure AI on this page first (select config profile, fill API Key, model name, etc.)"
                + "2. Open a text file, tap the \"Pencil\" button at the top of the editor, find \"AI Edit\" in the popup menu"
                + "(if not found: first drag \"AI Edit\" from the \"Custom\" group to a common position)"
                + "3. Enter instruction → Send → Confirm/modify AI output in preview box → tap \"Apply\" to write back to file and save."
                + "Tip: AI output can be directly modified before applying; scope (Whole File / Selected Text / Insert at Cursor) can be selected as needed,"
                + "choosing a small scope (e.g. only select the part to change) can significantly save upload tokens; multi-turn conversation can use \"New Conversation\" to clear history.");
        builder.addText("API Key Security")
                .summary("Stored locally only, deleted on uninstall; please comply with each provider's terms of service.");
        builder.addText("Endpoint left empty")
                .summary("When selecting a preset provider, leaving endpoint empty will auto-use the provider's default endpoint;"
                        + " \"Custom\" provider requires manually filling the complete endpoint.");

        // ===== Community & About =====
        builder.addHeader("Community & About");
        builder.addText("About & Community")
                .summary("Version info · Website / QQ Channel / QQ Group (click to view)")
                .onClick((pluginUI, item) -> AboutDialog.show(pluginUI));

        // Register provider change listener to auto-set endpoint
        builder.onPreferenceChange((pluginUI, preferenceItem, newValue) -> {
            String key = preferenceItem.getKey();
            String providerId = newValue != null ? newValue.toString() : "deepseek";
            ProviderRegistry.Provider p = ProviderRegistry.findById(providerId);
            if (p != null) {
                SharedPreferences prefs = context.getPreferences();
                String baseUrlKey = baseUrlKeyFor(providerKey);
                String currentEndpoint = prefs.getString(baseUrlKey, "");
                boolean isCustom = "custom".equals(providerId);
                if (!isCustom && (currentEndpoint == null || currentEndpoint.isEmpty())) {
                    prefs.edit().putString(baseUrlKey, p.defaultBaseUrl).apply();
                } else if (isCustom) {
                    prefs.edit().putString(baseUrlKey, "").apply();
                }
            }
        });
    }

    private String providerKeyFor(String providerKey) {
        return providerKey;
    }

    private String baseUrlKeyFor(String providerKey) {
        if (Prefs.KEY_PROVIDER.equals(providerKey)) return Prefs.KEY_BASE_URL;
        if ("p2_provider".equals(providerKey)) return "p2_base_url";
        if ("p3_provider".equals(providerKey)) return "p3_base_url";
        return providerKey + "_base_url";
    }

    /** Provider dropdown (shared by profiles) */
    private void addProviderList(Builder builder, String title, String key, String baseUrlKey, PluginContext context) {
        builder.addList(title, key)
                .defaultValue("deepseek")
                .summary("Select provider - endpoint auto-set to provider default")
                .addItem(presetId("openai"), "openai")
                .addItem(presetId("deepseek"), "deepseek")
                .addItem(presetId("qwen"), "qwen")
                .addItem(presetId("kimi"), "kimi")
                .addItem(presetId("glm"), "glm")
                .addItem(presetId("siliconflow"), "siliconflow")
                .addItem(presetId("openrouter"), "openrouter")
                .addItem(presetId("nim"), "nim")
                .addItem(presetId("ollama"), "ollama")
                .addItem(presetId("claude"), "claude")
                .addItem(presetId("gemini"), "gemini")
                .addItem(presetId("custom"), "custom");
    }

    /** Model dropdown (shared by profiles) - preset + fetched models */
    private void addModelDropdown(Builder builder, String title, String key, String providerKey, String defaultModel, PluginContext context) {
        SharedPreferences prefs = context.getPreferences();
        String providerId = prefs.getString(providerKey, "deepseek");
        java.util.List<String> models = ProviderRegistry.getAllModels(prefs, ProviderRegistry.findById(providerId));
        String currentModel = prefs.getString(key, defaultModel);
        var mlb = builder.addList(title, key)
                .defaultValue(models.contains(currentModel) ? currentModel : (models.isEmpty() ? defaultModel : models.get(0)))
                .summary(models.size() + " models available. Tap 'Refresh model list' below to update from provider endpoint.");
        for (String m : models) {
            mlb.addItem(m, m);
        }
        if (!models.contains(currentModel) && currentModel != null && !currentModel.isEmpty()) {
            mlb.addItem(currentModel, currentModel);
        }
    }

    /** Refresh model list button */
    private void addRefreshButton(Builder builder, String title, PluginContext context, String providerKey, String apiKeyKey, String baseUrlKey) {
        builder.addText(title)
                .summary("Fetch latest available models from the configured provider endpoint")
                .onClick((pluginUI, item) -> refreshModelsForProfile(pluginUI, providerKey, apiKeyKey, baseUrlKey, context));
    }

    /** Verify connection button */
    private void addVerifyButton(Builder builder, String title, PluginContext context, String providerKey, String apiKeyKey, String baseUrlKey, String modelKey) {
        builder.addText(title)
                .summary("Test API key, endpoint, and model are working")
                .onClick((pluginUI, item) -> testConnectionForProfile(pluginUI, providerKey, apiKeyKey, baseUrlKey, modelKey, context));
    }

    /** Click to apply system prompt preset */
    private static void applyPreset(PluginUI pluginUI, String name, String template) {
        Prefs.putString(pluginUI.getContext().getPreferences(), Prefs.KEY_SYSTEM_PROMPT, template);
        pluginUI.showToast("System prompt preset applied: " + name);
    }

    /** Get display name of provider preset */
    private static String presetId(String id) {
        return ProviderRegistry.findById(id).name;
    }

    /** Fetch and cache the latest model list for a profile's provider */
    private static void refreshModelsForProfile(PluginUI pluginUI, String providerKey,
                                                String apiKeyKey, String baseUrlKey, PluginContext context) {
        SharedPreferences prefs = context.getPreferences();
        String providerId = prefs.getString(providerKey, "deepseek");
        String apiKey = prefs.getString(apiKeyKey, "");
        String baseUrl = prefs.getString(baseUrlKey, "");

        ProviderRegistry.Provider provider = ProviderRegistry.findById(providerId);
        if (provider == null) {
            pluginUI.showToast("Unknown provider: " + providerId);
            return;
        }

        String rawEndpoint = (baseUrl != null && !baseUrl.isEmpty())
                ? baseUrl : provider.defaultBaseUrl;
        String endpoint = ProviderRegistry.normalizeBaseUrl(rawEndpoint);

        if (endpoint == null || endpoint.isEmpty()) {
            pluginUI.showToast("No endpoint configured for " + provider.name);
            return;
        }

        pluginUI.showToast("Fetching models from " + provider.name + "...");

        new Thread(() -> {
            ProviderRegistry.ModelFetchResult result = ProviderRegistry.fetchModelsFromEndpoint(providerId, endpoint, apiKey);
            if (!result.success) {
                pluginUI.showToast(result.errorMessage);
                return;
            }
            ProviderRegistry.putCachedModels(prefs, providerId, result.models);
            pluginUI.showToast("Fetched " + result.models.size() + " models for " + provider.name
                    + ". They are now available in the model dropdown.");
        }).start();
    }

    /** Test if the provider connection works for a profile's current settings */
    private static void testConnectionForProfile(PluginUI pluginUI, String providerKey,
                                                 String apiKeyKey, String baseUrlKey,
                                                 String modelKey, PluginContext context) {
        SharedPreferences prefs = context.getPreferences();
        String providerId = prefs.getString(providerKey, "deepseek");
        String apiKey = prefs.getString(apiKeyKey, "");
        String baseUrl = prefs.getString(baseUrlKey, "");
        String model = prefs.getString(modelKey, "");

        ProviderRegistry.Provider provider = ProviderRegistry.findById(providerId);
        if (provider == null) {
            pluginUI.showToast("Unknown provider: " + providerId);
            return;
        }

        String rawEndpoint = (baseUrl != null && !baseUrl.isEmpty())
                ? baseUrl : provider.defaultBaseUrl;
        String endpoint = ProviderRegistry.normalizeBaseUrl(rawEndpoint);

        if (endpoint == null || endpoint.isEmpty()) {
            pluginUI.showToast("No endpoint configured for " + provider.name);
            return;
        }

        if (model == null || model.isEmpty()) {
            pluginUI.showToast("No model configured for " + provider.name);
            return;
        }

        pluginUI.showToast("Testing connection to " + provider.name + "...");

        new Thread(() -> {
            String result = ProviderRegistry.testConnection(providerId, endpoint, apiKey);
            pluginUI.showToast(provider.name + ": " + result);
        }).start();
    }
}