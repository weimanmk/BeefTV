#!/usr/bin/env bash
set -euo pipefail
APK=android/app/build/outputs/apk/release/app-release-unsigned.apk
if [[ -z "${ANDROID_KEYSTORE_BASE64:-}" ]]; then
  echo "No persistent signing secret configured; release output remains UNSIGNED."
  exit 0
fi
: "${ANDROID_KEYSTORE_PASSWORD:?Missing keystore password}"
: "${ANDROID_KEY_ALIAS:?Missing key alias}"
: "${ANDROID_KEY_PASSWORD:?Missing key password}"
: "${ANDROID_SIGNING_CERT_SHA256:?Missing pinned certificate SHA256}"
TASK_KEYSTORE="$(mktemp)"
trap 'rm -f "$TASK_KEYSTORE"' EXIT
chmod 600 "$TASK_KEYSTORE"
printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 --decode > "$TASK_KEYSTORE"
SIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
OUT=android/app/build/outputs/apk/release/BeefTV-Mobile.apk
"$SIGNER" sign --ks "$TASK_KEYSTORE" --ks-pass env:ANDROID_KEYSTORE_PASSWORD --key-pass env:ANDROID_KEY_PASSWORD --ks-key-alias "$ANDROID_KEY_ALIAS" --out "$OUT" "$APK"
"$SIGNER" verify --verbose --print-certs "$OUT" > android-signature.txt
ACTUAL="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' android-signature.txt)"
[[ "${ACTUAL,,}" == "${ANDROID_SIGNING_CERT_SHA256,,}" ]] || { rm -f "$OUT"; echo "Signing certificate mismatch"; exit 1; }
cat android-signature.txt

