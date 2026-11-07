package com.mtplugin.aieditor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Common AI provider preset registry.
 *
 * Each preset contains: unique ID, display name, interface protocol type, default endpoint address, common model list.
 * The actual endpoint/Key/model used always follows what the user fills in the "Settings" interface;
 * these presets are only used for: dropdown options in the dialog / auto-filling common models when switching providers.
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
                    Arrays.asList("openrouter/auto:free", "meta-llama/llama-3.1-8b-instruct:free",
                            "microsoft/phi-3-mini-128k-instruct:free", "google/gemma-2-9b-it:free",
                            "qwen/qwen-2.5-7b-instruct:free", "mistralai/mistral-7b-instruct:free",
                            "anthropic/claude-3-haiku", "openai/gpt-4o-mini",
                            "deepseek/deepseek-chat", "openai/gpt-4o",
                            "google/gemini-2.5-flash", "meta-llama/llama-3.1-405b-instruct")),
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
    // OpenRouter free models use the ":free" suffix (e.g. "meta-llama/llama-3.1-8b-instruct:free").

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
}