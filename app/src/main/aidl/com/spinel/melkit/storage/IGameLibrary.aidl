package com.spinel.melkit.storage;

import android.os.ParcelFileDescriptor;

// Read-only bridge. No shell commands, writes, renames, or deletion endpoints.
interface IGameLibrary {
    void destroy() = 16777114;
    String list(String storageRoot, String relativePath, int offset) = 1;
    String info(String storageRoot, String relativePath) = 2;
    ParcelFileDescriptor openRead(String storageRoot, String relativePath) = 3;
    byte[] readPreview(String storageRoot, String relativePath) = 4;
}

