package org.mokai.coder;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import javax.net.ssl.HttpsURLConnection;

final class ChatClient {
    private static final URI OPENROUTER = URI.create("https://openrouter.ai/api/v1/");
    private static final URI XAI = URI.create("https://api.x.ai/v1/");
    private static final int RESPONSE_LIMIT = 32_000;

    static final class Settings {
        final boolean server;
        final URI base;
        final String secret;
        final String provider;
        final String model;
        final String reviewer;
        final String reviewModel;

        private Settings(boolean server, URI base, String secret, String provider, String model,
                         String reviewer, String reviewModel) {
            this.server = server;
            this.base = base;
            this.secret = secret;
            this.provider = provider;
            this.model = model;
            this.reviewer = reviewer;
            this.reviewModel = reviewModel;
        }

        static Settings free(String secret) {
            return new Settings(false, OPENROUTER, secret, "openrouter", "openrouter/free", "", "");
        }

        static Settings grok(String secret, String model) {
            return new Settings(false, XAI, secret, "grok", model.trim(), "", "");
        }

        boolean directGrok() {
            return !server && "grok".equals(provider);
        }

        static Settings server(String address, String secret, String provider, String model,
                               String reviewer, String reviewModel) {
            URI parsed = URI.create(address.trim());
            if (!"https".equalsIgnoreCase(parsed.getScheme()) || parsed.getHost() == null
                    || parsed.getUserInfo() != null || parsed.getQuery() != null
                    || parsed.getFragment() != null) {
                throw new IllegalArgumentException("أدخل رابط HTTPS صالحًا لخادم MokAiCoder.");
            }
            if (model.trim().isEmpty() && !"grok".equals(provider)) {
                throw new IllegalArgumentException("أدخل معرّف نموذج الخادم.");
            }
            if (!reviewer.isEmpty() && (reviewer.equals(provider) || reviewModel.trim().isEmpty())) {
                throw new IllegalArgumentException("اختر مزوّد مراجعة مختلفًا وأدخل معرّف نموذج المراجعة.");
            }
            String base = parsed.toString();
            return new Settings(true, URI.create(base.endsWith("/") ? base : base + "/"),
                    secret, provider, model.trim(), reviewer, reviewModel.trim());
        }

        boolean paid() {
            return directGrok() || (server && (mayCharge(provider) || mayCharge(reviewer)));
        }

        boolean collaborates() {
            return server && !reviewer.isEmpty();
        }

        private boolean mayCharge(String id) {
            return "paid".equals(id) || "grok".equals(id);
        }
    }

    interface Listener {
        void onText(String text);
    }

    static final class Call {
        private volatile HttpsURLConnection connection;
        private volatile boolean cancelled;

        void cancel() {
            cancelled = true;
            HttpsURLConnection active = connection;
            if (active != null) active.disconnect();
        }

        boolean isCancelled() {
            return cancelled;
        }

        String chat(Settings settings, JSONArray messages, Listener listener)
                throws IOException, JSONException {
            JSONObject request = new JSONObject();
            request.put("model", settings.model.isEmpty() ? firstGrokModel(settings) : settings.model);
            if (settings.server) {
                request.put("provider", settings.provider);
                request.put("messages", messages);
                if (settings.paid()) request.put("allowPaid", true);
                if (settings.collaborates()) {
                    request.put("reviewer", new JSONObject().put("provider", settings.reviewer)
                            .put("model", settings.reviewModel));
                    request.put("shareReview", true);
                }
            } else {
                JSONArray prompt = new JSONArray();
                prompt.put(new JSONObject().put("role", "system").put("content",
                        "أنت مساعد MokAiCoder البرمجي. أجب بالعربية ما لم يُطلب غير ذلك."));
                for (int index = 0; index < messages.length(); index++) {
                    prompt.put(messages.getJSONObject(index));
                }
                request.put("messages", prompt);
                request.put("stream", true);
                request.put("max_tokens", 1536);
            }

            HttpsURLConnection active = connect(settings,
                    settings.server ? "api/ai/chat" : "chat/completions", request);
            try {
                verifyStatus(active, settings);
                try (BufferedReader reader = reader(active)) {
                    return readStream(reader, settings.server, listener);
                }
            } finally {
                active.disconnect();
                connection = null;
            }
        }

