package local.tv.bluehome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable, bundled assets; online-cache pruning never visits this catalogue. */
final class OfflineGallery {
    static final class Entry {
        final String id,file,title,sourceType,source;
        Entry(String id,String file,String title,String sourceType,String source){
            this.id=id;this.file=file;this.title=title;this.sourceType=sourceType;this.source=source;
        }
    }
    final int version;
    private final LinkedHashMap<String,Entry> entries;
    private OfflineGallery(int version,LinkedHashMap<String,Entry> entries){this.version=version;this.entries=entries;}
    List<String> ids(){return new ArrayList<String>(entries.keySet());}
    Entry get(String id){return entries.get(id);}
    static OfflineGallery load(Context context) throws IOException {
        try {
            InputStream input=context.getAssets().open("offline-gallery.json");ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            try {byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1){if(bytes.size()+n>65536)throw new IOException("Gallery catalogue too large");bytes.write(buffer,0,n);}}
            finally {input.close();}
            JSONObject root=new JSONObject(new String(bytes.toByteArray(),"UTF-8"));
            int version=root.getInt("version");if(version<1)throw new IOException("Invalid gallery version");
            JSONArray items=root.getJSONArray("items");if(items.length()>OfflineRotationPolicy.MAX_ENTRIES)throw new IOException("Too many gallery entries");
            LinkedHashMap<String,Entry> entries=new LinkedHashMap<String,Entry>();
            for(int index=0;index<items.length();index++){
                JSONObject item=items.getJSONObject(index);String id=item.getString("id"),file=item.getString("file");
                if(!OfflineRotationPolicy.validId(id)||entries.containsKey(id)
                        ||!file.matches("preloaded/[a-zA-Z0-9_-]{1,64}\\.(jpg|jpeg|png|webp)"))
                    throw new IOException("Invalid gallery entry");
                entries.put(id,new Entry(id,file,clean(item.optString("title",id),100),
                        clean(item.optString("sourceType","preloaded"),80),clean(item.optString("source",""),500)));
            }
            return new OfflineGallery(version,entries);
        }catch(IOException error){throw error;}
        catch(Exception invalid){throw new IOException("Invalid gallery catalogue");}
    }
    ScenicView.Prepared prepare(Context context,Entry entry) throws IOException {
        if(entry==null)throw new IOException("Missing gallery entry");
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        InputStream input=context.getAssets().open(entry.file);
        try {BitmapFactory.decodeStream(input,null,bounds);}finally {input.close();}
        if(!WallpaperPolicy.acceptableSize(bounds.outWidth,bounds.outHeight))throw new IOException("Unsupported gallery dimensions");
        BitmapFactory.Options options=new BitmapFactory.Options();options.inScaled=false;
        options.inSampleSize=WallpaperPolicy.sampleSize(bounds.outWidth,bounds.outHeight);options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        input=context.getAssets().open(entry.file);Bitmap bitmap;
        try {bitmap=BitmapFactory.decodeStream(input,null,options);}finally {input.close();}
        if(bitmap==null)throw new IOException("Gallery image failed decoding");
        try {return ScenicView.prepare(bitmap);}finally {bitmap.recycle();}
    }
    private static String clean(String value,int maximum){
        StringBuilder result=new StringBuilder();for(int i=0;i<value.length()&&result.length()<maximum;i++)
            if(!Character.isISOControl(value.charAt(i)))result.append(value.charAt(i));
        return result.toString().trim();
    }
}
