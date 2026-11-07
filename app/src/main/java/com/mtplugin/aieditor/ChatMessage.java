package com.mtplugin.aieditor;

import androidx.annotation.NonNull;

/**
 * Chat message (role + content).
 * OpenAI-compatible and Anthropic protocols both use (role, content) structure; Gemini conversion takes user text.
 */
public final class ChatMessage {

    public static final String ROLE_SYSTEM = "system";
    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";

    @NonNull
    public final String role;

    @NonNull
    public final String content;

    public ChatMessage(@NonNull String role, @NonNull String content) {
        this.role = role;
        this.content = content;
    }

    public static ChatMessage system(@NonNull String content) {
        return new ChatMessage(ROLE_SYSTEM, content);
    }

    public static ChatMessage user(@NonNull String content) {
        return new ChatMessage(ROLE_USER, content);
    }

    public static ChatMessage assistant(@NonNull String content) {
        return new ChatMessage(ROLE_ASSISTANT, content);
    }
}