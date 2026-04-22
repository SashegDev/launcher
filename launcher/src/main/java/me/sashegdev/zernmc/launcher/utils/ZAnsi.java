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

    public static String brightCyan(String text) {
        return Ansi.ansi().fgBright(Ansi.Color.CYAN).a(text).reset().toString();
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

    public static String brightBlue(String text) {
        return Ansi.ansi().fgBright(Ansi.Color.BLUE).a(text).reset().toString();
    }

    public static String magenta(String text) {
        return Ansi.ansi().fg(Ansi.Color.MAGENTA).a(text).reset().toString();
    }

    public static String brightMagenta(String text) {
        return Ansi.ansi().fgBright(Ansi.Color.MAGENTA).a(text).reset().toString();
    }

    // Пурпурный как brightPurple (используем magenta)
    public static String purple(String text) {
        return brightMagenta(text);
    }

    public static String brightPurple(String text) {
        return brightMagenta(text);
    }

    public static String white(String text) {
        return Ansi.ansi().fg(Ansi.Color.WHITE).a(text).reset().toString();
    }

    public static String brightWhite(String text) {
        return Ansi.ansi().fgBright(Ansi.Color.WHITE).a(text).reset().toString();
    }

    public static String black(String text) {
        return Ansi.ansi().fg(Ansi.Color.BLACK).a(text).reset().toString();
    }

    // === Фоновые цвета ===
    public static String bgGreen(String text) {
        return Ansi.ansi().bg(Ansi.Color.GREEN).a(text).reset().toString();
    }

    public static String bgRed(String text) {
        return Ansi.ansi().bg(Ansi.Color.RED).a(text).reset().toString();
    }

    public static String bgYellow(String text) {
        return Ansi.ansi().bg(Ansi.Color.YELLOW).a(text).reset().toString();
    }

    public static String bgBlue(String text) {
        return Ansi.ansi().bg(Ansi.Color.BLUE).a(text).reset().toString();
    }

    // === Стили ===
    public static String bold(String text) {
        return Ansi.ansi().bold().a(text).reset().toString();
    }

    public static String reset() {
        return Ansi.ansi().reset().toString();
    }

    // === Комбинированные удобные методы ===
    public static String header(String text) {
        return Ansi.ansi().fgBright(Ansi.Color.CYAN).bold().a(text).reset().toString();
    }

    public static String success(String text) {
        return Ansi.ansi().fgBright(Ansi.Color.GREEN).bold().a("[✓] " + text).reset().toString();
    }

    public static String error(String text) {
        return Ansi.ansi().fgBright(Ansi.Color.RED).bold().a("[✗] " + text).reset().toString();
    }

    public static String warning(String text) {
        return Ansi.ansi().fgBright(Ansi.Color.YELLOW).bold().a("[!] " + text).reset().toString();
    }

    public static String info(String text) {
        return Ansi.ansi().fgBright(Ansi.Color.CYAN).bold().a("[i] " + text).reset().toString();
    }

    public static String selected(String text) {
        return Ansi.ansi()
                .bgBright(Ansi.Color.WHITE)
                .fg(Ansi.Color.BLACK)
                .bold()
                .a(" > " + text + " ")
                .reset()
                .toString();
    }

    public static String dim(String text) {
        return Ansi.ansi().fgBright(Ansi.Color.BLACK).a(text).reset().toString();
    }

    // === Цветной текст для ролей ===
    public static String roleUser(String text) {
        return white(text);
    }

    public static String rolePassHolder(String text) {
        return brightGreen(text);
    }

    public static String roleModerator(String text) {
        return brightBlue(text);
    }

    public static String roleElder(String text) {
        return brightPurple(text);
    }

    public static String roleCreator(String text) {
        return brightRed(text);
    }

    // === Очистка экрана ===
    public static String clearScreen() {
        return Ansi.ansi().eraseScreen().cursor(1, 1).toString();
    }

    // === Прогресс бар символы ===
    public static String progressChar() {
        return Ansi.ansi().fgBright(Ansi.Color.CYAN).a("█").reset().toString();
    }

    public static String progressEmpty() {
        return Ansi.ansi().fg(Ansi.Color.BLACK).a("░").reset().toString();
    }
}