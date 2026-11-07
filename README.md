# AI Text Editor (MT AI Plugin)

Call any common AI API directly in the **MT Manager** text editor and let AI help you edit files.

> Open file → tap the editor top "**Pencil**" → "**AI Edit**" → enter instruction → send → preview → one-click **Apply**.

---

## Features

- **Any AI API**: Supports OpenAI-compatible (DeepSeek / Qwen / Kimi / Zhipu / SiliconFlow / Ollama / proxies) + Claude + Gemini, with 10 built-in provider presets, plus custom endpoints;
- **Three scope options**: Whole File / Selected Text / Insert at Cursor, choose as needed to **save tokens**;
- **Streaming output**: Results display in real time, stop anytime;
- **Plan / Act dual mode**: Let AI analyze a plan first (read-only), tap "Execute Plan" to switch to Act and auto-apply the write;
- **Continuous conversation**: After AI replies, the input box clears automatically—type the next question to follow up, AI remembers context until the window closes;
- **Conversation export**: One-click export the full session to a text file (directory and filename customizable).

## Installation Requirements

- MT Manager **2.26.3+** and must be **VIP** (plugin system v3 required);
- Download the `.mtp` plugin package → MT sidebar → Tools → **Plugin Management** → Install.

## Quick Start (Three Steps)

1. **Configure AI first (required)**: Plugin Management → find "AI Text Editor" → tap "**Settings**" → select provider, fill in API Key, confirm model name (endpoint can be left blank, defaults apply automatically).
2. **Open the dialog**: Use MT to open any file → tap top "Pencil" → tap "**AI Edit**" (if not found, drag it from the "Custom" group to a common position).
3. **Start editing**: Select scope → enter instruction (e.g. "add comments to this code", "translate to English") → send → confirm then tap "**Apply**" to write back to the file.

## Usage Tips

- **Save tokens when possible**: "Whole File" sends the entire file (most expensive), "Selected Text" sends only the selection (cheapest), "Insert at Cursor" sends only a small segment around the cursor. For large files, first select the part you want to change;
- **Edit before applying**: AI output appears in the preview box—you can manually adjust it before tapping "Apply" or "Copy";
- **Use Plan first for important operations**: Want to be conservative? Switch to Plan to see the plan first, then switch to Act to execute.

## FAQ

- **Can't find the "AI Edit" entry**: After tapping "Pencil" and not seeing it? Drag it from the "Custom" group to a common position;
- **Error 401**: API Key incorrect or no permission; **Error 404**: endpoint missing/extra `/v1`, or wrong model name;
- **Worried about token cost**: Prefer "Selected Text" mode, avoid "Whole File" for huge files;
- **Is the Key secure?**: Stored locally only, deleted on uninstall, only sent to the endpoint you configured with each request.

---

This plugin is for legal personal file editing only. Please comply with the terms of service of the AI providers you use.
