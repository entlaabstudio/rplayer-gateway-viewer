#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

VERSION_BUILD="${1:-}"
APKSIGNER="${APKSIGNER:-$HOME/Android/build-tools/35.0.0/apksigner}"
KEYSTORE="${KEYSTORE:-$HOME/Android/keystores/unexpected-tracks-release-100y.jks}"
KEY_ALIAS="${KEY_ALIAS:-unexpected-tracks}"

if [[ -z "$VERSION_BUILD" ]]; then
    echo "Usage: $0 <version-build>"
    echo "Example: $0 0.1.3-build-34"
    exit 1
fi

if [[ ! "$VERSION_BUILD" =~ ^[0-9]+\.[0-9]+\.[0-9]+-build-[0-9]+$ ]]; then
    echo "Invalid version-build value: $VERSION_BUILD"
    echo "Expected format: 0.1.3-build-34"
    exit 1
fi

if [[ ! -x "$APKSIGNER" ]]; then
    echo "apksigner was not found or is not executable: $APKSIGNER"
    exit 1
fi

if [[ ! -f "$KEYSTORE" ]]; then
    echo "Keystore was not found: $KEYSTORE"
    exit 1
fi

passwordCreatedByScript=0
if [[ -z "${APK_SIGNING_PASSWORD+x}" ]]; then
    read -r -s -p "Keystore password: " APK_SIGNING_PASSWORD
    echo
    export APK_SIGNING_PASSWORD
    passwordCreatedByScript=1
fi

cleanup() {
    if [[ "$passwordCreatedByScript" -eq 1 ]]; then
        unset APK_SIGNING_PASSWORD
    fi
}
trap cleanup EXIT

FLAVORS=(
    "unexpectedTracks"
    "dreamer"
    "krehkyMechanismus"
)

signApk() {
    local flavorName="$1"
    local unsignedApk="${flavorName}-${VERSION_BUILD}-${flavorName}Release.apk"
    local signedApk="${flavorName}-${VERSION_BUILD}-release-signed.apk"
    local unsignedPath="$PROJECT_ROOT/app/build/outputs/apk/$flavorName/release/$unsignedApk"
    local signedPath="$PROJECT_ROOT/app/build/outputs/apk/$flavorName/release/$signedApk"

    if [[ ! -f "$unsignedPath" ]]; then
        echo "Missing release APK: $unsignedPath"
        exit 1
    fi

    echo "Signing $signedApk"
    "$APKSIGNER" sign \
        --ks "$KEYSTORE" \
        --ks-key-alias "$KEY_ALIAS" \
        --ks-pass env:APK_SIGNING_PASSWORD \
        --out "$signedPath" \
        "$unsignedPath"
}

for flavorName in "${FLAVORS[@]}"; do
    signApk "$flavorName"
done

echo "Signed APK files are ready."
