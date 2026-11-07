package com.mtplugin.aieditor.api;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.concurrent.TimeUnit;

import bin.mt.json.JSONArray;
import bin.mt.json.JSONObject;
import bin.mt.json.JSONValue;
import okhttp3.OkHttpClient;
import okhttp3.Response;

/**
 * API client shared utility class.
 *
 * Note: these methods cannot be placed in the AiClient interface for implementation classes to call by simple name---
 * Java interface static methods do not enter the implementation class scope, must be called via interface name or utility class,
 * therefore uniformly concentrated in this class, client calls always write ApiUtils.xxx.
 * (ApiUtils is in the same package as clients, no import needed.)
 */
public final class ApiUtils {

    private ApiUtils() {
    }

    /* =============== bin.mt.json safe access (mnJson style) =============== */

    /** Get string member; missing or non-string returns null (no exception) */
    public static long optLong(JSONObject obj, String name, long def) {
        if (obj == null || !obj.contains(name)) {
            return def;
        }
        try {
            Object v = obj.get(name);
            if (v instanceof Number) {
                return ((Number) v).longValue();
            }
            String s = String.valueOf(v).trim();
            if (s.isEmpty()) {
                return def;
            }
            return Long.parseLong(s);
        } catch (Throwable ignored) {
            return def;
        }
    }

    public static String optString(JSONObject obj, String name) {
        if (obj == null) {
            return null;
        }
        JSONValue v = obj.get(name);
        return (v != null && v.isString()) ? v.asString() : null;
    }

    /** Get object member; missing or non-object returns null */
    public static JSONObject optObject(JSONObject obj, String name) {
        if (obj == null) {
            return null;
        }
        JSONValue v = obj.get(name);
        return (v != null && v.isObject()) ? v.asObject() : null;
    }

    /** Get array member; missing or non-array returns null */
    public static JSONArray optArray(JSONObject obj, String name) {
        if (obj == null) {
            return null;
        }
        JSONValue v = obj.get(name);
        return (v != null && v.isArray()) ? v.asArray() : null;
    }

    /** Get object at array index; out of bounds or non-object returns null */
    public static JSONObject optObjectAt(JSONArray arr, int index) {
        if (arr == null || index < 0 || index >= arr.size()) {
            return null;
        }
        JSONValue v = arr.get(index);
        return (v != null && v.isObject()) ? v.asObject() : null;
    }

    /* =============== HTTP utilities =============== */

    /** Remove trailing / */
    public static String trimSlash(String url) {
        if (url == null) {
            return "";
        }
        String s = url.trim();
        while (s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    /** Assemble OkHttpClient, unified timeout */
    public static OkHttpClient buildClient(int timeoutSec) {
        int t = Math.max(15, timeoutSec);
        return new OkHttpClient.Builder()
                .connectTimeout(t, TimeUnit.SECONDS)
                .writeTimeout(t, TimeUnit.SECONDS)
                .readTimeout(t, TimeUnit.SECONDS)
                .callTimeout(t + 60, TimeUnit.SECONDS)
                .build();
    }

    /** Read error response body (no exception on failure) */
    public static String readErrorBody(Response response) {
        try {
            if (response != null && response.body() != null) {
                return response.body().string();
            }
        } catch (IOException ignored) {
        }
        return "";
    }

    /**
     * Extract a readable error message from the error response body as much as possible.
     * Compatible with common formats: {"error":{"message":"..."}}, {"error":"..."}, {"message":"..."} or plain text.
     */
    public static String extractError(String body) {
        if (body == null || body.isEmpty()) {
            return "";
        }
        String text = body.trim();
        if (text.startsWith("{")) {
            try {
                JSONObject obj = new JSONObject(text);
                JSONObject error = optObject(obj, "error");
                if (error != null) {
                    String msg = optString(error, "message");
                    if (msg != null && !msg.isEmpty()) {
                        return msg;
                    }
                    // Claude style: error object has type/message
                    msg = optString(error, "type");
                    if (msg != null && !msg.isEmpty()) {
                        String detail = optString(error, "message");
                        return detail != null && !detail.isEmpty() ? detail : msg;
                    }
                }
                String msg = optString(obj, "message");
                if (msg != null && !msg.isEmpty()) {
                    return msg;
                }
                JSONValue ev = obj.get("error");
                if (ev != null && ev.isString()) {
                    return ev.asString();
                }
            } catch (Throwable ignored) {
                // Continue processing as plain text
            }
        }
        String s = text.replace('\n', ' ').trim();
        return s.length() > 300 ? s.substring(0, 300) : s;
    }

    /** URL encoding compatible with Android 5.0+ */
    public static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }
}