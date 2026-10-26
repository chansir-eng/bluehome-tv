package local.tv.bluehome;

import java.util.Locale;

/** Pure identity mapping. Labels only disambiguate vendor entries or cover unknown package aliases. */
final class IconMapping {
    private IconMapping() { }
    static String assetName(String packageId,int kind,String label){
        if(kind==1)return"hdmi";if(kind==2)return"library";if(kind==3)return"cast";
        if(kind==4)return"settings";if(kind==5)return"stock";
        String pkg=packageId==null?"":packageId;
        if(pkg.equals("@hdmi"))return"hdmi";if(pkg.equals("@library"))return"library";
        if(pkg.equals("@cast"))return"cast";if(pkg.equals("@settings"))return"settings";
        if(pkg.equals("@stock"))return"stock";
        String named=labelName(label);
        if(pkg.equals("com.tcl.sourcemananger"))return"hdmi";
        if(pkg.equals("com.tcl.MultiScreenInteraction_TV"))return"cast";
        if(pkg.equals("com.tcl.settings"))return"settings";
        if(pkg.equals("com.tcl.cyberui"))return"stock";
        if(pkg.equals("com.xiaodianshi.tv.yst"))return"bili";
        if(pkg.equals("com.tcl.qiyiguo"))return"qiyi";
        if(pkg.equals("com.golive.cinema"))return"golive";
        if(pkg.equals("com.quark.yun.tv"))return"quark";
        if(pkg.equals("com.tcl.common.weather"))return"weather";
        if(pkg.equals("com.audiocn.kalaok.tv")||pkg.equals("com.android.music")||pkg.equals("com.tcl.audioplayer"))return"music";
        if(pkg.equals("com.tcl.ui_mediaCenter"))return"media";
        if(pkg.equals("com.tcl.appmarket2"))return"store";
        if(pkg.equals("com.tcl.weixin"))return"wechat";
        if(pkg.equals("com.tcl.wholenetsearch"))return"search";
        if(pkg.equals("com.android.deskclock"))return"clock";
        if(pkg.equals("com.android.tcl.messagebox"))return"message";
        if(pkg.equals("com.tcl.usercenter"))return"account";
        if(pkg.equals("com.tcl.tvweishi")||pkg.equals("com.tcl.tvhealthcheck"))return"shield";
        if(pkg.equals("com.droidlogic.otaupgrade")||pkg.equals("com.tcl.versionUpdateApp"))return"upgrade";
        // These vendor TV packages need the resolved activity label to distinguish box setup from live TV.
        if(pkg.equals("com.smit.livevideo")||pkg.equals("com.tcl.tv")||pkg.equals("com.tcl.tliveplay"))return"box".equals(named)?"box":"cable";
        return named==null?"generic":named;
    }
    private static String labelName(String value){
        if(value==null)return null;
        StringBuilder normalized=new StringBuilder();
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(!Character.isWhitespace(c)&&!Character.isISOControl(c)&&c!='&'&&c!='＆'&&c!='-'&&c!='_'&&c!='·')normalized.append(c);
        }
        String name=normalized.toString().toLowerCase(Locale.US);
        if(name.equals("机顶盒设置")||name.equals("机顶盒")||name.equals("settopbox"))return"box";
        if(name.equals("有线频道")||name.equals("有线电视")||name.equals("电视直播"))return"cable";
        if(name.equals("升级备份")||name.equals("升级与备份")||name.equals("系统升级")||name.equals("系统更新"))return"upgrade";
        if(name.equals("哔哩哔哩")||name.equals("云视听小电视")||name.equals("bilibili"))return"bili";
        if(name.equals("银河奇异果")||name.equals("奇异果")||name.equals("奇异果tv"))return"qiyi";
        if(name.equals("cibn同步院线")||name.equals("同步院线"))return"golive";
        if(name.equals("夸克网盘"))return"quark";
        if(name.equals("游戏主机")||name.equals("信源管理"))return"hdmi";
        if(name.equals("投屏"))return"cast";if(name.equals("应用库"))return"library";
        if(name.equals("设置"))return"settings";if(name.equals("原厂桌面"))return"stock";
        if(name.equals("天气预报")||name.equals("天气"))return"weather";
        if(name.equals("天籁k歌")||name.equals("音乐"))return"music";
        if(name.equals("媒体中心"))return"media";if(name.equals("应用商店"))return"store";
        if(name.equals("微信互联"))return"wechat";if(name.equals("搜索"))return"search";
        if(name.equals("时钟"))return"clock";if(name.equals("消息盒子"))return"message";
        if(name.equals("用户中心"))return"account";
        if(name.equals("电视卫士")||name.equals("tvguard"))return"shield";
        return null;
    }
}
