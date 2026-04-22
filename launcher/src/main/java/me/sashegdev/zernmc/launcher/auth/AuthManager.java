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
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public class AuthManager {

    private static final Path AUTH_FILE = Config.getConfigDir().resolve("auth.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile AuthSession session = null;
    private static volatile UserInfo userInfo = null;

    // === Роли ===
    public static final int ROLE_USER = 0;
    public static final int ROLE_PASS_HOLDER = 1;
    public static final int ROLE_MODERATOR = 2;
    public static final int ROLE_ELDER = 3;
    public static final int ROLE_CREATOR = 4;

    // === Права доступа ===
    public static final String PERM_VIEW_PACKS = "view_packs";
    public static final String PERM_DOWNLOAD_PACK = "download_pack";

    public static boolean loadSavedSession() {
        if (!Files.exists(AUTH_FILE)) return false;
        try {
            String json = Files.readString(AUTH_FILE);
            AuthSession loaded = GSON.fromJson(json, AuthSession.class);
            if (loaded == null || loaded.accessToken == null) return false;

            session = loaded;
            userInfo = fetchUserInfo();

            if (isAccessTokenExpired()) {
                return tryRefresh();
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ====================== АВТОРИЗАЦИЯ ======================
    public static AuthResult login(String username, String password) {
        return authRequest("/auth/login", username, password);
    }

    public static AuthResult register(String username, String password) {
        return authRequest("/auth/register", username, password);
    }

    private static AuthResult authRequest(String endpoint, String username, String password) {
        try {
            String body = GSON.toJson(new LoginRequest(username, password));
            SimpleHttpResponse resp = post(endpoint, body);

            if (resp.statusCode() == 200) {
                session = GSON.fromJson(resp.body(), AuthSession.class);
                session.expiresAt = System.currentTimeMillis() / 1000L + session.expiresIn;
                saveSession();
                userInfo = fetchUserInfo();
                return AuthResult.ok();
            } else if (resp.statusCode() == 422) {
                return AuthResult.fail("Ошибка валидации: " + extractError(resp.body()));
            } else {
                return AuthResult.fail(extractError(resp.body()));
            }
        } catch (Exception e) {
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
        userInfo = null;
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
            SimpleHttpResponse resp = post("/auth/refresh", body);

            if (resp.statusCode() == 200) {
                AuthSession newSession = GSON.fromJson(resp.body(), AuthSession.class);
                newSession.expiresAt = System.currentTimeMillis() / 1000L + newSession.expiresIn;
                session = newSession;
                userInfo = fetchUserInfo();
                saveSession();
                return true;
            }
        } catch (Exception ignored) {}
        session = null;
        userInfo = null;
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

    // ==================== ПОЛУЧЕНИЕ ИНФОРМАЦИИ О ПОЛЬЗОВАТЕЛЕ ====================
    private static UserInfo fetchUserInfo() {
        if (!isLoggedIn() || session.accessToken == null) return null;

        try {
            // Используем существующий метод ZHttpClient.get() + вручную добавляем токен
            java.net.HttpURLConnection conn = null;
            try {
                URL url = new URL(ZHttpClient.getBaseUrl() + "/admin/me");
                conn = (java.net.HttpURLConnection) url.openConnection();
                conn.setRequestMethod("GET");
                conn.setRequestProperty("Accept", "application/json");
                conn.setRequestProperty("Authorization", "Bearer " + session.accessToken);
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(10000);

                int responseCode = conn.getResponseCode();
                if (responseCode != 200) return null;

                StringBuilder response = new StringBuilder();
                try (var reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                }
                return GSON.fromJson(response.toString(), UserInfo.class);
            } finally {
                if (conn != null) conn.disconnect();
            }
        } catch (Exception e) {
            System.err.println("Не удалось получить UserInfo: " + e.getMessage());
            return null;
        }
    }

    // ==================== ПРОВЕРКИ ПРАВ ====================
    public static boolean hasPass() {
        if (userInfo != null) return userInfo.has_pass;
        return getRole() >= ROLE_PASS_HOLDER;
    }

    public static boolean canViewPacks() {
        if (userInfo != null && userInfo.permissions != null) {
            return userInfo.permissions.contains(PERM_VIEW_PACKS);
        }
        return hasPass(); // fallback для старых аккаунтов
    }

    public static boolean canDownloadPacks() {
        if (userInfo != null && userInfo.permissions != null) {
            return userInfo.permissions.contains(PERM_DOWNLOAD_PACK);
        }
        return hasPass(); // fallback
    }

    public static int getRole() {
        return session != null ? session.role : ROLE_USER;
    }

    // ====================== POST ======================
    private static SimpleHttpResponse post(String endpoint, String jsonBody) throws Exception {
        String fullUrl = ZHttpClient.getBaseUrl() + endpoint;
        HttpURLConnection conn = null;

        try {
            URL url = new URL(fullUrl);
            conn = (HttpURLConnection) url.openConnection();

            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "ZernMC-Launcher/1.0");
            conn.setRequestProperty("Connection", "close");

            if (session != null && session.accessToken != null) {
                conn.setRequestProperty("Authorization", "Bearer " + session.accessToken);
            }

            conn.setDoOutput(true);
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(15000);

            byte[] input = jsonBody.getBytes(StandardCharsets.UTF_8);
            conn.setFixedLengthStreamingMode(input.length);

            try (var os = conn.getOutputStream()) {
                os.write(input);
                os.flush();
            }

            int statusCode = conn.getResponseCode();
            var is = (statusCode >= 200 && statusCode < 300) ? conn.getInputStream() : conn.getErrorStream();

            String responseBody;
            try (var scanner = new java.util.Scanner(is, StandardCharsets.UTF_8.name())) {
                responseBody = scanner.useDelimiter("\\A").hasNext() ? scanner.next() : "";
            }

            return new SimpleHttpResponse(statusCode, responseBody);

        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String extractError(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (json.has("detail")) {
                if (json.get("detail").isJsonArray()) {
                    return json.getAsJsonArray("detail").get(0).getAsJsonObject().get("msg").getAsString();
                }
                return json.get("detail").getAsString();
            }
        } catch (Exception ignored) {}
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
    }

    public static boolean hasActivePass() {
        if (!isLoggedIn()) return false;
        try {
            String response = ZHttpClient.get("/auth/pass/my");
            JsonObject json = JsonParser.parseString(response).getAsJsonObject();
            return json.has("has_active") && json.get("has_active").getAsBoolean();
        } catch (Exception e) {
            System.err.println(ZAnsi.red("Не удалось проверить проходки: ") + e.getMessage());
            return false;
        }
    }
    
    public static String getPassStatus() {
        if (!isLoggedIn()) return "Не авторизован";
        try {
            String response = ZHttpClient.get("/auth/pass/my");
            JsonObject json = JsonParser.parseString(response).getAsJsonObject();
            boolean hasActive = json.has("has_active") && json.get("has_active").getAsBoolean();
            return hasActive ? "Есть активная проходка" : "Проходка отсутствует";
        } catch (Exception e) {
            return "Ошибка проверки";
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
        public int role;
    }

    public static class UserInfo {
        public int id;
        public String username;
        public String uuid;
        public int role;
        public String role_name;
        public boolean has_pass;
        public List<String> permissions;

        public boolean hasPermission(String perm) {
            return permissions != null && permissions.contains(perm);
        }
    }

    private static class LoginRequest {
        final String username;
        final String password;
        LoginRequest(String u, String p) {
            this.username = u;
            this.password = p;
        }
    }

    public static class AuthResult {
        public final boolean success;
        public final String error;
        private AuthResult(boolean s, String e) { success = s; error = e; }
        public static AuthResult ok() { return new AuthResult(true, null); }
        public static AuthResult fail(String msg) { return new AuthResult(false, msg); }
    }
}

// ====================== ВСПОМОГАТЕЛЬНЫЙ КЛАСС ======================
class SimpleHttpResponse {
    final int statusCode;
    final String body;

    SimpleHttpResponse(int statusCode, String body) {
        this.statusCode = statusCode;
        this.body = body;
    }

    int statusCode() { return statusCode; }
    String body() { return body; }
}