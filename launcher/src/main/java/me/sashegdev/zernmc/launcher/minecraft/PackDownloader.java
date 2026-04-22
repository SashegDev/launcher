package me.sashegdev.zernmc.launcher.minecraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import me.sashegdev.zernmc.launcher.auth.AuthManager;
import me.sashegdev.zernmc.launcher.utils.ProgressBar;
import me.sashegdev.zernmc.launcher.utils.ZAnsi;
import me.sashegdev.zernmc.launcher.utils.ZHttpClient;

import java.io.*;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

public class PackDownloader {

    private final Instance instance;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final HttpClient httpClient = HttpClient.newHttpClient();
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    public PackDownloader(Instance instance) {
        this.instance = instance;
    }

    /**
     * Получить список доступных паков с сервера
     */
    public List<ServerPack> getAvailablePacks() throws Exception {
        String accessToken = AuthManager.getAccessToken();
        if (accessToken == null) {
            throw new IOException("Не авторизован. Требуется проходка для просмотра сборок.");
        }
        if (!AuthManager.canViewPacks()) {
            throw new IOException("Для просмотра сборок требуется активная проходка");
        }

        // Используем HttpURLConnection для GET с авторизацией
        java.net.HttpURLConnection connection = null;
        try {
            java.net.URL url = new java.net.URL(ZHttpClient.getBaseUrl() + "/packs");
            connection = (java.net.HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);

            int responseCode = connection.getResponseCode();

            if (responseCode == 403) {
                throw new IOException("Для просмотра сборок требуется активная проходка");
            }

            StringBuilder response = new StringBuilder();
            try (java.io.InputStream is = responseCode < 400 ? connection.getInputStream() : connection.getErrorStream();
                 java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is, "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            if (responseCode != 200) {
                throw new IOException("HTTP " + responseCode);
            }

            return parsePacksResponse(response.toString());

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private List<ServerPack> parsePacksResponse(String responseBody) {
        JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
        JsonArray packsArray = root.getAsJsonArray("packs");
        List<ServerPack> result = new ArrayList<>();
        
        for (JsonElement elem : packsArray) {
            JsonObject pack = elem.getAsJsonObject();

            if (pack.has("error") || (pack.has("status") && "not_scanned".equals(pack.get("status").getAsString()))) {
                continue;
            }

            try {
                String name = pack.get("name").getAsString();
                int version = pack.has("version") ? pack.get("version").getAsInt() : 0;
                String minecraftVersion = pack.has("minecraft_version") ? pack.get("minecraft_version").getAsString() : "unknown";
                String loaderType = pack.has("loader_type") ? pack.get("loader_type").getAsString() : "vanilla";
                String loaderVersion = pack.has("loader_version") && !pack.get("loader_version").isJsonNull() 
                        ? pack.get("loader_version").getAsString() : "";
                int filesCount = pack.has("files_count") ? pack.get("files_count").getAsInt() : 0;

                LocalDateTime updatedAt = null;
                if (pack.has("updated_at") && !pack.get("updated_at").isJsonNull()) {
                    try {
                        updatedAt = LocalDateTime.parse(pack.get("updated_at").getAsString(), 
                                DateTimeFormatter.ISO_DATE_TIME);
                    } catch (Exception ignored) {}
                }

                result.add(new ServerPack(name, version, minecraftVersion, loaderType, 
                        loaderVersion, updatedAt, filesCount));
            } catch (Exception e) {
                System.err.println("Ошибка парсинга пака: " + e.getMessage());
            }
        }

        return result;
    }

    /**
     * Получить манифест пака
     */
    public PackManifest getPackManifest(String packName) throws Exception {
        String response = ZHttpClient.get("/pack/" + packName);
        return gson.fromJson(response, PackManifest.class);
    }

    /**
     * Установить или обновить сборку с сервера
     */
    public boolean installOrUpdatePack(String packName, ServerPack serverPack) throws Exception {
        System.out.println(ZAnsi.cyan("Установка сборки " + packName + " с сервера..."));
        
        // 1. Получаем манифест
        PackManifest manifest = getPackManifest(packName);
        
        // 2. Сначала устанавливаем Minecraft + Loader через MinecraftLib
        MinecraftLib lib = new MinecraftLib(instance);
        
        System.out.println(ZAnsi.cyan("Установка Minecraft " + manifest.getMinecraftVersion() + "..."));
        
        boolean needsMinecraftInstall = instance.getMinecraftVersion() == null || 
                                        !instance.getMinecraftVersion().equals(manifest.getMinecraftVersion());
        
        if (needsMinecraftInstall) {
            if ("fabric".equalsIgnoreCase(manifest.getLoaderType())) {
                boolean success = lib.installFabric(manifest.getMinecraftVersion(), manifest.getLoaderVersion());
                if (!success) {
                    System.err.println(ZAnsi.brightRed("Не удалось установить Fabric"));
                    return false;
                }
            } else if ("forge".equalsIgnoreCase(manifest.getLoaderType())) {
                boolean success = lib.installForge(manifest.getMinecraftVersion(), manifest.getLoaderVersion());
                if (!success) {
                    System.err.println(ZAnsi.brightRed("Не удалось установить Forge"));
                    return false;
                }
            } else {
                boolean success = lib.installMinecraft(manifest.getMinecraftVersion());
                if (!success) {
                    System.err.println(ZAnsi.brightRed("Не удалось установить Vanilla Minecraft"));
                    return false;
                }
            }
        } else {
            System.out.println(ZAnsi.green("Minecraft уже установлен, пропускаем..."));
        }
        
        // 3. Сканируем локальные файлы ТОЛЬКО если есть файлы для скачивания
        Map<String, String> localFiles = scanLocalFiles();
        
        // Если в сборке нет файлов (только vanilla/loader), пропускаем diff
        if (manifest.files == null || manifest.files.isEmpty()) {
            System.out.println(ZAnsi.green("Сборка не содержит дополнительных файлов"));
            
            // Обновляем метаданные инстанса
            instance.setServerPack(true);
            instance.setServerPackName(packName);
            instance.setServerVersion(manifest.getVersion());
            instance.setMinecraftVersion(manifest.getMinecraftVersion());
            instance.setLoaderType(manifest.getLoaderType());
            instance.setLoaderVersion(manifest.getLoaderVersion());
            instance.setAssetIndex(manifest.getAssetIndex());
            
            System.out.println(ZAnsi.brightGreen("Сборка успешно установлена!"));
            return true;
        }
        
        // 4. Отправляем diff запрос
        System.out.println(ZAnsi.cyan("Проверка файлов сборки..."));
        DiffResponse diff = getDiff(packName, localFiles);
        
        // 5. Применяем изменения
        boolean success = applyDiff(diff, packName);
        
        if (success) {
            // 6. Обновляем метаданные инстанса
            instance.setServerPack(true);
            instance.setServerPackName(packName);
            instance.setServerVersion(manifest.getVersion());
            instance.setMinecraftVersion(manifest.getMinecraftVersion());
            instance.setLoaderType(manifest.getLoaderType());
            instance.setLoaderVersion(manifest.getLoaderVersion());
            instance.setAssetIndex(manifest.getAssetIndex());
            
            System.out.println(ZAnsi.brightGreen("Сборка успешно установлена!"));
        }
        
        return success;
    }

    /**
     * Проверить наличие обновлений для серверной сборки
     */
    public boolean checkForUpdates(String packName) throws Exception {
        if (!instance.isServerPack()) return false;
        
        PackManifest manifest = getPackManifest(packName);
        int serverVersion = manifest.getVersion();
        int localVersion = instance.getServerVersion();
        
        return serverVersion > localVersion;
    }

    /**
     * Обновить существующую серверную сборку
     */
    public boolean updatePack(String packName) throws Exception {
        System.out.println(ZAnsi.cyan("Проверка обновлений для " + instance.getName() + "..."));
        
        PackManifest manifest = getPackManifest(packName);
        int serverVersion = manifest.getVersion();
        
        if (serverVersion <= instance.getServerVersion()) {
            System.out.println(ZAnsi.green("Сборка уже актуальна (v" + instance.getServerVersion() + ")"));
            return true;
        }
        
        System.out.println(ZAnsi.yellow("Доступно обновление: v" + instance.getServerVersion() + " → v" + serverVersion));
        
        // Сканируем локальные файлы
        Map<String, String> localFiles = scanLocalFiles();
        
        // Получаем diff
        DiffResponse diff = getDiff(packName, localFiles);
        
        // Применяем изменения
        boolean success = applyDiff(diff, packName);
        
        if (success) {
            instance.setServerVersion(serverVersion);
            System.out.println(ZAnsi.brightGreen("Сборка обновлена до v" + serverVersion));
        }
        
        return success;
    }

    /**
     * Сканирование локальных файлов и вычисление хешей
     */
    private Map<String, String> scanLocalFiles() throws IOException {
        Map<String, String> files = new HashMap<>();
        Path instancePath = instance.getPath();
        
        // Игнорируемые директории
        Set<String> ignoredDirs = Set.of(
            "resourcepacks", "shaderpacks", "saves", "logs",
            "crash-reports", "screenshots", "journeymap", "config",
            "natives", "assets", "libraries", "versions", "cache"
        );
        
        if (!Files.exists(instancePath)) {
            return files;
        }
        
        Files.walk(instancePath)
            .filter(Files::isRegularFile)
            .forEach(file -> {
                Path relative = instancePath.relativize(file);
                String path = relative.toString().replace("\\", "/");
                
                // Проверяем, не в игнорируемой ли директории
                for (String ignored : ignoredDirs) {
                    if (path.startsWith(ignored + "/") || path.startsWith(ignored + "\\")) {
                        return;
                    }
                }
                
                try {
                    String hash = calculateHash(file);
                    files.put(path, hash);
                } catch (Exception e) {
                    // Пропускаем файлы, которые не можем прочитать
                }
            });
        
        return files;
    }

    /**
     * Отправить diff запрос на сервер
     */
    private DiffResponse getDiff(String packName, Map<String, String> localFiles) throws Exception {
        String json = gson.toJson(localFiles);

        // Получаем токен авторизации
        String accessToken = AuthManager.getAccessToken();
        if (accessToken == null) {
            throw new IOException("Не авторизован. Требуется проходка для скачивания сборок.");
        }
        if (!AuthManager.canDownloadPacks()) {
            throw new IOException("Для скачивания сборок требуется активная проходка");
        }

        String url = ZHttpClient.getBaseUrl() + "/pack/" + packName + "/diff";

        // Используем HttpURLConnection для полного контроля
        java.net.HttpURLConnection connection = null;
        try {
            java.net.URL urlObj = new java.net.URL(url);
            connection = (java.net.HttpURLConnection) urlObj.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Authorization", "Bearer " + accessToken);
            connection.setRequestProperty("Content-Length", String.valueOf(json.getBytes("UTF-8").length));
            connection.setDoOutput(true);
            connection.setConnectTimeout(30000);
            connection.setReadTimeout(30000);

            // Отправляем JSON
            try (java.io.OutputStream os = connection.getOutputStream()) {
                byte[] input = json.getBytes("UTF-8");
                os.write(input, 0, input.length);
                os.flush();
            }

            int responseCode = connection.getResponseCode();

            // Читаем ответ
            StringBuilder response = new StringBuilder();
            try (java.io.InputStream is = responseCode < 400 ? connection.getInputStream() : connection.getErrorStream();
                 java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(is, "UTF-8"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    response.append(line);
                }
            }

            String responseBody = response.toString();

            if (responseCode == 403) {
                throw new IOException("Для скачивания сборок требуется активная проходка. Обратитесь к администратору.");
            }

            if (responseCode != 200) {
                throw new IOException("HTTP " + responseCode + ": " + extractErrorFromResponse(responseBody));
            }

            return gson.fromJson(responseBody, DiffResponse.class);

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private String extractErrorFromResponse(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (json.has("detail")) {
                return json.get("detail").getAsString();
            }
        } catch (Exception ignored) {}
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }

    /**
     * Применить diff (скачать новые файлы, удалить старые)
     */
    private boolean applyDiff(DiffResponse diff, String packName) {
        System.out.println(ZAnsi.cyan("\nПрименение изменений:"));
        System.out.println("  Загрузить: " + diff.getToDownload().size() + " файлов");
        System.out.println("  Удалить: " + diff.getToDelete().size() + " файлов");
        
        // Создаем директории если нужно
        try {
            Files.createDirectories(instance.getPath());
        } catch (IOException e) {
            System.err.println(ZAnsi.red("Ошибка создания директорий: " + e.getMessage()));
            return false;
        }
        
        // Удаляем файлы
        for (String filePath : diff.getToDelete()) {
            Path fullPath = instance.getPath().resolve(filePath);
            try {
                if (Files.deleteIfExists(fullPath)) {
                    System.out.println(ZAnsi.yellow("  Удален: " + filePath));
                }
            } catch (IOException e) {
                System.err.println(ZAnsi.red("  Ошибка удаления " + filePath + ": " + e.getMessage()));
            }
        }
        
        // Скачиваем файлы
        AtomicInteger downloaded = new AtomicInteger(0);
        int total = diff.getToDownload().size();
        
        for (FileInfo file : diff.getToDownload()) {
            String path = file.getPath();
            Path fullPath = instance.getPath().resolve(path);
            
            try {
                // Создаем директории
                Files.createDirectories(fullPath.getParent());
                
                // Скачиваем файл
                downloadFile(file, fullPath);
                
                // Проверяем хеш
                String actualHash = calculateHash(fullPath);
                if (!actualHash.equals(file.getHash())) {
                    throw new IOException("Хеш не совпадает! Ожидался: " + file.getHash() + 
                                         ", получен: " + actualHash);
                }
                
                downloaded.incrementAndGet();
                if (total > 0) {
                    ProgressBar.show("Скачивание", downloaded.get(), total, "файлов");
                }
                
            } catch (Exception e) {
                System.err.println("\n" + ZAnsi.red("  Ошибка скачивания " + path + ": " + e.getMessage()));
                return false;
            }
        }
        
        if (total > 0) {
            ProgressBar.finish("Скачивание");
        }
        
        return true;
    }

    /**
     * Скачать один файл с сервера
     */
    private void downloadFile(FileInfo file, Path destination) throws Exception {
        String url = ZHttpClient.getBaseUrl() + file.getUrl();
        
        HttpRequest request = HttpRequest.newBuilder()
            .uri(java.net.URI.create(url))
            .GET()
            .build();
        
        HttpResponse<InputStream> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofInputStream());
        
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        
        // Скачиваем с прогрессом
        try (InputStream in = response.body();
             FileOutputStream out = new FileOutputStream(destination.toFile())) {
            
            byte[] buffer = new byte[8192];
            int bytesRead;
            long totalRead = 0;
            long fileSize = file.getSize();
            
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                totalRead += bytesRead;
                
                if (fileSize > 0 && totalRead % 8192 == 0) {
                    ProgressBar.showDownload("  " + file.getPath(), totalRead, fileSize);
                }
            }
        }
        
        ProgressBar.clearLine();
    }

    /**
     * Вычисление SHA256 хеша файла
     */
    private String calculateHash(Path file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        
        try (InputStream in = Files.newInputStream(file)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                digest.update(buffer, 0, bytesRead);
            }
        }
        
        byte[] hashBytes = digest.digest();
        StringBuilder sb = new StringBuilder();
        for (byte b : hashBytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // ====================== Вложенные классы ======================

    public static class PackManifest {
        private String pack_name;
        private int version;
        private String minecraft_version;
        private String loader_type;
        private String loader_version;
        private String asset_index;
        private Map<String, Object> files;

        public String getPackName() { return pack_name; }
        public int getVersion() { return version; }
        public String getMinecraftVersion() { return minecraft_version; }
        public String getLoaderType() { return loader_type; }
        public String getLoaderVersion() { return loader_version; }
        public String getAssetIndex() { return asset_index != null ? asset_index : minecraft_version; }
        public Map<String, Object> getFiles() { return files; }
        public boolean isEmpty() { return files == null || files.isEmpty(); }
    }

    public static class DiffResponse {
        private int version;
        private List<FileInfo> to_download;
        private List<String> to_delete;
        private List<String> to_update;

        public int getVersion() { return version; }
        public List<FileInfo> getToDownload() { return to_download != null ? to_download : new ArrayList<>(); }
        public List<String> getToDelete() { return to_delete != null ? to_delete : new ArrayList<>(); }
        public List<String> getToUpdate() { return to_update != null ? to_update : new ArrayList<>(); }
    }

    public static class FileInfo {
        private String path;
        private String url;
        private long size;
        private String hash;

        public String getPath() { return path; }
        public String getUrl() { return url; }
        public long getSize() { return size; }
        public String getHash() { return hash; }
    }
}