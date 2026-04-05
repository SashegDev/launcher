package me.sashegdev.zernmc.launcher.utils;

import java.io.IOException;
import java.net.URI;
//import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class ZHttpClient {

    private static final java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    private static final String BASE_URL = "http://87.120.187.36:1582";

    public static String get(String endpoint) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + endpoint))
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }

        return response.body();
    }

    public static String getBaseUrl() {
        return BASE_URL;
    }

    public static String getLauncherVersion() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/launcher/version"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }

        return response.body();
    }
    public static String getLauncherVersionInfo() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(BASE_URL + "/launcher/version"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }

        return response.body();
    }
    /**
     * Получить список всех доступных версий Fabric Loader
     */
    public static List<String> getFabricLoaderVersions() throws IOException, InterruptedException {
        String url = "https://meta.fabricmc.net/v2/versions/loader";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }

        // Парсим JSON массив
        org.json.JSONArray array = new org.json.JSONArray(response.body());
        List<String> versions = new ArrayList<>();

        for (int i = 0; i < array.length(); i++) {
            org.json.JSONObject obj = array.getJSONObject(i);
            if (obj.has("version")) {
                versions.add(obj.getString("version"));
            }
        }

        versions.sort((a, b) -> {
            // Правильная семантическая сортировка версий
            String[] partsA = a.split("\\+")[0].split("\\.");
            String[] partsB = b.split("\\+")[0].split("\\.");
            for (int i = 0; i < Math.min(partsA.length, partsB.length); i++) {
                int cmp = Integer.compare(Integer.parseInt(partsB[i]), Integer.parseInt(partsA[i]));
                if (cmp != 0) return cmp;
            }
            return b.compareTo(a); // fallback
        });

        return versions;
    }

    public static String downloadString(String url) throws IOException, InterruptedException {
    HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .GET()
                .build();
        
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        
        if (response.statusCode() != 200) {
            throw new IOException("HTTP " + response.statusCode());
        }
        return response.body();
    }
}