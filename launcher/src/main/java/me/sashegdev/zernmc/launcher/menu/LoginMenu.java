package me.sashegdev.zernmc.launcher.menu;

import me.sashegdev.zernmc.launcher.auth.AuthManager;
import me.sashegdev.zernmc.launcher.auth.AuthManager.AuthResult;
import me.sashegdev.zernmc.launcher.ui.ArrowMenu;
import me.sashegdev.zernmc.launcher.utils.ConsoleUtils;
import me.sashegdev.zernmc.launcher.utils.Input;
import me.sashegdev.zernmc.launcher.utils.ZAnsi;

import java.io.IOException;
import java.util.List;

/**
 * Экран входа/регистрации.
 * Показывается при старте лаунчера, если нет сохранённой сессии.
 *
 * show() возвращает true  — пользователь вошёл/зарегистрировался
 *                   false — пользователь выбрал выход из лаунчера
 */
public class LoginMenu {

    /**
     * Главный экран выбора действия.
     */
    public boolean show() throws IOException {
        while (true) {
            ConsoleUtils.clearScreen();
            printBanner();

            List<String> options = List.of(
                    "Войти в аккаунт",
                    "Создать аккаунт",
                    "Выйти из лаунчера"
            );

            ArrowMenu menu = new ArrowMenu("Добро пожаловать в ZernMC!", options);
            int choice = menu.show();

            if (choice == -1 || choice == 2) return false;

            boolean success = switch (choice) {
                case 0 -> doLogin();
                case 1 -> doRegister();
                default -> false;
            };

            if (success) return true;
            // Если не успех — покажем меню снова (ошибка уже напечатана внутри методов)
        }
    }

    /**
     * Показывается когда пользователь уже вошёл — предлагает выйти из аккаунта.
     */
    public void showAccountMenu() throws IOException {
        ConsoleUtils.clearScreen();

        System.out.println(ZAnsi.header("=== Аккаунт ==="));
        System.out.println();
        System.out.println(ZAnsi.white("  Игрок: ") + ZAnsi.brightGreen(AuthManager.getUsername()));
        System.out.println(ZAnsi.white("  UUID:  ") + ZAnsi.cyan(AuthManager.getUuid()));
        System.out.println();

        List<String> options = List.of(
                "Выйти из аккаунта",
                "Назад"
        );

        ArrowMenu menu = new ArrowMenu("Управление аккаунтом", options);
        int choice = menu.show();

        if (choice == 0) {
            AuthManager.logout();
            System.out.println(ZAnsi.yellow("Вы вышли из аккаунта."));
            ConsoleUtils.pause();
        }
    }

    // ====================== ПРИВАТНЫЕ МЕТОДЫ ======================

    private boolean doLogin() throws IOException {
        ConsoleUtils.clearScreen();
        printBanner();
        System.out.println(ZAnsi.cyan("  [ Вход в аккаунт ]"));
        System.out.println();

        String username = Input.readLine(ZAnsi.white("  Имя пользователя: "));
        if (username.isEmpty()) return false;

        String password = readPassword("  Пароль: ");
        if (password.isEmpty()) return false;

        System.out.println();
        System.out.print(ZAnsi.cyan("  Выполняем вход..."));

        AuthResult result = AuthManager.login(username, password);

        if (result.success) {
            System.out.println("\r" + ZAnsi.brightGreen("  Добро пожаловать, " + AuthManager.getUsername() + "!    "));
            ConsoleUtils.pause();
            return true;
        } else {
            System.out.println("\r" + ZAnsi.brightRed("  Ошибка: " + result.error + "    "));
            ConsoleUtils.pause();
            return false;
        }
    }

    private boolean doRegister() throws IOException {
        ConsoleUtils.clearScreen();
        printBanner();
        System.out.println(ZAnsi.cyan("  [ Создание аккаунта ]"));
        System.out.println();
        System.out.println(ZAnsi.yellow("  Допустимые символы в имени: a-z, A-Z, 0-9, _"));
        System.out.println(ZAnsi.yellow("  Длина имени: 3–16 символов | Длина пароля: от 6 символов"));
        System.out.println();

        String username = Input.readLine(ZAnsi.white("  Имя пользователя: "));
        if (username.isEmpty()) return false;

        String password = readPassword("  Пароль: ");
        if (password.isEmpty()) return false;

        String confirm = readPassword("  Повторите пароль: ");
        if (!password.equals(confirm)) {
            System.out.println(ZAnsi.brightRed("\n  Пароли не совпадают!"));
            ConsoleUtils.pause();
            return false;
        }

        System.out.println();
        System.out.print(ZAnsi.cyan("  Создаём аккаунт..."));

        AuthResult result = AuthManager.register(username, password);

        if (result.success) {
            System.out.println("\r" + ZAnsi.brightGreen("  Аккаунт создан! Добро пожаловать, " + AuthManager.getUsername() + "!    "));
            ConsoleUtils.pause();
            return true;
        } else {
            System.out.println("\r" + ZAnsi.brightRed("  Ошибка: " + result.error + "    "));
            ConsoleUtils.pause();
            return false;
        }
    }

    /**
     * Читаем пароль — стараемся скрыть вывод через Console,
     * если недоступно (IDE/терминал без TTY) — читаем обычным способом.
     */
    private String readPassword(String prompt) {
        java.io.Console console = System.console();
        if (console != null) {
            char[] chars = console.readPassword(prompt);
            return chars != null ? new String(chars) : "";
        }
        // Fallback: в IDE пароль будет виден
        return Input.readLine(prompt);
    }

    private void printBanner() {
        System.out.println(ZAnsi.header("╔══════════════════════════════╗"));
        System.out.println(ZAnsi.header("║      ZernMC Launcher         ║"));
        System.out.println(ZAnsi.header("╚══════════════════════════════╝"));
        System.out.println();
    }
}
