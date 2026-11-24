package org.mokai.coder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.net.ssl.HttpsURLConnection;

import org.json.JSONObject;

public final class GitHubClientTest {
    private static final String REVISION = "a".repeat(40);
    private static final String BASE_TREE = "b".repeat(40);
    private static final String BLOB = "c".repeat(40);
    private static final String NEW_TREE = "d".repeat(40);
    private static final String NEW_COMMIT = "e".repeat(40);
    private static final String TOKEN = "Bearer edit-token";
    private static final String REPO = "/repos/owner/project";
    private static final Deque<Response> responses = new ArrayDeque<>();
    private static String newBranch;

    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
            @Override protected HttpsURLConnection openConnection(URL url) {
                Response next = responses.remove();
                same("api.github.com", url.getHost());
                same(next.path.replace("{new-branch}", newBranch == null ? "" : newBranch.replace("/", "%2F")),
                        url.getFile());
                return new HttpsURLConnection(url) {
                    private final ByteArrayOutputStream request = new ByteArrayOutputStream();
                    @Override public ByteArrayOutputStream getOutputStream() { return request; }
                    @Override public int getResponseCode() throws IOException {
                        same(next.authorization, getRequestProperty("Authorization"));
                        if (next.checkBody != null) {
                            same("POST", getRequestMethod());
                            same("application/json; charset=utf-8", getRequestProperty("Content-Type"));
                            next.checkBody.accept(new JSONObject(request.toString(StandardCharsets.UTF_8)));
                        } else {
                            same("GET", getRequestMethod());
                        }
                        if (next.status == -1) throw new java.net.SocketTimeoutException();
                        return next.status;
                    }
                    @Override public InputStream getInputStream() {
                        return new ByteArrayInputStream(next.body.getBytes(StandardCharsets.UTF_8));
                    }
                    @Override public void disconnect() {}
                    @Override public boolean usingProxy() { return false; }
                    @Override public void connect() {}
                    @Override public String getCipherSuite() { return null; }
                    @Override public Certificate[] getLocalCertificates() { return null; }
                    @Override public Certificate[] getServerCertificates() { return null; }
                };
            }
        } : null);

        same("someone/project", GitHubClient.normalizeTarget(" someone/project "));
        same("Other-Owner/sample", GitHubClient.normalizeTarget("HTTPS://GitHub.com/Other-Owner/sample.git/"));
        same("Other-Owner/sample", GitHubClient.normalizeTarget("github.com/Other-Owner/sample/tree/main"));
        same("some_org", GitHubClient.normalizeTarget("https://github.com/some_org/"));
        try {
            GitHubClient.normalizeTarget("https://github.com.evil.example/someone/project");
            throw new AssertionError("Accepted a non-GitHub address");
        } catch (IllegalArgumentException expected) {
            // A lookalike hostname must not become an API target.
        }

        reply("/repos/another-owner/project", "Bearer demo-token", 404, "{}");
        repository("another-owner/project", "custom-default", null);
        GitHubClient.Repository publicRepo = GitHubClient.openRepository("another-owner/project", "", "demo-token");
        same("another-owner/project", publicRepo.name);
        same(REVISION, publicRepo.revision);
        same("", publicRepo.token);
        same(1, publicRepo.files.length());

        repository("team/private-repo", "release/mobile", "Bearer allowed-token");
        GitHubClient.Repository privateRepo = GitHubClient.openRepository("team/private-repo", "release/mobile", "allowed-token");
        same("allowed-token", privateRepo.token);

        reply("/repos/owner/project", null, 200, "{\"default_branch\":\"main\"}");
        reply("/repos/owner/project/commits/typo", null, 404, "{}");
        try {
            GitHubClient.openRepository("owner/project", "typo", "");
            throw new AssertionError("Accepted missing branch");
        } catch (IOException expected) {
            if (!expected.getMessage().contains("typo")) throw expected;
        }

        reply("/users/another-org", null, 200, "{\"type\":\"Organization\"}");
        reply("/orgs/another-org/repos?type=public&sort=full_name&direction=asc&per_page=30&page=2",
                null, 200, "[{\"full_name\":\"another-org/project\"}]");
        same("another-org/project", GitHubClient.publicRepositories("another-org", 2)
                .getJSONObject(0).getString("full_name"));
        testChatEdits();
        testAssistantProposals();
        if (!responses.isEmpty()) throw new AssertionError("Not all expected requests ran");
        System.out.println("GitHub browsing, assistant proposals, multi-file chat edits and publication recovery passed.");
    }

    private static void testChatEdits() throws Exception {
        same(true, GitHubEdit.isCommand("/تعديل\nملف: README.md"));
        same(true, GitHubEdit.isCommand("ادخل التعديلات على ملف README.md"));
        same(false, GitHubEdit.isCommand("كيف أعدّل ملف README؟"));
        Map<String, List<GitHubEdit.Replacement>> edits = GitHubEdit.parse("/تعديل\n"
                + "ملف: README.md\nابحث:\n```\nold\n```\nاستبدل:\n```\nnew\n```\n"
                + "ملف: run.js\nابحث:\n```js\nx\ny\n```\nاستبدل:\n```js\nx\nz\n```");
        same("x\r\nz\r\n", GitHubEdit.apply("run.js", "x\r\ny\r\n", edits.get("run.js")));
        try {
            GitHubEdit.apply("README.md", "old old", edits.get("README.md"));
            throw new AssertionError("Accepted ambiguous replacement");
        } catch (IOException expected) {
            if (!expected.getMessage().contains("يتكرر")) throw expected;
        }
        for (String unsafe : new String[]{"../README.md", "dir/.git/README.md", "dir\\README.md"}) {
            try {
                GitHubEdit.parse(GitHubEdit.EXAMPLE.replace("README.md", unsafe));
                throw new AssertionError("Accepted unsafe path " + unsafe);
            } catch (IOException expected) {
                if (!expected.getMessage().contains("مسار")) throw expected;
            }
        }

        GitHubClient.EditPlan plan = preview(edits);
        if (!plan.preview.contains("- old\n+ new") || !plan.preview.contains("- y\n+ x\n+ z")) {
            throw new AssertionError("Preview should show exact replacements");
        }
        reply(REPO + "/git/ref/heads/main", TOKEN, 200, ref(NEW_COMMIT));
        try {
            GitHubClient.publishEdits(plan, "edit-token");
            throw new AssertionError("Published a stale preview");
        } catch (IOException expected) {
            if (!expected.getMessage().contains("منذ المعاينة")) throw expected;
        }
        reply(REPO + "/git/ref/heads/main", TOKEN, 200, ref(REVISION));
        post("/git/trees", 201, object(NEW_TREE), body -> {
            same(BASE_TREE, body.getString("base_tree"));
            same(2, body.getJSONArray("tree").length());
            JSONObject first = body.getJSONArray("tree").getJSONObject(0);
            same("README.md", first.getString("path"));
            same("new\n", first.getString("content"));
            JSONObject second = body.getJSONArray("tree").getJSONObject(1);
            same("100755", second.getString("mode"));
            same("x\r\nz\r\n", second.getString("content"));
        });
        post("/git/commits", 201, object(NEW_COMMIT), body -> {
            same(NEW_TREE, body.getString("tree"));
            same(REVISION, body.getJSONArray("parents").getString(0));
        });
        post("/git/refs", -1, "{}", body -> {
            newBranch = body.getString("ref").replaceFirst("^refs/heads/", "");
            if (!newBranch.startsWith("mokai/chat-")) throw new AssertionError("Unexpected write destination");
            same(NEW_COMMIT, body.getString("sha"));
        });
        try {
            GitHubClient.publishEdits(plan, "edit-token");
            throw new AssertionError("Accepted ambiguous network result");
        } catch (java.net.SocketTimeoutException expected) {
            // The same branch is discovered on retry.
        }
        reply(REPO + "/git/ref/heads/{new-branch}", TOKEN, 200, ref(NEW_COMMIT));
        post("/pulls", 201, "{\"number\":17}", body -> {
            same("main", body.getString("base"));
            same(newBranch, body.getString("head"));
            same(true, body.getBoolean("draft"));
        });
        GitHubClient.Publication result = GitHubClient.publishEdits(plan, "edit-token");
        same(true, result.hasPullRequest);
        same("https://github.com/owner/project/pull/17", result.url);
    }

    private static void testAssistantProposals() throws Exception {
        String order = "/مهمة اشرح التشغيل في README.md. ثم عدّل `run.js`";
        same(true, GitHubTask.isCommand(order));
        same(List.of("README.md", "run.js"), GitHubTask.mentionedPaths(GitHubTask.task(order)));
        same(List.of("README.md", "run.js"), GitHubTask.selectedPaths("README.md\nrun.js\nREADME.md"));
        same(false, GitHubTask.isCommand("/مهمةأخرى"));

        GitHubClient.EditSnapshot snapshot = snapshot(List.of("README.md", "run.js"));
        Map<String, String> contents = snapshot.contents();
        JSONObject message = GitHubTask.messages(GitHubTask.task(order), contents).getJSONObject(0);
        same("user", message.getString("role"));
        String context = message.getString("content");
        if (!context.contains("README.md") || !context.contains("old\\n") || context.contains("edit-token")) {
            throw new AssertionError("Prompt should contain verified files and no GitHub credential");
        }
        String modelAnswer = "{\"changes\":[{\"path\":\"README.md\",\"before\":\"old\",\"after\":\"new\"},"
                + "{\"path\":\"run.js\",\"before\":\"y\",\"after\":\"z\"}]}";
        GitHubClient.EditPlan plan = snapshot.plan(GitHubTask.proposal("```json\n" + modelAnswer + "\n```", contents.keySet()));
        if (!plan.preview.contains("- old\n+ new") || !plan.preview.contains("- y\n+ z")) {
            throw new AssertionError("Assistant proposals should use the verified working copy");
        }
        for (String invalid : List.of(modelAnswer.replace("run.js", "other.js"), modelAnswer + "{}", "{\"changes\":[]}")) {
            try {
                GitHubTask.proposal(invalid, contents.keySet());
                throw new AssertionError("Accepted invalid assistant proposal");
            } catch (IOException expected) {
                // Malformed proposals, edits outside the selected paths and empty proposals stop here.
            }
        }
        try {
            snapshot.plan(GitHubTask.proposal(modelAnswer.replace("old", "imaginary"), contents.keySet()));
            throw new AssertionError("Accepted a replacement that is missing from the verified file");
        } catch (IOException expected) {
            if (!expected.getMessage().contains("النص القديم")) throw expected;
        }
        try {
            GitHubTask.messages("task", Map.of("README.md", "x".repeat(24_000)));
            throw new AssertionError("Accepted oversized provider context");
        } catch (IOException expected) {
            if (!expected.getMessage().contains("السياق")) throw expected;
        }
    }

    private static GitHubClient.EditPlan preview(Map<String, List<GitHubEdit.Replacement>> edits) throws Exception {
        queueSnapshot();
        return GitHubClient.previewEdits("owner/project", "", "edit-token", edits);
    }

    private static GitHubClient.EditSnapshot snapshot(List<String> paths) throws Exception {
        queueSnapshot();
        return GitHubClient.readEditSnapshot("owner/project", "", "edit-token", paths);
    }

    private static void queueSnapshot() {
        reply(REPO, TOKEN, 200, "{\"default_branch\":\"main\"}");
        reply(REPO + "/git/ref/heads/main", TOKEN, 200, ref(REVISION));
        reply(REPO + "/git/commits/" + REVISION, TOKEN, 200, "{\"tree\":" + object(BASE_TREE) + "}");
        String otherBlob = "f".repeat(40);
        reply(REPO + "/git/trees/" + BASE_TREE, TOKEN, 200, new JSONObject()
                .put("tree", new org.json.JSONArray()
                        .put(new JSONObject(object(BLOB)).put("path", "README.md").put("mode", "100644").put("type", "blob"))
                        .put(new JSONObject(object(otherBlob)).put("path", "run.js").put("mode", "100755").put("type", "blob"))).toString());
        blob(BLOB, "old\n");
        blob(otherBlob, "x\r\ny\r\n");
    }

    private static void blob(String sha, String text) {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        reply(REPO + "/git/blobs/" + sha, TOKEN, 200, new JSONObject(object(sha))
                .put("size", bytes.length).put("encoding", "base64")
                .put("content", Base64.getEncoder().encodeToString(bytes)).toString());
    }

    private static String object(String sha) { return "{\"sha\":\"" + sha + "\"}"; }
    private static String ref(String sha) { return "{\"object\":" + object(sha) + "}"; }

    private static void repository(String name, String ref, String authorization) {
        reply("/repos/" + name, authorization, 200,
                "{\"full_name\":\"" + name + "\",\"default_branch\":\"custom-default\"}");
        reply("/repos/" + name + "/commits/" + ref.replace("/", "%2F"), authorization, 200,
                "{\"sha\":\"" + REVISION + "\"}");
        reply("/repos/" + name + "/contents?ref=" + REVISION, authorization, 200, "[{\"name\":\"README.md\"}]");
    }

    private static void reply(String path, String authorization, int status, String body) {
        responses.add(new Response(path, authorization, status, body, null));
    }

    private static void post(String path, int status, String body, Consumer<JSONObject> checkBody) {
        responses.add(new Response(REPO + path, TOKEN, status, body, checkBody));
    }

    private static void same(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) {
            throw new AssertionError("Expected " + expected + ", got " + actual);
        }
    }

    private static final class Response {
        final String path;
        final String authorization;
        final int status;
        final String body;
        final Consumer<JSONObject> checkBody;

        Response(String path, String authorization, int status, String body, Consumer<JSONObject> checkBody) {
            this.path = path;
            this.authorization = authorization;
            this.status = status;
            this.body = body;
            this.checkBody = checkBody;
        }
    }
}
