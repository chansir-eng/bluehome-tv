package local.tv.bluehome;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import java.util.UUID;

/** Records an OS boot only; no network, image decoding, or activity launch here. */
public final class WallpaperBootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context context,Intent intent){
        if(intent==null||!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction()))return;
        context.getSharedPreferences(WallpaperController.PREFS,Context.MODE_PRIVATE).edit()
                .putString("boot_fallback",UUID.randomUUID().toString()).commit();
        context.sendBroadcast(new Intent(WallpaperController.ACTION_BOOT_READY).setPackage(context.getPackageName()));
    }
}
