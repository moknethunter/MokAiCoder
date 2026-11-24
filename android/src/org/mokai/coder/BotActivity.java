package org.mokai.coder;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.print.PrintManager;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.vosk.Model;
import org.vosk.Recognizer;
import org.vosk.android.RecognitionListener;
import org.vosk.android.SpeechService;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class BotActivity extends Activity {
    private static final int IMPORT_MODEL = 101;
    private static final int EXPORT_FILE = 102;
    private static final int MICROPHONE = 103;
    private static final float SAMPLE_RATE = 16_000f;
    private static final int INK = AppUi.INK;
    private static final int MUTED = AppUi.MUTED;
    private static final String WORD = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String[] KIND_LABELS = {"صوت وكتابة", "تنسيق المستندات", "تصميم الواجهات"};

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<Bot> bots = new ArrayList<>();
    private final StringBuilder recognized = new StringBuilder();
    private SharedPreferences preferences;
    private Spinner catalog;
    private LinearLayout workspace;
    private TextView status;
    private Bot current;
    private TextToSpeech tts;
    private boolean ttsReady;
    private boolean importing;
    private boolean loadingVoice;
    private boolean closed;
    private Model model;
    private Recognizer recognizer;
    private SpeechService speech;
    private int voiceSession;

    private EditText nameField;
    private EditText contentField;
    private EditText cardsField;
    private Spinner options;
    private Button record;
    private Button modelButton;
    private TextView modelStatus;
    private WebView preview;
    private WebView printView;
    private FileWriter pendingExport;

    private interface FileWriter {
        void write(OutputStream stream) throws IOException;
    }

    private static final class Bot {
        final String id;
        final String name;
        final int kind;

        Bot(String id, String name, int kind) {
            this.id = id;
            this.name = name;
            this.kind = kind;
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("local-bots", MODE_PRIVATE);
        readBots();
        tts = new TextToSpeech(this, result -> ttsReady = result == TextToSpeech.SUCCESS);
        ScrollView scroll = new ScrollView(this);
        LinearLayout page = AppUi.page(this);
        page.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        AppUi.heading(page, "البوتات المحلية");
        page.addView(text("تُحفظ إعدادات البوتات على هذا الهاتف.", 14, MUTED));
        page.addView(button("إنشاء بوت", this::createBot));
        catalog = spinner(new String[]{});
        page.addView(catalog);
        status = text("", 14, MUTED);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        page.addView(status);
        workspace = column();
        page.addView(workspace);
        scroll.addView(page);
        setContentView(scroll);
        catalog.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (position < bots.size() && current != bots.get(position)) openBot(bots.get(position));
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        refreshCatalog(0);
    }

    private boolean toolsOnly() {
        return "org.mokai.coder.tools".equals(getPackageName());
    }

    private void readBots() {
        String raw = preferences.getString("bots", null);
        if (raw == null) {
            bots.add(new Bot("voice", KIND_LABELS[0], 0));
            bots.add(new Bot("document", KIND_LABELS[1], 1));
            if (!toolsOnly()) bots.add(new Bot("interface", KIND_LABELS[2], 2));
            return;
        }
        try {
            JSONArray data = new JSONArray(raw);
            for (int i = 0; i < data.length(); i++) {
                JSONObject record = data.getJSONObject(i);
                int kind = record.getInt("kind");
                if (kind >= 0 && kind < (toolsOnly() ? 2 : KIND_LABELS.length))
                    bots.add(new Bot(record.getString("id"), record.getString("name"), kind));
            }
        } catch (JSONException invalid) {
            bots.clear();
        }
    }

    private void saveBots() {
        JSONArray data = new JSONArray();
        for (Bot bot : bots) {
            JSONObject record = new JSONObject();
            try {
                record.put("id", bot.id);
                record.put("name", bot.name);
                record.put("kind", bot.kind);
                data.put(record);
            } catch (JSONException invalid) {
                throw new IllegalStateException(invalid);
            }
        }
        preferences.edit().putString("bots", data.toString()).apply();
    }

    private void createBot() {
        if (bots.size() >= 40) {
            message("وصلت إلى الحد الأقصى. احذف بوتًا لا تحتاجه لإضافة بوت جديد.");
            return;
        }
        LinearLayout form = column();
        form.setPadding(dp(16), dp(6), dp(16), 0);
        form.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        EditText name = field("اسم البوت", 80, false);
        Spinner kind = spinner(toolsOnly() ? Arrays.copyOf(KIND_LABELS, 2) : KIND_LABELS);
        form.addView(name);
        form.addView(kind);
        new AlertDialog.Builder(this).setTitle("إنشاء بوت على الهاتف").setView(form)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حفظ", (dialog, which) -> {
                    int choice = kind.getSelectedItemPosition();
                    String label = name.getText().toString().trim();
                    bots.add(new Bot(UUID.randomUUID().toString(), label.isEmpty() ? KIND_LABELS[choice] : label, choice));
                    saveBots();
                    refreshCatalog(bots.size() - 1);
                }).show();
    }

    private void refreshCatalog(int selected) {
        List<String> labels = new ArrayList<>();
        for (Bot bot : bots) labels.add(bot.name + " · " + KIND_LABELS[bot.kind]);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
        catalog.setAdapter(adapter);
        if (bots.isEmpty()) {
            leaveBot();
            current = null;
            catalog.setVisibility(View.GONE);
            message("أنشئ بوتًا للبدء.");
        } else {
            catalog.setVisibility(View.VISIBLE);
            catalog.setSelection(selected);
            openBot(bots.get(selected));
        }
    }

    private void leaveBot() {
        voiceSession++;
        loadingVoice = false;
        releaseRecording();
        if (tts != null) tts.stop();
        workspace.removeAllViews();
        if (preview != null) {
            preview.destroy();
            preview = null;
        }
        record = null;
        modelButton = null;
        modelStatus = null;
    }

    private void openBot(Bot bot) {
        leaveBot();
        current = bot;
        message("");
        TextView name = text(bot.name, 19, INK);
        name.setTypeface(null, Typeface.BOLD);
        workspace.addView(name);
        if (bot.kind == 0) showVoice();
        if (bot.kind == 1) showDocument();
        if (bot.kind == 2) showDesign();
        workspace.addView(button("حذف هذا البوت", () -> new AlertDialog.Builder(this)
                .setTitle("حذف " + bot.name + "؟")
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حذف", (dialog, which) -> {
                    bots.remove(bot);
                    preferences.edit().remove("option-" + bot.id).apply();
                    saveBots();
                    refreshCatalog(0);
                }).show()));
    }

    private void showVoice() {
        modelButton = button("استيراد ملف نموذج الصوت ZIP", () -> {
            if (importing || loadingVoice || speech != null) return;
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*");
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            openPicker(pick, IMPORT_MODEL);
        });
        workspace.addView(modelButton);
        workspace.addView(button("تنزيل نموذج الإملاء العربي", () ->
                openPicker(new Intent(Intent.ACTION_VIEW, Uri.parse(
                        "https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip")), 0)));
        modelStatus = text("", 14, MUTED);
        updateModelLabel();
        workspace.addView(modelStatus);
        record = button("ابدأ الإملاء من الميكروفون", this::recordPressed);
        workspace.addView(record);
        contentField = field("يظهر الإملاء هنا. يمكنك أيضًا لصق نص لقراءته.", 12_000, true);
        workspace.addView(contentField);
        options = botOptions(new String[]{"صوت عربي", "صوت إنجليزي"});
        workspace.addView(options);
        workspace.addView(button("اقرأ النص الآن", this::speak));
        workspace.addView(button("إيقاف القراءة", () -> {
            tts.stop();
            message("توقفت القراءة.");
        }));
        workspace.addView(button("إعداد صوت القراءة على الهاتف", () ->
                openPicker(new Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA), 0)));
        workspace.addView(button("حفظ النص TXT", () -> {
            String content = input(contentField);
            if (!hasText(content)) return;
            export("text/plain", "MokAiCoder.txt", out -> out.write(content.getBytes(StandardCharsets.UTF_8)));
        }));
        if (importing) refreshRecordButtons("جارٍ تجهيز نموذج الصوت…");
    }

    private void updateModelLabel() {
        if (modelStatus == null) return;
        String installed = preferences.getString("model-label", "");
        modelStatus.setText(installed.isEmpty()
                ? "ثبّت نموذج Vosk مرة واحدة لبدء الإملاء."
                : "نموذج الإملاء: " + installed);
    }

    private void showDocument() {
        nameField = field("عنوان المستند (اختياري)", 160, false);
        contentField = field("نص المستند. اكتب ## قبل عنوان فرعي و- قبل نقطة.", 18_000, true);
        workspace.addView(nameField);
        workspace.addView(contentField);
        options = botOptions(new String[]{"من اليمين إلى اليسار", "من اليسار إلى اليمين"});
        workspace.addView(options);
        workspace.addView(button("معاينة المستند", () -> {
            List<LocalExports.Block> blocks = document();
            if (!blocks.isEmpty()) showPreview(LocalExports.printHtml(blocks, rtl()));
        }));
        workspace.addView(button("حفظ Word", () -> {
            List<LocalExports.Block> blocks = document();
            if (blocks.isEmpty()) return;
            boolean direction = rtl();
            export(WORD, "MokAiCoder.docx", out -> LocalExports.writeWord(out, blocks, direction));
        }));
        workspace.addView(button("حفظ PDF", () -> {
            List<LocalExports.Block> blocks = document();
            if (!blocks.isEmpty()) printPdf(LocalExports.printHtml(blocks, rtl()));
        }));
        addPreview();
    }

    private void showDesign() {
        nameField = field("عنوان الواجهة", 100, false);
        contentField = field("وصف الشاشة وما تعرضه", 800, true);
        cardsField = field("قسم في كل سطر. مثال: الطلبات: اعرض الطلبات الجديدة", 2_500, true);
        workspace.addView(nameField);
        workspace.addView(contentField);
        workspace.addView(cardsField);
        options = botOptions(new String[]{"أخضر", "أزرق", "عنابي"});
        workspace.addView(options);
        workspace.addView(button("معاينة الواجهة", () -> {
            String html = layout();
            if (html != null) showPreview(html);
        }));
        workspace.addView(button("حفظ الواجهة HTML", () -> {
            String html = layout();
            if (html != null) export("text/html", "MokAiCoder.html", out -> out.write(html.getBytes(StandardCharsets.UTF_8)));
        }));
        addPreview();
    }

    private Spinner botOptions(String[] labels) {
        Spinner result = spinner(labels);
        String storageKey = "option-" + current.id;
        result.setSelection(Math.max(0, Math.min(labels.length - 1, preferences.getInt(storageKey, 0))));
        result.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                preferences.edit().putInt(storageKey, position).apply();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        });
        return result;
    }

    private List<LocalExports.Block> document() {
        List<LocalExports.Block> blocks = LocalExports.format(input(nameField), input(contentField));
        if (blocks.isEmpty()) message("أدخل نص المستند أولًا.");
        return blocks;
    }

    private boolean rtl() {
        return options.getSelectedItemPosition() == 0;
    }

    private String layout() {
        String title = input(nameField);
        if (!hasText(title)) return null;
        boolean rtl = !title.matches("(?s).*[a-zA-Z].*") || title.matches("(?s).*[\\p{InArabic}].*");
        return LocalExports.interfaceHtml(title, input(contentField), input(cardsField), rtl,
                options.getSelectedItemPosition());
    }

    private void addPreview() {
        preview = localWebView();
        preview.setVisibility(View.GONE);
        workspace.addView(preview, new LinearLayout.LayoutParams(-1, dp(390)));
    }

    private WebView localWebView() {
        WebView view = new WebView(this);
        view.getSettings().setBlockNetworkLoads(true);
        view.getSettings().setAllowFileAccess(false);
        view.getSettings().setAllowContentAccess(false);
        return view;
    }

    private void showPreview(String html) {
        preview.setVisibility(View.VISIBLE);
        preview.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        message("المعاينة جاهزة.");
    }

    private void printPdf(String html) {
        if (printView != null) {
            printView.destroy();
            printView = null;
        }
        WebView page = localWebView();
        printView = page;
        page.setWebViewClient(new WebViewClient() {
            private boolean printed;
            @Override public void onPageFinished(WebView view, String url) {
                if (printed || closed || printView != page) return;
                printed = true;
                PrintManager manager = getSystemService(PrintManager.class);
                if (manager == null) {
                    message("خدمة طباعة PDF غير متاحة على هذا الهاتف.");
                    return;
                }
                manager.print("MokAiCoder", page.createPrintDocumentAdapter("MokAiCoder"), null);
            }
        });
        page.loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
        message("اختر الحفظ بتنسيق PDF من شاشة الطباعة.");
    }

    private void speak() {
        if (speech != null || loadingVoice) {
            message("أوقف الإملاء قبل تشغيل القراءة.");
            return;
        }
        String value = input(contentField);
        if (!hasText(value)) return;
        if (!ttsReady) {
            message("محرك قراءة أندرويد لم يجهز بعد. تحقق من إعداد صوت القراءة.");
            return;
        }
        Locale language = options.getSelectedItemPosition() == 0 ? Locale.forLanguageTag("ar-SA") : Locale.US;
        Set<Voice> available = tts.getVoices();
        Voice local = null;
        if (available != null) for (Voice voice : available) {
            if (voice.isNetworkConnectionRequired() || !language.getLanguage().equals(voice.getLocale().getLanguage())
                    || voice.getFeatures() != null
                    && voice.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
            if (local == null || voice.getLocale().equals(language)) local = voice;
        }
        if (local == null || tts.setVoice(local) != TextToSpeech.SUCCESS) {
            message("ثبّت صوتًا لهذه اللغة من إعدادات قراءة أندرويد، ثم جرّب ثانية.");
            return;
        }
        int maximum = TextToSpeech.getMaxSpeechInputLength();
        for (int offset = 0; offset < value.length();) {
            int end = Math.min(value.length(), offset + maximum);
            if (end < value.length()) {
                int boundary = value.lastIndexOf(' ', end - 1);
                if (boundary > offset) end = boundary + 1;
                if (Character.isHighSurrogate(value.charAt(end - 1))) end--;
            }
            if (tts.speak(value.substring(offset, end), offset == 0 ? TextToSpeech.QUEUE_FLUSH : TextToSpeech.QUEUE_ADD,
                    null, "mokai-local-" + offset) == TextToSpeech.ERROR) {
                message("تعذّر تشغيل الصوت المحلي.");
                return;
            }
            offset = end;
        }
        message("بدأت القراءة المحلية.");
    }

    private void recordPressed() {
        if (speech != null) {
            releaseRecording();
            refreshRecordButtons("توقف الإملاء.");
            return;
        }
        if (loadingVoice || importing) return;
        String path = preferences.getString("model-path", "");
        if (path.isEmpty()) {
            message("استورد ملف نموذج Vosk ZIP أولًا.");
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, MICROPHONE);
            return;
        }
        tts.stop();
        if (model != null) {
            listen();
            return;
        }
        loadingVoice = true;
        int session = ++voiceSession;
        refreshRecordButtons("يُحمّل النموذج إلى ذاكرة الهاتف…");
        worker.execute(() -> {
            try {
                Model loaded = new Model(path);
                runOnUiThread(() -> {
                    if (closed || session != voiceSession) {
                        loaded.close();
                        return;
                    }
                    model = loaded;
                    loadingVoice = false;
                    listen();
                });
            } catch (IOException | RuntimeException failure) {
                runOnUiThread(() -> {
                    if (closed || session != voiceSession) return;
                    loadingVoice = false;
                    refreshRecordButtons("تعذّر فتح النموذج. أعد استيراد ملف Vosk المناسب.");
                });
            }
        });
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != MICROPHONE) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED && record != null) recordPressed();
        else message("يحتاج الإملاء إلى إذن الميكروفون.");
    }

    private void listen() {
        int session = ++voiceSession;
        recognized.setLength(0);
        recognized.append(input(contentField));
        try {
            recognizer = new Recognizer(model, SAMPLE_RATE);
            speech = new SpeechService(recognizer, SAMPLE_RATE);
            if (!speech.startListening(new RecognitionListener() {
                @Override public void onPartialResult(String value) { transcript(session, value, "partial", false); }
                @Override public void onResult(String value) { transcript(session, value, "text", true); }
                @Override public void onFinalResult(String value) { transcript(session, value, "text", true); }
                @Override public void onError(Exception error) {
                    if (session != voiceSession || closed) return;
                    releaseRecording();
                    refreshRecordButtons("تعذّر استخدام الميكروفون أو نموذج الصوت.");
                }
                @Override public void onTimeout() {
                    if (session != voiceSession || closed) return;
                    releaseRecording();
                    refreshRecordButtons("توقف الإملاء.");
                }
            })) throw new IOException("Could not start recognition");
            contentField.setEnabled(false);
            refreshRecordButtons("استمع الآن. يظهر النص أثناء الكلام.");
        } catch (IOException | RuntimeException failure) {
            releaseRecording();
            refreshRecordButtons("تعذّر بدء الإملاء. تحقق من الميكروفون والنموذج.");
        }
    }

    private void transcript(int session, String value, String key, boolean committed) {
        if (closed || session != voiceSession || record == null) return;
        try {
            String part = new JSONObject(value).optString(key).trim();
            if (committed && !part.isEmpty()) {
                if (recognized.length() > 0) recognized.append(' ');
                recognized.append(part);
            }
            String whole = recognized + (committed || part.isEmpty() ? "" : (recognized.length() > 0 ? " " : "") + part);
            if (whole.length() >= 12_000) {
                int end = Character.isHighSurrogate(whole.charAt(11_999)) ? 11_999 : 12_000;
                voiceSession++;
                releaseRecording();
                contentField.setText(whole.substring(0, end));
                refreshRecordButtons("وصل النص إلى الحد المسموح. احفظه قبل متابعة الإملاء.");
            } else contentField.setText(whole);
        } catch (JSONException ignored) {
            message("تعذّرت قراءة جزء من نتيجة الإملاء.");
        }
    }

    private void releaseRecording() {
        if (speech != null) {
            speech.stop();
            speech.shutdown();
            speech = null;
        }
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
        if (record != null && contentField != null) contentField.setEnabled(true);
    }

    private void refreshRecordButtons(String info) {
        if (record != null) {
            record.setEnabled(!loadingVoice && !importing);
            record.setText(speech == null ? "ابدأ الإملاء من الميكروفون" : "أوقف الإملاء");
            modelButton.setEnabled(!loadingVoice && !importing && speech == null);
        }
        message(info);
    }

    private void importModel(Uri source) {
        importing = true;
        if (model != null) {
            model.close();
            model = null;
        }
        refreshRecordButtons("جارٍ تثبيت نموذج الصوت على الهاتف…");
        File root = new File(getFilesDir(), "vosk-models");
        File packageDirectory = new File(root, UUID.randomUUID().toString());
        worker.execute(() -> {
            try (InputStream stream = getContentResolver().openInputStream(source)) {
                if (stream == null || (!root.exists() && !root.mkdir())) throw new IOException("Could not open model input");
                File installed = OfflineModel.importZip(stream, packageDirectory);
                try (Model verification = new Model(installed.getPath())) {
                    // Validate the imported model before replacing the active one.
                }
                runOnUiThread(() -> {
                    if (closed) {
                        OfflineModel.removeImportedFiles(packageDirectory);
                        return;
                    }
                    if (model != null) {
                        model.close();
                        model = null;
                    }
                    String previous = preferences.getString("model-package", "");
                    preferences.edit().putString("model-path", installed.getPath())
                            .putString("model-package", packageDirectory.getName())
                            .putString("model-label", installed.equals(packageDirectory) ? "Vosk" : installed.getName()).apply();
                    if (!previous.isEmpty()) worker.execute(() -> OfflineModel.removeImportedFiles(new File(root, previous)));
                    importing = false;
                    updateModelLabel();
                    refreshRecordButtons("النموذج جاهز للإملاء دون إنترنت.");
                });
            } catch (IOException | RuntimeException failure) {
                OfflineModel.removeImportedFiles(packageDirectory);
                runOnUiThread(() -> {
                    if (closed) return;
                    importing = false;
                    refreshRecordButtons("تعذّر تثبيت النموذج. اختر ملف ZIP صالحًا وتحقق من مساحة الهاتف.");
                });
            }
        });
    }

    private void export(String type, String filename, FileWriter write) {
        pendingExport = write;
        Intent create = new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(type);
        create.addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE, filename);
        openPicker(create, EXPORT_FILE);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Uri uri = resultCode == RESULT_OK && data != null ? data.getData() : null;
        if (requestCode == IMPORT_MODEL && uri != null) importModel(uri);
        if (requestCode == EXPORT_FILE) {
            FileWriter write = pendingExport;
            pendingExport = null;
            if (uri != null && write != null) worker.execute(() -> {
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new IOException("Could not open output");
                    write.write(out);
                    runOnUiThread(() -> message("حُفظ الملف."));
                } catch (IOException | RuntimeException failure) {
                    runOnUiThread(() -> message("تعذّر حفظ الملف في المكان المختار."));
                }
            });
        }
    }

    private void openPicker(Intent intent, int request) {
        try {
            if (request == 0) startActivity(intent);
            else startActivityForResult(intent, request);
        } catch (ActivityNotFoundException unavailable) {
            pendingExport = null;
            message("هذه الخدمة غير متاحة على الهاتف.");
        }
    }

    private boolean hasText(String value) {
        if (!value.isEmpty()) return true;
        message("أدخل نصًا أولًا.");
        return false;
    }

    private void message(String label) {
        if (!closed) status.setText(label);
    }

    private String input(EditText field) {
        return field.getText().toString().trim();
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView text(String value, int size, int color) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color);
        label.setPadding(0, dp(5), 0, dp(5));
        return label;
    }

    private Button button(String label, Runnable action) {
        Button button = AppUi.button(this, label, false);
        button.setLayoutParams(AppUi.spaced(this, 7));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private EditText field(String hint, int max, boolean multiline) {
        EditText field = new EditText(this);
        AppUi.field(field);
        field.setHint(hint);
        field.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(max)});
        field.setInputType(InputType.TYPE_CLASS_TEXT | (multiline ? InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
        if (multiline) {
            field.setMinLines(4);
            field.setMaxLines(10);
        }
        return field;
    }

    private Spinner spinner(String[] labels) {
        Spinner result = new Spinner(this);
        ArrayAdapter<String> values = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
        result.setAdapter(values);
        return result;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override protected void onStop() {
        super.onStop();
        if (loadingVoice) {
            voiceSession++;
            loadingVoice = false;
            refreshRecordButtons("توقف تحميل الصوت عند مغادرة الشاشة.");
        }
        if (speech != null) {
            releaseRecording();
            refreshRecordButtons("توقف الإملاء عند مغادرة الشاشة.");
        }
        if (tts != null) tts.stop();
    }

    @Override protected void onDestroy() {
        closed = true;
        leaveBot();
        if (model != null) model.close();
        if (tts != null) tts.shutdown();
        if (printView != null) printView.destroy();
        worker.shutdownNow();
        super.onDestroy();
    }
}
