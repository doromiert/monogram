#!/usr/bin/env python3
"""Validate the draft set using pinned XSF DTD/entities and Java's XML validator.

Requires Python >=3.11 and Java >=21. First use fetches two public XSF files;
subsequent runs are offline and verify their recorded SHA-256 hashes.
"""
import argparse
import hashlib
import json
import re
import xml.etree.ElementTree as ET
import os
from pathlib import Path
import shutil
import subprocess
import urllib.request

ROOT = Path(__file__).resolve().parent

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--java', default=os.environ.get('JAVA_HOME', '') + '/bin/java' if os.environ.get('JAVA_HOME') else shutil.which('java'))
    parser.add_argument('--cache-dir', type=Path, default=ROOT.parent / '.state' / 'xep-authoring')
    args = parser.parse_args()
    if not args.java:
        parser.error('Java 21+ is required; pass --java /path/to/java')
    lock = json.loads((ROOT / 'xsf-tooling.json').read_text())
    args.cache_dir.mkdir(parents=True, exist_ok=True)
    for name, digest in lock['sha256'].items():
        target = args.cache_dir / name
        if not target.exists():
            url = f"https://raw.githubusercontent.com/xsf/xeps/{lock['revision']}/{name}"
            data = urllib.request.urlopen(url, timeout=30).read()
            if hashlib.sha256(data).hexdigest() != digest:
                raise ValueError(f'Unexpected XSF {name} hash')
            target.write_bytes(data)
        if hashlib.sha256(target.read_bytes()).hexdigest() != digest:
            raise ValueError(f'Cached XSF {name} differs from pinned source')
    manifest = json.loads((ROOT / 'manifest.json').read_text())
    declared = {row['path'] for row in manifest['drafts']}
    actual = {str(path.relative_to(ROOT)) for path in ROOT.rglob('xep-*.xml')}
    if declared != actual:
        raise ValueError(f'Manifest mismatch: missing={declared-actual}; unlisted={actual-declared}')
    for row in manifest['drafts']:
        if row['deferred'] != row['path'].startswith('deferred/'):
            raise ValueError(f"Deferred classification mismatch: {row['path']}")
    examples = 0
    source_links = 0
    prefix = f"https://github.com/doromiert/monogram/blob/{manifest['audited_commit']}/"
    for relative in sorted(actual):
        text = (ROOT / relative).read_text()
        # The full Java pass resolves the pinned entities and validates the DTD.
        # This auxiliary pass only inspects our content, without external entities.
        content = re.sub(r'<!DOCTYPE.*?\]>', '', text, count=1, flags=re.S).replace('&LEGALNOTICE;', '')
        document = ET.fromstring(content)
        for node in document.findall('.//example'):
            value = ''.join(node.itertext()).strip()
            if value.startswith('<'):
                ET.fromstring('<examples>' + value + '</examples>')
                examples += 1
            elif value.startswith('{'):
                json.loads(value)
                examples += 1
        for link in document.findall('.//link'):
            url = link.get('url', '')
            if url.startswith(prefix):
                path = ROOT.parent / url[len(prefix):]
                if not path.is_file():
                    raise ValueError(f'Missing audited implementation source: {path}')
                source_links += 1
    subprocess.run([args.java, str(ROOT / 'tools' / 'ValidateXeps.java'), str(args.cache_dir), str(ROOT)], check=True)
    print(f'{examples} XML/JSON example blocks and {source_links} pinned implementation references checked.')
    print(f"Manifest and {len(actual)} draft documents valid; no submission performed.")

if __name__ == '__main__':
    main()
