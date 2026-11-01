#!/usr/bin/env bash
set -euo pipefail

echo "============================================================"
echo " 1. Secret Leak Scan (Source & Docs)"
echo "============================================================"
if grep -rInE --exclude-dir=build --exclude-dir=.gradle "AIza|AQ\." app/src/ docs/; then
    echo "ERROR: Potential hardcoded API key detected in repository!"
    exit 1
else
    echo "OK: Zero hardcoded API keys found in app/src/ or docs/."
fi

echo "============================================================"
echo " 2. Gradle Wrapper Verification"
echo "============================================================"
if [ -x "./gradlew" ]; then
    GRADLE_CMD="./gradlew"
else
    GRADLE_CMD="gradle"
fi
$GRADLE_CMD --version

echo "============================================================"
echo " 3. Build Debug APK, Unit Tests, and Lint"
echo "============================================================"
$GRADLE_CMD assembleDebug testDebugUnitTest lintDebug

echo "============================================================"
echo " 4. Inspect Debug APK for Embedded Secrets"
echo "============================================================"
DEBUG_APK="app/build/outputs/apk/debug/app-debug.apk"
if [ -f "$DEBUG_APK" ]; then
    python3 -c '
import zipfile, re, sys
apk = sys.argv[1]
pat = re.compile(rb"(AIza[0-9A-Za-z\-_]{20,}|AQ\.[0-9A-Za-z\-_]{15,})")
with zipfile.ZipFile(apk, "r") as zf:
    for name in zf.namelist():
        if name.endswith(".dex"):
            data = zf.read(name)
            m = pat.search(data)
            if m:
                print(f"ERROR: Found secret pattern in {name}")
                sys.exit(1)
print("OK: Zero secret patterns found in DEX files of", apk)
' "$DEBUG_APK"
fi

echo "============================================================"
echo " Verification Completed Successfully"
echo "============================================================"
