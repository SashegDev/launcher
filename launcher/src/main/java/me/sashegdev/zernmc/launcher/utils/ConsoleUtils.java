package me.sashegdev.zernmc.launcher.utils;

import java.io.IOException;

public class ConsoleUtils {

    public static void clearScreen() {
        System.out.print("\033[H\033[2J");
        System.out.flush();
    }

    public static void pause() {
        System.out.print(ZAnsi.white("\nНажмите Enter для продолжения..."));
        try {
            System.in.read();
            // Очищаем буфер ввода
            while (System.in.available() > 0) {
                System.in.read();
            }
        } catch (IOException ignored) {}
    }

    public static void printHeader(String subtitle) {
        clearScreen();
        System.out.println(ZAnsi.header("=== ZernMC Launcher ==="));
        if (subtitle != null && !subtitle.isEmpty()) {
            System.out.println(ZAnsi.yellow(subtitle));
        }
        System.out.println();
    }

    public static void printHeader() {
        printHeader(null);
    }

    public static void separator() {
        System.out.println(ZAnsi.white("────────────────────────────────────────────────────────────"));
    }
}