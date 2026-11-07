package com.mtplugin.aieditor.ui;

import androidx.annotation.NonNull;

import android.content.SharedPreferences;
import android.text.Editable;
import android.text.TextUtils;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import com.mtplugin.aieditor.ChatMessage;
import com.mtplugin.aieditor.Prefs;
import com.mtplugin.aieditor.ProviderRegistry;
import com.mtplugin.aieditor.api.AiClient;
import com.mtplugin.aieditor.ui.AboutDialog;

import bin.mt.plugin.api.PluginContext;
import bin.mt.plugin.api.editor.TextEditor;
import bin.mt.plugin.api.ui.PluginButton;
import bin.mt.plugin.api.ui.PluginEditText;
import bin.mt.plugin.api.ui.PluginEditTextWatcher;
import bin.mt.plugin.api.ui.PluginSpinner;
import bin.mt.plugin.api.ui.PluginTextView;
import bin.mt.plugin.api.ui.PluginUI;
import bin.mt.plugin.api.ui.PluginView;
import bin.mt.plugin.api.ui.builder.PluginUIBuilder;
import bin.mt.plugin.api.ui.dialog.PluginDialog;
import bin.mt.plugin.api.util.ThreadUtil;

/**
 * AI Edit dialog.
 *
 * UI: instruction input box + scope + provider/config profile dropdown + model input + settings/about entry
 *     + send/stop + streaming output preview + copy/export/apply/close.
 *
 * Features: three scopes (whole file / selected text / cursor), multiple config profile switching, multi-turn continuous conversation,
 *           streaming output (stoppable, content retained), token usage display, reasoning model progress hint,
 *           token estimation, output can be manually modified before applying.
 *
 * Note: network requests run on background thread; all UI updates (setText/setEnabled etc.) must switch back to main thread
 *       via ThreadUtil.runOnUiThread (SDK has UI thread check internally).
 */
public final class AiEditDialog {

    /** Scope */
    private static final int SCOPE_FULL = 0;
    private static final int SCOPE_SELECTED = 1;
    private static final int SCOPE_CURSOR = 2;

    /** Max characters for whole file mode (approx 100k chars ≈ tens of thousands of tokens, prevent exceeding context/too slow) */
    private static final int MAX_FULL_CHARS = 100000;

    /** Multi-turn conversation history limit (count, user+assistant each count as one; 12 = last 6 rounds) */
    private static final int MAX_HISTORY = 12;

    private AiEditDialog() {
    }

