package me.sashegdev.zernmc.launcher.utils;

import me.sashegdev.zernmc.launcher.ui.ArrowMenu;

import java.io.IOException;
import java.util.List;
import java.util.Scanner;

/**
 * Улучшенный Input с поддержкой кириллицы и confirm через ArrowMenu
 */
public class Input {

    // Используем UTF-8 явно — это помогает на Windows
    private static final Scanner scanner = new Scanner(System.in, "UTF-8");

    public static String readLine() {
        return scanner.nextLine().trim();
    }

    public static String readLine(String prompt) {
        flushInput(); // Очищаем буфер
        System.out.print(prompt);
        return scanner.nextLine().trim();
    }

    public static int readInt(String prompt) {
        while (true) {
            try {
                System.out.print(prompt);
                return Integer.parseInt(scanner.nextLine().trim());
            } catch (NumberFormatException e) {
                System.out.println(ZAnsi.brightRed("Некорректное число. Попробуйте ещё раз."));
            }
        }
    }

    public static int readInt(String prompt, int min, int max) {
        while (true) {
            int value = readInt(prompt);
            if (value >= min && value <= max) {
                return value;
            }
            System.out.println(ZAnsi.brightRed("Значение должно быть от " + min + " до " + max + "."));
        }
    }

    /**
     * Новый confirm через ArrowMenu
     * @throws IOException 
     */
    public static boolean confirm(String question) throws IOException {
        ConsoleUtils.clearScreen(); // опционально, можно убрать

        List<String> options = List.of(
            "Да",
            "Нет"
        );

        ArrowMenu menu = new ArrowMenu(question, options);
        int choice = menu.show();

        return choice == 0; // 0 = "Да"
    }

    /**
     * Альтернативный confirm без очистки экрана
     * @throws IOException 
     */
    public static boolean confirmInline(String question) throws IOException {
        List<String> options = List.of("Да", "Нет");
        ArrowMenu menu = new ArrowMenu(question, options);
        int choice = menu.show();
        return choice == 0;
    }

    /**
     * Закрытие сканнера (вызывать при выходе из программы, если нужно)
     */
    public static void close() {
        scanner.close();
    }


    /**
     * Очищает буфер ввода от оставшихся символов
     */
    public static void flushInput() {
        try {
            while (System.in.available() > 0) {
                System.in.read();
            }
        } catch (IOException e) {
            // Игнорируем
        }
    }
}