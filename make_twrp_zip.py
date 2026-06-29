#!/usr/bin/env python3
"""
Flyme 8 Mi 5s - NFC & Fingerprint Fix TWRP Zip Builder
======================================================
Packages Flyme8_Fix_TWRP into a flashable TWRP zip.
Output: Flyme8_Mi5s_Fix_NFC_FP_TWRP_v1.0.zip

Usage: python make_twrp_zip.py
"""

import zipfile
import os
import sys

sys.stdout.reconfigure(encoding='utf-8')

src_dir = r'd:\MD3L1\Flyme8_Fix_TWRP'
out_zip = r'd:\MD3L1\Flyme8_Mi5s_Fix_NFC_FP_TWRP_v1.0.zip'

required_files = [
    'META-INF/com/google/android/update-binary',
    'META-INF/com/google/android/updater-script',
    'install.sh',
]

print("=== Flyme 8 Fix TWRP Zip Builder ===")
print()
print(f"Source: {src_dir}")
print(f"Output: {out_zip}")
print()

print("Checking required files...")
all_ok = True
for rf in required_files:
    rf_path = os.path.join(src_dir, rf)
    if os.path.exists(rf_path):
        size = os.path.getsize(rf_path)
        print(f"  [OK] {rf} ({size} bytes)")
    else:
        print(f"  [XX] {rf} - MISSING!")
        all_ok = False

if not all_ok:
    print()
    print("ERROR: Required files are missing!")
    exit(1)

print()
print("All required files present.")
print()

if os.path.exists(out_zip):
    os.remove(out_zip)
    print(f"Removed existing: {out_zip}")

print()
print(f"Creating TWRP zip: {out_zip}")
with zipfile.ZipFile(out_zip, 'w', zipfile.ZIP_DEFLATED) as zf:
    file_count = 0
    for root, dirs, files in os.walk(src_dir):
        for fn in files:
            full_path = os.path.join(root, fn)
            rel_path = os.path.relpath(full_path, src_dir)
            rel_path = rel_path.replace('\\', '/')
            print(f'  + {rel_path} ({os.path.getsize(full_path)} bytes)')
            zf.write(full_path, rel_path)
            file_count += 1

print()
print(f'=== Zip created successfully! ===')
print(f'  Total files: {file_count}')
print(f'  Path: {out_zip}')
print(f'  Size: {os.path.getsize(out_zip)} bytes')
print()

print('Contents verification:')
with zipfile.ZipFile(out_zip, 'r') as zf:
    for info in zf.infolist():
        ratio = (1 - info.compress_size / info.file_size) * 100 if info.file_size > 0 else 0
        print(f'  {info.filename} ({info.file_size}B -> {info.compress_size}B, ratio: {ratio:.0f}%)')

print()
print(f'Total compressed: {os.path.getsize(out_zip)} bytes')
print('Done!')
