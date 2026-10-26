package local.tv.bluehome;

import java.net.URL;
import java.net.URLEncoder;

/** Small, deterministic rules; deliberately performs no person/face recognition. */
final class WallpaperPolicy {
    private WallpaperPolicy() { }
    static boolean validKey(String key) { return key != null && key.matches("[A-Za-z0-9_-]{24,128}"); }
    static boolean validId(String id) { return id != null && id.matches("[a-z0-9]{6,8}"); }
    static boolean acceptableSize(int width, int height) {
        return width >= 1920 && height >= 1080 && width <= 8192 && height <= 8192
                && (long) width * height <= 40000000L
                && Math.abs(width / (double) height - 16.0 / 9.0) < .05;
    }
    static int sampleSize(int width, int height) {
        int sample = 1;
        while (width / (sample * 2) >= 1920 && height / (sample * 2) >= 1080) sample *= 2;
        return sample;
    }
    static boolean safeApi(URL url) {
        return safeTls(url) && ("wallhaven.cc".equalsIgnoreCase(url.getHost()))
                && url.getPath().startsWith("/api/v1/")&&!url.getPath().contains("..");
    }
    static boolean safeImage(URL url) {
        if (!safeTls(url) || url.getPath().contains("..")) return false;
        return ("w.wallhaven.cc".equalsIgnoreCase(url.getHost()) && url.getPath().startsWith("/full/"))
                || ("th.wallhaven.cc".equalsIgnoreCase(url.getHost())
                    && (url.getPath().startsWith("/small/") || url.getPath().startsWith("/lg/")));
    }
    private static boolean safeTls(URL url) {
        return "https".equalsIgnoreCase(url.getProtocol()) && url.getUserInfo() == null
                && (url.getPort() == -1 || url.getPort() == 443) && url.getRef() == null;
    }
    static String searchUrl(String base,String query) throws java.io.UnsupportedEncodingException {
        String address=base+"/api/v1/search?categories=111&purity=100&ratios=16x9&atleast=1920x1080&sorting=random&page=1";
        return query==null||query.trim().length()==0?address:address+"&q="+URLEncoder.encode(query.trim(),"UTF-8");
    }
    static String cycleToken(String boot,boolean onWake,long wakeSerial){return boot+(onWake?":wake:"+wakeSerial:":boot-only");}
    static boolean isNewCycle(String applied,String observed){return observed!=null&&!observed.equals(applied);}
    static boolean meaningfulWake(String offBoot,String nowBoot,long off,long now,boolean onWake){
        return onWake&&off>=0&&now>=off+5000L&&offBoot!=null&&offBoot.equals(nowBoot);
    }
    /** Penalize detail and very bright regions where the existing white UI is drawn. */
    static double compositionScore(int[] pixels, int width, int height) {
        if (pixels == null || width < 8 || height < 8 || pixels.length != width * height) return 99;
        double[] rail = region(pixels, width, height, .04, .14, .62, .32);
        double[] hero = region(pixels, width, height, .04, .66, .40, .92);
        double[] status = region(pixels, width, height, .76, .03, .96, .13);
        return rail[1] * 2.8 + hero[1] * 3.5 + status[1] * 1.2
                + rail[0] * .10 + hero[0] * .16 + status[0] * .08
                + rail[2] * .25 + hero[2] * .25 + status[2] * .15;
    }
    private static double[] region(int[] p, int w, int h, double x0, double y0, double x1, double y1) {
        int left = Math.max(1, (int) (x0 * w)), top = Math.max(1, (int) (y0 * h));
        int right = Math.min(w - 1, (int) (x1 * w)), bottom = Math.min(h - 1, (int) (y1 * h));
        double sum = 0, edges = 0, bright = 0; int count = 0;
        for (int y = top; y < bottom; y++) for (int x = left; x < right; x++) {
            double l = luminance(p[y * w + x]);
            sum += l; if (l > .85) bright++;
            edges += (Math.abs(l - luminance(p[y * w + x - 1]))
                    + Math.abs(l - luminance(p[(y - 1) * w + x]))) / 2;
            count++;
        }
        return count == 0 ? new double[] {1, 1, 1} : new double[] {sum / count, edges / count, bright / count};
    }
    private static double luminance(int color) {
        return (((color >>> 16) & 255) * .2126 + ((color >>> 8) & 255) * .7152
                + (color & 255) * .0722) / 255.0;
    }
}