    public static void show(@NonNull PluginUI pluginUI, @NonNull TextEditor editor) {
        final PluginContext context = pluginUI.getContext();
        final SharedPreferences prefs = context.getPreferences();

        // ---------- Read editor state ----------
        final boolean hasSelection = editor.hasTextSelected();
        final int selStart = editor.getSelectionStart();
        final int selEnd = editor.getSelectionEnd();
        final String selectedText = hasSelection ? editor.subText(selStart, selEnd) : "";
        final String filePath = editor.getFilePath();
        final String fileName = editor.getFileName();
        // Note: fullText (whole file) is not read at open time, delayed until send (avoid large file lag)
        final int cursorPos = editor.getSelectionStart();

        // ---------- Global parameters (parts not included in profile) ----------
        final float temperature = Prefs.getFloat(prefs, Prefs.KEY_TEMPERATURE, 0.2f);
        final int maxTokens = Prefs.getInt(prefs, Prefs.KEY_MAX_TOKENS, 8192);
        final int timeoutSec = Prefs.getInt(prefs, Prefs.KEY_TIMEOUT_SEC, 120);
        final String systemPrompt = Prefs.getString(prefs, Prefs.KEY_SYSTEM_PROMPT, Prefs.DEFAULT_SYSTEM_PROMPT);

        // ---------- Config profile (determines provider/Key/endpoint/model) ----------
        final String activeProfile = Prefs.getString(prefs, Prefs.KEY_ACTIVE_PROFILE, "p1");
        String[] initCfg = readProfile(prefs, activeProfile); // {providerId, apiKey, baseUrl, model}
        final String initProviderId = initCfg[0];
        final ProviderRegistry.Provider initProvider = ProviderRegistry.findById(initProviderId);
        String initModel = initCfg[3].trim();
        if (initModel.isEmpty() && initProvider != null && !initProvider.models.isEmpty()) {
            initModel = initProvider.models.get(0);
        }
        final String initModelText = initModel;
        final String initBaseUrl = initCfg[2].trim().isEmpty() && initProvider != null
                ? initProvider.defaultBaseUrl : initCfg[2];

        // ---------- Dropdown data ----------
        int scopeDefault = hasSelection ? SCOPE_SELECTED : SCOPE_FULL;
        int profileDefault = "p2".equals(activeProfile) ? 1 : ("p3".equals(activeProfile) ? 2 : 0);

        // Spinner current selection (SDK Spinner notifies via onItemSelected callback, captured via array)
        final int[] scopeSel = {scopeDefault};
        final int[] profileSel = {profileDefault};
        // Current effective model (main UI no longer has model input box, model is set in "Config" popup)
        final String[] currentModel = {initModelText};

        // ---------- Build view ----------
        // Note: Spinner's onItemSelected triggers once when dialog is shown,
        // so all view references must be obtained before show(), callbacks can safely operate controls.
        PluginView view = pluginUI.buildVerticalLayout()
                .paddingTop(pluginUI.dialogPaddingVertical() / 2)
                // Instruction input (occupies a full line, complete multi-line display, not cropped by horizontal layout)
                .addEditBox("instruction")
                .hint(context.getString("dialog_instruction_hint"))
                .textSize(13)
                .minLines(2).maxLines(5)
                .softWrap(PluginEditText.SOFT_WRAP_KEEP_WORD)
                // Scope + Send button (send button right next to input box, coherent operation)
                .addHorizontalLayout().children(row -> row
                        .addSpinner("scope")
                        .items(Arrays.asList(
                                context.getString("dialog_scope_full"),
                                context.getString("dialog_scope_selected"),
                                context.getString("dialog_scope_cursor")))
                        .selection(scopeDefault)
                        .layoutWeight(1)
                        .onItemSelected((spinner, position) -> scopeSel[0] = position)
                        .addButton("send").text(context.getString("dialog_send_btn"))
                        .textColor(0xFFFFFFFF)
                        .backgroundColor(0xFF4CAF50)
                )
                // Status bar
                .addTextView("status").textSize(12).textColor(pluginUI.colorTextSecondary())
                .text(context.getString("dialog_status_idle") + "  " + initBaseUrl)
                // Action buttons (Stop / Config)
                .addHorizontalLayout().children(row -> row
                        .addButton("stop").text(context.getString("dialog_stop_btn"))
                        .width(0).layoutWeight(1).enable(false)
                        .addButton("config").text(context.getString("dialog_config_btn"))
                        .width(0).layoutWeight(1)
                )
                // Result area
                .addTextView("resultLabel").text(context.getString("dialog_result_label"))
                .textSize(12).textColor(pluginUI.colorTextSecondary())
                .addEditBox("result").textSize(12).minLines(6).maxLines(12)
                .softWrap(PluginEditText.SOFT_WRAP_KEEP_WORD)
                // Plan / Act / Execute Plan (below AI output box)
                .addHorizontalLayout().children(row -> row
                        .addButton("plan").text(context.getString("dialog_mode_plan"))
                        .width(0).layoutWeight(1)
                        .addButton("act").text(context.getString("dialog_mode_act"))
                        .width(0).layoutWeight(1)
                        .addButton("execute").text(context.getString("dialog_execute_btn"))
                        .width(0).layoutWeight(1)
                )
                // Divider, separates output area from bottom actions
                .addView().height(1).widthMatchParent().marginTopDp(6).marginBottomDp(6)
                .backgroundColor(pluginUI.colorDivider())
                // Copy / Export / Close / Apply (bottom row of view, borderless text links, whitespace; Apply on far right)
                .addHorizontalLayout().children(row -> row
                        .addTextView("copy").text(context.getString("dialog_copy_btn"))
                        .textSize(13).layoutWeight(1)
                        .addTextView("export").text(context.getString("dialog_export_btn"))
                        .textSize(13).layoutWeight(1)
                        .addTextView("close").text(context.getString("dialog_close_btn"))
                        .textSize(13).layoutWeight(1)
                        .addTextView("apply").text(context.getString("dialog_apply_btn"))
                        .textSize(13).layoutWeight(1)
                )
                .paddingTopDp(10).paddingBottomDp(14)
                .build();

        // View references (must be obtained before show)
        final PluginEditText instruction = view.requireViewById("instruction");
        final PluginButton configBtn = view.requireViewById("config");
        final PluginTextView status = view.requireViewById("status");
        final PluginButton sendBtn = view.requireViewById("send");
        final PluginButton stopBtn = view.requireViewById("stop");

        final PluginEditText resultBox = view.requireViewById("result");
        final PluginButton planBtn = view.requireViewById("plan");
        final PluginButton actBtn = view.requireViewById("act");
        final PluginButton executeBtn = view.requireViewById("execute");

        // ---------- Dialog (all action buttons are in the bottom text link row of the view) ----------
        final PluginDialog dialog = pluginUI.buildDialog()
                .setTitle("{plugin_name}")
                .setView(view)
                .show();

        final PluginTextView applyBtn = view.requireViewById("apply");
        final PluginTextView closeBtn = view.requireViewById("close");
        final PluginTextView copyBtn = view.requireViewById("copy");
        final PluginTextView exportBtn = view.requireViewById("export");
        applyBtn.setEnabled(false);
        copyBtn.setEnabled(false);

        final AtomicBoolean cancelled = new AtomicBoolean(false);
        // Token usage for this request [input, output], -1 means unknown (provider did not return usage)
        final long[] lastTokens = new long[]{-1, -1};
        // Whether output is truncated by max_tokens (common for reasoning models)
        final boolean[] truncated = {false};
        // Cumulative length of thinking content for this request (not included in result)
        final long[] thinkingLen = new long[]{0};
        // Multi-turn conversation history (user/assistant pairs; excludes system)
        final ArrayList<ChatMessage> history = new ArrayList<>();
        final StringBuilder resultText = new StringBuilder();
        // Plan/Act dual mode: plan=read-only analysis, act=executable write (default plan, remembers last choice)
        final String[] mode = {Prefs.getString(prefs, Prefs.KEY_MODE, Prefs.MODE_PLAN)};

        // Output box editing control: Plan mode always read-only; Act mode also read-only while output not finished
        final boolean[] streaming = {false};           // Whether currently requesting/outputting
        final boolean[] editingAllowed = {true};       // Whether user is currently allowed to modify output box
        final boolean[] programmaticEdit = {false};    // Programmatic setText flag (avoid false interception)
        final Runnable refreshEditingState = () ->
                editingAllowed[0] = Prefs.MODE_ACT.equals(mode[0]) && !streaming[0];
        // editingAllowed = Act mode AND not currently streaming

        // Uniformly refresh "Apply / Copy" button state: shared by onFinished / onError / stop, avoid missing state
        final Runnable refreshActionButtons = () -> {
            boolean hasText = resultText.length() > 0;
            boolean actReady = hasText && !streaming[0] && Prefs.MODE_ACT.equals(mode[0]);
            applyBtn.setEnabled(actReady);
            // Act output finished, "Apply" highlighted green
            applyBtn.setTextColor(actReady ? 0xFF4CAF50 : 0xFF808080);
            copyBtn.setEnabled(hasText);
        };

        // Client corresponding to current request: used to truly interrupt underlying network request on stop/close
        final AiClient[] currentClient = new AiClient[1];

        // Intercept illegal editing: in Plan mode / Act output not finished, user modifying output box will be reverted and prompted
        resultBox.addTextChangedListener(new PluginEditTextWatcher.Simple() {
            @Override
            public void afterTextChanged(PluginEditText editText, Editable s) {
                if (programmaticEdit[0]) {
                    return;
                }
                if (!editingAllowed[0]) {
                    programmaticEdit[0] = true;
                    editText.setText(resultText);
                    moveCursorToEnd(editText);
                    programmaticEdit[0] = false;
                    pluginUI.showToast(context.getString(
                            Prefs.MODE_ACT.equals(mode[0])
                                    ? "dialog_status_output_pending"
                                    : "dialog_status_plan_readonly_edit"));
                    // Act mode: output pending / Plan mode: read-only edit
                }
            }
        });

        // ---------- Send (shared by Send and "Execute Plan" buttons) ----------
        // executePending: Execute Plan mode flag -- skip instruction validation, do not append userMsg (continuation instruction already injected into history)
        final boolean[] executePending = {false};
        final Runnable sendRunnable = () -> {
            boolean executeMode = executePending[0];
            executePending[0] = false;
            String instructionText = executeMode ? "" : instruction.getText().toString().trim();
            String modelText = currentModel[0];
            int scope = scopeSel[0];

            if (instructionText.isEmpty() && !executeMode) {
                status.setText(context.getString("dialog_status_no_instruction"));
                return;
            }
            if (modelText.isEmpty()) {
                status.setText(context.getString("dialog_status_no_model"));
                return;
            }

            // Read connection config according to current config profile
            String[] cfg = readProfile(prefs, profileId(profileSel[0]));
            final String providerId = cfg[0];
            final String apiKey = cfg[1];
            final ProviderRegistry.Provider provider = ProviderRegistry.findById(providerId);
            final String baseUrl = cfg[2].trim().isEmpty() && provider != null
                    ? provider.defaultBaseUrl : cfg[2].trim();
            final String protocol = "custom".equals(providerId)
                    ? readProtocol(prefs, profileId(profileSel[0]))
                    : provider.protocol;

            if (baseUrl.isEmpty()) {
                status.setText(context.getString("dialog_status_err", "Endpoint not configured, please fill it in Settings first"));
                return;
            }
            boolean needKey = !("ollama".equals(providerId)
                    || baseUrl.contains("localhost") || baseUrl.contains("127.0.0.1")
                    || baseUrl.contains("192.168.") || baseUrl.contains("10."));
            if (needKey && apiKey.trim().isEmpty()) {
                status.setText(context.getString("dialog_status_no_key"));
                return;
            }
            if (scope == SCOPE_SELECTED && !hasSelection) {
                status.setText(context.getString("dialog_status_need_selection"));
                return;
            }
            // Whole file mode: oversize protection (prevent exceeding model context / slow transfer)
            int fileLen = editor.length();
            if (scope == SCOPE_FULL && fileLen > MAX_FULL_CHARS) {
                status.setText(context.getString("dialog_status_file_too_large", fileLen));
                return;
            }
            // Read on demand, avoid sending extra file content (save input tokens):
            // Whole file = read all; Cursor insert = read only cursor vicinity window; Selected text = do not read whole file
            String fullText = "";
            int promptCursor = cursorPos;
            if (scope == SCOPE_FULL) {
                fullText = editor.subText(0, fileLen);
            } else if (scope == SCOPE_CURSOR) {
                int winStart = Math.max(0, cursorPos - 150);
                int winEnd = Math.min(fileLen, cursorPos + 100);
                fullText = editor.subText(winStart, winEnd);
                promptCursor = cursorPos - winStart; // Convert to relative position within window
            }

            // Construct messages (multi-turn: system appears only in first round, history carried afterwards)
            List<ChatMessage> messages = new ArrayList<>();
            if (history.isEmpty()) {
                messages.add(ChatMessage.system(buildSystemPrompt(systemPrompt, mode[0])));
            }
            messages.addAll(history);
            final ChatMessage userMsg;
            if (executeMode) {
                // Execute Plan mode: continuation instruction already injected into history, no need to construct userMsg again
                userMsg = null;
            } else {
                userMsg = ChatMessage.user(buildUserPrompt(scope, instructionText, selectedText,
                        filePath, fileName, fullText, promptCursor, mode[0]));
                messages.add(userMsg);
            }

            long estTokens = estimateTokens(messages);

            // Create client (select implementation by protocol)
            final AiClient client = AiClient.Factory.create(
                    protocol, baseUrl, apiKey, modelText, temperature, maxTokens, timeoutSec);
            currentClient[0] = client;

            // UI state
            cancelled.set(false);
            lastTokens[0] = -1;
            lastTokens[1] = -1;
            thinkingLen[0] = 0;
            truncated[0] = false;
            streaming[0] = true;
            refreshEditingState.run();
            resultText.setLength(0);
            programmaticEdit[0] = true;
            resultBox.setText("");
            programmaticEdit[0] = false;
            applyBtn.setEnabled(false);
            applyBtn.setTextColor(0xFF808080);
            copyBtn.setEnabled(false);
            sendBtn.setEnabled(false);
            stopBtn.setEnabled(true);
            if (scope == SCOPE_FULL) {
                status.setText(context.getString("dialog_status_uploading_full", fullText.length(), estTokens));
            } else {
                status.setText(context.getString("dialog_status_sending_tokens", estTokens));
            }
            // Uploading full text / Sending tokens

            new Thread(() -> {
                client.chat(messages, new AiClient.Callback() {
                    @Override
                    public void onDelta(@NonNull String deltaText) {
                        // UI updates must switch back to main thread (SDK's setEnabled/setText has thread check internally)
                        ThreadUtil.runOnUiThread(() -> {
                            resultText.append(deltaText);
                            programmaticEdit[0] = true;
                            resultBox.setText(resultText);
                            programmaticEdit[0] = false;
                            // Key: move cursor to end so view scroll follows output,
                            // otherwise setText full refresh would reset cursor/scroll to top, missing bottom content
                            moveCursorToEnd(resultBox);
                        });
                    }

                    @Override
                    public void onUsage(long promptTokens, long completionTokens) {
                        lastTokens[0] = promptTokens;
                        lastTokens[1] = completionTokens;
                    }

                    @Override
                    public void onReasoning(@NonNull String reasoningText) {
                        // Reasoning model (OpenAI-compatible): thinking content not written to result, only accumulated and progress hinted in status bar
                        if (reasoningText != null && reasoningText.length() > 0) {
                            thinkingLen[0] += reasoningText.length();
                            ThreadUtil.runOnUiThread(() ->
                                    status.setText(context.getString("dialog_status_thinking", thinkingLen[0])));
                        }
                    }

                    @Override
                    public void onTruncated() {
                        truncated[0] = true;
                    }

                    @Override
                    public void onThinking(@NonNull String thinkingText) {
                        thinkingLen[0] += thinkingText.length();
                        ThreadUtil.runOnUiThread(() ->
                                status.setText(context.getString("dialog_status_thinking", thinkingLen[0])));
                    }

                    @Override
                    public void onFinished(@NonNull String ignored) {
                        ThreadUtil.runOnUiThread(() -> {
                            boolean success = !cancelled.get() && resultText.length() > 0;
                            if (success) {
                                // Record to history (multi-turn conversation), keep last 6 rounds
                                // Execute Plan mode userMsg is null (continuation instruction already in history), do not record twice
                                if (userMsg != null) {
                                    history.add(userMsg);
                                }
                                history.add(ChatMessage.assistant(resultText.toString()));
                                // AI finished, automatically clear instruction input box: next direct input automatically continues conversation (history retained)
                                instruction.setText("");
                                while (history.size() > MAX_HISTORY) {
                                    history.remove(0);
                                    if (!history.isEmpty() && history.get(0) != null) {
                                        history.remove(0);
                                    }
                                }
                            }
                            if (cancelled.get()) {
                                // Content retained after stop (can copy / continue modifying then apply)
                                status.setText(context.getString("dialog_status_stopped", resultText.length()));
                            } else if (resultText.length() == 0 && thinkingLen[0] > 0) {
                                // Model only returned thinking content (e.g. max_tokens too small, request truncated)
                                status.setText(context.getString("dialog_status_only_thinking"));
                            } else {
                                String done = context.getString("dialog_status_done", resultText.length());
                                if (truncated[0]) {
                                    done += " · " + context.getString("dialog_status_truncated_hint");
                                }
                                if (Prefs.getBoolean(prefs, Prefs.KEY_SHOW_TOKENS, true)
                                        && lastTokens[0] >= 0 && lastTokens[1] >= 0) {
                                    done += " · " + context.getString("dialog_tokens_usage",
                                            lastTokens[0], lastTokens[1]);
                                }
                                status.setText(done);
                            }
                            streaming[0] = false;
                            refreshEditingState.run();
                            refreshActionButtons.run();
                            sendBtn.setEnabled(true);
                            stopBtn.setEnabled(false);
                        });
                    }

                    @Override
                    public void onError(@NonNull String message) {
                        ThreadUtil.runOnUiThread(() -> {
                            streaming[0] = false;
                            refreshEditingState.run();
                            sendBtn.setEnabled(true);
                            stopBtn.setEnabled(false);
                            // On error, "Apply" not allowed (output may be incomplete), but existing content can still be copied
                            applyBtn.setEnabled(false);
                            applyBtn.setTextColor(0xFF808080);
                            copyBtn.setEnabled(resultText.length() > 0);
                            status.setText(context.getString("dialog_status_err", message));
                        });
                    }
                }, cancelled);
            }).start();
        };
        sendBtn.setOnClickListener(v -> sendRunnable.run());

        // ---------- Stop (content retained) ----------
        stopBtn.setOnClickListener(v -> {
            cancelled.set(true);
            // Truly interrupt underlying network request (not just wait for flag, avoid blocking until timeout on slow response)
            AiClient c = currentClient[0];
            if (c != null) {
                c.cancel();
            }
            streaming[0] = false;
            refreshEditingState.run();
            // Retain generated content after stop: allow copy / (Act mode) apply partial output
            refreshActionButtons.run();
            sendBtn.setEnabled(true);
            stopBtn.setEnabled(false);
        });

        // ---------- Plan / Act mode switching ----------
        final Runnable refreshModeButtons = () -> {
            boolean act = Prefs.MODE_ACT.equals(mode[0]);
            refreshEditingState.run();
            planBtn.setEnabled(act);
            actBtn.setEnabled(!act);
            executeBtn.setEnabled(!act); // Button not clickable in Act mode
            if (!act) {
                applyBtn.setEnabled(false); // Plan read-only: prohibit apply
                applyBtn.setTextColor(0xFF808080);
            } else if (resultText.length() > 0) {
                applyBtn.setEnabled(true);
                applyBtn.setTextColor(0xFF4CAF50);
            }
        };
        planBtn.setOnClickListener(v -> {
            if (Prefs.MODE_ACT.equals(mode[0])) { // Current Act -> switch Plan
                // Only switch this session's mode, do not write back to settings (default startup mode determined by Settings interface)
                mode[0] = Prefs.MODE_PLAN;
                if (!history.isEmpty()) {
                    // Inject continuation instruction, seamlessly inherit context
                    history.add(ChatMessage.user("[Mode switched to Plan] Please stop writing, only perform analysis, solution design and code review, do not output modified full file content."));
                }
                status.setText(context.getString("dialog_status_mode_plan"));
            }
            refreshModeButtons.run();
        });
        actBtn.setOnClickListener(v -> {
            if (!Prefs.MODE_ACT.equals(mode[0])) { // Current Plan -> switch Act
                // Only switch this session's mode, do not write back to settings (default startup mode determined by Settings interface)
                mode[0] = Prefs.MODE_ACT;
                if (!history.isEmpty()) {
                    // Inject continuation instruction: seamlessly inherit Plan phase analysis conclusions
                    history.add(ChatMessage.user("[Mode switched to Act] User has approved the plan, please start executing: based on previous analysis conclusions, directly output modified full file content, do not output plan or explanation."));
                }
                status.setText(context.getString("dialog_status_mode_act"));
            }
            refreshModeButtons.run();
        });

        // ---------- Execute Plan: switch Act and auto-execute based on history (button disabled in Act mode) ----------
        executeBtn.setOnClickListener(v -> {
            if (Prefs.MODE_ACT.equals(mode[0])) {
                return; // Button not clickable in Act mode
            }
            if (history.isEmpty()) {
                status.setText(context.getString("dialog_status_no_plan"));
                return;
            }
            // Switch to Act mode (this session only, not written back to settings)
            mode[0] = Prefs.MODE_ACT;
            // Inject continuation instruction: let AI directly output modified full content based on historical analysis conclusions
            history.add(ChatMessage.user("[Mode switched to Act] User has approved the plan, please start executing: based on previous analysis conclusions, directly output modified full file content, do not output plan or explanation."));
            refreshModeButtons.run();
            // Trigger one send (Execute Plan mode: do not validate input box, do not append new userMsg)
            executePending[0] = true;
            sendRunnable.run();
        });
        refreshModeButtons.run(); // Initial button state set by saved mode

        // ---------- Copy (read live content from preview box, including manual modifications) ----------
        copyBtn.setOnClickListener(v -> {
            String text = resultBox.getText() == null ? "" : resultBox.getText().toString();
            if (!text.isEmpty()) {
                context.setClipboardText(text);
                pluginUI.showToast(context.getString("dialog_copy_btn") + ": " + text.length());
            }
        });

        // ---------- Export conversation (bottom text link, open export popup: directory + filename format) ----------
        exportBtn.setOnClickListener(v -> {
            showExportDialog(pluginUI, context, prefs, fileName, mode[0], currentModel[0], history, resultBox);
        });

        // ---------- Apply (read live content from preview box: AI output can be manually modified before applying) ----------
        applyBtn.setOnClickListener(v -> {
            if (!Prefs.MODE_ACT.equals(mode[0])) {
                // Plan mode read-only: prohibit writing to file
                status.setText(context.getString("dialog_status_plan_readonly"));
                return;
            }
            String result = sanitizeResult(resultBox.getText() == null ? "" : resultBox.getText().toString());
            if (result.isEmpty()) {
                status.setText(context.getString("dialog_status_no_result"));
                return;
            }
            int scope = scopeSel[0];
            try {
                if (scope == SCOPE_FULL) {
                    editor.replaceText(0, editor.length(), result);
                } else if (scope == SCOPE_SELECTED) {
                    if (selStart >= 0 && selEnd >= selStart) {
                        editor.replaceText(selStart, selEnd, result);
                    }
                } else {
                    editor.insertText(cursorPos, result);
                }
                editor.save(null);
                // Apply success does not close dialog: toast is enough, convenient for continuing conversation / applying again
                pluginUI.showToast(context.getString("dialog_status_saved"));
            } catch (Throwable t) {
                status.setText(context.getString("dialog_status_err", t.toString()));
            }
        });

        // ---------- Close ----------
        closeBtn.setOnClickListener(v -> {
            // Close interrupts ongoing request to avoid background thread callbacks to destroyed dialog controls
            cancelled.set(true);
            AiClient c = currentClient[0];
            if (c != null) {
                c.cancel();
            }
            dialog.dismiss();
        });

        // ---------- Config entry (model/profile popup) ----------
        configBtn.setOnClickListener(v -> {
            cancelled.set(true);
            AiClient c = currentClient[0];
            if (c != null) {
                c.cancel();
            }
            showConfigDialog(pluginUI, context, prefs, dialog, () -> {
                // After config saved: refresh main UI current profile and model
                String active = Prefs.getString(prefs, Prefs.KEY_ACTIVE_PROFILE, "p1");
                profileSel[0] = "p2".equals(active) ? 1 : ("p3".equals(active) ? 2 : 0);
                String[] cfg = readProfile(prefs, active);
                currentModel[0] = cfg[3].trim();
                if (currentModel[0].isEmpty()) {
                    ProviderRegistry.Provider pp = ProviderRegistry.findById(cfg[0]);
                    if (pp != null && !pp.models.isEmpty()) {
                        currentModel[0] = pp.models.get(0);
                    }
                }
            });
        });

        // Note: Settings and About entries moved into "Config" popup; input method not auto-popped on open
    }

