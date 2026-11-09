package com.spinel.melkit.storage;

import android.os.ParcelFileDescriptor;

// Scoped reads, verified restores and journalled mod moves; no overwrite or shell endpoint.
interface IGameLibrary {
    void destroy() = 16777114;
    String list(String storageRoot, String relativePath, int offset) = 1;
    String info(String storageRoot, String relativePath) = 2;
    ParcelFileDescriptor openRead(String storageRoot, String relativePath) = 3;
    byte[] readPreview(String storageRoot, String relativePath) = 4;
    String restoreFile(String storageRoot, String relativePath, in ParcelFileDescriptor source,
        long expectedBytes, String expectedSha) = 5;
    String listMods(String storageRoot, int offset, int filter) = 6;
    String toggleMod(String storageRoot, String relativePath, String vaultId, boolean enable,
        long expectedBytes, long expectedModified) = 7;
    byte[] readModPreview(String storageRoot, String relativePath, String vaultId) = 8;
    String profiles(String storageRoot, String action, String payload) = 9;
    String scanFiles(String storageRoot, String action, String payload) = 10;
    String contentCounts(String storageRoot) = 11;
    String restoreFileAsCopy(String storageRoot, String relativePath, in ParcelFileDescriptor source,
        long expectedBytes, String expectedSha) = 12;
}


