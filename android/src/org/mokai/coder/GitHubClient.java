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
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.net.ssl.HttpsURLConnection;

final class GitHubClient {
    private static final String API = "https://api.github.com";
    private static final String LOGIN = "https://github.com/login/";
    static final int MAX_FILE = 24_000;
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

    static EditPlan previewEdits(String repository, String branch, String token,
                                 Map<String, List<GitHubEdit.Replacement>> edits) throws Exception {
        String url = repoUrl(repository);
        JSONObject details = (JSONObject) request(url, token, null);
        String base = branch.isEmpty() ? details.getString("default_branch") : branch;
        String revision = branchRevision(url, base, token);
        JSONObject commit = (JSONObject) request(url + "/git/commits/" + revision, token, null);
        String baseTree = sha(commit.getJSONObject("tree"));
        Map<String, JSONArray> directories = new HashMap<>();
        JSONArray changes = new JSONArray();
        StringBuilder preview = new StringBuilder();
        for (Map.Entry<String, List<GitHubEdit.Replacement>> entry : edits.entrySet()) {
            String path = entry.getKey();
            JSONObject item = treeFile(url, baseTree, path, token, directories);
            String original = file(repository, path, sha(item), token);
            String content = GitHubEdit.apply(path, original, entry.getValue());
            changes.put(new JSONObject().put("path", path).put("type", "blob")
                    .put("mode", item.getString("mode")).put("content", content));
            preview.append('\n').append(path).append('\n');
            for (GitHubEdit.Replacement operation : entry.getValue()) {
                preview.append("- ").append(operation.before.replace("\n", "\n- ")).append('\n');
                preview.append("+ ").append(operation.after.replace("\n", "\n+ ")).append('\n');
            }
        }
        return new EditPlan(repository, base, revision, baseTree, changes, preview.toString());
    }

    private static JSONObject treeFile(String url, String tree, String path, String token,
                                       Map<String, JSONArray> directories) throws Exception {
        String[] parts = path.split("/");
        for (int depth = 0; depth < parts.length; depth++) {
            JSONArray entries = directories.get(tree);
            if (entries == null) {
                JSONObject response = (JSONObject) request(url + "/git/trees/" + tree, token, null);
                if (response.optBoolean("truncated")) throw new IOException("يتعذّر التحقق من شجرة الملفات كاملة.");
                entries = response.getJSONArray("tree");
                directories.put(tree, entries);
            }
            JSONObject match = null;
            for (int i = 0; i < entries.length(); i++) {
                JSONObject item = entries.getJSONObject(i);
                if (parts[depth].equals(item.optString("path"))) {
                    match = item;
                    break;
                }
            }
            if (match == null) break;
            if (depth == parts.length - 1) {
                String mode = match.optString("mode");
                if ("blob".equals(match.optString("type")) && ("100644".equals(mode) || "100755".equals(mode))) {
                    return match;
                }
            }
            if (!"tree".equals(match.optString("type"))) break;
            tree = sha(match);
        }
        throw new IOException("لم نجد ملفًا عاديًا في المسار «" + path + "».");
    }

    private static String branchRevision(String url, String branch, String token) throws Exception {
        JSONObject ref = (JSONObject) request(url + "/git/ref/heads/" + encode(branch), token, null);
        return sha(ref.getJSONObject("object"));
    }

    private static String sha(JSONObject object) throws Exception {
        String value = object.getString("sha");
        if (!value.matches("[a-f0-9]{40}")) throw new IOException("تعذّر التحقق من إصدار GitHub.");
        return value;
    }

    static Publication publishEdits(EditPlan plan, String token) throws Exception {
        if (token.isEmpty()) throw new IOException("أضف رمز GitHub بصلاحية الكتابة لهذا المستودع.");
        String url = repoUrl(plan.repository);
        try {
            if (!plan.branchCreated) {
                String published = plan.commit == null ? null : findBranch(url, plan.head, token);
                if (published != null && !published.equals(plan.commit)) {
                    throw new IOException("اسم فرع التعديل مستخدم بالفعل. جهّز معاينة جديدة.");
                }
                if (published == null) {
                    if (!plan.revision.equals(branchRevision(url, plan.base, token))) {
                        throw new IOException("تغيّر الفرع منذ المعاينة. أرسل الأمر مجددًا لمراجعة النسخة الجديدة.");
                    }
                    if (plan.commit == null) {
                        JSONObject tree = (JSONObject) request(url + "/git/trees", token,
                                new JSONObject().put("base_tree", plan.baseTree).put("tree", plan.changes));
                        JSONObject commit = (JSONObject) request(url + "/git/commits", token, new JSONObject()
                                .put("message", "Apply reviewed MokAiCoder chat edits").put("tree", sha(tree))
                                .put("parents", new JSONArray().put(plan.revision)));
                        plan.commit = sha(commit);
                    }
                    request(url + "/git/refs", token, new JSONObject().put("ref", "refs/heads/" + plan.head)
                            .put("sha", plan.commit));
                }
                plan.branchCreated = true;
            }
        } catch (GitHubError failure) {
            if (failure.status == 403 || failure.status == 404) {
                throw new IOException("تعذّر رفع الفرع. تحقّق من إعدادات المستودع وصلاحية Contents: Read and write.");
            }
            throw failure;
        }
        String web = "https://github.com/" + plan.repository;
        String compare = web + "/compare/" + encode(plan.base) + "..." + encode(plan.head) + "?expand=1";
        try {
            JSONObject pull = (JSONObject) request(url + "/pulls", token, new JSONObject()
                    .put("title", "Apply MokAiCoder chat edits").put("base", plan.base)
                    .put("head", plan.head).put("draft", true));
            return new Publication(web + "/pull/" + pull.getInt("number"), true);
        } catch (Exception rejected) {
            return new Publication(compare, false);
        }
    }

    private static String findBranch(String url, String branch, String token) throws Exception {
        try {
            return branchRevision(url, branch, token);
        } catch (GitHubError failure) {
            if (failure.status == 404) return null;
            throw failure;
        }
    }

    static final class EditPlan {
        final String repository;
        final String base;
        final String revision;
        final String preview;
        private final String baseTree;
        private final JSONArray changes;
        private final String head = "mokai/chat-" + UUID.randomUUID();
        private String commit;
        private boolean branchCreated;

        EditPlan(String repository, String base, String revision, String baseTree, JSONArray changes, String preview) {
            this.repository = repository;
            this.base = base;
            this.revision = revision;
            this.baseTree = baseTree;
            this.changes = changes;
            this.preview = preview;
        }
    }

    static final class Publication {
        final String url;
        final boolean hasPullRequest;

        Publication(String url, boolean hasPullRequest) {
            this.url = url;
            this.hasPullRequest = hasPullRequest;
        }
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
                connection.setDoOutput(true);
                String payload;
                if (address.startsWith(API + "/")) {
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                    payload = body.toString();
                } else {
                    connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    StringBuilder form = new StringBuilder();
                    java.util.Iterator<String> names = body.keys();
                    while (names.hasNext()) {
                        String name = names.next();
                        if (form.length() != 0) form.append('&');
                        form.append(encode(name)).append('=').append(encode(body.getString(name)));
                    }
                    payload = form.toString();
                }
                byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
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
