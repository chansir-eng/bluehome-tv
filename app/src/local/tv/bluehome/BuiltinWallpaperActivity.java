package local.tv.bluehome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Non-exported, fixed bundled choices. Never reads incoming Intent data or extras. */
public final class BuiltinWallpaperActivity extends Activity {
    private SharedPreferences prefs;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
                |WindowManager.LayoutParams.FLAG_SECURE);
        prefs=getSharedPreferences(WallpaperController.PREFS,MODE_PRIVATE);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xff07111e);
        LinearLayout column=new LinearLayout(this);column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(dp(56),dp(32),dp(56),dp(32));column.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(column,new ScrollView.LayoutParams(-1,-1));
        column.addView(text("内置壁纸",32,0xfff4f8fc),layout(0,12));
        column.addView(text("山湖 · Hidden Lake",23,0xffe4eef7),layout(0,8));
        column.addView(text("摄影：Joalde · CC0 1.0\n仅选择随应用内置的已审核照片，不读取外部文件或网址。",17,0xffb7c9d9),layout(0,20));
        boolean selected=prefs.getBoolean(WallpaperController.BUILTIN_SELECTED,false);
        column.addView(text(selected?"当前：内置山湖图":"当前：原背景模式",18,0xffd4e8f7),layout(0,14));
        final Button choose=button("选择山湖图");
        final Button restore=button("恢复原背景模式");
        Button back=button("返回桌面");
        column.addView(choose,layout(0,10));column.addView(restore,layout(0,10));column.addView(back,layout(0,20));
        restore.setEnabled(selected);restore.setAlpha(selected?1f:.45f);
        column.addView(text("原背景模式及轮换设置会保留；恢复后仍按原有规则显示。\n通用图片导入属于后续开发计划，本版没有外部导入入口。",16,0xffb7c9d9),layout(0,0));
        choose.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View view){select();}});
        restore.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View view){restore();}});
        back.setOnClickListener(new View.OnClickListener(){@Override public void onClick(View view){finish();}});
        setContentView(scroll);(selected?restore:choose).requestFocus();
    }

    private void select() {
        if(prefs.getBoolean(WallpaperController.BUILTIN_SELECTED,false)){finish();return;}
        // Snapshot describes the previous mode. Its real preferences are left untouched,
        // including credentials, cache metadata, rotation state and any later updates.
        boolean saved=prefs.edit()
                .putBoolean("builtin_previous_use_preloaded",prefs.getBoolean("use_preloaded",true))
                .putBoolean("builtin_previous_preloaded_enabled",prefs.getBoolean("preloaded_enabled",true))
                .putBoolean("builtin_previous_rotate_on_wake",prefs.getBoolean("rotate_on_wake",true))
                .putBoolean("builtin_previous_online_enabled",prefs.getBoolean("enabled",false))
                .putBoolean(WallpaperController.BUILTIN_SELECTED,true)
                .putLong("configuration_revision",nextRevision()).commit();
        finishIfSaved(saved,"已选择山湖图，返回桌面后显示");
    }
    private void restore() {
        if(!prefs.getBoolean(WallpaperController.BUILTIN_SELECTED,false)){finish();return;}
        boolean saved=prefs.edit().remove(WallpaperController.BUILTIN_SELECTED)
                .remove("builtin_previous_use_preloaded").remove("builtin_previous_preloaded_enabled")
                .remove("builtin_previous_rotate_on_wake").remove("builtin_previous_online_enabled")
                .putLong("configuration_revision",nextRevision()).commit();
        finishIfSaved(saved,"已恢复原背景模式");
    }
    private long nextRevision() {
        long current=prefs.getLong("configuration_revision",0L);
        return current==Long.MAX_VALUE?1L:current+1L;
    }
    private void finishIfSaved(boolean saved,String message) {
        Toast.makeText(this,saved?message:"保存失败，请重试",Toast.LENGTH_SHORT).show();
        if(saved)finish();
    }
    private TextView text(String value,int size,int color) {
        TextView result=new TextView(this);result.setText(value);result.setTextSize(size);result.setTextColor(color);
        result.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));result.setLineSpacing(dp(3),1f);return result;
    }
    private Button button(String label) {
        Button result=new Button(this);result.setText(label);result.setTextSize(20);result.setAllCaps(false);
        result.setTextColor(0xfff4f8fc);result.setMinHeight(dp(52));
        result.setPadding(dp(16),dp(10),dp(16),dp(10));
        StateListDrawable states=new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused},shape(0xff2d465c,0xfff4f8fc));
        states.addState(new int[]{android.R.attr.state_pressed},shape(0xff3b5870,0xffe8f3fb));
        states.addState(new int[]{},shape(0xff14283a,0xff3a5064));result.setBackground(states);return result;
    }
    private GradientDrawable shape(int fill,int stroke) {
        GradientDrawable shape=new GradientDrawable();shape.setColor(fill);shape.setCornerRadius(dp(9));
        shape.setStroke(dp(1),stroke);return shape;
    }
    private LinearLayout.LayoutParams layout(int top,int bottom) {
        LinearLayout.LayoutParams result=new LinearLayout.LayoutParams(-1,-2);
        result.topMargin=dp(top);result.bottomMargin=dp(bottom);return result;
    }
    private int dp(float value){return Math.round(value*getResources().getDisplayMetrics().density);}
}
