package me.sashegdev.zernmc.launcher.auth;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import me.sashegdev.zernmc.launcher.utils.Config;
import me.sashegdev.zernmc.launcher.utils.ZAnsi;
import me.sashegdev.zernmc.launcher.utils.ZHttpClient;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

public class AuthManager {

    private static final Path AUTH_FILE = Config.getConfigDir().resolve("auth.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .version(HttpClient.Version.HTTP_1_1)
            .build();

    private static volatile AuthSession session = null;  // ← volatile для потокобезопасности

    public static boolean loadSavedSession() {
        if (!Files.exists(AUTH_FILE)) return false;
        try {
            String json = Files.readString(AUTH_FILE);
            AuthSession loaded = GSON.fromJson(json, AuthSession.class);
            if (loaded == null || loaded.accessToken == null) return false;

            session = loaded;
            if (isAccessTokenExpired()) {
                return tryRefresh();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static AuthResult login(String username, String password) {
        return authRequest("/auth/login", username, password);
    }

    public static AuthResult register(String username, String password) {
        return authRequest("/auth/register", username, password);
    }

    private static AuthResult authRequest(String endpoint, String username, String password) {
        try {
            String body = GSON.toJson(new LoginRequest(username, password));
            
            // Логируем для отладки
            System.out.println(ZAnsi.cyan("[AUTH] Отправка запроса: " + endpoint));
            
            HttpResponse<String> resp = post(endpoint, body);

            System.out.println(ZAnsi.cyan("[AUTH] Ответ: HTTP " + resp.statusCode()));
            
            if (resp.statusCode() == 200) {
                session = GSON.fromJson(resp.body(), AuthSession.class);
                session.expiresAt = System.currentTimeMillis() / 1000L + session.expiresIn;
                saveSession();
                return AuthResult.ok();
            } else if (resp.statusCode() == 422) {
                // Ошибка валидации — парсим детали
                return AuthResult.fail("Ошибка валидации: " + extractError(resp.body()));
            } else {
                return AuthResult.fail(extractError(resp.body()));
            }
        } catch (Exception e) {
            System.err.println(ZAnsi.red("[AUTH] Исключение: " + e.getMessage()));
            e.printStackTrace();
            return AuthResult.fail("Ошибка соединения: " + e.getMessage());
        }
    }

    public static void logout() {
        if (session != null && session.refreshToken != null) {
            try { 
                post("/auth/logout", "{\"refresh_token\":\"" + session.refreshToken + "\"}"); 
            } catch (Exception ignored) {}
        }
        session = null;
        try { Files.deleteIfExists(AUTH_FILE); } catch (Exception ignored) {}
    }

    public static boolean isLoggedIn() {
        return session != null && session.accessToken != null;
    }

    public static String getUsername() {
        return session != null ? session.username : "Player";
    }

    public static String getUuid() {
        return session != null ? session.uuid : "00000000-0000-0000-0000-000000000000";
    }

    public static String getAccessToken() {
        if (session == null) return "0";
        if (isAccessTokenExpired()) {
            // ВАЖНО: в UI-контексте это плохо, но пока оставим
            tryRefresh();
        }
        return session != null && session.accessToken != null ? session.accessToken : "0";
    }

    private static boolean isAccessTokenExpired() {
        if (session == null) return true;
        return System.currentTimeMillis() / 1000L >= session.expiresAt - 300;
    }

    private static boolean tryRefresh() {
        if (session == null || session.refreshToken == null) return false;
        try {
            String body = "{\"refresh_token\":\"" + session.refreshToken + "\"}";
            HttpResponse<String> resp = post("/auth/refresh", body);

            if (resp.statusCode() == 200) {
                AuthSession newSession = GSON.fromJson(resp.body(), AuthSession.class);
                newSession.expiresAt = System.currentTimeMillis() / 1000L + newSession.expiresIn;
                session = newSession;
                saveSession();
                return true;
            }
        } catch (Exception ignored) {}
        session = null;
        try { Files.deleteIfExists(AUTH_FILE); } catch (Exception ignored) {}
        return false;
    }

    private static void saveSession() {
        try {
            Files.createDirectories(AUTH_FILE.getParent());
            Files.writeString(AUTH_FILE, GSON.toJson(session));
        } catch (IOException e) {
            System.err.println(ZAnsi.yellow("Не удалось сохранить сессию: " + e.getMessage()));
        }
    }

    private static HttpResponse<String> post(String endpoint, String jsonBody) throws Exception {
        String fullUrl = ZHttpClient.getBaseUrl() + endpoint;
        
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(fullUrl))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .header("User-Agent", "ZernMC-Launcher/1.0")
                .header("Connection", "close")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        
        // Логируем для отладки
        System.out.println(ZAnsi.cyan("[HTTP] → POST " + fullUrl));
        System.out.println(ZAnsi.cyan("[HTTP] Body: " + jsonBody));
        
        return HTTP.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private static String extractError(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            
            // FastAPI формат
            if (json.has("detail")) {
                // Может быть строкой или массивом
                if (json.get("detail").isJsonArray()) {
                    return json.getAsJsonArray("detail").get(0).getAsJsonObject()
                            .get("msg").getAsString();
                }
                return json.get("detail").getAsString();
            }
            // Наш формат
            if (json.has("error")) {
                return json.get("error").getAsString();
            }
        } catch (Exception ignored) {}
        
        // Если не смогли распарсить — возвращаем первые 200 символов
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }

    public static boolean hasActivePass() {
        if (!isLoggedIn()) return false;
        try {
            String response = ZHttpClient.get("/auth/pass/my");
            return response.contains("\"is_active\":true");
        } catch (Exception e) {
            System.err.println("Не удалось проверить проходки: " + e.getMessage());
            return false;
        }
    }
    
    public static String activatePass(String passCode) {
        try {
            String json = "{\"pass_code\":\"" + passCode.toUpperCase() + "\"}";
            HttpResponse<String> resp = post("/auth/pass/activate", json);
            
            if (resp.statusCode() == 200) {
                return "Проходка успешно активирована!";
            } else {
                String error = extractError(resp.body());
                return "Ошибка: " + error;
            }
        } catch (Exception e) {
            return "Ошибка соединения: " + e.getMessage();
        }
    }

    // ====================== ВНУТРЕННИЕ КЛАССЫ ======================

    public static class AuthSession {
        @SerializedName("access_token") public String accessToken;
        @SerializedName("refresh_token") public String refreshToken;
        @SerializedName("expires_in") public int expiresIn;
        public transient long expiresAt;
        public String username;
        public String uuid;
    }

    private static class LoginRequest {
        final String username;
        final String password;
        LoginRequest(String u, String p) { this.username = u; this.password = p; }
    }

    public static class AuthResult {
        public final boolean success;
        public final String error;
        private AuthResult(boolean s, String e) { success = s; error = e; }
        public static AuthResult ok() { return new AuthResult(true, null); }
        public static AuthResult fail(String msg) { return new AuthResult(false, msg); }
    }
}