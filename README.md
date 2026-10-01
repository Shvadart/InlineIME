# InlineIME

**InlineIME** is an experimental open-source Android keyboard focused on fast text editing, append-only smart suggestions, and optional AI-assisted text completion.

> Project status: early development / pre-alpha.

## Goals

- Fast everyday Russian and English typing.
- A dedicated cursor/editing toolbar for long text.
- Fast clipboard operations, including large text.
- Local calculator suggestions that **append** the result instead of replacing the expression.
- A pluggable suggestion engine for future on-device and self-hosted AI completion.
- Privacy-first behavior: sensitive fields must never be sent to an AI service.

## First milestone — v0.1

- [ ] Minimal Android IME that can be enabled and selected as a keyboard
- [ ] RU / EN layouts and number row
- [ ] Cursor and editing toolbar
- [ ] Copy / cut / paste / select all
- [ ] Calculator suggestion: `309+678=` → `987`
- [ ] Accepting the suggestion produces `309+678=987`
- [ ] Large-text paste benchmark and optimized paste path
- [ ] Basic tests and CI

## Planned AI architecture

```text
Android IME
   │
   ├── Local providers
   │   ├── Calculator
   │   ├── Dictionary
   │   └── Clipboard
   │
   └── Optional AI provider
           │
           └── Self-hosted completion API
                   │
                   └── llama.cpp / small LLM
```

The AI provider is intentionally optional. Core typing and local suggestions should continue to work offline.

## Project principles

1. **Never replace user text unexpectedly.**
2. **Local-first for deterministic features.**
3. **Do not transmit password/PIN/OTP fields.**
4. **Keep text-input latency measurable and low.**
5. **Treat keyboard access as highly sensitive.**

## Inspiration and prior art

The UX is inspired by features found in modern mobile keyboards (including cursor toolbars and calculator suggestions). FlorisBoard is being studied as an open-source Android IME reference. InlineIME's initial implementation is developed independently; third-party code, if incorporated later, will be documented with its license and attribution.

## License

Apache License 2.0. See `LICENSE`.

---

Built as a portfolio project with an emphasis on Android IME APIs, text-processing architecture, performance, privacy, and self-hosted AI integration.
