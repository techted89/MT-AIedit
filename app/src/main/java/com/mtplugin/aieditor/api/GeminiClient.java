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
 * Google Gemini client (streamGenerateContent).
 * API Key passed via x-goog-api-key request header (with query parameter as double insurance).
 * Supports alt=sse streaming response; non-streaming JSON (object or array) can also be parsed.
 * Common utility methods go through {@link ApiUtils}.
 */
public final class GeminiClient implements AiClient {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private final String url;
    private final String apiKey;
    private final String model;
    private final float temperature;
    private final int maxTokens;
    private final int timeoutSec;

    /** Current ongoing request (used by cancel() to interrupt the underlying connection) */
    private volatile Call currentCall;

    public GeminiClient(String baseUrl, String apiKey, String model,
                        float temperature, int maxTokens, int timeoutSec) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model;
        this.temperature = temperature;
        this.maxTokens = maxTokens;
        this.timeoutSec = timeoutSec;
        // URLEncoder.encode(String, Charset) requires API 33+, here using String overload compatible with all versions
        this.url = ApiUtils.trimSlash(baseUrl) + "/models/" + ApiUtils.urlEncode(model)
                + ":streamGenerateContent?alt=sse&key=" + ApiUtils.urlEncode(this.apiKey);
    }

    @Override
    public void chat(@NonNull List<ChatMessage> messages,
                     @NonNull Callback callback,
                     @NonNull AtomicBoolean cancelled) {
        try {
            String system = null;
            StringBuilder userText = new StringBuilder();
            for (ChatMessage m : messages) {
                if (ChatMessage.ROLE_SYSTEM.equals(m.role)) {
                    system = m.content;
                } else if (ChatMessage.ROLE_USER.equals(m.role)) {
                    if (userText.length() > 0) {
                        userText.append('\n');
                    }
                    userText.append(m.content);
                }
            }

            JSONObject generationConfig = new JSONObject()
                    .put("temperature", temperature)
                    .put("maxOutputTokens", maxTokens);

            JSONObject body = new JSONObject();
            JSONArray parts = new JSONArray();
            parts.add(new JSONObject().put("text", userText.toString()));
            JSONObject content = new JSONObject();
            content.put("role", "user");
            content.put("parts", parts);
            JSONArray contents = new JSONArray();
            contents.add(content);
            body.put("contents", contents);
            if (system != null && !system.isEmpty()) {
                JSONArray sysParts = new JSONArray();
                sysParts.add(new JSONObject().put("text", system));
                body.put("systemInstruction", new JSONObject().put("parts", sysParts));
            }
            body.put("generationConfig", generationConfig);

            Request.Builder rb = new Request.Builder()
                    .url(url)
                    .post(RequestBody.create(JSON, body.toString()));
            if (!apiKey.isEmpty()) {
                rb.header("x-goog-api-key", apiKey);
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
                    // Gemini usageMetadata located at top of each chunk (streaming generally returned in last chunk)
                    JSONObject gemObj = new JSONObject(data);
                    JSONObject um = ApiUtils.optObject(gemObj, "usageMetadata");
                    if (um != null) {
                        long pt = ApiUtils.optLong(um, "promptTokenCount", -1);
                        long ct = ApiUtils.optLong(um, "candidatesTokenCount", -1);
                        if (pt >= 0 && ct >= 0) {
                            callback.onUsage(pt, ct);
                        }
                    }
                    // Output truncated by max_tokens (finishReason=MAX_TOKENS)
                    JSONObject cand = ApiUtils.optObjectAt(ApiUtils.optArray(gemObj, "candidates"), 0);
                    if (cand != null && "MAX_TOKENS".equals(ApiUtils.optString(cand, "finishReason"))) {
                        callback.onTruncated();
                    }
                    String text = extractTextFromCandidate(data);
                    if (text != null && !text.isEmpty()) {
                        accumulated.append(text);
                        callback.onDelta(text);
                        if (cancelled.get()) {
                            return;
                        }
                    }
                } catch (Throwable ignored) {
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
            String text = extractTextFromCandidate(whole);
            if (text != null) {
                callback.onFinished(text);
            } else {
                callback.onError("Response format cannot be parsed");
            }
        } catch (Throwable t) {
            callback.onError("Response parsing failed: " + t.getMessage());
        }
    }

    /**
     * Extract concatenated text from a single Gemini candidate JSON (object or array).
     * Gemini streaming response: each line is an object; non-streaming may be an object or an object array.
     */
    private String extractTextFromCandidate(String json) throws Exception {
        String text = json.trim();
        if (text.startsWith("[")) {
            JSONArray arr = new JSONArray(text);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.size(); i++) {
                String part = candidateText(ApiUtils.optObjectAt(arr, i));
                if (part != null) {
                    sb.append(part);
                }
            }
            return sb.length() == 0 ? null : sb.toString();
        }
        return candidateText(new JSONObject(text));
    }

    private String candidateText(JSONObject obj) {
        if (obj == null) {
            return null;
        }
        JSONObject first = ApiUtils.optObjectAt(ApiUtils.optArray(obj, "candidates"), 0);
        if (first == null) {
            return null;
        }
        JSONObject content = ApiUtils.optObject(first, "content");
        if (content == null) {
            return null;
        }
        JSONArray parts = ApiUtils.optArray(content, "parts");
        if (parts == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            String t = ApiUtils.optString(ApiUtils.optObjectAt(parts, i), "text");
            if (t != null) {
                sb.append(t);
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }
}