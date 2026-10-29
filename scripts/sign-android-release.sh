#!/usr/bin/env bash
set -euo pipefail
APK=android/app/build/outputs/apk/release/app-release-unsigned.apk
: "${ANDROID_KEYSTORE_BASE64:?Missing ANDROID_KEYSTORE_BASE64}"
: "${ANDROID_KEYSTORE_PASSWORD:?Missing keystore password}"
: "${ANDROID_KEY_ALIAS:?Missing key alias}"
: "${ANDROID_KEY_PASSWORD:?Missing key password}"
: "${ANDROID_SIGNING_CERT_SHA256:?Missing pinned certificate SHA256}"
TASK_KEYSTORE="$(mktemp)"
trap 'rm -f "$TASK_KEYSTORE"' EXIT
chmod 600 "$TASK_KEYSTORE"
printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 --decode > "$TASK_KEYSTORE"
EXPECTED="$(tr -d '[:space:]' < android/signing-certificate.sha256)"
CONFIGURED="$(printf '%s' "$ANDROID_SIGNING_CERT_SHA256" | tr -d '[:space:]:')"
[[ "${CONFIGURED,,}" == "${EXPECTED,,}" ]] || { echo "Configured certificate differs from installed 0.1.5 series"; exit 1; }
ACTUAL="$(keytool -exportcert -keystore "$TASK_KEYSTORE" -alias "$ANDROID_KEY_ALIAS" -storepass:env ANDROID_KEYSTORE_PASSWORD | sha256sum | cut -d ' ' -f 1)"
[[ "${ACTUAL,,}" == "${EXPECTED,,}" ]] || { echo "Keystore certificate mismatch; refusing to change upgrade identity"; exit 1; }
if [[ "${1:-}" == "--check" ]]; then
  echo "Persistent signing configuration verified: $ACTUAL"
  exit 0
fi
SIGNER="$ANDROID_HOME/build-tools/35.0.0/apksigner"
OUT=android/app/build/outputs/apk/release/BeefTV-Mobile.apk
"$SIGNER" sign --ks "$TASK_KEYSTORE" --ks-pass env:ANDROID_KEYSTORE_PASSWORD --key-pass env:ANDROID_KEY_PASSWORD --ks-key-alias "$ANDROID_KEY_ALIAS" --out "$OUT" "$APK"
"$SIGNER" verify --verbose --print-certs "$OUT" > android-signature.txt
ACTUAL="$(sed -n 's/^Signer #1 certificate SHA-256 digest: //p' android-signature.txt)"
[[ "${ACTUAL,,}" == "${EXPECTED,,}" ]] || { rm -f "$OUT"; echo "Signing certificate mismatch"; exit 1; }
cat android-signature.txt

