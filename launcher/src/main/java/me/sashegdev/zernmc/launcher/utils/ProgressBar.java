package me.sashegdev.zernmc.launcher.utils;

import java.text.DecimalFormat;

public class ProgressBar {

    private static final int BAR_LENGTH = 40;
    private static final DecimalFormat DF = new DecimalFormat("#.##");

    /**
     * Прогресс по количеству файлов (для библиотек и общего прогресса)
     */
    public static void show(String label, long current, long total, String unit) {
        if (total <= 0) {
            System.out.print("\r" + ZAnsi.cyan(label) + " ...");
            return;
        }
        double progress = (double) current / total;
        int filled = (int) (progress * BAR_LENGTH);
        String bar = "█".repeat(filled) + "░".repeat(BAR_LENGTH - filled);
        int percent = (int) (progress * 100);

        String text = String.format("%s [%s] %3d%% (%d/%d %s)",
                ZAnsi.cyan(label), bar, percent, current, total, unit);

        System.out.print("\r" + text);
        System.out.flush();
    }

    /**
     * Прогресс по байтам для одного файла (реальный прогресс)
     */
    public static void showDownload(String label, long downloaded, long totalBytes) {
        if (totalBytes <= 0) {
            System.out.print("\r" + ZAnsi.cyan(label) + " ...");
            return;
        }

        double progress = (double) downloaded / totalBytes;
        int filled = (int) (progress * BAR_LENGTH);
        String bar = "█".repeat(filled) + "░".repeat(BAR_LENGTH - filled);
        String percent = DF.format(progress * 100);

        String text = String.format("%s [%s] %6s%%  %s / %s",
                ZAnsi.cyan(label),
                bar,
                percent,
                formatBytes(downloaded),
                formatBytes(totalBytes));

        System.out.print("\r" + text);
        System.out.flush();
    }

    public static void showAnimated(String label, long current, long total, String unit) {
        if (total <= 0) {
            // Анимация для неизвестного размера
            char[] spinner = {'|', '/', '-', '\\'};
            int idx = (int) (current / 1024) % 4;
            System.out.print("\r" + label + " [" + spinner[idx] + "] " + formatBytes(current));
        } else {
            show(label, (int) ((current * 100) / total), 100, unit);
        }
    }

    public static void finish(String message) {
        System.out.println("\r" + ZAnsi.brightGreen(message + " завершено ✓"));
        System.out.flush();
    }

    public static void clearLine() {
        System.out.print("\r" + " ".repeat(110) + "\r");
        System.out.flush();
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return DF.format(bytes / 1024.0) + " KB";
        return DF.format(bytes / (1024.0 * 1024)) + " MB";
    }
}