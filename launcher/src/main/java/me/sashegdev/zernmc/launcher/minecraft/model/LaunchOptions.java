package me.sashegdev.zernmc.launcher.minecraft.model;

import java.util.ArrayList;
import java.util.List;

public class LaunchOptions {
    private String username = "Player";
    private String uuid = "00000000-0000-0000-0000-000000000000";
    private String accessToken = "token";
    private int maxMemory = 4096;
    private boolean fullscreen = false;
    private String javaPath = "java";
    private List<String> extraJvmArgs = new ArrayList<>();
    private int width = 854;
    private int height = 480;

    // Геттеры и сеттеры
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getUuid() { return uuid; }
    public void setUuid(String uuid) { this.uuid = uuid; }

    public String getAccessToken() { return accessToken; }
    public void setAccessToken(String accessToken) { this.accessToken = accessToken; }

    public int getMaxMemory() { return maxMemory; }
    public void setMaxMemory(int maxMemory) { this.maxMemory = maxMemory; }

    public boolean isFullscreen() { return fullscreen; }
    public void setFullscreen(boolean fullscreen) { this.fullscreen = fullscreen; }

    public String getJavaPath() { return javaPath; }
    public void setJavaPath(String javaPath) { this.javaPath = javaPath; }

    public List<String> getExtraJvmArgs() { return extraJvmArgs; }
    public void setExtraJvmArgs(List<String> extraJvmArgs) { this.extraJvmArgs = extraJvmArgs; }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
}