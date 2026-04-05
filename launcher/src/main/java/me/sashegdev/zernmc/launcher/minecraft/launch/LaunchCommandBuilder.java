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

        String loaderType = instance.getLoaderType().toLowerCase();
        if ("fabric".equals(loaderType)) {
            jvmArgs.add("--add-modules=ALL-MODULE-PATH");
            jvmArgs.add("--add-opens=java.base/java.io=ALL-UNNAMED");
            jvmArgs.add("--add-opens=java.base/java.util=ALL-UNNAMED");
            jvmArgs.add("--add-opens=java.base/java.lang=ALL-UNNAMED");
            jvmArgs.add("--add-opens=java.base/java.lang.invoke=ALL-UNNAMED");
            jvmArgs.add("--add-opens=java.base/java.nio=ALL-UNNAMED");
            jvmArgs.add("--add-opens=java.base/java.net=ALL-UNNAMED");
            jvmArgs.add("--add-opens=java.base/sun.nio.ch=ALL-UNNAMED");
            jvmArgs.add("--add-opens=java.base/java.lang.reflect=ALL-UNNAMED");
            jvmArgs.add("--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED");
        }

        return jvmArgs;
    }

    private String buildClasspath() throws Exception {
        List<String> paths = new ArrayList<>();

        //String loaderType = instance.getLoaderType().toLowerCase();
        String versionId = getVersionId();

        Path versionJar = instance.getPath()
                .resolve("versions")
                .resolve(versionId)
                .resolve(versionId + ".jar");

        if (Files.exists(versionJar)) {
            paths.add(versionJar.toAbsolutePath().toString());
        } else {
            Path altVersionJar = instance.getPath()
                    .resolve("versions")
                    .resolve(instance.getMinecraftVersion())
                    .resolve(instance.getMinecraftVersion() + ".jar");

            if (Files.exists(altVersionJar)) {
                paths.add(altVersionJar.toAbsolutePath().toString());
            } else {
                System.err.println(ZAnsi.yellow("Warning: Vanilla Minecraft jar not found at: " + versionJar));
            }
        }

        Path librariesDir = instance.getPath().resolve("libraries");
        if (Files.exists(librariesDir)) {
            try (var stream = Files.walk(librariesDir)) {
                stream.filter(p -> p.toString().endsWith(".jar"))
                      .map(p -> p.toAbsolutePath().toString())
                      .forEach(paths::add);
            }
        }

        String separator = System.getProperty("os.name").toLowerCase().contains("win") ? ";" : ":";
        return String.join(separator, paths);
    }

    private String getMainClass() {
        String loaderType = instance.getLoaderType().toLowerCase();

        if ("fabric".equals(loaderType)) {
            // Fabric 0.14+ использует KnotClient
            return "net.fabricmc.loader.impl.launch.knot.KnotClient";
        } 
        else if ("forge".equals(loaderType)) {
            // Forge 1.20.1 использует ClientModLoader
            return "net.minecraftforge.client.loading.ClientModLoader";
        } 
        else {
            return "net.minecraft.client.main.Main";
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
        String loaderType = instance.getLoaderType().toLowerCase();
        String mcVersion = instance.getMinecraftVersion();
        String loaderVer = instance.getLoaderVersion();

        if ("vanilla".equals(loaderType)) {
            return mcVersion;
        } 
        else if ("fabric".equals(loaderType)) {
            return mcVersion;
        } 
        else if ("forge".equals(loaderType)) {
            return mcVersion + "-forge-" + loaderVer;
        }

        return mcVersion;
    }

    
}