# AndroidX/Compose consumer rules and manifest entry points are supplied by AGP.
# Shizuku loads this Binder class and its public constructor by name.
-keep class com.spinel.melkit.storage.GameLibraryService { *; }
