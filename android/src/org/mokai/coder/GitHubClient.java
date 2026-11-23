package org.mokai.coder;

import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;

final class GitHubClient {
    private static final String API = "https://api.github.com";
    private static final String LOGIN = "https://github.com/login/";
    private static final int MAX_FILE = 24_000;

    static JSONObject deviceCode(String clientId) throws Exception {
        return (JSONObject) request(LOGIN + "device/code", "", new JSONObject().put("client_id", clientId).put("scope", "offline_access"));
    }

    static JSONObject deviceToken(String clientId, String deviceCode) throws Exception {
        JSONObject body = new JSONObject().put("client_id", clientId).put("device_code", deviceCode)
                .put("grant_type", "urn:ietf:params:oauth:grant-type:device_code").put("scope", "offline_access");
        return (JSONObject) request(LOGIN + "oauth/access_token", "", body);
    }

    static void updateToken(JSONObject connection, JSONObject token) throws Exception {
        if (token.optString("access_token").isEmpty()) throw new IOException("تعذّر الحصول على توكن GitHub.");
        connection.put("token", token.getString("access_token"))
                .put("refresh", token.optString("refresh_token"))
                .put("expires", token.optLong("expires_in", 28_800) * 1000 + System.currentTimeMillis());
    }

    static String accessToken(JSONObject connection, ConnectionStore store) throws Exception {
        String token = connection.optString("token");
        long expires = connection.optLong("expires");
        if (expires == 0 || System.currentTimeMillis() + 60_000 < expires) return token;
        if (connection.optString("refresh").isEmpty()) throw new IOException("أعد تفويض GitHub من إعدادات الربط.");
        JSONObject body = new JSONObject().put("client_id", connection.getString("client"))
                .put("grant_type", "refresh_token").put("refresh_token", connection.getString("refresh"));
        JSONObject response = (JSONObject) request(LOGIN + "oauth/access_token", "", body);
        if (response.has("error")) throw new IOException("انتهى تفويض GitHub. أعد الربط من الإعدادات.");
        updateToken(connection, response);
        store.save("github", connection);
        return connection.getString("token");
    }

    static String revision(String repository, String branch, String token) throws Exception {
        String ref = branch.isEmpty() ? "HEAD" : branch;
        JSONObject result = (JSONObject) request(repoUrl(repository) + "/commits/" + Uri.encode(ref), token, null);
        String sha = result.getString("sha");
        if (!sha.matches("[a-f0-9]{40}")) throw new IOException("تعذّر تحديد إصدار المستودع.");
        return sha;
    }

    static JSONArray directory(String repository, String sha, String path, String token) throws Exception {
        Object result = request(contentsUrl(repository, sha, path), token, null);
        if (!(result instanceof JSONArray)) throw new IOException("لم نجد مجلدًا في هذا المسار.");
        return (JSONArray) result;
    }

    static String file(String repository, String sha, String path, String blob, String token) throws Exception {
        if (!shareable(path) || !blob.matches("[a-f0-9]{40}")) throw new IOException("هذا النوع من الملفات غير مدعوم.");
        JSONObject result = (JSONObject) request(repoUrl(repository) + "/git/blobs/" + blob, token, null);
        if (!blob.equals(result.optString("sha")) || !"base64".equals(result.optString("encoding"))
                || result.optInt("size", MAX_FILE + 1) > MAX_FILE) {
            throw new IOException("اختر ملفًا نصيًا بحجم 24 كيلوبايت أو أقل.");
        }
        byte[] bytes = Base64.decode(result.getString("content"), Base64.DEFAULT);
        String value = new String(bytes, StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE || value.indexOf('\0') >= 0 || value.indexOf('\uFFFD') >= 0) {
            throw new IOException("اختر ملفًا نصيًا صالحًا بترميز UTF-8.");
        }
        return value;
    }

    static boolean shareable(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        String file = lower.substring(lower.lastIndexOf('/') + 1);
        if (file.startsWith(".") || file.contains("secret") || file.contains("credential")
                || file.contains("password") || file.contains("token")
                || file.startsWith("id_rsa") || file.startsWith("id_ed25519")) return false;
        return file.matches(".+\\.(java|kt|ts|tsx|js|jsx|py|go|rs|rb|cs|c|cpp|h|hpp|swift|dart|md|html|css|sql)")
                || "readme".equals(file);
    }

    private static String repoUrl(String repository) throws IOException {
        if (!repository.matches("[A-Za-z0-9-]+/[A-Za-z0-9_.-]+")
                || repository.endsWith("/.") || repository.endsWith("/..")) {
            throw new IOException("اكتب المستودع بصيغة المالك/اسم-المستودع.");
        }
        return API + "/repos/" + repository;
    }

    private static String contentsUrl(String repository, String sha, String path) throws IOException {
        StringBuilder url = new StringBuilder(repoUrl(repository)).append("/contents");
        if (!path.isEmpty()) {
            for (String part : path.split("/", -1)) {
                if (part.isEmpty() || ".".equals(part) || "..".equals(part)) {
                    throw new IOException("مسار GitHub غير صالح.");
                }
                url.append('/').append(Uri.encode(part));
            }
        } else {
            url.append('/');
        }
        return url.append("?ref=").append(Uri.encode(sha)).toString();
    }

    private static Object request(String address, String token, JSONObject body) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) new URL(address).openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("Accept", address.startsWith(API)
                    ? "application/vnd.github+json" : "application/json");
            if (!token.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                connection.setDoOutput(true);
                StringBuilder form = new StringBuilder();
                java.util.Iterator<String> names = body.keys();
                while (names.hasNext()) {
                    String name = names.next();
                    if (form.length() != 0) form.append('&');
                    form.append(Uri.encode(name)).append('=').append(Uri.encode(body.getString(name)));
                }
                byte[] bytes = form.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }
            int code = connection.getResponseCode();
            if (code == 401 || code == 403 || code == 404) {
                throw new IOException("تحقق من المستودع والفرع وصلاحية القراءة في GitHub (HTTP " + code + ").");
            }
            if (code < 200 || code >= 300) throw new IOException("تعذّر الاتصال بـ GitHub (HTTP " + code + ").");
            try (InputStream input = connection.getInputStream();
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                for (int count; (count = input.read(buffer)) != -1;) {
                    if (output.size() + count > 2_000_000) throw new IOException("نتيجة GitHub كبيرة جدًا.");
                    output.write(buffer, 0, count);
                }
                return new JSONTokener(output.toString("UTF-8")).nextValue();
            }
        } finally {
            connection.disconnect();
        }
    }
}
