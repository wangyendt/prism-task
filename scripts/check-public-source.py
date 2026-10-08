#!/usr/bin/env python3
"""Small guardrail for committed files; not a replacement for a full secret scanner."""
import pathlib, re, subprocess, sys
root=pathlib.Path(__file__).resolve().parents[1]
files=subprocess.check_output(['git','ls-files','-z'],cwd=root).decode().split('\0')
patterns=[rb'ctt_[A-Za-z0-9_-]{20,}',rb'gh[pousr]_[A-Za-z0-9_]{20,}',rb'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----']
blocked=[]
for name in files:
    if not name: continue
    path=root/name
    if not path.is_file(): continue
    if any(part in ('.signing',) for part in path.parts) or path.suffix in ('.jks','.keystore','.env','.token','.apk'):
        blocked.append(name);continue
    data=path.read_bytes()
    if any(re.search(pattern,data) for pattern in patterns): blocked.append(name)
if blocked:
    print('Potential private data in these tracked files (values suppressed):\n'+'\n'.join(blocked));sys.exit(1)
print('Public-source guard passed. No credential values were printed.')
