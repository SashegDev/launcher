package me.sashegdev.zernmc.launcher.utils;

import java.util.jar.Attributes;
import java.util.jar.Manifest;

public class Version {

    public static String getCurrentVersion() {
        try {
            // Способ 1: Из манифеста (самый правильный)
            Manifest manifest = new Manifest(
                Version.class.getClassLoader().getResourceAsStream("META-INF/MANIFEST.MF")
            );

            String version = manifest.getMainAttributes().getValue(Attributes.Name.IMPLEMENTATION_VERSION);
            if (version != null && !version.isBlank()) {
                return version;
            }

            // Способ 2: Из Package (запасной)
            version = Version.class.getPackage().getImplementationVersion();
            if (version != null && !version.isBlank()) {
                return version;
            }

        } catch (Exception ignored) {
            // если не получилось прочитать манифест — идём дальше
        }

        // Финальный fallback
        return "1.0.0";
    }

    public static boolean isNewer(String current, String server) {
        if (current == null || server == null) return false;

        current = current.replace("-SNAPSHOT", "").trim();
        server = server.replace("-SNAPSHOT", "").trim();

        if (current.equals(server)) return false;

        String[] cParts = current.split("\\.");
        String[] sParts = server.split("\\.");

        int max = Math.max(cParts.length, sParts.length);

        for (int i = 0; i < max; i++) {
            int c = i < cParts.length ? Integer.parseInt(cParts[i]) : 0;
            int s = i < sParts.length ? Integer.parseInt(sParts[i]) : 0;

            if (s > c) return true;
            if (s < c) return false;
        }
        return false;
    }
}