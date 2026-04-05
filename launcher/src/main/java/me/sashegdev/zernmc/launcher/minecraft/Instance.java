package me.sashegdev.zernmc.launcher.minecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class Instance {

    private final String name;
    private final Path path;

    private String minecraftVersion;
    private String loaderType;      // vanilla, fabric, forge
    private String loaderVersion;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public Instance(String name, Path path) {
        this.name = name;
        this.path = path;
        loadMetadata();
    }

    public String getName() { return name; }
    public Path getPath() { return path; }

    public String getMinecraftVersion() { return minecraftVersion; }
    public void setMinecraftVersion(String minecraftVersion) { 
        this.minecraftVersion = minecraftVersion; 
        saveMetadata();
    }

    public String getLoaderType() { return loaderType != null ? loaderType : "vanilla"; }
    public void setLoaderType(String loaderType) { 
        this.loaderType = loaderType; 
        saveMetadata();
    }

    public String getLoaderVersion() { return loaderVersion; }
    public void setLoaderVersion(String loaderVersion) { 
        this.loaderVersion = loaderVersion; 
        saveMetadata();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(name);

        if (minecraftVersion != null) {
            sb.append(" [").append(minecraftVersion);

            if (!"vanilla".equalsIgnoreCase(getLoaderType())) {
                sb.append(" + ").append(getLoaderType());
                if (loaderVersion != null) {
                    sb.append(" ").append(loaderVersion);
                }
            }
            sb.append("]");
        } else {
            sb.append(" [?]");
        }

        return sb.toString();
    }

    // ====================== Метаданные ======================

    private void loadMetadata() {
        Path metaFile = path.resolve("instance.json");
        if (!Files.exists(metaFile)) return;

        try {
            String json = Files.readString(metaFile);
            InstanceMeta meta = GSON.fromJson(json, InstanceMeta.class);

            this.minecraftVersion = meta.minecraftVersion;
            this.loaderType = meta.loaderType;
            this.loaderVersion = meta.loaderVersion;
        } catch (Exception e) {
            // игнорируем, если файл повреждён
        }
    }

    private void saveMetadata() {
        Path metaFile = path.resolve("instance.json");
        InstanceMeta meta = new InstanceMeta(minecraftVersion, loaderType, loaderVersion);

        try {
            Files.writeString(metaFile, GSON.toJson(meta));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    // Внутренний класс для сериализации
    private static class InstanceMeta {
        String minecraftVersion;
        String loaderType;
        String loaderVersion;

        public InstanceMeta(String minecraftVersion, String loaderType, String loaderVersion) {
            this.minecraftVersion = minecraftVersion;
            this.loaderType = loaderType;
            this.loaderVersion = loaderVersion;
        }
    }
}