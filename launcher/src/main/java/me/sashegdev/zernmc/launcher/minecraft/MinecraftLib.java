package me.sashegdev.zernmc.launcher.minecraft;

import me.sashegdev.zernmc.launcher.minecraft.installer.FabricInstaller;
import me.sashegdev.zernmc.launcher.minecraft.installer.ForgeInstaller;
import me.sashegdev.zernmc.launcher.minecraft.installer.VersionInstaller;
import me.sashegdev.zernmc.launcher.minecraft.launch.LaunchCommandBuilder;
import me.sashegdev.zernmc.launcher.minecraft.model.LaunchOptions;
import me.sashegdev.zernmc.launcher.utils.ConsoleUtils;
import me.sashegdev.zernmc.launcher.utils.ZAnsi;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class MinecraftLib {

    private final Instance instance;

    public MinecraftLib(Instance instance) {
        this.instance = instance;
    }

    //Очистка


    //Установка
    public boolean installMinecraft(String versionId) throws Exception {
        VersionInstaller installer = new VersionInstaller(instance.getPath());
        
        String assetIndex = installer.install(versionId);   // ← теперь возвращается String
    
        if (assetIndex != null && !assetIndex.isEmpty()) {
            instance.setMinecraftVersion(versionId);
            instance.setAssetIndex(assetIndex);      // ← сохраняем правильный индекс!
            instance.setLoaderType("vanilla");
            return true;
        }
        return false;
    }

    public boolean installForge(String minecraftVersion, String forgeVersion) throws Exception {
       ForgeInstaller installer = new ForgeInstaller(instance);
       return installer.install(minecraftVersion, forgeVersion);
    }

    public boolean installFabric(String minecraftVersion, String loaderVersion) throws Exception {
        FabricInstaller installer = new FabricInstaller(instance);
        boolean success = installer.install(minecraftVersion, loaderVersion);

        if (success) {
            // Сохраняем информацию в Instance
            instance.setMinecraftVersion(minecraftVersion);
            instance.setLoaderType("fabric");
            instance.setLoaderVersion(loaderVersion);
        }
        return success;
    }

    /**
     * Полная установка сборки (vanilla + loader + моды)
     * Пока заглушка — будем расширять
     */
    public boolean installPack(String packName, String minecraftVersion, String loaderType, String loaderVersion) throws Exception {
        System.out.println(ZAnsi.cyan("Начинается полная установка сборки: " + packName));

        // 1. Устанавливаем Minecraft
        boolean mcInstalled = installMinecraft(minecraftVersion);
        if (!mcInstalled) {
            System.out.println(ZAnsi.brightRed("Не удалось установить Minecraft " + minecraftVersion));
            return false;
        }

        // 2. Устанавливаем лоадер
        if ("fabric".equalsIgnoreCase(loaderType)) {
            boolean fabricInstalled = installFabric(minecraftVersion, loaderVersion);
            if (!fabricInstalled) {
                System.out.println(ZAnsi.brightRed("Не удалось установить Fabric"));
                return false;
            }
        } else if ("forge".equalsIgnoreCase(loaderType)) {
            System.out.println(ZAnsi.yellow("Forge пока не поддерживается"));
            return false;
        }

        // 3. В будущем здесь будет diff и скачивание модов

        System.out.println(ZAnsi.brightGreen("Базовая установка сборки завершена!"));
        return true;
    }

    //Запуск
    public void launch(LaunchOptions options) throws Exception {
        System.out.println(ZAnsi.brightGreen("Запуск сборки: " + instance.getName()));
        cleanupOldLoaders();

        LaunchCommandBuilder builder = new LaunchCommandBuilder(instance);
        List<String> command = builder.build(options);

        System.out.println(ZAnsi.cyan("Команда запуска (" + command.size() + " аргументов):"));
        command.forEach(arg -> System.out.println("  " + arg));

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(instance.getPath().toFile());
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        pb.redirectInput(ProcessBuilder.Redirect.INHERIT);

        System.out.println(ZAnsi.brightGreen("\nЗапускаем Minecraft...\n"));
        ConsoleUtils.clearScreen();

        Process process = pb.start();
        int exitCode = process.waitFor();

        System.out.println(ZAnsi.yellow("\nMinecraft завершился с кодом: " + exitCode));
    }

    private void safeDeleteDirectory(Path dir) {
        try {
            Files.walk(dir)
                 .sorted((a, b) -> b.compareTo(a))
                 .forEach(p -> {
                     try { Files.deleteIfExists(p); } 
                     catch (IOException ignored) {}
                 });
        } catch (IOException ignored) {}
    }

    private void deleteOldVersionDirs(Path versionsDir, String keepVersion) throws IOException {
        if (!Files.exists(versionsDir)) return;

        try (var stream = Files.walk(versionsDir)) {
            stream.filter(Files::isDirectory)
                  .filter(dir -> dir.getFileName().toString().contains("fabric-loader") || 
                                 dir.getFileName().toString().contains("forge"))
                  .filter(dir -> !dir.getFileName().toString().contains(keepVersion))
                  .forEach(this::safeDeleteDirectory);
        }
    }

    private void deleteAllExcept(Path baseDir, String keepVersion) throws IOException {
        if (!Files.exists(baseDir)) return;

        try (var stream = Files.walk(baseDir)) {
            stream.filter(Files::isDirectory)
                  .filter(dir -> {
                      String name = dir.getFileName().toString();
                      return name.contains(".") && !name.contains(keepVersion);
                  })
                  .forEach(this::safeDeleteDirectory);
        }
    }

    private void cleanupOldLoaders() throws IOException {
        String loaderType = instance.getLoaderType().toLowerCase();
        String currentLoaderVer = instance.getLoaderVersion();

        if (currentLoaderVer == null) return;

        System.out.println(ZAnsi.yellow("Выполняем очистку старых версий лоадера..."));

        // Удаляем все старые fabric-loader / forge
        Path libraries = instance.getPath().resolve("libraries");

        if ("fabric".equals(loaderType)) {
            deleteAllExcept(libraries.resolve("net/fabricmc/fabric-loader"), currentLoaderVer);
        } else if ("forge".equals(loaderType)) {
            deleteAllExcept(libraries.resolve("net/minecraftforge/forge"), currentLoaderVer);
        }

        // Также чистим versions/ от старых fabric/forge версий
        Path versionsDir = instance.getPath().resolve("versions");
        deleteOldVersionDirs(versionsDir, currentLoaderVer);
    }



    public Instance getInstance() {
        return instance;
    }
}