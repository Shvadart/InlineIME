#!/usr/bin/env python3
"""Build a compact Bloom filter from Goudron/ru-spelling-dictionary CSpell forms.

Source: https://github.com/Goudron/ru-spelling-dictionary
License: MPL-2.0. The generated filter is derived dictionary data.
"""
from __future__ import annotations
import gzip, hashlib, io, pathlib, urllib.request

URL = "https://raw.githubusercontent.com/Goudron/ru-spelling-dictionary/main/cspell/dictionaries/ru_RU.txt.gz"
OUT = pathlib.Path("app/src/main/assets/dictionaries/ru_words.bloom")
BITS = 1 << 27  # 16 MiB; ~57 bits/form for 2.35M forms
HASHES = 10

def norm(s: str) -> str:
    return s.strip().lower().replace("ё", "е")

def main() -> None:
    print("Downloading Russian dictionary…")
    raw = urllib.request.urlopen(URL, timeout=90).read()
    bits = bytearray(BITS // 8)
    count = 0
    with gzip.GzipFile(fileobj=io.BytesIO(raw)) as gz:
        for line in io.TextIOWrapper(gz, encoding="utf-8"):
            word = norm(line)
            if not word or not all(ch.isalpha() or ch in "-'" for ch in word):
                continue
            digest = hashlib.sha256(word.encode("utf-8")).digest()
            h1 = int.from_bytes(digest[0:8], "big")
            h2 = int.from_bytes(digest[8:16], "big") | 1
            for i in range(HASHES):
                bit = (h1 + i * h2) & (BITS - 1)
                bits[bit >> 3] |= 1 << (bit & 7)
            count += 1
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_bytes(bits)
    print(f"Wrote {OUT}: {len(bits)} bytes, {count} accepted forms")

if __name__ == "__main__":
    main()
