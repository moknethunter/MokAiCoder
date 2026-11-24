package org.mokai.coder;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

final class AppUi {
    static final int INK = Color.rgb(20, 41, 35);
    static final int MUTED = Color.rgb(72, 98, 88);
    static final int ACCENT = Color.rgb(19, 102, 77);
    static final int BACKGROUND = Color.rgb(243, 247, 245);
    static final int LINE = Color.rgb(213, 228, 222);
    static final int MINT = Color.rgb(221, 246, 235);
    static final int SURFACE = Color.WHITE;

    private AppUi() {}

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    static GradientDrawable shape(Context context, int fill, int border, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(context, radius));
        if (border != Color.TRANSPARENT) shape.setStroke(dp(context, 1), border);
        return shape;
    }

    static TextView text(Context context, String value, int size, int color, boolean bold) {
        TextView label = new TextView(context);
        label.setText(value);
        label.setTextSize(size);
        label.setTextColor(color);
        label.setTextDirection(View.TEXT_DIRECTION_FIRST_STRONG);
        if (bold) label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return label;
    }

    static Button button(Context context, String label, boolean primary) {
        Button button = new Button(context);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(new ColorStateList(
                new int[][]{{-android.R.attr.state_enabled}, {}},
                new int[]{primary ? 0xFFC7DDD4 : 0xFF637C70, primary ? Color.WHITE : ACCENT}));
        button.setPadding(dp(context, 14), dp(context, 7), dp(context, 14), dp(context, 7));
        button.setMinimumHeight(dp(context, 48));
        button.setMinHeight(dp(context, 48));
        button.setStateListAnimator(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x24176E51),
                shape(context, primary ? ACCENT : SURFACE, primary ? Color.TRANSPARENT : LINE, 13), null));
        return button;
    }

    static void field(EditText field) {
        Context context = field.getContext();
        field.setTextColor(INK);
        field.setHintTextColor(MUTED);
        field.setTextSize(16);
        field.setMinHeight(dp(context, 50));
        field.setPadding(dp(context, 13), dp(context, 11), dp(context, 13), dp(context, 11));
        field.setBackground(shape(context, SURFACE, LINE, 14));
    }

    static LinearLayout page(Activity activity) {
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        content.setBackgroundColor(BACKGROUND);
        int margin = dp(activity, 16);
        content.setPadding(margin, margin, margin, dp(activity, 28));
        return content;
    }

    static LinearLayout.LayoutParams spaced(Context context, int top) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(context, top);
        return layout;
    }

    static void heading(LinearLayout parent, String title) {
        TextView label = text(parent.getContext(), title, 24, INK, true);
        if (Build.VERSION.SDK_INT >= 28) label.setAccessibilityHeading(true);
        parent.addView(label, spaced(parent.getContext(), 4));
    }

    static Drawable menuIcon() {
        return new Drawable() {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            {
                paint.setColor(INK);
                paint.setStrokeWidth(1.8f);
                paint.setStrokeCap(Paint.Cap.ROUND);
            }

            @Override public void draw(Canvas canvas) {
                int state = canvas.save();
                canvas.translate(getBounds().left, getBounds().top);
                canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
                for (int y = 6; y <= 18; y += 6) canvas.drawLine(4, y, 20, y, paint);
                canvas.restoreToCount(state);
            }

            @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
            @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
            @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        };
    }
}