        JSONObject scan(Settings settings, String branch) throws IOException, JSONException {
            JSONObject request = new JSONObject();
            if (!branch.isEmpty()) request.put("branch", branch);
            HttpsURLConnection active = connect(settings, "api/project/scan", request);
            try {
                verifyStatus(active, settings);
                try (BufferedReader reader = reader(active)) {
                    StringBuilder result = new StringBuilder();
                    char[] chunk = new char[8192];
                    int count;
                    while ((count = reader.read(chunk)) >= 0) {
                        if (cancelled) throw new IOException("أوقفت الطلب.");
                        if (result.length() + count > 4_000_000) {
                            throw new IOException("تجاوزت نتيجة الفحص الحجم المدعوم على الهاتف.");
                        }
                        result.append(chunk, 0, count);
                    }
                    return new JSONObject(result.toString());
                }
            } finally {
                active.disconnect();
                connection = null;
            }
        }

        private String firstGrokModel(Settings settings) throws IOException, JSONException {
            HttpsURLConnection active = connect(settings, settings.server ? "api/ai/providers/grok/models" : "models", null);
            try {
                verifyStatus(active, settings);
                try (BufferedReader reader = reader(active)) {
                    StringBuilder result = new StringBuilder();
                    char[] chunk = new char[2048];
                    for (int count; (count = reader.read(chunk)) != -1;) {
                        if (cancelled) throw new IOException("أوقفت الطلب.");
                        if (result.length() + count > 64_000) throw new IOException("قائمة نماذج Grok كبيرة.");
                        result.append(chunk, 0, count);
                    }
                    JSONArray models = settings.server ? new JSONArray(result.toString())
                            : new JSONObject(result.toString()).getJSONArray("data");
                    for (int i = 0; i < models.length(); i++) {
                        String id = models.getJSONObject(i).optString("id");
                        if (!id.isEmpty() && (settings.server || (id.startsWith("grok-")
                                && !id.contains("image") && !id.contains("video")
                                && !id.contains("voice")))) return id;
                    }
                    throw new IOException("لم نجد نموذج محادثة Grok. أدخل معرّف النموذج في الاتصال.");
                }
            } finally {
                active.disconnect();
                connection = null;
            }
        }

        private HttpsURLConnection connect(Settings settings, String path, JSONObject request)
                throws IOException {
            HttpsURLConnection active =
                    (HttpsURLConnection) settings.base.resolve(path).toURL().openConnection();
            connection = active;
            try {
                if (cancelled) throw new IOException("أوقفت الطلب.");
                active.setInstanceFollowRedirects(false);
                active.setConnectTimeout(12_000);
                active.setReadTimeout(settings.collaborates() ? 185_000 : 95_000);
                active.setRequestProperty("Authorization", "Bearer " + settings.secret);
                if (request == null) {
                    active.setRequestProperty("Accept", "application/json");
                    return active;
                }
                active.setRequestMethod("POST");
                active.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                active.setRequestProperty("Accept", "api/project/scan".equals(path)
                        ? "application/json" : "text/event-stream");
                active.setDoOutput(true);
                byte[] bytes = request.toString().getBytes(StandardCharsets.UTF_8);
                active.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream output = active.getOutputStream()) {
                    output.write(bytes);
                }
                return active;
            } catch (IOException | RuntimeException problem) {
                active.disconnect();
                connection = null;
                throw problem;
            }
        }

