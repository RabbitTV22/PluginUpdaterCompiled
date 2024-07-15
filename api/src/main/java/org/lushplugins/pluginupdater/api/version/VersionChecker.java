package org.lushplugins.pluginupdater.api.version;

import org.bukkit.Bukkit;
import org.lushplugins.pluginupdater.api.platform.PlatformData;
import org.lushplugins.pluginupdater.api.platform.PlatformRegistry;
import org.lushplugins.pluginupdater.api.updater.PluginData;
import org.lushplugins.pluginupdater.api.util.DownloadLogger;
import org.lushplugins.pluginupdater.api.util.UpdaterConstants;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@SuppressWarnings("CodeBlock2Expr")
public interface VersionChecker {
    Pattern VERSION_PATTERN = Pattern.compile("(\\d+(\\.\\d+)+)");

    String getLatestVersion(PluginData pluginData, PlatformData platformData) throws IOException;

    String getDownloadUrl(PluginData pluginData, PlatformData platformData) throws IOException;

    default boolean isUpdateAvailable(PluginData pluginData, PlatformData platformData) throws IOException {
        String currentVersion = pluginData.getCurrentVersion();

        Matcher matcher = VersionChecker.VERSION_PATTERN.matcher(getLatestVersion(pluginData, platformData));
        if (!matcher.find()) {
            return false;
        }
        String latestVersion = matcher.group();

        pluginData.setCheckRan(true);
        VersionDifference versionDifference = VersionDifference.getVersionDifference(currentVersion, latestVersion);
        if (!versionDifference.equals(VersionDifference.LATEST)) {
            pluginData.setLatestVersion(latestVersion);
            pluginData.setVersionDifference(versionDifference);
            return true;
        } else {
            return false;
        }
    }

    default boolean download(PluginData pluginData, PlatformData platformData) throws IOException {
        String pluginName = pluginData.getPluginName();
        String latestVersion = pluginData.getLatestVersion();
        String downloadUrl = getDownloadUrl(pluginData, platformData);
        if (downloadUrl == null) {
            return false;
        }

        URL url = new URL(downloadUrl);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.addRequestProperty("User-Agent", "PluginUpdater/" + UpdaterConstants.VERSION);
        connection.setInstanceFollowRedirects(true);
        HttpURLConnection.setFollowRedirects(true);

        if (connection.getResponseCode() != 200) {
            throw new IllegalStateException("Response code was " + connection.getResponseCode());
        }

        ReadableByteChannel rbc = Channels.newChannel(connection.getInputStream());
        String fileName = pluginName + "-" + latestVersion + ".jar";
        File out = new File(Bukkit.getUpdateFolderFile(), fileName);
        UpdaterConstants.LOGGER.info("Saving '" + fileName + "' to '" + out.getAbsolutePath() + "'");
        FileOutputStream fos = new FileOutputStream(out);
        fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
        fos.close();

        DownloadLogger.logDownload(pluginData);

        return true;
    }

    static String getLatestVersion(PluginData pluginData) throws IOException {
        try {
            return attemptOnPlatforms(pluginData, (versionChecker, platformData) -> {
                return versionChecker.getLatestVersion(pluginData, platformData);
            });
        } catch (IOException e) {
            throw new IOException("Failed to check plugin '" + pluginData.getPluginName() + "' for latest version.");
        }
    }

    static String getDownloadUrl(PluginData pluginData) throws IOException {
        try {
            return attemptOnPlatforms(pluginData, (versionChecker, platformData) -> {
                return versionChecker.getDownloadUrl(pluginData, platformData);
            });
        } catch (IOException e) {
            throw new IOException("Failed to get download url for plugin '" + pluginData.getPluginName() + "'.");
        }
    }

    static boolean isUpdateAvailable(PluginData pluginData) throws IOException {
        try {
            return attemptOnPlatforms(pluginData, (versionChecker, platformData) -> {
                return versionChecker.isUpdateAvailable(pluginData, platformData);
            });
        } catch (IOException e) {
            throw new IOException("Failed to check if update is available for plugin '" + pluginData.getPluginName() + "'.");
        }
    }

    static boolean download(PluginData pluginData) throws IOException {
        try {
            return attemptOnPlatforms(pluginData, (versionChecker, platformData) -> {
                return versionChecker.download(pluginData, platformData);
            });
        } catch (IOException e) {
            throw new IOException("Failed to download update for plugin '" + pluginData.getPluginName() + "'.");
        }
    }

    private static <T> T attemptOnPlatforms(PluginData pluginData, VersionCheckerCallable<T> callable) throws IOException {
        for (PlatformData platformData : pluginData.getPlatformData()) {
            VersionChecker versionChecker = PlatformRegistry.getVersionChecker(platformData.getName());
            if (versionChecker == null) {
                continue;
            }

            try {
                return callable.call(versionChecker, platformData);
            } catch (IOException e) {
                UpdaterConstants.LOGGER.severe(e.getMessage());
            }
        }

        throw new IOException("Failed to all attempts on all available platforms for plugin '" + pluginData.getPluginName() + "'.");
    }

    @FunctionalInterface
    interface VersionCheckerCallable<T> {
        T call(VersionChecker versionChecker, PlatformData platformData) throws IOException;
    }
}
