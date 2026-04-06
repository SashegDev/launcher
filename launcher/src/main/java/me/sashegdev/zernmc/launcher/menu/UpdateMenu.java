package me.sashegdev.zernmc.launcher.menu;

import me.sashegdev.zernmc.launcher.minecraft.Instance;
import me.sashegdev.zernmc.launcher.minecraft.InstanceManager;
import me.sashegdev.zernmc.launcher.minecraft.PackDownloader;
import me.sashegdev.zernmc.launcher.ui.ArrowMenu;
import me.sashegdev.zernmc.launcher.utils.ConsoleUtils;
import me.sashegdev.zernmc.launcher.utils.Input;
import me.sashegdev.zernmc.launcher.utils.ZAnsi;
import me.sashegdev.zernmc.launcher.utils.ZHttpClient;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class UpdateMenu {

    public void show() throws IOException {
        List<String> options = List.of(
            "Проверить обновления сборки (модпака)",
            "Проверить обновления лаунчера",
            "Назад в главное меню"
        );

        ArrowMenu menu = new ArrowMenu("Проверка обновлений", options);
        int choice = menu.show();

        if (choice == -1 || choice == 2) return;

        ConsoleUtils.clearScreen();

        if (choice == 0) {
            try {
                checkPackUpdates();
            } catch (Exception e) {
                System.out.println(ZAnsi.brightRed("Ошибка: " + e.getMessage()));
                e.printStackTrace();
                ConsoleUtils.pause();
            }
        } else {
            checkLauncherUpdates();
        }
    }

    private void checkPackUpdates() throws Exception {
        System.out.println(ZAnsi.cyan("Проверка обновлений сборок..."));
        
        List<Instance> instances = InstanceManager.getAllInstances();
        List<Instance> serverInstances = instances.stream()
            .filter(Instance::isServerPack)
            .collect(Collectors.toList());
        
        if (serverInstances.isEmpty()) {
            System.out.println(ZAnsi.yellow("Нет сборок, установленных с сервера."));
            ConsoleUtils.pause();
            return;
        }
        
        System.out.println(ZAnsi.cyan("\nПроверка обновлений для серверных сборок:\n"));
        
        boolean hasUpdates = false;
        List<Instance> updatableInstances = new ArrayList<>();
        
        for (Instance instance : serverInstances) {
            PackDownloader downloader = new PackDownloader(instance);
            
            try {
                boolean hasUpdate = downloader.checkForUpdates(instance.getServerPackName());
                if (hasUpdate) {
                    System.out.println(ZAnsi.yellow(instance.getName() + " - Есть обновление!"));
                    updatableInstances.add(instance);
                    hasUpdates = true;
                } else {
                    System.out.println(ZAnsi.green(instance.getName() + " - Актуальна"));
                }
            } catch (Exception e) {
                System.out.println(ZAnsi.red(instance.getName() + " - Ошибка проверки: " + e.getMessage()));
            }
        }
        
        if (!hasUpdates) {
            System.out.println(ZAnsi.green("\nВсе сборки актуальны!"));
            ConsoleUtils.pause();
            return;
        }
        
        // Предлагаем обновить каждую сборку отдельно
        for (Instance instance : updatableInstances) {
            System.out.println(ZAnsi.brightYellow("\nОбновить сборку '" + instance.getName() + "'?"));
            if (Input.confirm("Обновить")) {
                System.out.println(ZAnsi.cyan("Обновление " + instance.getName() + "..."));
                PackDownloader downloader = new PackDownloader(instance);
                
                try {
                    boolean success = downloader.updatePack(instance.getServerPackName());
                    if (success) {
                        System.out.println(ZAnsi.brightGreen(instance.getName() + " обновлен"));
                    } else {
                        System.out.println(ZAnsi.brightRed(instance.getName() + " не удалось обновить"));
                    }
                } catch (Exception e) {
                    System.out.println(ZAnsi.brightRed(instance.getName() + ": " + e.getMessage()));
                }
            } else {
                System.out.println(ZAnsi.yellow("  Пропущено: " + instance.getName()));
            }
        }
        
        ConsoleUtils.pause();
    }

    private void checkLauncherUpdates() {
        System.out.println(ZAnsi.cyan("Проверка обновлений лаунчера..."));
        
        try {
            String json = ZHttpClient.getLauncherVersionInfo();
            String serverVersion = extractVersion(json);
            String currentVersion = me.sashegdev.zernmc.launcher.utils.Version.getCurrentVersion();
            
            System.out.println(ZAnsi.white("Текущая версия: ") + currentVersion);
            System.out.println(ZAnsi.white("Версия на сервере: ") + serverVersion);
            
            if (me.sashegdev.zernmc.launcher.utils.Version.isNewer(currentVersion, serverVersion)) {
                System.out.println(ZAnsi.brightYellow("\nДоступна новая версия!"));
                if (Input.confirm("Обновить лаунчер?")) {
                    // Обновление будет при следующем запуске
                    System.out.println(ZAnsi.green("Лаунчер будет обновлен при следующем запуске."));
                }
            } else {
                System.out.println(ZAnsi.brightGreen("Лаунчер актуален."));
            }
        } catch (Exception e) {
            System.out.println(ZAnsi.yellow("Не удалось проверить обновления лаунчера."));
            System.out.println(ZAnsi.white("Ошибка: ") + e.getMessage());
        }
        
        ConsoleUtils.pause();
    }

    private String extractVersion(String json) {
        try {
            int start = json.indexOf("\"version\"");
            if (start == -1) return "unknown";
            start = json.indexOf("\"", start + 9) + 1;
            int end = json.indexOf("\"", start);
            return json.substring(start, end);
        } catch (Exception e) {
            return "unknown";
        }
    }
}