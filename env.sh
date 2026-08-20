#!/usr/bin/env bash
# Environment for building LandPoint with the toolchain installed under $HOME.
export JAVA_HOME="$(echo "$HOME"/android-tools/jdk-17*)"
export ANDROID_HOME="$HOME/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$HOME/gradle-8.11.1/bin:$PATH"
export GRADLE_USER_HOME="${GRADLE_USER_HOME:-$HOME/.gradle}"
