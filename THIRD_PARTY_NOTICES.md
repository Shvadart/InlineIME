# Russian dictionary attribution

InlineIME builds a compact local membership index from **Goudron/ru-spelling-dictionary**:

- Source: https://github.com/Goudron/ru-spelling-dictionary
- Dictionary release documented upstream: 1.0.8
- License: Mozilla Public License 2.0 (MPL-2.0)
- Upstream source files: `ru_RU.aff`, `ru_RU.dic`, and generated CSpell word list.

The generated `ru_words.bloom` asset is derived dictionary data. It is used only for
offline word-membership checks. InlineIME does not contact the dictionary source at
runtime and does not send typed text anywhere.
