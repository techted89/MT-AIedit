package com.mtplugin.aieditor;

import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import bin.mt.json.JSONArray;
import bin.mt.json.JSONObject;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import com.mtplugin.aieditor.api.ApiUtils;

/**
 * Common AI provider preset registry.
 *
 * Each preset contains: unique ID, display name, interface protocol type, default endpoint address, common model list.
 * The actual endpoint/Key/model used always follows what the user fills in the "Settings" interface;
 * these presets are only used for: dropdown options in the dialog / auto-filling common models when switching providers.
 *
 * Models can be dynamically fetched from each provider's /v1/models endpoint and cached in SharedPreferences.
 * Fetched models are merged with the preset list so users always have access to the latest available models.
 */
public final class ProviderRegistry {

    /** Interface protocol type */
    public interface Protocol {
        String OPENAI = "openai";  // OpenAI-compatible /chat/completions
        String CLAUDE = "claude";  // Anthropic Messages API
        String GEMINI = "gemini";  // Google Gemini generateContent
    }

    /** Provider preset */
    public static final class Provider {
        public final String id;
        public final String name;
        public final String protocol;
        public final String defaultBaseUrl;
        public final List<String> models;

        Provider(String id, String name, String protocol, String defaultBaseUrl, List<String> models) {
            this.id = id;
            this.name = name;
            this.protocol = protocol;
            this.defaultBaseUrl = defaultBaseUrl;
            this.models = models;
        }
    }

    /** SharedPreferences key prefix for cached model lists per provider */
    private static final String CACHED_MODELS_KEY_PREFIX = "cached_models_";

    /** Maximum number of cached models to store per provider */
    private static final int MAX_CACHED_MODELS = 200;

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    private static final List<Provider> PRESETS = Arrays.asList(
            new Provider("openai", "OpenAI", Protocol.OPENAI,
                    "https://api.openai.com/v1",
                    Arrays.asList("gpt-4o", "gpt-4o-mini", "gpt-4.1", "gpt-4.1-mini", "gpt-4.1-nano", "o3-mini")),
            new Provider("deepseek", "DeepSeek", Protocol.OPENAI,
                    "https://api.deepseek.com/v1",
                    Arrays.asList("deepseek-chat", "deepseek-reasoner")),
            new Provider("qwen", "Qwen (Tongyi Qianwen)", Protocol.OPENAI,
                    "https://dashscope.aliyuncs.com/compatible-mode/v1",
                    Arrays.asList("qwen-plus", "qwen-turbo", "qwen-max", "qwen-long", "qwen3-max")),
            new Provider("kimi", "Kimi / Moonshot", Protocol.OPENAI,
                    "https://api.moonshot.cn/v1",
                    Arrays.asList("moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k", "kimi-latest")),
            new Provider("glm", "Zhipu GLM", Protocol.OPENAI,
                    "https://open.bigmodel.cn/api/paas/v4",
                    Arrays.asList("glm-4-plus", "glm-4-air", "glm-4-flash", "glm-4-long")),
            new Provider("siliconflow", "SiliconFlow", Protocol.OPENAI,
                    "https://api.siliconflow.cn/v1",
                    Arrays.asList("deepseek-ai/DeepSeek-V3", "deepseek-ai/DeepSeek-R1",
                            "Qwen/Qwen2.5-72B-Instruct", "THUDM/glm-4-9b-chat")),
            new Provider("openrouter", "OpenRouter", Protocol.OPENAI,
                    "https://openrouter.ai/api/v1",
                    Arrays.asList("openrouter/free",
                            // Free models (:free suffix) - verified working Nov 2026
                            "nvidia/nemotron-3.5-super-vl:free",
                            "nvidia/nemotron-3-ultra-550b-a55b:free",
                            "nvidia/nemotron-3.5-lightning:free",
                            "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free",
                            "cohere/north-mini-code:free",
                            "poolside/laguna-s-2.1:free",
                            "dots-studio/dots-3-note-preview:free",
                            // Paid models
                            "anthropic/claude-3-haiku", "openai/gpt-4o-mini",
                            "deepseek/deepseek-chat", "openai/gpt-4o",
                            "google/gemini-2.5-flash", "meta-llama/llama-3.1-405b-instruct")),
            new Provider("nim", "NVIDIA NIM", Protocol.OPENAI,
                    "https://integrate.api.nvidia.com/v1",
                    Arrays.asList("nvidia/nemotron-3.5-super-vl",
                            "nvidia/nemotron-3-ultra-550b-a55b",
                            "nvidia/nemotron-3.5-lightning",
                            "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning",
                            "meta/llama-3.1-8b-instruct",
                            "meta/llama-3.1-70b-instruct",
                            "meta/llama-3.1-405b-instruct",
                            "mistralai/mistral-nemo-instruct-2407",
                            "google/gemma-2-9b-it")),
            new Provider("ollama", "Ollama (Local)", Protocol.OPENAI,
                    "http://192.168.1.100:11434/v1",
                    Arrays.asList("llama3.1", "qwen2.5", "deepseek-r1", "gemma2")),
            new Provider("claude", "Anthropic Claude", Protocol.CLAUDE,
                    "https://api.anthropic.com/v1",
                    Arrays.asList("claude-3-7-sonnet-latest", "claude-3-5-sonnet-latest",
                            "claude-3-5-haiku-latest", "claude-opus-4-1")),
            new Provider("gemini", "Google Gemini", Protocol.GEMINI,
                    "https://generativelanguage.googleapis.com/v1beta",
                    Arrays.asList("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.0-flash", "gemini-1.5-pro")),
            // Fully custom: protocol/endpoint/model all come from the Settings interface
            new Provider("custom", "Custom", Protocol.OPENAI,
                    "",
                    Arrays.asList(""))
    );

