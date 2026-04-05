package me.sashegdev.zernmc.launcher.minecraft;

import me.sashegdev.zernmc.launcher.minecraft.installer.FabricInstaller;
import me.sashegdev.zernmc.launcher.minecraft.installer.ForgeInstaller;
import me.sashegdev.zernmc.launcher.minecraft.installer.VersionInstaller;
import me.sashegdev.zernmc.launcher.minecraft.launch.LaunchCommandBuilder;
import me.sashegdev.zernmc.launcher.minecraft.model.LaunchOptions;
import me.sashegdev.zernmc.launcher.utils.ConsoleUtils;
import me.sashegdev.zernmc.launcher.utils.ZAnsi;

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
        boolean success = installer.install(versionId);

        if (success) {
            instance.setMinecraftVersion(versionId);
            instance.setLoaderType("vanilla");
        }
        return success;
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
    
        LaunchCommandBuilder builder = new LaunchCommandBuilder(instance);
        List<String> command = builder.build(options);
    
        System.out.println(ZAnsi.cyan("Команда запуска (" + command.size() + " аргументов):"));
        for (String arg : command) {
            System.out.println("  " + arg);
        }
    
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(instance.getPath().toFile());
    
        // Важно: перенаправляем вывод Minecraft в консоль лаунчера
        pb.redirectOutput(ProcessBuilder.Redirect.INHERIT);
        pb.redirectError(ProcessBuilder.Redirect.INHERIT);
        pb.redirectInput(ProcessBuilder.Redirect.INHERIT);
    
        System.out.println(ZAnsi.brightGreen("\nЗапускаем Minecraft...\n"));
        ConsoleUtils.clearScreen();   // очищаем TUI перед запуском игры
    
        Process process = pb.start();
    
        // Ждём завершения игры
        int exitCode = process.waitFor();
    
        System.out.println(ZAnsi.yellow("\nMinecraft завершился с кодом: " + exitCode));
    }

    public Instance getInstance() {
        return instance;
    }
}