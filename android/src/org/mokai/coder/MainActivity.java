package org.mokai.coder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.net.Uri;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
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
    private static final int INK = AppUi.INK;
    private static final int MUTED = AppUi.MUTED;
    private static final int ACCENT = AppUi.ACCENT;
    private static final class Tabs {
        static final int CHAT = 0;
        static final int PROJECTS = 1;
        static final int TOOLS = 2;
    }
    private static final String[] PROVIDERS = {"openrouter", "ollama", "grok", "paid"};
    private static final String[] PROVIDER_LABELS = {"OpenRouter", "Ollama", "Grok (xAI)", "مزوّد مدفوع"};
    private static final String[] DEFAULT_MODELS = {"openrouter/free", "qwen2.5-coder:7b", "", ""};

    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final List<Turn> history = new ArrayList<>();
    private ConnectionStore connections;
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
    private View scanCard;
    private LinearLayout empty;
    private TextView emptyTitle;
    private TextView emptyHint;
    private Button emptyConnect;
    private final View[] pages = new View[3];
    private final TextView[] tabs = new TextView[3];
    private int selectedTab;
    private boolean closed;
    private boolean scanning;

    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        connections = new ConnectionStore(this);
        LinearLayout root = column();
        root.setBackgroundColor(AppUi.BACKGROUND);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setFocusableInTouchMode(true);
        root.addView(buildHeader(), new LinearLayout.LayoutParams(-1, -2));

        FrameLayout content = new FrameLayout(this);
        pages[Tabs.CHAT] = buildChatPage();
        pages[Tabs.PROJECTS] = buildProjectsPage();
        pages[Tabs.TOOLS] = buildToolsPage();
        for (View page : pages) content.addView(page, new FrameLayout.LayoutParams(-1, -1));
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        status = label("", 13, MUTED, false);
        status.setPadding(dp(16), dp(4), dp(16), dp(6));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setVisibility(View.GONE);
        root.addView(status, new LinearLayout.LayoutParams(-1, -2));
        root.addView(buildNavigation(), new LinearLayout.LayoutParams(-1, -2));

        setContentView(root);
        root.requestFocus();
        showTab(savedState == null ? Tabs.CHAT : savedState.getInt("screen", Tabs.CHAT));
        refreshEmptyChat();
        try {
            JSONObject saved = connections.read("chat");
            if (saved.has("secret")) {
                settings = readSettings(saved);
                showConnection();
            }
        } catch (Exception unavailable) {
            sessionStatus("تعذّرت استعادة الاتصال المحفوظ. أعد ضبطه من الاتصال.");
        }
    }

    private View buildHeader() {
        LinearLayout banner = row();
        banner.setBackgroundColor(AppUi.SURFACE);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(dp(16), dp(10), dp(16), dp(10));
        ImageView icon = new ImageView(this);
        icon.setImageResource(getApplicationInfo().icon);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        banner.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout title = column();
        title.setPaddingRelative(dp(10), 0, dp(8), 0);
        TextView brand = label("MokAiCoder", 20, INK, true);
        brand.setTextDirection(View.TEXT_DIRECTION_LTR);
        brand.setGravity(Gravity.START);
        title.addView(brand);
        source = label("إعداد الاتصال", 13, MUTED, false);
        source.setSingleLine();
        source.setEllipsize(TextUtils.TruncateAt.END);
        title.addView(source);
        banner.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        configure = button("الاتصال", false);
        configure.setOnClickListener(view -> connectionDialog());
        banner.addView(configure, new LinearLayout.LayoutParams(-2, -2));
        return banner;
    }

    private View buildChatPage() {
        LinearLayout chat = column();
        LinearLayout heading = row();
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.setPadding(dp(16), dp(6), dp(16), dp(4));
        TextView title = label("المحادثة", 19, INK, true);
        heading.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        clear = button("محادثة جديدة", false);
        clear.setOnClickListener(view -> resetChat());
        heading.addView(clear);
        chat.addView(heading);

        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = column();
        body.setPadding(dp(16), dp(4), dp(16), dp(12));
        empty = column();
        empty.setGravity(Gravity.CENTER_VERTICAL);
        empty.setPadding(dp(16), dp(24), dp(16), dp(24));
        emptyTitle = label("", 24, INK, true);
        emptyHint = label("", 16, MUTED, false);
        emptyHint.setLineSpacing(dp(4), 1);
        empty.addView(emptyTitle);
        empty.addView(emptyHint, AppUi.spaced(this, 8));
        emptyConnect = button("إعداد الاتصال", true);
        emptyConnect.setOnClickListener(view -> connectionDialog());
        empty.addView(emptyConnect, AppUi.spaced(this, 18));
        body.addView(empty, new LinearLayout.LayoutParams(-1, 0, 1));
        transcript = column();
        body.addView(transcript, new LinearLayout.LayoutParams(-1, -2));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -1));
        chat.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout composer = row();
        composer.setGravity(Gravity.BOTTOM);
        composer.setPadding(dp(16), dp(6), dp(16), dp(6));
        prompt = field("اكتب رسالة", InputType.TYPE_CLASS_TEXT
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
            if (active == null) {
                sendPrompt();
                return;
            }
            active.cancel();
            send.setEnabled(false);
            sessionStatus("جارٍ إيقاف الطلب…");
        });
        chat.addView(composer);
        return chat;
    }

    private View buildProjectsPage() {
        LinearLayout page = AppUi.page(this);
        AppUi.heading(page, "المشروع");
        Button assistant = button("فتح مساعد المشروع", true);
        assistant.setOnClickListener(view -> launchProject());
        addAction(page, "المساعد البرمجي", "راجع ملفات المشروع واقترح تعديلات عليه.", assistant);
        Button github = button("فتح GitHub", false);
        github.setOnClickListener(view -> {
            if (active == null) startActivity(new Intent(this, GitHubActivity.class));
        });
        addAction(page, "مستودع GitHub", "اربط مستودعًا أو تصفّح ملفاته.", github);
        scan = button("ابدأ الفحص", false);
        scan.setOnClickListener(view -> {
            if (active == null) {
                scanDialog();
            } else if (scanning) {
                active.cancel();
                scan.setEnabled(false);
                sessionStatus("جارٍ إيقاف الفحص…");
            }
        });
        scanCard = addAction(page, "فحص مستودع الخادم", "اعرض التبعيات والدورات والملفات المعزولة.", scan);
        scanCard.setVisibility(View.GONE);
        return scrollPage(page);
    }

    private View buildToolsPage() {
        LinearLayout page = AppUi.page(this);
        AppUi.heading(page, "الأدوات");
        Button bots = button("فتح البوتات", true);
        bots.setOnClickListener(view -> startActivity(new Intent(this, BotActivity.class)));
        addAction(page, "البوتات المحلية", "الإملاء وقراءة النص وتنسيق ملفات Word وPDF.", bots);
        Button models = button("إدارة النماذج", false);
        models.setOnClickListener(view -> openLocalModels(""));
        addAction(page, "نماذج الهاتف", "نزّل نموذجًا أو استورد ملف GGUF وشغّله على الهاتف.", models);
        return scrollPage(page);
    }

    private View addAction(LinearLayout parent, String title, String description, Button action) {
        LinearLayout card = column();
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackground(AppUi.shape(this, AppUi.SURFACE, AppUi.LINE, 18));
        card.addView(label(title, 17, INK, true));
        TextView detail = label(description, 14, MUTED, false);
        detail.setLineSpacing(dp(3), 1);
        card.addView(detail, AppUi.spaced(this, 4));
        card.addView(action, AppUi.spaced(this, 11));
        parent.addView(card, AppUi.spaced(this, 13));
        return card;
    }

    private ScrollView scrollPage(LinearLayout content) {
        ScrollView page = new ScrollView(this);
        page.addView(content, new ScrollView.LayoutParams(-1, -2));
        return page;
    }

    private View buildNavigation() {
        LinearLayout navigation = row();
        navigation.setPadding(dp(10), dp(6), dp(10), dp(8));
        navigation.setBackgroundColor(AppUi.SURFACE);
        navigation.setElevation(dp(3));
        String[] names = {"المحادثة", "المشروع", "الأدوات"};
        for (int index = 0; index < names.length; index++) {
            int page = index;
            Button tab = new Button(this);
            tab.setText(names[index]);
            tab.setAllCaps(false);
            tab.setTextSize(13);
            tab.setMinHeight(dp(56));
            tab.setMinimumHeight(dp(56));
            tab.setPadding(dp(4), dp(7), dp(4), dp(7));
            tab.setStateListAnimator(null);
            tab.setOnClickListener(view -> showTab(page));
            tabs[index] = tab;
            LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(0, -2, 1);
            size.setMarginStart(dp(3));
            size.setMarginEnd(dp(3));
            navigation.addView(tab, size);
        }
        return navigation;
    }

    private void showTab(int index) {
        selectedTab = Math.max(Tabs.CHAT, Math.min(index, Tabs.TOOLS));
        if (selectedTab != Tabs.CHAT && prompt.hasFocus()) {
            InputMethodManager keyboard = getSystemService(InputMethodManager.class);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(prompt.getWindowToken(), 0);
            prompt.clearFocus();
        }
        for (int i = 0; i < pages.length; i++) {
            boolean selected = i == selectedTab;
            pages[i].setVisibility(selected ? View.VISIBLE : View.GONE);
            AppUi.styleTab(tabs[i], i, selected);
        }
    }

    private void resetChat() {
        history.clear();
        transcript.removeAllViews();
        refreshEmptyChat();
        sessionStatus("");
    }

    private void refreshEmptyChat() {
        boolean needsConnection = settings == null;
        emptyTitle.setText(needsConnection ? "ابدأ محادثتك" : "كيف أساعدك في الكود؟");
        emptyHint.setText(needsConnection
                ? "اضبط Grok أو OpenRouter أو خادم MokAiCoder لإرسال رسالتك الأولى."
                : "اكتب سؤالك أو الصق مقطع كود للمراجعة.");
        emptyConnect.setVisibility(needsConnection ? View.VISIBLE : View.GONE);
        emptyConnect.setEnabled(active == null);
        empty.setVisibility(transcript.getChildCount() == 0 ? View.VISIBLE : View.GONE);
        clear.setVisibility(transcript.getChildCount() == 0 ? View.GONE : View.VISIBLE);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("screen", selectedTab);
        super.onSaveInstanceState(state);
    }

    @Override public void onBackPressed() {
        if (selectedTab == Tabs.CHAT) super.onBackPressed();
        else showTab(Tabs.CHAT);
    }

    private void connectionDialog() {
        LinearLayout fields = column();
        fields.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        fields.setPadding(dp(20), dp(8), dp(20), dp(8));
        Spinner mode = picker(new String[]{"OpenRouter المجاني", "خادم MokAiCoder", "Grok مباشر"});
        int initialMode = settings == null || settings.directGrok() ? 2 : settings.server ? 1 : 0;
        mode.setSelection(initialMode);
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
        fields.addView(serverFields);
        LinearLayout serverOptions = column();
        addField(serverOptions, "معرّف النموذج (اختياري لـ Grok)", model);
        addField(serverOptions, "مزوّد المراجعة", reviewer);
        LinearLayout reviewFields = column();
        addField(reviewFields, "معرّف نموذج المراجعة", reviewModel);
        serverOptions.addView(reviewFields);
        EditText grokModel = field("اختياري، يُكتشف تلقائيًا", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        grokModel.setTextDirection(View.TEXT_DIRECTION_LTR);
        if (settings != null && settings.directGrok()) grokModel.setText(settings.model);
        LinearLayout grokFields = column();
        addField(grokFields, "معرّف نموذج Grok", grokModel);
        fields.addView(secretLabel);
        secret.setId(View.generateViewId());
        secretLabel.setLabelFor(secret.getId());
        fields.addView(secret, fieldSize());
        Button advanced = button("إعدادات متقدمة", false);
        fields.addView(advanced, AppUi.spaced(this, 3));
        fields.addView(serverOptions);
        fields.addView(grokFields);
        TextView error = label("", 13, Color.rgb(151, 48, 43), false);
        fields.addView(error);

        int initialProvider = settings == null ? providerIndex("grok") : providerIndex(settings.provider);
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

        Runnable updateMode = () -> {
            int selected = mode.getSelectedItemPosition();
            boolean useServer = selected == 1;
            boolean useGrok = selected == 2;
            serverFields.setVisibility(useServer ? View.VISIBLE : View.GONE);
            advanced.setVisibility(useServer || useGrok ? View.VISIBLE : View.GONE);
            advanced.setText(advanced.isSelected() ? "إخفاء الإعدادات المتقدمة" : "إعدادات متقدمة");
            serverOptions.setVisibility(useServer && advanced.isSelected() ? View.VISIBLE : View.GONE);
            grokFields.setVisibility(useGrok && advanced.isSelected() ? View.VISIBLE : View.GONE);
            secretLabel.setText(useServer ? "رمز وصول MokAiCoder"
                    : useGrok ? "مفتاح API من xAI" : "مفتاح API من OpenRouter");
            secret.setHint(settings != null && selected == initialMode
                    ? "محفوظ على الهاتف · اتركه فارغًا للاحتفاظ به" : "أدخل المفتاح أو الرمز");
        };
        advanced.setOnClickListener(view -> {
            advanced.setSelected(!advanced.isSelected());
            updateMode.run();
        });
        updateMode.run();
        mode.setOnItemSelectedListener(new Selection() {
            private int previous = initialMode;
            @Override public void select(int position) {
                if (position != previous) {
                    secret.getText().clear();
                    advanced.setSelected(false);
                }
                previous = position;
                updateMode.run();
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
        AlertDialog.Builder builder = new AlertDialog.Builder(this)
                .setTitle("إعداد الاتصال")
                .setView(dialogScroll)
                .setNegativeButton("إلغاء", null)
                .setPositiveButton("حفظ", null);
        if (settings != null) builder.setNeutralButton("حذف الاتصال", (ignored, which) -> forgetConnection());
        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String key = secret.getText().toString().trim();
                    int selected = mode.getSelectedItemPosition();
                    if (key.isEmpty() && settings != null && selected == initialMode) key = settings.secret;
                    try {
                        if (key.isEmpty()) throw new IllegalArgumentException("أدخل مفتاح API أو رمز الوصول.");
                        int reviewIndex = reviewer.getSelectedItemPosition();
                        JSONObject saved = new JSONObject().put("mode", selected).put("secret", key)
                                .put("address", address.getText().toString())
                                .put("provider", PROVIDERS[provider.getSelectedItemPosition()])
                                .put("model", selected == 2 ? grokModel.getText().toString() : model.getText().toString())
                                .put("reviewer", reviewIndex == 0 ? "" : PROVIDERS[reviewIndex - 1])
                                .put("reviewModel", reviewModel.getText().toString());
                        ChatClient.Settings updated = readSettings(saved);
                        connections.save("chat", saved);
                        settings = updated;
                        resetChat();
                        showConnection();
                        secret.getText().clear();
                        dialog.dismiss();
                    } catch (IllegalArgumentException invalid) {
                        String message = invalid.getMessage();
                        error.setText(message != null && !message.isEmpty() && message.charAt(0) >= 0x0600
                                ? message : "تحقّق من رابط الخادم أو معرّف النموذج.");
                    } catch (Exception unavailable) {
                        error.setText("تعذّر حفظ الاتصال على الهاتف.");
                    }
                }));
        dialog.show();
    }

    private ChatClient.Settings readSettings(JSONObject saved) throws JSONException {
        String key = saved.getString("secret");
        switch (saved.getInt("mode")) {
            case 1:
                return ChatClient.Settings.server(saved.getString("address"), key, saved.getString("provider"),
                        saved.getString("model"), saved.getString("reviewer"), saved.getString("reviewModel"));
            case 2:
                return ChatClient.Settings.grok(key, saved.getString("model"));
            default:
                return ChatClient.Settings.free(key);
        }
    }

    private void showConnection() {
        source.setText(settings.server ? "خادم MokAiCoder"
                : settings.directGrok() ? "Grok مباشر" : "OpenRouter · نموذج مجاني");
        scanCard.setVisibility(settings.server ? View.VISIBLE : View.GONE);
        refreshEmptyChat();
        sessionStatus("الاتصال محفوظ على الهاتف.");
    }

    private void forgetConnection() {
        try {
            connections.remove("chat");
            settings = null;
            resetChat();
            scanCard.setVisibility(View.GONE);
            source.setText("إعداد الاتصال");
            sessionStatus("حُذف الاتصال المحفوظ.");
        } catch (IllegalStateException unavailable) {
            sessionStatus("تعذّر حذف الاتصال المحفوظ.");
        }
    }

    private void launchProject() {
        if (settings == null || !settings.server) {
            if (active == null) startActivity(new Intent(this, GitHubActivity.class));
            return;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(settings.base.resolve("project").toString())));
        } catch (ActivityNotFoundException unavailable) {
            sessionStatus("متصفح الهاتف غير متاح.");
        }
    }

    private void openLocalModels(String command) {
        startActivity(new Intent(this, ModelsActivity.class).putExtra(ModelsActivity.COMMAND, command));
    }

    private void sendPrompt() {
        String question = prompt.getText().toString().trim();
        if (LocalModelCatalog.downloadCommand(question)) {
            prompt.getText().clear();
            openLocalModels(question);
            return;
        }
        if (settings == null) {
            connectionDialog();
            return;
        }
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
            refreshEmptyChat();
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
        scanning = true;
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
        if (idle) scanning = false;
        scan.setText(scanning ? "إيقاف الفحص" : "ابدأ الفحص");
        scan.setEnabled(idle || scanning);
        configure.setEnabled(idle);
        clear.setEnabled(idle);
        refreshEmptyChat();
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
        status.setVisibility(message.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private TextView appendMessage(String author, String text, boolean user) {
        LinearLayout item = column();
        item.setPadding(dp(12), dp(8), dp(12), dp(10));
        item.setBackground(AppUi.shape(this, user ? AppUi.MINT : AppUi.SURFACE, AppUi.LINE, 16));
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
        refreshEmptyChat();
        if (transcript.getChildCount() > 80) transcript.removeViewAt(0);
        scrollToEnd();
        return message;
    }

    private void scrollToEnd() {
        scroll.post(() -> scroll.scrollTo(0, scroll.getChildAt(0).getHeight()));
    }

    private int providerIndex(String name) {
        for (int i = 0; i < PROVIDERS.length; i++) if (PROVIDERS[i].equals(name)) return i;
        return 0;
    }

    private void addField(LinearLayout fields, String title, View field) {
        TextView caption = label(title, 14, INK, true);
        caption.setPadding(0, dp(8), 0, dp(4));
        if (field.getId() == View.NO_ID) field.setId(View.generateViewId());
        caption.setLabelFor(field.getId());
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
        AppUi.field(field);
        return field;
    }

    private Button button(String text, boolean primary) {
        return AppUi.button(this, text, primary);
    }

    private TextView label(String text, int size, int color, boolean heading) {
        return AppUi.text(this, text, size, color, heading);
    }

    private LinearLayout column() { return layout(LinearLayout.VERTICAL); }
    private LinearLayout row() { return layout(LinearLayout.HORIZONTAL); }

    private LinearLayout layout(int orientation) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(orientation);
        return layout;
    }

    private int dp(int value) {
        return AppUi.dp(this, value);
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
