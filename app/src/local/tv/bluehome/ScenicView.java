package local.tv.bluehome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;
import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;

/** A static, replaceable photographic backdrop and once-prepared frosted material. */
final class ScenicView extends View {
    private static final float W=1920f,H=1080f;
    private final Paint imagePaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Paint leftPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bottomPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint topPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glassPaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Paint tintPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF frame=new RectF(0,0,W,H);
    private Bitmap photograph,blurred;

    ScenicView(Context context){
        super(context);setFocusable(false);setClickable(false);
        leftPaint.setShader(new LinearGradient(0,0,W,0,new int[]{0x99000712,0x33000a16,0x00000a16},new float[]{0,.46f,.77f},Shader.TileMode.CLAMP));
        bottomPaint.setShader(new LinearGradient(0,H,0,0,new int[]{0x85000610,0x1f010812,0x05010812,0x2e000812},new float[]{0,.42f,.65f,1},Shader.TileMode.CLAMP));
        topPaint.setShader(new LinearGradient(0,0,0,H,new int[]{0x94000712,0x66000712,0x00000712,0x00000712},new float[]{0,.133333f,.333333f,1},Shader.TileMode.CLAMP));
        tintPaint.setColor(0x401d3349);
        try(InputStream input=context.getAssets().open("default-background.png")){
            BitmapFactory.Options options=new BitmapFactory.Options();options.inScaled=false;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
            Bitmap source=BitmapFactory.decodeStream(input,null,options);
            if(source!=null){Prepared prepared=prepare(source);if(source!=prepared.photograph)source.recycle();applyPrepared(prepared);}
        }catch(IOException ignored){}catch(OutOfMemoryError ignored){blurred=null;glassPaint.setShader(null);}
    }
    void onResume(){} // No continuous wallpaper decoding or drawing while idle.
    void onPause(){}

