package me.sashegdev.zernmc.launcher;

import me.sashegdev.zernmc.launcher.auth.AuthManager;
import me.sashegdev.zernmc.launcher.menu.*;
import me.sashegdev.zernmc.launcher.ui.ArrowMenu;
import me.sashegdev.zernmc.launcher.utils.*;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

public class Main {

    private static final String CURRENT_VERSION = Version.getCurrentVersion();

    public static void main(String[] args) throws IOException {
        System.setProperty("org.jline.terminal.disableDeprecatedProviderWarning", "true");
        System.setProperty("file.encoding", "UTF-8");
        System.setProperty("sun.err.encoding", "UTF-8");
        System.setProperty("sun.stdout.encoding", "UTF-8");
        java.nio.charset.Charset.defaultCharset();
        ZAnsi.install();

        System.out.print("\033[H\033[2J");
        System.out.println(ZAnsi.brightGreen("Добро пожаловать в ZernMC Launcher " + CURRENT_VERSION));

        //проверка всех сервисов при старте
        ZHttpClient.checkAllServicesOnStartup();

        checkAndAutoUpdateLauncher();

        // === АВТОРИЗАЦИЯ ===
        System.out.println(ZAnsi.cyan("Проверка авторизации..."));
        boolean sessionRestored = AuthManager.loadSavedSession();

        if (!sessionRestored) {
            LoginMenu loginMenu = new LoginMenu();
            boolean loggedIn = loginMenu.show();
            if (!loggedIn) {
                System.out.println(ZAnsi.yellow("До свидания!"));
                ZAnsi.uninstall();
                System.exit(0);
            }
        } else {
            System.out.println(ZAnsi.brightGreen("Добро пожаловать обратно, " + AuthManager.getUsername() + "!"));
        }
        // === КОНЕЦ АВТОРИЗАЦИИ ===

        try {
            mainLoop();
        } catch (Exception e) {
            System.err.println(ZAnsi.brightRed("Критическая ошибка: " + e.getMessage()));
            e.printStackTrace();
        } finally {
            ZAnsi.uninstall();
        }
    }

    private static void checkAndAutoUpdateLauncher() {
        System.out.println(ZAnsi.cyan("Проверка обновлений лаунчера..."));

        try {
            String json = ZHttpClient.getLauncherVersionInfo();
            String serverVersion = extractVersion(json);

            System.out.println(ZAnsi.white("Текущая версия: ") + CURRENT_VERSION);
            System.out.println(ZAnsi.white("Версия на сервере: ") + serverVersion);

            if (Version.isNewer(CURRENT_VERSION, serverVersion)) {
                System.out.println(ZAnsi.brightYellow("\nДоступна новая версия лаунчера! (" + serverVersion + ")"));
                System.out.println(ZAnsi.cyan("Начинается автоматическое обновление...\n"));

                performAutoUpdate(serverVersion);
                restartLauncher();
            } else {
                System.out.println(ZAnsi.brightGreen("Лаунчер актуален."));
            }

        } catch (Exception e) {
            System.out.println(ZAnsi.yellow("Не удалось проверить обновления лаунчера."));
            System.out.println(ZAnsi.white("Ошибка: ") + e.getMessage());
        }
    }

    private static void performAutoUpdate(String newVersion) throws Exception {
        String downloadUrl = ZHttpClient.getBaseUrl() + "/launcher/download?type=jar";
        Path currentJar = getCurrentJarPath();
        Path tempJar = currentJar.getParent().resolve("zernmc-launcher-new.jar");

        System.out.println(ZAnsi.cyan("Скачивание версии " + newVersion + "..."));

        HttpClient client = HttpClient.newBuilder().build();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(java.net.URI.create(downloadUrl))
                .GET()
                .build();

        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(tempJar));

        if (response.statusCode() != 200) {
            throw new IOException("Сервер вернул код: " + response.statusCode());
        }

        long size = Files.size(tempJar);
        System.out.println(ZAnsi.brightGreen("Скачано успешно (" + (size / 1024) + " KB)"));

        // Заменяем текущий jar
        Files.move(tempJar, currentJar, StandardCopyOption.REPLACE_EXISTING);

        System.out.println(ZAnsi.brightGreen("Обновление успешно установлено!"));
    }

    private static void restartLauncher() {
        try {
            String javaPath = System.getProperty("java.home") + "/bin/java";
            String jarPath = getCurrentJarPath().toAbsolutePath().toString();

            System.out.println(ZAnsi.brightGreen("Перезапуск лаунчера с новой версией..."));

            new ProcessBuilder(javaPath, "-jar", jarPath)
                    .inheritIO()
                    .start();

            System.exit(0);
        } catch (Exception e) {
            System.err.println(ZAnsi.brightRed("Не удалось перезапустить лаунчер."));
            System.exit(1);
        }
    }

    private static String extractVersion(String json) {
        try {
            return json.replaceAll(".*\"version\"\\s*:\\s*\"([^\"]+)\".*", "$1");
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static Path getCurrentJarPath() {
        try {
            return Path.of(Main.class.getProtectionDomain()
                    .getCodeSource()
                    .getLocation()
                    .toURI());
        } catch (Exception e) {
            return Path.of("zernmc-launcher-1.0-jar-with-dependencies.jar");
        }
    }

    private static void mainLoop() throws Exception {
        while (true) {
            List<String> options = List.of(
                "Запустить игру",
                "Проверка обновлений",
                "Настройки",
                "Проверка подключения к серверам Zern",
                "Выход"
            );

            ArrowMenu menu = new ArrowMenu("Главное меню", options);
            int choice = menu.show();

            if (choice == -1 || choice == 4) {
                System.out.print("\033[H\033[2J");
                System.out.println(ZAnsi.yellow("До свидания!"));
                break;
            }

            switch (choice) {
                case 0 -> new LaunchMenu().show();
                case 1 -> new UpdateMenu().show();
                case 2 -> new SettingsMenu().show();
                case 3 -> new ServerCheckMenu().show();
            }
        }
    }
}