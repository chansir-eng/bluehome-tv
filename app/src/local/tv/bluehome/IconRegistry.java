package local.tv.bluehome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;

/** One shared decode per asset. No package drawable, icon cropping, or per-frame work. */
final class IconRegistry {
    private final Context context;
    private final HashMap<String,Bitmap> cache=new HashMap<String,Bitmap>();
    IconRegistry(Context context){
        this.context=context.getApplicationContext();
        Bitmap generic=decode("generic");
        if(generic==null)generic=emergencyGeneric();
        cache.put("generic",generic);
    }
    synchronized Bitmap get(String packageId,int kind,String label){
        String name=IconMapping.assetName(packageId,kind,label);
        Bitmap existing=cache.get(name);if(existing!=null)return existing;
        Bitmap bitmap=decode(name);if(bitmap==null)bitmap=cache.get("generic");
        cache.put(name,bitmap);return bitmap;
    }
    private Bitmap decode(String name){
        try(InputStream input=context.getAssets().open("icons-white/"+name+".png")){
            BitmapFactory.Options options=new BitmapFactory.Options();options.inScaled=false;
            options.inPreferredConfig=Bitmap.Config.ARGB_8888;
            Bitmap bitmap=BitmapFactory.decodeStream(input,null,options);
            if(bitmap!=null&&(bitmap.getWidth()!=384||bitmap.getHeight()!=384)){bitmap.recycle();return null;}
            return bitmap;
        }catch(IOException unavailable){return null;}
        catch(RuntimeException invalid){return null;}
        catch(OutOfMemoryError unavailable){return null;}
    }
    /** Only used if the packaged generic PNG is absent or damaged; never restores a colored icon. */
    private Bitmap emergencyGeneric(){
        Bitmap bitmap=Bitmap.createBitmap(384,384,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(bitmap);
        Paint line=new Paint(Paint.ANTI_ALIAS_FLAG);line.setColor(Color.WHITE);line.setStyle(Paint.Style.STROKE);
        line.setStrokeWidth(5f);line.setStrokeCap(Paint.Cap.ROUND);line.setStrokeJoin(Paint.Join.ROUND);
        for(int row=0;row<2;row++)for(int column=0;column<2;column++){
            float x=116+column*84,y=116+row*84;canvas.drawRoundRect(new RectF(x,y,x+68,y+68),9,9,line);
        }
        return bitmap;
    }
}
