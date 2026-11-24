package org.mokai.coder;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLStreamHandler;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.ArrayDeque;
import java.util.Deque;
import javax.net.ssl.HttpsURLConnection;

public final class GitHubClientTest {
    private static final String REVISION = "a".repeat(40);
    private static final Deque<Response> responses = new ArrayDeque<>();

    public static void main(String[] args) throws Exception {
        URL.setURLStreamHandlerFactory(protocol -> "https".equals(protocol) ? new URLStreamHandler() {
            @Override protected HttpsURLConnection openConnection(URL url) {
                Response next = responses.remove();
                same("api.github.com", url.getHost());
                same(next.path, url.getFile());
                return new HttpsURLConnection(url) {
                    @Override public int getResponseCode() {
                        same(next.authorization, getRequestProperty("Authorization"));
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
        if (!responses.isEmpty()) throw new AssertionError("Not all expected requests ran");
        System.out.println("GitHub links, account listing, default and explicit branches, and public fallback passed.");
    }

    private static void repository(String name, String ref, String authorization) {
        reply("/repos/" + name, authorization, 200,
                "{\"full_name\":\"" + name + "\",\"default_branch\":\"custom-default\"}");
        reply("/repos/" + name + "/commits/" + ref.replace("/", "%2F"), authorization, 200,
                "{\"sha\":\"" + REVISION + "\"}");
        reply("/repos/" + name + "/contents?ref=" + REVISION, authorization, 200, "[{\"name\":\"README.md\"}]");
    }

    private static void reply(String path, String authorization, int status, String body) {
        responses.add(new Response(path, authorization, status, body));
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

        Response(String path, String authorization, int status, String body) {
            this.path = path;
            this.authorization = authorization;
            this.status = status;
            this.body = body;
        }
    }
}
