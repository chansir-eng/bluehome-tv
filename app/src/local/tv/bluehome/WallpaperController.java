package local.tv.bluehome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.PowerManager;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.HttpsURLConnection;
import org.json.JSONArray;
import org.json.JSONObject;

/** Private cache and bounded, serialized Wallhaven I/O. HOME returns never form a new cycle. */
final class WallpaperController {
    static final String PREFS="wallhaven-background-v1";
    static final String ACTION_BOOT_READY="local.tv.bluehome.WALLPAPER_BOOT_READY";
    static final String BUILTIN_SELECTED="builtin_hidden_lake_selected";
    private static final long SUCCESS_BACKOFF_MS=30000L, FAILURE_BACKOFF_MS=300000L;
    private static final int MAX_JSON_BYTES=524288,MAX_THUMB_BYTES=614400,MAX_DOWNLOAD_BYTES=12582912;
    interface Listener {void onWallpaper(ScenicView.Prepared prepared);}
    private final Context context;
    private final SharedPreferences prefs;
    private final File directory;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker=new ThreadPoolExecutor(0,1,10L,TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(),new ThreadFactory(){
        @Override public Thread newThread(Runnable job){Thread t=new Thread(job,"BlueHomeWallpaper");t.setDaemon(true);return t;}
    });
    private Listener listener;
    private volatile boolean closed;
    private boolean active,queued,needsPass,registered;
    private volatile String loadedId="";
    // Read once per serialized worker pass; stale results cannot replace a newer choice.
    private long refreshRevision;
    private OfflineGallery offlineGallery;
    private final Random offlineRandom=new Random();
    private long retryDelay=-1;
    private final Runnable retry=new Runnable(){@Override public void run(){enqueue();}};
    private final BroadcastReceiver events=new BroadcastReceiver(){
        @Override public void onReceive(Context c,Intent event){
            if(closed||event==null)return;
            String action=event.getAction();
            if(Intent.ACTION_SCREEN_OFF.equals(action)){
                prefs.edit().putLong("screen_off_elapsed",SystemClock.elapsedRealtime())
                        .putString("screen_off_boot",bootToken()).commit();
            }else if(Intent.ACTION_SCREEN_ON.equals(action)){
                consumeWake();enqueue();
            }else if(Intent.ACTION_TIME_CHANGED.equals(action)){
                prefs.edit().putLong("network_until_elapsed",0).putInt("attempt_count",0).commit();enqueue();
            }else if(ACTION_BOOT_READY.equals(action)
                    ||ConnectivityManager.CONNECTIVITY_ACTION.equals(action))enqueue();
        }
    };

