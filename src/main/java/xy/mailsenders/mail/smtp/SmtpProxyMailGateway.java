package xy.mailsenders.mail.smtp;

import jakarta.mail.*;
import jakarta.mail.Authenticator;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.internet.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import xy.mailsenders.mail.config.MailSendingProperties;
import xy.mailsenders.mail.domain.MailAttachment;
import xy.mailsenders.mail.domain.MailFailure;
import xy.mailsenders.mail.domain.MailPayload;
import xy.mailsenders.service.MailGateway;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * SMTP Mail Gateway with SOCKS5/HTTP proxy support.
 *
 * SOCKS5 AUTH FIX:
 *   Jakarta Mail's mail.smtp.socks.host uses Java's SocksSocketImpl which
 *   ignores java.net.Authenticator for credentials. The only reliable ways are:
 *
 *   1. System properties java.net.socks.username/password — but these are
 *      GLOBAL, causing race conditions with proxy rotation.
 *
 *   2. Open the raw SOCKS5 socket ourselves (with proper auth handshake),
 *      then hand it to Jakarta Mail via mail.smtp.socketFactory — BUT
 *      SocketFetcher still bypasses it when socks.host is set.
 *
 *   3. [USED HERE] Open the Transport manually using connect(host, port, user, pass)
 *      after setting a custom SocketFactory that builds SOCKS5 sockets with auth.
 *      We set mail.smtp.socketFactory.* but do NOT set mail.smtp.socks.* so
 *      SocketFetcher uses our factory instead of its own SOCKS handler.
 *
 *   The custom Socks5SocketFactory performs the SOCKS5 handshake manually
 *   (RFC 1928 + RFC 1929) so credentials are sent correctly per-connection,
 *   with no global state — safe for concurrent proxy rotation.
 */
@Slf4j
@Service
public class SmtpProxyMailGateway implements MailGateway {

    private static final int MAX_CONCURRENCY = 50;

    private final MailSendingProperties properties;
    private final SmtpProperties        smtpProperties;
    private final List<SmtpServer>      serverPool;
    private final AtomicInteger         serverIndex = new AtomicInteger(0);

    public SmtpProxyMailGateway(MailSendingProperties properties, SmtpProperties smtpProperties) {
        this.properties     = properties;
        this.smtpProperties = smtpProperties;
        this.serverPool     = buildServerPool(smtpProperties);
        logStartup();
    }

    // -------------------------------------------------------------------------
    // MailGateway
    // -------------------------------------------------------------------------

