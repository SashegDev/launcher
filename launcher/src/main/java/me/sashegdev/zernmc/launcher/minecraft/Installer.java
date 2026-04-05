package me.sashegdev.zernmc.launcher.minecraft;

import me.sashegdev.zernmc.launcher.utils.ZAnsi;

public class Installer {

    public static boolean installPack(String packName, Instance instance) {
        System.out.println(ZAnsi.cyan("Начинается установка сборки: " + packName));

        // TODO: 
        // 1. Получить манифест пака (/pack/{packName})
        // 2. Запустить diff
        // 3. Скачать недостающие файлы
        // 4. Установить Minecraft + Loader (через MinecraftLib)

        System.out.println(ZAnsi.yellow("Установка пока в разработке..."));
        return false;
    }
}