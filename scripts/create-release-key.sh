#!/usr/bin/env bash
# Local-only signing material; never add .signing to Git. Back it up privately.
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ -e .signing/release.properties ]]; then echo "Release signing configuration already exists."; exit 0; fi
umask 077
mkdir -p .signing
password="$(openssl rand -hex 24)"
keytool -genkeypair -keystore .signing/prism-release.jks -storepass "$password" -keypass "$password" \
  -alias prism -keyalg RSA -keysize 3072 -validity 10000 -dname 'CN=Prism Task, O=Prism Task' >/dev/null 2>&1
printf 'storeFile=.signing/prism-release.jks\nstorePassword=%s\nkeyAlias=prism\nkeyPassword=%s\n' "$password" "$password" > .signing/release.properties
unset password
echo "Local release key created in ignored .signing/. Keep a private backup for future upgrades."
