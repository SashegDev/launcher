package me.sashegdev.zernmc.launcher.menu;

import me.sashegdev.zernmc.launcher.minecraft.Instance;
import me.sashegdev.zernmc.launcher.minecraft.InstanceManager;
import me.sashegdev.zernmc.launcher.minecraft.MinecraftLib;
import me.sashegdev.zernmc.launcher.minecraft.installer.VersionInstaller;
import me.sashegdev.zernmc.launcher.minecraft.model.LaunchOptions;
import me.sashegdev.zernmc.launcher.minecraft.model.MinecraftVersion;
import me.sashegdev.zernmc.launcher.ui.ArrowMenu;
import me.sashegdev.zernmc.launcher.utils.ConsoleUtils;
import me.sashegdev.zernmc.launcher.utils.Input;
import me.sashegdev.zernmc.launcher.utils.ZAnsi;
import me.sashegdev.zernmc.launcher.utils.ZHttpClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class LaunchMenu {

    public void show() throws Exception {
        while (true) {
            ConsoleUtils.clearScreen();
            List<Instance> instances = InstanceManager.getAllInstances();

            List<String> options = instances.stream()
                    .map(Instance::toString)
                    .collect(Collectors.toList());

            options.add("Установить новую сборку");
            options.add("Назад в главное меню");

            ArrowMenu menu = new ArrowMenu("Управление сборками", options);
            int choice = menu.show();

            if (choice == -1) break;
            if (choice == options.size() - 1) break; // Назад

            if (choice == instances.size()) {
                installNewPack();
                continue;
            }

            Instance selected = instances.get(choice);
            manageInstance(selected);
        }
    }

    private void installNewPack() throws IOException {
        ConsoleUtils.clearScreen();
        System.out.println(ZAnsi.cyan("Получение списка версий Minecraft..."));

        try {
            VersionInstaller versionInstaller = new VersionInstaller(null);
            List<MinecraftVersion> allVersions = versionInstaller.getAvailableVersions();

            List<String> versionOptions = allVersions.stream()
                    .map(v -> v.getId() + "  (" + v.getType() + ")")
                    .collect(Collectors.toList());
            versionOptions.add("Назад");

            ArrowMenu versionMenu = new ArrowMenu("Выбор версии Minecraft", versionOptions);
            int versionChoice = versionMenu.show();

            if (versionChoice == -1 || versionChoice == versionOptions.size() - 1) return;

            MinecraftVersion selectedMc = allVersions.get(versionChoice);
            String mcVersion = selectedMc.getId();

            // === Выбор лоадера с правильной проверкой поддержки ===
            List<String> loaderOptions = buildLoaderOptions(mcVersion);
            ArrowMenu loaderMenu = new ArrowMenu("Выбор модлоадера для " + mcVersion, loaderOptions);
            int loaderChoice = loaderMenu.show();

            if (loaderChoice == -1 || loaderChoice == loaderOptions.size() - 1) return;

            String selectedLoader = loaderOptions.get(loaderChoice);

            if (selectedLoader.contains("Vanilla")) {
                createVanillaInstance(mcVersion);
                return;
            }

            String loaderType = selectedLoader.contains("Fabric") ? "fabric" : "forge";

            String loaderVersion;
            if (loaderType.equals("fabric")) {
                loaderVersion = askFabricLoaderVersion();
            } else {
                loaderVersion = askForgeVersion(mcVersion);
            }

            if (loaderVersion == null) return;

            String packName = askPackName();
            if (packName == null) return;

            InstanceManager.createInstanceFolder(packName);
            Instance newInstance = InstanceManager.getInstance(packName);

            MinecraftLib lib = new MinecraftLib(newInstance);

            boolean success = loaderType.equals("fabric")
                    ? lib.installFabric(mcVersion, loaderVersion)
                    : lib.installForge(mcVersion, loaderVersion);

            if (success) {
                System.out.println(ZAnsi.brightGreen("\nСборка '" + packName + "' успешно установлена!"));
            }

        } catch (Exception e) {
            System.out.println(ZAnsi.brightRed("Ошибка: " + e.getMessage()));
        }

        ConsoleUtils.pause();
    }

    // ====================== Вспомогательные методы ======================

    private List<String> buildLoaderOptions(String mcVersion) {
        List<String> options = new ArrayList<>();

        if (isFabricSupported(mcVersion)) {
            options.add("Fabric");
        }
        if (isForgeSupported(mcVersion)) {
            options.add("Forge");
        }
        options.add("Vanilla");
        options.add("Назад");

        return options;
    }

    private boolean isFabricSupported(String version) {
        // Fabric стабильно работает с 1.14+
        return version.matches("^1\\.(1[4-9]|[2-9]\\d).*");
    }

    private boolean isForgeSupported(String version) {
        // Forge поддерживает примерно до 1.21.4 на текущий момент
        // Для версий 1.22+ и экспериментальных — отключаем
        if (version.matches("^1\\.2[2-9].*") || version.matches("^\\d{2}.*")) {
            return false;
        }
        return version.matches("^1\\.(1[2-9]|[2-9]\\d).*") ||
               version.matches("^1\\.20.*") || version.matches("^1\\.21.*");
    }

    private void manageInstance(Instance instance) throws Exception {
        while (true) {
            ConsoleUtils.clearScreen();
            System.out.println(ZAnsi.header("Управление сборкой: " + instance.getName()));
            System.out.println(ZAnsi.white("Версия: " + instance.getMinecraftVersion()));
            System.out.println(ZAnsi.white("Лоадер: " + instance.getLoaderType() + 
                    (instance.getLoaderVersion() != null ? " " + instance.getLoaderVersion() : "")));

            List<String> options = new ArrayList<>();
            options.add("Запустить сборку");
            options.add("Изменить версию лоадера");
            options.add("Удалить сборку");
            options.add("Назад");

            ArrowMenu menu = new ArrowMenu("Действия", options);
            int choice = menu.show();

            if (choice == -1 || choice == 3) return; // Esc или Назад

            switch (choice) {
                case 0 -> launchExistingInstance(instance);
                case 1 -> changeLoaderVersion(instance);
                case 2 -> deleteInstance(instance);
            }
        }
    }

    private void changeLoaderVersion(Instance instance) throws Exception {
        ConsoleUtils.clearScreen();
        System.out.println(ZAnsi.cyan("Изменение версии лоадера для " + instance.getName()));

        String currentLoader = instance.getLoaderType();
        String mcVersion = instance.getMinecraftVersion();

        if ("vanilla".equalsIgnoreCase(currentLoader)) {
            System.out.println(ZAnsi.yellow("Это vanilla сборка. Нельзя изменить лоадер."));
            ConsoleUtils.pause();
            return;
        }

        String newLoaderVersion;
        if ("fabric".equalsIgnoreCase(currentLoader)) {
            newLoaderVersion = askFabricLoaderVersion();
        } else {
            newLoaderVersion = askForgeVersion(mcVersion);
        }

        if (newLoaderVersion == null) return;

        System.out.println(ZAnsi.cyan("Переустановка лоадера " + currentLoader + " → " + newLoaderVersion + "..."));

        MinecraftLib lib = new MinecraftLib(instance);
        boolean success;

        try {
            if ("fabric".equalsIgnoreCase(currentLoader)) {
                success = lib.installFabric(mcVersion, newLoaderVersion);
            } else {
                success = lib.installForge(mcVersion, newLoaderVersion);
            }

            if (success) {
                System.out.println(ZAnsi.brightGreen("Версия лоадера успешно изменена!"));
            }
        } catch (Exception e) {
            System.out.println(ZAnsi.brightRed("Ошибка при смене лоадера: " + e.getMessage()));
        }

        ConsoleUtils.pause();
    }

    private void deleteInstance(Instance instance) throws IOException {
        ConsoleUtils.clearScreen();
        
        List<String> confirmOptions = List.of(
            "Да, удалить сборку",
            "Нет, отменить"
        );
    
        ArrowMenu confirmMenu = new ArrowMenu(
            "Вы действительно хотите удалить сборку '" + instance.getName() + "'?", 
            confirmOptions
        );
    
        int choice = confirmMenu.show();
    
        if (choice == 0) {  // "Да, удалить"
            boolean deleted = InstanceManager.deleteInstance(instance.getName());
            if (deleted) {
                System.out.println(ZAnsi.brightGreen("Сборка '" + instance.getName() + "' успешно удалена."));
            } else {
                System.out.println(ZAnsi.brightRed("Не удалось удалить сборку."));
            }
        } else {
            System.out.println(ZAnsi.yellow("Удаление отменено."));
        }
    
        ConsoleUtils.pause();
    }

    private String askFabricLoaderVersion() throws Exception {
        System.out.println(ZAnsi.cyan("Получение списка версий Fabric Loader..."));
        List<String> versions = ZHttpClient.getFabricLoaderVersions();

        List<String> options = versions.stream()
                .limit(30)                    // увеличил до 30
                .map(v -> "Fabric Loader " + v)
                .collect(Collectors.toList());
        options.add("Назад");

        ArrowMenu menu = new ArrowMenu("Выбор версии Fabric Loader", options);
        int choice = menu.show();

        if (choice == -1 || choice == options.size() - 1) return null;
        return versions.get(choice);
    }

    private String askForgeVersion(String mcVersion) throws Exception {
        System.out.println(ZAnsi.cyan("Получение списка версий Forge для " + mcVersion + "..."));

        // Получаем все версии Forge из Maven
        List<String> allForgeVersions = getAllForgeVersions();

        // Фильтруем только те, которые подходят под нашу версию Minecraft
        List<String> compatibleVersions = allForgeVersions.stream()
                .filter(v -> v.startsWith(mcVersion + "-"))
                .map(v -> v.substring(mcVersion.length() + 1)) // убираем "1.20.1-" 
                .collect(Collectors.toList());

        if (compatibleVersions.isEmpty()) {
            System.out.println(ZAnsi.yellow("Не найдено совместимых версий Forge для " + mcVersion));
            ConsoleUtils.pause();
            return null;
        }

        List<String> options = compatibleVersions.stream()
                .map(v -> "Forge " + v)
                .collect(Collectors.toList());
        options.add("Назад");

        ArrowMenu menu = new ArrowMenu("Выбор версии Forge для " + mcVersion, options);
        int choice = menu.show();

        if (choice == -1 || choice == options.size() - 1) return null;

        return compatibleVersions.get(choice);
    }

    private String askPackName() {
        System.out.print(ZAnsi.white("\nВведите название новой сборки: "));
        String name = Input.readLine();           // используем наш Input
        if (name.isEmpty()) {
            System.out.println(ZAnsi.yellow("Отменено."));
            return null;
        }
        return name;
    }

    private void createVanillaInstance(String mcVersion) throws Exception {
        String packName = askPackName();
        if (packName == null) return;

        InstanceManager.createInstanceFolder(packName);
        Instance newInstance = InstanceManager.getInstance(packName);

        MinecraftLib lib = new MinecraftLib(newInstance);
        boolean success = lib.installMinecraft(mcVersion);

        if (success) {
            System.out.println(ZAnsi.brightGreen("\nVanilla сборка '" + packName + "' успешно создана!"));
        }
    }

    private void launchExistingInstance(Instance instance) {
        ConsoleUtils.clearScreen();
        System.out.println(ZAnsi.brightGreen("Запуск сборки: " + instance.getName()));

        MinecraftLib lib = new MinecraftLib(instance);
        LaunchOptions options = new LaunchOptions();

        try {
            lib.launch(options);
        } catch (Exception e) {
            System.out.println(ZAnsi.brightRed("Ошибка при запуске: " + e.getMessage()));
        }

        ConsoleUtils.pause();
    }

    private List<String> getAllForgeVersions() throws Exception {
        String metadataUrl = "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml";
        
        String xml = ZHttpClient.downloadString(metadataUrl);   // добавь этот метод в ZHttpClient, если его нет
        
        // Парсим простым способом (без XML парсера)
        List<String> versions = new ArrayList<>();
        int index = 0;
        
        while ((index = xml.indexOf("<version>", index)) != -1) {
            int start = index + 9;
            int end = xml.indexOf("</version>", start);
            if (end == -1) break;
        
            String version = xml.substring(start, end).trim();
            versions.add(version);
            index = end;
        }
    
        // Сортируем по убыванию (новые сверху)
        versions.sort((a, b) -> b.compareTo(a));
    
        return versions;
    }
}