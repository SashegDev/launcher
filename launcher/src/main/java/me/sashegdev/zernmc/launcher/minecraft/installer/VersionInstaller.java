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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class VersionInstaller {

    private final Path minecraftDir;
    private final HttpClient httpClient;
    private final ExecutorService executor = Executors.newFixedThreadPool(32);

    public VersionInstaller(Path minecraftDir) {
        this.minecraftDir = minecraftDir;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

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

    public String install(String versionId) throws Exception {
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

        String assetIndex;
        if (versionData.has("assetIndex")) {
            JSONObject assetIndexObj = versionData.getJSONObject("assetIndex");
            assetIndex = assetIndexObj.getString("id");  // ← это "5" для 1.20.1
        } else {
            assetIndex = versionData.getString("assets"); // fallback
        }

        System.out.println(ZAnsi.cyan("Asset index: " + assetIndex));

        // Скачиваем ассеты используя правильный индекс
        System.out.println(ZAnsi.cyan("Скачивание ассетов..."));
        downloadAssets(versionData, assetIndex);

        System.out.println(ZAnsi.brightGreen("\nMinecraft " + versionId + " полностью установлен!"));
        return assetIndex;  // ← возвращаем "5" а не "1.20.1"
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
                    // Пропускаем проблемные библиотеки
                }
            }
            count++;
            ProgressBar.show("Библиотеки", count, total, "файлов");
        }
        ProgressBar.finish("Библиотеки загружены");
    }

    private void downloadAssets(JSONObject versionData, String assetIndex) throws Exception {
        // Находим URL для asset index
        JSONObject assetIndexInfo = versionData.getJSONObject("assetIndex");
        String indexUrl = assetIndexInfo.getString("url");

        Path indexesDir = minecraftDir.resolve("assets/indexes");
        Files.createDirectories(indexesDir);
        Path indexPath = indexesDir.resolve(assetIndex + ".json");  // ← используем assetIndex

        System.out.println(ZAnsi.cyan("Скачивание asset index (" + assetIndex + ")..."));
        downloadFile(indexUrl, indexPath, "asset index");

        String jsonContent = Files.readString(indexPath);
        JSONObject root = new JSONObject(jsonContent);
        JSONObject objects = root.getJSONObject("objects");

        System.out.println(ZAnsi.cyan("Скачивание " + objects.length() + " объектов ассетов (index: " + assetIndex + ")..."));

        int total = objects.length();
        int[] success = {0};
        int[] failed = {0};

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String key : objects.keySet()) {
            JSONObject asset = objects.getJSONObject(key);
            String hash = asset.getString("hash");  // ← вот это правильный хеш!

            String url = "https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash;
            Path target = minecraftDir.resolve("assets/objects")
                    .resolve(hash.substring(0, 2))
                    .resolve(hash);

            Files.createDirectories(target.getParent());

            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                boolean downloaded = false;
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        downloadFile(url, target, "");
                        synchronized (this) {
                            success[0]++;
                            ProgressBar.show("Ассеты", success[0], total, "файлов");
                        }
                        downloaded = true;
                        break;
                    } catch (Exception e) {
                        if (attempt == 3) {
                            synchronized (this) {
                                failed[0]++;
                            }
                            System.err.println("Не удалось скачать " + hash);
                        } else {
                            try { Thread.sleep(500 * attempt); } catch (InterruptedException ignored) {}
                        }
                    }
                }
            }, executor);

            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        ProgressBar.finish("Ассеты загружены (" + success[0] + " успешно, " + failed[0] + " пропущено)");

        if (failed[0] > 0) {
            System.out.println(ZAnsi.yellow("Предупреждение: " + failed[0] + " файлов ассетов не удалось скачать."));
            System.out.println(ZAnsi.yellow("Игра запустится, но некоторые текстуры/звуки могут отсутствовать."));
        }
    }

    public String getAssetIndexForVersion(String versionId) throws Exception {
        String versionUrl = getVersionUrl(versionId);
        if (versionUrl == null) throw new Exception("Версия " + versionId + " не найдена");
        
        String versionJson = downloadString(versionUrl);
        JSONObject versionData = new JSONObject(versionJson);
        
        return versionData.getString("assets");
    }

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

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(target));

        if (response.statusCode() != 200) {
            if (label.isEmpty()) return; // для ассетов молча
            throw new IOException("HTTP " + response.statusCode() + " при скачивании " + label);
        }

        if (!label.isEmpty()) {
            long size = Files.size(target);
            ProgressBar.finish(label + " (" + ProgressBar.formatBytes(size) + ")");
        }
    }
}