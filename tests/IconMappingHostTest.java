package local.tv.bluehome;

final class IconMappingHostTest {
    private static int checks;
    private static void expect(String pkg, int kind, String label, String wanted) {
        String found = IconMapping.assetName(pkg, kind, label);
        checks++;
        if (!wanted.equals(found)) {
            throw new AssertionError(wanted + " != " + found);
        }
    }
    public static void main(String[] args) {
        expect("com.tcl.qiyiguo",0,null,"qiyi");
        expect("com.hunantv.market",0,null,"mango");
        expect("com.cibn.tv",0,null,"coolmiao");
        expect("com.xiaodianshi.tv.yst",0,null,"bili");
        expect("com.golive.cinema",0,null,"golive");
        expect("com.quark.yun.tv",0,null,"quark");
        expect("@hdmi",0,null,"hdmi");
        expect("com.tcl.tv",0,null,"cable");
        expect("com.tcl.tv",0,"settopbox","box");
        expect("@cast",0,null,"cast");
        expect("@library",0,null,"library");
        expect("com.tcl.ui_mediaCenter",0,null,"media");
        expect("com.tcl.appmarket2",0,null,"store");
        expect("com.tcl.wholenetsearch",0,null,"search");
        expect("com.tcl.settings",0,null,"settings");
        expect("com.tcl.cyberui",0,null,"stock");
        expect("com.tcl.common.weather",0,null,"weather");
        expect("com.android.deskclock",0,null,"clock");
        expect("com.android.tcl.messagebox",0,null,"message");
        expect("com.android.music",0,null,"music");
        expect("com.tcl.weixin",0,null,"wechat");
        expect("com.tcl.usercenter",0,null,"account");
        expect("com.tcl.tvweishi",0,null,"shield");
        expect("com.droidlogic.otaupgrade",0,null,"upgrade");
        expect("unknown.example.package",0,null,"generic");
        System.out.println("icon-mapping-checks=" + checks);
    }
}
