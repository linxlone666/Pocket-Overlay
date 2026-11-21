package app.pocketoverlay;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.regex.Pattern;

final class AppUpdate {
    static final String PROJECT = "https://github.com/linxlone666/Pocket-Plus";
    // GitHub repository IDs stay the same when a repository is renamed.
    private static final String LATEST =
            "https://api.github.com/repositories/1449641281/releases/latest";
    private static final int MAX_METADATA_BYTES = 1024 * 1024;
    private static final long MAX_APK_BYTES = 64L * 1024 * 1024;

    private AppUpdate() {}

    static final class Release {
        final String version;
        final URL apk;
        final long size;
        final String digest;

        Release(String version, URL apk, long size, String digest) {
            this.version = version;
            this.apk = apk;
            this.size = size;
            this.digest = digest;
        }
    }

    static Release check(String installedVersion) throws IOException, JSONException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        HttpURLConnection connection;
        try {
            connection = connect(new URL(LATEST), "application/vnd.github+json");
        } catch (HttpStatusException error) {
            if (error.status != HttpURLConnection.HTTP_FORBIDDEN && error.status != 429) throw error;
            return checkPublicRelease(installedVersion);
        }
        try (InputStream input = connection.getInputStream()) {
            copy(input, body, MAX_METADATA_BYTES);
        } finally {
            connection.disconnect();
        }
        JSONObject release = new JSONObject(new String(body.toByteArray(), StandardCharsets.UTF_8));
        if (release.getBoolean("draft") || release.getBoolean("prerelease")) return null;
        String tag = release.getString("tag_name");
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        if (!isNewer(version, installedVersion)) return null;

        String name = "Pocket-Overlay-" + version + ".apk";

