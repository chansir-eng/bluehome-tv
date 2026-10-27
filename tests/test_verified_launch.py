"""Exercise the real entry policy with small Android API stand-ins (no device access)."""
from pathlib import Path
import argparse
import json
import subprocess
import tempfile
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "app/src/local/tv/bluehome"
STUBS = {
    "android/content/ComponentName.java": """package android.content;
public final class ComponentName {
  private final String pkg, cls;
  public ComponentName(String p,String c){pkg=p;cls=c;}
  public String getPackageName(){return pkg;}
  public String getClassName(){return cls;}
}""",
    "android/net/Uri.java": """package android.net;
public final class Uri {
  private final String value;
  private Uri(String v){value=v;}
  public static Uri parse(String v){return new Uri(v);}
  public String toString(){return value;}
}""",
    "android/content/Intent.java": """package android.content;
import android.net.Uri;
import java.util.HashMap;
import java.util.HashSet;
public final class Intent {
  public static final String ACTION_VIEW="android.intent.action.VIEW";
  public static final String ACTION_MAIN="android.intent.action.MAIN";
  public static final String CATEGORY_LAUNCHER="android.intent.category.LAUNCHER";
  public String action; public ComponentName component; public Uri data;
  public HashSet<String> categories=new HashSet<String>();
  public HashMap<String,Object> extras=new HashMap<String,Object>();
  public Intent(String a){action=a;}
  public Intent setComponent(ComponentName c){component=c;return this;}
  public Intent setData(Uri d){data=d;return this;}
  public Intent addCategory(String c){categories.add(c);return this;}
  public Intent putExtra(String k,boolean v){extras.put(k,Boolean.valueOf(v));return this;}
}""",
    "android/content/pm/ApplicationInfo.java": """package android.content.pm;
public class ApplicationInfo { public boolean enabled=true; }""",
    "android/content/pm/PackageInfo.java": """package android.content.pm;
public class PackageInfo {
  public int versionCode; public String versionName;
  public ApplicationInfo applicationInfo=new ApplicationInfo();
}""",
    "android/content/pm/ActivityInfo.java": """package android.content.pm;
public class ActivityInfo {
  public boolean enabled=true, exported=true;
  public String permission;
  public ApplicationInfo applicationInfo=new ApplicationInfo();
}""",
    "android/content/pm/PackageManager.java": """package android.content.pm;
import android.content.ComponentName;
public class PackageManager {
  public static class NameNotFoundException extends Exception {}
  public PackageInfo getPackageInfo(String p,int flags) throws NameNotFoundException {throw new NameNotFoundException();}
  public ActivityInfo getActivityInfo(ComponentName c,int flags) throws NameNotFoundException {throw new NameNotFoundException();}
}""",
    "local/tv/bluehome/LaunchPolicyHostTest.java": """package local.tv.bluehome;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.*;
final class LaunchPolicyHostTest {
  static int checks;
  static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
  static final class FakePM extends PackageManager {
    PackageInfo pkg=new PackageInfo(); ActivityInfo activity=new ActivityInfo();
    boolean missingPackage,missingActivity,denyPackage,denyActivity;
    String seenPackage,seenActivity;
    FakePM(String name,int code){pkg.versionName=name;pkg.versionCode=code;}
    @Override public PackageInfo getPackageInfo(String p,int flags) throws NameNotFoundException {
      seenPackage=p;if(denyPackage)throw new SecurityException();
      if(missingPackage)throw new NameNotFoundException();return pkg;
    }
    @Override public ActivityInfo getActivityInfo(ComponentName c,int flags) throws NameNotFoundException {
      seenActivity=c.getClassName();if(denyActivity)throw new SecurityException();
      if(missingActivity)throw new NameNotFoundException();return activity;
    }
  }
  static void mango(){
    String p="com.hunantv.market";
    FakePM pm=new FakePM("7.0.912.353.3.TC_TVAPP.0.0_Release",26011615);
    Intent i=VerifiedLaunchPolicy.prepare(pm,p);
    check(i!=null,"mango present");check(Intent.ACTION_VIEW.equals(i.action),"mango action");
    check("com.mgtv.tv.jump.view.TclIntentParseActivity".equals(i.component.getClassName()),"mango activity");
    check("mgtvapp://channel/home?fromInside=2".equals(i.data.toString()),"mango URI");
    check(i.extras.isEmpty(),"mango has no extras");
    check(p.equals(pm.seenPackage),"mango package queried");
    check(pm.seenActivity.equals(i.component.getClassName()),"mango component checked");
    pm.pkg.versionCode++;check(VerifiedLaunchPolicy.prepare(pm,p)==null,"mango code mismatch");pm.pkg.versionCode--;
    pm.pkg.versionName="other";check(VerifiedLaunchPolicy.prepare(pm,p)==null,"mango name mismatch");
    pm.pkg.versionName="7.0.912.353.3.TC_TVAPP.0.0_Release";
    unavailable(pm,p);
  }
  static void youku(){
    String p="com.cibn.tv";
    FakePM pm=new FakePM("13.7.1.30",2131370130);
    Intent i=VerifiedLaunchPolicy.prepare(pm,p);
    check(i!=null,"youku present");check(Intent.ACTION_MAIN.equals(i.action),"youku MAIN");
    check(i.categories.contains(Intent.CATEGORY_LAUNCHER),"youku launcher category");
    check("com.youku.tv.home.activity.HomeActivity".equals(i.component.getClassName()),"youku activity");
    check(i.data==null,"youku has no URI");
    check(i.extras.size()==1 && Boolean.FALSE.equals(i.extras.get("show_welcome")),"youku boolean false");
    pm.pkg.versionCode++;check(VerifiedLaunchPolicy.prepare(pm,p)==null,"youku code mismatch");pm.pkg.versionCode--;
    pm.pkg.versionName="other";check(VerifiedLaunchPolicy.prepare(pm,p)==null,"youku name mismatch");
    pm.pkg.versionName="13.7.1.30";
    unavailable(pm,p);
  }
  static void unavailable(FakePM pm,String p){
    pm.missingPackage=true;check(VerifiedLaunchPolicy.prepare(pm,p)==null,"missing package");pm.missingPackage=false;
    pm.denyPackage=true;check(VerifiedLaunchPolicy.prepare(pm,p)==null,"package denied");pm.denyPackage=false;
    pm.missingActivity=true;check(VerifiedLaunchPolicy.prepare(pm,p)==null,"missing component");pm.missingActivity=false;
    pm.denyActivity=true;check(VerifiedLaunchPolicy.prepare(pm,p)==null,"component denied");pm.denyActivity=false;
    pm.activity.exported=false;check(VerifiedLaunchPolicy.prepare(pm,p)==null,"not exported");pm.activity.exported=true;
    pm.activity.enabled=false;check(VerifiedLaunchPolicy.prepare(pm,p)==null,"component disabled");pm.activity.enabled=true;
    pm.activity.permission="private.permission";check(VerifiedLaunchPolicy.prepare(pm,p)==null,"permission required");pm.activity.permission=null;
    pm.pkg.applicationInfo.enabled=false;check(VerifiedLaunchPolicy.prepare(pm,p)==null,"package disabled");pm.pkg.applicationInfo.enabled=true;
    pm.activity.applicationInfo.enabled=false;check(VerifiedLaunchPolicy.prepare(pm,p)==null,"activity app disabled");pm.activity.applicationInfo.enabled=true;
  }
  static void tapGate(){
    VerifiedLaunchPolicy.TapGate g=new VerifiedLaunchPolicy.TapGate();
    check(g.accept("com.cibn.tv",1000),"first remote press");
    check(!g.accept("com.cibn.tv",1001),"repeat press blocked");
    check(!g.accept("com.cibn.tv",1899),"repeat window end");
    check(g.accept("com.cibn.tv",1900),"later hot start allowed");
    check(g.accept("com.hunantv.market",1901),"different app allowed");
    check(g.accept("com.hunantv.market",5),"clock rollback allowed");
    check(!g.accept(null,6),"null package blocked");
  }
  public static void main(String[] unused){
    check(VerifiedLaunchPolicy.supports("com.hunantv.market"),"mango supported");
    check(VerifiedLaunchPolicy.supports("com.cibn.tv"),"youku supported");
    check(!VerifiedLaunchPolicy.supports("com.tcl.qiyiguo"),"qiyiguo unchanged");
    check(!VerifiedLaunchPolicy.supports("com.other"),"unknown untouched");
    check(VerifiedLaunchPolicy.prepare(null,"com.cibn.tv")==null,"missing PM");
    mango();youku();tapGate();
    System.out.println("passed="+checks);
  }
}""",
}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-home", required=True, type=Path)
    args = parser.parse_args()
    work = Path(tempfile.mkdtemp(prefix="test-verified-launch-", dir=ROOT / "build"))
    classes = work / "classes"
    classes.mkdir()
    files = []
    for relative, source in STUBS.items():
        target = work / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(source + "\n", encoding="utf-8")
        files.append(target)
    files.append(SRC / "VerifiedLaunchPolicy.java")
    java = args.java_home / "bin/java.exe"
    javac = args.java_home / "bin/javac.exe"
    subprocess.run([str(javac), "-encoding", "UTF-8", "-source", "8", "-target", "8",
                    "-d", str(classes), *map(str, files)], check=True, capture_output=True)
    result = subprocess.run([str(java), "-cp", str(classes),
                             "local.tv.bluehome.LaunchPolicyHostTest"], check=True,
                            text=True, capture_output=True)
    source = (SRC / "MainActivity.java").read_text(encoding="utf-8")
    ordinary = source.split("private boolean openOrdinaryPackage(", 1)[1].split(
        "private void openSource()", 1)[0]
    launch = source.split("private void launch(AppEntry app,boolean allowVerifiedEntry)", 1)[1].split(
        "private void navigate(int key)", 1)[0]
    manifest = ET.parse(ROOT / "app/AndroidManifest.xml").getroot()
    ns = "{http://schemas.android.com/apk/res/android}"
    wiring = {
        "shelf_verified_library_ordinary": "launch(shelf.get(selected),true)" in source
             and "launch(apps.get(gridChoice),false)" in source,
        "one_fallback_chain": launch.count("if(verified!=null&&openIntent(verified))return;") == 1
             and launch.count("openOrdinaryPackage(app.packageName,app.activityName)") == 1
             and ordinary.count("openIntent(ordinary)") == 1
             and "for(" not in ordinary and "while(" not in ordinary,
        "tap_gate_used": "launchGate.accept(app.packageName,SystemClock.uptimeMillis())" in source,
        "qiyiguo_policy_retained": "intent = QiyiguoLaunchPolicy.prepare(intent);" in source,
        "no_added_privileged_permission": {p.get(ns + "name") for p in manifest.findall("uses-permission")}
             == {"android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE",
                 "android.permission.RECEIVE_BOOT_COMPLETED"},
    }
    if not all(wiring.values()):
        raise AssertionError(wiring)
    print(json.dumps({"java_assertions": int(result.stdout.strip().split("=")[1]),
                      "source_contracts": wiring, "device_tested": False}))


if __name__ == "__main__":
    main()
