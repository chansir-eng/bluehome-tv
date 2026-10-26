package local.tv.bluehome;

import android.content.ComponentName;
import android.content.Intent;

/** Applies only to the TCL entry verified in the startup comparison. */
final class QiyiguoLaunchPolicy {
    private static final String PACKAGE_NAME = "com.tcl.qiyiguo";
    private static final String ACTIVITY_NAME = "com.gala.video.app.epg.HomeActivity";
    private static final String EXTRA_NAME = "disable_start_preview";

    private QiyiguoLaunchPolicy() {}

    static Intent prepare(Intent intent) {
        if (intent == null || !Intent.ACTION_MAIN.equals(intent.getAction())) {
            return intent;
        }
        ComponentName component = intent.getComponent();
        if (component == null
                || !PACKAGE_NAME.equals(component.getPackageName())
                || !ACTIVITY_NAME.equals(component.getClassName())) {
            return intent;
        }
        return new Intent(intent).putExtra(EXTRA_NAME, true);
    }
}