        private String readStream(BufferedReader reader, boolean server, Listener listener)
                throws IOException, JSONException {
            StringBuilder answer = new StringBuilder();
            StringBuilder data = new StringBuilder();
            String event = "";
            boolean finished = false;
            String line;
            while (!cancelled && (line = reader.readLine()) != null) {
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) data.append('\n');
                    data.append(line.substring(5).trim());
                    if (data.length() > 256_000) throw new IOException("تجاوز الرد الحجم المدعوم.");
                } else if (line.isEmpty()) {
                    if (data.length() != 0) {
                        String frame = data.toString();
                        if ((!server && "[DONE]".equals(frame)) || (server && "done".equals(event))) {
                            finished = true;
                            break;
                        }
                        JSONObject json = new JSONObject(frame);
                        if ("error".equals(event) || json.has("error")) {
                            JSONObject providerError = json.optJSONObject("error");
                            String code = providerError == null
                                    ? json.optString("code") : providerError.optString("code");
                            if ("AI_PROVIDER_CREDITS".equals(code)) {
                                throw new IOException("يتطلب المزوّد رصيد API لهذا الطلب.");
                            }
                            if ("429".equals(code) || "AI_RATE_LIMIT".equals(code)) {
                                throw new IOException("بلغت حد الاستخدام المجاني. حاول لاحقًا.");
                            }
                            throw new IOException("توقف الرد لدى مزوّد النموذج.");
                        }
                        String delta = "";
                        if (server && "delta".equals(event)) {
                            delta = json.optString("text");
                        } else if (!server) {
                            JSONArray choices = json.optJSONArray("choices");
                            JSONObject choice = choices == null ? null : choices.optJSONObject(0);
                            if (choice != null) {
                                String reason = choice.isNull("finish_reason")
                                        ? "" : choice.optString("finish_reason", "");
                                if ("error".equals(reason)) throw new IOException("توقف رد النموذج.");
                                if (!reason.isEmpty()) finished = true;
                                JSONObject part = choice.optJSONObject("delta");
                                if (part != null && !part.isNull("content")) delta = part.optString("content");
                            }
                        }
                        if (!delta.isEmpty()) {
                            if (answer.length() + delta.length() > RESPONSE_LIMIT) {
                                throw new IOException("تجاوز الرد الحجم المدعوم.");
                            }
                            answer.append(delta);
                            listener.onText(delta);
                        }
                    }
                    data.setLength(0);
                    event = "";
                }
            }
            if (cancelled) throw new IOException("أوقفت الرد.");
            if (!finished || answer.length() == 0) throw new IOException("انقطع الرد قبل اكتماله.");
            return answer.toString();
        }

        private BufferedReader reader(HttpsURLConnection active) throws IOException {
            return new BufferedReader(new InputStreamReader(active.getInputStream(), StandardCharsets.UTF_8));
        }

        private void verifyStatus(HttpsURLConnection active, Settings settings) throws IOException {
            boolean server = settings.server;
            int status = active.getResponseCode();
            if (status >= 200 && status < 300) return;
            if (status == 401 || status == 403) {
                throw new IOException(server ? "رفض الخادم طلب الوصول. راجع رمز الدخول والإعدادات."
                        : "رفض " + (settings.directGrok() ? "Grok" : "OpenRouter") + " مفتاح API. تحقّق من المفتاح.");
            }
            if (status == 402) throw new IOException("يتطلب هذا الطلب رصيد API لدى المزوّد.");
            if (status == 429) {
                String retry = active.getHeaderField("Retry-After");
                String message = retry != null && retry.matches("[0-9]{1,8}")
                        ? "جرّب بعد " + retry + " ثانية." : "جرّب لاحقًا.";
                throw new IOException("بلغت حد الاستخدام. " + message);
            }
            if (status == 503 && server) throw new IOException("الطلب غير متاح في إعدادات الخادم.");
            throw new IOException("تعذّر إكمال الطلب (HTTP " + status + ").");
        }
    }
}
