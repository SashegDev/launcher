package me.sashegdev.zernmc.launcher.utils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class ZHttpClient {

    private static final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private static final String BASE_URL = "http://87.120.187.36:1582";

    // Глобальный прокси режим (для обратной совместимости)
    private static final AtomicBoolean useProxyMode = new AtomicBoolean(false);
    private static final AtomicBoolean proxyTested = new AtomicBoolean(false);

    // Умное проксирование по сервисам
    public enum ServiceType {
        ZERN_SERVER(BASE_URL, true),
        FABRIC_META("https://meta.fabricmc.net", false),
        FABRIC_MAVEN("https://maven.fabricmc.net", false),
        MOJANG_META("https://piston-meta.mojang.com", false),
        MOJANG_RESOURCES("https://resources.download.minecraft.net", false),
        FORGE_MAVEN("https://maven.minecraftforge.net", false),
        GOOGLE("https://google.com", false),
        CLOUDFLARE("https://cloudflare.com", false);

        private final String baseUrl;
        private final boolean alwaysDirect;

        ServiceType(String baseUrl, boolean alwaysDirect) {
            this.baseUrl = baseUrl;
            this.alwaysDirect = alwaysDirect;
        }

        public String getBaseUrl() { return baseUrl; }
        public boolean isAlwaysDirect() { return alwaysDirect; }
    }

    // Статусы сервисов
    private static final Map<ServiceType, Boolean> serviceProxyMode = new ConcurrentHashMap<>();
    private static final Map<ServiceType, Integer> serviceFailCount = new ConcurrentHashMap<>();
    private static final Map<ServiceType, Long> serviceLastCheckTime = new ConcurrentHashMap<>();
    private static final Map<ServiceType, Boolean> serviceHealthy = new ConcurrentHashMap<>();

    private static final int MAX_FAILS_BEFORE_PROXY = 2;
    private static final long HEALTH_CHECK_INTERVAL_MS = 60000; // 1 минута
    private static final long CHECK_TIMEOUT_MS = 7000; // 7 секунд на проверку

    // Статистика
    private static int directSuccessCount = 0;
    private static int proxySuccessCount = 0;
    private static int directFailCount = 0;

    static {
        for (ServiceType type : ServiceType.values()) {
            serviceProxyMode.put(type, false);
            serviceFailCount.put(type, 0);
            serviceHealthy.put(type, false);
        }
    }

    /**
     * Вызывать один раз при запуске лаунчера
     */
    public static void checkAllServicesOnStartup() {
        if (proxyTested.get()) return;

        System.out.println(ZAnsi.cyan("Проверка доступности сервисов..."));

        List<ServiceType> servicesToCheck = List.of(
                ServiceType.ZERN_SERVER,
                ServiceType.GOOGLE,
                ServiceType.FABRIC_META,
                ServiceType.FABRIC_MAVEN,
                ServiceType.MOJANG_META,
                ServiceType.MOJANG_RESOURCES,
                ServiceType.FORGE_MAVEN
        );

        for (ServiceType service : servicesToCheck) {
            boolean isHealthy = checkServiceHealth(service);
            serviceHealthy.put(service, isHealthy);

            if (service.isAlwaysDirect()) {
                System.out.println(isHealthy ?
                        ZAnsi.green("  " + service.name() + " - OK") :
                        ZAnsi.red("  " + service.name() + " - НЕ ДОСТУПЕН (критично!)"));
            } else {
                if (isHealthy) {
                    System.out.println(ZAnsi.green("  " + service.name() + " - прямое подключение работает"));
                } else {
                    System.out.println(ZAnsi.yellow("  " + service.name() + " - НЕ ДОСТУПЕН, будет использован прокси"));
                    serviceProxyMode.put(service, true);
                    serviceFailCount.put(service, MAX_FAILS_BEFORE_PROXY);
                }
            }
        }

        if (!serviceHealthy.get(ServiceType.ZERN_SERVER)) {
            System.out.println(ZAnsi.brightRed("Критическая ошибка: Zern сервер недоступен!"));
        }

        proxyTested.set(true);
        startHealthCheckThread();
        printStats();
    }

    /**
     * Принудительная проверка Mojang-сервисов (рекомендуется вызывать перед установкой сборки)
     */
    public static void forceCheckMojangServices() {
        System.out.println(ZAnsi.cyan("Принудительная проверка Mojang сервисов..."));

        for (ServiceType service : List.of(ServiceType.MOJANG_META, ServiceType.MOJANG_RESOURCES)) {
            boolean healthy = checkServiceHealth(service);
            serviceHealthy.put(service, healthy);

            if (healthy) {
                System.out.println(ZAnsi.green("  " + service.name() + " доступен напрямую"));
                serviceProxyMode.put(service, false);
                serviceFailCount.put(service, 0);
            } else {
                System.out.println(ZAnsi.yellow("  " + service.name() + " недоступен → прокси режим активирован"));
                serviceProxyMode.put(service, true);
                serviceFailCount.put(service, MAX_FAILS_BEFORE_PROXY);
            }
        }
    }

    private static boolean checkServiceHealth(ServiceType service) {
        return checkDirectConnection(service.getBaseUrl());
    }

    /**
     * Улучшенная проверка прямого подключения
     */
    private static boolean checkDirectConnection(String baseUrl) {
        String testUrl = baseUrl;

        if (baseUrl.contains("piston-meta.mojang.com") || baseUrl.contains("launchermeta.mojang.com")) {
            testUrl = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
        } else if (baseUrl.contains("resources.download.minecraft.net")) {
            testUrl = "https://resources.download.minecraft.net/00/0000000000000000000000000000000000000000";
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(testUrl))
                    .timeout(Duration.ofMillis(CHECK_TIMEOUT_MS))
                    .GET()
                    .header("User-Agent", "ZernMC-Launcher/HealthCheck")
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            return code == 200 || code == 404; // 404 для ресурсов — нормально
        } catch (Exception e) {
            return false;
        }
    }

    private static void startHealthCheckThread() {
        Thread healthThread = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(HEALTH_CHECK_INTERVAL_MS);
                    performHealthCheck();
                } catch (InterruptedException e) {
                    break;
                }
            }
        });
        healthThread.setDaemon(true);
        healthThread.start();
    }

    private static void performHealthCheck() {
        for (ServiceType service : ServiceType.values()) {
            if (service.isAlwaysDirect()) continue;

            boolean isHealthy = checkServiceHealth(service);
            serviceHealthy.put(service, isHealthy);

            if (isHealthy && serviceProxyMode.get(service)) {
                serviceProxyMode.put(service, false);
                serviceFailCount.put(service, 0);
                System.out.println(ZAnsi.green("[NET] " + service.name() + " восстановлен, переключен на прямое подключение"));
            } else if (!isHealthy && !serviceProxyMode.get(service)) {
                int fails = serviceFailCount.getOrDefault(service, 0) + 1;
                serviceFailCount.put(service, fails);
                serviceLastCheckTime.put(service, System.currentTimeMillis());

                if (fails >= MAX_FAILS_BEFORE_PROXY) {
                    serviceProxyMode.put(service, true);
                    System.out.println(ZAnsi.yellow("[NET] " + service.name() + " недоступен, включен прокси режим"));
                }
            }
        }
    }

    private static ServiceType detectService(String url) {
        if (url.contains("meta.fabricmc.net")) return ServiceType.FABRIC_META;
        if (url.contains("maven.fabricmc.net")) return ServiceType.FABRIC_MAVEN;
        if (url.contains("piston-meta.mojang.com") || url.contains("launchermeta.mojang.com"))
            return ServiceType.MOJANG_META;
        if (url.contains("resources.download.minecraft.net")) return ServiceType.MOJANG_RESOURCES;
        if (url.contains("maven.minecraftforge.net")) return ServiceType.FORGE_MAVEN;
        if (url.contains("google.com")) return ServiceType.GOOGLE;
        if (url.contains("cloudflare.com")) return ServiceType.CLOUDFLARE;
        return null;
    }

    private static boolean shouldUseProxyForUrl(String url) {
        if (useProxyMode.get()) return true;

        ServiceType service = detectService(url);
        if (service == null || service.isAlwaysDirect()) return false;

        return serviceProxyMode.getOrDefault(service, false);
    }

    private static boolean isConnectionError(Throwable e) {
        Throwable cause = e.getCause() != null ? e.getCause() : e;
        String msg = cause.getMessage() != null ? cause.getMessage().toLowerCase() : "";

        return cause instanceof java.net.ConnectException ||
               cause instanceof java.net.UnknownHostException ||
               cause instanceof java.nio.channels.ClosedChannelException ||
               msg.contains("connection") ||
               msg.contains("timeout") ||
               msg.contains("refused") ||
               msg.contains("closed");
    }

    private static void markServiceAsBlocked(String url) {
        ServiceType service = detectService(url);
        if (service == null || service.isAlwaysDirect()) return;

        int fails = serviceFailCount.getOrDefault(service, 0) + 1;
        serviceFailCount.put(service, fails);
        serviceLastCheckTime.put(service, System.currentTimeMillis());

        if (fails >= MAX_FAILS_BEFORE_PROXY && !serviceProxyMode.get(service)) {
            serviceProxyMode.put(service, true);
            System.out.println(ZAnsi.yellow("[NET] " + service.name() + " заблокирован, переключаемся на прокси"));
        }
    }
    /**
     * Универсальный GET с умным прокси + автоматическим fallback
     */
    public static String getWithSmartProxy(String url) throws IOException, InterruptedException {
        // Попытка прямого подключения
        if (!shouldUseProxyForUrl(url)) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(25))
                        .header("User-Agent", "ZernMC-Launcher/1.0")
                        .GET()
                        .build();

                HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    directSuccessCount++;
                    return response.body();
                }

                if (response.statusCode() >= 400) {
                    throw new IOException("HTTP " + response.statusCode());
                }
            } catch (Exception e) {
                if (isConnectionError(e)) {
                    directFailCount++;
                    markServiceAsBlocked(url);
                }
                // Если ошибка соединения — пробуем через прокси
            }
        }

        // Через прокси
        try {
            String encodedUrl = URLEncoder.encode(url, StandardCharsets.UTF_8);
            String proxyUrl = BASE_URL + "/download?url=" + encodedUrl;

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(proxyUrl))
                    .timeout(Duration.ofSeconds(40))
                    .header("User-Agent", "ZernMC-Launcher/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new IOException("Proxy HTTP " + response.statusCode());
            }

            proxySuccessCount++;
            return response.body();

        } catch (Exception e) {
            throw new IOException("Не удалось получить данные ни напрямую, ни через прокси: " + e.getMessage(), e);
        }
    }

    /**
     * Скачивание файла с умным прокси + fallback
     */
    public static void downloadFileWithSmartProxy(String url, Path target) throws Exception {
        if (!shouldUseProxyForUrl(url)) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(40))
                        .header("User-Agent", "ZernMC-Launcher/1.0")
                        .GET()
                        .build();

                HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(target));

                if (response.statusCode() == 200) {
                    directSuccessCount++;
                    return;
                }
            } catch (Exception e) {
                if (isConnectionError(e)) {
                    directFailCount++;
                    markServiceAsBlocked(url);
                }
                // fallback на прокси ниже
            }
        }

        // Скачивание через прокси
        String encodedUrl = URLEncoder.encode(url, StandardCharsets.UTF_8);
        String proxyUrl = BASE_URL + "/proxy/download?url=" + encodedUrl;

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(proxyUrl))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "ZernMC-Launcher/1.0")
                .GET()
                .build();

        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(target));

        if (response.statusCode() != 200) {
            throw new IOException("Proxy download failed: HTTP " + response.statusCode());
        }

        proxySuccessCount++;
    }

    // ====================== СТАРЫЕ МЕТОДЫ (обновлённые) ======================

    public static String get(String endpoint) throws IOException, InterruptedException {
        checkAllServicesOnStartup();

        if (useProxyMode.get()) {
            return proxyGet(endpoint);
        }

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + endpoint))
                    .timeout(Duration.ofSeconds(15))
                    .header("User-Agent", "ZernMC-Launcher/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
            return response.body();
        } catch (Exception e) {
            directFailCount++;
            throw e;
        }
    }

    private static String proxyGet(String endpoint) throws IOException {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + "/proxy" + endpoint))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "ZernMC-Launcher/1.0")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }

            proxySuccessCount++;
            return response.body();
        } catch (Exception e) {
            throw new IOException("Ошибка прокси: " + e.getMessage(), e);
        }
    }

    // ====================== МЕТОДЫ ДЛЯ EXTERNAL РЕСУРСОВ ======================

    public static List<String> getFabricLoaderVersions() throws IOException, InterruptedException {
        String url = "https://meta.fabricmc.net/v2/versions/loader";
        return parseFabricVersionsFromJson(getWithSmartProxy(url));
    }

    public static JSONObject getMojangVersionManifest() throws IOException, InterruptedException {
        String url = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
        String response = getWithSmartProxy(url);
        return new JSONObject(response);
    }

    public static JSONObject getMojangVersionJson(String versionId) throws IOException, InterruptedException {
        JSONObject manifest = getMojangVersionManifest();
        JSONArray versions = manifest.getJSONArray("versions");

        for (int i = 0; i < versions.length(); i++) {
            JSONObject v = versions.getJSONObject(i);
            if (v.getString("id").equals(versionId)) {
                return new JSONObject(getWithSmartProxy(v.getString("url")));
            }
        }
        throw new IOException("Version " + versionId + " not found");
    }

    public static String getForgeVersionsXml() throws IOException, InterruptedException {
        String url = "https://maven.minecraftforge.net/net/minecraftforge/forge/maven-metadata.xml";
        return getWithSmartProxy(url);
    }

    public static void downloadFile(String url, Path target) throws Exception {
        downloadFileWithSmartProxy(url, target);
    }

    public static void downloadAsset(String hash, Path target) throws Exception {
        String url = "https://resources.download.minecraft.net/" + hash.substring(0, 2) + "/" + hash;
        downloadFileWithSmartProxy(url, target);
    }

    public static String downloadString(String url) throws IOException, InterruptedException {
        return getWithSmartProxy(url);
    }

    private static List<String> parseFabricVersionsFromJson(String json) {
        JSONArray array = new JSONArray(json);
        List<String> versions = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.getJSONObject(i);
            if (obj.has("version")) {
                versions.add(obj.getString("version"));
            }
        }
        return versions;
    }

    // ====================== ВСПОМОГАТЕЛЬНЫЕ ======================

    public static String getBaseUrl() {
        return BASE_URL;
    }

    public static String getLauncherVersionInfo() throws IOException, InterruptedException {
        return get("/launcher/version");
    }

    public static void forceProxyMode() {
        useProxyMode.set(true);
        System.out.println(ZAnsi.yellow("Принудительно включен глобальный прокси режим"));
    }

    public static void disableProxyMode() {
        useProxyMode.set(false);
        for (ServiceType type : ServiceType.values()) {
            if (!type.isAlwaysDirect()) {
                serviceProxyMode.put(type, false);
                serviceFailCount.put(type, 0);
            }
        }
        System.out.println(ZAnsi.green("Режим прокси выключен"));
    }

    public static boolean isProxyMode() {
        return useProxyMode.get();
    }

    public static void printStats() {
        System.out.println(ZAnsi.cyan("\n=== Статистика сети ==="));
        System.out.println(ZAnsi.white("Глобальный прокси: ") + (useProxyMode.get() ? "ВКЛ" : "ВЫКЛ"));
        System.out.println(ZAnsi.white("Прямых успехов: ") + directSuccessCount);
        System.out.println(ZAnsi.white("Прямых неудач: ") + directFailCount);
        System.out.println(ZAnsi.white("Прокси успехов: ") + proxySuccessCount);

        System.out.println(ZAnsi.cyan("\nСтатус сервисов:"));
        for (ServiceType type : ServiceType.values()) {
            if (type.isAlwaysDirect()) continue;
            String status = serviceProxyMode.get(type) ? ZAnsi.red("ПРОКСИ") : ZAnsi.green("ПРЯМО");
            String health = serviceHealthy.get(type) ? ZAnsi.green("[+]") : ZAnsi.red("[-]");
            System.out.println(ZAnsi.white("  " + type.name() + ": ") + status + " " + health);
        }
    }
}