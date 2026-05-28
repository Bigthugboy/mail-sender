package xy.mailsenders.mail.smtp.proxy;

import javax.net.SocketFactory;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * SocketFactory that tunnels through a SOCKS5 proxy (STARTTLS / plain path).
 * Jakarta Mail uses this for port 587.  SSL wrapping happens afterwards
 * inside the mail session — this factory just provides the tunnel socket.
 *
 * Delegates the handshake to {@link Socks5Handshake} — no byte-level
 * protocol code here.
 */
public class Socks5SocketFactory extends SocketFactory {

    private final String proxyHost;
    private final int    proxyPort;
    private final String username;
    private final String password;
    private final int    timeoutMs;

    public Socks5SocketFactory(String proxyHost, int proxyPort,
                               String username, String password, int timeoutMs) {
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.username  = username;
        this.password  = password;
        this.timeoutMs = timeoutMs;
    }

    @Override public Socket createSocket() { return new Socket(); }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
        return tunnel(host, port);
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException {
        return tunnel(host, port);
    }

    @Override
    public Socket createSocket(InetAddress host, int port) throws IOException {
        return tunnel(host.getHostAddress(), port);
    }

    @Override
    public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException {
        return tunnel(host.getHostAddress(), port);
    }

    private Socket tunnel(String targetHost, int targetPort) throws IOException {
        Socket socket = new Socket();
        socket.setSoTimeout(timeoutMs);
        socket.connect(new InetSocketAddress(proxyHost, proxyPort), timeoutMs);
        Socks5Handshake.connect(socket.getInputStream(), socket.getOutputStream(),
                targetHost, targetPort, username, password);
        socket.setSoTimeout(0);
        return socket;
    }
}
