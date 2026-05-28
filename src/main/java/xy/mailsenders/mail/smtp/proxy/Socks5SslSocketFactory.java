package xy.mailsenders.mail.smtp.proxy;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;

/**
 * SSLSocketFactory that opens a SOCKS5 tunnel first, then wraps it in SSL.
 * Used for port 465 (implicit SSL) through a proxy — the SMTP server sees
 * SSL, the proxy sees only opaque encrypted bytes.
 *
 * Jakarta Mail accepts this under mail.smtps.socketFactory.
 * The handshake is delegated to {@link Socks5Handshake}.
 */
public class Socks5SslSocketFactory extends SSLSocketFactory {

    private final String           proxyHost;
    private final int              proxyPort;
    private final String           username;
    private final String           password;
    private final String           smtpHost;   // for SSL SNI
    private final int              timeoutMs;
    private final SSLSocketFactory delegate;

    public Socks5SslSocketFactory(String proxyHost, int proxyPort,
                                  String username, String password,
                                  String smtpHost, int timeoutMs) {
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.username  = username;
        this.password  = password;
        this.smtpHost  = smtpHost;
        this.timeoutMs = timeoutMs;
        this.delegate  = (SSLSocketFactory) SSLSocketFactory.getDefault();
    }

    @Override public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }
    @Override public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }

    @Override
    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
        return delegate.createSocket(s, host, port, autoClose);
    }

    @Override public Socket createSocket(String host, int port) throws IOException { return tunnelThenSsl(host, port); }
    @Override public Socket createSocket(String h, int p, InetAddress l, int lp) throws IOException { return tunnelThenSsl(h, p); }
    @Override public Socket createSocket(InetAddress h, int p) throws IOException { return tunnelThenSsl(h.getHostAddress(), p); }
    @Override public Socket createSocket(InetAddress h, int p, InetAddress l, int lp) throws IOException { return tunnelThenSsl(h.getHostAddress(), p); }

    private Socket tunnelThenSsl(String targetHost, int targetPort) throws IOException {
        // Step 1 — raw TCP to proxy + SOCKS5 handshake
        Socket raw = new Socket();
        raw.setSoTimeout(timeoutMs);
        raw.connect(new InetSocketAddress(proxyHost, proxyPort), timeoutMs);
        Socks5Handshake.connect(raw.getInputStream(), raw.getOutputStream(),
                targetHost, targetPort, username, password);
        raw.setSoTimeout(0);

        // Step 2 — SSL over the tunnel with SNI
        SSLSocket ssl = (SSLSocket) delegate.createSocket(raw, smtpHost, targetPort, true);
        ssl.setUseClientMode(true);
        SSLParameters params = ssl.getSSLParameters();
        params.setServerNames(List.of(new SNIHostName(smtpHost)));
        ssl.setSSLParameters(params);
        ssl.startHandshake();
        return ssl;
    }
}
