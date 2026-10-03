#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
check_dir=$(mktemp -d)
trap 'rm -rf "$check_dir"' EXIT
javac -encoding UTF-8 -d "$check_dir" app/src/main/java/com/puremusic/app/SongCatalog.java app/src/main/java/com/puremusic/app/RecommendationProvider.java app/src/main/java/com/puremusic/app/RecommendationEngine.java tests/RecommendationEngineTest.java
java -cp "$check_dir" com.puremusic.app.RecommendationEngineTest
