package me.sashegdev.zernmc.launcher.menu;

import me.sashegdev.zernmc.launcher.ui.ArrowMenu;
import me.sashegdev.zernmc.launcher.utils.ConsoleUtils;

import java.io.IOException;
import java.util.List;

public class UpdateMenu {

    public void show() throws IOException {
        List<String> options = List.of(
            "Проверить обновления сборки (модпака)",
            "Проверить обновления лаунчера",
            "Назад в главное меню"
        );

        ArrowMenu menu = new ArrowMenu("Проверка обновлений", options);
        int choice = menu.show();

        if (choice == -1 || choice == 2) return;

        ConsoleUtils.clearScreen();

        if (choice == 0) {
            System.out.println("Проверка обновлений сборки...");
            System.out.println("Дифф обновлений пока в заглушке (сборки ещё не загружены)");
            System.out.println("   Эндпоинт: POST /pack/{pack_name}/diff");
        } else {
            System.out.println("Проверка обновлений лаунчера...");
            System.out.println("Версия лаунчера актуальна (заглушка)");
        }

        ConsoleUtils.pause();
    }
}