package com.spinel.melkit.storage;

import android.os.ParcelFileDescriptor;

// Scoped reads and verified restore to a new content filename; no overwrite or shell endpoint.
interface IGameLibrary {
    void destroy() = 16777114;
    String list(String storageRoot, String relativePath, int offset) = 1;
    String info(String storageRoot, String relativePath) = 2;
    ParcelFileDescriptor openRead(String storageRoot, String relativePath) = 3;
    byte[] readPreview(String storageRoot, String relativePath) = 4;
    String restoreFile(String storageRoot, String relativePath, in ParcelFileDescriptor source,
        long expectedBytes, String expectedSha) = 5;
}

