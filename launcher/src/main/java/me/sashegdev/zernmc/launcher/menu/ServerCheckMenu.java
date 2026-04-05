package me.sashegdev.zernmc.launcher.menu;

import me.sashegdev.zernmc.launcher.ui.ArrowMenu;
import me.sashegdev.zernmc.launcher.utils.ConsoleUtils;
import me.sashegdev.zernmc.launcher.utils.ZAnsi;
import me.sashegdev.zernmc.launcher.utils.ZHttpClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

public class ServerCheckMenu {

    public void show() throws IOException {
        List<String> options = List.of(
            "Проверить подключение к ZernMC серверу",
            "Проверить доступ к Mojang (Minecraft)",
            "Проверить доступ к Fabric Meta",
            "Назад в главное меню"
        );

        ArrowMenu menu = new ArrowMenu("Диагностика подключения", options);
        int choice = menu.show();

        if (choice == -1 || choice == 4) return;

        ConsoleUtils.clearScreen();

        switch (choice) {
            case 0 -> checkZernServer();
            case 1 -> checkMojang();
            case 2 -> checkFabric();
        }

        ConsoleUtils.pause();
    }

    private void checkZernServer() {
        System.out.println(ZAnsi.cyan("Проверка подключения к ZernMC серверу..."));
        try {
            String response = ZHttpClient.get("/health");
            System.out.println(ZAnsi.brightGreen("Сервер успешно подключён!"));
            System.out.println("Ответ: " + response);
        } catch (Exception e) {
            System.out.println(ZAnsi.brightRed("Не удалось подключиться к ZernMC серверу"));
            System.out.println("Ошибка: " + e.getMessage());
        }
    }

    private void checkMojang() {
        System.out.println(ZAnsi.cyan("Проверка доступа к Mojang..."));
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(8))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://launchermeta.mojang.com/mc/game/version_manifest_v2.json"))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                System.out.println(ZAnsi.brightGreen("Mojang доступен"));
            } else {
                System.out.println(ZAnsi.brightRed("Mojang вернул код " + response.statusCode()));
            }
        } catch (Exception e) {
            System.out.println(ZAnsi.brightRed("Нет доступа к Mojang"));
            System.out.println("Ошибка: " + e.getMessage());
        }
    }

    private void checkFabric() {
        System.out.println(ZAnsi.cyan("Проверка доступа к Fabric Meta..."));
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(8))
                    .build();

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://meta.fabricmc.net/v2/versions"))
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                System.out.println(ZAnsi.brightGreen("Fabric Meta доступен"));
            } else {
                System.out.println(ZAnsi.brightRed("Fabric Meta вернул код " + response.statusCode()));
            }
        } catch (Exception e) {
            System.out.println(ZAnsi.brightRed("Нет доступа к Fabric Meta"));
            System.out.println("Ошибка: " + e.getMessage());
        }
    }
}