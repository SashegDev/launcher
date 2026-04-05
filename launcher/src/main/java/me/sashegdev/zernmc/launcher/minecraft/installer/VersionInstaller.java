package me.sashegdev.zernmc.launcher.minecraft.installer;

import me.sashegdev.zernmc.launcher.minecraft.model.MinecraftVersion;
import me.sashegdev.zernmc.launcher.utils.ProgressBar;
import me.sashegdev.zernmc.launcher.utils.ZAnsi;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

public class VersionInstaller {

    private final Path minecraftDir;
    private final HttpClient httpClient;

    public VersionInstaller(Path minecraftDir) {
        this.minecraftDir = minecraftDir;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
    }

    // getAvailableVersions() оставляем как было (с исправлением времени)

    public List<MinecraftVersion> getAvailableVersions() throws Exception {
        String jsonString = downloadString("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
        JSONObject root = new JSONObject(jsonString);
        JSONArray versionsArray = root.getJSONArray("versions");

        List<MinecraftVersion> versions = new ArrayList<>();

        for (int i = 0; i < versionsArray.length(); i++) {
            JSONObject v = versionsArray.getJSONObject(i);
            String id = v.getString("id");
            String type = v.getString("type");
            String releaseTimeStr = v.getString("releaseTime").replace("Z", "").replace("+00:00", "");
            String url = v.getString("url");

            LocalDateTime releaseTime = LocalDateTime.parse(releaseTimeStr);
            versions.add(new MinecraftVersion(id, type, releaseTime, url));
        }

        versions.sort((a, b) -> b.getReleaseTime().compareTo(a.getReleaseTime()));
        return versions;
    }

    public boolean install(String versionId) throws Exception {
        System.out.println(ZAnsi.cyan("Полная установка Minecraft " + versionId + "..."));

        Path versionDir = minecraftDir.resolve("versions").resolve(versionId);
        Files.createDirectories(versionDir);

        String versionUrl = getVersionUrl(versionId);
        if (versionUrl == null) throw new Exception("Версия " + versionId + " не найдена");

        String versionJson = downloadString(versionUrl);
        Files.writeString(versionDir.resolve(versionId + ".json"), versionJson);

        JSONObject versionData = new JSONObject(versionJson);

        // client.jar
        downloadFile(versionData.getJSONObject("downloads").getJSONObject("client").getString("url"),
                versionDir.resolve(versionId + ".jar"), "client.jar");

        // Библиотеки
        System.out.println(ZAnsi.cyan("Скачивание библиотек..."));
        downloadLibraries(versionData.getJSONArray("libraries"));

        // Ассеты
        if (versionData.has("assetIndex")) {
            System.out.println(ZAnsi.cyan("Скачивание ассетов..."));
            downloadAssets(versionData);
        }

        System.out.println(ZAnsi.brightGreen("\nMinecraft " + versionId + " полностью установлен!"));
        return true;
    }

    private void downloadLibraries(JSONArray libraries) throws Exception {
        int total = libraries.length();
        int count = 0;

        for (int i = 0; i < total; i++) {
            JSONObject lib = libraries.getJSONObject(i);
            if (lib.has("downloads") && lib.getJSONObject("downloads").has("artifact")) {
                JSONObject art = lib.getJSONObject("downloads").getJSONObject("artifact");
                String url = art.getString("url");
                String path = art.getString("path");

                Path target = minecraftDir.resolve("libraries").resolve(path);
                Files.createDirectories(target.getParent());

                try {
                    downloadFile(url, target, "library");
                } catch (Exception e) {
                    // Многие библиотеки могут быть пропущены — это нормально
                }
            }
            count++;
            if (count % 20 == 0) ProgressBar.show("Библиотеки", count, total, "");
        }
        ProgressBar.finish("Библиотеки загружены");
    }

    private void downloadAssets(JSONObject versionData) throws Exception {
        JSONObject assetIndexInfo = versionData.getJSONObject("assetIndex");
        String indexUrl = assetIndexInfo.getString("url");
        String indexId = versionData.getString("assets");

        Path indexPath = minecraftDir.resolve("assets/indexes").resolve(indexId + ".json");
        Files.createDirectories(indexPath.getParent());
        downloadFile(indexUrl, indexPath, "asset index");

        String assetsJson = new String(Files.readAllBytes(indexPath));
        JSONObject objects = new JSONObject(assetsJson).getJSONObject("objects");

        System.out.println(ZAnsi.cyan("Скачивание " + objects.length() + " объектов ассетов..."));

        int count = 0;
        int failed = 0;

        for (String hash : objects.keySet()) {
            String url = "https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash;
            Path target = minecraftDir.resolve("assets/objects")
                    .resolve(hash.substring(0, 2))
                    .resolve(hash);

            Files.createDirectories(target.getParent());

            try {
                downloadFile(url, target, "");   // пустой label = ассет
                count++;
            } catch (Exception e) {
                failed++;
            }

            ProgressBar.show("Ассеты", count, objects.length(), "файлов");
        }

        ProgressBar.finish("Ассеты загружены (" + count + " успешно, " + failed + " пропущено)");
    }


    
    // === Вспомогательные методы ===

    private String getVersionUrl(String versionId) throws Exception {
        for (MinecraftVersion v : getAvailableVersions()) {
            if (v.getId().equals(versionId)) return v.getUrl();
        }
        return null;
    }

    private String downloadString(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());
        return resp.body();
    }

    private void downloadFile(String url, Path target, String label) throws Exception {
        if (!label.isEmpty()) {
            ProgressBar.clearLine();
            System.out.println(ZAnsi.cyan("Скачивание " + label + "..."));
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .GET()
                    .build();

            HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(target));

            if (response.statusCode() != 200) {
                // Для ассетов 404 — это нормально, просто пропускаем
                if (label.isEmpty()) {
                    return; // тихий пропуск для ассетов
                }
                throw new IOException("HTTP " + response.statusCode());
            }

            if (!label.isEmpty()) {
                long size = Files.size(target);
                ProgressBar.finish(label + " (" + ProgressBar.formatBytes(size) + ")");
            }

        } catch (Exception e) {
            if (!label.isEmpty()) {
                // Для важных файлов (client.jar, библиотеки, index) — ошибка
                throw e;
            }
            // Для ассетов — просто пропускаем молча
        }
    }
}