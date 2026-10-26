package local.tv.bluehome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.method.PasswordTransformationMethod;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Local-only configuration. No intent extras, logs or saved UI state contain credentials. */
public final class WallpaperSettingsActivity extends Activity {
    private static final int TEXT = 0xfff4f8fc;
    private static final int SECONDARY = 0xffb7c9d9;
    private SharedPreferences preferences;
    private CheckBox preloadedEnabled;
    private CheckBox onlineMode;
    private CheckBox enabled;
    private CheckBox rotateOnWake;
    private LinearLayout onlineSection;
    private EditText keyInput;
    private EditText queryInput;
    private Button removeKeyButton;
    private TextView sourceText;
    private TextView statusText;
    private TextView footerText;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                | WindowManager.LayoutParams.FLAG_SECURE);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        preferences = getSharedPreferences(WallpaperController.PREFS, MODE_PRIVATE);

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(0xff07111e);
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        int screenDp = Math.round(getResources().getDisplayMetrics().widthPixels
                / getResources().getDisplayMetrics().density);
        FrameLayout.LayoutParams scrollLayout = new FrameLayout.LayoutParams(
                dp(Math.min(940, Math.max(320, screenDp - 96))), -1, Gravity.LEFT | Gravity.TOP);
        scrollLayout.leftMargin = dp(48);
        frame.addView(scroll, scrollLayout);

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(4), dp(36), dp(8), dp(36));
        scroll.addView(form, new ScrollView.LayoutParams(-1, -2));
        form.addView(text("背景", 34, TEXT, true), block(0, 14));
        form.addView(text("每次开机换一张，保留蓝境的留白与清晰。", 17, SECONDARY, false),
                block(0, 26));

        int localCount = galleryCount();
        form.addView(text(localCount == 0 ? "本地图库 · 暂无预加载背景"
                : "本地图库 · " + localCount + " 张", 19, TEXT, false), block(0, 14));
        preloadedEnabled = choice("开机 / 唤醒轮换", preferences.getBoolean("preloaded_enabled", true));
        form.addView(preloadedEnabled, block(0, 8));
        rotateOnWake = choice("遥控唤醒时也更换", preferences.getBoolean("rotate_on_wake", true));
        form.addView(rotateOnWake, block(0, 18));
        onlineMode = choice("使用 Wallhaven 在线模式", !preferences.getBoolean("use_preloaded", true));
        form.addView(onlineMode, block(0, 20));

        onlineSection = new LinearLayout(this);
        onlineSection.setOrientation(LinearLayout.VERTICAL);
        form.addView(onlineSection, block(0, 0));
        enabled = choice("启用 Wallhaven 联网轮换", preferences.getBoolean("enabled", false));
        onlineSection.addView(enabled, block(0, 20));

        onlineSection.addView(text("Wallhaven API Key", 19, TEXT, false), block(0, 8));
        keyInput = input();
        keyInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        keyInput.setTransformationMethod(PasswordTransformationMethod.getInstance());
        keyInput.setSaveEnabled(false);
        keyInput.setContentDescription("Wallhaven API Key，输入后隐藏显示");
        // A saved credential is deliberately never inserted into a view or instance state.
        keyInput.setHint(preferences.getString("key", "").length() == 0
                ? "输入你的 API Key" : "已保存，留空保留原 Key");
        onlineSection.addView(keyInput, inputBlock(0, 20));

        onlineSection.addView(text("可选主题", 19, TEXT, false), block(0, 8));
        queryInput = input();
        queryInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        queryInput.setHint("留空可混选，或填写 Wallhaven 搜索条件");
        queryInput.setText(preferences.getString("query", ""));
        queryInput.setSaveEnabled(false);
        onlineSection.addView(queryInput, inputBlock(0, 16));
        onlineSection.addView(text("人物、动漫、插画与风景均可；只选适合公开展示的横向图片。", 15,
                SECONDARY, false), block(0, 24));

        sourceText = text("", 15, SECONDARY, false);
        statusText = text("", 15, SECONDARY, false);
        form.addView(sourceText, block(0, 7));
        form.addView(statusText, block(0, 26));

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        Button save = action("保存");
        Button back = action("返回");
        removeKeyButton = action("移除 Key");
        actions.addView(save, actionBlock(true));
        actions.addView(back, actionBlock(true));
        actions.addView(removeKeyButton, actionBlock(false));
        form.addView(actions, block(0, 16));
        footerText = text("", 15, SECONDARY, false);
        form.addView(footerText, block(0, 0));

        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { save(); }
        });
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) { finish(); }
        });
        removeKeyButton.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View view) {
                preferences.edit().remove("key").putBoolean("enabled", false)
                        .putLong("configuration_revision", nextRevision()).apply();
                keyInput.setText("");
                Toast.makeText(WallpaperSettingsActivity.this, "Key 已移除，保留当前背景", Toast.LENGTH_SHORT).show();
                finish();
            }
        });
        onlineMode.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(CompoundButton button, boolean checked) { updateMode(); }
        });
        updateMode();
        setContentView(frame);
        (onlineMode.isChecked() ? onlineMode : preloadedEnabled).requestFocus();
    }

    private void save() {
        boolean online = onlineMode.isChecked();
        String entered = keyInput.getText().toString().trim();
        if (online && entered.length() != 0 && !entered.matches("[A-Za-z0-9_-]{24,128}")) {
            Toast.makeText(this, "请检查 API Key 的格式", Toast.LENGTH_LONG).show();
            keyInput.requestFocus();
            return;
        }
        String saved = preferences.getString("key", "");
        if (online && enabled.isChecked() && entered.length() == 0 && saved.length() == 0) {
            Toast.makeText(this, "请先填写 Wallhaven API Key", Toast.LENGTH_LONG).show();
            keyInput.requestFocus();
            return;
        }
        String query = queryInput.getText().toString().trim();
        if (online && (query.length() > 200 || containsControl(query))) {
            Toast.makeText(this, "主题请控制在 200 字以内，使用单行文字", Toast.LENGTH_LONG).show();
            queryInput.requestFocus();
            return;
        }
        SharedPreferences.Editor editor = preferences.edit()
                .putBoolean("use_preloaded", !online)
                .putBoolean("preloaded_enabled", preloadedEnabled.isChecked())
                .putBoolean("enabled", enabled.isChecked())
                .putBoolean("rotate_on_wake", rotateOnWake.isChecked())
                .putLong("configuration_revision", nextRevision());
        if (online) editor.putString("query", query);
        if (online && entered.length() != 0) editor.putString("key", entered);
        editor.apply();
        keyInput.setText("");
        Toast.makeText(this, "背景设置已保存", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void updateMode() {
        boolean online = onlineMode.isChecked();
        onlineSection.setVisibility(online ? View.VISIBLE : View.GONE);
        preloadedEnabled.setVisibility(online ? View.GONE : View.VISIBLE);
        removeKeyButton.setVisibility(online ? View.VISIBLE : View.GONE);
        if (online) {
            String id = preferences.getString("current_id", "");
            String source = "来源：Wallhaven";
            if (id.matches("[A-Za-z0-9]{6,8}")) source += "  ·  当前图片 " + id;
            sourceText.setText(source);
            statusText.setText(safeStatus(preferences.getString("last_status", "尚未启用")));
            footerText.setText("下载失败时沿用当前背景，返回主页不会额外换图。");
        } else {
            String title = safeMetadata(preferences.getString("offline_current_title", ""), 60);
            String type = safeMetadata(preferences.getString("offline_current_source_type", ""), 36);
            String source = safeMetadata(preferences.getString("offline_current_source", ""), 100);
            String line = "来源：本地图库";
            if (title.length() != 0) line += "  ·  " + title;
            if (type.length() != 0) line += "\n" + type;
            if (source.length() != 0) line += (type.length() == 0 ? "\n" : " · ") + source;
            sourceText.setText(line);
            statusText.setText(safeStatus(preferences.getString("offline_last_status", "返回蓝境后选择本地背景")));
            footerText.setText("本地图库无需联网，返回主页不会额外换图。");
        }
    }

    private int galleryCount() {
        try {return OfflineGallery.load(this).ids().size();}
        catch (Exception ignored) {return 0;}
    }

    private static String safeMetadata(String value, int maximum) {
        if (value == null) return "";
        StringBuilder clean = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!Character.isISOControl(c)) clean.append(c);
            if (clean.length() > maximum + 1) break;
        }
        String result = clean.toString().trim();
        // Public source URLs need no query or fragment; neither can leak a credential here.
        if (result.indexOf("://") >= 0) {
            int query = result.indexOf('?'), fragment = result.indexOf('#');
            int end = query < 0 ? result.length() : query;
            if (fragment >= 0) end = Math.min(end, fragment);
            result = result.substring(0, end);
        } else if (result.matches("[A-Za-z0-9_-]{24,}")) return "";
        return result.length() > maximum ? result.substring(0, maximum) + "…" : result;
    }

    private long nextRevision() {
        long revision = preferences.getLong("configuration_revision", 0L);
        return revision == Long.MAX_VALUE ? 1L : revision + 1L;
    }

    private static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return true;
        return false;
    }

    private static String safeStatus(String status) {
        // Controller status is non-secret. Still refuse accidental credential-like strings.
        if (status == null || status.length() > 180 || containsControl(status)
                || status.matches(".*[A-Za-z0-9_-]{24,}.*") || status.indexOf("://") >= 0) {
            return "返回蓝境后更新背景状态";
        }
        return status;
    }

    private TextView text(String content, float size, int color, boolean light) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(light ? "sans-serif-light" : "sans-serif", Typeface.NORMAL));
        view.setLineSpacing(dp(3), 1f);
        return view;
    }

    private CheckBox choice(String label, boolean checked) {
        CheckBox view = new CheckBox(this);
        view.setText(label);
        view.setTextSize(20);
        view.setTextColor(TEXT);
        view.setChecked(checked);
        view.setPadding(dp(14), dp(8), dp(14), dp(8));
        view.setMinHeight(dp(52));
        view.setButtonTintList(new ColorStateList(new int[][]{
                new int[]{android.R.attr.state_checked}, new int[]{}}, new int[]{TEXT, SECONDARY}));
        view.setBackground(focusBackground(false));
        return view;
    }

    private EditText input() {
        EditText view = new EditText(this);
        view.setSingleLine(true);
        view.setTextSize(19);
        view.setTextColor(TEXT);
        view.setHintTextColor(0xff8fa8bd);
        view.setPadding(dp(16), dp(10), dp(16), dp(10));
        view.setBackground(focusBackground(true));
        return view;
    }

    private Button action(String label) {
        Button view = new Button(this);
        view.setText(label);
        view.setTextSize(18);
        view.setTextColor(TEXT);
        view.setAllCaps(false);
        view.setMinHeight(dp(52));
        view.setPadding(dp(10), dp(8), dp(10), dp(8));
        view.setBackground(focusBackground(true));
        return view;
    }

    private StateListDrawable focusBackground(boolean restingFill) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, shape(0xff2d465c, 0xfff4f8fc));
        states.addState(new int[]{android.R.attr.state_pressed}, shape(0xff3b5870, 0xffe8f3fb));
        states.addState(new int[]{}, shape(restingFill ? 0xff14283a : Color.TRANSPARENT,
                restingFill ? 0xff3a5064 : Color.TRANSPARENT));
        return states;
    }

    private GradientDrawable shape(int fill, int stroke) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(9));
        drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams block(int top, int bottom) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2);
        layout.topMargin = dp(top);
        layout.bottomMargin = dp(bottom);
        return layout;
    }

    private LinearLayout.LayoutParams inputBlock(int top, int bottom) {
        LinearLayout.LayoutParams layout = block(top, bottom);
        layout.height = dp(56);
        return layout;
    }

    private LinearLayout.LayoutParams actionBlock(boolean gap) {
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(0, dp(54), 1f);
        if (gap) layout.rightMargin = dp(14);
        return layout;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
