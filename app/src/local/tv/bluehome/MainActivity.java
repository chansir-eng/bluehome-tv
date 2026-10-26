package local.tv.bluehome;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.Toast;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import org.json.JSONException;
import org.json.JSONObject;

/** Native photographic TV home with independent weather and wallpaper workers. */
public class MainActivity extends Activity {
    private static final String SOURCE = "com.tcl.sourcemananger";
    private static final String CAST = "com.tcl.MultiScreenInteraction_TV";
    private static final String STOCK = "com.tcl.cyberui";
    private HomeView home;
    private ScenicView scene;
    private WeatherView weather;
    private WallpaperController wallpapers;
    private boolean clockVisible;
    private final BroadcastReceiver clockChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (home == null) return;
            if (Intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction())) {
                home.setClockTimeZone(intent.getStringExtra("time-zone"));
            }
            // TIME_SET is emitted for a clock correction; do not leave a stale display
            // on screen until the next periodic callback. Hidden homes do no repaint work.
            if (clockVisible) {
                clockHandler.removeCallbacks(clockTick);
                clockTick.run();
            }
        }
    };
    private final BroadcastReceiver packageChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (home != null) home.markAppsDirty();
        }
    };
    private final Handler clockHandler = new Handler();
    private final Runnable clockTick = new Runnable() {
        @Override public void run() {
            if (!clockVisible) return;
            if (home != null) home.updateClock();
            clockHandler.postDelayed(this, home == null ? 1000 : home.nextClockDelay());
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        FrameLayout layers = new FrameLayout(this);
        scene = new ScenicView(this);
        home = new HomeView();
        weather = new WeatherView(this);
        layers.addView(scene, new FrameLayout.LayoutParams(-1, -1));
        layers.addView(home, new FrameLayout.LayoutParams(-1, -1));
        layers.addView(weather, new FrameLayout.LayoutParams(-1, -1));
        setContentView(layers);
        wallpapers=new WallpaperController(this,new WallpaperController.Listener(){
            @Override public void onWallpaper(ScenicView.Prepared prepared){scene.applyPrepared(prepared);home.invalidate();}
        });
        IntentFilter packages = new IntentFilter();
        packages.addAction(Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(Intent.ACTION_PACKAGE_REMOVED);
        packages.addAction(Intent.ACTION_PACKAGE_CHANGED);
        packages.addDataScheme("package");
        registerReceiver(packageChanges, packages);
        IntentFilter timeEvents = new IntentFilter();
        timeEvents.addAction(Intent.ACTION_TIME_CHANGED);
        timeEvents.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        timeEvents.addAction(Intent.ACTION_TIME_TICK);
        // Keep this separate from the package filter: it has no "package:" data URI.
        registerReceiver(clockChanges, timeEvents);
        home.requestFocus();
    }

    @Override protected void onResume() {
        super.onResume();
        scene.onResume();
        wallpapers.onResume();
        weather.onResume();
        home.loadApps();
        home.resumeMotion();
        clockVisible = true;
        clockHandler.removeCallbacks(clockTick);
        clockTick.run();
        home.requestFocus();
    }

    @Override protected void onPause() {
        clockVisible = false;
        wallpapers.onPause();
        clockHandler.removeCallbacks(clockTick);
        home.stopMotion();
        weather.onPause();
        scene.onPause();
        super.onPause();
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        if (intent != null && intent.hasCategory(Intent.CATEGORY_HOME)) home.returnHome();
    }
    @Override protected void onDestroy() {
        if(wallpapers!=null)wallpapers.close();
        unregisterReceiver(packageChanges);
        unregisterReceiver(clockChanges);
        super.onDestroy();
    }
    @Override public void onBackPressed() { if (home != null) home.back(); }
    private void message(String text) { Toast.makeText(this, text, Toast.LENGTH_LONG).show(); }

    private boolean openIntent(Intent intent) {
        if (intent == null) return false;
        try {
            intent = QiyiguoLaunchPolicy.prepare(intent);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) { return false; }
        catch (SecurityException e) { return false; }
        catch (RuntimeException e) { return false; }
    }

    private boolean openPackage(String packageName) {
        PackageManager pm = getPackageManager();
        Intent intent = pm.getLaunchIntentForPackage(packageName);
        if (intent != null && openIntent(intent)) return true;
        Intent query = new Intent(Intent.ACTION_MAIN);
        query.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        query.setPackage(packageName);
        for (ResolveInfo match : pm.queryIntentActivities(query, 0)) {
            Intent explicit = new Intent(Intent.ACTION_MAIN);
            explicit.setComponent(new ComponentName(match.activityInfo.packageName, match.activityInfo.name));
            if (openIntent(explicit)) return true;
        }
        return false;
    }

    private void openSource() {
        Intent menu = new Intent("com.tcl.insrc.SHOW_WINDOW");
        menu.setComponent(new ComponentName(SOURCE, "com.tcl.insrc.ShowWindowService"));
        try {
            if (startService(menu) != null) return;
        } catch (SecurityException ignored) { }
        catch (RuntimeException ignored) { }
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(new ComponentName(SOURCE, "com.tcl.sourcmanager.MainActivity"));
        if (!openIntent(intent) && !openPackage(SOURCE)) message("请按遥控器的“信号源 / 输入”键，选择游戏机的 HDMI 接口。");
    }

    private void openCast() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(new ComponentName(CAST, "com.tcl.tcast.ui.activity.MainActivity"));
        if (!openIntent(intent) && !openPackage(CAST)) message("投屏服务仍保留。请用手机投屏，或从原厂桌面打开投屏设置。");
    }

    private void openSettings() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(new ComponentName("com.tcl.settings", "com.tcl.settings.MainActivity"));
        if (!openIntent(intent) && !openIntent(new Intent(Settings.ACTION_SETTINGS))) message("请使用遥控器的设置键打开电视设置。");
    }

    private void openStock() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setComponent(new ComponentName(STOCK, STOCK + ".MainActivity"));
        if (!openIntent(intent) && !openPackage(STOCK)) message("原厂桌面暂时无法打开，请使用电脑上的恢复原厂桌面文件。");
    }

    private static final class AppEntry {
        String label, packageName, activityName;
        Bitmap icon;
        int color, kind;
        Shader shader;
    }
    private static final class TileState {
        float x, y, size, alpha, fromX, fromY, fromSize, fromAlpha, toX, toY, toSize, toAlpha;
    }

    private final class HomeView extends View {
        private static final float W=1920,H=1080,NORMAL=104,FOCUSED=144,GAP=16,INLINE=136,ROW_Y=172;
        private static final int TOP=0,CONTENT=1,LAUNCH=2;
        private final IconRegistry iconRegistry=new IconRegistry(MainActivity.this);
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        private final Paint textPaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.SUBPIXEL_TEXT_FLAG);
        private final RectF rect=new RectF();
        private final Path path=new Path();
        private final Typeface light=loadLightTypeface();
        private final Typeface medium=Typeface.create("sans-serif-medium",Typeface.NORMAL);
        private final SimpleDateFormat timeFormat=new SimpleDateFormat("HH:mm",Locale.CHINA);
        private final SimpleDateFormat dateFormat=new SimpleDateFormat("MM.dd E",Locale.CHINA);
        private static final long CLOCK_EVIDENCE_GRACE_MS=10L*60L*1000L;
        private final SharedPreferences clockEvidence=getSharedPreferences("clock-trust-v1",MODE_PRIVATE);
        private final SharedPreferences weatherEvidence=getSharedPreferences("weather-v1",MODE_PRIVATE);
        private long assetClockFloor,clockFloor;
        private String clockZoneId;
        private boolean clockReady;
        private String clockText="",dateText="";
        private final PathInterpolator easing=new PathInterpolator(.16f,1f,.3f,1f);
        private final ArrayList<AppEntry> apps=new ArrayList<AppEntry>();
        private final ArrayList<AppEntry> shelf=new ArrayList<AppEntry>();
        private final ArrayList<TileState> tiles=new ArrayList<TileState>();
        private final Handler heroHandler=new Handler();
        private final Runnable heroChange=new Runnable(){@Override public void run(){showHero(true);}};
        private ValueAnimator transition,heroTransition;
        private Bitmap focusGlow,topController,settingsIcon;
        private static final int HALO_PAD=32,HALO_SIZE=208;
        private final Shader cardShade=new LinearGradient(0,0,144,144,new int[]{0x38677a8d,0x47283a4c,0x66182a3c},new float[]{0,.57f,1},Shader.TileMode.CLAMP);
        private final Shader focusedShade=new LinearGradient(0,0,144,144,new int[]{0x4d768a9d,0x5c2a3d50,0x8014273a},new float[]{0,.53f,1},Shader.TileMode.CLAMP);
        private final Shader pillShade=new LinearGradient(0,928,0,984,new int[]{Color.WHITE,0xffeef4fa},null,Shader.TileMode.CLAMP);
        private int area=CONTENT,selected=0,topChoice=0,gridChoice=0,heroIndex=0;
        private float heroProgress=1,viewportScale=1,originX,originY,touchX,touchY;
        private boolean library=false,active=false,appsLoaded=false,appsDirty=true;

        HomeView(){
            super(MainActivity.this);setFocusable(true);setFocusableInTouchMode(true);setSoundEffectsEnabled(true);
            textPaint.setTypeface(light);textPaint.setSubpixelText(true);textPaint.setShadowLayer(1,0,1,0x50000000);
            focusGlow=makeHalo();topController=tightIcon(minimalIcon("@hdmi",1));settingsIcon=tightIcon(minimalIcon("@settings",4));
            readClockSeed();updateClock();
            setContentDescription("蓝境桌面。左右选择应用，下选择启动，上进入分类和设置，确定打开，菜单打开应用库。");
        }

        private Typeface loadLightTypeface(){
            try{return Typeface.createFromAsset(getContext().getAssets(),"NotoSansCJKsc-Light.otf");}
            catch(RuntimeException unavailable){return Typeface.create("sans-serif-light",Typeface.NORMAL);}
        }

        private void readClockSeed(){
            // This is a historical, verified model-data timestamp shipped with the app,
            // never a guessed current date or a fixed release-year cutoff.
            try(InputStream input=getAssets().open("weather-location.json")){
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[1024];int count;
                while((count=input.read(buffer))!=-1){if(bytes.size()+count>8192)throw new IOException("clock seed too large");bytes.write(buffer,0,count);}
                JSONObject seed=new JSONObject(bytes.toString("UTF-8")).optJSONObject("seed");
                if(seed!=null)assetClockFloor=Math.max(0,seed.optLong("timeEpochMs",0));
            }catch(IOException ignored){}catch(JSONException ignored){}
            clockFloor=Math.max(assetClockFloor,clockEvidence.getLong("confirmed-evidence-epoch-ms",0));
        }
        private void refreshClockEvidence(){
            String source=weatherEvidence.getString("source","");long observed=0;
            if("open-meteo".equals(source)||"asset-seed".equals(source))observed=weatherEvidence.getLong("timeEpochMs",0);
            long next=Math.max(clockFloor,Math.max(assetClockFloor,observed));
            // Persist only independently sourced evidence. A bad RTC or a manual future
            // device date must never ratchet this floor upward and poison later boots.
            if(next>0&&next>clockEvidence.getLong("confirmed-evidence-epoch-ms",0))clockEvidence.edit().putLong("confirmed-evidence-epoch-ms",next).apply();
            clockFloor=next;
        }
        void setClockTimeZone(String id){clockZoneId=id==null||id.length()==0?null:id;}
        void updateClock(){
            refreshClockEvidence();long now=System.currentTimeMillis();
            clockReady=clockFloor>0&&now>=clockFloor-CLOCK_EVIDENCE_GRACE_MS;
            if(clockReady){
                TimeZone zone=clockZoneId==null?TimeZone.getDefault():TimeZone.getTimeZone(clockZoneId);
                timeFormat.setTimeZone(zone);dateFormat.setTimeZone(zone);
                Date date=new Date(now);clockText=timeFormat.format(date);dateText=dateFormat.format(date);
            }else{clockText="";dateText="";}
            invalidate();
        }
        long nextClockDelay(){
            if(!clockReady)return 1000; // Vendor clock writes occasionally omit TIME_SET.
            return Math.max(1000,60000-System.currentTimeMillis()%60000);
        }

        void loadApps(){
            if(appsLoaded&&!appsDirty)return;
            if(transition!=null){transition.cancel();transition=null;}
            String remembered=shelf.isEmpty()?"@hdmi":shelf.get(Math.min(selected,shelf.size()-1)).packageName;
            apps.clear();shelf.clear();tiles.clear();PackageManager pm=getPackageManager();Set<String> seen=new HashSet<String>();
            for(String category:new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER,Intent.CATEGORY_LAUNCHER}){
                Intent query=new Intent(Intent.ACTION_MAIN);query.addCategory(category);
                for(ResolveInfo match:pm.queryIntentActivities(query,0)){
                    String pkg=match.activityInfo.packageName;
                    if(!match.activityInfo.enabled||!match.activityInfo.exported||pkg.equals(getPackageName())||!seen.add(pkg))continue;
                    AppEntry app=new AppEntry();app.packageName=pkg;app.activityName=match.activityInfo.name;
                    try{app.label=String.valueOf(match.loadLabel(pm)).replace('\n',' ').replace('\r',' ').replace('\t',' ').trim();}catch(RuntimeException e){app.label=pkg;}
                    if(pkg.equals(SOURCE)){app.kind=1;app.label="游戏主机";}else if(pkg.equals(CAST)){app.kind=3;app.label="投屏";}else if(pkg.equals("com.tcl.settings")){app.kind=4;app.label="设置";}else if(pkg.equals(STOCK)){app.kind=5;app.label="原厂桌面";}
                    try{app.icon=iconRegistry.get(pkg,app.kind,app.label);}catch(RuntimeException e){app.icon=null;}catch(OutOfMemoryError e){app.icon=null;}
                    apps.add(app);
                }
            }
            // Retain explicit routes to television functions and the recovery home,
            // including packages which do not advertise a normal launcher category.
            addLibraryFunction("游戏主机",SOURCE,1);addLibraryFunction("投屏",CAST,3);
            addLibraryFunction("设置","com.tcl.settings",4);addLibraryFunction("原厂桌面",STOCK,5);
            Collections.sort(apps,new Comparator<AppEntry>(){@Override public int compare(AppEntry a,AppEntry b){return a.label.compareToIgnoreCase(b.label);}});
            shelf.add(builtin("游戏主机","@hdmi",1));
            shelf.add(favorite("com.xiaodianshi.tv.yst","哔哩哔哩"));
            shelf.add(favorite("com.tcl.qiyiguo","银河奇异果"));
            shelf.add(favorite("com.golive.cinema","CIBN 同步院线"));
            shelf.add(favorite("com.quark.yun.tv","夸克网盘"));
            shelf.add(builtin("投屏","@cast",3));shelf.add(builtin("应用库","@library",2));
            selected=0;for(int i=0;i<shelf.size();i++)if(shelf.get(i).packageName.equals(remembered))selected=i;
            gridChoice=Math.min(gridChoice,Math.max(0,apps.size()-1));for(int i=0;i<shelf.size();i++)tiles.add(new TileState());
            setTargets();settleTiles();appsLoaded=true;appsDirty=false;showHero(false);updateAccessibility();invalidate();
        }
        void markAppsDirty(){appsDirty=true;}
        private void addLibraryFunction(String label,String pkg,int kind){if(findApp(pkg)==null)apps.add(builtin(label,pkg,kind));}
        private AppEntry favorite(String pkg,String label){AppEntry app=findApp(pkg);return app==null?builtin(label,pkg,0):app;}
        private AppEntry builtin(String label,String id,int kind){AppEntry app=new AppEntry();app.label=label;app.packageName=id;app.kind=kind;app.icon=iconRegistry.get(id,kind,label);return app;}
        private AppEntry findApp(String pkg){for(AppEntry app:apps)if(app.packageName.equals(pkg))return app;return null;}
        private Bitmap minimalIcon(String pkg,int kind){return iconRegistry.get(pkg,kind,null);}
        private Bitmap tightIcon(Bitmap bitmap){
            int w=bitmap.getWidth(),h=bitmap.getHeight(),left=w,top=h,right=-1,bottom=-1;int[] pixels=new int[w*h];bitmap.getPixels(pixels,0,w,0,0,w,h);
            for(int y=0;y<h;y++)for(int x=0;x<w;x++)if((pixels[y*w+x]>>>24)>1){left=Math.min(left,x);right=Math.max(right,x);top=Math.min(top,y);bottom=Math.max(bottom,y);}
            return right>=left?Bitmap.createBitmap(bitmap,left,top,right-left+1,bottom-top+1):bitmap;
        }
        private Bitmap makeHalo(){
            int[] pixels=new int[HALO_SIZE*HALO_SIZE];float center=HALO_SIZE/2f,inner=FOCUSED/2-20;
            for(int y=0;y<HALO_SIZE;y++)for(int x=0;x<HALO_SIZE;x++){
                float qx=Math.abs(x-center)-inner,qy=Math.abs(y-center)-inner;
                float dx=Math.max(qx,0),dy=Math.max(qy,0),d=(float)Math.sqrt(dx*dx+dy*dy)+Math.min(Math.max(qx,qy),0)-20;
                float strength=d<0?0:.26f*(float)Math.exp(-(d-5)*(d-5)/(2*9f*9f));pixels[y*HALO_SIZE+x]=(Math.round(strength*255)<<24)|0x00d7edff;
            }
            return Bitmap.createBitmap(pixels,HALO_SIZE,HALO_SIZE,Bitmap.Config.ARGB_8888);
        }
        @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){viewportScale=Math.min(w/W,h/H);originX=(w-W*viewportScale)/2;originY=(h-H*viewportScale)/2;}
        @Override protected void onDraw(Canvas c){
            super.onDraw(c);c.save();c.translate(originX,originY);c.scale(viewportScale,viewportScale);
            drawTop(c);drawShelf(c);drawHero(c);if(library)drawLibrary(c);c.restore();
        }
        private void drawTop(Canvas c){
            p.setShader(null);p.setStyle(Paint.Style.FILL);p.setColor(Color.WHITE);p.setAlpha(245);
            rect.set(96,60,144,91);c.drawBitmap(topController,null,rect,p);p.setAlpha(255);p.setColor(0xf5ffffff);c.drawRect(96,118,144,120,p);
            p.setColor(0x40f4f8ff);c.drawRect(1590,55,1591,103,p);c.drawRect(1750,55,1751,103,p);
            if(clockReady){text(c,clockText,1620,78,36,0xf5f8fbff,light,Paint.Align.LEFT);text(c,dateText,1620,108,20,0xcfeef5fc,light,Paint.Align.LEFT);}else drawClockPlaceholder(c);
            p.setColor(Color.WHITE);p.setAlpha(230);rect.set(1784,62,1816,94);c.drawBitmap(settingsIcon,null,rect,p);p.setAlpha(255);
            if(area==TOP&&!library){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.5f);p.setColor(0xcceff8ff);rect.set(topChoice==0?84:1772,48,topChoice==0?156:1828,110);c.drawRoundRect(rect,12,12,p);p.setStyle(Paint.Style.FILL);}
        }
        private void drawClockPlaceholder(Canvas c){
            p.setShader(null);p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.5f);p.setColor(0x70ffffff);
            c.drawLine(1620,67,1634,67,p);c.drawLine(1642,67,1656,67,p);c.drawCircle(1667,62,1,p);c.drawCircle(1667,72,1,p);c.drawLine(1678,67,1692,67,p);c.drawLine(1700,67,1714,67,p);
            p.setColor(0x42e2f1ff);c.drawLine(1620,102,1690,102,p);p.setStyle(Paint.Style.FILL);
        }
        private void drawShelf(Canvas c){
            for(int i=0;i<tiles.size();i++)if(i!=selected)drawShelfTile(c,i);
            if(selected<tiles.size())drawShelfTile(c,selected);
        }
        private void drawShelfTile(Canvas c,int i){
            TileState tile=tiles.get(i);boolean chosen=i==selected;
            drawCard(c,shelf.get(i),tile.x,tile.y,tile.size,1,chosen,chosen&&area==CONTENT&&!library);
            if(chosen)text(c,shortTitle(shelf.get(i)),tile.x+tile.size+16,tile.y+tile.size-10,18,area==TOP?0xa0f9fcff:0xedf9fcff,light,Paint.Align.LEFT);
        }
        private void drawCard(Canvas c,AppEntry app,float x,float y,float size,float opacity,boolean selectedCard,boolean keyboardFocus){
            p.setShader(null);p.setStyle(Paint.Style.FILL);float a=opacity*(selectedCard?1:.76f),ratio=size/FOCUSED;
            if(selectedCard){p.setColor(Color.WHITE);p.setAlpha(Math.round(255*opacity*(keyboardFocus?1:.65f)));rect.set(x-HALO_PAD*ratio,y-HALO_PAD*ratio,x+size+HALO_PAD*ratio,y+size+HALO_PAD*ratio);c.drawBitmap(focusGlow,null,rect,p);p.setAlpha(255);}
            rect.set(x,y,x+size,y+size);
            if(MainActivity.this.scene!=null)MainActivity.this.scene.drawGlass(c,rect,20*ratio,a);
            c.save();c.translate(x,y);c.scale(ratio,ratio);rect.set(0,0,144,144);
            p.setShader(selectedCard?focusedShade:cardShade);p.setAlpha(Math.round(255*a));c.drawRoundRect(rect,20,20,p);p.setShader(null);p.setAlpha(255);
            if(app.icon!=null){p.setColor(Color.WHITE);p.setAlpha(Math.round(255*a*(selectedCard?1:.72f)));rect.set(12.96f,12.96f,131.04f,131.04f);c.drawBitmap(app.icon,null,rect,p);p.setAlpha(255);}
            p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1/ratio);p.setColor(alpha(0xffdcedfa,.16f*a));rect.set(.5f,.5f,143.5f,143.5f);c.drawRoundRect(rect,20,20,p);p.setStyle(Paint.Style.FILL);c.restore();
            if(selectedCard){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(2);p.setColor(alpha(Color.WHITE,keyboardFocus?.98f:.56f));rect.set(x-6,y-6,x+size+6,y+size+6);c.drawRoundRect(rect,26*ratio,26*ratio,p);p.setStyle(Paint.Style.FILL);}
        }
        private String title(AppEntry app){String id=app.packageName;if(app.kind==1)return "游戏主机";if(app.kind==2)return "应用库";if(app.kind==3)return "投屏";if(id.equals("com.xiaodianshi.tv.yst"))return "哔哩哔哩";if(id.equals("com.tcl.qiyiguo"))return "银河奇异果";if(id.equals("com.golive.cinema"))return "CIBN 同步院线";if(id.equals("com.quark.yun.tv"))return "夸克网盘";return app.label;}
        private String shortTitle(AppEntry app){if(app.packageName.equals("com.tcl.qiyiguo"))return "奇异果";if(app.packageName.equals("com.golive.cinema"))return "同步院线";return title(app);}
        private String subtitle(AppEntry app){String id=app.packageName;if(app.kind==1)return "选择 HDMI 信号源，开始游戏。";if(app.kind==2)return "已安装的应用，都在这里。";if(app.kind==3)return "让手机内容，来到大屏。";if(id.equals("com.xiaodianshi.tv.yst"))return "哔哩哔哩 (゜-゜)つロ 干杯~";if(id.equals("com.tcl.qiyiguo"))return "电影与剧集，随心选择。";if(id.equals("com.golive.cinema"))return "打开同步院线，选择想看的电影。";if(id.equals("com.quark.yun.tv"))return "打开网盘，播放你的珍藏。";return "打开应用，继续探索。";}
        private void drawHero(Canvas c){
            if(shelf.isEmpty())return;AppEntry app=shelf.get(Math.min(heroIndex,shelf.size()-1));float a=.1f+.9f*heroProgress,offset=8*(1-heroProgress);
            text(c,title(app),96,841+offset,56,alpha(0xfff5f8fb,a),light,Paint.Align.LEFT);
            text(c,subtitle(app),96,891+offset,24,alpha(0xffeef5fc,.79f*a),light,Paint.Align.LEFT);
            p.setShader(null);p.setStyle(Paint.Style.FILL);p.setColor(0x16000000);rect.set(96,935,276,991);c.drawRoundRect(rect,28,28,p);
            if(area==LAUNCH&&!library){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(1.5f);p.setColor(0xdff5fbff);rect.set(91,923,281,989);c.drawRoundRect(rect,33,33,p);p.setStyle(Paint.Style.FILL);}
            p.setAlpha(255);p.setShader(pillShade);rect.set(96,928,276,984);c.drawRoundRect(rect,28,28,p);p.setShader(null);
            text(c,"启动",186,963,20,0xff101b28,medium,Paint.Align.CENTER);
        }
        private void drawLibrary(Canvas c){
            p.setShader(null);p.setStyle(Paint.Style.FILL);p.setColor(0xf007111e);c.drawRect(0,0,W,H,p);text(c,"应用库",96,108,34,0xfff5f8fb,light,Paint.Align.LEFT);
            int page=gridChoice/18,pages=Math.max(1,(apps.size()+17)/18);text(c,(page+1)+" / "+pages,1824,104,20,0xbfeef5fc,light,Paint.Align.RIGHT);
            for(int i=page*18;i<Math.min(page*18+18,apps.size());i++){
                int cell=i%18;float x=102+(cell%6)*286,y=228+(cell/6)*240;AppEntry app=apps.get(i);drawCard(c,app,x,y,144,1,i==gridChoice,i==gridChoice);
                textClipped(c,title(app),x,y+180,22,i==gridChoice?0xfff5f8fb:0xbfeef5fc,232);
            }
        }
        private void text(Canvas c,String value,float x,float y,float size,int color,Typeface font,Paint.Align align){textPaint.setTypeface(font);textPaint.setTextAlign(align);textPaint.setTextSize(size);textPaint.setColor(color);c.drawText(value,x,y,textPaint);}
        private void textClipped(Canvas c,String value,float x,float y,float size,int color,float width){textPaint.setTypeface(light);textPaint.setTextSize(size);int count=textPaint.breakText(value,true,width,null);String shown=count<value.length()?value.substring(0,Math.max(0,count-1))+"…":value;text(c,shown,x,y,size,color,light,Paint.Align.LEFT);}
        private int alpha(int color,float amount){return(color&0x00ffffff)|(Math.round(Math.max(0,Math.min(1,amount))*255)<<24);}
        private float mix(float from,float to,float value){return from+(to-from)*value;}
        private void setTargets(){float x=102;for(int i=0;i<tiles.size();i++){TileState t=tiles.get(i);boolean chosen=i==selected;t.toX=x;t.toY=ROW_Y+(chosen?0:20);t.toSize=chosen?FOCUSED:NORMAL;t.toAlpha=1;x+=(chosen?FOCUSED+INLINE:NORMAL)+GAP;}}
        private void settleTiles(){for(TileState t:tiles){t.x=t.toX;t.y=t.toY;t.size=t.toSize;t.alpha=t.toAlpha;}}
        private void move(){
            if(transition!=null){transition.cancel();transition=null;}for(TileState t:tiles){t.fromX=t.x;t.fromY=t.y;t.fromSize=t.size;}setTargets();
            if(!active){settleTiles();showHero(false);updateAccessibility();invalidate();return;}
            transition=ValueAnimator.ofFloat(0,1);transition.setDuration(240);transition.setInterpolator(easing);transition.addUpdateListener(new ValueAnimator.AnimatorUpdateListener(){@Override public void onAnimationUpdate(ValueAnimator animation){float v=((Float)animation.getAnimatedValue()).floatValue();for(TileState t:tiles){t.x=mix(t.fromX,t.toX,v);t.y=mix(t.fromY,t.toY,v);t.size=mix(t.fromSize,t.toSize,v);}invalidate();}});transition.start();
            heroHandler.removeCallbacks(heroChange);if(area==LAUNCH)showHero(false);else heroHandler.postDelayed(heroChange,150);updateAccessibility();invalidate();
        }
        private void showHero(boolean animate){
            heroIndex=selected;if(heroTransition!=null){heroTransition.cancel();heroTransition=null;}
            if(!active||!animate){heroProgress=1;invalidate();return;}heroProgress=0;heroTransition=ValueAnimator.ofFloat(0,1);heroTransition.setDuration(320);heroTransition.setInterpolator(easing);heroTransition.addUpdateListener(new ValueAnimator.AnimatorUpdateListener(){@Override public void onAnimationUpdate(ValueAnimator a){heroProgress=((Float)a.getAnimatedValue()).floatValue();invalidate();}});heroTransition.start();
        }
        void resumeMotion(){active=true;setTargets();settleTiles();showHero(false);invalidate();}
        void stopMotion(){active=false;heroHandler.removeCallbacks(heroChange);if(transition!=null){transition.cancel();transition=null;}if(heroTransition!=null){heroTransition.cancel();heroTransition=null;}setTargets();settleTiles();heroIndex=selected;heroProgress=1;}
        void returnHome(){library=false;area=CONTENT;selected=0;topChoice=0;move();}
        void back(){if(library){library=false;area=CONTENT;updateAccessibility();invalidate();return;}if(area!=CONTENT){area=CONTENT;updateAccessibility();invalidate();return;}if(selected!=0){selected=0;move();}}
        private void updateAccessibility(){String label=library?(apps.isEmpty()?"应用库，暂无应用":title(apps.get(gridChoice))):area==TOP?(topChoice==0?"游戏分类":"设置"):shelf.isEmpty()?"蓝境桌面":title(shelf.get(selected));if(area==LAUNCH&&!library)label+="，启动";setContentDescription(label+"。左右选择，确定打开，上下切换，返回收起。");}
        private void openLibrary(){if(appsDirty)loadApps();library=true;area=CONTENT;if(!shelf.isEmpty())for(int i=0;i<apps.size();i++)if(apps.get(i).packageName.equals(shelf.get(selected).packageName))gridChoice=i;updateAccessibility();invalidate();}
        private void activate(){if(library){if(!apps.isEmpty())launch(apps.get(gridChoice));return;}if(area==TOP){if(topChoice==1)openSettings();else{area=CONTENT;updateAccessibility();invalidate();}return;}heroHandler.removeCallbacks(heroChange);showHero(false);if(!shelf.isEmpty())launch(shelf.get(selected));}
        private void launch(AppEntry app){
            if(app.kind==1){openSource();return;}if(app.kind==2){openLibrary();return;}if(app.kind==3){openCast();return;}if(app.kind==4){openSettings();return;}if(app.kind==5){openStock();return;}
            if(app.activityName!=null){Intent intent=new Intent(Intent.ACTION_MAIN);intent.addCategory(Intent.CATEGORY_LAUNCHER);intent.setComponent(new ComponentName(app.packageName,app.activityName));if(openIntent(intent))return;}
            if(!openPackage(app.packageName))message("暂时无法打开“"+title(app)+"”。可以从应用库或原厂桌面重试。");
        }
        private void navigate(int key){
            int oldArea=area,oldSelected=selected,oldTop=topChoice,oldGrid=gridChoice;
            if(library){if(!apps.isEmpty()){if(key==KeyEvent.KEYCODE_DPAD_LEFT&&gridChoice>0)gridChoice--;if(key==KeyEvent.KEYCODE_DPAD_RIGHT&&gridChoice+1<apps.size())gridChoice++;if(key==KeyEvent.KEYCODE_DPAD_UP&&gridChoice>=6)gridChoice-=6;if(key==KeyEvent.KEYCODE_DPAD_DOWN&&gridChoice+6<apps.size())gridChoice+=6;else if(key==KeyEvent.KEYCODE_DPAD_DOWN&&gridChoice/6<(apps.size()-1)/6)gridChoice=apps.size()-1;}}
            else if(area==TOP){if(key==KeyEvent.KEYCODE_DPAD_LEFT)topChoice=0;if(key==KeyEvent.KEYCODE_DPAD_RIGHT)topChoice=1;if(key==KeyEvent.KEYCODE_DPAD_DOWN)area=CONTENT;}
            else{if(key==KeyEvent.KEYCODE_DPAD_LEFT&&selected>0)selected--;if(key==KeyEvent.KEYCODE_DPAD_RIGHT&&selected+1<shelf.size())selected++;if(key==KeyEvent.KEYCODE_DPAD_UP){if(area==LAUNCH)area=CONTENT;else{area=TOP;topChoice=0;}}if(key==KeyEvent.KEYCODE_DPAD_DOWN)area=LAUNCH;}
            if(oldArea!=area||oldSelected!=selected||oldTop!=topChoice||oldGrid!=gridChoice){playSoundEffect(android.view.SoundEffectConstants.NAVIGATION_DOWN);if(area==LAUNCH){heroHandler.removeCallbacks(heroChange);showHero(false);}if(oldSelected!=selected)move();else{updateAccessibility();invalidate();}}
        }
        @Override public boolean onKeyDown(int key,KeyEvent event){
            if(key==KeyEvent.KEYCODE_BACK||key==KeyEvent.KEYCODE_ESCAPE||key==KeyEvent.KEYCODE_BUTTON_B){if(event.getRepeatCount()==0)back();return true;}
            if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER||key==KeyEvent.KEYCODE_NUMPAD_ENTER||key==KeyEvent.KEYCODE_BUTTON_A){if(event.getRepeatCount()==0)activate();return true;}
            if(key==KeyEvent.KEYCODE_MENU){if(event.getRepeatCount()==0){if(library)back();else openLibrary();}return true;}
            if(key==KeyEvent.KEYCODE_SETTINGS){if(event.getRepeatCount()==0)openSettings();return true;}
            if(key==KeyEvent.KEYCODE_DPAD_LEFT||key==KeyEvent.KEYCODE_DPAD_RIGHT||key==KeyEvent.KEYCODE_DPAD_UP||key==KeyEvent.KEYCODE_DPAD_DOWN){navigate(key);return true;}return super.onKeyDown(key,event);
        }
        @Override public boolean onTouchEvent(MotionEvent event){
            float x=(event.getX()-originX)/viewportScale,y=(event.getY()-originY)/viewportScale;
            if(event.getAction()==MotionEvent.ACTION_DOWN){touchX=x;touchY=y;return true;}if(event.getAction()!=MotionEvent.ACTION_UP)return true;
            float dx=x-touchX,dy=y-touchY;if(Math.max(Math.abs(dx),Math.abs(dy))>58){navigate(Math.abs(dx)>Math.abs(dy)?(dx<0?KeyEvent.KEYCODE_DPAD_RIGHT:KeyEvent.KEYCODE_DPAD_LEFT):(dy<0?KeyEvent.KEYCODE_DPAD_DOWN:KeyEvent.KEYCODE_DPAD_UP));return true;}
            if(library){int col=(int)((x-96)/286),row=(int)((y-221)/240),index=(gridChoice/18)*18+row*6+col;if(x>=96&&y>=221&&col>=0&&col<6&&row>=0&&row<3&&index<apps.size()){gridChoice=index;activate();updateAccessibility();invalidate();}return true;}
            if(y>=48&&y<=122&&x>=1766&&x<=1834){area=TOP;topChoice=1;activate();return true;}
            if(y>=48&&y<=128&&x>=84&&x<=160){area=CONTENT;updateAccessibility();invalidate();return true;}
            if(x>=90&&x<=282&&y>=920&&y<=992){area=LAUNCH;activate();updateAccessibility();invalidate();return true;}
            for(int i=tiles.size()-1;i>=0;i--){TileState t=tiles.get(i);if(x>=t.x&&x<=t.x+t.size&&y>=t.y&&y<=t.y+t.size){if(i==selected&&area==CONTENT)activate();else{selected=i;area=CONTENT;move();}return true;}}return true;
        }
    }

}
