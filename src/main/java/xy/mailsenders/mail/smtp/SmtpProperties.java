package xy.mailsenders.mail.smtp;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

/**
 * Configuration for the SMTP proxy gateway.
 *
 * Supports two modes:
 *
 * MODE 1 — Single SMTP server (simple):
 *   Set app.mail.smtp.host/port/username/password directly.
 *   Optionally set proxy-host/port/type/username/password.
 *
 * MODE 2 — Multiple SMTP servers (rotation):
 *   Define a list under app.mail.smtp.servers.
 *   Each server can have its own proxy, credentials, and host.
 *   The gateway round-robins: email 1 → server[0], email 2 → server[1], etc.
 *
 * Example application.yml (multi-server):
 *
 *   app:
 *     mail:
 *       smtp:
 *         enabled: true
 *         servers:
 *           - name: brevo
 *             host: smtp-relay.brevo.com
 *             port: 587
 *             username: you@brevo.com
 *             password: yourBrevoSmtpKey
 *             proxy-host: 23.229.19.94
 *             proxy-port: 8689
 *             proxy-type: SOCKS5
 *             proxy-username: mailServer
 *             proxy-password: Rapilomme
 *
 *           - name: gmail
 *             host: smtp.gmail.com
 *             port: 587
 *             username: you@gmail.com
 *             password: yourGmailAppPassword
 *             proxy-host: 45.12.34.56
 *             proxy-port: 1080
 *             proxy-type: SOCKS5
 *             proxy-username: user2
 *             proxy-password: pass2
 *
 *           - name: my-vps
 *             host: mail.mydomain.com
 *             port: 587
 *             username: noreply@mydomain.com
 *             password: mailpassword
 *             use-ssl: false
 *             # no proxy — direct connection
 */
@Getter
@Setter
@Validated
@ConfigurationProperties(prefix = "app.mail.smtp")
public class SmtpProperties {

    /** Master switch — set true to use SMTP instead of Brevo REST API. */
    private boolean enabled;

    /** Whether to route through proxy (applies to single-server mode). */
    private boolean useProxy;

    // --- Mode Activation Flags ---

    /** Mode 1: Single SMTP server (direct or single proxy) */
    private boolean singleServerEnabled;

    /** Mode 2: Single SMTP server rotating through multiple proxies */
    private boolean proxyRotationEnabled;

    /** Mode 3: Rotating through multiple complete SMTP server definitions */
    private boolean multiServerEnabled;

    /** Whether to enable round-robin rotation (applies to modes 2 and 3) */
    private boolean rotationEnabled;

    // =========================================================================
    // Single-server mode (flat properties)
    // =========================================================================

    private String host;
    private int    port;
    private String username;
    private String password;
    private boolean useSsl;

    private int connectionTimeoutMs;
    private int readTimeoutMs;
    private int writeTimeoutMs;

    // Single proxy
    private String proxyHost;
    private int    proxyPort;
    private String proxyType;
    private String proxyUsername;
    private String proxyPassword;

    // Legacy multi-proxy pool (proxy-only rotation, same SMTP server)
    private List<ProxyDefinition> proxies;

    // =========================================================================
    // Multi-server mode — each entry is a complete SMTP+proxy config
    // =========================================================================

    /**
     * List of SMTP servers to rotate through.
     * When non-empty, overrides the flat single-server properties above.
     * Each server carries its own proxy config (optional).
     */
    private List<ServerDefinition> servers;

    // =========================================================================
    // Nested: multi-proxy definition (legacy — proxy rotation, one SMTP server)
    // =========================================================================

    @Getter
    @Setter
    public static class ProxyDefinition {
        private String host;
        private int    port;
        private String type;
        private String username;
        private String password;
    }

    // =========================================================================
    // Nested: full SMTP server + optional proxy definition
    // =========================================================================

    @Getter
    @Setter
    public static class ServerDefinition {

        /** Human-readable name for logging (e.g. "brevo", "gmail", "my-vps"). */
        private String name;

        // ---- SMTP server ----
        private String  host;
        private int     port;
        private String  username;
        private String  password;
        private boolean useSsl;

        // ---- Proxy (optional — leave host blank to connect directly) ----
        private String proxyHost;
        private int    proxyPort;
        private String proxyType;
        private String proxyUsername;
        private String proxyPassword;

        // ---- Timeouts (optional — fall back to top-level if zero) ----
        private int connectionTimeoutMs;
        private int readTimeoutMs;
        private int writeTimeoutMs;
    }
}
