#!/usr/bin/env sh
set -eu
cd "$(dirname "$0")"
jar=test-platform-deploy/target/probeloom.jar
if [ -f probeloom.jar ]; then jar=probeloom.jar; fi
exec java "-Dprobeloom.home=$PWD" -jar "$jar" "$@"