        JSONArray assets = release.getJSONArray("assets");
        for (int i = 0; i < assets.length(); i++) {
            JSONObject asset = assets.getJSONObject(i);
            if (!name.equals(asset.getString("name"))) continue;
            URL download = new URL(asset.getString("browser_download_url"));
            if (!matchesAsset(download, tag, name)) continue;
            long size = asset.getLong("size");
            String digest = asset.isNull("digest") ? "" : asset.optString("digest");
            if (size <= 0 || size > MAX_APK_BYTES
                    || (!digest.isEmpty() && !digest.matches("sha256:[0-9a-fA-F]{64}"))) {
                throw new IOException("Invalid update asset metadata");
            }
            return new Release(version, download, size, digest);
        }
        throw new IOException("The latest release has no matching APK");
    }

    private static Release checkPublicRelease(String installedVersion) throws IOException {
        HttpURLConnection page = connect(new URL(PROJECT + "/releases/latest"), "text/html", "HEAD");
        URL latest;
        try {
            latest = page.getURL();
        } finally {
            page.disconnect();
        }
        String path = latest.getPath();
        if (!"github.com".equalsIgnoreCase(latest.getHost())
                || !path.matches("/linxlone666/[A-Za-z0-9_.-]+/releases/tag/v?[0-9.]+")) {
            throw new IOException("Unexpected public release URL");
        }
        String tag = path.substring(path.lastIndexOf('/') + 1);
        String version = tag.startsWith("v") ? tag.substring(1) : tag;
        if (!isNewer(version, installedVersion)) return null;

        String name = "Pocket-Overlay-" + version + ".apk";
        URL download = new URL(latest, path.replace("/releases/tag/", "/releases/download/") + "/" + name);
        if (!matchesAsset(download, tag, name)) throw new IOException("Unexpected update asset URL");
        HttpURLConnection asset = connect(download, "application/octet-stream", "HEAD");
        try {
            long size = asset.getContentLengthLong();
            if (size <= 0 || size > MAX_APK_BYTES) throw new IOException("Invalid update asset size");
            // The APK signature and package metadata still authenticate this download.
            return new Release(version, download, size, "");
        } finally {
            asset.disconnect();
        }
    }

    private static boolean matchesAsset(URL url, String tag, String name) {
        return "https".equals(url.getProtocol()) && "github.com".equalsIgnoreCase(url.getHost())
                && url.getPort() == -1 && url.getUserInfo() == null
                && url.getQuery() == null && url.getRef() == null
                && url.getPath().matches("/linxlone666/[A-Za-z0-9_.-]+/releases/download/"
                        + Pattern.quote(tag + "/" + name));
    }

    @SuppressWarnings("deprecation")
    static File download(File cache, PackageManager packages, PackageInfo installed, Release release)
            throws IOException, NoSuchAlgorithmException {
        File verified = UpdateApkProvider.verifiedApk(cache);
        Files.createDirectories(verified.toPath().getParent());
        File temporary = File.createTempFile("pocket-overlay-", ".apk", cache);
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            HttpURLConnection connection = connect(release.apk, "application/octet-stream");
            try (InputStream input = new DigestInputStream(connection.getInputStream(), sha256);
                 OutputStream output = Files.newOutputStream(temporary.toPath())) {
                if (copy(input, output, release.size) != release.size) {
                    throw new IOException("Incomplete update");
                }
            } finally {
                connection.disconnect();
            }

            String digest = String.format(Locale.ROOT, "sha256:%064x", new BigInteger(1, sha256.digest()));
            if (!release.digest.isEmpty() && !digest.equalsIgnoreCase(release.digest)) {
                throw new IOException("Update checksum mismatch");
            }
            PackageInfo update = packages.getPackageArchiveInfo(temporary.getPath(),
                    PackageManager.GET_SIGNING_CERTIFICATES);
            if (update == null || update.signingInfo == null || installed.signingInfo == null
                    || !installed.packageName.equals(update.packageName)
                    || !release.version.equals(update.versionName)
                    || update.getLongVersionCode() < installed.getLongVersionCode()
                    || !new HashSet<>(Arrays.asList(installed.signingInfo.getApkContentsSigners()))
                            .equals(new HashSet<>(Arrays.asList(update.signingInfo.getApkContentsSigners())))) {
                throw new IOException("Update package, version or signer did not match");
            }
            Files.move(temporary.toPath(), verified.toPath(),
                    StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return verified;
        } finally {
            Files.deleteIfExists(temporary.toPath());
        }
    }

    private static boolean isNewer(String candidate, String installed) throws IOException {
        if (installed == null || !installed.matches("[0-9]{1,9}\\.[0-9]{1,9}\\.[0-9]{1,9}")
                || !candidate.matches("[0-9]{1,9}\\.[0-9]{1,9}\\.[0-9]{1,9}")) {
            throw new IOException("Unrecognized release version");
        }
        String[] next = candidate.split("\\.");
        String[] current = installed.split("\\.");
        for (int i = 0; i < next.length; i++) {
            int order = Integer.compare(Integer.parseInt(next[i]), Integer.parseInt(current[i]));
            if (order != 0) return order > 0;
        }
        return false;
    }

    private static final class HttpStatusException extends IOException {
        final int status;

        HttpStatusException(int status) {
            super("GitHub returned " + status);
            this.status = status;
        }
    }

    private static HttpURLConnection connect(URL url, String accept) throws IOException {
        return connect(url, accept, "GET");
    }

    private static HttpURLConnection connect(URL url, String accept, String method) throws IOException {
        for (int redirects = 0; redirects <= 4; redirects++) {
            String host = url.getHost();
            if (!"https".equals(url.getProtocol()) || (!"api.github.com".equals(host)
                    && !"github.com".equals(host) && !"release-assets.githubusercontent.com".equals(host)
                    && !"objects.githubusercontent.com".equals(host))) {
                throw new IOException("Unexpected update server");
            }

            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod(method);
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("User-Agent", "Pocket-Overlay");
            connection.setRequestProperty("Accept", accept);
            try {
                int status = connection.getResponseCode();
                if (status == HttpURLConnection.HTTP_OK) return connection;
                if (status < 300 || status >= 400) throw new HttpStatusException(status);
                String location = connection.getHeaderField("Location");
                if (location == null) throw new IOException("Missing update redirect");
                url = new URL(url, location);
            } catch (IOException exception) {
                connection.disconnect();
                throw exception;
            }
            connection.disconnect();
        }
        throw new IOException("Too many update redirects");
    }

    private static long copy(InputStream input, OutputStream output, long limit) throws IOException {
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        int length;
        while ((length = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Update canceled");
            total += length;
            if (total > limit) throw new IOException("Update response exceeds the expected size");
            output.write(buffer, 0, length);
        }
        return total;
    }
}
