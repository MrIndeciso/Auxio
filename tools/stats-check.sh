#!/bin/sh
set -eu
export JAVA_HOME=/home/mrindeciso/Applications/android-studio/jbr
export AUXIO_MIGRATION_FIXTURES="$PWD/release/migration-fixtures"
exec ./gradlew "$@"
