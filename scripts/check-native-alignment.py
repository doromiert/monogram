#!/usr/bin/env python3
"""Reject 64-bit APK libraries that require Android's 16 KB compatibility mode."""
import struct
import sys
import zipfile
from pathlib import Path


def check(name, data):
    if data[:5] != b'\x7fELF\x02':
        return
    endian = '<' if data[5] == 1 else '>'
    offset = struct.unpack_from(endian + 'Q', data, 32)[0]
    size, count = struct.unpack_from(endian + 'HH', data, 54)
    loads = [struct.unpack_from(endian + 'IIQQQQQQ', data, offset + i * size)
             for i in range(count)]
    for segment in loads:
        if segment[0] == 1:
            assert segment[7] >= 16384, f'{name}: LOAD alignment is {segment[7]}, expected >=16384'
            assert segment[2] % 16384 == segment[3] % 16384, f'{name}: LOAD offset/address mismatch'
    print(f'{name}: 16 KB aligned')


for arg in sys.argv[1:]:
    path = Path(arg)
    if zipfile.is_zipfile(path):
        with zipfile.ZipFile(path) as archive:
            for name in archive.namelist():
                if name.startswith('lib/') and name.endswith('.so'):
                    check(name, archive.read(name))
    else:
        check(path.name, path.read_bytes())
