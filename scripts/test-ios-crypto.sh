#!/bin/sh
# Tests the iOS app's CryptoKit AES-GCM (iosApp/iosApp/VyayaAesGcm.swift) on the Mac: NIST vectors and the spec's vector.
set -e
out="${TMPDIR:-/tmp}/vyaya-aesgcm-tests"
swiftc -O -o "$out" iosApp/iosApp/VyayaAesGcm.swift iosApp/Tests/main.swift
"$out"
