package me.sashegdev.zernmc.launcher.menu;

import me.sashegdev.zernmc.launcher.auth.AuthManager;
import me.sashegdev.zernmc.launcher.minecraft.Instance;
import me.sashegdev.zernmc.launcher.minecraft.InstanceManager;
import me.sashegdev.zernmc.launcher.minecraft.MinecraftLib;
import me.sashegdev.zernmc.launcher.minecraft.PackDownloader;
import me.sashegdev.zernmc.launcher.minecraft.ServerPack;
import me.sashegdev.zernmc.launcher.minecraft.installer.VersionInstaller;
import me.sashegdev.zernmc.launcher.minecraft.model.LaunchOptions;
import me.sashegdev.zernmc.launcher.minecraft.model.MinecraftVersion;
import me.sashegdev.zernmc.launcher.ui.ArrowMenu;
import me.sashegdev.zernmc.launcher.utils.Config;
import me.sashegdev.zernmc.launcher.utils.ConsoleUtils;
import me.sashegdev.zernmc.launcher.utils.Input;
import me.sashegdev.zernmc.launcher.utils.ZAnsi;
import me.sashegdev.zernmc.launcher.utils.ZHttpClient;

import java.awt.*;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class LaunchMenu {

    public void show() throws Exception {
        if (Config.isZernMCBuild()) {
            showZernMCOnly();
        } else {
            showGlobal();
        }
    }

    // ====================== ZERNMC BUILD ======================
    private void showZernMCOnly() throws Exception {
        while (true) {
            ConsoleUtils.clearScreen();
            System.out.println(ZAnsi.header("=== ZernMC Private Launcher ==="));
            System.out.println(ZAnsi.cyan("Доступны только серверные сборки"));

            if (!awaitActivePass()) {
                return;
            }

            PackDownloader tempDownloader = new PackDownloader(null);
            List<ServerPack> availablePacks = tempDownloader.getAvailablePacks();

            if (availablePacks.isEmpty()) {
                System.out.println(ZAnsi.yellow("На данный момент нет доступных сборок на сервере."));
                ConsoleUtils.pause();
                return;
            }

            List<String> options = availablePacks.stream()
                    .map(p -> String.format("%s [%s + %s v%d] — %d файлов",
                            p.getName(),
                            p.getMinecraftVersion(),
                            p.getLoaderType(),
                            p.getVersion(),
                            p.getFilesCount()))
                    .collect(Collectors.toList());

            options.add("Назад в главное меню");

            ArrowMenu menu = new ArrowMenu("Выберите сборку", options);
            int choice = menu.show();

            if (choice == -1 || choice == options.size() - 1) return;

            ServerPack selected = availablePacks.get(choice);
            installAndRunServerPack(selected);
        }
    }

    private boolean awaitActivePass() throws Exception {
        if (AuthManager.hasActivePass()) {
            System.out.println(ZAnsi.brightGreen("✓ Активная проходка подтверждена"));
            return true;
        }

        ConsoleUtils.clearScreen();
        System.out.println(ZAnsi.brightRed("У вас нет активной проходки!"));
        System.out.println(ZAnsi.white("Для доступа к сборкам ZernMC требуется активная проходка."));
        System.out.println();

        openActivationWebsite();

        System.out.println(ZAnsi.cyan("Ожидаем активацию проходки... (проверка каждые 10 секунд)"));
        System.out.println(ZAnsi.white("Нажмите Enter для отмены"));

        for (int i = 0; i < 60; i++) {
            try {
                if (System.in.available() > 0) {
                    Input.readLine();
                    System.out.println(ZAnsi.yellow("\nОжидание отменено."));
                    return false;
                }
            } catch (Exception ignored) {}

            Thread.sleep(10000);

            if (AuthManager.hasActivePass()) {
                System.out.println(ZAnsi.brightGreen("\n✓ Проходка успешно активирована!"));
                return true;
            }

            System.out.print(ZAnsi.cyan("."));
            if ((i + 1) % 6 == 0) System.out.println();
        }

        System.out.println(ZAnsi.brightRed("\n\nВремя ожидания истекло."));
        return false;
    }

    private void openActivationWebsite() {
        //String url = "https://launcher.ru.zernmc.ru/activate-pass";
        String url = ZHttpClient.getBaseUrl() + "/activate-pass";

        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(new URI(url));
                System.out.println(ZAnsi.cyan("Браузер открыт: " + url));
            } else {
                System.out.println(ZAnsi.yellow("Не удалось открыть браузер автоматически."));
                System.out.println(ZAnsi.white("Откройте вручную: " + url));
            }
        } catch (Exception e) {
            System.out.println(ZAnsi.brightRed("Ошибка открытия браузера: " + e.getMessage()));
            System.out.println(ZAnsi.white("Ссылка: " + url));
        }
    }

    private void installAndRunServerPack(ServerPack selected) throws Exception {
        ConsoleUtils.clearScreen();
        System.out.println(ZAnsi.header("Установка сборки: " + selected.getName()));

        System.out.println(ZAnsi.white(" Minecraft: ") + selected.getMinecraftVersion());
        System.out.println(ZAnsi.white(" Лоадер: ") + selected.getLoaderType() +
                (selected.getLoaderVersion() != null ? " " + selected.getLoaderVersion() : ""));
        System.out.println(ZAnsi.white(" Версия: v") + selected.getVersion());
        System.out.println(ZAnsi.white(" Файлов: ") + selected.getFilesCount());

        String localName = askPackName();
        if (localName == null) return;

        if (InstanceManager.getInstance(localName) != null) {
            System.out.println(ZAnsi.brightRed("Сборка с таким именем уже существует!"));
            ConsoleUtils.pause();
            return;
        }

        InstanceManager.createInstanceFolder(localName);
        Instance newInstance = InstanceManager.getInstance(localName);

        PackDownloader packDownloader = new PackDownloader(newInstance);
        boolean success = packDownloader.installOrUpdatePack(selected.getName(), selected);

        if (!success) {
            System.out.println(ZAnsi.brightRed("\n[FAIL] Не удалось установить сборку."));
            ConsoleUtils.pause();
            return;
        }

        System.out.println(ZAnsi.brightGreen("\n[OK] Сборка '" + localName + "' успешно установлена!"));
        ConsoleUtils.pause();

        launchExistingInstance(newInstance);
    }

    // ====================== GLOBAL BUILD ======================
    private void showGlobal() throws Exception {
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

            if (choice == -1 || choice == options.size() - 1) break;

            if (choice == instances.size()) {
                installNewPackGlobal();
                continue;
            }

            Instance selected = instances.get(choice);
            manageInstance(selected);
        }
    }

    private void installNewPackGlobal() throws Exception {
        ConsoleUtils.clearScreen();

        List<String> options = List.of(
                "Установить сборку с сервера ZernMC",
                "Установить Vanilla Minecraft",
                "Создать сборку вручную (Fabric/Forge)",
                "Назад"
        );

        ArrowMenu menu = new ArrowMenu("Установка новой сборки", options);
        int choice = menu.show();

        if (choice == -1 || choice == 3) return;

        switch (choice) {
            case 0 -> installServerPackGlobal();
            case 1 -> createVanillaInstance();
            case 2 -> createCustomInstance();
        }
    }

    private void installServerPackGlobal() throws Exception {
        if (!awaitActivePass()) return;

        ConsoleUtils.clearScreen();
        System.out.println(ZAnsi.cyan("Получение списка доступных сборок..."));

        PackDownloader tempDownloader = new PackDownloader(null);
        List<ServerPack> availablePacks = tempDownloader.getAvailablePacks();

        if (availablePacks.isEmpty()) {
            System.out.println(ZAnsi.yellow("Нет доступных сборок на сервере."));
            ConsoleUtils.pause();
            return;
        }

        List<String> options = availablePacks.stream()
                .map(p -> String.format("%s [%s + %s v%d] — %d файлов",
                        p.getName(),
                        p.getMinecraftVersion(),
                        p.getLoaderType(),
                        p.getVersion(),
                        p.getFilesCount()))
                .collect(Collectors.toList());
        options.add("Назад");

        ArrowMenu menu = new ArrowMenu("Выберите сборку для установки", options);
        int choice = menu.show();

        if (choice == -1 || choice == options.size() - 1) return;

        ServerPack selected = availablePacks.get(choice);

        ConsoleUtils.clearScreen();
        System.out.println(ZAnsi.header("Установка сборки: " + selected.getName()));

        System.out.print(ZAnsi.white("\nВведите название локальной сборки (Enter = имя пака): "));
        String localName = Input.readLine().trim();
        if (localName.isEmpty()) localName = selected.getName();

        if (InstanceManager.getInstance(localName) != null) {
            System.out.println(ZAnsi.brightRed("Сборка с таким именем уже существует!"));
            ConsoleUtils.pause();
            return;
        }

        InstanceManager.createInstanceFolder(localName);
        Instance newInstance = InstanceManager.getInstance(localName);

        PackDownloader packDownloader = new PackDownloader(newInstance);
        boolean success = packDownloader.installOrUpdatePack(selected.getName(), selected);

        if (success) {
            System.out.println(ZAnsi.brightGreen("\n[OK] Сборка '" + localName + "' успешно установлена!"));
        } else {
            System.out.println(ZAnsi.brightRed("\n[FAIL] Не удалось установить сборку."));
        }

        ConsoleUtils.pause();
    }

    // ====================== manageInstance — полностью восстановлен ======================
    private void manageInstance(Instance instance) throws Exception {
        while (true) {
            ConsoleUtils.clearScreen();
            System.out.println(ZAnsi.header("Управление сборкой: " + instance.getName()));
            System.out.println(ZAnsi.white("Версия: " + instance.getMinecraftVersion()));
            System.out.println(ZAnsi.white("Лоадер: " + instance.getLoaderType() +
                    (instance.getLoaderVersion() != null ? " " + instance.getLoaderVersion() : "")));

            if (instance.isServerPack()) {
                System.out.println(ZAnsi.green("Серверная сборка: v" + instance.getServerVersion()));
            }

            List<String> options = new ArrayList<>();
            options.add("Запустить сборку");
            if (instance.isServerPack()) {
                options.add("Проверить обновления");
            }
            options.add("Изменить версию лоадера");
            options.add("Удалить сборку");
            options.add("Назад");

            ArrowMenu menu = new ArrowMenu("Действия", options);
            int choice = menu.show();

            if (choice == -1 || choice == options.size() - 1) return;

            switch (choice) {
                case 0 -> launchExistingInstance(instance);
                case 1 -> {
                    if (instance.isServerPack()) {
                        checkAndUpdateServerPack(instance);
                    } else {
                        changeLoaderVersion(instance);
                    }
                }
                case 2 -> {
                    if (instance.isServerPack()) {
                        changeLoaderVersion(instance);
                    } else {
                        deleteInstance(instance);
                    }
                }
                case 3 -> deleteInstance(instance);
            }
        }
    }

    private void checkAndUpdateServerPack(Instance instance) throws Exception {
        ConsoleUtils.clearScreen();
        System.out.println(ZAnsi.cyan("Проверка обновлений для " + instance.getName()));

        PackDownloader downloader = new PackDownloader(instance);
        boolean hasUpdate = downloader.checkForUpdates(instance.getServerPackName());

        if (!hasUpdate) {
            System.out.println(ZAnsi.green("Сборка актуальна (v" + instance.getServerVersion() + ")"));
            ConsoleUtils.pause();
            return;
        }

        System.out.println(ZAnsi.brightYellow("Доступно обновление!"));
        if (Input.confirm("Обновить сборку")) {
            boolean success = downloader.updatePack(instance.getServerPackName());
            if (success) {
                System.out.println(ZAnsi.brightGreen("Сборка успешно обновлена!"));
            } else {
                System.out.println(ZAnsi.brightRed("Не удалось обновить сборку."));
            }
        } else {
            System.out.println(ZAnsi.yellow("Обновление отменено."));
        }
        ConsoleUtils.pause();
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

        System.out.println(ZAnsi.cyan("Переустановка лоадера " + currentLoader + " -> " + newLoaderVersion + "..."));

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
            } else {
                System.out.println(ZAnsi.brightRed("Не удалось изменить версию лоадера."));
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

        if (choice == 0) {
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

    private void launchExistingInstance(Instance instance) {
        if (instance.isServerPack() && !AuthManager.hasActivePass()) {
            ConsoleUtils.clearScreen();
            System.out.println(ZAnsi.brightRed("Для запуска серверной сборки требуется активная проходка!"));
            ConsoleUtils.pause();
            return;
        }

        ConsoleUtils.clearScreen();
        System.out.println(ZAnsi.brightGreen("Запуск сборки: " + instance.getName()));

        MinecraftLib lib = new MinecraftLib(instance);
        LaunchOptions options = new LaunchOptions();

        options.setUsername(AuthManager.getUsername());
        options.setUuid(AuthManager.getUuid());
        options.setAccessToken(AuthManager.getAccessToken());

        try {
            lib.launch(options);
        } catch (Exception e) {
            System.out.println(ZAnsi.brightRed("Ошибка при запуске: " + e.getMessage()));
            e.printStackTrace();
        }

        ConsoleUtils.pause();
    }

    // ====================== Остальные вспомогательные методы ======================

    private String askPackName() {
        System.out.print(ZAnsi.white("\nВведите название новой сборки: "));
        String name = Input.readLine().trim();
        if (name.isEmpty()) {
            System.out.println(ZAnsi.yellow("Отменено."));
            return null;
        }
        return name;
    }

    private void createVanillaInstance() throws Exception {
        ConsoleUtils.clearScreen();
        System.out.println(ZAnsi.cyan("Получение списка версий Minecraft..."));

        VersionInstaller versionInstaller = new VersionInstaller(null);
        List<MinecraftVersion> allVersions = versionInstaller.getAvailableVersions();

        List<String> versionOptions = allVersions.stream()
                .map(v -> v.getId() + " (" + v.getType() + ")")
                .collect(Collectors.toList());
        versionOptions.add("Назад");

        ArrowMenu versionMenu = new ArrowMenu("Выбор версии Minecraft", versionOptions);
        int versionChoice = versionMenu.show();

        if (versionChoice == -1 || versionChoice == versionOptions.size() - 1) return;

        MinecraftVersion selectedMc = allVersions.get(versionChoice);
        String mcVersion = selectedMc.getId();

        String packName = askPackName();
        if (packName == null) return;

        if (InstanceManager.getInstance(packName) != null) {
            System.out.println(ZAnsi.brightRed("Сборка с таким именем уже существует!"));
            ConsoleUtils.pause();
            return;
        }

        InstanceManager.createInstanceFolder(packName);
        Instance newInstance = InstanceManager.getInstance(packName);

        MinecraftLib lib = new MinecraftLib(newInstance);
        boolean success = lib.installMinecraft(mcVersion);

        if (success) {
            System.out.println(ZAnsi.brightGreen("\n[OK] Vanilla сборка '" + packName + "' успешно создана!"));
        } else {
            System.out.println(ZAnsi.brightRed("\n[FAIL] Не удалось создать сборку."));
        }

        ConsoleUtils.pause();
    }

    private void createCustomInstance() throws Exception {
        ConsoleUtils.clearScreen();
        System.out.println(ZAnsi.cyan("Получение списка версий Minecraft..."));

        VersionInstaller versionInstaller = new VersionInstaller(null);
        List<MinecraftVersion> allVersions = versionInstaller.getAvailableVersions();

        List<String> versionOptions = allVersions.stream()
                .map(v -> v.getId() + " (" + v.getType() + ")")
                .collect(Collectors.toList());
        versionOptions.add("Назад");

        ArrowMenu versionMenu = new ArrowMenu("Выбор версии Minecraft", versionOptions);
        int versionChoice = versionMenu.show();

        if (versionChoice == -1 || versionChoice == versionOptions.size() - 1) return;

        MinecraftVersion selectedMc = allVersions.get(versionChoice);
        String mcVersion = selectedMc.getId();

        List<String> loaderOptions = buildLoaderOptions(mcVersion);
        ArrowMenu loaderMenu = new ArrowMenu("Выбор модлоадера для " + mcVersion, loaderOptions);
        int loaderChoice = loaderMenu.show();

        if (loaderChoice == -1 || loaderChoice == loaderOptions.size() - 1) return;

        String selectedLoader = loaderOptions.get(loaderChoice);

        if (selectedLoader.contains("Vanilla")) {
            createVanillaInstance();
            return;
        }

        String loaderType = selectedLoader.contains("Fabric") ? "fabric" : "forge";

        String loaderVersion = loaderType.equals("fabric")
                ? askFabricLoaderVersion()
                : askForgeVersion(mcVersion);

        if (loaderVersion == null) return;

        String packName = askPackName();
        if (packName == null) return;

        if (InstanceManager.getInstance(packName) != null) {
            System.out.println(ZAnsi.brightRed("Сборка с таким именем уже существует!"));
            ConsoleUtils.pause();
            return;
        }

        InstanceManager.createInstanceFolder(packName);
        Instance newInstance = InstanceManager.getInstance(packName);

        MinecraftLib lib = new MinecraftLib(newInstance);

        boolean success = loaderType.equals("fabric")
                ? lib.installFabric(mcVersion, loaderVersion)
                : lib.installForge(mcVersion, loaderVersion);

        if (success) {
            System.out.println(ZAnsi.brightGreen("\n[OK] Сборка '" + packName + "' успешно установлена!"));
        } else {
            System.out.println(ZAnsi.brightRed("\n[FAIL] Не удалось установить сборку."));
        }

        ConsoleUtils.pause();
    }

    private List<String> buildLoaderOptions(String mcVersion) {
        List<String> options = new ArrayList<>();

        if (isFabricSupported(mcVersion)) options.add("Fabric");
        if (isForgeSupported(mcVersion)) options.add("Forge");
        options.add("Vanilla");
        options.add("Назад");

        return options;
    }

    private boolean isFabricSupported(String version) {
        return version.matches("^1\\.(1[4-9]|[2-9]\\d).*");
    }

    private boolean isForgeSupported(String version) {
        if (version.matches("^1\\.2[2-9].*") || version.matches("^\\d{2}.*")) return false;
        return version.matches("^1\\.(1[2-9]|[2-9]\\d).*") ||
               version.matches("^1\\.20.*") || version.matches("^1\\.21.*");
    }

    private String askFabricLoaderVersion() throws Exception {
        System.out.println(ZAnsi.cyan("Получение списка версий Fabric Loader..."));
        List<String> versions = ZHttpClient.getFabricLoaderVersions();

        List<String> options = versions.stream()
                .limit(30)
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

        List<String> allForgeVersions = getAllForgeVersions();

        List<String> compatibleVersions = allForgeVersions.stream()
                .filter(v -> v.startsWith(mcVersion + "-"))
                .map(v -> v.substring(mcVersion.length() + 1))
                .collect(Collectors.toList());

        if (compatibleVersions.isEmpty()) {
            System.out.println(ZAnsi.yellow("Не найдено совместимых версий Forge для " + mcVersion));
            ConsoleUtils.pause();
            return null;
        }

        List<String> options = compatibleVersions.stream()
                .limit(30)
                .map(v -> "Forge " + v)
                .collect(Collectors.toList());
        options.add("Назад");

        ArrowMenu menu = new ArrowMenu("Выбор версии Forge для " + mcVersion, options);
        int choice = menu.show();

        if (choice == -1 || choice == options.size() - 1) return null;

        return compatibleVersions.get(choice);
    }

    private List<String> getAllForgeVersions() throws Exception {
        String xml = ZHttpClient.downloadString("https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml");

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

        versions.sort((a, b) -> b.compareTo(a));
        return versions;
    }
}