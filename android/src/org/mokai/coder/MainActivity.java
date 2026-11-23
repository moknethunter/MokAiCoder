package org.mokai.coder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
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

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int INK = Color.rgb(26, 46, 36);
    private static final int MUTED = Color.rgb(73, 96, 83);
    private static final int ACCENT = Color.rgb(29, 105, 75);
    private static final int BACKGROUND = Color.rgb(246, 248, 244);
    private static final int LINE = Color.rgb(216, 227, 219);
    private static final int HEADER = Color.rgb(20, 46, 36);
    private static final int PALE_GREEN = Color.rgb(229, 244, 234);
    private static final String[] PROVIDERS = {"openrouter", "ollama", "grok", "paid"};
    private static final String[] PROVIDER_LABELS = {"OpenRouter", "Ollama", "Grok (xAI)", "مزوّد مدفوع"};
    private static final String[] DEFAULT_MODELS = {"openrouter/free", "qwen2.5-coder:7b", "", ""};

    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final List<Turn> history = new ArrayList<>();
    private ChatClient.Settings settings;
    private ChatClient.Call active;
    private LinearLayout transcript;
    private ScrollView scroll;
    private TextView status;
    private TextView source;
    private EditText prompt;
    private Button send;
    private Button configure;
    private Button scan;
    private Button clear;
    private boolean closed;

    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        LinearLayout root = column();
        root.setBackgroundColor(BACKGROUND);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setFocusableInTouchMode(true);

        LinearLayout banner = row();
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(dp(16), dp(12), dp(16), dp(12));
        banner.setBackgroundColor(HEADER);
        LinearLayout title = column();
        TextView brand = label("MokAiCoder", 22, Color.WHITE, true);
        brand.setTextDirection(View.TEXT_DIRECTION_LTR);
        title.addView(brand);
        source = label("محادثة برمجية", 13, Color.rgb(186, 221, 202), false);
        title.addView(source);
        banner.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        configure = button("الاتصال", false);
        configure.setOnClickListener(view -> connectionDialog());
        banner.addView(configure);
        root.addView(banner, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout toolbar = row();
        toolbar.setPadding(dp(12), dp(6), dp(12), 0);
        clear = button("محادثة جديدة", false);
        clear.setOnClickListener(view -> {
            history.clear();
            transcript.removeAllViews();
            sessionStatus("بدأت محادثة جديدة.");
        });
        toolbar.addView(clear);
        scan = button("فحص المشروع", false);
        scan.setVisibility(View.GONE);
        scan.setOnClickListener(view -> scanDialog());
        LinearLayout.LayoutParams scanSize = new LinearLayout.LayoutParams(-2, -2);
        scanSize.setMarginStart(dp(6));
        toolbar.addView(scan, scanSize);
        root.addView(toolbar);

        scroll = new ScrollView(this);
        scroll.setFillViewport(false);
        transcript = column();
        transcript.setPadding(dp(12), dp(6), dp(12), dp(8));
        scroll.addView(transcript, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        status = label("", 13, MUTED, false);
        status.setPadding(dp(16), dp(5), dp(16), dp(7));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));
        sessionStatus("افتح الاتصال وأدخل مفتاح OpenRouter أو بيانات خادمك.");

        LinearLayout composer = row();
        composer.setGravity(Gravity.BOTTOM);
        composer.setPadding(dp(12), dp(4), dp(12), dp(10));
        prompt = field("اكتب سؤالك", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        prompt.setMaxLines(5);
        prompt.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6000)});
        prompt.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        composer.addView(prompt, new LinearLayout.LayoutParams(0, -2, 1));
        send = button("إرسال", true);
        LinearLayout.LayoutParams sendSize = new LinearLayout.LayoutParams(-2, -2);
        sendSize.setMarginStart(dp(8));
        composer.addView(send, sendSize);
        send.setOnClickListener(view -> {
            if (active != null) {
                active.cancel();
                send.setEnabled(false);
                sessionStatus("جارٍ إيقاف الطلب…");
            } else {
                sendPrompt();
            }
        });
        root.addView(composer, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        root.requestFocus();
    }

    private void connectionDialog() {
        LinearLayout fields = column();
        fields.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        fields.setPadding(dp(20), dp(8), dp(20), dp(8));
        Spinner mode = picker(new String[]{"OpenRouter المجاني", "خادم MokAiCoder"});
        mode.setSelection(settings == null || settings.server ? 1 : 0);
        addField(fields, "طريقة الاتصال", mode);
        EditText address = field("https://", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        EditText secret = field("", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secret.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        secret.setTextDirection(View.TEXT_DIRECTION_LTR);
        Spinner provider = picker(PROVIDER_LABELS);
        String[] reviewLabels = new String[PROVIDER_LABELS.length + 1];
        reviewLabels[0] = "دون مراجع";
        System.arraycopy(PROVIDER_LABELS, 0, reviewLabels, 1, PROVIDER_LABELS.length);
        Spinner reviewer = picker(reviewLabels);
        EditText reviewModel = field("معرّف نموذج المراجعة", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        reviewModel.setTextDirection(View.TEXT_DIRECTION_LTR);
        EditText model = field("معرّف النموذج", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        model.setTextDirection(View.TEXT_DIRECTION_LTR);
        TextView secretLabel = label("", 14, INK, true);
        LinearLayout serverFields = column();
        addField(serverFields, "رابط خادم MokAiCoder", address);
        addField(serverFields, "المزوّد المهيّأ على الخادم", provider);
        addField(serverFields, "معرّف النموذج", model);
        addField(serverFields, "مزوّد المراجعة", reviewer);
        LinearLayout reviewFields = column();
        addField(reviewFields, "معرّف نموذج المراجعة", reviewModel);
        serverFields.addView(reviewFields);
        fields.addView(serverFields);
        fields.addView(secretLabel);
        fields.addView(secret, fieldSize());
        TextView error = label("", 13, Color.rgb(151, 48, 43), false);
        fields.addView(error);

        int initialProvider = settings == null ? 0 : providerIndex(settings.provider);
        provider.setSelection(initialProvider);
        int initialReviewer = settings == null || !settings.collaborates()
                ? 0 : providerIndex(settings.reviewer) + 1;
        reviewer.setSelection(initialReviewer);
        if (initialReviewer != 0) reviewModel.setText(settings.reviewModel);
        reviewFields.setVisibility(initialReviewer == 0 ? View.GONE : View.VISIBLE);
        reviewer.setOnItemSelectedListener(new Selection() {
            private int selection = initialReviewer;
            @Override public void select(int position) {
                reviewFields.setVisibility(position == 0 ? View.GONE : View.VISIBLE);
                if (selection != position && position > 0) reviewModel.setText(DEFAULT_MODELS[position - 1]);
                selection = position;
            }
        });
        model.setText(settings != null && settings.server ? settings.model : DEFAULT_MODELS[initialProvider]);
        if (settings != null && settings.server) address.setText(settings.base.toString());
        if (settings != null) secret.setText(settings.secret);
        Runnable updateMode = () -> {
            boolean useServer = mode.getSelectedItemPosition() == 1;
            serverFields.setVisibility(useServer ? View.VISIBLE : View.GONE);
            secretLabel.setText(useServer ? "رمز وصول MokAiCoder" : "مفتاح API من OpenRouter");
            secret.setHint(useServer ? "رمز الدخول" : "sk-or-…");
        };
        updateMode.run();
        mode.setOnItemSelectedListener(new Selection() {
            @Override public void select(int position) {
                updateMode.run();
                if (settings != null && (position == 1) != settings.server) secret.getText().clear();
            }
        });
        provider.setOnItemSelectedListener(new Selection() {
            private int selection = initialProvider;
            @Override public void select(int position) {
                if (selection != position) model.setText(DEFAULT_MODELS[position]);
                selection = position;
            }
        });

        ScrollView dialogScroll = new ScrollView(this);
        dialogScroll.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        dialogScroll.addView(fields);
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("إعداد الاتصال")
                .setView(dialogScroll)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حفظ", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String key = secret.getText().toString().trim();
                    try {
                        if (key.isEmpty()) throw new IllegalArgumentException("أدخل مفتاح API أو رمز الوصول.");
                        boolean server = mode.getSelectedItemPosition() == 1;
                        int reviewIndex = reviewer.getSelectedItemPosition();
                        settings = server
                                ? ChatClient.Settings.server(address.getText().toString(), key,
                                PROVIDERS[provider.getSelectedItemPosition()], model.getText().toString(),
                                reviewIndex == 0 ? "" : PROVIDERS[reviewIndex - 1],
                                reviewModel.getText().toString())
                                : ChatClient.Settings.free(key);
                        history.clear();
                        transcript.removeAllViews();
                        source.setText(server ? "خادم المشروع · " + settings.model : "OpenRouter · نموذج مجاني");
                        scan.setVisibility(server ? View.VISIBLE : View.GONE);
                        sessionStatus("جاهز لإرسال سؤال.");
                        secret.getText().clear();
                        dialog.dismiss();
                    } catch (IllegalArgumentException invalid) {
                        String message = invalid.getMessage();
                        error.setText(message != null && !message.isEmpty() && message.charAt(0) >= 0x0600
                                ? message : "تحقّق من رابط الخادم أو معرّف النموذج.");
                    }
                }));
        dialog.show();
    }

    private void sendPrompt() {
        if (settings == null) {
            connectionDialog();
            return;
        }
        String question = prompt.getText().toString().trim();
        if (question.isEmpty()) {
            prompt.requestFocus();
            return;
        }

        if (settings.paid() || settings.collaborates()) {
            String consent = settings.collaborates()
                    ? "تُشارك المحادثة ومسودة الرد والمراجعة بين المزوّدات التي اخترتها."
                    : "";
            if (settings.paid()) consent += "\nقد يُخصم هذا الطلب من رصيد API لدى المزوّد.";
            new AlertDialog.Builder(this)
                    .setTitle("تأكيد إرسال الطلب")
                    .setMessage(consent.trim())
                    .setNegativeButton("إلغاء", null)
                    .setPositiveButton("إرسال", (dialog, which) -> startChat(question))
                    .show();
        } else {
            startChat(question);
        }
    }

    private void startChat(String question) {
        ChatClient.Settings selected = settings;
        JSONArray request;
        try {
            request = messages(question);
        } catch (JSONException error) {
            sessionStatus("تعذّر تجهيز السؤال.");
            return;
        }
        ChatClient.Call call = new ChatClient.Call();
        setActive(call, "جارٍ تجهيز الرد…");
        prompt.getText().clear();
        TextView sent = appendMessage("أنت", question, true);
        TextView response = appendMessage("المساعد", "", false);

        network.execute(() -> {
            try {
                String answer = call.chat(selected, request, delta ->
                        runOnUiThread(() -> {
                            if (!isCurrent(call) || call.isCancelled()) return;
                            boolean follow = !scroll.canScrollVertically(1);
                            response.append(delta);
                            if (follow) scrollToEnd();
                        }));
                runOnUiThread(() -> {
                    if (!isCurrent(call)) return;
                    if (call.isCancelled()) {
                        showInterrupted(call, sent, response, question, "أوقفت الرد.");
                        return;
                    }
                    history.add(new Turn("user", question));
                    history.add(new Turn("assistant", answer));
                    if (history.size() > 12) history.subList(0, 2).clear();
                    setActive(null, "اكتمل الرد.");
                });
            } catch (Exception problem) {
                runOnUiThread(() -> {
                    if (isCurrent(call)) showInterrupted(call, sent, response, question, errorText(call, problem));
                });
            }
        });
    }

    private void showInterrupted(ChatClient.Call call, TextView question, TextView response,
                                 String original, String message) {
        if (!isCurrent(call)) return;
        if (response.length() == 0) {
            transcript.removeView((View) question.getParent());
            transcript.removeView((View) response.getParent());
            if (prompt.length() == 0) prompt.setText(original);
        }
        setActive(null, message);
    }

    private JSONArray messages(String question) throws JSONException {
        int start = history.size();
        int chars = question.length();
        while (start >= 2) {
            int pair = history.get(start - 1).text.length() + history.get(start - 2).text.length();
            if (chars + pair > 24_000) break;
            chars += pair;
            start -= 2;
        }
        JSONArray request = new JSONArray();
        for (int i = start; i < history.size(); i++) request.put(history.get(i).asJson());
        request.put(new Turn("user", question).asJson());
        return request;
    }

    private void scanDialog() {
        LinearLayout wrapper = column();
        wrapper.setPadding(dp(20), dp(8), dp(20), dp(8));
        EditText branch = field("الفرع اختياري", InputType.TYPE_CLASS_TEXT);
        branch.setSingleLine();
        wrapper.addView(branch, fieldSize());
        new AlertDialog.Builder(this)
                .setTitle("فحص مستودع الخادم")
                .setView(wrapper)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("فحص", (dialog, which) -> runScan(branch.getText().toString().trim()))
                .show();
    }

    private void runScan(String branch) {
        ChatClient.Settings selected = settings;
        ChatClient.Call call = new ChatClient.Call();
        setActive(call, "جارٍ فحص المشروع…");
        network.execute(() -> {
            try {
                JSONObject result = call.scan(selected, branch);
                runOnUiThread(() -> {
                    if (!isCurrent(call)) return;
                    if (call.isCancelled()) {
                        setActive(null, "أوقفت الفحص.");
                        return;
                    }
                    setActive(null, "اكتمل فحص المشروع.");
                    new AlertDialog.Builder(this)
                            .setTitle("نتيجة فحص المشروع")
                            .setMessage("الملفات: " + result.optInt("fileCount")
                                    + "\nالتبعيات: " + result.optInt("edgeCount")
                                    + "\nالدورات: " + result.optInt("cycleCount")
                                    + "\nالملفات المعزولة: " + result.optInt("orphanCount")
                                    + "\nالرموز المكررة: " + result.optInt("duplicateCount"))
                            .setPositiveButton("إغلاق", null)
                            .show();
                });
            } catch (Exception problem) {
                runOnUiThread(() -> {
                    if (isCurrent(call)) setActive(null, errorText(call, problem));
                });
            }
        });
    }

    private boolean isCurrent(ChatClient.Call call) {
        return !closed && active == call;
    }

    private void setActive(ChatClient.Call call, String message) {
        active = call;
        boolean idle = call == null;
        send.setText(idle ? "إرسال" : "إيقاف");
        send.setEnabled(true);
        scan.setEnabled(idle);
        configure.setEnabled(idle);
        clear.setEnabled(idle);
        sessionStatus(message);
    }

    private String errorText(ChatClient.Call call, Exception problem) {
        if (call.isCancelled()) return "أوقفت الطلب.";
        if (problem instanceof SocketTimeoutException) return "انتهت مهلة الاتصال. جرّب مرة أخرى.";
        String message = problem.getMessage();
        if (message != null && !message.isEmpty() && message.charAt(0) >= 0x0600) return message;
        return "تعذّر الاتصال. تحقّق من الإنترنت وإعدادات الاتصال.";
    }

    private void sessionStatus(String message) {
        status.setText(message);
    }

    private TextView appendMessage(String author, String text, boolean user) {
        LinearLayout item = column();
        item.setPadding(dp(12), dp(8), dp(12), dp(10));
        item.setBackground(shape(user ? PALE_GREEN : Color.WHITE, LINE, 12));
        item.addView(label(author, 12, ACCENT, true));
        TextView message = label(text, 16, INK, false);
        message.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        message.setTextIsSelectable(true);
        message.setPadding(0, dp(5), 0, 0);
        message.setLineSpacing(dp(3), 1);
        item.addView(message);
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.topMargin = dp(8);
        if (user) size.setMarginEnd(dp(28));
        else size.setMarginStart(dp(24));
        transcript.addView(item, size);
        if (transcript.getChildCount() > 80) transcript.removeViewAt(0);
        scrollToEnd();
        return message;
    }

    private void scrollToEnd() {
        scroll.post(() -> scroll.scrollTo(0, transcript.getHeight()));
    }

    private int providerIndex(String name) {
        for (int i = 0; i < PROVIDERS.length; i++) if (PROVIDERS[i].equals(name)) return i;
        return 0;
    }

    private void addField(LinearLayout fields, String title, View field) {
        TextView caption = label(title, 14, INK, true);
        caption.setPadding(0, dp(8), 0, dp(4));
        fields.addView(caption);
        fields.addView(field, fieldSize());
    }

    private LinearLayout.LayoutParams fieldSize() {
        LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, -2);
        size.bottomMargin = dp(8);
        return size;
    }

    private Spinner picker(String[] entries) {
        Spinner picker = new Spinner(this, Spinner.MODE_DROPDOWN);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, entries);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        picker.setAdapter(adapter);
        picker.setMinimumHeight(dp(46));
        return picker;
    }

    private EditText field(String hint, int inputType) {
        EditText field = new EditText(this);
        field.setInputType(inputType);
        field.setHint(hint);
        field.setTextColor(INK);
        field.setHintTextColor(MUTED);
        field.setTextSize(16);
        field.setMinHeight(dp(45));
        field.setPadding(dp(12), dp(7), dp(12), dp(7));
        field.setBackground(shape(Color.WHITE, LINE, 10));
        return field;
    }

    private Button button(String text, boolean primary) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTextColor(primary ? Color.WHITE : ACCENT);
        button.setPadding(dp(12), 0, dp(12), 0);
        button.setMinHeight(dp(44));
        button.setMinimumHeight(dp(44));
        button.setBackground(shape(Color.WHITE, LINE, 10));
        if (primary) button.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
        return button;
    }

    private TextView label(String text, int size, int color, boolean heading) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(color);
        if (heading) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private LinearLayout column() { return layout(LinearLayout.VERTICAL); }
    private LinearLayout row() { return layout(LinearLayout.HORIZONTAL); }

    private LinearLayout layout(int orientation) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(orientation);
        return layout;
    }

    private GradientDrawable shape(int background, int border, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(background);
        shape.setCornerRadius(dp(radius));
        shape.setStroke(dp(1), border);
        return shape;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + .5f);
    }

    @Override
    protected void onDestroy() {
        closed = true;
        if (active != null) active.cancel();
        network.shutdownNow();
        settings = null;
        super.onDestroy();
    }

    private static final class Turn {
        final String role;
        final String text;

        Turn(String role, String text) {
            this.role = role;
            this.text = text;
        }

        JSONObject asJson() throws JSONException {
            return new JSONObject().put("role", role).put("content", text);
        }
    }

    private abstract static class Selection implements android.widget.AdapterView.OnItemSelectedListener {
        abstract void select(int position);

        @Override public void onItemSelected(android.widget.AdapterView<?> parent, View item, int position, long id) {
            select(position);
        }
        @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
    }
}
