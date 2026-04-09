package me.sashegdev.zernmc.launcher.auth;

import com.google.gson.*;
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
import java.util.List;

public class AuthManager {

    private static final Path AUTH_FILE = Config.getConfigDir().resolve("auth.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    private static volatile AuthSession session = null;
    private static volatile UserInfo userInfo = null;

    // === Роли (для совместимости) ===
    public static final int ROLE_USER = 0;
    public static final int ROLE_PASS_HOLDER = 1;
    public static final int ROLE_MODERATOR = 2;
    public static final int ROLE_ELDER = 3;
    public static final int ROLE_CREATOR = 4;

    // === Права доступа (синхронизировано с сервером) ===
    public static final String PERM_VIEW_PACKS = "view_packs";
    public static final String PERM_DOWNLOAD_PACK = "download_pack";
    public static final String PERM_REQUEST_PASS = "request_pass";

    public static boolean loadSavedSession() {
        if (!Files.exists(AUTH_FILE)) return false;
        try {
            String json = Files.readString(AUTH_FILE);
            AuthSession loaded = GSON.fromJson(json, AuthSession.class);
            if (loaded == null || loaded.accessToken == null) return false;

            session = loaded;

            if (session.username != null) {
                userInfo = fetchUserInfo();
            }

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
            JsonObject body = new JsonObject();
            body.addProperty("username", username);
            body.addProperty("password", password);

            String jsonBody = GSON.toJson(body);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ZHttpClient.getBaseUrl() + endpoint))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .header("User-Agent", "ZernMC-Launcher/1.0")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                session = GSON.fromJson(response.body(), AuthSession.class);
                session.expiresAt = System.currentTimeMillis() / 1000L + session.expiresIn;
                saveSession();

                userInfo = fetchUserInfo();

                return AuthResult.ok();
            } else {
                String error = extractError(response.body());
                return AuthResult.fail(error);
            }
        } catch (Exception e) {
            return AuthResult.fail("Ошибка соединения: " + e.getMessage());
        }
    }

    public static void logout() {
        if (session != null && session.refreshToken != null) {
            try {
                JsonObject body = new JsonObject();
                body.addProperty("refresh_token", session.refreshToken);

                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(ZHttpClient.getBaseUrl() + "/auth/logout"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                        .build();

                HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
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

    public static int getRole() {
        return session != null ? session.role : ROLE_USER;
    }

    public static String getRoleName() {
        return session != null ? session.roleName : "Игрок";
    }

    // === Основные проверки ===
    public static boolean hasPass() {
        if (userInfo != null) return userInfo.has_pass;
        return getRole() >= ROLE_PASS_HOLDER;
    }

    public static boolean hasPermission(String permission) {
        if (userInfo != null && userInfo.permissions != null) {
            return userInfo.permissions.contains(permission);
        }
        // Fallback на старую систему
        if (PERM_VIEW_PACKS.equals(permission) || PERM_DOWNLOAD_PACK.equals(permission)) {
            return hasPass();
        }
        return false;
    }

    public static boolean canViewPacks() {
        return hasPermission(PERM_VIEW_PACKS);
    }

    public static boolean canDownloadPacks() {
        return hasPermission(PERM_DOWNLOAD_PACK);
    }

    public static String getAccessToken() {
        if (session == null) return null;
        if (isAccessTokenExpired()) {
            tryRefresh();
        }
        return session != null ? session.accessToken : null;
    }

    private static UserInfo fetchUserInfo() {
        if (!isLoggedIn()) return null;

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ZHttpClient.getBaseUrl() + "/admin/me"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + session.accessToken)
                    .header("Accept", "application/json")
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return GSON.fromJson(response.body(), UserInfo.class);
            }
        } catch (Exception e) {
            System.err.println("Не удалось получить информацию о пользователе: " + e.getMessage());
        }
        return null;
    }

    private static boolean isAccessTokenExpired() {
        if (session == null) return true;
        return System.currentTimeMillis() / 1000L >= session.expiresAt - 300;
    }

    private static boolean tryRefresh() {
        if (session == null || session.refreshToken == null) return false;

        try {
            JsonObject body = new JsonObject();
            body.addProperty("refresh_token", session.refreshToken);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(ZHttpClient.getBaseUrl() + "/auth/refresh"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                String newAccessToken = json.get("access_token").getAsString();
                int expiresIn = json.get("expires_in").getAsInt();

                session.accessToken = newAccessToken;
                session.expiresAt = System.currentTimeMillis() / 1000L + expiresIn;
                saveSession();
                userInfo = fetchUserInfo(); // обновляем информацию после рефреша
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

    private static String extractError(String body) {
        try {
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            if (json.has("detail")) {
                if (json.get("detail").isJsonArray()) {
                    return json.getAsJsonArray("detail").get(0).getAsJsonObject()
                            .get("msg").getAsString();
                }
                return json.get("detail").getAsString();
            }
            if (json.has("error")) {
                return json.get("error").getAsString();
            }
        } catch (Exception ignored) {}
        return body.length() > 200 ? body.substring(0, 200) + "..." : body;
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
        @SerializedName("role_name") public String roleName;
    }

    public static class UserInfo {
        public int id;
        public String username;
        public String uuid;
        public int role;
        public String role_name;
        public long created_at;
        public Long last_login;
        public boolean has_pass;
        public List<String> permissions;

        public boolean hasPermission(String permission) {
            return permissions != null && permissions.contains(permission);
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