    /** A fixed bundled JPEG only. This is not a file, URL or Intent import API. */
    static Prepared prepareBuiltin(Context context) throws IOException {
        return prepareAsset(context,"builtin/hidden-lake-cc0.jpg",true);
    }
    static Prepared prepareDefault(Context context) throws IOException {
        try {return prepareAsset(context,"default-background.png",false);}
        catch(IOException unavailable){
            Bitmap solid=Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888);
            solid.eraseColor(0xff07111e);
            try {return prepare(solid);}finally {solid.recycle();}
        }
    }
    /** Bounds and sampled decode are checked before the fixed 1920x1080 preparation. */
    private static Prepared prepareAsset(Context context,String asset,boolean jpegOnly) throws IOException {
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        InputStream input=new BoundedAssetStream(context.getAssets().open(asset));
        try {BitmapFactory.decodeStream(input,null,bounds);}finally {input.close();}
        if(bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>8192||bounds.outHeight>8192
                ||(long)bounds.outWidth*bounds.outHeight>32000000L
                ||(jpegOnly&&!"image/jpeg".equals(bounds.outMimeType)))
            throw new IOException("Unsupported bundled image");
        int sample=1;
        while((bounds.outWidth+sample-1)/sample>2048||(bounds.outHeight+sample-1)/sample>2048
                ||(long)((bounds.outWidth+sample-1)/sample)*((bounds.outHeight+sample-1)/sample)>3000000L)
            sample*=2;
        BitmapFactory.Options options=new BitmapFactory.Options();options.inScaled=false;
        options.inSampleSize=sample;options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        input=new BoundedAssetStream(context.getAssets().open(asset));Bitmap source;
        try {source=BitmapFactory.decodeStream(input,null,options);}finally {input.close();}
        if(source==null)throw new IOException("Bundled image failed decoding");
        try {
            if(source.getWidth()>2048||source.getHeight()>2048||(long)source.getWidth()*source.getHeight()>3000000L)
                throw new IOException("Decoded image exceeds memory budget");
            return prepare(source);
        }finally {source.recycle();}
    }
    private static final class BoundedAssetStream extends FilterInputStream {
        private int remaining=16*1024*1024;
        BoundedAssetStream(InputStream input){super(input);}
        @Override public int read() throws IOException {
            if(remaining==0){if(super.read()!=-1)throw new IOException("Bundled image too large");return -1;}
            int value=super.read();if(value!=-1)remaining--;return value;
        }
        @Override public int read(byte[] bytes,int offset,int length) throws IOException {
            if(length==0)return 0;
            if(remaining==0){if(super.read()!=-1)throw new IOException("Bundled image too large");return -1;}
            int count=in.read(bytes,offset,Math.min(length,remaining));if(count>0)remaining-=count;return count;
        }
        @Override public long skip(long count) throws IOException {
            long skipped=in.skip(Math.min(count,(long)remaining));remaining-=(int)skipped;return skipped;
        }
        @Override public boolean markSupported(){return false;}
    }

    static final class Prepared {
        final Bitmap photograph, blurred;
        Prepared(Bitmap photograph, Bitmap blurred){this.photograph=photograph;this.blurred=blurred;}
        void discard(){if(photograph!=null&&!photograph.isRecycled())photograph.recycle();if(blurred!=null&&!blurred.isRecycled())blurred.recycle();}
    }
    /** Called on the wallpaper worker. Crop without stretching; never blur while drawing. */
    static Prepared prepare(Bitmap source){
        Bitmap photograph=Bitmap.createBitmap((int)W,(int)H,Bitmap.Config.ARGB_8888);
        Canvas target=new Canvas(photograph);target.drawColor(0xff07111e);
        float scale=Math.max(W/source.getWidth(),H/source.getHeight());
        Matrix crop=new Matrix();crop.setScale(scale,scale);
        crop.postTranslate((W-source.getWidth()*scale)/2f,(H-source.getHeight()*scale)/2f);
        target.drawBitmap(source,crop,new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG));
        Bitmap small=Bitmap.createScaledBitmap(photograph,480,270,true);
        Bitmap blurred=blurOnce(small);if(small!=photograph&&small!=blurred)small.recycle();
        return new Prepared(photograph,blurred);
    }
    /** UI thread only. Old display-list bitmaps are released by GC, not recycled in-use. */
    void applyPrepared(Prepared prepared){
        // Allocate before changing the displayed state. A failed shader/Matrix allocation
        // leaves the old bitmaps owned by this view and lets the caller discard this pair.
        BitmapShader material=new BitmapShader(prepared.blurred,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
        Matrix matrix=new Matrix();matrix.setScale(W/prepared.blurred.getWidth(),H/prepared.blurred.getHeight());
        material.setLocalMatrix(matrix);
        invalidate(); // UI-thread invalidation schedules drawing after the following commit.
        glassPaint.setShader(material);
        photograph=prepared.photograph;blurred=prepared.blurred;
    }

    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);canvas.drawColor(0xff07111e);
        canvas.save();canvas.scale(getWidth()/W,getHeight()/H);
        if(photograph!=null)canvas.drawBitmap(photograph,null,frame,imagePaint);
        leftPaint.setAlpha(255);bottomPaint.setAlpha(255);topPaint.setAlpha(255);
        canvas.drawRect(frame,leftPaint);canvas.drawRect(frame,bottomPaint);canvas.drawRect(frame,topPaint);canvas.restore();
    }

    /** Called in the HomeView's 1920x1080 coordinate space; never allocates a frame bitmap. */
    void drawGlass(Canvas canvas,RectF bounds,float radius,float alpha){
        int a=Math.round(255*Math.max(0,Math.min(1,alpha)));
        if(glassPaint.getShader()!=null){glassPaint.setAlpha(a);canvas.drawRoundRect(bounds,radius,radius,glassPaint);}
        leftPaint.setAlpha(a);bottomPaint.setAlpha(a);topPaint.setAlpha(a);canvas.drawRoundRect(bounds,radius,radius,leftPaint);canvas.drawRoundRect(bounds,radius,radius,bottomPaint);canvas.drawRoundRect(bounds,radius,radius,topPaint);
        // Tint intensity is separate from source bitmap alpha.
        tintPaint.setColor((Math.round(64*a/255f)<<24)|0x001d3349);canvas.drawRoundRect(bounds,radius,radius,tintPaint);
    }

    private static Bitmap blurOnce(Bitmap bitmap){
        int w=bitmap.getWidth(),h=bitmap.getHeight();int[] a=new int[w*h],b=new int[w*h];bitmap.getPixels(a,0,w,0,0,w,h);
        for(int pass=0;pass<3;pass++){
            blurPass(a,b,w,h,true,3);blurPass(b,a,w,h,false,3);
        }
        return Bitmap.createBitmap(a,w,h,Bitmap.Config.ARGB_8888);
    }
    private static void blurPass(int[] src,int[] dst,int w,int h,boolean horizontal,int radius){
        int lines=horizontal?h:w,length=horizontal?w:h,div=radius*2+1;
        for(int line=0;line<lines;line++){
            int r=0,g=0,b=0;
            for(int n=-radius;n<=radius;n++){
                int pos=Math.max(0,Math.min(length-1,n));int color=src[horizontal?line*w+pos:pos*w+line];
                r+=(color>>16)&255;g+=(color>>8)&255;b+=color&255;
            }
            for(int pos=0;pos<length;pos++){
                dst[horizontal?line*w+pos:pos*w+line]=0xff000000|((r/div)<<16)|((g/div)<<8)|(b/div);
                int minus=Math.max(0,pos-radius),plus=Math.min(length-1,pos+radius+1);
                int old=src[horizontal?line*w+minus:minus*w+line],next=src[horizontal?line*w+plus:plus*w+line];
                r+=((next>>16)&255)-((old>>16)&255);g+=((next>>8)&255)-((old>>8)&255);b+=(next&255)-(old&255);
            }
        }
    }
}
