package me.sashegdev.zernmc.launcher.minecraft.launch;

import me.sashegdev.zernmc.launcher.minecraft.Instance;
import me.sashegdev.zernmc.launcher.minecraft.model.LaunchOptions;
import me.sashegdev.zernmc.launcher.utils.ZAnsi;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
        if (!Files.exists(nativesDir)) {
            Files.createDirectories(nativesDir);
        }
        command.add("-Djava.library.path=" + nativesDir.toAbsolutePath());

        String loaderType = instance.getLoaderType().toLowerCase();
        
        if ("forge".equals(loaderType)) {
            command.addAll(getForgeJvmArguments());
            command.add("-cp");
            command.add(buildForgeClasspath());
            command.add("cpw.mods.modlauncher.Launcher");
            command.addAll(getForgeArguments(options));
        } else {
            command.add("-cp");
            command.add(buildClasspath());
            command.add(getMainClass());
            command.addAll(getMinecraftArguments(options));
        }

        return command;
    }

    private String getJavaPath() {
        return "java";
    }

    private List<String> getJvmArguments(LaunchOptions options) {
        List<String> jvmArgs = new ArrayList<>();

        int ramMB = options.getMaxMemory() > 0 ? options.getMaxMemory() : 4096;
        jvmArgs.add("-Xmx" + ramMB + "M");
        jvmArgs.add("-Xms" + Math.max(512, ramMB / 2) + "M");

        jvmArgs.add("-XX:+UseG1GC");
        jvmArgs.add("-XX:+UnlockExperimentalVMOptions");
        jvmArgs.add("-XX:G1NewSizePercent=20");
        jvmArgs.add("-XX:G1ReservePercent=20");
        jvmArgs.add("-XX:MaxGCPauseMillis=50");
        jvmArgs.add("-XX:G1HeapRegionSize=32M");

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

        if (options.getExtraJvmArgs() != null && !options.getExtraJvmArgs().isEmpty()) {
            jvmArgs.addAll(options.getExtraJvmArgs());
        }

        return jvmArgs;
    }

    private List<String> getForgeJvmArguments() {
        List<String> jvmArgs = new ArrayList<>();
        
        jvmArgs.add("--add-modules=ALL-MODULE-PATH");
        jvmArgs.add("--add-opens=java.base/java.util.jar=ALL-UNNAMED");
        jvmArgs.add("--add-opens=java.base/java.lang.invoke=ALL-UNNAMED");
        jvmArgs.add("--add-opens=java.base/java.lang.reflect=ALL-UNNAMED");
        jvmArgs.add("--add-opens=java.base/java.io=ALL-UNNAMED");
        jvmArgs.add("--add-opens=java.base/java.nio=ALL-UNNAMED");
        jvmArgs.add("--add-opens=java.base/java.net=ALL-UNNAMED");
        jvmArgs.add("--add-opens=java.base/java.util=ALL-UNNAMED");
        jvmArgs.add("--add-opens=java.base/sun.nio.ch=ALL-UNNAMED");
        jvmArgs.add("--add-opens=jdk.unsupported/sun.misc=ALL-UNNAMED");
        
        jvmArgs.add("-Dforge.logging.console.level=debug");
        jvmArgs.add("-Dforge.logging.mojang.level=info");
        jvmArgs.add("-DignoreList=bootstraplauncher,securejarhandler,asm-commons,asm-util,asm-analysis,asm-tree,asm,JarJarFileSystems,client-extra,fmlcore,javafmllanguage,lowcodelanguage,mclanguage,forge-");
        jvmArgs.add("-DmergeModules=jna-5.10.0.jar,jna-platform-5.10.0.jar");
        
        return jvmArgs;
    }

    private String buildClasspath() throws Exception {
        List<String> paths = new ArrayList<>();

        String versionId = getVersionId();

        Path versionJar = instance.getPath()
                .resolve("versions")
                .resolve(versionId)
                .resolve(versionId + ".jar");

        if (Files.exists(versionJar)) {
            paths.add(versionJar.toAbsolutePath().toString());
        } else {
            String mcVersion = instance.getMinecraftVersion();
            Path fallbackJar = instance.getPath()
                    .resolve("versions")
                    .resolve(mcVersion)
                    .resolve(mcVersion + ".jar");
            if (Files.exists(fallbackJar)) {
                paths.add(fallbackJar.toAbsolutePath().toString());
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

    private String buildForgeClasspath() throws Exception {
        List<String> paths = new ArrayList<>();

        String versionId = getVersionId();
        String mcVersion = instance.getMinecraftVersion();
        String forgeVersion = instance.getLoaderVersion();

        Path librariesDir = instance.getPath().resolve("libraries");
        if (Files.exists(librariesDir)) {
            try (var stream = Files.walk(librariesDir)) {
                stream.filter(p -> p.toString().endsWith(".jar"))
                      .map(p -> p.toAbsolutePath().toString())
                      .forEach(paths::add);
            }
        }

        Path versionJar = instance.getPath()
                .resolve("versions")
                .resolve(versionId)
                .resolve(versionId + ".jar");
        if (Files.exists(versionJar)) {
            paths.add(0, versionJar.toAbsolutePath().toString());
        } else {
            Path vanillaJar = instance.getPath()
                    .resolve("versions")
                    .resolve(mcVersion)
                    .resolve(mcVersion + ".jar");
            if (Files.exists(vanillaJar)) {
                paths.add(0, vanillaJar.toAbsolutePath().toString());
            }
        }

        Path forgeUniversal = instance.getPath()
                .resolve("libraries")
                .resolve("net")
                .resolve("minecraftforge")
                .resolve("forge")
                .resolve(mcVersion + "-" + forgeVersion)
                .resolve("forge-" + mcVersion + "-" + forgeVersion + "-universal.jar");
        if (Files.exists(forgeUniversal)) {
            paths.add(forgeUniversal.toAbsolutePath().toString());
        }

        Path forgeClient = instance.getPath()
                .resolve("libraries")
                .resolve("net")
                .resolve("minecraftforge")
                .resolve("forge")
                .resolve(mcVersion + "-" + forgeVersion)
                .resolve("forge-" + mcVersion + "-" + forgeVersion + "-client.jar");
        if (Files.exists(forgeClient)) {
            paths.add(forgeClient.toAbsolutePath().toString());
        }

        String[] forgeModules = {"fmlcore", "javafmllanguage", "lowcodelanguage", "mclanguage"};
        for (String module : forgeModules) {
            Path modulePath = instance.getPath()
                    .resolve("libraries")
                    .resolve("net")
                    .resolve("minecraftforge")
                    .resolve(module)
                    .resolve(mcVersion + "-" + forgeVersion)
                    .resolve(module + "-" + mcVersion + "-" + forgeVersion + ".jar");
            if (Files.exists(modulePath)) {
                paths.add(modulePath.toAbsolutePath().toString());
            }
        }

        String separator = System.getProperty("os.name").toLowerCase().contains("win") ? ";" : ":";
        return String.join(separator, paths);
    }

    private String getMainClass() {
        String loaderType = instance.getLoaderType().toLowerCase();

        if ("fabric".equals(loaderType)) {
            return "net.fabricmc.loader.impl.launch.knot.KnotClient";
        } 
        else if ("forge".equals(loaderType)) {
            return "cpw.mods.modlauncher.Launcher";
        } 
        else {
            return "net.minecraft.client.main.Main";
        }
    }

    /**
     * ИСПРАВЛЕНО: используем instance.getAssetIndex() вместо minecraftVersion
     */
    private List<String> getMinecraftArguments(LaunchOptions options) {
        List<String> args = new ArrayList<>();

        args.add("--version");
        args.add(instance.getName());

        args.add("--gameDir");
        args.add(instance.getPath().toAbsolutePath().toString());

        args.add("--assetsDir");
        args.add(instance.getPath().resolve("assets").toAbsolutePath().toString());

        // FIXED: Используем правильный assetIndex
        args.add("--assetIndex");
        String assetIndex = instance.getAssetIndex();
        if (assetIndex == null || assetIndex.isEmpty()) {
            assetIndex = instance.getMinecraftVersion();
            System.out.println(ZAnsi.yellow("Asset index не найден, использую версию: " + assetIndex));
        } else {
            System.out.println(ZAnsi.green("Использую asset index: " + assetIndex));
        }
        args.add(assetIndex);

        args.add("--username");
        args.add(options.getUsername() != null ? options.getUsername() : "Player");

        args.add("--accessToken");
        args.add(options.getAccessToken() != null ? options.getAccessToken() : "0");

        args.add("--uuid");
        args.add(options.getUuid() != null ? options.getUuid() : "00000000-0000-0000-0000-000000000000");

        args.add("--userType");
        args.add("legacy");

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

    /**
     * ИСПРАВЛЕНО: для Forge тоже используем правильный assetIndex
     */
    private List<String> getForgeArguments(LaunchOptions options) {
        List<String> args = new ArrayList<>();
        
        args.add("--launchTarget");
        args.add("forgeclient");
        
        args.add("--fml.forgeVersion");
        args.add(instance.getLoaderVersion());
        
        args.add("--fml.mcVersion");
        args.add(instance.getMinecraftVersion());
        
        args.add("--fml.forgeGroup");
        args.add("net.minecraftforge");
        
        args.add("--gameDir");
        args.add(instance.getPath().toAbsolutePath().toString());
        
        args.add("--assetsDir");
        args.add(instance.getPath().resolve("assets").toAbsolutePath().toString());
        
        // FIXED: Используем правильный assetIndex для Forge
        args.add("--assetIndex");
        String assetIndex = instance.getAssetIndex();
        if (assetIndex == null || assetIndex.isEmpty()) {
            assetIndex = instance.getMinecraftVersion();
        }
        args.add(assetIndex);
        
        args.add("--username");
        args.add(options.getUsername() != null ? options.getUsername() : "Player");
        
        args.add("--accessToken");
        args.add(options.getAccessToken() != null ? options.getAccessToken() : "0");
        
        args.add("--uuid");
        args.add(options.getUuid() != null ? options.getUuid() : "00000000-0000-0000-0000-000000000000");
        
        args.add("--userType");
        args.add("legacy");
        
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

    /**
     * ИСПРАВЛЕНО: для Fabric используем сохраненный fabricVersionId
     */
    private String getVersionId() {
        String loaderType = instance.getLoaderType().toLowerCase();
        String mcVersion = instance.getMinecraftVersion();
        String loaderVer = instance.getLoaderVersion();

        if ("vanilla".equals(loaderType)) {
            return mcVersion;
        } 
        else if ("fabric".equals(loaderType)) {
            // Используем сохраненный fabricVersionId если есть
            String fabricId = instance.getFabricVersionId();
            if (fabricId != null && !fabricId.isEmpty()) {
                return fabricId;
            }
            // fallback
            return "fabric-loader-" + loaderVer + "-" + mcVersion;
        } 
        else if ("forge".equals(loaderType)) {
            return mcVersion + "-forge-" + loaderVer;
        }

        return mcVersion;
    }
}