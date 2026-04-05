package me.sashegdev.zernmc.launcher.minecraft.model;

import java.time.LocalDateTime;

public class MinecraftVersion {
    private final String id;
    private final String type; // release, snapshot, old_beta, old_alpha
    private final LocalDateTime releaseTime;
    private final String url;

    public MinecraftVersion(String id, String type, LocalDateTime releaseTime, String url) {
        this.id = id;
        this.type = type;
        this.releaseTime = releaseTime;
        this.url = url;
    }

    public String getId() { return id; }
    public String getType() { return type; }
    public LocalDateTime getReleaseTime() { return releaseTime; }
    public String getUrl() { return url; }

    @Override
    public String toString() {
        return id + " (" + type + ")";
    }
}