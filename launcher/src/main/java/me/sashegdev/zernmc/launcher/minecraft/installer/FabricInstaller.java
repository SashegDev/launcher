package me.sashegdev.zernmc.launcher.minecraft.installer;

import me.sashegdev.zernmc.launcher.minecraft.Instance;
import me.sashegdev.zernmc.launcher.utils.ProgressBar;
import me.sashegdev.zernmc.launcher.utils.ZAnsi;

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

        // Шаг 1: Устанавливаем vanilla и получаем assetIndex
        VersionInstaller versionInstaller = new VersionInstaller(instancePath);
        String assetIndex = versionInstaller.install(minecraftVersion);
        
        // ДОПОЛНИТЕЛЬНАЯ ПРОВЕРКА: если versionInstaller.install() вернул неправильный индекс
        // (например, "1.20.1" вместо "5"), то получаем правильный индекс напрямую
        if (assetIndex == null || assetIndex.isEmpty() || assetIndex.equals(minecraftVersion)) {
            System.out.println(ZAnsi.yellow("Asset index из установки выглядит подозрительно: " + assetIndex));
            System.out.println(ZAnsi.cyan("Получаем правильный asset index для " + minecraftVersion + "..."));
            
            // Получаем правильный asset index из манифеста версии
            String correctAssetIndex = versionInstaller.getAssetIndexForVersion(minecraftVersion);
            if (correctAssetIndex != null && !correctAssetIndex.isEmpty()) {
                assetIndex = correctAssetIndex;
                System.out.println(ZAnsi.green("Правильный asset index: " + assetIndex));
            } else {
                System.out.println(ZAnsi.brightRed("Не удалось получить asset index для версии " + minecraftVersion));
                return false;
            }
        }

        // Сохраняем правильный assetIndex
        instance.setAssetIndex(assetIndex);
        System.out.println(ZAnsi.green("Asset index сохранён: " + assetIndex));

        // Шаг 2: Скачивание Fabric Installer
        String installerVersion = getLatestInstallerVersion();
        String installerUrl = "https://maven.fabricmc.net/net/fabricmc/fabric-installer/"
                + installerVersion + "/fabric-installer-" + installerVersion + ".jar";

        Path installerJar = instancePath.resolve("fabric-installer.jar");

        if (!Files.exists(installerJar)) {
            ProgressBar.show("Скачивание Fabric Installer", 0, 100, "%");
            downloadFile(installerUrl, installerJar);
            ProgressBar.finish("Fabric Installer скачан");
        } else {
            System.out.println(ZAnsi.green("Fabric Installer уже скачан, пропускаем..."));
        }

        System.out.println(ZAnsi.cyan("Запуск Fabric Installer..."));
        
        // Используем ProcessBuilder с правильными аргументами
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

        // Проверка результата - Fabric создаёт папку versions/fabric-loader-{loaderVersion}-{minecraftVersion}
        String fabricVersionId = "fabric-loader-" + loaderVersion + "-" + minecraftVersion;
        Path fabricVersionDir = instancePath.resolve("versions").resolve(fabricVersionId);
        
        // Альтернативная проверка (иногда Fabric использует другой формат)
        if (!Files.exists(fabricVersionDir)) {
            fabricVersionId = "fabric-loader-" + loaderVersion + "-" + minecraftVersion;
            fabricVersionDir = instancePath.resolve("versions").resolve(fabricVersionId);
        }

        if (Files.exists(fabricVersionDir)) {
            System.out.println(ZAnsi.brightGreen("Fabric успешно установлен!"));
            System.out.println(ZAnsi.white("Версия: ") + fabricVersionId);
            System.out.println(ZAnsi.white("Asset index: ") + assetIndex);

            // Сохраняем метаданные в Instance
            instance.setMinecraftVersion(minecraftVersion);
            instance.setLoaderType("fabric");
            instance.setLoaderVersion(loaderVersion);
            // assetIndex уже сохранён выше, но сохраняем ещё раз для надёжности
            instance.setAssetIndex(assetIndex);
            
            // Копируем или создаём ссылку на правильный asset index в версии Fabric
            ensureAssetIndexInFabricVersion(fabricVersionDir, assetIndex, minecraftVersion);

            return true;
        } else {
            System.out.println(ZAnsi.brightRed("Fabric Installer отработал, но версия не найдена."));
            System.out.println(ZAnsi.yellow("Искали: " + fabricVersionDir));
            
            // Выводим содержимое папки versions для отладки
            Path versionsDir = instancePath.resolve("versions");
            if (Files.exists(versionsDir)) {
                System.out.println(ZAnsi.cyan("Доступные версии в папке versions:"));
                try (var stream = Files.list(versionsDir)) {
                    stream.forEach(p -> System.out.println("  - " + p.getFileName()));
                }
            }
            return false;
        }
    }

    /**
     * Убеждаемся, что в JSON файле версии Fabric есть правильный asset index
     */
    private void ensureAssetIndexInFabricVersion(Path fabricVersionDir, String assetIndex, String minecraftVersion) throws IOException {
        Path versionJson = fabricVersionDir.resolve(fabricVersionDir.getFileName() + ".json");
        
        if (!Files.exists(versionJson)) {
            System.out.println(ZAnsi.yellow("JSON файл версии не найден: " + versionJson));
            return;
        }
        
        String content = Files.readString(versionJson);
        
        // Проверяем, есть ли в JSON правильный asset index
        if (!content.contains("\"assets\":\"" + assetIndex + "\"")) {
            System.out.println(ZAnsi.yellow("Исправляем asset index в JSON файле версии..."));
            
            // Заменяем assets на правильное значение
            // Ищем "assets": "что-то" и заменяем
            content = content.replaceAll("\"assets\":\\s*\"[^\"]*\"", "\"assets\": \"" + assetIndex + "\"");
            
            // Также проверяем assetIndex в downloads
            if (content.contains("\"assetIndex\"")) {
                content = content.replaceAll("\"assetIndex\":\\s*\"[^\"]*\"", "\"assetIndex\": \"" + assetIndex + "\"");
            }
            
            Files.writeString(versionJson, content);
            System.out.println(ZAnsi.green("Asset index исправлен на: " + assetIndex));
        } else {
            System.out.println(ZAnsi.green("Asset index в JSON версии уже правильный: " + assetIndex));
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
        String url = "https://maven.fabricmc.net/net/fabricmc/fabric-installer/maven-metadata.xml";
        String xml = downloadString(url);

        int start = xml.indexOf("<latest>") + 8;
        int end = xml.indexOf("</latest>", start);
        return xml.substring(start, end).trim();
    }

    private String downloadString(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) {
            throw new IOException("HTTP " + resp.statusCode() + " при скачивании " + url);
        }
        return resp.body();
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