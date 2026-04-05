package me.sashegdev.zernmc.launcher.minecraft;

import me.sashegdev.zernmc.launcher.utils.Config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;

public class InstanceManager {

    private static final Path INSTANCES_DIR = Config.getInstancesDir();

    public static List<Instance> getAllInstances() throws IOException {
        if (!Files.exists(INSTANCES_DIR)) {
            Files.createDirectories(INSTANCES_DIR);
            return List.of();
        }

        return Files.list(INSTANCES_DIR)
                .filter(Files::isDirectory)
                .map(path -> new Instance(path.getFileName().toString(), path))
                .collect(Collectors.toList());
    }

    public static Instance getInstance(String name) {
        Path instancePath = INSTANCES_DIR.resolve(name);
        if (Files.exists(instancePath) && Files.isDirectory(instancePath)) {
            return new Instance(name, instancePath);
        }
        return null;
    }
    
    public static boolean deleteInstance(String instanceName) {
        if (instanceName == null || instanceName.isBlank()) {
            return false;
        }

        Path instancePath = INSTANCES_DIR.resolve(instanceName);

        if (!Files.exists(instancePath)) {
            return false;
        }

        try {
            // Рекурсивно удаляем всю папку сборки
            Files.walk(instancePath)
                 .sorted((a, b) -> b.compareTo(a)) // удаляем снизу вверх
                 .forEach(path -> {
                     try {
                         Files.deleteIfExists(path);
                     } catch (IOException e) {
                         System.err.println("Не удалось удалить: " + path);
                     }
                 });
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static boolean createInstanceFolder(String name) throws IOException {
        Path path = INSTANCES_DIR.resolve(name);
        if (Files.exists(path)) {
            return false;
        }
        Files.createDirectories(path);
        return true;
    }
}