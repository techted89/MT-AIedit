package com.mtplugin.aieditor.api;

import androidx.annotation.NonNull;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mtplugin.aieditor.ChatMessage;

import bin.mt.json.JSONArray;
import bin.mt.json.JSONObject;
import okhttp3.Call;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * OpenAI-compatible protocol client (/chat/completions).
 * Applies to: OpenAI, DeepSeek, Qwen (compatible mode), Kimi, Zhipu GLM, SiliconFlow,
 * Ollama (OpenAI-compatible interface) and any OpenAI-compatible gateway/proxy.
 *
 * Supports streaming (SSE) and non-streaming responses; common utility methods go through {@link ApiUtils}.
 * Reasoning models (DeepSeek-R1 etc.) reasoning_content is separately callbacked via {@link Callback#onReasoning},
 * not mixed into the formal result; finish_reason=length triggers {@link Callback#onTruncated} truncation hint.
 */
public final class OpenAiCompatibleClient implements AiClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String url;
    private final String apiKey;
    private final String model;
    private final float temperature;
    private final int maxTokens;
    private final int timeoutSec;

    /** Current ongoing request (used by cancel() to interrupt the underlying connection) */
    private volatile Call currentCall;

    public OpenAiCompatibleClient(String baseUrl, String apiKey, String model,
                                  float temperature, int maxTokens, int timeoutSec) {
        this.url = ApiUtils.trimSlash(baseUrl) + "/chat/completions";
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeoutSec = timeoutSec;
    }

    @Override
    public void chat(@NonNull List<ChatMessage> messages,
                     @NonNull Callback callback,
                     @NonNull AtomicBoolean cancelled) {
        try {
            JSONArray arr = new JSONArray();
            for (ChatMessage m : messages) {
                arr.add(new JSONObject()
                        .put("role", m.role)
                        .put("content", m.content));
            }

            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("messages", arr);
            body.put("temperature", temperature);
            body.put("max_tokens", Math.max(256, maxTokens));
            body.put("stream", true);

            Request.Builder rb = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(JSON, body.toString()));
            if (!apiKey.isEmpty()) {
                rb.header("Authorization", "Bearer " + apiKey);
            }

            OkHttpClient client = ApiUtils.buildClient(timeoutSec);
            Call call = client.newCall(rb.build());
            currentCall = call;
            try (Response response = call.execute()) {
                if (!response.isSuccessful()) {
                    String err = ApiUtils.extractError(ApiUtils.readErrorBody(response));
                    callback.onError("HTTP " + response.code() + (err.isEmpty() ? "" : "：" + err));
                    return;
                }
                if (response.body() == null) {
                    callback.onError("Empty response");
                    return;
                }
                parseBody(response, callback, cancelled);
            } finally {
                currentCall = null;
            }
        } catch (Throwable t) {
            if (cancelled.get()) {
                return;
            }
            callback.onError(t.getClass().getSimpleName() + ": " + t.getMessage());
        }
    }

    @Override
    public void cancel() {
        Call c = currentCall;
        if (c != null) {
            c.cancel();
        }
    }

    private void parseBody(Response response, Callback callback, AtomicBoolean cancelled) throws IOException {
        StringBuilder accumulated = new StringBuilder();
        StringBuilder raw = new StringBuilder();
        final boolean[] truncated = {false};
        okio.BufferedSource source = response.body().source();
        String line;
        while ((line = source.readUtf8Line()) != null) {
            if (cancelled.get()) {
                return;
            }
            if (line.startsWith("data:")) {
                String data = line.substring(5).trim();
                if (data.isEmpty()) {
                    continue;
                }
                if ("[DONE]".equals(data)) {
                    break;
                }
                try {
                    JSONObject obj = new JSONObject(data);
                    // Last chunk of stream often carries usage (OpenAI-compatible format)
                    JSONObject usage = ApiUtils.optObject(obj, "usage");
                    if (usage != null) {
                        long pt = ApiUtils.optLong(usage, "prompt_tokens", -1);
                        long ct = ApiUtils.optLong(usage, "completion_tokens", -1);
                        if (pt >= 0 && ct >= 0) {
                            callback.onUsage(pt, ct);
                        }
                    }
                    JSONObject choice = ApiUtils.optObjectAt(ApiUtils.optArray(obj, "choices"), 0);
                    if (choice != null) {
                        // Reasoning model (DeepSeek-R1 etc.): reasoning_content and formal content separated
                        JSONObject delta = ApiUtils.optObject(choice, "delta");
                        String reason = delta == null ? null : ApiUtils.optString(delta, "reasoning_content");
                        if (reason == null && delta != null) {
                            reason = ApiUtils.optString(delta, "reasoning");
                        }
                        if (reason != null && !reason.isEmpty()) {
                            callback.onReasoning(reason);
                            if (cancelled.get()) {
                                return;
                            }
                        }
                        String part = delta == null ? null : ApiUtils.optString(delta, "content");
                        if (part == null || part.isEmpty()) {
                            part = ApiUtils.optString(choice, "text");
                        }
                        if (part != null && !part.isEmpty()) {
                            accumulated.append(part);
                            callback.onDelta(part);
                            if (cancelled.get()) {
                                return;
                            }
                        }
                        // Output truncated by max_tokens (finish_reason=length)
                        if ("length".equals(ApiUtils.optString(choice, "finish_reason"))) {
                            truncated[0] = true;
                        }
                    }
                } catch (Throwable ignored) {
                    // Ignore lines that cannot be parsed
                }
            } else if (line.length() > 0) {
                raw.append(line);
            }
        }

        if (truncated[0]) {
            callback.onTruncated();
        }

        if (accumulated.length() > 0) {
            callback.onFinished(accumulated.toString());
            return;
        }

        String whole = raw.toString();
        if (whole.isEmpty()) {
            whole = ApiUtils.readErrorBody(response);
        }
        if (whole.trim().isEmpty()) {
            callback.onFinished("");
            return;
        }
        try {
            String content = extractContent(whole);
            if (content != null) {
                callback.onFinished(content);
            } else {
                callback.onError("Response format cannot be parsed");
            }
        } catch (Throwable t) {
            callback.onError("Response parsing failed: " + t.getMessage());
        }
    }

    /** Extract choices[0].message.content from full JSON */
    private String extractContent(String body) throws Exception {
        String text = body.trim();
        if (text.startsWith("[")) {
            JSONArray arr = new JSONArray(text);
            if (arr.size() == 0) {
                return "";
            }
            text = arr.get(0).toString();
        }
        JSONObject obj = new JSONObject(text);
        JSONObject choice = ApiUtils.optObjectAt(ApiUtils.optArray(obj, "choices"), 0);
        if (choice == null) {
            throw new Exception(ApiUtils.extractError(body));
        }
        JSONObject message = ApiUtils.optObject(choice, "message");
        if (message != null) {
            String content = ApiUtils.optString(message, "content");
            if (content != null && !content.isEmpty()) {
                return content;
            }
            content = ApiUtils.optString(message, "text");
            if (content != null && !content.isEmpty()) {
                return content;
            }
        }
        String text2 = ApiUtils.optString(choice, "text");
        if (text2 != null && !text2.isEmpty()) {
            return text2;
        }
        return null;
    }
}
