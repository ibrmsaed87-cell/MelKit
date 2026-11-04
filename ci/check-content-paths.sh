#!/usr/bin/env bash
set -euo pipefail
melkit_test_dir="$(mktemp -d)"
trap 'rm -rf "$melkit_test_dir"' EXIT
javac -encoding UTF-8 -d "$melkit_test_dir" \
  app/src/main/java/com/spinel/melkit/storage/ContentPaths.java \
  app/src/main/java/com/spinel/melkit/storage/ExportPaths.java \
  app/src/main/java/com/spinel/melkit/storage/PreviewImages.java \
  app/src/main/java/com/spinel/melkit/storage/NewFileRestore.java \
  app/src/main/java/com/spinel/melkit/storage/ModVault.java \
  app/src/main/java/com/spinel/melkit/storage/ModProfiles.java \
  tests/ContentPathsCheck.java tests/ExportPathsCheck.java tests/PreviewImagesCheck.java tests/RestoreCheck.java tests/ModVaultCheck.java tests/ModProfilesCheck.java
java -cp "$melkit_test_dir" ContentPathsCheck
java -cp "$melkit_test_dir" ExportPathsCheck
java -cp "$melkit_test_dir" PreviewImagesCheck
java -cp "$melkit_test_dir" RestoreCheck
java -cp "$melkit_test_dir" ModVaultCheck
java -cp "$melkit_test_dir" ModProfilesCheck


