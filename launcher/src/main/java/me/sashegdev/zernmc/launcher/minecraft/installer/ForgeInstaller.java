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
import java.nio.file.StandardOpenOption;

public class ForgeInstaller {

    private final Instance instance;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(30))
            .build();

    public ForgeInstaller(Instance instance) {
        this.instance = instance;
    }

    public boolean install(String mcVersion, String forgeVersion) throws Exception {
        System.out.println(ZAnsi.cyan("Установка Forge " + forgeVersion + " для Minecraft " + mcVersion));

        // Шаг 1: Устанавливаем vanilla и получаем настоящий assetIndex
        System.out.println(ZAnsi.cyan("Установка базовой версии Minecraft " + mcVersion + "..."));
        VersionInstaller vanillaInstaller = new VersionInstaller(instance.getPath());
        
        String assetIndex = vanillaInstaller.install(mcVersion);   // ← теперь возвращает String

        if (assetIndex == null || assetIndex.isEmpty()) {
            System.out.println(ZAnsi.brightRed("Не удалось установить базовую версию Minecraft"));
            return false;
        }

        // Сохраняем assetIndex (очень важно!)
        instance.setAssetIndex(assetIndex);

        // Шаг 2: Создаём launcher_profiles.json
        createLauncherProfile();

        // Шаг 3: Скачиваем и запускаем Forge Installer
        String installerUrl = "https://maven.minecraftforge.net/net/minecraftforge/forge/"
                + mcVersion + "-" + forgeVersion
                + "/forge-" + mcVersion + "-" + forgeVersion + "-installer.jar";

        Path installerJar = instance.getPath().resolve("forge-installer.jar");

        ProgressBar.show("Скачивание Forge Installer", 0, 100, "%");
        downloadFile(installerUrl, installerJar);
        ProgressBar.finish("Forge Installer скачан");

        System.out.println(ZAnsi.cyan("Запуск Forge Installer..."));
        ProcessBuilder pb = new ProcessBuilder(
                "java",
                "-jar",
                installerJar.toAbsolutePath().toString(),
                "--installClient"
        );
        pb.directory(instance.getPath().toFile());
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);

        Process process = pb.start();
        int exitCode = process.waitFor();

        if (exitCode != 0) {
            System.out.println(ZAnsi.brightRed("Forge Installer завершился с ошибкой (код " + exitCode + ")"));
            return false;
        }

        System.out.println(ZAnsi.brightGreen("Forge " + forgeVersion + " успешно установлен!"));

        instance.setMinecraftVersion(mcVersion);
        instance.setLoaderType("forge");
        instance.setLoaderVersion(forgeVersion);

        return true;
    }

    private void createLauncherProfile() throws IOException {
        Path profilePath = instance.getPath().resolve("launcher_profiles.json");
        if (Files.exists(profilePath)) return;

        String minimalProfile = """
                {
                  "profiles": {},
                  "selectedProfile": "Default"
                }
                """;
        Files.writeString(profilePath, minimalProfile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        System.out.println(ZAnsi.yellow("Создан launcher_profiles.json"));
    }

    private void downloadFile(String url, Path target) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        HttpResponse<Path> response = httpClient.send(request, HttpResponse.BodyHandlers.ofFile(target));
        if (response.statusCode() != 200) {
            throw new IOException("Не удалось скачать Forge installer (HTTP " + response.statusCode() + ")");
        }
    }
}