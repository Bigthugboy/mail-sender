package xy.mailsenders.mail.smtp.pool;

import xy.mailsenders.mail.smtp.proxy.ProxyType;

/**
 * Immutable snapshot of one SMTP server and its optional proxy.
 * Built once at startup — safe to share across threads.
 */
public record SmtpServer(
        String  name,
        String  host,
        int     port,
        String  username,
        String  password,
        boolean useSsl,
        int     connectionTimeoutMs,
        int     readTimeoutMs,
        int     writeTimeoutMs,
        boolean hasProxy,
        ProxyType proxyType,
        String  proxyHost,
        int     proxyPort,
        String  proxyUsername,
        String  proxyPassword
) {
    public String proxyLabel() {
        return hasProxy ? proxyType + "://" + proxyHost + ":" + proxyPort : "direct";
    }
}
