package xy.mailsenders.mail.smtp.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Performs the full SOCKS5 handshake (RFC 1928 + RFC 1929) on an already-open
 * TCP stream.  Extracted so both Socks5SocketFactory and Socks5SslSocketFactory
 * share a single implementation — no duplication.
 *
 * Call {@link #connect(InputStream, OutputStream, String, int, String, String)}
 * once the raw socket is connected to the proxy.
 */
public final class Socks5Handshake {

    private static final byte VER           = 0x05;
    private static final byte AUTH_NONE     = 0x00;
    private static final byte AUTH_USERPASS = 0x02;
    private static final byte AUTH_FAIL     = (byte) 0xFF;
    private static final byte CMD_CONNECT   = 0x01;
    private static final byte ATYP_DOMAIN   = 0x03;
    private static final byte SUCCESS       = 0x00;
    private static final byte USERPASS_VER  = 0x01;

    private Socks5Handshake() {}

    public static void connect(InputStream in, OutputStream out,
                                String targetHost, int targetPort,
                                String username, String password) throws IOException {
        boolean hasAuth = username != null && !username.isEmpty();

        // Step 1 — greeting
        out.write(hasAuth
                ? new byte[]{ VER, 2, AUTH_NONE, AUTH_USERPASS }
                : new byte[]{ VER, 1, AUTH_NONE });
        out.flush();

        // Step 2 — server picks method
        byte serverVer = (byte) in.read();
        byte method    = (byte) in.read();
        if (serverVer != VER)    throw new IOException("SOCKS5: unexpected server version: " + serverVer);
        if (method == AUTH_FAIL) throw new IOException("SOCKS5: no acceptable auth method offered");

        // Step 3 — authenticate if required
        if (method == AUTH_USERPASS) {
            byte[] u = username.getBytes(StandardCharsets.UTF_8);
            byte[] p = (password != null ? password : "").getBytes(StandardCharsets.UTF_8);
            byte[] msg = new byte[3 + u.length + p.length];
            msg[0] = USERPASS_VER;
            msg[1] = (byte) u.length;
            System.arraycopy(u, 0, msg, 2, u.length);
            msg[2 + u.length] = (byte) p.length;
            System.arraycopy(p, 0, msg, 3 + u.length, p.length);
            out.write(msg);
            out.flush();
            in.read(); // sub-version
            if ((byte) in.read() != SUCCESS)
                throw new IOException("SOCKS5: authentication failed — check proxy credentials");
        }

        // Step 4 — CONNECT request
        byte[] host = targetHost.getBytes(StandardCharsets.UTF_8);
        byte[] req  = new byte[7 + host.length];
        req[0] = VER; req[1] = CMD_CONNECT; req[2] = 0x00; req[3] = ATYP_DOMAIN;
        req[4] = (byte) host.length;
        System.arraycopy(host, 0, req, 5, host.length);
        req[5 + host.length] = (byte) ((targetPort >> 8) & 0xFF);
        req[6 + host.length] = (byte) (targetPort & 0xFF);
        out.write(req);
        out.flush();

        // Step 5 — read reply
        in.read();                  // VER
        byte rep  = (byte) in.read();
        in.read();                  // RSV
        byte atyp = (byte) in.read();
        drainAddress(in, atyp);
        in.readNBytes(2);           // port

        if (rep != SUCCESS) throw new IOException("SOCKS5 CONNECT refused — code 0x"
                + String.format("%02X", rep) + " " + errorMessage(rep));
    }

    private static void drainAddress(InputStream in, byte atyp) throws IOException {
        switch (atyp) {
            case 0x01 -> in.readNBytes(4);
            case 0x03 -> in.readNBytes(in.read());
            case 0x04 -> in.readNBytes(16);
        }
    }

    private static String errorMessage(byte code) {
        return switch (code) {
            case 0x01 -> "(general failure)";
            case 0x02 -> "(not allowed by ruleset)";
            case 0x03 -> "(network unreachable)";
            case 0x04 -> "(host unreachable)";
            case 0x05 -> "(connection refused)";
            case 0x06 -> "(TTL expired)";
            case 0x07 -> "(command not supported)";
            case 0x08 -> "(address type not supported)";
            default   -> "(unknown)";
        };
    }
}
