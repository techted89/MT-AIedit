package com.mtplugin.aieditor.api;

import androidx.annotation.NonNull;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mtplugin.aieditor.ChatMessage;
import com.mtplugin.aieditor.ProviderRegistry;

/**
 * Unified AI chat client interface.
 * All clients are "synchronous blocking" implementations: internal network IO + streaming parsing,
 * executed by the caller (dialog) on a background thread.
 *
 * Note: common utility methods (trimSlash/buildClient/optString etc.) are concentrated in {@link ApiUtils},
 * do not define static methods in this interface for implementation classes to call by simple name (Java does not allow).
 */
public interface AiClient {

    /**
     * Streaming/result callback. onDelta may be called 0..N times; at the end only onFinished or onError is called once.
     */
    interface Callback {
        /** Streaming incremental text (may be empty fragment) */
        void onDelta(@NonNull String deltaText);

        /**
         * Model thinking content (e.g. deepseek-reasoner's reasoning_content,
         * Claude thinking block). Callback on background thread; not written to final result.
         *
         * @param thinkingText This round's incremental thinking content
         */
        default void onThinking(@NonNull String thinkingText) {
        }

        /**
         * Token usage for this request (may not be returned by all providers/all requests).
         * Callback on background thread; if provider did not return usage it will not be called.
         *
         * @param promptTokens     Input tokens (-1 means unknown)
         * @param completionTokens Output tokens (-1 means unknown)
         */
        default void onUsage(long promptTokens, long completionTokens) {
        }

        /**
         * Model "thinking" content fragment (e.g. DeepSeek-R1's reasoning_content, OpenAI o1's reasoning).
         * Thinking content should not be written to result; caller can use it to hint user "model thinking".
         */
        default void onReasoning(@NonNull String reasoningText) {
        }

        /**
         * Output truncated due to reaching max_tokens limit (finish_reason=length / stop_reason=max_tokens /
         * finishReason=MAX_TOKENS), used to hint user to increase limit or change model.
         */
        default void onTruncated() {
        }

        /** All finished: fullText is the accumulated complete output */
        void onFinished(@NonNull String fullText);

        /** Failure */
        void onError(@NonNull String message);
    }

    /**
     * Initiate a chat request (blocking).
     *
     * @param messages  Message list (including system / user)
     * @param callback  Callback
     * @param cancelled Cancel flag: set true to interrupt ASAP (caller thread continues running)
     */
    void chat(@NonNull List<ChatMessage> messages,
              @NonNull Callback callback,
              @NonNull AtomicBoolean cancelled);

    /**
     * Cancel the currently ongoing request (if any).
     * Underlying connection immediately interrupted, blocking read thread returns ASAP.
     * Caller usually sets {@code cancelled} flag first then calls this method, to avoid callback race conditions.
     */
    void cancel();

    /**
     * Client factory: create corresponding client by protocol type.
     */
    class Factory {

        private Factory() {
        }

        /**
         * @param protocol    Value in ProviderRegistry.Protocol (openai / claude / gemini)
         * @param baseUrl     Endpoint root address, e.g. https://api.deepseek.com/v1
         * @param apiKey      API Key (local service without Key can pass empty string)
         * @param model       Model name
         * @param temperature Sampling temperature
         * @param maxTokens   Max output token count
         * @param timeoutSec  Request timeout (seconds)
         */
        @NonNull
        public static AiClient create(@NonNull String protocol,
                                      @NonNull String baseUrl,
                                      @NonNull String apiKey,
                                      @NonNull String model,
                                      float temperature,
                                      int maxTokens,
                                      int timeoutSec) {
            switch (protocol) {
                case ProviderRegistry.Protocol.CLAUDE:
                    return new ClaudeClient(baseUrl, apiKey, model, temperature, maxTokens, timeoutSec);
                case ProviderRegistry.Protocol.GEMINI:
                    return new GeminiClient(baseUrl, apiKey, model, temperature, maxTokens, timeoutSec);
                case ProviderRegistry.Protocol.OPENAI:
                default:
                    return new OpenAiCompatibleClient(baseUrl, apiKey, model, temperature, maxTokens, timeoutSec);
            }
        }
    }
}