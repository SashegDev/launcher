package me.sashegdev.zernmc.launcher.minecraft;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class ServerPack {
    private final String name;
    private final int version;
    private final String minecraftVersion;
    private final String loaderType;
    private final String loaderVersion;
    private final LocalDateTime updatedAt;
    private final int filesCount;

    public ServerPack(String name, int version, String minecraftVersion, 
                      String loaderType, String loaderVersion, 
                      LocalDateTime updatedAt, int filesCount) {
        this.name = name;
        this.version = version;
        this.minecraftVersion = minecraftVersion;
        this.loaderType = loaderType;
        this.loaderVersion = loaderVersion;
        this.updatedAt = updatedAt;
        this.filesCount = filesCount;
    }

    public String getName() { return name; }
    public int getVersion() { return version; }
    public String getMinecraftVersion() { return minecraftVersion; }
    public String getLoaderType() { return loaderType; }
    public String getLoaderVersion() { return loaderVersion; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public int getFilesCount() { return filesCount; }

    @Override
    public String toString() {
        if (updatedAt != null) {
            return String.format("%s [%s + %s v%d] - %d файлов (обновлен: %s)", 
                name, minecraftVersion, loaderType, version, filesCount,
                updatedAt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")));
        } else {
            return String.format("%s [%s + %s v%d] - %d файлов", 
                name, minecraftVersion, loaderType, version, filesCount);
        }
    }
}