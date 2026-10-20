#!/usr/bin/env python3
"""Regenerate offline native emoji search data from Unicode CLDR 48 (Unicode-3.0)."""
from pathlib import Path
from collections import defaultdict
from urllib.request import urlopen
import xml.etree.ElementTree as ET

REVISION = "acd6d88ae493633240e19a87a721076a8a75c310"
BASE = f"https://raw.githubusercontent.com/unicode-org/cldr/{REVISION}/"
ASSETS = Path(__file__).resolve().parents[1] / "TMessagesProj/src/main/assets"
for language in ("en", "pl", "ro"):
    entries = defaultdict(set)
    for directory in ("annotations", "annotationsDerived"):
        xml = urlopen(BASE + f"common/{directory}/{language}.xml").read()
        for annotation in ET.fromstring(xml).iter("annotation"):
            emoji = annotation.attrib["cp"]
            for keyword in (annotation.text or "").split("|"):
                keyword = keyword.strip().lower()
                if keyword and keyword != "↑↑↑":
                    assert not any(c in keyword for c in "\t\n\r")
                    entries[keyword].add(emoji)
    assert len(entries) > 1000
    data = "".join(keyword + "\t" + "\t".join(sorted(emojis)) + "\n" for keyword, emojis in sorted(entries.items()))
    (ASSETS / f"emoji-keywords-{language}.tsv").write_text(data, encoding="utf-8")
    print(language, len(entries), "keywords")
(ASSETS / "emoji-keywords-LICENSE.txt").write_bytes(urlopen(BASE + "LICENSE").read())
