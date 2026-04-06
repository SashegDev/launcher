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
        ZERN_SERVER(BASE_URL, true),      // Всегда прямое подключение
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
        
        public String getBaseUrl() {
            return baseUrl;
        }
        
        public boolean isAlwaysDirect() {
            return alwaysDirect;
        }
    }
    
    // Статусы сервисов
    private static final Map<ServiceType, Boolean> serviceProxyMode = new ConcurrentHashMap<>();
    private static final Map<ServiceType, Integer> serviceFailCount = new ConcurrentHashMap<>();
    private static final Map<ServiceType, Long> serviceLastCheckTime = new ConcurrentHashMap<>();
    private static final Map<ServiceType, Boolean> serviceHealthy = new ConcurrentHashMap<>();
    
    private static final int MAX_FAILS_BEFORE_PROXY = 2;
    private static final long HEALTH_CHECK_INTERVAL_MS = 60000; // 1 минута
    private static final long CHECK_TIMEOUT_MS = 5000; // 5 секунд на проверку
    
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
     * Проверить все сервисы при старте
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
            ServiceType.FORGE_MAVEN
        );
        
        for (ServiceType service : servicesToCheck) {
            boolean isHealthy = checkServiceHealth(service);
            serviceHealthy.put(service, isHealthy);
            
            if (service.isAlwaysDirect()) {
                if (!isHealthy) {
                    System.out.println(ZAnsi.red("  " + service.name() + " - НЕ ДОСТУПЕН (критично!)"));
                } else {
                    System.out.println(ZAnsi.green("  " + service.name() + " - OK"));
                }
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
        
        // Проверяем, нужно ли включить глобальный прокси режим
        boolean anyCriticalDown = !serviceHealthy.get(ServiceType.ZERN_SERVER);
        if (anyCriticalDown) {
            System.out.println(ZAnsi.brightRed("Критическая ошибка: Zern сервер недоступен!"));
        }
        
        proxyTested.set(true);
        
        // Запускаем фоновую проверку
        startHealthCheckThread();
        
        printStats();
    }
    
    /**
     * Проверить здоровье конкретного сервиса
     */
    private static boolean checkServiceHealth(ServiceType service) {
        if (service.isAlwaysDirect()) {
            return checkDirectConnection(service.getBaseUrl());
        }
        
        return checkDirectConnection(service.getBaseUrl());
    }
    
    /**
     * Проверить прямое подключение к URL
     */
    private static boolean checkDirectConnection(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMillis(CHECK_TIMEOUT_MS))
                    .HEAD()
                    .build();
            HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Запустить фоновый поток для периодической проверки
     */
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
    
    /**
     * Периодическая проверка здоровья сервисов
     */
    private static void performHealthCheck() {
        for (ServiceType service : ServiceType.values()) {
            if (service.isAlwaysDirect()) continue;
            
            boolean isHealthy = checkServiceHealth(service);
            serviceHealthy.put(service, isHealthy);
            
            if (isHealthy && serviceProxyMode.get(service)) {
                // Сервис восстановился - пробуем переключить обратно
                Long lastCheck = serviceLastCheckTime.get(service);
                if (lastCheck == null || System.currentTimeMillis() - lastCheck > HEALTH_CHECK_INTERVAL_MS) {
                    serviceProxyMode.put(service, false);
                    serviceFailCount.put(service, 0);
                    System.out.println(ZAnsi.green("[NET] " + service.name() + " восстановлен, переключен на прямое подключение"));
                }
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
    
    /**
     * Определить тип сервиса по URL
     */
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
    
    /**
     * Нужно ли использовать прокси для URL
     */
    private static boolean shouldUseProxyForUrl(String url) {
        if (useProxyMode.get()) return true;
        
        ServiceType service = detectService(url);
        if (service == null) return false;
        if (service.isAlwaysDirect()) return false;
        
        return serviceProxyMode.getOrDefault(service, false);
    }
    
    /**
     * Получить URL через прокси если нужно
     */
    public static String getWithSmartProxy(String url) throws IOException, InterruptedException {
        if (shouldUseProxyForUrl(url)) {
            String encodedUrl = URLEncoder.encode(url, StandardCharsets.UTF_8);
            return proxyGet("/download?url=" + encodedUrl);
        }
        
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "ZernMC-Launcher/1.0")
                    .GET()
                    .build();
            
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
            
            directSuccessCount++;
            return response.body();
            
        } catch (Exception e) {
            directFailCount++;
            ServiceType service = detectService(url);
            if (service != null && !service.isAlwaysDirect()) {
                int fails = serviceFailCount.getOrDefault(service, 0) + 1;
                serviceFailCount.put(service, fails);
                if (fails >= MAX_FAILS_BEFORE_PROXY) {
                    serviceProxyMode.put(service, true);
                }
            }
            throw e;
        }
    }
    
    /**
     * Скачать файл с умным прокси
     */
    public static void downloadFileWithSmartProxy(String url, Path target) throws Exception {
        if (shouldUseProxyForUrl(url)) {
            downloadViaProxy(url, target);
            return;
        }
        
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .header("User-Agent", "ZernMC-Launcher/1.0")
                    .GET()
                    .build();
            
            HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(target));
            
            if (response.statusCode() != 200) {
                throw new IOException("HTTP " + response.statusCode());
            }
            
            directSuccessCount++;
            
        } catch (Exception e) {
            directFailCount++;
            ServiceType service = detectService(url);
            if (service != null && !service.isAlwaysDirect()) {
                int fails = serviceFailCount.getOrDefault(service, 0) + 1;
                serviceFailCount.put(service, fails);
                if (fails >= MAX_FAILS_BEFORE_PROXY) {
                    serviceProxyMode.put(service, true);
                }
            }
            throw e;
        }
    }
    
    // ====================== ОСНОВНЫЕ МЕТОДЫ (СОХРАНЕНЫ) ======================
    
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
            throw new IOException("Ошибка прокси запроса: " + e.getMessage(), e);
        }
    }
    
    private static void downloadViaProxy(String url, Path target) throws Exception {
        String encodedUrl = URLEncoder.encode(url, StandardCharsets.UTF_8.toString());
        String proxyUrl = BASE_URL + "/proxy/download?url=" + encodedUrl;
        
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(proxyUrl))
                .timeout(Duration.ofMinutes(5))
                .header("User-Agent", "ZernMC-Launcher/1.0")
                .GET()
                .build();
        
        HttpResponse<Path> response = client.send(request, HttpResponse.BodyHandlers.ofFile(target));
        
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        
        proxySuccessCount++;
    }
    
    // ====================== МЕТОДЫ ДЛЯ EXTERNAL РЕСУРСОВ ======================
    
    public static List<String> getFabricLoaderVersions() throws IOException, InterruptedException {
        String url = "https://meta.fabricmc.net/v2/versions/loader";
        String response = getWithSmartProxy(url);
        return parseFabricVersionsFromJson(response);
    }
    
    public static JSONObject getMojangVersionManifest() throws IOException, InterruptedException {
        String url = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
        String response = getWithSmartProxy(url);
        return new JSONObject(response);
    }
    
    public static JSONObject getMojangVersionJson(String versionId) throws IOException, InterruptedException {
        JSONObject manifest = getMojangVersionManifest();
        JSONArray versions = manifest.getJSONArray("versions");
        
        String versionUrl = null;
        for (int i = 0; i < versions.length(); i++) {
            JSONObject v = versions.getJSONObject(i);
            if (v.getString("id").equals(versionId)) {
                versionUrl = v.getString("url");
                break;
            }
        }
        
        if (versionUrl == null) {
            throw new IOException("Version " + versionId + " not found");
        }
        
        String response = getWithSmartProxy(versionUrl);
        return new JSONObject(response);
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
    
    // ====================== ВСПОМОГАТЕЛЬНЫЕ МЕТОДЫ ======================
    
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
        System.out.println(ZAnsi.white("Глобальный прокси режим: ") + (useProxyMode.get() ? "ВКЛЮЧЕН" : "ВЫКЛЮЧЕН"));
        System.out.println(ZAnsi.white("Прямых успехов: ") + directSuccessCount);
        System.out.println(ZAnsi.white("Прямых неудач: ") + directFailCount);
        System.out.println(ZAnsi.white("Прокси успехов: ") + proxySuccessCount);
        
        System.out.println(ZAnsi.cyan("\nСтатус сервисов:"));
        for (ServiceType type : ServiceType.values()) {
            if (type.isAlwaysDirect()) continue;
            String status = serviceProxyMode.get(type) ? ZAnsi.red("ПРОКСИ") : ZAnsi.green("ПРЯМО");
            String health = serviceHealthy.get(type) ? ZAnsi.green("✓") : ZAnsi.red("✗");
            System.out.println(ZAnsi.white("  " + type.name() + ": ") + status + " " + health);
        }
    }
}