    @Override
    public void send(MailPayload payload) {
        SmtpServer server = pickServer();
        Session session = buildSession(server);
        try {
            MimeMessage message = buildMessage(session, payload);
            Transport.send(message, server.username(), server.password());
            log.info("SMTP sent OK: to={} subject={} server={} proxy={}",
                    payload.getTo(), payload.getSubject(), server.name(), server.proxyLabel());
        } catch (MessagingException | UnsupportedEncodingException e) {
            log.error("SMTP send failed: to={} server={} proxy={} error={}",
                    payload.getTo(), server.name(), server.proxyLabel(), e.getMessage(), e);
            throw new RuntimeException("SMTP send failed for " + payload.getTo() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<MailFailure> sendAll(Collection<MailPayload> payloads, int concurrency) {
        int actual = Math.min(concurrency, MAX_CONCURRENCY);
        ExecutorService pool = Executors.newFixedThreadPool(actual);
        List<Future<MailFailure>> futures = new ArrayList<>(payloads.size());

        for (MailPayload payload : payloads) {
            futures.add(pool.submit(() -> {
                try {
                    send(payload);
                    return null;
                } catch (Exception ex) {
                    log.error("Bulk SMTP failure for {}: {}", payload.getTo(), ex.getMessage());
                    return new MailFailure(payload.getTo(), ex.getMessage());
                }
            }));
        }

        pool.shutdown();
        try {
            if (!pool.awaitTermination(10, TimeUnit.MINUTES)) {
                log.warn("SMTP bulk send timed out");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("SMTP bulk send interrupted", e);
        }

        List<MailFailure> failures = new ArrayList<>();
        for (Future<MailFailure> f : futures) {
            try {
                MailFailure result = f.get();
                if (result != null) failures.add(result);
            } catch (Exception ex) {
                failures.add(new MailFailure("<unknown>", ex.getMessage()));
            }
        }

        log.info("SMTP bulk send complete: total={} failures={}", payloads.size(), failures.size());
        return failures;
    }

    // -------------------------------------------------------------------------
    // Session construction
    // -------------------------------------------------------------------------

    private Session buildSession(SmtpServer server) {
        Properties props = new Properties();

        props.put("mail.smtp.host", server.host());
        props.put("mail.smtp.port", String.valueOf(server.port()));
        props.put("mail.smtp.auth", "true");

        if (server.useSsl()) {
            props.put("mail.smtp.ssl.enable", "true");
            props.put("mail.smtp.ssl.checkserveridentity", "true");
        } else {
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.smtp.starttls.required", "true");
        }

        props.put("mail.smtp.connectiontimeout", String.valueOf(server.connectionTimeoutMs()));
        props.put("mail.smtp.timeout",           String.valueOf(server.readTimeoutMs()));
        props.put("mail.smtp.writetimeout",       String.valueOf(server.writeTimeoutMs()));
        props.put("mail.smtp.ehlo",    "true");
        props.put("mail.mime.charset", "UTF-8");

        // Inject proxy if configured
        if (server.hasProxy()) {
            applyProxy(server, props);
        }

        final String user = server.username();
        final String pass = server.password();
        return Session.getInstance(props, new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(user, pass);
            }
        });
    }

    /**
     * Wires proxy into session properties.
     *
     * SOCKS5: uses our custom Socks5SocketFactory which does the RFC 1928/1929
     *   handshake manually — this is the ONLY reliable way to pass credentials
     *   per-connection. Jakarta Mail's built-in socks.host ignores credentials.
     *
     * HTTP: uses Jakarta Mail's native mail.smtp.proxy.host/port.
     *
     * NONE / no proxy: nothing added — direct TCP connection to SMTP server.
     */
    private void applyProxy(SmtpServer server, Properties props) {
        if (server.proxyType() == ProxyType.SOCKS5) {
            if (server.useSsl()) {
                // SSL (port 465): must use SSLSocketFactory subclass so Jakarta Mail
                // doesn't bypass the tunnel with its own direct SSL connection.
                // Socks5SslSocketFactory tunnels via SOCKS5 then wraps in SSL.
                Socks5SslSocketFactory sslFactory = new Socks5SslSocketFactory(
                        server.proxyHost(), server.proxyPort(),
                        server.proxyUsername(), server.proxyPassword(),
                        server.host(), server.connectionTimeoutMs()
                );
                props.put("mail.smtp.ssl.socketFactory",       sslFactory);
                props.put("mail.smtp.ssl.socketFactory.port",  String.valueOf(server.port()));
                props.put("mail.smtp.ssl.checkserveridentity", "false");
            } else {
                // STARTTLS (port 587): plain socket factory, Jakarta Mail upgrades in-band.
                Socks5SocketFactory factory = new Socks5SocketFactory(
                        server.proxyHost(), server.proxyPort(),
                        server.proxyUsername(), server.proxyPassword(),
                        server.connectionTimeoutMs()
                );
                props.put("mail.smtp.socketFactory",          factory);
                props.put("mail.smtp.socketFactory.port",     String.valueOf(server.port()));
                props.put("mail.smtp.socketFactory.fallback", "false");
            }
        } else if (server.proxyType() == ProxyType.HTTP) {
            props.put("mail.smtp.proxy.host", server.proxyHost());
            props.put("mail.smtp.proxy.port", String.valueOf(server.proxyPort()));
            if (StringUtils.hasText(server.proxyUsername())) {
                props.put("mail.smtp.proxy.user",     server.proxyUsername());
                props.put("mail.smtp.proxy.password", server.proxyPassword() != null ? server.proxyPassword() : "");
            }
        }
        // NONE = direct, nothing to add
    }

    // -------------------------------------------------------------------------
    // Message construction
    // -------------------------------------------------------------------------

    private MimeMessage buildMessage(Session session, MailPayload payload)
            throws MessagingException, UnsupportedEncodingException {
        MimeMessage msg = new MimeMessage(session);

        String fromAddr = StringUtils.hasText(properties.getFromAddress())
                ? properties.getFromAddress().trim()
                : smtpProperties.getUsername();
        String fromName = StringUtils.hasText(properties.getAnalyticsSenderName())
                ? properties.getAnalyticsSenderName() : null;
        try {
            msg.setFrom(fromName != null
                    ? new InternetAddress(fromAddr, fromName, "UTF-8")
                    : new InternetAddress(fromAddr));
        } catch (Exception e) {
            msg.setFrom(new InternetAddress(fromAddr));
        }

        msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(payload.getTo().trim()));
        msg.setSubject(payload.getSubject().trim(), "UTF-8");
        msg.setSentDate(new java.util.Date());
        msg.setHeader("Message-ID", generateMessageId(fromAddr));

        if (StringUtils.hasText(properties.getUnsubscribeMailto())) {
            msg.setHeader("List-Unsubscribe", "<mailto:" + properties.getUnsubscribeMailto().trim() + ">");
            msg.setHeader("List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
        }

        if (CollectionUtils.isEmpty(payload.getAttachments())) {
            setBody(msg, payload);
        } else {
            MimeMultipart multipart = new MimeMultipart("mixed");
            MimeBodyPart bodyPart = new MimeBodyPart();
            setBody(bodyPart, payload);
            multipart.addBodyPart(bodyPart);
            for (MailAttachment attachment : payload.getAttachments()) {
                MimeBodyPart attachPart = new MimeBodyPart();
                byte[] decoded = Base64.getDecoder().decode(attachment.getBase64Content());
                attachPart.setContent(decoded, detectMimeType(attachment.getFileName()));
                attachPart.setFileName(MimeUtility.encodeText(attachment.getFileName(), "UTF-8", "B"));
                attachPart.setDisposition(Part.ATTACHMENT);
                multipart.addBodyPart(attachPart);
            }
            msg.setContent(multipart);
        }
        return msg;
    }

    private void setBody(MimePart part, MailPayload payload) throws MessagingException {
        if (payload.isHtml()) {
            MimeMultipart alt = new MimeMultipart("alternative");
            MimeBodyPart text = new MimeBodyPart();
            text.setText(stripHtml(payload.getBody()), "UTF-8");
            alt.addBodyPart(text);
            MimeBodyPart html = new MimeBodyPart();
            html.setContent(payload.getBody(), "text/html; charset=UTF-8");
            alt.addBodyPart(html);
            part.setContent(alt);
        } else {
            part.setText(payload.getBody(), "UTF-8");
        }
    }

    // -------------------------------------------------------------------------
    // Server pool construction
    // -------------------------------------------------------------------------

    private SmtpServer pickServer() {
        if (serverPool.size() == 1 || !smtpProperties.isRotationEnabled()) return serverPool.get(0);
        int idx = Math.abs(serverIndex.getAndIncrement() % serverPool.size());
        return serverPool.get(idx);
    }

    private List<SmtpServer> buildServerPool(SmtpProperties smtpProperties) {

        // --- Multi-Server Pool ---
        if (smtpProperties.isMultiServerEnabled() && !CollectionUtils.isEmpty(smtpProperties.getServers())) {
            List<SmtpServer> pool = smtpProperties.getServers().stream()
                    .filter(s -> StringUtils.hasText(s.getHost()))
                    .map(s -> {
                        int connMs  = s.getConnectionTimeoutMs() > 0 ? s.getConnectionTimeoutMs() : smtpProperties.getConnectionTimeoutMs();
                        int readMs  = s.getReadTimeoutMs()       > 0 ? s.getReadTimeoutMs()       : smtpProperties.getReadTimeoutMs();
                        int writeMs = s.getWriteTimeoutMs()      > 0 ? s.getWriteTimeoutMs()      : smtpProperties.getWriteTimeoutMs();
                        ProxyType pt = resolveType(s.getProxyType());
                        boolean hasProxy = StringUtils.hasText(s.getProxyHost()) && pt != ProxyType.NONE;
                        return new SmtpServer(
                                s.getName(), s.getHost(), s.getPort(),
                                s.getUsername(), s.getPassword(), s.isUseSsl(),
                                connMs, readMs, writeMs,
                                hasProxy, pt,
                                s.getProxyHost(), s.getProxyPort(),
                                s.getProxyUsername(), s.getProxyPassword()
                        );
                    })
                    .toList();

            if (!pool.isEmpty()) return pool;
        }

        // --- Proxy Rotation Pool (Legacy / Single SMTP + multiple proxies) ---
        if (smtpProperties.isProxyRotationEnabled() && !CollectionUtils.isEmpty(smtpProperties.getProxies())) {
            List<SmtpServer> pool = smtpProperties.getProxies().stream()
                    .filter(p -> StringUtils.hasText(p.getHost()))
                    .map(p -> {
                        ProxyType pt = resolveType(p.getType());
                        return new SmtpServer(
                                "proxy-" + p.getHost(),
                                smtpProperties.getHost(), smtpProperties.getPort(),
                                smtpProperties.getUsername(), smtpProperties.getPassword(), smtpProperties.isUseSsl(),
                                smtpProperties.getConnectionTimeoutMs(), smtpProperties.getReadTimeoutMs(), smtpProperties.getWriteTimeoutMs(),
                                true, pt,
                                p.getHost(), p.getPort(), p.getUsername(), p.getPassword()
                        );
                    })
                    .toList();
            if (!pool.isEmpty()) return pool;
        }

        // --- Single Server (Direct or single proxy) ---
        boolean hasProxy = smtpProperties.isUseProxy() && StringUtils.hasText(smtpProperties.getProxyHost());
        ProxyType pt = hasProxy ? resolveType(smtpProperties.getProxyType()) : ProxyType.NONE;
        return List.of(new SmtpServer(
                "default",
                smtpProperties.getHost(), smtpProperties.getPort(),
                smtpProperties.getUsername(), smtpProperties.getPassword(), smtpProperties.isUseSsl(),
                smtpProperties.getConnectionTimeoutMs(), smtpProperties.getReadTimeoutMs(), smtpProperties.getWriteTimeoutMs(),
                hasProxy, pt,
                smtpProperties.getProxyHost(), smtpProperties.getProxyPort(),
                smtpProperties.getProxyUsername(), smtpProperties.getProxyPassword()
        ));
    }

    private ProxyType resolveType(String type) {
        if (!StringUtils.hasText(type)) return ProxyType.SOCKS5;
        return switch (type.toUpperCase()) {
            case "HTTP"            -> ProxyType.HTTP;
            case "SOCKS", "SOCKS5" -> ProxyType.SOCKS5;
            default                -> ProxyType.NONE;
        };
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String generateMessageId(String fromAddr) {
        String domain = fromAddr.contains("@") ? fromAddr.substring(fromAddr.indexOf('@') + 1) : "mail.local";
        return "<" + UUID.randomUUID() + "@" + domain + ">";
    }

    private String detectMimeType(String filename) {
        if (filename == null) return "application/octet-stream";
        String lower = filename.toLowerCase();
        if (lower.endsWith(".pdf"))  return "application/pdf";
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif"))  return "image/gif";
        if (lower.endsWith(".csv"))  return "text/csv";
        if (lower.endsWith(".txt"))  return "text/plain";
        if (lower.endsWith(".docx")) return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if (lower.endsWith(".xlsx")) return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if (lower.endsWith(".zip"))  return "application/zip";
        return "application/octet-stream";
    }

    private String stripHtml(String html) {
        if (!StringUtils.hasText(html)) return "";
        return html.replaceAll("(?s)<style.*?>.*?</style>", "")
                .replaceAll("(?s)<script.*?>.*?</script>", "")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</?p\\s*/?>", "\n")
                .replaceAll("<[^>]*>", "")
                .replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replaceAll("\\n\\s*\\n+", "\n\n").trim();
    }

    private void logStartup() {
        log.info("SmtpProxyMailGateway active — {} server(s) in pool:", serverPool.size());
        serverPool.forEach(s -> log.info("  [{}] {}:{} ssl={} proxy={}",
                s.name(), s.host(), s.port(), s.useSsl(), s.proxyLabel()));
    }

    // -------------------------------------------------------------------------
    // Inner types
    // -------------------------------------------------------------------------

    enum ProxyType { SOCKS5, HTTP, NONE }

    /**
     * Immutable snapshot of one SMTP server + its optional proxy.
     * Pre-resolved at startup — safe to use from multiple threads.
     */
    record SmtpServer(
            String name,
            String host, int port,
            String username, String password,
            boolean useSsl,
            int connectionTimeoutMs, int readTimeoutMs, int writeTimeoutMs,
            boolean hasProxy, ProxyType proxyType,
            String proxyHost, int proxyPort,
            String proxyUsername, String proxyPassword
    ) {
        String proxyLabel() {
            if (!hasProxy) return "direct";
            return proxyType + "://" + proxyHost + ":" + proxyPort;
        }
    }

    // -------------------------------------------------------------------------
    // Socks5SocketFactory — manual RFC 1928 + RFC 1929 handshake
    // -------------------------------------------------------------------------

    /**
     * Performs the full SOCKS5 handshake (RFC 1928 + RFC 1929) manually per connection.
     *
     * WHY: Jakarta Mail's built-in SOCKS support (mail.smtp.socks.host) uses Java's
     * SocksSocketImpl which only reads credentials from global JVM system properties.
     * That makes concurrent proxy rotation impossible (race condition on global state).
     *
     * This factory does the handshake per-socket, passing credentials inline with
     * no shared mutable state — safe for multi-threaded bulk sending.
     */
    static class Socks5SocketFactory extends javax.net.SocketFactory {

        private static final byte VER            = 0x05;
        private static final byte AUTH_NONE      = 0x00;
        private static final byte AUTH_USERPASS  = 0x02;
        private static final byte AUTH_FAIL      = (byte) 0xFF;
        private static final byte CMD_CONNECT    = 0x01;
        private static final byte ATYP_DOMAIN    = 0x03;
        private static final byte SUCCESS        = 0x00;
        private static final byte USERPASS_VER   = 0x01;

        private final String proxyHost;
        private final int    proxyPort;
        private final String username;
        private final String password;
        private final int    timeoutMs;

        Socks5SocketFactory(String proxyHost, int proxyPort,
                            String username, String password, int timeoutMs) {
            this.proxyHost = proxyHost;
            this.proxyPort = proxyPort;
            this.username  = username != null ? username : "";
            this.password  = password != null ? password : "";
            this.timeoutMs = timeoutMs;
        }

        @Override public Socket createSocket() throws IOException { return new Socket(); }

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

        /**
         * Opens a TCP socket to the SOCKS5 proxy, performs the full handshake,
         * requests a tunnel to (targetHost:targetPort), and returns the connected socket.
         * Jakarta Mail then uses this socket as if it connected directly to the SMTP server.
         */
        private Socket tunnel(String targetHost, int targetPort) throws IOException {
            Socket socket = new Socket();
            socket.setSoTimeout(timeoutMs);
            socket.connect(new InetSocketAddress(proxyHost, proxyPort), timeoutMs);

            var out = socket.getOutputStream();
            var in  = socket.getInputStream();

            boolean hasAuth = !username.isEmpty();

            // Step 1: Greeting — offer auth methods
            if (hasAuth) {
                out.write(new byte[]{ VER, 2, AUTH_NONE, AUTH_USERPASS });
            } else {
                out.write(new byte[]{ VER, 1, AUTH_NONE });
            }
            out.flush();

            // Step 2: Server chooses method
            byte serverVer = (byte) in.read();
            byte method    = (byte) in.read();

            if (serverVer != VER) {
                socket.close();
                throw new IOException("SOCKS5: bad server version: " + serverVer);
            }
            if (method == AUTH_FAIL) {
                socket.close();
                throw new IOException("SOCKS5: no acceptable auth method");
            }

            // Step 3: Authenticate if server chose username/password
            if (method == AUTH_USERPASS) {
                byte[] uBytes = username.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                byte[] pBytes = password.getBytes(java.nio.charset.StandardCharsets.UTF_8);

                byte[] authMsg = new byte[3 + uBytes.length + pBytes.length];
                authMsg[0] = USERPASS_VER;
                authMsg[1] = (byte) uBytes.length;
                System.arraycopy(uBytes, 0, authMsg, 2, uBytes.length);
                authMsg[2 + uBytes.length] = (byte) pBytes.length;
                System.arraycopy(pBytes, 0, authMsg, 3 + uBytes.length, pBytes.length);
                out.write(authMsg);
                out.flush();

                in.read(); // version byte
                byte status = (byte) in.read();
                if (status != SUCCESS) {
                    socket.close();
                    throw new IOException("SOCKS5: auth failed — check proxy username/password. Status=" + status);
                }
            }

            // Step 4: CONNECT request
            byte[] hostBytes = targetHost.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] req = new byte[7 + hostBytes.length];
            req[0] = VER;
            req[1] = CMD_CONNECT;
            req[2] = 0x00; // RSV
            req[3] = ATYP_DOMAIN;
            req[4] = (byte) hostBytes.length;
            System.arraycopy(hostBytes, 0, req, 5, hostBytes.length);
            req[5 + hostBytes.length] = (byte) ((targetPort >> 8) & 0xFF);
            req[6 + hostBytes.length] = (byte) (targetPort & 0xFF);
            out.write(req);
            out.flush();

            // Step 5: Read CONNECT reply
            in.read(); // VER
            byte rep  = (byte) in.read();
            in.read(); // RSV
            byte atyp = (byte) in.read();

            // Drain bound address
            switch (atyp) {
                case 0x01 -> in.readNBytes(4);   // IPv4
                case 0x03 -> in.readNBytes(in.read()); // domain
                case 0x04 -> in.readNBytes(16);  // IPv6
            }
            in.readNBytes(2); // port

            if (rep != SUCCESS) {
                socket.close();
                throw new IOException("SOCKS5: CONNECT failed. Code=0x"
                        + String.format("%02X", rep) + " " + socksError(rep));
            }

            socket.setSoTimeout(0); // let Jakarta Mail set its own timeouts
            return socket;
        }

        private String socksError(byte code) {
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
}



// =========================================================================
// Socks5SslSocketFactory
// Extends SSLSocketFactory so Jakarta Mail accepts it for mail.smtp.ssl.socketFactory.
// Opens SOCKS5 tunnel first, then wraps in SSL — proxy IP is what Aruba sees.
// =========================================================================
class Socks5SslSocketFactory extends javax.net.ssl.SSLSocketFactory {

    private static final byte VER           = 0x05;
    private static final byte AUTH_NONE     = 0x00;
    private static final byte AUTH_USERPASS = 0x02;
    private static final byte AUTH_FAIL     = (byte) 0xFF;
    private static final byte CMD_CONNECT   = 0x01;
    private static final byte ATYP_DOMAIN   = 0x03;
    private static final byte SUCCESS       = 0x00;
    private static final byte USERPASS_VER  = 0x01;

    private final String proxyHost;
    private final int    proxyPort;
    private final String username;
    private final String password;
    private final String smtpHost;   // needed for SSL SNI
    private final int    timeoutMs;
    private final javax.net.ssl.SSLSocketFactory delegate;

    Socks5SslSocketFactory(String proxyHost, int proxyPort,
                           String username, String password,
                           String smtpHost, int timeoutMs) {
        this.proxyHost = proxyHost;
        this.proxyPort = proxyPort;
        this.username  = username != null ? username : "";
        this.password  = password != null ? password : "";
        this.smtpHost  = smtpHost;
        this.timeoutMs = timeoutMs;
        this.delegate  = (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
    }

    // Jakarta Mail calls createSocket(host, port) for SSL connections
    @Override
    public java.net.Socket createSocket(String host, int port) throws IOException {
        return tunnelThenSsl(host, port);
    }

    @Override
    public java.net.Socket createSocket(String host, int port,
                                        java.net.InetAddress localHost, int localPort) throws IOException {
        return tunnelThenSsl(host, port);
    }

    @Override
    public java.net.Socket createSocket(java.net.InetAddress host, int port) throws IOException {
        return tunnelThenSsl(host.getHostAddress(), port);
    }

    @Override
    public java.net.Socket createSocket(java.net.InetAddress address, int port,
                                        java.net.InetAddress localAddress, int localPort) throws IOException {
        return tunnelThenSsl(address.getHostAddress(), port);
    }

    // Called when Jakarta Mail wraps an existing plain socket in SSL (STARTTLS path — not used here)
    @Override
    public java.net.Socket createSocket(java.net.Socket s, String host, int port, boolean autoClose) throws IOException {
        return delegate.createSocket(s, host, port, autoClose);
    }

    @Override
    public String[] getDefaultCipherSuites() { return delegate.getDefaultCipherSuites(); }

    @Override
    public String[] getSupportedCipherSuites() { return delegate.getSupportedCipherSuites(); }

    /**
     * Step 1: Open raw TCP to SOCKS5 proxy + full RFC1928/RFC1929 handshake → tunnel to SMTP host.
     * Step 2: Wrap tunneled socket in SSL → SSL handshake with SMTP server.
     * Result: Jakarta Mail speaks SMTP over SSL over SOCKS5. Aruba sees the proxy IP.
     */
    private java.net.Socket tunnelThenSsl(String targetHost, int targetPort) throws IOException {
        // --- SOCKS5 tunnel ---
        java.net.Socket raw = new java.net.Socket();
        raw.setSoTimeout(timeoutMs);
        raw.connect(new java.net.InetSocketAddress(proxyHost, proxyPort), timeoutMs);

        java.io.OutputStream out = raw.getOutputStream();
        java.io.InputStream  in  = raw.getInputStream();

        boolean hasAuth = !username.isEmpty();

        // Greeting
        out.write(hasAuth
                ? new byte[]{ VER, 2, AUTH_NONE, AUTH_USERPASS }
                : new byte[]{ VER, 1, AUTH_NONE });
        out.flush();

        byte sVer   = (byte) in.read();
        byte method = (byte) in.read();
        if (sVer != VER)        { raw.close(); throw new IOException("SOCKS5: bad version: " + sVer); }
        if (method == AUTH_FAIL){ raw.close(); throw new IOException("SOCKS5: no acceptable auth method"); }

        // Auth
        if (method == AUTH_USERPASS) {
            byte[] u = username.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] p = password.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] msg = new byte[3 + u.length + p.length];
            msg[0] = USERPASS_VER;
            msg[1] = (byte) u.length;
            System.arraycopy(u, 0, msg, 2, u.length);
            msg[2 + u.length] = (byte) p.length;
            System.arraycopy(p, 0, msg, 3 + u.length, p.length);
            out.write(msg); out.flush();
            in.read(); // sub-version
            byte st = (byte) in.read();
            if (st != SUCCESS) { raw.close(); throw new IOException("SOCKS5: auth failed. status=" + st); }
        }

        // CONNECT request
        byte[] host = targetHost.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] req = new byte[7 + host.length];
        req[0] = VER; req[1] = CMD_CONNECT; req[2] = 0x00; req[3] = ATYP_DOMAIN;
        req[4] = (byte) host.length;
        System.arraycopy(host, 0, req, 5, host.length);
        req[5 + host.length] = (byte) ((targetPort >> 8) & 0xFF);
        req[6 + host.length] = (byte) (targetPort & 0xFF);
        out.write(req); out.flush();

        // Read reply
        in.read(); byte rep = (byte) in.read(); in.read(); byte atyp = (byte) in.read();
        switch (atyp) {
            case 0x01 -> in.readNBytes(4);
            case 0x03 -> in.readNBytes(in.read());
            case 0x04 -> in.readNBytes(16);
        }
        in.readNBytes(2);
        if (rep != SUCCESS) { raw.close(); throw new IOException("SOCKS5: CONNECT failed. code=0x" + String.format("%02X", rep)); }

        // --- Wrap in SSL over the tunnel ---
        raw.setSoTimeout(0);
        javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket)
                delegate.createSocket(raw, smtpHost, targetPort, true);
        ssl.setUseClientMode(true);
        // SNI
        javax.net.ssl.SSLParameters params = ssl.getSSLParameters();
        params.setServerNames(java.util.List.of(new javax.net.ssl.SNIHostName(smtpHost)));
        ssl.setSSLParameters(params);
        ssl.startHandshake();
        return ssl;
    }
}