    /* ---------- Config popup ---------- */

    /** Open "Config" popup: select config profile + provider + model, includes settings/about entry */
    private static void showConfigDialog(@NonNull PluginUI pluginUI,
                                         @NonNull PluginContext context,
                                         @NonNull SharedPreferences prefs,
                                         @NonNull PluginDialog mainDialog,
                                         @NonNull Runnable onSaved) {
        final List<String> providerNames = ProviderRegistry.allNames();
        final List<String> providerIds = ProviderRegistry.allIds();
        final String activeProfile = Prefs.getString(prefs, Prefs.KEY_ACTIVE_PROFILE, "p1");
        int profileDefault = "p2".equals(activeProfile) ? 1 : ("p3".equals(activeProfile) ? 2 : 0);
        String[] initCfg = readProfile(prefs, activeProfile);
        int providerDefault = providerIds.indexOf(initCfg[0]);
        if (providerDefault < 0) {
            providerDefault = 0;
        }

        final int[] profileSel = {profileDefault};
        final int[] providerSel = {providerDefault};
        final PluginSpinner[] providerSpinnerRef = new PluginSpinner[1];
        final PluginSpinner[] modelSpinnerRef = new PluginSpinner[1];
        final String[] selectedModel = {initCfg[3].trim()};

        // Build dynamic model list for the active profile's provider
        String activeProviderId = initCfg[0];
        ProviderRegistry.Provider activeProvider = ProviderRegistry.findById(activeProviderId);
        java.util.List<String> activeModels = ProviderRegistry.getAllModels(prefs, activeProvider);
        String initModel = initCfg[3].trim();
        if (initModel.isEmpty() && !activeModels.isEmpty()) {
            initModel = activeModels.get(0);
        }
        int modelDefault = 0;
        for (int i = 0; i < activeModels.size(); i++) {
            if (activeModels.get(i).equals(initModel)) {
                modelDefault = i;
                break;
            }
        }
        selectedModel[0] = initModel;

        PluginView view = pluginUI.buildVerticalLayout()
                .paddingTop(pluginUI.dialogPaddingVertical() / 2)
                // Config profile
                .addTextView("profileLabel").text(context.getString("dialog_profile_label")).textSize(12)
                .addSpinner("profile")
                .items(Arrays.asList(
                        context.getString("dialog_profile_p1") + " · " + providerName(readProfile(prefs, "p1")[0]),
                        context.getString("dialog_profile_p2") + " · " + providerName(readProfile(prefs, "p2")[0]),
                        context.getString("dialog_profile_p3") + " · " + providerName(readProfile(prefs, "p3")[0])))
                .selection(profileDefault)
                .onItemSelected((spinner, position) -> {
                    profileSel[0] = position;
                    // Switch profile: bring up that profile's provider and model
                    String[] cfg = readProfile(prefs, profileId(position));
                    int pi = providerIds.indexOf(cfg[0]);
                    if (pi >= 0) {
                        providerSel[0] = pi;
                        PluginSpinner pv = providerSpinnerRef[0];
                        if (pv != null) {
                            try {
                                pv.setSelection(pi);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                    // Update model spinner for new profile
                    String[] cfg2 = readProfile(prefs, profileId(position));
                    ProviderRegistry.Provider pp2 = ProviderRegistry.findById(cfg2[0]);
                    java.util.List<String> models2 = ProviderRegistry.getAllModels(prefs, pp2);
                    String m2 = cfg2[3].trim();
                    if (m2.isEmpty() && !models2.isEmpty()) {
                        m2 = models2.get(0);
                    }
                    selectedModel[0] = m2;
                    PluginSpinner ms = modelSpinnerRef[0];
                    if (ms != null && models2 != null && !models2.isEmpty()) {
                        try {
                            ms.setItems(models2);
                            int defIdx = 0;
                            for (int i = 0; i < models2.size(); i++) {
                                if (models2.get(i).equals(m2)) {
                                    defIdx = i;
                                    break;
                                }
                            }
                            ms.setSelection(defIdx);
                        } catch (Throwable ignored) {
                        }
                    }
                })
                // Provider
                .addTextView("providerLabel").text(context.getString("dialog_provider_label")).textSize(12)
                .addSpinner("provider")
                .items(providerNames)
                .selection(providerDefault)
                .onItemSelected((spinner, position) -> {
                    int previous = providerSel[0];
                    providerSel[0] = position;
                    // Update model spinner for new provider
                    String newProviderId = providerIds.get(position);
                    ProviderRegistry.Provider newProvider = ProviderRegistry.findById(newProviderId);
                    java.util.List<String> newModels = ProviderRegistry.getAllModels(prefs, newProvider);
                    String newModel = newModels.isEmpty() ? "" : newModels.get(0);
                    selectedModel[0] = newModel;
                    PluginSpinner ms = modelSpinnerRef[0];
                    if (ms != null && newModels != null && !newModels.isEmpty()) {
                        try {
                            ms.setItems(newModels);
                            int defIdx = 0;
                            for (int i = 0; i < newModels.size(); i++) {
                                if (newModels.get(i).equals(newModel)) {
                                    defIdx = i;
                                    break;
                                }
                            }
                            ms.setSelection(defIdx);
                        } catch (Throwable ignored) {
                        }
                    }
                })
                // Model (dropdown with preset + fetched models)
                .addTextView("modelLabel").text(context.getString("dialog_model_label")).textSize(12)
                .addSpinner("model")
                .items(activeModels)
                .selection(modelDefault)
                .onItemSelected((spinner, position) -> {
                    selectedModel[0] = activeModels.get(position);
                })
                // Refresh models button
                .addTextView("modelRefresh").text("⟳ Refresh models")
                .textSize(12).textColor(0xFF4CAF50).paddingTopDp(4)
                .onClick(v -> {
                    String pid = providerIds.get(providerSel[0]);
                    String ak = prefs.getString(profileKeyPrefix(profileId(profileSel[0])) + "api_key", "");
                    String bu = prefs.getString(profileKeyPrefix(profileId(profileSel[0])) + "base_url", "");
                    String ep = (bu != null && !bu.isEmpty()) ? bu : ProviderRegistry.findById(pid).defaultBaseUrl;
                    pluginUI.showToast("Fetching models from " + pid + "...");
                    new Thread(() -> {
                        java.util.List<String> fetched = ProviderRegistry.fetchModelsFromEndpoint(pid, ep, ak);
                        if (fetched != null && !fetched.isEmpty()) {
                            ProviderRegistry.putCachedModels(prefs, pid, fetched);
                            // Rebuild model list with fetched models
                            java.util.List<String> allModels = ProviderRegistry.getAllModels(prefs, ProviderRegistry.findById(pid));
                            PluginSpinner ms = modelSpinnerRef[0];
                            if (ms != null && allModels != null && !allModels.isEmpty()) {
                                try {
                                    ms.setItems(allModels);
                                    ms.setSelection(0);
                                    selectedModel[0] = allModels.get(0);
                                } catch (Throwable ignored) {
                                }
                            }
                            pluginUI.showToast("Fetched " + fetched.size() + " models for " + pid);
                        } else {
                            pluginUI.showToast("Failed to fetch models from " + pid
                                    + ". Check your endpoint and API key.");
                        }
                    }).start();
                })
                // Settings / About
                .addHorizontalLayout().children(row -> row
                        .addButton("cfgSettings").text(context.getString("dialog_settings_btn"))
                        .width(0).layoutWeight(1)
                        .addButton("cfgAbout").text(context.getString("dialog_about_btn"))
                        .width(0).layoutWeight(1)
                )
                .build();

        providerSpinnerRef[0] = view.requireViewById("provider");
        modelSpinnerRef[0] = view.requireViewById("model");

        final PluginDialog cfgDialog = pluginUI.buildDialog()
                .setTitle(context.getString("dialog_config_btn"))
                .setView(view)
                .setPositiveButton(context.getString("dialog_ok_btn"), (dialog, which) -> {
                    // Save: write current profile's provider and model + switch default profile
                    String profile = profileId(profileSel[0]);
                    Prefs.putString(prefs, Prefs.KEY_ACTIVE_PROFILE, profile);
                    Prefs.putString(prefs, profileKeyPrefix(profile) + "provider",
                            providerIds.get(providerSel[0]));
                    Prefs.putString(prefs, profileKeyPrefix(profile) + "model", selectedModel[0]);
                    onSaved.run();
                    dialog.dismiss();
                })
                .setNegativeButton(context.getString("dialog_cancel_btn"), (dialog, which) -> dialog.dismiss())
                .show();

        // Settings / About entry inside config popup
        view.requireViewById("cfgSettings").setOnClickListener(v -> {
            cfgDialog.dismiss();
            mainDialog.dismiss();
            pluginUI.showPreference(null); // null = main settings screen
        });
        view.requireViewById("cfgAbout").setOnClickListener(v -> {
            cfgDialog.dismiss();
            mainDialog.dismiss();
            AboutDialog.show(pluginUI);
        });
    }

    /* ---------- Config profiles ---------- */

    private static String providerName(String providerId) {
        try {
            return ProviderRegistry.findById(providerId).name;
        } catch (Throwable ignored) {
            return providerId;
        }
    }

    private static String profileId(int index) {
        return index == 1 ? "p2" : (index == 2 ? "p3" : "p1");
    }

    private static String profileKeyPrefix(String profile) {
        // p1 reuses old global key (compatible with old user config); p2/p3 use p2_ / p3_ prefixes
        return "p1".equals(profile) ? "" : profile + "_";
    }

    /** Read profile config: return [providerId, apiKey, baseUrl, model] */
    private static String[] readProfile(SharedPreferences prefs, String profile) {
        String prefix = profileKeyPrefix(profile);
        return new String[]{
                Prefs.getString(prefs, prefix + "provider", "deepseek"),
                Prefs.getString(prefs, prefix + "api_key", ""),
                Prefs.getString(prefs, prefix + "base_url", ""),
                Prefs.getString(prefs, prefix + "model", "")
        };
    }

    /** Read interface protocol corresponding to profile (p1 reuses old global key; p2/p3 use respective prefix keys) */
    private static String readProtocol(SharedPreferences prefs, String profile) {
        return Prefs.getString(prefs, profileKeyPrefix(profile) + "protocol",
                ProviderRegistry.Protocol.OPENAI);
    }

    /* ---------- Prompt construction ---------- */

    private static String buildUserPrompt(int scope, String instruction, String selectedText,
                                          String filePath, String fileName, String fullText,
                                          int cursorPos, String mode) {
        String path = TextUtils.isEmpty(filePath) ? (TextUtils.isEmpty(fileName) ? "Unknown" : fileName) : filePath;
        String name = TextUtils.isEmpty(fileName) ? "Unknown" : fileName;
        if (scope == SCOPE_FULL) {
            if (Prefs.MODE_PLAN.equals(mode)) {
                // Plan mode: analyze only, provide solution, do not output original text
                return "You are helping the user analyze a text file (currently Plan read-only mode)."
                        + "Please provide problem analysis and modification plan based on user [Instruction], explaining involved locations and changes."
                        + "Do not output original text, do not output modified full file content, do not output any code block fences.\n\n"
                        + "File path: " + path + "\n"
                        + "File name: " + name + "\n\n"
                        + "[Instruction]\n" + instruction + "\n\n"
                        + "[Current file content]\n" + fullText;
            }
            return "You are helping the user edit a text file. Please strictly follow user [Instruction] to modify, output only the modified full file content.\n"
                    + "Do not output any explanation, description, preamble or code block fences (do not wrap with ```)."
                    + "If no modification needed, output file content as-is.\n\n"
                    + "File path: " + path + "\n"
                    + "File name: " + name + "\n\n"
                    + "[Instruction]\n" + instruction + "\n\n"
                    + "[Current file content]\n" + fullText;
        }
        if (scope == SCOPE_SELECTED) {
            return "You are helping the user edit a segment of text in a file. Please strictly follow user [Instruction] to modify the selected content given below,"
                    + "output only the modified content (used to replace the selected part), do not output any explanation, description or code block fences.\n\n"
                    + "[Instruction]\n" + instruction + "\n\n"
                    + "[Selected content]\n" + selectedText;
        }
        // SCOPE_CURSOR: fullText is window near cursor, cursorPos is relative offset within window
        int len = fullText.length();
        int pos = Math.max(0, Math.min(cursorPos, len));
        String before = fullText.substring(0, pos);
        String after = fullText.substring(pos, len);
        return "You are helping the user insert content into a file. Please strictly follow user [Instruction] to generate text to insert,"
                + "output only the content to insert itself, do not output any explanation, description or code block fences.\n\n"
                + "[Instruction]\n" + instruction + "\n\n"
                + "[Content before insertion point (excerpt)]\n" + before + "\n\n"
                + "[Content after insertion point (excerpt)]\n" + after;
    }

    /* ---------- Plan / Act ---------- */

    /** Dynamically build System Prompt by current mode: Plan=read-only analysis, Act=execute write */
    private static String buildSystemPrompt(String base, String mode) {
        if (Prefs.MODE_ACT.equals(mode)) {
            return base + "\n\n[Current mode: Act (Execute)] User has approved the plan, please start executing:"
                    + "Directly output modified full content, strictly execute based on previous Plan analysis conclusions, do not output plan or explanation.";
        }
        return base + "\n\n[Current mode: Plan (Read-only Analysis)] You can only perform analysis, solution design and code review,"
                + "Do not output original text, do not output modified full file content (file will not be modified)."
                + "Please only output problem analysis and modification plan (including involved locations and changes), do not include any original text content.";
    }

    /* ---------- Utilities ---------- */

    /** Roughly estimate tokens: CJK/full-width 1 token/char, others 4 chars/token */
    private static long estimateTokens(List<ChatMessage> messages) {
        long total = 0;
        for (ChatMessage m : messages) {
            if (m != null && m.content != null) {
                total += estimateTokens(m.content);
            }
        }
        return total;
    }

    private static long estimateTokens(String s) {
        long cjk = 0;
        int len = s.length();
        for (int i = 0; i < len; i++) {
            char c = s.charAt(i);
            if (c >= 0x2E80 && c <= 0x9FFF) {
                cjk++;
            }
        }
        return cjk + (len - cjk + 3) / 4;
    }

    /** Move cursor to end (with scroll follow), ensure bottom content of streaming output is visible */
    private static void moveCursorToEnd(PluginEditText editText) {
        try {
            int len = editText.getText().length();
            editText.setSelection(len);
        } catch (Throwable ignored) {
            // Ignore in extreme cases, does not affect main flow
        }
    }

    /** Plan mode guide fallback: only append guide sentence when output contains executable modification plan and no Act switch hint */


    /** Open export popup: can specify directory + filename format ({title}/{date}/{time}), directory auto-remembered */
    private static void showExportDialog(@NonNull PluginUI pluginUI,
                                         @NonNull PluginContext context,
                                         @NonNull SharedPreferences prefs,
                                         @NonNull String title,
                                         @NonNull String mode,
                                         @NonNull String model,
                                         @NonNull ArrayList<ChatMessage> history,
                                         @NonNull PluginEditText resultBox) {
        String savedDir = Prefs.getString(prefs, Prefs.KEY_EXPORT_DIR, "");
        String savedName = Prefs.getString(prefs, Prefs.KEY_EXPORT_NAME, Prefs.DEFAULT_EXPORT_NAME);

        PluginView view = pluginUI.buildVerticalLayout()
                .paddingTop(pluginUI.dialogPaddingVertical() / 2)
                // Directory
                .addTextView("dirLabel").text(context.getString("dialog_export_dir_label")).textSize(12)
                .addEditText("exportDir").text(savedDir)
                .hint(context.getFilesDir() == null ? "" : context.getFilesDir().getAbsolutePath())
                // Select directory (common directory quick selection, manual input still available)
                .addTextView("pickDir").text(context.getString("dialog_export_pick"))
                .textSize(13).textColor(0xFF4CAF50).paddingTopDp(2)
                // Filename format
                .addTextView("nameLabel").text(context.getString("dialog_export_name_label")).textSize(12)
                .addEditText("exportName").text(savedName)
                .hint(Prefs.DEFAULT_EXPORT_NAME)
                // Placeholder description
                .addTextView("phHint").text(context.getString("dialog_export_ph")).textSize(12)
                .textColor(pluginUI.colorTextSecondary())
                // Real-time preview
                .addTextView("preview").textSize(12).textColor(pluginUI.colorTextSecondary())
                .build();

        final PluginEditText dirEdit = view.requireViewById("exportDir");
        final PluginEditText nameEdit = view.requireViewById("exportName");
        final PluginTextView preview = view.requireViewById("preview");
        final PluginTextView pickDir = view.requireViewById("pickDir");
        pickDir.setOnClickListener(v -> showDirPicker(pluginUI, context, prefs, dirEdit));

        final Runnable updatePreview = () -> {
            String dir = dirEdit.getText() == null ? "" : dirEdit.getText().toString().trim();
            if (dir.isEmpty()) {
                File fd = context.getFilesDir();
                dir = fd == null ? "" : fd.getAbsolutePath();
            }
            String name = resolveExportName(nameEdit.getText() == null ? "" : nameEdit.getText().toString(), title);
            preview.setText(context.getString("dialog_export_preview") + "  " + dir + "/" + name);
        };
        PluginEditTextWatcher.Simple watcher = new PluginEditTextWatcher.Simple() {
            @Override
            public void afterTextChanged(PluginEditText editText, Editable s) {
                updatePreview.run();
            }
        };
        dirEdit.addTextChangedListener(watcher);
        nameEdit.addTextChangedListener(watcher);
        updatePreview.run();

        pluginUI.buildDialog()
                .setTitle(context.getString("dialog_export_title"))
                .setView(view)
                .setPositiveButton(context.getString("dialog_ok_btn"), (dialog, which) -> {
                    String dir = dirEdit.getText() == null ? "" : dirEdit.getText().toString().trim();
                    if (dir.isEmpty()) {
                        File fd = context.getFilesDir();
                        if (fd == null) {
                            pluginUI.showToast(context.getString("dialog_export_fail") + "no dir");
                            return;
                        }
                        dir = fd.getAbsolutePath();
                    }
                    File dirFile = new File(dir);
                    if (!dirFile.exists() && !dirFile.mkdirs()) {
                        pluginUI.showToast(context.getString("dialog_export_fail") + dir);
                        return;
                    }
                    String name = resolveExportName(
                            nameEdit.getText() == null ? "" : nameEdit.getText().toString(), title);
                    // Remember directory and filename format
                    Prefs.putString(prefs, Prefs.KEY_EXPORT_DIR, dirFile.getAbsolutePath());
                    Prefs.putString(prefs, Prefs.KEY_EXPORT_NAME,
                            nameEdit.getText() == null ? "" : nameEdit.getText().toString().trim());
                    exportConversationTo(pluginUI, dirFile, name, mode, model, history, resultBox);
                    dialog.dismiss();
                })
                .setNegativeButton(context.getString("dialog_cancel_btn"), (dialog, which) -> dialog.dismiss())
                .show();
    }

    /** Directory quick selection: common directory list, click to fill (manual input retained) */
    private static void showDirPicker(@NonNull PluginUI pluginUI,
                                      @NonNull PluginContext context,
                                      @NonNull SharedPreferences prefs,
                                      @NonNull PluginEditText dirEdit) {
        String saved = Prefs.getString(prefs, Prefs.KEY_EXPORT_DIR, "");
        File filesDir = context.getFilesDir();
        String[] dirs = new String[]{
                "/sdcard/Download",
                "/sdcard/Documents",
                "/sdcard/AIedit",
                saved == null || saved.isEmpty() ? (filesDir == null ? "/sdcard" : filesDir.getAbsolutePath()) : saved
        };
        // Deduplicate
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (String d : dirs) {
            if (d != null && !d.isEmpty()) {
                set.add(d);
            }
        }
        // Dynamically build selection list (builder chain append, cannot add controls to already-built PluginView)
        // Use array to hold popup reference (cannot obtain before show), list item click sets directory and auto-closes
        final PluginDialog[] pickDialog = new PluginDialog[1];
        PluginUIBuilder builder = pluginUI.buildVerticalLayout()
                .paddingTop(pluginUI.dialogPaddingVertical() / 2);
        for (String d : set) {
            final String dir = d;
            builder = builder.addTextView("dir_" + Math.abs(dir.hashCode()))
                    .text(dir).textSize(14).paddingTopDp(6).paddingBottomDp(6)
                    .onClick(v -> {
                        dirEdit.setText(dir);
                        if (pickDialog[0] != null) {
                            pickDialog[0].dismiss();
                        }
                    });
        }
        pickDialog[0] = pluginUI.buildDialog()
                .setTitle(context.getString("dialog_export_pick"))
                .setView(builder.build())
                .setNegativeButton(context.getString("dialog_cancel_btn"), (dialog, which) -> dialog.dismiss())
                .show();
    }

    /** Parse filename by placeholders: {title}/{date}/{time}, and clean illegal characters */
    private static String resolveExportName(String format, String title) {
        String s = format == null ? "" : format.trim();
        if (s.isEmpty()) {
            s = Prefs.DEFAULT_EXPORT_NAME;
        }
        String t = title == null || title.trim().isEmpty() ? "conversation" : title.trim();
        if (t.contains(".")) {
            t = t.substring(0, t.lastIndexOf('.'));
        }
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(new Date());
        String time = new SimpleDateFormat("HHmmss", Locale.getDefault()).format(new Date());
        s = s.replace("{title}", t).replace("{date}", date).replace("{time}", time);
        return s.replaceAll("[\\\\/:*?\"<>|]", "_");
    }

    /** Export conversation to specified file */
    private static void exportConversationTo(@NonNull PluginUI pluginUI,
                                             @NonNull File dir,
                                             @NonNull String name,
                                             @NonNull String mode,
                                             @NonNull String model,
                                             @NonNull ArrayList<ChatMessage> history,
                                             @NonNull PluginEditText resultBox) {
        try {
            File file = new File(dir, name);
            StringBuilder sb = new StringBuilder();
            sb.append("===== AI Text Editor Conversation Record =====\n");
            sb.append("Time: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                    .format(new Date())).append("\n");
            sb.append("Mode: ").append(Prefs.MODE_ACT.equals(mode) ? "Act (Execute)" : "Plan (Read-only)").append("\n");
            sb.append("Model: ").append(model == null ? "" : model).append("\n\n");
            for (ChatMessage m : history) {
                if (m == null || m.content == null) {
                    continue;
                }
                sb.append(ChatMessage.ROLE_USER.equals(m.role) ? "[User] " : "[AI] ")
                        .append(m.content).append("\n\n");
            }
            String cur = resultBox.getText() == null ? "" : resultBox.getText().toString();
            if (cur.length() > 0) {
                sb.append("[AI current output] ").append(cur).append("\n");
            }
            BufferedWriter w = new BufferedWriter(new FileWriter(file));
            w.write(sb.toString());
            w.close();
            pluginUI.showToast(pluginUI.getContext().getString("dialog_export_done")
                    + "\n" + file.getAbsolutePath());
        } catch (Throwable t) {
            pluginUI.showToast(pluginUI.getContext().getString("dialog_export_fail") + t.getMessage());
        }
    }

    /** Remove ``` code block fences wrapped at start/end of AI output (only when entire output start/end are fences) */
    static String sanitizeResult(String text) {
        String t = text == null ? "" : text.trim();
        if (t.startsWith("```")) {
            int idx = t.indexOf('\n');
            if (idx >= 0) {
                t = t.substring(idx + 1);
            } else {
                t = t.substring(3);
            }
        }
        if (t.endsWith("```")) {
            t = t.substring(0, t.length() - 3);
        }
        return t.trim();
    }
}