    // Alibaba Cloud Bailian (Qwen OpenAI-compatible), Zhipu, etc. are all built-in;
    // any other OpenAI-compatible gateway/proxy, select "Custom" and manually fill the endpoint.
    // OpenRouter free models use the ":free" suffix. Free models are rate-limited
    // (20 req/min, 50-1000 req/day) and do not require a credit purchase.
    // See https://openrouter.ai/models for the current free model list.

    private static final Provider CUSTOM = findByIdInternal("custom");

    private ProviderRegistry() {
    }

    public static List<Provider> all() {
        return PRESETS;
    }

    /** For dialog/settings UI: display names of all providers */
    public static List<String> allNames() {
        List<String> names = new ArrayList<>(PRESETS.size());
        for (Provider p : PRESETS) {
            names.add(p.name);
        }
        return names;
    }

    /** Find preset by ID, return "Custom" if not found */
    public static Provider findById(String id) {
        Provider p = findByIdInternal(id);
        return p != null ? p : CUSTOM;
    }

    private static Provider findByIdInternal(String id) {
        if (id == null) {
            return null;
        }
        for (Provider p : PRESETS) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return null;
    }

    /** Provider ID list (consistent order with allNames()), used for Spinner index mapping */
    public static List<String> allIds() {
        List<String> ids = new ArrayList<>(PRESETS.size());
        for (Provider p : PRESETS) {
            ids.add(p.id);
        }
        return ids;
    }

    /* =============== Dynamic model fetching =============== */

    /** SharedPreferences key for a provider's cached model list */
    private static String cachedModelsKey(String providerId) {
        return CACHED_MODELS_KEY_PREFIX + providerId;
    }

    /** Get cached models for a provider (never null) */
    @NonNull
    public static List<String> getCachedModels(@NonNull SharedPreferences prefs, @NonNull String providerId) {
        Set<String> set = prefs.getStringSet(cachedModelsKey(providerId), null);
        if (set == null || set.isEmpty()) {
            return Collections.emptyList();
        }
        return new ArrayList<>(set);
    }

    /** Cache fetched models for a provider */
    public static void putCachedModels(@NonNull SharedPreferences prefs, @NonNull String providerId,
                                       @NonNull List<String> models) {
        Set<String> set = new LinkedHashSet<>(models);
        if (set.size() > MAX_CACHED_MODELS) {
            set = new LinkedHashSet<>(new ArrayList<>(set).subList(0, MAX_CACHED_MODELS));
        }
        prefs.edit().putStringSet(cachedModelsKey(providerId), set).apply();
    }

    /** Clear cached models for a provider */
    public static void clearCachedModels(@NonNull SharedPreferences prefs, @NonNull String providerId) {
        prefs.edit().remove(cachedModelsKey(providerId)).apply();
    }

