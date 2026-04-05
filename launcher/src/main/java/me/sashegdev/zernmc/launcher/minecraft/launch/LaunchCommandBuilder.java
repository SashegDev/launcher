package me.sashegdev.zernmc.launcher.minecraft.launch;

import me.sashegdev.zernmc.launcher.minecraft.Instance;
import me.sashegdev.zernmc.launcher.minecraft.model.LaunchOptions;
import me.sashegdev.zernmc.launcher.utils.ZAnsi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Генерирует полную команду запуска Minecraft (Vanilla / Fabric / Forge)
 */
public class LaunchCommandBuilder {

    private final Instance instance;

    public LaunchCommandBuilder(Instance instance) {
        this.instance = instance;
    }

    public List<String> build(LaunchOptions options) throws Exception {
        System.out.println(ZAnsi.cyan("Генерация команды запуска для " + instance.getName() + "..."));

        List<String> command = new ArrayList<>();

        // 1. Путь к Java
        String javaPath = getJavaPath();
        command.add(javaPath);

        // 2. JVM аргументы
        command.addAll(getJvmArguments(options));

        // 3. Natives
        Path nativesDir = instance.getPath().resolve("natives");
        command.add("-Djava.library.path=" + nativesDir.toAbsolutePath());

        // 4. Classpath
        String classpath = buildClasspath();
        command.add("-cp");
        command.add(classpath);

        // 5. Главный класс
        String mainClass = getMainClass();
        command.add(mainClass);

        // 6. Аргументы Minecraft
        command.addAll(getMinecraftArguments(options));

        return command;
    }

    private String getJavaPath() {
        // Пока берём системную java. Позже можно добавить выбор из ~/.zernmc/jre/
        return "java";
    }

    private List<String> getJvmArguments(LaunchOptions options) {
        List<String> jvmArgs = new ArrayList<>();

        // Выделенная память
        int ramMB = options.getMaxMemory() > 0 ? options.getMaxMemory() : 2048;
        jvmArgs.add("-Xmx" + ramMB + "M");
        jvmArgs.add("-Xms" + Math.max(512, ramMB / 2) + "M");

        // Стандартные оптимизации
        jvmArgs.add("-XX:+UseG1GC");
        jvmArgs.add("-XX:+UnlockExperimentalVMOptions");
        jvmArgs.add("-XX:G1NewSizePercent=20");
        jvmArgs.add("-XX:G1ReservePercent=20");
        jvmArgs.add("-XX:MaxGCPauseMillis=50");
        jvmArgs.add("-XX:G1HeapRegionSize=32M");

        // Дополнительные JVM аргументы из настроек пользователя
        if (options.getExtraJvmArgs() != null && !options.getExtraJvmArgs().isEmpty()) {
            jvmArgs.addAll(options.getExtraJvmArgs());
        }

        return jvmArgs;
    }

    private String buildClasspath() throws Exception {
        List<String> paths = new ArrayList<>();
    
        String versionId = getVersionId();
        Path versionsDir = instance.getPath().resolve("versions");
    
        // 1. Основной jar версии (fabric-loader-...-1.20.1.jar)
        paths.add(versionsDir.resolve(versionId).resolve(versionId + ".jar").toAbsolutePath().toString());
    
        // 2. Все библиотеки
        Path librariesDir = instance.getPath().resolve("libraries");
        if (Files.exists(librariesDir)) {
            try (var stream = Files.walk(librariesDir)) {
                stream.filter(p -> p.toString().endsWith(".jar"))
                      .map(p -> p.toAbsolutePath().toString())
                      .forEach(paths::add);
            }
        }
    
        // Для Windows используем ";" вместо ":"
        String separator = System.getProperty("os.name").toLowerCase().contains("win") ? ";" : ":";
        return String.join(separator, paths);
    }

    private String getMainClass() {
        String loaderType = instance.getLoaderType().toLowerCase();

        if ("fabric".equals(loaderType)) {
            String loaderVer = instance.getLoaderVersion();
            if (loaderVer != null && loaderVer.startsWith("0.9")) {
                return "net.fabricmc.loader.impl.launch.knot.KnotClient";
            } else {
                // Для более новых версий Fabric (0.14+)
                return "net.fabricmc.loader.impl.launch.knot.KnotClient";
            }
        } 
        else if ("forge".equals(loaderType)) {
            return "net.minecraftforge.client.loading.ClientModLoader";
        } 
        else {
            return "net.minecraft.client.main.Main"; // Vanilla
        }
    }

    private List<String> getMinecraftArguments(LaunchOptions options) {
        List<String> args = new ArrayList<>();

        args.add("--version");
        args.add(instance.getName());

        args.add("--gameDir");
        args.add(instance.getPath().toAbsolutePath().toString());

        args.add("--assetsDir");
        args.add(instance.getPath().resolve("assets").toAbsolutePath().toString());

        args.add("--assetIndex");
        args.add(instance.getAssetIndex());

        args.add("--username");
        args.add(options.getUsername() != null ? options.getUsername() : "Player");

        args.add("--accessToken");
        args.add("0");   // потом токен от блядкого сервера

        args.add("--uuid");
        args.add("00000000-0000-0000-0000-000000000000");  // тоже потом от блядкого сервера

        args.add("--userType");
        args.add("legacy");

        // Дополнительные параметры
        if (options.getWidth() > 0) {
            args.add("--width");
            args.add(String.valueOf(options.getWidth()));
        }
        if (options.getHeight() > 0) {
            args.add("--height");
            args.add(String.valueOf(options.getHeight()));
        }

        return args;
    }

    private String getVersionId() {
        if ("vanilla".equalsIgnoreCase(instance.getLoaderType())) {
            return instance.getMinecraftVersion();
        } else {
            // Для Fabric/Forge версия выглядит как fabric-loader-... или forge-...
            return instance.getMinecraftVersion() + "-" + instance.getLoaderType() + "-" + instance.getLoaderVersion();
        }
    }

    
}