package org.mokai.coder;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;

final class GitHubClient {
    private static final String API = "https://api.github.com";
    private static final String LOGIN = "https://github.com/login/";
    private static final int MAX_FILE = 24_000;
    static final int REPOSITORIES_PER_PAGE = 30;
    private static final String OWNER = "[A-Za-z0-9][A-Za-z0-9_-]*";
    private static final String REPOSITORY = OWNER + "/[A-Za-z0-9_.-]+";

    static final class Repository {
        final String name;
        final String revision;
        final String token;
        final JSONArray files;

        Repository(String name, String revision, String token, JSONArray files) {
            this.name = name;
            this.revision = revision;
            this.token = token;
            this.files = files;
        }
    }

    private static final class GitHubError extends IOException {
        private static final long serialVersionUID = 1L;
        final int status;

        GitHubError(int status, String message) {
            super(message);
            this.status = status;
        }

        boolean canTryPublicAccess() {
            return status == 401 || status == 403 || status == 404;
        }
    }

    static String normalizeTarget(String input) {
        String target = input.trim();
        if (target.regionMatches(true, 0, "github.com/", 0, 11)) {
            target = "https://" + target;
        }
        if (target.matches("(?i)^https?://.*")) {
            URI url = URI.create(target);
            if (!"github.com".equalsIgnoreCase(url.getHost()) || url.getRawUserInfo() != null
                    || url.getPort() != -1) {
                throw new IllegalArgumentException("Expected a GitHub URL");
            }
            target = url.getPath().replaceFirst("^/", "");
            String[] parts = target.split("/", -1);
            if (parts.length > 2 && !parts[2].isEmpty()) target = parts[0] + "/" + parts[1];
        }
        target = target.replaceFirst("/+$", "");
        if (target.contains("/")) target = target.replaceFirst("(?i)\\.git$", "");
        if (!target.matches(OWNER) && !validRepository(target)) {
            throw new IllegalArgumentException("Expected a GitHub account or repository");
        }
        return target;
    }

    private static boolean validRepository(String name) {
        return name.matches(REPOSITORY) && !name.endsWith("/.") && !name.endsWith("/..");
    }

    static JSONArray publicRepositories(String owner, int page) throws Exception {
        if (!owner.matches(OWNER) || page < 1) throw new IllegalArgumentException("Invalid GitHub account");
        JSONObject account = (JSONObject) request(API + "/users/" + encode(owner), "", null);
        boolean organization = "Organization".equals(account.optString("type"));
        String endpoint = organization ? "/orgs/" : "/users/";
        String query = organization ? "?type=public" : "?type=owner";
        return (JSONArray) request(API + endpoint + encode(owner) + "/repos" + query
                + "&sort=full_name&direction=asc&per_page=" + REPOSITORIES_PER_PAGE + "&page=" + page, "", null);
    }

    static Repository openRepository(String name, String branch, String token) throws Exception {
        try {
            return resolve(name, branch, token);
        } catch (GitHubError first) {
            if (token.isEmpty() || !first.canTryPublicAccess()) throw first;
            try {
                return resolve(name, branch, "");
            } catch (GitHubError anonymous) {
                if (anonymous.status == 404) throw first;
                throw anonymous;
            }
        }
    }

    private static Repository resolve(String name, String branch, String token) throws Exception {
        JSONObject details;
        try {
            details = (JSONObject) request(repoUrl(name), token, null);
        } catch (GitHubError error) {
            if (error.status == 404) {
                throw new GitHubError(404, "المستودع «" + name + "» غير متاح. تحقّق من الاسم وصلاحية القراءة.");
            }
            throw error;
        }
        String canonical = details.optString("full_name", name);
        String ref = branch.isEmpty() ? details.optString("default_branch") : branch;
        if (ref.isEmpty()) throw new IOException("لا يوجد فرع افتراضي في هذا المستودع.");
        String sha = revision(canonical, ref, token);
        return new Repository(canonical, sha, token, directory(canonical, sha, "", token));
    }

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

    static String accessToken(JSONObject connection) throws Exception {
        String token = connection.optString("token");
        long expires = connection.optLong("expires");
        if (expires == 0 || System.currentTimeMillis() + 60_000 < expires) return token;
        if (connection.optString("refresh").isEmpty()) throw new IOException("أعد تفويض GitHub من إعدادات الربط.");
        JSONObject body = new JSONObject().put("client_id", connection.getString("client"))
                .put("grant_type", "refresh_token").put("refresh_token", connection.getString("refresh"));
        JSONObject response = (JSONObject) request(LOGIN + "oauth/access_token", "", body);
        if (response.has("error")) throw new IOException("انتهى تفويض GitHub. أعد الربط من الإعدادات.");
        updateToken(connection, response);
        return connection.getString("token");
    }

