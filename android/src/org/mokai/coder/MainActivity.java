package org.mokai.coder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.net.Uri;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.NoRouteToHostException;
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
    private ChatProfiles profiles;
    private ChatClient.Settings settings;
    private ChatClient.Call active;
    private LinearLayout transcript;
    private ScrollView scroll;
    private TextView status;
    private TextView screenTitle;
    private LinearLayout application;
    private FrameLayout navigation;
    private ScrollView drawerPanel;
    private ImageButton menu;
    private Button closeMenu;
    private EditText prompt;
    private Button send;
    private Button configure;
    private Button scan;
    private Button clear;
    private View scanCard;
    private LinearLayout empty;
    private final View[] pages = new View[3];
    private int selectedTab;
    private boolean closed;
    private boolean scanning;

    @Override
    protected void onCreate(Bundle savedState) {
        super.onCreate(savedState);
        connections = new ConnectionStore(this);
        FrameLayout root = new FrameLayout(this);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        application = column();
        application.setBackgroundColor(AppUi.BACKGROUND);
        application.setFocusableInTouchMode(true);
        application.addView(buildHeader(), new LinearLayout.LayoutParams(-1, -2));

        FrameLayout content = new FrameLayout(this);
        pages[Tabs.CHAT] = buildChatPage();
        pages[Tabs.PROJECTS] = buildProjectsPage();
        pages[Tabs.TOOLS] = buildToolsPage();
        for (View page : pages) content.addView(page, new FrameLayout.LayoutParams(-1, -1));
        application.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));

        status = label("", 13, MUTED, false);
        status.setPadding(dp(16), dp(4), dp(16), dp(6));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setVisibility(View.GONE);
        application.addView(status, new LinearLayout.LayoutParams(-1, -2));
        root.addView(application, new FrameLayout.LayoutParams(-1, -1));
        root.addView(buildNavigation(), new FrameLayout.LayoutParams(-1, -1));

        setContentView(root);
        application.requestFocus();
        showTab(savedState == null ? Tabs.CHAT : savedState.getInt("screen", Tabs.CHAT));
        refreshEmptyChat();
        try {
            profiles = loadProfiles();
            settings = readSettings(profiles.get(profiles.activeMode()), profiles.activeMode());
            showConnection();
        } catch (Exception unavailable) {
            sessionStatus("تعذّرت استعادة الإعدادات المحفوظة. افتح القائمة للمحاولة مرة أخرى.");
        }
    }

    private View buildHeader() {
        LinearLayout banner = row();
        banner.setBackgroundColor(AppUi.SURFACE);
        banner.setGravity(Gravity.CENTER_VERTICAL);
        banner.setPadding(dp(12), dp(6), dp(16), dp(6));
        menu = new ImageButton(this);
        menu.setImageDrawable(AppUi.menuIcon());
        menu.setPadding(dp(12), dp(12), dp(12), dp(12));
        menu.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        menu.setContentDescription("فتح القائمة");
        menu.setStateListAnimator(null);
        menu.setBackground(new RippleDrawable(ColorStateList.valueOf(0x25176E51),
                AppUi.shape(this, AppUi.SURFACE, AppUi.LINE, 14), null));
        menu.setOnClickListener(view -> openMenu());
        banner.addView(menu, new LinearLayout.LayoutParams(dp(48), dp(48)));

        screenTitle = label("MokAiCoder", 21, INK, true);
        screenTitle.setPaddingRelative(dp(12), 0, 0, 0);
        screenTitle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        banner.addView(screenTitle, new LinearLayout.LayoutParams(0, -1, 1));
        return banner;
    }

    private View buildChatPage() {
        LinearLayout chat = column();
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout body = column();
        body.setPadding(dp(16), dp(4), dp(16), dp(12));
        empty = column();
        empty.setGravity(Gravity.CENTER_VERTICAL);
        empty.setPadding(dp(16), dp(24), dp(16), dp(24));
        empty.addView(label("كيف أساعدك؟", 26, INK, true));
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
        navigation = new FrameLayout(this);
        navigation.setVisibility(View.GONE);
        navigation.setBackgroundColor(0x55142923);
        navigation.setOnClickListener(view -> closeMenu());

        LinearLayout panel = column();
        panel.setPadding(dp(16), dp(14), dp(16), dp(20));
        panel.setBackgroundColor(AppUi.SURFACE);
        panel.setClickable(true);
        LinearLayout top = row();
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("MokAiCoder", 20, INK, true);
        top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        closeMenu = button("إغلاق", false);
        closeMenu.setOnClickListener(view -> closeMenu());
        top.addView(closeMenu);
        panel.addView(top);
        addMenuItem(panel, "المحادثة", () -> showTab(Tabs.CHAT), true);
        clear = addMenuItem(panel, "محادثة جديدة", () -> {
            resetChat();
            showTab(Tabs.CHAT);
        }, false);
        addMenuItem(panel, "المشروع", () -> showTab(Tabs.PROJECTS), false);
        addMenuItem(panel, "الأدوات", () -> showTab(Tabs.TOOLS), false);
        configure = addMenuItem(panel, "إعدادات الاتصال", this::connectionDialog, false);

        drawerPanel = scrollPage(panel);
        drawerPanel.setBackgroundColor(AppUi.SURFACE);
        drawerPanel.setFillViewport(true);
        drawerPanel.setElevation(dp(8));
        navigation.addView(drawerPanel, new FrameLayout.LayoutParams(dp(304), -1, Gravity.START));
        return navigation;
    }

    private Button addMenuItem(LinearLayout panel, String title, Runnable action, boolean primary) {
        Button item = button(title, primary);
        item.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        item.setOnClickListener(view -> {
            closeMenu();
            action.run();
        });
        panel.addView(item, AppUi.spaced(this, 9));
        return item;
    }

    private void openMenu() {
        if (navigation.getVisibility() == View.VISIBLE) return;
        dismissKeyboard();
        FrameLayout.LayoutParams size = (FrameLayout.LayoutParams) drawerPanel.getLayoutParams();
        int width = application.getWidth();
        size.width = width > 0 ? Math.min(dp(320), (int) (width * .86f)) : dp(304);
        drawerPanel.setLayoutParams(size);
        application.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        navigation.setVisibility(View.VISIBLE);
        drawerPanel.setTranslationX(size.width);
        drawerPanel.animate().translationX(0).setDuration(170).start();
        closeMenu.requestFocus();
    }

    private void closeMenu() {
        drawerPanel.animate().cancel();
        navigation.setVisibility(View.GONE);
        application.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        menu.requestFocus();
    }

    private void dismissKeyboard() {
        if (!prompt.hasFocus()) return;
        InputMethodManager keyboard = getSystemService(InputMethodManager.class);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(prompt.getWindowToken(), 0);
        prompt.clearFocus();
    }

    private void showTab(int index) {
        selectedTab = Math.max(Tabs.CHAT, Math.min(index, Tabs.TOOLS));
        if (selectedTab != Tabs.CHAT) dismissKeyboard();
        screenTitle.setText(selectedTab == Tabs.PROJECTS ? "المشروع"
                : selectedTab == Tabs.TOOLS ? "الأدوات" : "MokAiCoder");
        for (int i = 0; i < pages.length; i++) {
            pages[i].setVisibility(i == selectedTab ? View.VISIBLE : View.GONE);
        }
    }

    private void resetChat() {
        history.clear();
        transcript.removeAllViews();
        refreshEmptyChat();
        sessionStatus("");
    }

    private void refreshEmptyChat() {
        empty.setVisibility(transcript.getChildCount() == 0 ? View.VISIBLE : View.GONE);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        state.putInt("screen", selectedTab);
        super.onSaveInstanceState(state);
    }

    @Override public void onBackPressed() {
        if (navigation.getVisibility() == View.VISIBLE) closeMenu();
        else if (selectedTab != Tabs.CHAT) showTab(Tabs.CHAT);
        else super.onBackPressed();
    }

    private ChatProfiles loadProfiles() throws Exception {
        return ChatProfiles.from(connections.read(ChatProfiles.STORAGE_NAME));
    }

    private void connectionDialog() {
        if (active != null) return;
        final ChatProfiles current;
        try {
            current = profiles == null ? loadProfiles() : profiles;
        } catch (Exception unavailable) {
            sessionStatus("تعذّرت قراءة الإعدادات المحفوظة. جرّب مرة أخرى.");
            return;
        }
        LinearLayout fields = column();
        fields.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        fields.setPadding(dp(20), dp(8), dp(20), dp(8));
        Spinner mode = picker(new String[]{"OpenRouter المجاني", "خادم MokAiCoder", "Grok مباشر"});
        mode.setSelection(current.activeMode());
        JSONObject savedServer = current.get(ChatProfiles.SERVER);
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
        grokModel.setText(current.get(ChatProfiles.GROK).optString("model"));
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

        int initialProvider = providerIndex(savedServer.optString("provider", "grok"));
        provider.setSelection(initialProvider);
        String savedReviewer = savedServer.optString("reviewer");
        int initialReviewer = savedReviewer.isEmpty() ? 0 : providerIndex(savedReviewer) + 1;
        reviewer.setSelection(initialReviewer);
        reviewModel.setText(savedServer.optString("reviewModel"));
        reviewFields.setVisibility(initialReviewer == 0 ? View.GONE : View.VISIBLE);
        reviewer.setOnItemSelectedListener(new Selection() {
            private int selection = initialReviewer;
            @Override public void select(int position) {
                reviewFields.setVisibility(position == 0 ? View.GONE : View.VISIBLE);
                if (selection != position && position > 0) reviewModel.setText(DEFAULT_MODELS[position - 1]);
                selection = position;
            }
        });
        model.setText(savedServer.optString("model", DEFAULT_MODELS[initialProvider]));
        address.setText(savedServer.optString("address"));

        Runnable updateMode = () -> {
            int selected = mode.getSelectedItemPosition();
            boolean useServer = selected == ChatProfiles.SERVER;
            boolean useGrok = selected == ChatProfiles.GROK;
            serverFields.setVisibility(useServer ? View.VISIBLE : View.GONE);
            advanced.setVisibility(useServer || useGrok ? View.VISIBLE : View.GONE);
            advanced.setText(advanced.isSelected() ? "إخفاء الإعدادات المتقدمة" : "إعدادات متقدمة");
            serverOptions.setVisibility(useServer && advanced.isSelected() ? View.VISIBLE : View.GONE);
            grokFields.setVisibility(useGrok && advanced.isSelected() ? View.VISIBLE : View.GONE);
            secretLabel.setText(useServer ? "رمز وصول MokAiCoder"
                    : useGrok ? "مفتاح API من xAI" : "مفتاح API من OpenRouter");
            boolean stored = !current.get(selected).optString("secret").isEmpty();
            secret.setHint(stored ? "محفوظ على الهاتف · اتركه فارغًا للاحتفاظ به" : "أدخل المفتاح أو الرمز");
        };
        advanced.setOnClickListener(view -> {
            advanced.setSelected(!advanced.isSelected());
            updateMode.run();
        });
        updateMode.run();
        mode.setOnItemSelectedListener(new Selection() {
            private int previous = current.activeMode();
            @Override public void select(int position) {
                if (position != previous) {
                    secret.getText().clear();
                    error.setText("");
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
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("إعداد الاتصال")
                .setView(dialogScroll)
                .setNegativeButton("إلغاء", null)
                .setNeutralButton("حذف المفتاح المحدد", null)
                .setPositiveButton("حفظ", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                int selected = mode.getSelectedItemPosition();
                if (current.get(selected).optString("secret").isEmpty()) {
                    error.setText("لا يوجد مفتاح محفوظ لهذا المزوّد.");
                } else if (forgetConnection(current, selected)) {
                    dialog.dismiss();
                } else {
                    error.setText("تعذّر حذف المفتاح المحدد.");
                }
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                int selected = mode.getSelectedItemPosition();
                String entered = secret.getText().toString().trim();
                String key = entered.isEmpty() ? current.get(selected).optString("secret") : entered;
                try {
                    if (key.isEmpty()) throw new IllegalArgumentException("أدخل مفتاح API أو رمز الوصول.");
                    JSONObject saved = new JSONObject().put("secret", key);
                    if (selected == ChatProfiles.GROK) {
                        saved.put("model", grokModel.getText().toString());
                    } else if (selected == ChatProfiles.SERVER) {
                        int reviewIndex = reviewer.getSelectedItemPosition();
                        saved.put("address", address.getText().toString())
                                .put("provider", PROVIDERS[provider.getSelectedItemPosition()])
                                .put("model", model.getText().toString())
                                .put("reviewer", reviewIndex == 0 ? "" : PROVIDERS[reviewIndex - 1])
                                .put("reviewModel", reviewModel.getText().toString());
                    }
                    ChatClient.Settings updated = readSettings(saved, selected);
                    ChatProfiles next = current.select(selected, saved);
                    connections.save(ChatProfiles.STORAGE_NAME, next.toJson());
                    profiles = next;
                    settings = updated;
                    resetChat();
                    showConnection();
                    secret.getText().clear();
                    dialog.dismiss();
                    sessionStatus("حُفظت الإعدادات على الهاتف.");
                } catch (IllegalArgumentException invalid) {
                    String message = invalid.getMessage();
                    error.setText(message != null && !message.isEmpty() && message.charAt(0) >= 0x0600
                            ? message : "تحقّق من رابط الخادم أو معرّف النموذج.");
                } catch (Exception unavailable) {
                    error.setText("تعذّر حفظ الإعدادات على الهاتف.");
                }
            });
        });
        dialog.show();
    }

    private ChatClient.Settings readSettings(JSONObject saved, int mode) throws JSONException {
        String key = saved.optString("secret");
        if (key.isEmpty()) return null;
        switch (mode) {
            case ChatProfiles.SERVER:
                return ChatClient.Settings.server(saved.getString("address"), key, saved.getString("provider"),
                        saved.getString("model"), saved.getString("reviewer"), saved.getString("reviewModel"));
            case ChatProfiles.GROK:
                return ChatClient.Settings.grok(key, saved.optString("model"));
            default:
                return ChatClient.Settings.free(key);
        }
    }

    private void showConnection() {
        scanCard.setVisibility(settings != null && settings.server ? View.VISIBLE : View.GONE);
    }

    private boolean forgetConnection(ChatProfiles current, int mode) {
        try {
            ChatProfiles next = current.remove(mode);
            connections.save(ChatProfiles.STORAGE_NAME, next.toJson());
            profiles = next;
            if (current.activeMode() == mode) {
                settings = null;
                resetChat();
                showConnection();
            }
            sessionStatus("حُذف مفتاح المزوّد المحدد.");
            return true;
        } catch (Exception unavailable) {
            return false;
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
        if (question.isEmpty()) {
            prompt.requestFocus();
            return;
        }
        if (settings == null) {
            connectionDialog();
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
        if (problem instanceof UnknownHostException || problem instanceof NoRouteToHostException)
            return "تعذّر الوصول إلى الإنترنت. أعد إرسال رسالتك عند عودة الاتصال.";
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
