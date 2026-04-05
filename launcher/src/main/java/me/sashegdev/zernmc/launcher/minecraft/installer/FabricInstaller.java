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

        // Шаг 1: Установка vanilla версии (если ещё не установлена)
        VersionInstaller versionInstaller = new VersionInstaller(instancePath);
        boolean mcOk = versionInstaller.install(minecraftVersion);
        if (!mcOk) {
            System.out.println(ZAnsi.brightRed("Не удалось установить Minecraft " + minecraftVersion));
            return false;
        }

        // Шаг 2: Скачивание и запуск Fabric Installer
        String installerVersion = getLatestInstallerVersion();
        String installerUrl = "https://maven.fabricmc.net/net/fabricmc/fabric-installer/" 
                + installerVersion + "/fabric-installer-" + installerVersion + ".jar";

        Path installerJar = instancePath.resolve("fabric-installer.jar");

        ProgressBar.show("Скачивание Fabric Installer", 0, 100, "%");
        downloadFile(installerUrl, installerJar);
        ProgressBar.finish("Fabric Installer скачан");

        // Шаг 3: Запуск Fabric Installer
        System.out.println(ZAnsi.cyan("Запуск Fabric Installer..."));

        ProcessBuilder pb = new ProcessBuilder(
                "java", "-jar", installerJar.toAbsolutePath().toString(),
                "client",
                "-dir", instancePath.toAbsolutePath().toString(),
                "-mcversion", minecraftVersion,
                "-loader", loaderVersion,
                "-noprofile",
                "-snapshot"
        );

        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        Process process = pb.start();
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            System.out.println(ZAnsi.brightRed("Fabric Installer завершился с ошибкой (код " + exitCode + ")"));
            return false;
        }

        // Шаг 4: Проверка, что Fabric версия появилась
        String fabricVersionId = "fabric-loader-" + loaderVersion + "-" + minecraftVersion;
        Path fabricVersionDir = instancePath.resolve("versions").resolve(fabricVersionId);

        if (Files.exists(fabricVersionDir)) {
            System.out.println(ZAnsi.brightGreen("Fabric успешно установлен!"));
            System.out.println("Версия: " + fabricVersionId);
            return true;
        } else {
            System.out.println(ZAnsi.brightRed("Fabric Installer отработал, но версия не найдена."));
            return false;
        }
    }

    private void cleanOldFabricLoaders() throws IOException {
        Path librariesDir = instance.getPath().resolve("libraries/net/fabricmc/fabric-loader");
        if (!Files.exists(librariesDir)) return;
        
        System.out.println(ZAnsi.yellow("Очистка старых версий Fabric Loader..."));
        
        try (var stream = Files.walk(librariesDir)) {
            stream.filter(Files::isDirectory)
                  .filter(dir -> dir.getFileName().toString().startsWith("0."))
                  .forEach(dir -> {
                      try {
                          // Удаляем папку версии
                          Files.walk(dir)
                               .sorted((a,b) -> b.compareTo(a)) // удаляем файлы перед папками
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
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        HttpResponse<String> resp = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());
        return resp.body();
    }

    private void downloadFile(String url, Path target) throws Exception {
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();
        httpClient.send(request, HttpResponse.BodyHandlers.ofFile(target));
    }
}