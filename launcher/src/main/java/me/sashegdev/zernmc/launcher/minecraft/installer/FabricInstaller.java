package me.sashegdev.zernmc.launcher.minecraft.installer;

import me.sashegdev.zernmc.launcher.minecraft.Instance;
import me.sashegdev.zernmc.launcher.utils.ProgressBar;
import me.sashegdev.zernmc.launcher.utils.ZAnsi;
import me.sashegdev.zernmc.launcher.utils.ZHttpClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public class FabricInstaller {

    private final Instance instance;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    public FabricInstaller(Instance instance) {
        this.instance = instance;
    }

    public boolean install(String minecraftVersion, String loaderVersion) throws Exception {
        System.out.println(ZAnsi.cyan("Установка Fabric " + loaderVersion + " для Minecraft " + minecraftVersion));

        Path instancePath = instance.getPath();
        cleanOldFabricLoaders();

        VersionInstaller versionInstaller = new VersionInstaller(instancePath);
        String assetIndex = versionInstaller.install(minecraftVersion);
        
        System.out.println(ZAnsi.green("Asset index получен: " + assetIndex));
        
        instance.setAssetIndex(assetIndex);
        instance.setMinecraftVersion(minecraftVersion);

        String installerVersion = getLatestInstallerVersion();
        String installerUrl = "https://maven.fabricmc.net/net/fabricmc/fabric-installer/"
                + installerVersion + "/fabric-installer-" + installerVersion + ".jar";

        Path installerJar = instancePath.resolve("fabric-installer.jar");

        if (!Files.exists(installerJar)) {
            ProgressBar.show("Скачивание Fabric Installer", 0, 100, "%");
            downloadFileWithFallback(installerUrl, installerJar);
            ProgressBar.finish("Fabric Installer скачан");
        }

        System.out.println(ZAnsi.cyan("Запуск Fabric Installer..."));

        String fabricVersionId = "fabric-loader-" + loaderVersion + "-" + minecraftVersion;

        ProcessBuilder pb = new ProcessBuilder(
                "java", "-jar", installerJar.toAbsolutePath().toString(),
                "client",
                "-dir", instancePath.toAbsolutePath().toString(),
                "-mcversion", minecraftVersion,
                "-loader", loaderVersion,
                "-noprofile"
        );

        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        Process process = pb.start();
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            System.out.println(ZAnsi.brightRed("Fabric Installer завершился с ошибкой (код " + exitCode + ")"));
            return false;
        }

        Path fabricVersionDir = instancePath.resolve("versions").resolve(fabricVersionId);

        if (Files.exists(fabricVersionDir)) {
            System.out.println(ZAnsi.brightGreen("Fabric успешно установлен!"));

            instance.setLoaderType("fabric");
            instance.setLoaderVersion(loaderVersion);
            instance.setFabricVersionId(fabricVersionId); // ← СОХРАНЯЕМ

            ensureAssetIndexInFabricVersion(fabricVersionDir, assetIndex);

            return true;
        } else {
            System.out.println(ZAnsi.brightRed("Fabric Installer отработал, но версия не найдена."));
            return false;
        }
    }

    private void downloadFileWithFallback(String url, Path target) throws Exception {
        try {
            ZHttpClient.downloadFile(url, target);
        } catch (Exception e) {
            System.out.println(ZAnsi.yellow("Не удалось скачать Fabric Installer: " + e.getMessage()));
            throw e;
        }
    }

    private void ensureAssetIndexInFabricVersion(Path fabricVersionDir, String assetIndex) throws IOException {
        Path versionJson = fabricVersionDir.resolve(fabricVersionDir.getFileName() + ".json");
        
        if (!Files.exists(versionJson)) {
            System.out.println(ZAnsi.yellow("JSON файл версии не найден: " + versionJson));
            return;
        }
        
        String content = Files.readString(versionJson);
        
        // Проверяем и исправляем asset index
        if (!content.contains("\"assets\":\"" + assetIndex + "\"")) {
            System.out.println(ZAnsi.yellow("Исправляем asset index в JSON файле версии..."));
            
            // Заменяем assets на правильное значение
            content = content.replaceAll("\"assets\":\\s*\"[^\"]*\"", "\"assets\": \"" + assetIndex + "\"");
            
            // Также проверяем assetIndex
            if (content.contains("\"assetIndex\"")) {
                content = content.replaceAll("\"assetIndex\":\\s*\"[^\"]*\"", "\"assetIndex\": \"" + assetIndex + "\"");
            }
            
            Files.writeString(versionJson, content);
            System.out.println(ZAnsi.green("Asset index исправлен на: " + assetIndex));
        } else {
            System.out.println(ZAnsi.green("Asset index в JSON версии правильный: " + assetIndex));
        }
    }

    private void cleanOldFabricLoaders() throws IOException {
        Path librariesDir = instance.getPath().resolve("libraries/net/fabricmc/fabric-loader");
        if (!Files.exists(librariesDir)) return;
        
        System.out.println(ZAnsi.yellow("Очистка старых версий Fabric Loader..."));
        
        try (var stream = Files.walk(librariesDir)) {
            stream.filter(Files::isDirectory)
                  .filter(dir -> dir.getFileName().toString().matches("\\d+\\.\\d+\\.\\d+.*"))
                  .forEach(dir -> {
                      try {
                          Files.walk(dir)
                               .sorted((a,b) -> b.compareTo(a))
                               .forEach(p -> {
                                   try { Files.deleteIfExists(p); } 
                                   catch (IOException ignored) {}
                               });
                      } catch (IOException ignored) {}
                  });
        }
    }

    private String getLatestInstallerVersion() throws Exception {
        try {
            // Используем ZHttpClient с умным прокси
            String xml = ZHttpClient.downloadString("https://maven.fabricmc.net/net/fabricmc/fabric-installer/maven-metadata.xml");
            int start = xml.indexOf("<latest>") + 8;
            int end = xml.indexOf("</latest>", start);
            return xml.substring(start, end).trim();
        } catch (Exception e) {
            System.out.println(ZAnsi.yellow("Ошибка получения версии Fabric Installer: " + e.getMessage()));
            throw new Exception("Не удалось получить версию Fabric Installer", e);
        }
    }

    // под рефактор оставить
    private String downloadString(String url) throws Exception {
        Exception lastException = null;

        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(30 * attempt))
                        .header("User-Agent", "ZernMC-Launcher/1.0")
                        .GET()
                        .build();

                HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    return resp.body();
                }
                throw new IOException("HTTP " + resp.statusCode());
            } catch (Exception e) {
                lastException = e;
                System.out.println(ZAnsi.yellow("Попытка " + attempt + " не удалась: " + e.getMessage()));
                if (attempt < 3) {
                    Thread.sleep(1000 * attempt);
                }
            }
        }

        throw lastException;
    }

    private void downloadFile(String url, Path target) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        
        HttpResponse<Path> response = httpClient.send(request, 
                HttpResponse.BodyHandlers.ofFile(target));
        
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode() + " при скачивании " + url);
        }
    }
}