    /**
     * Fetch available models from a provider's models endpoint.
     * Different providers use different endpoints and auth schemes:
     *   - OpenAI-compatible (OpenAI, DeepSeek, Qwen, Kimi, GLM, SiliconFlow,
     *     OpenRouter, NVIDIA NIM, Ollama, Custom): GET {baseUrl}/models
     *   - Claude (Anthropic): no public models endpoint → returns null
     *   - Gemini: GET {baseUrl}/models?key={apiKey}
     *
     * @param providerId Provider ID (e.g. "openrouter", "nim", "gemini")
     * @param baseUrl    Provider base URL (e.g. https://integrate.api.nvidia.com/v1)
     * @param apiKey     API key (may be empty for providers that don't require one)
     * @return List of model IDs, or null on failure
     */
    @Nullable
    public static List<String> fetchModelsFromEndpoint(String providerId, String baseUrl, String apiKey) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            return null;
        }

        // Build the correct models URL for each provider family
        String url;
        if ("gemini".equals(providerId)) {
            // Gemini: models endpoint uses query param for API key
            url = baseUrl.trim();
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            url += "/models?key=" + (apiKey != null ? apiKey : "");
        } else if ("claude".equals(providerId)) {
            // Anthropic Claude has no public models listing endpoint
            return null;
        } else {
            // OpenAI-compatible: OpenAI, DeepSeek, Qwen, Kimi, GLM, SiliconFlow,
            // OpenRouter, NVIDIA NIM, Ollama, Custom
            url = baseUrl.trim();
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            url += "/models";
        }

        Request.Builder rb = new Request.Builder().url(url);
        if (!"gemini".equals(providerId) && apiKey != null && !apiKey.isEmpty()) {
            // Gemini passes key via query param; others via Bearer header
            rb.header("Authorization", "Bearer " + apiKey);
        }

        try (Response response = HTTP_CLIENT.newCall(rb.build()).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                return null;
            }
            String body = response.body().string();
            JSONObject obj = new JSONObject(body);
            JSONArray data = ApiUtils.optArray(obj, "data");
            if (data == null) {
                return null;
            }
            List<String> models = new ArrayList<>(data.size());
            for (int i = 0; i < data.size(); i++) {
                JSONObject item = ApiUtils.optObjectAt(data, i);
                if (item != null) {
                    String id = ApiUtils.optString(item, "id");
                    if (id != null && !id.isEmpty()) {
                        models.add(id);
                    }
                }
            }
            Collections.sort(models);
            return models.isEmpty() ? null : models;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Get the full model list for a provider: preset models merged with cached fetched models.
     * Preset models appear first, followed by any additional fetched models not already in the preset.
     */
    @NonNull
    public static List<String> getAllModels(@NonNull SharedPreferences prefs, @NonNull Provider provider) {
        List<String> result = new ArrayList<>(provider.models);
        List<String> cached = getCachedModels(prefs, provider.id);
        Set<String> presetSet = new HashSet<>(provider.models);
        for (String m : cached) {
            if (!presetSet.contains(m)) {
                result.add(m);
                presetSet.add(m);
            }
        }
        return result;
    }

    /**
     * Test if the provider connection works by making a lightweight API call.
     * Different providers use different endpoints:
     *   - OpenAI-compatible: GET {baseUrl}/models (checks auth + endpoint)
     *   - Gemini: GET {baseUrl}/models?key={apiKey}
     *   - Claude: GET {baseUrl}/models (Anthropic has no public models endpoint;
     *     a successful or 401 response still validates connectivity/auth)
     *
     * @return Human-readable result message (e.g. "Connection successful" or "Error: ...")
     */
    public static String testConnection(String providerId, String baseUrl, String apiKey) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            return "Error: No endpoint configured";
        }

        String url = baseUrl.trim();
        while (url.endsWith("/")) {
            url = url.substring(0, url.length() - 1);
        }

        if ("gemini".equals(providerId)) {
            url += "/models?key=" + (apiKey != null ? apiKey : "");
        } else {
            // OpenAI-compatible and Claude
            url += "/models";
        }

        Request.Builder rb = new Request.Builder().url(url);
        if (!"gemini".equals(providerId) && apiKey != null && !apiKey.isEmpty()) {
            rb.header("Authorization", "Bearer " + apiKey);
        }

        try (Response response = HTTP_CLIENT.newCall(rb.build()).execute()) {
            if (response.isSuccessful()) {
                return "Connection successful";
            } else {
                String body = "";
                try {
                    if (response.body() != null) {
                        body = response.body().string();
                    }
                } catch (IOException ignored) {
                }
                String msg = body.isEmpty() ? response.message() : body;
                return "Error: HTTP " + response.code() + " - " + msg;
            }
        } catch (Exception e) {
            return "Error: " + (e.getMessage() != null ? e.getMessage() : e.toString());
        }
    }
}