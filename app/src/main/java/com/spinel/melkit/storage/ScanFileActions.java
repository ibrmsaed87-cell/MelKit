package com.spinel.melkit.storage;

import java.io.*;
import java.util.UUID;

/** Explicit scan actions. All writes are verified copies; existing names are never replaced. */
public final class ScanFileActions {
    private ScanFileActions() {}

    public static File backup(FileScan scanner, FileScan.Finding finding, File backupRoot) throws Exception {
        File source = scanner.verifiedFile(finding);
        File folder = new File(backupRoot, UUID.randomUUID().toString());
        if (!folder.mkdirs() || !folder.equals(folder.getCanonicalFile())) throw new IOException("Cannot create backup");
        File target = new File(folder, source.getName());
        String hash = ModVault.hash(source, finding.bytes);
        try (InputStream input = new FileInputStream(source); FileOutputStream output = new FileOutputStream(target)) {
            byte[] buffer = new byte[65536]; long size = 0; int count;
            while ((count = input.read(buffer)) != -1) {
                size += count;
                if (size > finding.bytes) throw new ModVault.Changed();
                output.write(buffer, 0, count);
            }
            output.getFD().sync();
        }
        if (!hash.equals(ModVault.hash(target, finding.bytes))) throw new ModVault.Changed();
        scanner.verifiedFile(finding);
        if (!hash.equals(ModVault.hash(source, finding.bytes))) throw new ModVault.Changed();
        return target;
    }

    public static String rename(File gameRoot, FileScan scanner, FileScan.Finding finding,
            String stem, File backupRoot) throws Exception {
        if (stem == null || stem.trim().isEmpty() || stem.length() > 120 || stem.equals(".") || stem.equals("..")
                || stem.chars().anyMatch(c -> c < 32 || c == '/' || c == '\\')) throw new IOException("Invalid name");
        File source = scanner.verifiedFile(finding);
        String name = source.getName(); int dot = name.lastIndexOf('.');
        String extension = dot > 0 ? name.substring(dot) : "";
        String path = finding.path.substring(0, finding.path.lastIndexOf('/') + 1) + stem.trim() + extension;
        File target = new ContentPaths(gameRoot).resolve(path, false);
        File[] siblings = source.getParentFile().listFiles();
        if (siblings == null) throw new IOException("Cannot read directory");
        for (File sibling : siblings) if (sibling.getName().equalsIgnoreCase(target.getName())) throw new ModVault.Conflict();
        backup(scanner, finding, backupRoot);
        String hash = ModVault.hash(source, finding.bytes);
        try (InputStream input = new FileInputStream(source)) {
            NewFileRestore.restoreQuarantined(gameRoot, path, input, finding.bytes, hash);
        }
        scanner.verifiedFile(finding);
        if (!hash.equals(ModVault.hash(source, finding.bytes))) throw new ModVault.Changed();
        if (!source.delete() || source.exists()) throw new ModVault.Incomplete();
        return path;
    }

    public static File delete(FileScan scanner, FileScan.Finding finding, File backupRoot) throws Exception {
        File saved = backup(scanner, finding, backupRoot);
        File source = scanner.verifiedFile(finding);
        if (!ModVault.hash(saved, finding.bytes).equals(ModVault.hash(source, finding.bytes))) throw new ModVault.Changed();
        if (!source.delete() || source.exists()) throw new ModVault.Incomplete();
        return saved;
    }
}
