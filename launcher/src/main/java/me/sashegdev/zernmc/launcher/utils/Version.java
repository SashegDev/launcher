package me.sashegdev.zernmc.launcher.utils;

public class Version {

    public static String getCurrentVersion() {
        String version = Version.class.getPackage().getImplementationVersion();
        return (version != null && !version.isBlank()) ? version : "1.0.0";
    }

    /**
     * Универсальное сравнение версий
     * Возвращает true, если serverVersion новее currentVersion
     */
    public static boolean isNewer(String current, String server) {
        if (current == null || server == null) return false;

        // Убираем -SNAPSHOT для сравнения
        current = current.replace("-SNAPSHOT", "").trim();
        server = server.replace("-SNAPSHOT", "").trim();

        if (current.equals(server)) return false;

        String[] currentParts = current.split("\\.");
        String[] serverParts = server.split("\\.");

        int maxLength = Math.max(currentParts.length, serverParts.length);

        for (int i = 0; i < maxLength; i++) {
            int c = i < currentParts.length ? Integer.parseInt(currentParts[i]) : 0;
            int s = i < serverParts.length ? Integer.parseInt(serverParts[i]) : 0;

            if (s > c) return true;
            if (s < c) return false;
        }
        return false;
    }
}