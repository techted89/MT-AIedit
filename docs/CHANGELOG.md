# Development Log

> Record version evolution of AI Text Editor (MT-AIedit).

- **v1.0.0** First release: AI editing inside editor—streaming output, three scope options, preview-then-apply; supports OpenAI-compatible + Claude + Gemini protocols, 10 provider presets; full settings UI; Chinese/English language packs.
- **v1.0.1** Plugin renamed to "AI Text Editor", improved usage instructions (configure AI first, pencil button entry, drag from custom group).
- **v1.0.2** Optimized About page display (official website / QQ channel / group entries), apply/copy read modified content, streaming output scroll-follow fix.
- **v1.0.3** API key masked display, added token usage display (optional toggle), About page marks author.
- **v1.0.4** Fixed "Whole File" no-output bug (thinking models fill max_tokens), default limit raised to 8192, thinking progress / truncation hints.
- **v1.0.5** Optimized "Whole File" mode: upload progress hint, oversize file protection, opening no longer pre-reads full text causing lag.
- **v1.1.0** Added multiple config profiles (3 profiles switchable in seconds), multi-turn conversation, token estimation, system prompt presets, content retained after stop.
- **v1.1.2** Fixed profile selection UI glitch, config profiles on separate lines showing provider name, "New Conversation" changed to "Continue Conversation".
- **v1.2.1** Added Plan / Act dual mode (Plan read-only analysis / Act execute write, switch auto-inherits context).
- **v1.2.2** Send button moved to right of instruction box, input method not auto-popped on open, Plan / Act read-only output interception and hints.
- **v1.2.3** UI optimization: model selection and config profile moved into "Config" popup, main interface cleaner.
- **v1.2.4** Settings / About moved into config popup, config button placed between "Stop / Continue Conversation", Plan mode does not output original text, fixed instruction input box lower half disappearing.
- **v1.3.0** Added conversation export (next to copy button, export full session as text file); send button green highlight; settings added default startup mode (Plan/Act) customization.
- **v1.3.1** Copy / Export / Apply / Close merged into bottom text link row; export upgraded to standalone popup (specify directory and filename format, directory auto-remembered, supports {title}/{date}/{time} placeholders with real-time preview); removed forum post text to reduce size.
- **v1.3.2** Bottom action area beautified (divider + whitespace); after Act output ends, "Apply" turns green highlight; export directory supports common directory quick selection (manual input retained).
- **v1.3.3** After directory quick selection popup auto-closes; optimized full-text reading and prompts for three scopes (cursor insert reads only cursor vicinity window, selected text no longer reads full text, saving input tokens).
- **v1.3.4** Removed "Continue Conversation" button: AI clears input box after completion, typing again auto-continues multi-turn conversation (history retained).
- **v1.3.5~v1.3.8** Tried multiple Plan → Act guidance schemes (ending guide → conditional trigger → code fallback → [SWITCH_TO_ACT] signal), finally evaluated and removed.
- **v1.3.10** After clicking "Apply" writing back to file, dialog no longer closes (toast shows success), can continue conversation or apply again.
- **v1.3.9** Added "Execute Plan" button (Plan / Act side by side): one-click switch Act and auto-execute historical plan; button disabled in Act mode; removed Plan ending guide.

## Special Notes

- Plugin supports **multi-turn conversation**: after AI replies, instruction input box clears automatically, type next question to continue follow-up, AI remembers context until window closes.
- **Plan / Act / Execute Plan** three buttons located below AI output box: Plan read-only analysis; Act execute write; tap "Execute Plan" to switch Act and auto-execute historical plan (button disabled in Act mode).
- Bottom actions: Copy / Export / Close / Apply (Apply on the far right, turns green highlight after Act output completes).
