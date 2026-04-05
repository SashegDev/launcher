package me.sashegdev.zernmc.launcher.utils;

import java.util.Scanner;

public class Input {

    private static final Scanner scanner = new Scanner(System.in);

    public static String readLine() {
        return scanner.nextLine().trim();
    }

    public static String readLine(String prompt) {
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

    public static boolean confirm(String prompt) {
        System.out.print(prompt + " (да/нет): ");
        String answer = scanner.nextLine().trim().toLowerCase();
        return answer.equals("да") || answer.equals("y") || answer.equals("yes");
    }
}