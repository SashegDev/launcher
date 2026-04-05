package me.sashegdev.zernmc.launcher.minecraft.installer;

import me.sashegdev.zernmc.launcher.minecraft.Instance;
import me.sashegdev.zernmc.launcher.utils.ProgressBar;
import me.sashegdev.zernmc.launcher.utils.ZAnsi;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

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
        
        String assetIndex = vanillaInstaller.install(mcVersion);

        if (assetIndex == null || assetIndex.isEmpty()) {
            System.out.println(ZAnsi.brightRed("Не удалось установить базовую версию Minecraft"));
            return false;
        }

        instance.setAssetIndex(assetIndex);

        // Шаг 2: Создаём launcher_profiles.json
        createLauncherProfile();

        // Шаг 3: Скачиваем Forge Installer с прогресс-баром
        String installerUrl = "https://maven.minecraftforge.net/net/minecraftforge/forge/"
                + mcVersion + "-" + forgeVersion
                + "/forge-" + mcVersion + "-" + forgeVersion + "-installer.jar";

        Path installerJar = instance.getPath().resolve("forge-installer.jar");

        System.out.println(ZAnsi.cyan("Скачивание Forge Installer..."));
        downloadFileWithProgress(installerUrl, installerJar);

        // Шаг 4: Запускаем Forge Installer и показываем его вывод
        System.out.println(ZAnsi.cyan("Запуск Forge Installer..."));
        System.out.println(ZAnsi.yellow("Это может занять несколько минут. Пожалуйста, подождите...\n"));
        
        boolean success = runForgeInstaller(installerJar);

        // После успешной установки Forge, но перед сохранением метаданных
        if (success) {
            // Докачиваем пропущенные библиотеки
            try {
                downloadMissingLibraries(mcVersion, forgeVersion);
            } catch (Exception e) {
                System.out.println(ZAnsi.yellow("Предупреждение: не удалось докачать некоторые библиотеки: " + e.getMessage()));
            }
            
            System.out.println(ZAnsi.brightGreen("\nForge " + forgeVersion + " успешно установлен!"));
            instance.setMinecraftVersion(mcVersion);
            instance.setLoaderType("forge");
            instance.setLoaderVersion(forgeVersion);
            
            // Очищаем временный файл установщика
            Files.deleteIfExists(installerJar);
            return true;
        } else {
            System.out.println(ZAnsi.brightRed("\nОшибка при установке Forge!"));
            return false;
        }
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

    private void downloadFileWithProgress(String url, Path target) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
        
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }

        long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1);
        
        try (InputStream in = response.body();
             FileOutputStream out = new FileOutputStream(target.toFile())) {
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalRead = 0;
            int lastPercent = -1;
            
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                totalRead += bytesRead;
                
                if (contentLength > 0) {
                    int percent = (int) ((totalRead * 100) / contentLength);
                    if (percent != lastPercent) {
                        String downloaded = ProgressBar.formatBytes(totalRead);
                        String total = ProgressBar.formatBytes(contentLength);
                        ProgressBar.show("Forge Installer", percent, 100, "% (" + downloaded + "/" + total + ")");
                        lastPercent = percent;
                    }
                } else {
                    // Если размер неизвестен, показываем анимацию
                    char[] spinner = {'|', '/', '-', '\\'};
                    int idx = (int) (totalRead / 1024) % 4;
                    System.out.print("\rСкачивание Forge Installer: " + ProgressBar.formatBytes(totalRead) + " " + spinner[idx]);
                }
            }
        }
        
        ProgressBar.finish("Forge Installer (" + ProgressBar.formatBytes(Files.size(target)) + ")");
    }

    private boolean runForgeInstaller(Path installerJar) throws IOException, InterruptedException {
        // Пробуем до 3 раз с разными опциями
        int maxRetries = 3;
        int attempt = 1;

        while (attempt <= maxRetries) {
            System.out.println(ZAnsi.cyan("Попытка " + attempt + " из " + maxRetries));

            ProcessBuilder pb = new ProcessBuilder(
                    "java",
                    "-jar",
                    installerJar.toAbsolutePath().toString(),
                    "--installClient"
            );

            // Добавляем JVM аргументы для увеличения таймаутов
            pb.environment().put("JAVA_OPTS", "-Dhttp.connectionTimeout=60000 -Dhttp.socketTimeout=60000");

            pb.directory(instance.getPath().toFile());
            pb.redirectErrorStream(true);

            Process process = pb.start();

            // Читаем вывод в реальном времени
            StringBuilder output = new StringBuilder();
            boolean hasErrors = false;

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");

                    // Форматируем вывод Forge Installer
                    if (line.contains("Downloading") || line.contains("Extracting")) {
                        System.out.println(ZAnsi.blue("  -> " + line));
                    } else if (line.contains("SUCCESS") || line.contains("successfully")) {
                        System.out.println(ZAnsi.brightGreen("  + " + line));
                    } else if (line.contains("WARNING") || line.contains("warning")) {
                        System.out.println(ZAnsi.yellow("  ! " + line));
                    } else if (line.contains("ERROR") || line.contains("error") || line.contains("failed") || line.contains("timed out")) {
                        System.out.println(ZAnsi.brightRed("  X " + line));
                        if (line.contains("timed out") || line.contains("failed to download")) {
                            hasErrors = true;
                        }
                    } else if (!line.isBlank()) {
                        System.out.println("  " + line);
                    }
                }
            }

            int exitCode = process.waitFor();

            // Если успешно или нет ошибок скачивания
            if (exitCode == 0 && !hasErrors) {
                return true;
            }

            // Если ошибка и это не последняя попытка
            if (attempt < maxRetries) {
                System.out.println(ZAnsi.yellow("Ошибка при установке. Повторная попытка через 5 секунд..."));
                Thread.sleep(5000);

                // Очищаем временные файлы перед повтором
                Path librariesDir = instance.getPath().resolve("libraries");
                if (Files.exists(librariesDir)) {
                    // Удаляем только частично скачанные библиотеки Forge
                    try (var stream = Files.walk(librariesDir)) {
                        stream.filter(p -> p.toString().contains("asm") && p.toString().endsWith(".jar"))
                              .forEach(p -> {
                                  try { Files.deleteIfExists(p); } 
                                  catch (IOException e) { /* ignore */ }
                              });
                    }
                }
            } else {
                System.out.println(ZAnsi.brightRed("Forge Installer завершился с кодом ошибки: " + exitCode));

                // Показываем возможное решение
                if (output.toString().contains("timed out")) {
                    System.out.println(ZAnsi.yellow("\nВозможные решения:"));
                    System.out.println(ZAnsi.yellow("1. Проверьте интернет-соединение"));
                    System.out.println(ZAnsi.yellow("2. Запустите лаунчер от имени администратора"));
                    System.out.println(ZAnsi.yellow("3. Временно отключите антивирус/брандмауэр"));
                    System.out.println(ZAnsi.yellow("4. Попробуйте установить другую версию Forge"));
                }
            }

            attempt++;
        }

        return false;
    }

    private void downloadMissingLibraries(String mcVersion, String forgeVersion) throws Exception {
        System.out.println(ZAnsi.cyan("Проверка и докачка отсутствующих библиотек..."));
        
        // Список проблемных библиотек и их альтернативные URL
        Map<String, String> alternativeUrls = new HashMap<>();
        alternativeUrls.put("org/ow2/asm/asm/9.6/asm-9.6.jar", 
            "https://repo1.maven.org/maven2/org/ow2/asm/asm/9.6/asm-9.6.jar");
        alternativeUrls.put("org/ow2/asm/asm/9.6/asm-9.6.jar",
            "https://mirrors.huaweicloud.com/repository/maven/org/ow2/asm/asm/9.6/asm-9.6.jar");
        
        Path librariesDir = instance.getPath().resolve("libraries");
        
        for (Map.Entry<String, String> entry : alternativeUrls.entrySet()) {
            Path target = librariesDir.resolve(entry.getKey());
            if (!Files.exists(target)) {
                Files.createDirectories(target.getParent());
                System.out.println(ZAnsi.yellow("Докачка: " + target.getFileName()));
                
                for (int attempt = 1; attempt <= 3; attempt++) {
                    try {
                        downloadFileWithProgress(entry.getValue(), target);
                        break;
                    } catch (Exception e) {
                        if (attempt == 3) throw e;
                        System.out.println(ZAnsi.yellow("Повторная попытка " + attempt + "/3..."));
                        Thread.sleep(2000);
                    }
                }
            }
        }
    }
}