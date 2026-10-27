package local.tv.bluehome;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;

/** Fixed, version-scoped entry points observed on one TCL Android TV. */
final class VerifiedLaunchPolicy {
    private static final String MANGO = "com.hunantv.market";
    private static final String MANGO_VERSION = "7.0.912.353.3.TC_TVAPP.0.0_Release";
    private static final int MANGO_CODE = 26011615;
    private static final String MANGO_ACTIVITY = "com.mgtv.tv.jump.view.TclIntentParseActivity";
    private static final String MANGO_URI = "mgtvapp://channel/home?fromInside=2";

    private static final String YOUKU = "com.cibn.tv";
    private static final String YOUKU_VERSION = "13.7.1.30";
    private static final int YOUKU_CODE = 2131370130;
    private static final String YOUKU_ACTIVITY = "com.youku.tv.home.activity.HomeActivity";

    private VerifiedLaunchPolicy() { }

    /** Blocks duplicate remote OK presses without delaying a later hot start. */
    static final class TapGate {
        private String lastPackage;
        private long lastAt;

        boolean accept(String packageName, long now) {
            if (packageName == null) return false;
            if (packageName.equals(lastPackage) && now - lastAt >= 0
                    && now - lastAt < 900) return false;
            lastPackage = packageName;
            lastAt = now;
            return true;
        }
    }

    static boolean supports(String packageName) {
        return MANGO.equals(packageName) || YOUKU.equals(packageName);
    }

    /** Null means use the ordinary launcher entry; never guess a nearby version. */
    static Intent prepare(PackageManager pm, String packageName) {
        if (pm == null || !supports(packageName)) return null;
        boolean mango = MANGO.equals(packageName);
        String version = mango ? MANGO_VERSION : YOUKU_VERSION;
        int code = mango ? MANGO_CODE : YOUKU_CODE;
        ComponentName component = new ComponentName(packageName,
                mango ? MANGO_ACTIVITY : YOUKU_ACTIVITY);
        try {
            PackageInfo installed = pm.getPackageInfo(packageName, 0);
            if (installed == null || installed.versionCode != code
                    || !version.equals(installed.versionName)
                    || installed.applicationInfo == null || !installed.applicationInfo.enabled) {
                return null;
            }
            ActivityInfo entry = pm.getActivityInfo(component, 0);
            if (entry == null || !entry.enabled || !entry.exported
                    || entry.permission != null || entry.applicationInfo == null
                    || !entry.applicationInfo.enabled) return null;
        } catch (PackageManager.NameNotFoundException unavailable) {
            return null;
        } catch (SecurityException unavailable) {
            return null;
        } catch (RuntimeException unavailable) {
            return null;
        }
        if (mango) {
            return new Intent(Intent.ACTION_VIEW).setComponent(component)
                    .setData(Uri.parse(MANGO_URI));
        }
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(component).putExtra("show_welcome", false);
    }
}
