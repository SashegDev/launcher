package me.sashegdev.zernmc.launcher.utils;

import org.fusesource.jansi.Ansi;
import org.fusesource.jansi.AnsiConsole;

public class ZAnsi {

    //поддержка ANSI епта
    public static void install() {
        AnsiConsole.systemInstall();
    }

    public static void uninstall() {
        AnsiConsole.systemUninstall();
    }

    // === Основные цвета ===
    public static String green(String text) {
        return Ansi.ansi().fg(Ansi.Color.GREEN).a(text).reset().toString();
    }

    public static String brightGreen(String text) {
        return Ansi.ansi().fgBright(Ansi.Color.GREEN).a(text).reset().toString();
    }

    public static String cyan(String text) {
        return Ansi.ansi().fg(Ansi.Color.CYAN).a(text).reset().toString();
    }

    public static String yellow(String text) {
        return Ansi.ansi().fg(Ansi.Color.YELLOW).a(text).reset().toString();
    }

    public static String brightYellow(String text) {
        return Ansi.ansi().fgBright(Ansi.Color.YELLOW).a(text).reset().toString();
    }

    public static String red(String text) {
        return Ansi.ansi().fg(Ansi.Color.RED).a(text).reset().toString();
    }

    public static String brightRed(String text) {
        return Ansi.ansi().fgBright(Ansi.Color.RED).a(text).reset().toString();
    }

    public static String blue(String text) {
        return Ansi.ansi().fg(Ansi.Color.BLUE).a(text).reset().toString();
    }

    public static String white(String text) {
        return Ansi.ansi().fg(Ansi.Color.WHITE).a(text).reset().toString();
    }

    public static String brightWhite(String text) {
        return Ansi.ansi().fgBright(Ansi.Color.WHITE).a(text).reset().toString();
    }

    // Стили
    public static String bold(String text) {
        return Ansi.ansi().bold().a(text).reset().toString();
    }

    public static String reset() {
        return Ansi.ansi().reset().toString();
    }

    // Комбинированные удобные методы
    public static String header(String text) {
        return Ansi.ansi().fgBright(Ansi.Color.CYAN).bold().a(text).reset().toString();
    }

    public static String selected(String text) {
        return Ansi.ansi()
                .bgBright(Ansi.Color.WHITE)
                .fgBlack()
                .a(" > " + text + " ")
                .reset()
                .toString();
    }
}