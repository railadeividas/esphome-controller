#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
docker build -t esphome-android-build -f docker/Dockerfile .
mkdir -p "${TMPDIR:-/tmp}/esphome-gradle-cache"
docker run --rm -u "$(id -u):$(id -g)" -e HOME=/gradle-cache -e GRADLE_USER_HOME=/gradle-cache -v "${TMPDIR:-/tmp}/esphome-gradle-cache:/gradle-cache" -v "$PWD:/workspace" esphome-android-build gradle --no-daemon testDebugUnitTest assembleDebug
