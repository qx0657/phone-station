package dev.phonestation.adbkeep;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Locale;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/** Same SPKI pin and standard hostname verification for HTTPS and WSS. */
final class RelayTls {
    static SSLContext context(final String expectedPin) throws IOException {
        try {
            X509TrustManager manager = new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException {
                    throw new CertificateException("client certificate is not expected");
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType)
                        throws CertificateException {
                    if (chain == null || chain.length == 0) {
                        throw new CertificateException("relay certificate is missing");
                    }
                    try {
                        byte[] digest = MessageDigest.getInstance("SHA-256")
                                .digest(chain[0].getPublicKey().getEncoded());
                        StringBuilder hex = new StringBuilder(digest.length * 2);
                        for (byte item : digest) {
                            hex.append(String.format(Locale.US, "%02x", item & 0xff));
                        }
                        if (!MessageDigest.isEqual(
                                hex.toString().getBytes(StandardCharsets.US_ASCII),
                                expectedPin.getBytes(StandardCharsets.US_ASCII))) {
                            throw new CertificateException("relay certificate pin did not match");
                        }
                    } catch (CertificateException error) {
                        throw error;
                    } catch (Exception error) {
                        throw new CertificateException("cannot verify relay certificate", error);
                    }
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            };
            SSLContext ssl = SSLContext.getInstance("TLS");
            ssl.init(null, new TrustManager[] {manager}, new SecureRandom());
            return ssl;
        } catch (Exception error) {
            throw new IOException("cannot configure relay TLS", error);
        }
    }

}
