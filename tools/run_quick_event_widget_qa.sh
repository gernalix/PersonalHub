#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."
./gradlew :feature:multitimetracker:testDebugUnitTest --tests com.example.multitimetracker.widget.QuickEventWidgetTapRunnerTest