    WallpaperController(Context context,Listener listener){
        this.context=context.getApplicationContext();this.listener=listener;
        prefs=this.context.getSharedPreferences(PREFS,Context.MODE_PRIVATE);
        directory=new File(this.context.getFilesDir(),"wallhaven");
        IntentFilter filter=new IntentFilter();filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_SCREEN_ON);filter.addAction(ACTION_BOOT_READY);
        filter.addAction(Intent.ACTION_TIME_CHANGED);filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
        this.context.registerReceiver(events,filter);registered=true;
    }
    void onResume(){active=true;PowerManager power=(PowerManager)context.getSystemService(Context.POWER_SERVICE);if(power!=null&&power.isInteractive())consumeWake();enqueue();}
    void onPause(){active=false;main.removeCallbacks(retry);}
    void close(){
        if(closed)return;closed=true;active=false;listener=null;main.removeCallbacksAndMessages(null);
        if(registered){context.unregisterReceiver(events);registered=false;}
        // A running HTTPS operation reaches its bounded timeout and closes in its finally block.
        worker.shutdownNow();
    }
    private void consumeWake(){
        long off=prefs.getLong("screen_off_elapsed",-1),now=SystemClock.elapsedRealtime();
        if(off<0)return;
        String offBoot=prefs.getString("screen_off_boot","");
        SharedPreferences.Editor edit=prefs.edit().remove("screen_off_elapsed").remove("screen_off_boot");
        if(WallpaperPolicy.meaningfulWake(offBoot,bootToken(),off,now,prefs.getBoolean("rotate_on_wake",true)))
            edit.putLong("wake_serial",prefs.getLong("wake_serial",0)+1L);
        edit.commit();
    }
    private void enqueue(){
        main.removeCallbacks(retry);if(closed||!active)return;
        if(queued){needsPass=true;return;}queued=true;needsPass=false;
        worker.execute(new Runnable(){@Override public void run(){
            retryDelay=-1;
            try {refresh();}catch(Exception error){status("暂时无法取得壁纸，保留当前背景。");}
            catch(OutOfMemoryError error){status("图片尺寸过大，保留当前背景。");}
            final long delay=retryDelay;
            main.post(new Runnable(){@Override public void run(){
                queued=false;if(closed)return;
                if(active&&needsPass)enqueue();else if(active&&delay>0)main.postDelayed(retry,delay);
            }});
        }});
    }
    private void refresh() throws Exception {
        if(closed)return;
        refreshRevision=prefs.getLong("configuration_revision",0L);
        if(prefs.getBoolean(BUILTIN_SELECTED,false)){
            if(!"builtin:hidden-lake".equals(loadedId))
                publish("builtin:hidden-lake",ScenicView.prepareBuiltin(context));
            return;
        }
        // Selection never alters the old online/offline preferences. Restore their base
        // backdrop first, then let their existing rotation/cache policy run normally.
        if(loadedId.startsWith("builtin:"))publish("default",ScenicView.prepareDefault(context));
        // The default path never reads a network credential and never opens a connection.
        if(prefs.getBoolean("use_preloaded",true)){refreshPreloaded();return;}
        if(!directory.isDirectory()&&!directory.mkdirs())return;
        final String boot=bootToken();
        final boolean onWake=prefs.getBoolean("rotate_on_wake",true);
        final String cycle=WallpaperPolicy.cycleToken(boot,onWake,prefs.getLong("wake_serial",0L));
        final String key=prefs.getString("key","").trim();
        final String query=prefs.getString("query","").trim();
        final long revision=prefs.getLong("configuration_revision",0);
        final boolean enabled=prefs.getBoolean("enabled",false)&&WallpaperPolicy.validKey(key);
        String current=prefs.getString("current_id","");
        String next=prefs.getString("next_id","");
        if(!query.equals(prefs.getString("next_query",query))){next="";prefs.edit().remove("next_id").remove("next_query").commit();}
        boolean modeChanged=prefs.contains("last_mode")&&prefs.getBoolean("last_mode",true)!=onWake;
        prefs.edit().putBoolean("last_mode",onWake).commit();
        if(modeChanged)prefs.edit().putString("active_cycle",cycle).commit();
        if(enabled&&WallpaperPolicy.isNewCycle(prefs.getString("active_cycle",""),cycle)){
            if(!next.equals(current)&&validCache(next)){
                // Decode before committing metadata: a corrupt staged file must not displace the old cache.
                try {ScenicView.Prepared prepared=prepareCached(next);promote(next);current=next;publish(next,prepared);}
                catch(IOException invalid){file(next).delete();prefs.edit().remove("next_id").remove("next_query").commit();}
                next="";
            }
            // No staged image means retain the last one for this whole cycle, even if HOME is revisited.
            prefs.edit().putString("active_cycle",cycle).commit();
        }
        try {showCurrent(current);}catch(IOException invalid){prefs.edit().remove("current_id").remove("current_url").commit();current="";}
        prune();
        if(!enabled||closed)return;
        next=prefs.getString("next_id","");
        if(validCache(next)&&!next.equals(current))return;
        if(!prefs.getString("attempt_cycle","").equals(cycle))
            prefs.edit().putString("attempt_cycle",cycle).putInt("attempt_count",0).commit();
        int attempts=prefs.getInt("attempt_count",0);
        if(attempts>=3)return;
        long now=SystemClock.elapsedRealtime();
        long until=boot.equals(prefs.getString("network_boot",""))?prefs.getLong("network_until_elapsed",0):0;
        if(until>now){retryDelay=until-now;return;}
        prefs.edit().putInt("attempt_count",attempts+1).putString("network_boot",boot)
                .putLong("network_until_elapsed",now+FAILURE_BACKOFF_MS).commit();
        try {
            Candidate choice=choose(key,query,current,next);
            if(choice==null){status("本次没有合适候选，保留当前背景。");retryDelay=FAILURE_BACKOFF_MS;return;}
            cacheFullImage(choice);
            if(closed||revision!=prefs.getLong("configuration_revision",0)||!prefs.getBoolean("enabled",false))return;
            if(!validCache(current)){
                // First-time setup can display the first verified image; later downloads only stage the next boot.
                promote(choice.id);current=choice.id;showCurrent(current);status("当前背景已更新，正在准备下一张。");retryDelay=SUCCESS_BACKOFF_MS;
            }else{
                prefs.edit().putString("next_id",choice.id).putString("next_query",query).commit();
                status("下一张背景已准备，下次开机或所选唤醒时切换。");
            }
            prefs.edit().putString("network_boot",boot)
                    .putLong("network_until_elapsed",SystemClock.elapsedRealtime()+SUCCESS_BACKOFF_MS).commit();
            prune();
        }catch(HttpFailure failure){
            if(failure.api&&(failure.code==401||failure.code==403)){status("Wallhaven 暂未接受访问，请检查 Key 或稍后重试。");return;}
            if(!failure.api&&(failure.code==401||failure.code==403)){status("图片暂无法访问，保留已缓存背景。");retryDelay=FAILURE_BACKOFF_MS;return;}
            status("网络暂不可用，沿用已缓存背景。");retryDelay=FAILURE_BACKOFF_MS;
        }catch(Exception failure){status("网络或图片验证失败，沿用已缓存背景。");retryDelay=FAILURE_BACKOFF_MS;}
    }
    private void refreshPreloaded(){
        try {
            if(offlineGallery==null)offlineGallery=OfflineGallery.load(context);
            final long revision=prefs.getLong("configuration_revision",0);
            final String cycle=WallpaperPolicy.cycleToken(bootToken(),prefs.getBoolean("rotate_on_wake",true),prefs.getLong("wake_serial",0L));
            final boolean rotate=prefs.getBoolean("preloaded_enabled",true);
            if(prefs.getInt("offline_gallery_version",0)!=offlineGallery.version){
                prefs.edit().putInt("offline_gallery_version",offlineGallery.version).putString("offline_failed_ids","")
                        .putString("offline_remaining_bag","").commit();
            }
            final OfflineRotationPolicy.State prior=new OfflineRotationPolicy.State(prefs.getString("offline_current_id",""),
                    prefs.getString("offline_active_cycle",""),prefs.getString("offline_remaining_bag",""));
            ArrayList<String> available=new ArrayList<String>(offlineGallery.ids());
            ArrayList<String> failed=new ArrayList<String>();
            for(String id:prefs.getString("offline_failed_ids","").split(","))
                if(OfflineRotationPolicy.validId(id)&&available.contains(id)&&!failed.contains(id)){failed.add(id);available.remove(id);}
            // Every failed decode removes one candidate, so even an entirely damaged catalogue terminates.
            while(!closed&&!available.isEmpty()){
                OfflineRotationPolicy.State next=OfflineRotationPolicy.next(prior,available,cycle,rotate,offlineRandom);
                OfflineGallery.Entry entry=offlineGallery.get(next.currentId);if(entry==null)break;
                ScenicView.Prepared prepared=null;
                try {
                    String identity="offline:"+next.currentId;
                    if(!identity.equals(loadedId))prepared=offlineGallery.prepare(context,entry);
                    if(closed||!prefs.getBoolean("use_preloaded",true)||revision!=prefs.getLong("configuration_revision",0)){
                        if(prepared!=null)prepared.discard();return;
                    }
                    boolean saved=prefs.edit().putString("offline_current_id",next.currentId).putString("offline_active_cycle",next.appliedCycle)
                            .putString("offline_remaining_bag",next.remainingBagCsv).putString("offline_current_title",entry.title)
                            .putString("offline_current_source_type",entry.sourceType).putString("offline_current_source",entry.source)
                            .putString("offline_failed_ids",OfflineRotationPolicy.join(failed))
                            .putString("offline_last_status",available.size()>1?(rotate?"本地图库已就绪，无需联网即可循环轮换。":"已暂停轮换，保留当前本地背景。")
                                    :"当前仅一张有效背景，暂时保持此图。").commit();
                    if(!saved){if(prepared!=null)prepared.discard();return;}
                    if(prepared!=null)publish(identity,prepared);return;
                }catch(IOException invalid){
                    if(prepared!=null)prepared.discard();failed.add(next.currentId);available.remove(next.currentId);
                }catch(RuntimeException invalid){
                    if(prepared!=null)prepared.discard();failed.add(next.currentId);available.remove(next.currentId);
                }
            }
            prefs.edit().putString("offline_failed_ids",OfflineRotationPolicy.join(failed))
                    .putString("offline_last_status","本地图库暂无可用图片，保留内置背景。").commit();
        }catch(OutOfMemoryError insufficient){
            prefs.edit().putString("offline_last_status","内存暂不足，保留当前背景。").commit();
        }catch(Exception unavailable){
            prefs.edit().putString("offline_last_status","本地图库尚未就绪，保留内置背景。").commit();
        }
    }
    private void promote(String id){
        prefs.edit().putString("current_id",id).putString("current_url","https://wallhaven.cc/w/"+id)
                .remove("next_id").remove("next_query").commit();
        String old=prefs.getString("recent_ids","");StringBuilder recent=new StringBuilder(id);int count=1;
        for(String item:old.split(","))if(count<20&&WallpaperPolicy.validId(item)&&!item.equals(id)){recent.append(',').append(item);count++;}
        prefs.edit().putString("recent_ids",recent.toString()).commit();
    }
    private void showCurrent(final String id) throws IOException {
        if(closed||id.equals(loadedId)||!validCache(id))return;
        publish(id,prepareCached(id));
    }
    private ScenicView.Prepared prepareCached(String id) throws IOException {
        Bitmap bitmap=decode(file(id));
        if(bitmap==null)throw new IOException("Invalid cached image");
        final ScenicView.Prepared prepared;
        try {prepared=ScenicView.prepare(bitmap);}finally {bitmap.recycle();}
        return prepared;
    }
    private void publish(final String id,final ScenicView.Prepared prepared){
        final long expectedRevision=refreshRevision;
        main.post(new Runnable(){@Override public void run(){
            if(closed||listener==null||expectedRevision!=prefs.getLong("configuration_revision",0L)){
                prepared.discard();return;
            }
            try {listener.onWallpaper(prepared);loadedId=id;}
            catch(OutOfMemoryError insufficient){prepared.discard();}
            catch(RuntimeException invalid){prepared.discard();}
        }});
    }
    private Candidate choose(String key,String query,String current,String next) throws Exception {
        byte[] search;
        try {search=readBytes(new URL(WallpaperPolicy.searchUrl("https://wallhaven.cc",query)),key,MAX_JSON_BYTES);}
        catch(HttpFailure response){throw response;}
        catch(IOException unavailable){throw unavailable;}
        JSONObject json=new JSONObject(new String(search,"UTF-8"));
        JSONArray data=json.getJSONArray("data");List<Candidate> candidates=new ArrayList<Candidate>();
        String recent=","+prefs.getString("recent_ids","")+",";
        for(int i=0;i<data.length()&&candidates.size()<4;i++){
            JSONObject item=data.getJSONObject(i);String id=item.optString("id","");
            if(!WallpaperPolicy.validId(id)||id.equals(current)||id.equals(next)||recent.contains(","+id+","))continue;
            if(!"sfw".equalsIgnoreCase(item.optString("purity",""))||!WallpaperPolicy.acceptableSize(item.optInt("dimension_x"),item.optInt("dimension_y")))continue;
            String type=item.optString("file_type","");
            if(!type.equals("image/jpeg")&&!type.equals("image/png")&&!type.equals("image/webp"))continue;
            if(item.optLong("file_size",0)>MAX_DOWNLOAD_BYTES)continue;
            JSONObject thumbs=item.optJSONObject("thumbs");if(thumbs==null)continue;
            URL image=new URL(item.getString("path")),thumb=new URL(thumbs.getString("small"));
            if(WallpaperPolicy.safeImage(image)&&WallpaperPolicy.safeImage(thumb))candidates.add(new Candidate(id,image,thumb));
        }
        Candidate best=null;double bestScore=.52;int evaluated=0;long thumbnailDeadline=SystemClock.elapsedRealtime()+25000L;
        for(Candidate candidate:candidates){
            requireNetworkCurrent();
            if(closed||SystemClock.elapsedRealtime()>thumbnailDeadline)break;
            Bitmap thumb=null,small=null;
            try {
                byte[] bytes=readBytes(candidate.thumb,null,MAX_THUMB_BYTES);
                requireNetworkCurrent();
                BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
                BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);
                if(bounds.outWidth<8||bounds.outHeight<8||bounds.outWidth>2048||bounds.outHeight>2048)continue;
                BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=Math.max(1,bounds.outWidth/320);
                thumb=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(thumb==null)continue;
                small=Bitmap.createScaledBitmap(thumb,160,90,true);int[] pixels=new int[160*90];small.getPixels(pixels,0,160,0,0,160,90);
                evaluated++;
                double score=WallpaperPolicy.compositionScore(pixels,160,90);
                if(score<bestScore){bestScore=score;best=candidate;}
            }catch(Exception rejected){/* A bad thumbnail cannot discard a valid current wallpaper. */}
            finally {if(small!=null&&small!=thumb)small.recycle();if(thumb!=null)thumb.recycle();}
        }
        if(evaluated==0&&!candidates.isEmpty())throw new IOException("No thumbnail decoded");
        requireNetworkCurrent();
        return best;
    }
    private void cacheFullImage(Candidate candidate) throws Exception {
        requireNetworkCurrent();
        File raw=File.createTempFile("wallhaven-",".tmp",context.getCacheDir());
        try {
            HttpsURLConnection request=open(candidate.image,null);
            try {
                InputStream input=request.getInputStream();
                try {FileOutputStream output=new FileOutputStream(raw);try {copyBounded(input,output,MAX_DOWNLOAD_BYTES);}finally {output.close();}}
                finally {input.close();}
            }finally {request.disconnect();}
            requireNetworkCurrent();
            Bitmap bitmap=decode(raw);if(bitmap==null)throw new IOException("Invalid downloaded image");
            ScenicView.Prepared prepared;
            try {prepared=ScenicView.prepare(bitmap);}finally {bitmap.recycle();}
            try {
                requireNetworkCurrent();
                AtomicFile target=new AtomicFile(file(candidate.id));FileOutputStream output=null;
                try {output=target.startWrite();if(!prepared.photograph.compress(Bitmap.CompressFormat.JPEG,88,output))throw new IOException("Image encoding failed");requireNetworkCurrent();target.finishWrite(output);output=null;}
                finally {if(output!=null)target.failWrite(output);}
                if(file(candidate.id).length()>4194304L){file(candidate.id).delete();throw new IOException("Prepared image too large");}
                Bitmap verified=decode(file(candidate.id));
                if(verified==null){file(candidate.id).delete();throw new IOException("Saved image failed verification");}
                verified.recycle();
            }finally {prepared.discard();}
        }finally {raw.delete();}
    }
    private Bitmap decode(File file) throws IOException {
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);
        if(!WallpaperPolicy.acceptableSize(bounds.outWidth,bounds.outHeight))throw new IOException("Unsupported dimensions");
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=WallpaperPolicy.sampleSize(bounds.outWidth,bounds.outHeight);
        options.inScaled=false;options.inPreferredConfig=Bitmap.Config.ARGB_8888;return BitmapFactory.decodeFile(file.getAbsolutePath(),options);
    }
    private boolean validCache(String id){return WallpaperPolicy.validId(id)&&file(id).isFile()&&file(id).length()>100&&file(id).length()<=4194304L;}
    private File file(String id){return new File(directory,"wh-"+id+".jpg");}
    private void prune(){
        String current=prefs.getString("current_id",""),next=prefs.getString("next_id","");File[] files=directory.listFiles();if(files==null)return;
        for(File file:files)if(file.isFile()&&file.getName().startsWith("wh-")&&!file.equals(file(current))&&!file.equals(file(next)))file.delete();
        File[] temporary=context.getCacheDir().listFiles();if(temporary!=null)for(File temp:temporary)
            if(temp.isFile()&&temp.getName().startsWith("wallhaven-")&&temp.getName().endsWith(".tmp"))temp.delete();
    }
    private byte[] readBytes(URL url,String key,int limit) throws Exception {
        HttpsURLConnection request=open(url,key);
        try {InputStream input=request.getInputStream();try {ByteArrayOutputStream output=new ByteArrayOutputStream();copyBounded(input,output,limit);return output.toByteArray();}finally {input.close();}}
        finally {request.disconnect();}
    }
    private HttpsURLConnection open(URL url,String key) throws Exception {
        requireNetworkCurrent();
        boolean api=WallpaperPolicy.safeApi(url);
        if(!(api||WallpaperPolicy.safeImage(url))||(!api&&key!=null))throw new IOException("Unexpected endpoint");
        HttpsURLConnection request=(HttpsURLConnection)url.openConnection();
        try {
            boolean thumb="th.wallhaven.cc".equalsIgnoreCase(url.getHost());
            request.setConnectTimeout(thumb?3500:8000);request.setReadTimeout(thumb?5000:15000);request.setUseCaches(false);
            request.setInstanceFollowRedirects(false);request.setRequestProperty("User-Agent","BlueHome/3.1 (personal Android TV wallpaper)");
            request.setRequestProperty("Accept",api?"application/json":"image/jpeg,image/png,image/webp");
            request.setRequestProperty("Accept-Encoding","identity");
            if(api&&key!=null&&WallpaperPolicy.validKey(key))request.setRequestProperty("X-API-Key",key);
            WallpaperTls.configure(context,request);requireNetworkCurrent();
            int code=request.getResponseCode();requireNetworkCurrent();if(code!=200)throw new HttpFailure(code,api);
            return request;
        }catch(Exception failure){request.disconnect();throw failure;}
    }
    private void copyBounded(InputStream input,java.io.OutputStream output,int limit) throws IOException {
        byte[] buffer=new byte[16384];int total=0,n;long deadline=SystemClock.elapsedRealtime()+45000L;
        while(true){
            requireNetworkCurrent();
            if(SystemClock.elapsedRealtime()>deadline)throw new IOException("Stopped or timed out");
            n=input.read(buffer);requireNetworkCurrent();if(n==-1)break;
            total+=n;if(total>limit)throw new IOException("Response too large");output.write(buffer,0,n);
        }
        if(total==0)throw new IOException("Empty response");
    }
    /** Called only by the serialized online worker, before new I/O and between reads. */
    private void requireNetworkCurrent() throws IOException {
        if(closed||Thread.currentThread().isInterrupted()
                ||refreshRevision!=prefs.getLong("configuration_revision",0L)
                ||prefs.getBoolean(BUILTIN_SELECTED,false))throw new IOException("Wallpaper selection changed");
    }
    private String bootToken(){
        try {FileInputStream input=new FileInputStream("/proc/sys/kernel/random/boot_id");
            try {byte[] bytes=new byte[80];int n=input.read(bytes);String id=n>0?new String(bytes,0,n,"US-ASCII").trim():"";
                if(id.matches("[a-f0-9-]{36}"))return"boot:"+id;
            }finally {input.close();}
        }catch(Exception unavailable){/* Vendor SELinux may deny /proc access. */}
        return"broadcast:"+prefs.getString("boot_fallback","initial-install");
    }
    private void status(String message){if(!closed)prefs.edit().putString("last_status",message).commit();}
    private static final class Candidate {
        final String id;final URL image,thumb;
        Candidate(String id,URL image,URL thumb){this.id=id;this.image=image;this.thumb=thumb;}
    }
    private static final class HttpFailure extends IOException {
        final int code;final boolean api;HttpFailure(int code,boolean api){super("HTTP status "+code);this.code=code;this.api=api;}
    }
}
