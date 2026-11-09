#!/usr/bin/env bash
set -euo pipefail
melkit_test_dir="$(mktemp -d)"
trap 'rm -rf "$melkit_test_dir"' EXIT
javac -encoding UTF-8 -d "$melkit_test_dir" \
  app/src/main/java/com/spinel/melkit/storage/ContentPaths.java \
  app/src/main/java/com/spinel/melkit/storage/ContentCounts.java \
  app/src/main/java/com/spinel/melkit/push/PlayStoreLinks.java \
  app/src/main/java/com/spinel/melkit/storage/ExportPaths.java \
  app/src/main/java/com/spinel/melkit/storage/PreviewImages.java \
  app/src/main/java/com/spinel/melkit/storage/NewFileRestore.java \
  app/src/main/java/com/spinel/melkit/storage/ModVault.java \
  app/src/main/java/com/spinel/melkit/storage/ModProfiles.java \
  app/src/main/java/com/spinel/melkit/storage/FileScan.java \
  app/src/main/java/com/spinel/melkit/share/ShareWire.java \
  app/src/main/java/com/spinel/melkit/share/ContentBundle.java \
  app/src/main/java/com/spinel/melkit/share/BatchRestore.java \
  tests/ContentPathsCheck.java tests/ExportPathsCheck.java tests/PreviewImagesCheck.java tests/RestoreCheck.java \
  tests/ModVaultCheck.java tests/ModProfilesCheck.java tests/FileScanCheck.java tests/ShareWireCheck.java \
  tests/ContentBundleCheck.java tests/BatchRestoreCheck.java tests/ContentCountsCheck.java tests/RestoreCopyCheck.java tests/PlayStoreLinksCheck.java
java -cp "$melkit_test_dir" ContentPathsCheck
java -cp "$melkit_test_dir" ExportPathsCheck
java -cp "$melkit_test_dir" PreviewImagesCheck
java -cp "$melkit_test_dir" RestoreCheck
java -cp "$melkit_test_dir" ModVaultCheck
java -cp "$melkit_test_dir" ModProfilesCheck
java -cp "$melkit_test_dir" com.spinel.melkit.storage.FileScanCheck
java -cp "$melkit_test_dir" ShareWireCheck
java -cp "$melkit_test_dir" com.spinel.melkit.share.ContentBundleCheck
java -cp "$melkit_test_dir" BatchRestoreCheck
java -cp "$melkit_test_dir" ContentCountsCheck
java -cp "$melkit_test_dir" RestoreCopyCheck
java -cp "$melkit_test_dir" PlayStoreLinksCheck


