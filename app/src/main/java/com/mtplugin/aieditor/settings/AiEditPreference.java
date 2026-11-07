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
 *  - Config profile: which profile to use by default (p1/p2/p3), also switchable in dialog
 *  - Profile 1/2/3 connection config: Provider + API Key + Endpoint + Model (p1 reuses old global key, compatible with old versions)
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
        addProviderList(builder, "Provider", Prefs.KEY_PROVIDER);
        addProtocolList(builder, Prefs.KEY_PROTOCOL);
        builder.addInput("API Key", Prefs.KEY_API_KEY)
                .hint("sk-... (Ollama local can be left empty)")
                .summary("***********")
                .inputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        // Note: cannot add valueAsSummary(), otherwise saved Key would be displayed in plaintext in summary
        builder.addInput("Endpoint", Prefs.KEY_BASE_URL)
                .hint("Example: https://api.deepseek.com/v1")
                .valueAsSummary();
        // Dynamic model dropdown for Profile 1 (preset + fetched models)
        SharedPreferences prefs1 = context.getPreferences();
        String providerId1 = prefs1.getString(Prefs.KEY_PROVIDER, "deepseek");
        List<String> models1 = ProviderRegistry.getAllModels(prefs1, ProviderRegistry.findById(providerId1));
        String currentModel1 = prefs1.getString(Prefs.KEY_MODEL, "deepseek-chat");
        builder.addList("Default model", Prefs.KEY_MODEL)
                .defaultValue("deepseek-chat")
                .valueAsSummary();
        for (String m : models1) {
            builder.addItem(m, m);
        }
        // Ensure current model is selectable even if not in fetched list
        boolean currentInList = models1.contains(currentModel1);
        if (!currentInList && currentModel1 != null && !currentModel1.isEmpty()) {
            builder.addItem(currentModel1, currentModel1);
        }
        // Refresh model list for Profile 1
        builder.addText("Refresh model list (Profile 1)")
                .summary("Fetch latest available models from the configured provider endpoint")
                .onClick((pluginUI, item) -> refreshModelsForProfile(pluginUI, Prefs.KEY_PROVIDER,
                        Prefs.KEY_API_KEY, Prefs.KEY_BASE_URL));
        builder.addSwitch("Show token usage", Prefs.KEY_SHOW_TOKENS)
                .defaultValue(true)
                .summaryOn("Show input/output tokens for this request in status bar after request ends")
                .summaryOff("Do not show token usage");

        // ===== Profile 2 =====
        builder.addHeader("Profile 2 · Connection");
        addProviderList(builder, "Provider", "p2_provider");
        addProtocolList(builder, "p2_protocol");
        builder.addInput("API Key", "p2_api_key")
                .hint("sk-... (Ollama local can be left empty)")
                .summary("***********")
                .inputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        builder.addInput("Endpoint", "p2_base_url")
                .hint("Leave empty to auto-use provider's default endpoint")
                .valueAsSummary();
        // Dynamic model dropdown for Profile 2 (preset + fetched models)
        SharedPreferences prefs2 = context.getPreferences();
        String providerId2 = prefs2.getString("p2_provider", "deepseek");
        List<String> models2 = ProviderRegistry.getAllModels(prefs2, ProviderRegistry.findById(providerId2));
        String currentModel2 = prefs2.getString("p2_model", "deepseek-chat");
        builder.addList("Model", "p2_model")
                .defaultValue("deepseek-chat")
                .valueAsSummary();
        for (String m : models2) {
            builder.addItem(m, m);
        }
        boolean currentInList2 = models2.contains(currentModel2);
        if (!currentInList2 && currentModel2 != null && !currentModel2.isEmpty()) {
            builder.addItem(currentModel2, currentModel2);
        }
        // Refresh model list for Profile 2
        builder.addText("Refresh model list (Profile 2)")
                .summary("Fetch latest available models from the configured provider endpoint")
                .onClick((pluginUI, item) -> refreshModelsForProfile(pluginUI, "p2_provider",
                        "p2_api_key", "p2_base_url"));

        // ===== Profile 3 =====
        builder.addHeader("Profile 3 · Connection");
        addProviderList(builder, "Provider", "p3_provider");
        addProtocolList(builder, "p3_protocol");
        builder.addInput("API Key", "p3_api_key")
                .hint("sk-... (Ollama local can be left empty)")
                .summary("***********")
                .inputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        builder.addInput("Endpoint", "p3_base_url")
                .hint("Leave empty to auto-use provider's default endpoint")
                .valueAsSummary();
        // Dynamic model dropdown for Profile 3 (preset + fetched models)
        SharedPreferences prefs3 = context.getPreferences();
        String providerId3 = prefs3.getString("p3_provider", "deepseek");
        List<String> models3 = ProviderRegistry.getAllModels(prefs3, ProviderRegistry.findById(providerId3));
        String currentModel3 = prefs3.getString("p3_model", "deepseek-chat");
        builder.addList("Model", "p3_model")
                .defaultValue("deepseek-chat")
                .valueAsSummary();
        for (String m : models3) {
            builder.addItem(m, m);
        }
        boolean currentInList3 = models3.contains(currentModel3);
        if (!currentInList3 && currentModel3 != null && !currentModel3.isEmpty()) {
            builder.addItem(currentModel3, currentModel3);
        }
        // Refresh model list for Profile 3
        builder.addText("Refresh model list (Profile 3)")
                .summary("Fetch latest available models from the configured provider endpoint")
                .onClick((pluginUI, item) -> refreshModelsForProfile(pluginUI, "p3_provider",
                        "p3_api_key", "p3_base_url"));

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
    }

    /** Provider dropdown (shared by profiles) */
    private void addProviderList(Builder builder, String title, String key) {
        builder.addList(title, key)
                .defaultValue("deepseek")
                .summary("After switching, dialog will automatically bring up common models")
                .addItem(presetId("openai"), "openai")
                .addItem(presetId("deepseek"), "deepseek")
                .addItem(presetId("qwen"), "qwen")
                .addItem(presetId("kimi"), "kimi")
                .addItem(presetId("glm"), "glm")
                .addItem(presetId("siliconflow"), "siliconflow")
                .addItem(presetId("ollama"), "ollama")
                .addItem(presetId("claude"), "claude")
                .addItem(presetId("gemini"), "gemini")
                .addItem(presetId("custom"), "custom");
    }

    /** Interface protocol dropdown (shared by profiles): effective for custom providers, determines which protocol to use */
    private void addProtocolList(Builder builder, String key) {
        builder.addList("Interface protocol (effective for custom providers)", key)
                .defaultValue("openai")
                .summary("OpenAI-compatible covers OpenAI/DeepSeek/Qwen/Kimi/GLM/SiliconFlow/Ollama and any proxy")
                .addItem("OpenAI-compatible (/chat/completions)", "openai")
                .addItem("Anthropic Claude (/messages)", "claude")
                .addItem("Google Gemini (streamGenerateContent)", "gemini");
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
                                                String apiKeyKey, String baseUrlKey) {
        SharedPreferences prefs = pluginUI.getContext().getPreferences();
        String providerId = prefs.getString(providerKey, "deepseek");
        String apiKey = prefs.getString(apiKeyKey, "");
        String baseUrl = prefs.getString(baseUrlKey, "");

        ProviderRegistry.Provider provider = ProviderRegistry.findById(providerId);
        if (provider == null) {
            pluginUI.showToast("Unknown provider: " + providerId);
            return;
        }

        // Use the profile's custom endpoint if set, otherwise fall back to the preset default
        String endpoint = (baseUrl != null && !baseUrl.isEmpty())
                ? baseUrl : provider.defaultBaseUrl;

        if (endpoint == null || endpoint.isEmpty()) {
            pluginUI.showToast("No endpoint configured for " + provider.name);
            return;
        }

        pluginUI.showToast("Fetching models from " + provider.name + "...");

        List<String> models = ProviderRegistry.fetchModelsFromEndpoint(endpoint, apiKey);
        if (models == null || models.isEmpty()) {
            pluginUI.showToast("Failed to fetch models from " + provider.name
                    + ". Check your endpoint and API key.");
            return;
        }

        ProviderRegistry.putCachedModels(prefs, providerId, models);
        pluginUI.showToast("Fetched " + models.size() + " models for " + provider.name
                + ". They are now available in the model dropdown.");
    }
}
