package org.mokai.coder;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.provider.OpenableColumns;
import android.text.InputFilter;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ModelsActivity extends Activity {
    private static final int IMPORT = 301;
    private static final String FOLDER = "mokai-models";
    private static final String KEY_ID = "download";
    private static final String KEY_MODEL = "downloading-model";
    static final String COMMAND = "model_command";
    private final Handler timer = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final List<File> installed = new ArrayList<>();
    private final Runnable poll = this::pollDownload;
    private SharedPreferences prefs;
    private File directory;
    private DownloadManager downloads;
    private TextView status;
    private TextView output;
    private Spinner catalog;
    private Spinner selection;
    private EditText question;
    private Button run;
    private Button remove;
    private boolean busy;
    private boolean inference;
    private boolean closed;
    private String previous = "";

    @Override protected void onCreate(Bundle saved) {
        super.onCreate(saved);
        prefs = getSharedPreferences("local-models", MODE_PRIVATE);
        downloads = getSystemService(DownloadManager.class);
        File base = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (base != null) directory = new File(base, FOLDER);

        ScrollView scroll = new ScrollView(this);
        LinearLayout form = AppUi.page(this);
        AppUi.heading(form, "نماذج الهاتف");
        status = AppUi.text(this, "", 14, AppUi.MUTED, false);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        form.addView(status);
        form.addView(AppUi.text(this, "تنزيل أو استيراد", 17, AppUi.INK, true), AppUi.spaced(this, 12));
        catalog = new Spinner(this);
        catalog.setMinimumHeight(dp(48));
        catalog.setAdapter(adapter(Arrays.asList("Qwen3‑0.6B · 484 MB", "Qwen3‑1.7B · 1.28 GB", "Qwen2.5‑Coder‑1.5B · 1.12 GB")));
        form.addView(catalog);
        form.addView(button("فحص الموارد وتنزيل النموذج", () ->
                beginDownload(LocalModelCatalog.Model.values()[catalog.getSelectedItemPosition()])));
        form.addView(button("إلغاء تنزيل النموذج الجاري", this::cancelDownload));
        form.addView(button("استيراد نموذج GGUF من الهاتف", this::pick));
        form.addView(AppUi.text(this, "محادثة محلية", 17, AppUi.INK, true), AppUi.spaced(this, 20));
        selection = new Spinner(this);
        selection.setMinimumHeight(dp(48));
        form.addView(selection);
        question = new EditText(this);
        AppUi.field(question);
        question.setHint("اكتب طلبًا للنماذج المحلية");
        question.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        question.setMinLines(2);
        question.setFilters(new InputFilter[]{new InputFilter.LengthFilter(3500)});
        form.addView(question);
        run = AppUi.button(this, "تشغيل النموذج المحلي", true);
        run.setLayoutParams(AppUi.spaced(this, 8));
        run.setOnClickListener(view -> ask());
        form.addView(run);
        form.addView(button("إيقاف الإجابة", () -> {
            if (inference) LocalRuntime.cancel();
        }));
        remove = button("حذف النموذج المحدد من هذه النسخة", this::confirmRemove);
        form.addView(remove);
        output = new TextView(this);
        output.setTextSize(16);
        output.setTextIsSelectable(true);
        form.addView(output);
        scroll.addView(form);
        setContentView(scroll);
        reload(null);
        if (!LocalRuntime.AVAILABLE) note("يلزم إصدار MokAiCoder المرفق بمحرك GGUF على جهاز ARM64.");
        if (saved == null) handle(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handle(intent);
    }

    private void handle(Intent intent) {
        String command = intent.getStringExtra(COMMAND);
        if (command == null || command.isEmpty()) return;
        LocalModelCatalog.Model model = LocalModelCatalog.requested(command);
        if (model == null) {
            note("اختر أحد النماذج المتاحة، أو استورد ملف GGUF من الهاتف.");
            return;
        }
        catalog.setSelection(model.ordinal());
        beginDownload(model);
    }

    private void beginDownload(LocalModelCatalog.Model model) {
        if (busy || downloads == null) {
            note("انتظر انتهاء العملية الحالية.");
            return;
        }
        if (prefs.getLong(KEY_ID, -1) != -1) {
            note("يوجد تنزيل جارٍ. يمكنك متابعته أو إلغاؤه.");
            return;
        }
        if (!prepareDirectory()) return;
        File target = new File(directory, model.id + ".gguf");
        if (target.isFile()) {
            reload(target);
            note("النموذج موجود وجاهز للاستخدام.");
            return;
        }
        if (!resources(model.size, true)) return;
        File partial = new File(directory, model.id + ".part");
        if (partial.exists() && !partial.delete()) {
            note("تعذّر تجهيز ملف التنزيل.");
            return;
        }
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(model.url))
                    .setTitle(model.label)
                    .setDescription("تنزيل نموذج محلي لـ MokAiCoder")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS,
                            FOLDER + "/" + partial.getName());
            long id = downloads.enqueue(request);
            prefs.edit().putLong(KEY_ID, id).putString(KEY_MODEL, model.name()).apply();
            note("موارد الهاتف مناسبة وفق التقدير. بدأ التنزيل…");
            schedulePoll();
        } catch (RuntimeException unavailable) {
            note("تعذّر بدء التنزيل من المصدر.");
        }
    }

    private void pollDownload() {
        if (downloads == null || busy || closed || directory == null) return;
        long id = prefs.getLong(KEY_ID, -1);
        if (id == -1) return;
        String queued = prefs.getString(KEY_MODEL, "");
        LocalModelCatalog.Model model;
        try {
            model = LocalModelCatalog.Model.valueOf(queued);
        } catch (IllegalArgumentException invalid) {
            clearDownload();
            return;
        }
        File partial = new File(directory, model.id + ".part");
        File target = new File(directory, model.id + ".gguf");
        try (Cursor cursor = downloads.query(new DownloadManager.Query().setFilterById(id))) {
            if (cursor == null || !cursor.moveToFirst()) {
                clearDownload();
                note("تعذّر العثور على التنزيل في النظام.");
                return;
            }
            int state = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS));
            if (state == DownloadManager.STATUS_FAILED) {
                downloads.remove(id);
                clearDownload();
                partial.delete();
                note("فشل تنزيل النموذج. تحقق من الشبكة والمساحة ثم حاول مرة أخرى.");
                return;
            }
            if (state != DownloadManager.STATUS_SUCCESSFUL) {
                long read = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR));
                note("التنزيل جارٍ: " + human(read));
                schedulePoll();
                return;
            }
        } catch (RuntimeException failure) {
            note("تعذّرت قراءة حالة التنزيل من أندرويد.");
            schedulePoll();
            return;
        }
        busy = true;
        note("جارٍ التحقق من بصمة النموذج وربطه بالمحرك المحلي…");
        worker.execute(() -> {
            boolean valid = false;
            try {
                valid = target.isFile() || (partial.length() <= model.size && validHeader(partial)
                        && model.sha256.equals(sha256(partial)) && LocalRuntime.supports(partial)
                        && partial.renameTo(target));
            } catch (IOException | RuntimeException | LinkageError ignored) {}
            if (!valid) partial.delete();
            downloads.remove(id);
            clearDownload();
            final boolean installedModel = valid;
            runOnUiThread(() -> {
                if (closed) return;
                busy = false;
                reload(installedModel ? target : null);
                note(installedModel ? "اكتمل التنزيل وربط النموذج المحلي." : "ملف النموذج غير سليم. أعد تنزيله.");
            });
        });
    }

    private void schedulePoll() {
        timer.removeCallbacks(poll);
        timer.postDelayed(poll, 1200);
    }

    private void clearDownload() {
        prefs.edit().remove(KEY_ID).remove(KEY_MODEL).apply();
    }

    private void cancelDownload() {
        long id = prefs.getLong(KEY_ID, -1);
        if (busy || id == -1 || downloads == null) return;
        downloads.remove(id);
        clearDownload();
        timer.removeCallbacks(poll);
        note("أُلغي التنزيل.");
    }

    private void pick() {
        if (busy || !prepareDirectory()) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        try {
            startActivityForResult(intent, IMPORT);
        } catch (ActivityNotFoundException unavailable) {
            note("أداة اختيار الملفات غير متاحة.");
        }
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != IMPORT || result != RESULT_OK || data == null || data.getData() == null
                || busy || !prepareDirectory()) return;
        Uri uri = data.getData();
        long size = -1;
        String name = "local";
        try (Cursor cursor = getContentResolver().query(uri,
                new String[]{OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                if (!cursor.isNull(0)) size = cursor.getLong(0);
                if (!cursor.isNull(1)) name = cursor.getString(1);
            }
        } catch (RuntimeException ignored) {}
        if (size <= 0) {
            try (ParcelFileDescriptor file = getContentResolver().openFileDescriptor(uri, "r")) {
                if (file != null) size = file.getStatSize();
            } catch (IOException | RuntimeException ignored) {}
        }
        if (size <= 0) {
            note("لا يمكن قياس حجم الملف. اختر نسخة GGUF محفوظة في ملفات الهاتف.");
            return;
        }
        if (!resources(size, true)) return;
        String safe = name.replaceAll("[^a-zA-Z0-9._-]", "_").replaceAll("(?i)\\.gguf$", "");
        if (safe.length() > 50) safe = safe.substring(0, 50);
        File target = new File(directory, safe + "-" + UUID.randomUUID().toString().substring(0, 8) + ".gguf");
        File partial = new File(directory, target.getName() + ".part");
        final long expected = size;
        busy = true;
        note("جارٍ نسخ النموذج وفحص صيغته…");
        worker.execute(() -> {
            boolean valid = false;
            try {
                try (InputStream in = getContentResolver().openInputStream(uri);
                     FileOutputStream out = new FileOutputStream(partial)) {
                    if (in == null) throw new IOException("Cannot open model");
                    byte[] buffer = new byte[256 * 1024];
                    long total = 0;
                    for (int count; (count = in.read(buffer)) != -1;) {
                        total += count;
                        if (total > expected) throw new IOException("Unexpected model size");
                        out.write(buffer, 0, count);
                    }
                    if (total != expected) throw new IOException("Incomplete model");
                }
                valid = validHeader(partial) && LocalRuntime.supports(partial) && partial.renameTo(target);
            } catch (IOException | RuntimeException | LinkageError ignored) {
            } finally {
                if (!valid) partial.delete();
            }
            final boolean imported = valid;
            runOnUiThread(() -> {
                if (closed) return;
                busy = false;
                reload(imported ? target : null);
                note(imported ? "رُبط نموذج GGUF. جرّب سؤالًا للتحقق من توافقه." : "تعذّر قراءة ملف GGUF صالح.");
                schedulePoll();
            });
        });
    }

    private boolean prepareDirectory() {
        if (!LocalRuntime.AVAILABLE || !Process.is64Bit()) {
            note("هذه النسخة من المحرك المحلي مخصصة لـ Android ARM64.");
            return false;
        }
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) {
            note("مساحة النماذج غير متاحة.");
            return false;
        }
        return true;
    }

    private boolean resources(long size, boolean copying) {
        if (size <= 0 || size > LocalModelCatalog.MAX_BYTES) {
            note("اختر نموذجًا نصيًا GGUF أصغر من 2.8 GiB لهذا المحرك.");
            return false;
        }
        ActivityManager manager = getSystemService(ActivityManager.class);
        if (manager == null) {
            note("تعذّر قياس ذاكرة الهاتف.");
            return false;
        }
        ActivityManager.MemoryInfo memory = new ActivityManager.MemoryInfo();
        manager.getMemoryInfo(memory);
        long need = LocalModelCatalog.requiredMemory(size);
        if (memory.lowMemory || memory.availMem < need) {
            note("الذاكرة المتاحة " + human(memory.availMem) + "، ويقدّر المحرك الحاجة إلى " + human(need) + ".");
            return false;
        }
        long space = directory.getUsableSpace();
        if (copying && space < size + 300 * LocalModelCatalog.MIB) {
            note("التخزين المتاح " + human(space) + "، ويلزم " + human(size + 300 * LocalModelCatalog.MIB) + " تقريبًا.");
            return false;
        }
        return true;
    }

    private static boolean validHeader(File file) throws IOException {
        try (DataInputStream data = new DataInputStream(new FileInputStream(file))) {
            if (data.readInt() != 0x47475546) return false;
            int version = Integer.reverseBytes(data.readInt());
            return version == 2 || version == 3;
        }
    }

    private static String sha256(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[256 * 1024];
            for (int count; (count = input.read(buffer)) != -1;) hash.update(buffer, 0, count);
            StringBuilder text = new StringBuilder();
            for (byte b : hash.digest()) text.append(String.format(Locale.ROOT, "%02x", b & 255));
            return text.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IOException(impossible);
        }
    }

    private void reload(File chosen) {
        installed.clear();
        if (directory != null) {
            File[] files = directory.listFiles((folder, name) -> name.endsWith(".gguf"));
            if (files != null) {
                Arrays.sort(files, Comparator.comparing(File::getName));
                installed.addAll(Arrays.asList(files));
            }
        }
        List<String> labels = new ArrayList<>();
        labels.add("اختيار تلقائي حسب الطلب");
        for (File file : installed) labels.add(file.getName() + " · " + human(file.length()));
        selection.setAdapter(adapter(labels));
        if (chosen != null) selection.setSelection(Math.max(0, installed.indexOf(chosen) + 1));
        run.setEnabled(LocalRuntime.AVAILABLE && !installed.isEmpty());
        remove.setEnabled(!installed.isEmpty());
    }

    private File choose(String prompt) {
        int index = selection.getSelectedItemPosition() - 1;
        if (index >= 0 && index < installed.size()) return installed.get(index);
        if (LocalModelCatalog.coding(prompt)) {
            for (File file : installed) if (file.getName().toLowerCase(Locale.ROOT).contains("coder")) return file;
        }
        for (File file : installed) if (file.getName().startsWith(LocalModelCatalog.Model.CHAT.id)) return file;
        return installed.stream().min(Comparator.comparingLong(File::length)).orElse(null);
    }

    private void ask() {
        if (busy || !prepareDirectory()) return;
        String prompt = question.getText().toString().trim();
        if (prompt.isEmpty()) {
            note("اكتب طلبًا أولًا.");
            return;
        }
        File chosen = choose(prompt);
        if (chosen == null || !resources(chosen.length(), false)) return;
        String context = previous.isEmpty() ? prompt : "Previous exchange:\n" + previous + "\nNew message:\n" + prompt;
        busy = inference = true;
        run.setEnabled(false);
        output.setText("");
        note("يعالج النموذج " + chosen.getName() + " طلبك داخل الهاتف…");
        worker.execute(() -> {
            String answer = "";
            try {
                answer = LocalRuntime.generate(chosen, context);
            } catch (RuntimeException | LinkageError ignored) {}
            final String response = answer;
            runOnUiThread(() -> {
                if (closed) return;
                busy = inference = false;
                run.setEnabled(true);
                output.setText(response);
                if (!response.isEmpty()) {
                    String history = prompt + "\n" + response;
                    previous = history.substring(0, Math.min(history.length(), 1200));
                }
                note(response.isEmpty() ? "انتهت العملية بلا إجابة. تحقق من توافق النموذج أو جرّب سؤالًا أقصر." : "اكتملت الإجابة المحلية.");
                schedulePoll();
            });
        });
    }

    private void confirmRemove() {
        if (busy) return;
        int index = selection.getSelectedItemPosition() - 1;
        if (index < 0 || index >= installed.size()) {
            note("اختر اسم النموذج المراد حذفه.");
            return;
        }
        File file = installed.get(index);
        new android.app.AlertDialog.Builder(this).setTitle("حذف النموذج؟")
                .setMessage(file.getName()).setNegativeButton("إلغاء", null)
                .setPositiveButton("حذف", (dialog, which) -> {
                    boolean deleted = file.delete();
                    reload(null);
                    note(deleted ? "حُذف النموذج المحلي." : "تعذّر حذف النموذج.");
                }).show();
    }

    private String human(long bytes) {
        return String.format(Locale.ROOT, "%.0f MiB", bytes / (double) LocalModelCatalog.MIB);
    }

    private ArrayAdapter<String> adapter(List<String> labels) {
        return new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels);
    }

    private Button button(String label, Runnable action) {
        Button button = AppUi.button(this, label, false);
        button.setLayoutParams(AppUi.spaced(this, 7));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void note(String text) {
        if (!closed) status.setText(text);
    }

    @Override protected void onResume() {
        super.onResume();
        schedulePoll();
    }

    @Override protected void onStop() {
        timer.removeCallbacks(poll);
        if (inference) LocalRuntime.cancel();
        super.onStop();
    }

    @Override protected void onDestroy() {
        closed = true;
        timer.removeCallbacks(poll);
        if (inference) LocalRuntime.cancel();
        worker.shutdown();
        super.onDestroy();
    }
}
