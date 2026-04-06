package me.sashegdev.zernmc.launcher.minecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;



// ЭТОТ КЛАСС РАБОТАЕТ НЕ ТРОГАТЬ ТОТ КТО БУДЕТ ЧИТАТЬ (на момент 1.0.2)
public class Instance {
    private final String name;
    private final Path path;

    private String minecraftVersion;
    private String loaderType;      // vanilla, fabric, forge
    private String loaderVersion;
    private String assetIndex;
    private boolean isServerPack;      // флаг, что это сборка с сервера
    private int serverVersion;          // версия сборки на сервере
    private String serverPackName;      // имя пака на сервере
    private String fabricVersionId;

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

    /** Возвращает ТОТ САМЫЙ assetIndex, который сохранился при установке (например 30) */
    public String getAssetIndex() {
        return assetIndex != null ? assetIndex : minecraftVersion; // fallback для старых сборок
    }

    public void setAssetIndex(String assetIndex) {
        this.assetIndex = assetIndex;
        saveMetadata();
    }

    public String getFabricVersionId() {
        return fabricVersionId;
    }

    public void setFabricVersionId(String fabricVersionId) {
        this.fabricVersionId = fabricVersionId;
    }


    public boolean isServerPack() { 
        return isServerPack; 
    }
    
    public void setServerPack(boolean serverPack) {
        this.isServerPack = serverPack;
        saveMetadata();
    }

    public int getServerVersion() { 
        return serverVersion; 
    }
    
    public void setServerVersion(int serverVersion) {
        this.serverVersion = serverVersion;
        saveMetadata();
    }

    public String getServerPackName() { 
        return serverPackName; 
    }
    
    public void setServerPackName(String serverPackName) {
        this.serverPackName = serverPackName;
        saveMetadata();
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder(name);
        if (minecraftVersion != null) {
            sb.append(" [").append(minecraftVersion);
            if (!"vanilla".equalsIgnoreCase(getLoaderType())) {
                sb.append(" + ").append(getLoaderType());
                if (loaderVersion != null) sb.append(" ").append(loaderVersion);
            }
            sb.append("]");
            if (isServerPack) {
                sb.append("v").append(serverVersion);
            }
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
            this.assetIndex = meta.assetIndex;
            this.isServerPack = meta.isServerPack;
            this.serverVersion = meta.serverVersion;
            this.serverPackName = meta.serverPackName;
        } catch (Exception ignored) {}
    }

    private void saveMetadata() {
        Path metaFile = path.resolve("instance.json");
        InstanceMeta meta = new InstanceMeta(
            minecraftVersion, loaderType, loaderVersion, assetIndex,
            isServerPack, serverVersion, serverPackName
        );
        try {
            Files.writeString(metaFile, GSON.toJson(meta));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static class InstanceMeta {
        String minecraftVersion;
        String loaderType;
        String loaderVersion;
        String assetIndex;
        boolean isServerPack = false;
        int serverVersion = 0;
        String serverPackName;

        
        public InstanceMeta(String minecraftVersion, String loaderType,
                            String loaderVersion, String assetIndex,
                            boolean isServerPack, int serverVersion, 
                            String serverPackName) {
            this.minecraftVersion = minecraftVersion;
            this.loaderType = loaderType;
            this.loaderVersion = loaderVersion;
            this.assetIndex = assetIndex;
            this.isServerPack = isServerPack;
            this.serverVersion = serverVersion;
            this.serverPackName = serverPackName;
        }
    }
}