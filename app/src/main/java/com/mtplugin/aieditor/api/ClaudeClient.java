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
 * Anthropic Claude Messages API client (/messages).
 * Uses x-api-key request header + anthropic-version version header, supports stream output.
 * Common utility methods go through {@link ApiUtils}.
 */
public final class ClaudeClient implements AiClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String url;
    private final String apiKey;
    private final String model;
    private final float temperature;
    private final int maxTokens;
    private final int timeoutSec;

    /** Current ongoing request (used by cancel() to interrupt the underlying connection) */
    private volatile Call currentCall;

    public ClaudeClient(String baseUrl, String apiKey, String model,
                        float temperature, int maxTokens, int timeoutSec) {
        this.url = ApiUtils.trimSlash(baseUrl) + "/messages";
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
            String system = null;
            JSONArray arr = new JSONArray();
            for (ChatMessage m : messages) {
                if (ChatMessage.ROLE_SYSTEM.equals(m.role)) {
                    system = m.content;
                } else {
                    arr.add(new JSONObject()
                            .put("role", "user".equals(m.role) ? "user" : "assistant")
                            .put("content", m.content));
                }
            }

            JSONObject body = new JSONObject();
            body.put("model", model);
            body.put("max_tokens", Math.max(256, maxTokens));
            body.put("stream", true);
            body.put("temperature", temperature);
            if (system != null && !system.isEmpty()) {
                body.put("system", system);
            }
            body.put("messages", arr);

            Request.Builder rb = new Request.Builder()
                    .url(url)
                    .header("anthropic-version", "2023-06-01")
                    .post(RequestBody.create(JSON, body.toString()));
            if (!apiKey.isEmpty()) {
                rb.header("x-api-key", apiKey);
            }
            // Some Claude proxies allow leaving Key empty

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
                try {
                    JSONObject obj = new JSONObject(data);
                    // Claude Messages usage returned with message_start / message_delta
                    JSONObject usage = ApiUtils.optObject(obj, "usage");
                    if (usage != null) {
                        long pt = ApiUtils.optLong(usage, "input_tokens", -1);
                        long ct = ApiUtils.optLong(usage, "output_tokens", -1);
                        if (pt >= 0 && ct >= 0) {
                            callback.onUsage(pt, ct);
                        }
                    }
                    // Output truncated by max_tokens (stop_reason=max_tokens)
                    if ("max_tokens".equals(ApiUtils.optString(obj, "stop_reason"))) {
                        callback.onTruncated();
                    }
                    String type = ApiUtils.optString(obj, "type");
                    if ("content_block_delta".equals(type)) {
                        JSONObject delta = ApiUtils.optObject(obj, "delta");
                        String deltaType = delta == null ? null : ApiUtils.optString(delta, "type");
                        if ("thinking_delta".equals(deltaType)) {
                            // Claude thinking block: separate callback, not mixed into result
                            String thinking = ApiUtils.optString(delta, "thinking");
                            if (thinking != null && !thinking.isEmpty()) {
                                callback.onThinking(thinking);
                                if (cancelled.get()) {
                                    return;
                                }
                                continue;
                            }
                        }
                        String text = delta == null ? null : ApiUtils.optString(delta, "text");
                        if (text != null && !text.isEmpty()) {
                            accumulated.append(text);
                            callback.onDelta(text);
                            if (cancelled.get()) {
                                return;
                            }
                        }
                    } else if ("error".equals(type)) {
                        JSONObject err = ApiUtils.optObject(obj, "error");
                        String msg = err == null ? null : ApiUtils.optString(err, "message");
                        callback.onError(msg == null || msg.isEmpty() ? "Unknown error" : msg);
                        return;
                    }
                } catch (Throwable ignored) {
                    // Ignore lines that cannot be parsed
                }
            } else if (line.length() > 0) {
                raw.append(line);
            }
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
            JSONObject obj = new JSONObject(whole);
            String text = extractContent(obj);
            if (text != null) {
                callback.onFinished(text);
            } else {
                callback.onError("Response format cannot be parsed");
            }
        } catch (Throwable t) {
            callback.onError("Response parsing failed: " + t.getMessage());
        }
    }

    /** Extract content[0].text from non-streaming JSON */
    private String extractContent(JSONObject obj) throws Exception {
        JSONObject first = ApiUtils.optObjectAt(ApiUtils.optArray(obj, "content"), 0);
        if (first == null) {
            JSONObject err = ApiUtils.optObject(obj, "error");
            if (err != null) {
                String msg = ApiUtils.optString(err, "message");
                throw new Exception(msg == null || msg.isEmpty() ? "Unknown error" : msg);
            }
            return null;
        }
        return ApiUtils.optString(first, "text");
    }
}