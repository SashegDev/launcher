package me.sashegdev.zernmc.launcher.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class Config {

    private static final Path CONFIG_DIR = Path.of(System.getProperty("user.home"), ".zernmc");
    private static final Path CONFIG_FILE = CONFIG_DIR.resolve("launcher.properties");

    private static final String BUILD_PROFILE = System.getProperty("build.profile", "global");

    private static final Properties props = new Properties();

    // Настройки
    private static int maxMemory = 4096;           // будет перезаписано умной логикой
    private static String serverUrl = "http://87.120.187.36:1582";
    private static String lastUsername = "Player";

    static {
        load();
        applySmartRamRecommendation();
    }

    private static void load() {
        try {
            Files.createDirectories(CONFIG_DIR);
            if (Files.exists(CONFIG_FILE)) {
                try (var is = Files.newInputStream(CONFIG_FILE)) {
                    props.load(is);
                }
            }

            maxMemory = Integer.parseInt(props.getProperty("maxMemory", "4096"));
            serverUrl = props.getProperty("serverUrl", serverUrl);
            lastUsername = props.getProperty("lastUsername", lastUsername);

        } catch (Exception e) {
            System.err.println(ZAnsi.brightRed("Не удалось загрузить конфиг: ") + e.getMessage());
        }
    }

    public static void save() {
        try {
            props.setProperty("maxMemory", String.valueOf(maxMemory));
            props.setProperty("serverUrl", serverUrl);
            props.setProperty("lastUsername", lastUsername);

            try (var os = Files.newOutputStream(CONFIG_FILE)) {
                props.store(os, "ZernMC Launcher Configuration");
            }
        } catch (IOException e) {
            System.err.println(ZAnsi.brightRed("Не удалось сохранить конфиг: ") + e.getMessage());
        }
    }

    /**
     * Умная рекомендация RAM:
     * - минимум 1.5 GB
     * - рекомендуется totalRAM - 30%
     * - максимум 70% от доступной RAM
     */
    private static void applySmartRamRecommendation() {
        long totalRamMB = Runtime.getRuntime().maxMemory() / (1024 * 1024); // в MB

        // Рекомендуемое значение = total - 30%
        long recommended = (long) (totalRamMB * 0.70);   // 70% от доступной

        // Ограничения
        recommended = Math.max(1536, recommended);           // минимум 1.5 GB
        recommended = Math.min(recommended, totalRamMB - 1024); // оставляем минимум 1 GB системе

        // Если текущее значение сильно отличается от рекомендуемого — корректируем
        if (Math.abs(maxMemory - recommended) > 1024) {  // разница больше 1 GB
            maxMemory = (int) recommended;
            save(); // сохраняем умную рекомендацию
            System.out.println(ZAnsi.cyan("Автоматически рекомендовано RAM: " + maxMemory + " MB"));
        }
    }

    // Getters & Setters
    public static int getMaxMemory() {
        return maxMemory;
    }

    public static boolean isZernMCBuild() {
        return "zernmc".equalsIgnoreCase(BUILD_PROFILE);
    }

    public static boolean isGlobalBuild() {
        return !isZernMCBuild();
    }

    public static void setMaxMemory(int memory) {
        // Защита от слишком маленьких/больших значений
        if (memory < 1024) memory = 1536;
        if (memory > 32768) memory = 32768;

        maxMemory = memory;
        save();
    }

    public static String getServerUrl() {
        return serverUrl;
    }

    public static String getLastUsername() {
        return lastUsername;
    }

    public static void setLastUsername(String username) {
        lastUsername = username;
        save();
    }

    public static Path getInstancesDir() {
        return CONFIG_DIR.resolve("instances");
    }

    public static Path getJreDir() {
        return CONFIG_DIR.resolve("jre");
    }

    public static Path getConfigDir() {
        return CONFIG_DIR;
    }

    /**
     * Полезная информация для пользователя
     */
    public static String getRamInfo() {
        long totalMB = Runtime.getRuntime().maxMemory() / (1024 * 1024);
        return "Доступно RAM: " + totalMB + " MB | Рекомендуется: " + maxMemory + " MB";
    }
}