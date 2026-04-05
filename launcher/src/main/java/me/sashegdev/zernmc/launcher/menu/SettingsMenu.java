package me.sashegdev.zernmc.launcher.menu;

import me.sashegdev.zernmc.launcher.ui.ArrowMenu;
import me.sashegdev.zernmc.launcher.utils.Config;
import me.sashegdev.zernmc.launcher.utils.ConsoleUtils;
import me.sashegdev.zernmc.launcher.utils.Input;
import me.sashegdev.zernmc.launcher.utils.ZAnsi;

import java.io.IOException;
import java.util.List;

public class SettingsMenu {

    public void show() throws IOException {
        List<String> options = List.of(
            "Настроить путь к Java",
            "Настроить выделенную память (RAM)",
            "Дополнительные JVM параметры",
            "Назад в главное меню"
        );

        ArrowMenu menu = new ArrowMenu("Настройки лаунчера", options);
        int choice = menu.show();

        if (choice == -1 || choice == 3) return;

        ConsoleUtils.clearScreen();

        switch (choice) {
            case 0 -> configureJava();
            case 1 -> configureRam();
            case 2 -> configureJvmArgs();
        }

        ConsoleUtils.pause();
    }

    private void configureJava() {
        System.out.println(ZAnsi.cyan("Путь к Java:"));
        System.out.println("   " + Config.getJreDir().toAbsolutePath());
        System.out.println(ZAnsi.white("\nJava будет искаться автоматически в папке ~/.zernmc/jre/"));
        System.out.println("Если нужно — положите туда свою версию Java.");
    }

    private void configureRam() {
        System.out.println(ZAnsi.cyan("Настройка выделенной памяти"));
        System.out.println(Config.getRamInfo());

        int newRam = Input.readInt(
            ZAnsi.white("\nВведите новое значение RAM в MB (или 0 для отмены): "),
            0, 32768
        );

        if (newRam == 0) {
            System.out.println(ZAnsi.yellow("Настройка отменена."));
            return;
        }

        Config.setMaxMemory(newRam);
        System.out.println(ZAnsi.brightGreen("Выделенная память изменена на " + newRam + " MB"));
    }

    private void configureJvmArgs() {
        System.out.println(ZAnsi.yellow("Дополнительные JVM параметры"));
        System.out.println("Пока в разработке.");
        System.out.println("В будущем здесь будет список предустановленных оптимизаций.");
    }
}