    private static String revision(String repository, String ref, String token) throws Exception {
        JSONObject result;
        try {
            result = (JSONObject) request(repoUrl(repository) + "/commits/" + encode(ref), token, null);
        } catch (GitHubError e) {
            if (e.status == 404) throw new GitHubError(404, "الفرع «" + ref + "» غير متاح في هذا المستودع.");
            if (e.status == 409) throw new IOException("هذا المستودع فارغ. أضف إليه ملفًا على GitHub أولًا.");
            throw e;
        }
        String sha = result.getString("sha");
        if (!sha.matches("[a-f0-9]{40}")) throw new IOException("تعذّر تحديد إصدار المستودع.");
        return sha;
    }

    static JSONArray directory(String repository, String sha, String path, String token) throws Exception {
        Object result = request(contentsUrl(repository, sha, path), token, null);
        if (!(result instanceof JSONArray)) throw new IOException("لم نجد مجلدًا في هذا المسار.");
        return (JSONArray) result;
    }

    static String file(String repository, String path, String blob, String token) throws Exception {
        if (!shareable(path) || !blob.matches("[a-f0-9]{40}")) throw new IOException("هذا النوع من الملفات غير مدعوم.");
        JSONObject result = (JSONObject) request(repoUrl(repository) + "/git/blobs/" + blob, token, null);
        if (!blob.equals(result.optString("sha")) || !"base64".equals(result.optString("encoding"))
                || result.optInt("size", MAX_FILE + 1) > MAX_FILE) {
            throw new IOException("اختر ملفًا نصيًا بحجم 24 كيلوبايت أو أقل.");
        }
        byte[] bytes = Base64.getMimeDecoder().decode(result.getString("content"));
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
        if (!validRepository(repository)) {
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
                url.append('/').append(encode(part));
            }
        }
        return url.append("?ref=").append(encode(sha)).toString();
    }

    private static String encode(String value) throws IOException {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
    }

    private static Object request(String address, String token, JSONObject body) throws Exception {
        return request(address, token, body, 0);
    }

    private static Object request(String address, String token, JSONObject body, int redirects) throws Exception {
        HttpsURLConnection connection = (HttpsURLConnection) URI.create(address).toURL().openConnection();
        try {
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(12_000);
            connection.setReadTimeout(20_000);
            connection.setRequestProperty("User-Agent", "MokAiCoder-Android");
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
                    form.append(encode(name)).append('=').append(encode(body.getString(name)));
                }
                byte[] bytes = form.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(bytes);
                }
            }
            int code = connection.getResponseCode();
            String location = connection.getHeaderField("Location");
            if (body == null && address.startsWith(API + "/") && redirects < 3
                    && (code == 301 || code == 302 || code == 307 || code == 308) && location != null) {
                URI redirect = URI.create(address).resolve(location);
                if ("https".equalsIgnoreCase(redirect.getScheme()) && "api.github.com".equalsIgnoreCase(redirect.getHost())
                        && redirect.getRawUserInfo() == null && (redirect.getPort() == -1 || redirect.getPort() == 443)) {
                    connection.disconnect();
                    return request(redirect.toString(), token, null, redirects + 1);
                }
            }
            if (code == 404) throw new GitHubError(code, "المستودع أو الحساب غير متاح. تحقّق من الاسم وصلاحية القراءة.");
            if (code == 401) throw new GitHubError(code, "رفض GitHub رمز الوصول. أعد التفويض إذا كان المستودع خاصًا.");
            if (code == 429 || (code == 403 && "0".equals(connection.getHeaderField("X-RateLimit-Remaining")))) {
                throw new GitHubError(code, "بلغت حد طلبات GitHub. جرّب لاحقًا أو استخدم رمز قراءة صالحًا.");
            }
            if (code == 403) throw new GitHubError(code, "رفض GitHub الوصول. تحقّق من صلاحية القراءة.");
            if (code < 200 || code >= 300) throw new GitHubError(code, "تعذّر الاتصال بـ GitHub (HTTP " + code + ").");
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
