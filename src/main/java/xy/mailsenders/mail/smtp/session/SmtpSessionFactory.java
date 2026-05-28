package xy.mailsenders.mail.smtp.session;

import jakarta.mail.Authenticator;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import xy.mailsenders.mail.smtp.pool.SmtpServer;
import xy.mailsenders.mail.smtp.proxy.ProxyType;
import xy.mailsenders.mail.smtp.proxy.Socks5SocketFactory;
import xy.mailsenders.mail.smtp.proxy.Socks5SslSocketFactory;

import java.util.Properties;

/**
 * Creates a Jakarta Mail Session for a given SmtpServer.
 *
 * SRP: session config is its only concern — no message building, no sending.
 * OCP: adding a new proxy type (e.g. WireGuard) = new branch in applyProxy,
 *      not touching the gateway.
 */
@Component
public class SmtpSessionFactory {

    public Session create(SmtpServer server) {
        String proto = server.useSsl() ? "smtps" : "smtp";
        Properties props = baseProperties(server, proto);
        applyProxy(server, props, proto);
        return Session.getInstance(props, authenticator(server.username(), server.password()));
    }

    // ── base properties ──────────────────────────────────────────────────────

    private Properties baseProperties(SmtpServer s, String proto) {
        Properties p = new Properties();
        p.put("mail." + proto + ".host",              s.host());
        p.put("mail." + proto + ".port",              String.valueOf(s.port()));
        p.put("mail." + proto + ".auth",              "true");
        p.put("mail." + proto + ".connectiontimeout", String.valueOf(s.connectionTimeoutMs()));
        p.put("mail." + proto + ".timeout",           String.valueOf(s.readTimeoutMs()));
        p.put("mail." + proto + ".writetimeout",      String.valueOf(s.writeTimeoutMs()));
        p.put("mail." + proto + ".ehlo",              "true");
        p.put("mail.mime.charset",                    "UTF-8");
        p.put("mail.smtp.ssl.trust",                  "*");
        p.put("mail.smtp.ssl.checkserveridentity",    "false");

        if (s.useSsl()) {
            if (!s.hasProxy()) {
                p.put("mail.smtps.ssl.enable",               "true");
                p.put("mail.smtps.ssl.checkserveridentity",  "false");
            }
        } else {
            p.put("mail.smtp.starttls.enable",   "true");
            p.put("mail.smtp.starttls.required", "true");
        }
        return p;
    }

    // ── proxy wiring ─────────────────────────────────────────────────────────

    private void applyProxy(SmtpServer s, Properties props, String proto) {
        if (!s.hasProxy()) return;

        if (s.proxyType() == ProxyType.SOCKS5) {
            applySocks5Proxy(s, props, proto);
        } else if (s.proxyType() == ProxyType.HTTP) {
            applyHttpProxy(s, props, proto);
        }
    }

    private void applySocks5Proxy(SmtpServer s, Properties props, String proto) {
        if (s.useSsl()) {
            Socks5SslSocketFactory factory = new Socks5SslSocketFactory(
                    s.proxyHost(), s.proxyPort(),
                    s.proxyUsername(), s.proxyPassword(),
                    s.host(), s.connectionTimeoutMs());
            props.put("mail." + proto + ".socketFactory",          factory);
            props.put("mail." + proto + ".socketFactory.port",     String.valueOf(s.port()));
            props.put("mail." + proto + ".socketFactory.fallback", "false");
            props.put("mail." + proto + ".ssl.checkserveridentity","false");
        } else {
            Socks5SocketFactory factory = new Socks5SocketFactory(
                    s.proxyHost(), s.proxyPort(),
                    s.proxyUsername(), s.proxyPassword(),
                    s.connectionTimeoutMs());
            props.put("mail." + proto + ".socketFactory",          factory);
            props.put("mail." + proto + ".socketFactory.port",     String.valueOf(s.port()));
            props.put("mail." + proto + ".socketFactory.fallback", "false");
        }
    }

    private void applyHttpProxy(SmtpServer s, Properties props, String proto) {
        props.put("mail." + proto + ".proxy.host", s.proxyHost());
        props.put("mail." + proto + ".proxy.port", String.valueOf(s.proxyPort()));
        if (StringUtils.hasText(s.proxyUsername())) {
            props.put("mail." + proto + ".proxy.user",     s.proxyUsername());
            props.put("mail." + proto + ".proxy.password", s.proxyPassword() != null ? s.proxyPassword() : "");
        }
    }

    private Authenticator authenticator(String user, String pass) {
        return new Authenticator() {
            @Override
            protected PasswordAuthentication getPasswordAuthentication() {
                return new PasswordAuthentication(user, pass);
            }
        };
    }
}
