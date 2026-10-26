package local.tv.bluehome;

import android.content.Context;
import java.io.InputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Per-connection TLS 1.2; platform hostname and full certificate validation remain on. */
final class WallpaperTls {
    private WallpaperTls() { }
    static void configure(Context context, HttpsURLConnection request) throws Exception {
        final X509TrustManager system=manager(null);
        X509TrustManager optional=null;
        try {
            InputStream in=context.getAssets().open("isrg-root-x1.pem");
            X509Certificate root;
            try {root=(X509Certificate)CertificateFactory.getInstance("X.509").generateCertificate(in);}
            finally {in.close();}
            StringBuilder hex=new StringBuilder();
            for(byte value:MessageDigest.getInstance("SHA-256").digest(root.getEncoded()))
                hex.append(String.format(java.util.Locale.US,"%02x",value&255));
            if(!"96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6".equals(hex.toString()))
                throw new CertificateException("Unexpected bundled CA");
            root.checkValidity();root.verify(root.getPublicKey());
            if(root.getBasicConstraints()<0||!root.getIssuerX500Principal().equals(root.getSubjectX500Principal()))
                throw new CertificateException("Not a self-signed CA");
            KeyStore roots=KeyStore.getInstance(KeyStore.getDefaultType());roots.load(null,null);
            roots.setCertificateEntry("isrg-x1",root);optional=manager(roots);
        } catch(Exception unavailable) { /* A missing or expired optional root never weakens system trust. */ }
        final X509TrustManager fallback=optional;
        X509TrustManager trust=new X509TrustManager(){
            @Override public X509Certificate[] getAcceptedIssuers(){return system.getAcceptedIssuers();}
            @Override public void checkClientTrusted(X509Certificate[] chain,String auth) throws CertificateException {system.checkClientTrusted(chain,auth);}
            @Override public void checkServerTrusted(X509Certificate[] chain,String auth) throws CertificateException {
                try {system.checkServerTrusted(chain,auth);}
                catch(CertificateException original){if(fallback==null)throw original;fallback.checkServerTrusted(chain,auth);}
            }
        };
        SSLContext tls=SSLContext.getInstance("TLSv1.2");tls.init(null,new TrustManager[]{trust},null);
        request.setSSLSocketFactory(new Tls12(tls.getSocketFactory()));
    }
    private static X509TrustManager manager(KeyStore roots) throws Exception {
        TrustManagerFactory factory=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());factory.init(roots);
        for(TrustManager manager:factory.getTrustManagers())if(manager instanceof X509TrustManager)return(X509TrustManager)manager;
        throw new CertificateException("No platform trust manager");
    }
    private static final class Tls12 extends SSLSocketFactory {
        private final SSLSocketFactory delegate;
        Tls12(SSLSocketFactory delegate){this.delegate=delegate;}
        private Socket enable(Socket socket) throws IOException {
            if(!(socket instanceof SSLSocket))throw new IOException("TLS socket required");
            SSLSocket tls=(SSLSocket)socket;tls.setEnabledProtocols(new String[]{"TLSv1.2"});return tls;
        }
        @Override public String[] getDefaultCipherSuites(){return delegate.getDefaultCipherSuites();}
        @Override public String[] getSupportedCipherSuites(){return delegate.getSupportedCipherSuites();}
        @Override public Socket createSocket(Socket s,String h,int p,boolean c)throws IOException{return enable(delegate.createSocket(s,h,p,c));}
        @Override public Socket createSocket(String h,int p)throws IOException{return enable(delegate.createSocket(h,p));}
        @Override public Socket createSocket(String h,int p,InetAddress a,int l)throws IOException{return enable(delegate.createSocket(h,p,a,l));}
        @Override public Socket createSocket(InetAddress h,int p)throws IOException{return enable(delegate.createSocket(h,p));}
        @Override public Socket createSocket(InetAddress h,int p,InetAddress a,int l)throws IOException{return enable(delegate.createSocket(h,p,a,l));}
    }
}
