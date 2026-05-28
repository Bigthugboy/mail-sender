package xy.mailsenders.mail.smtp.pool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;
import xy.mailsenders.mail.smtp.SmtpProperties;
import xy.mailsenders.mail.smtp.proxy.ProxyType;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds and round-robins over the SMTP server pool.
 *
 * Three modes — resolved in priority order:
 *   1. Multi-server list  (app.mail.smtp.servers)
 *   2. Proxy-rotation     (app.mail.smtp.proxies)
 *   3. Single server      (flat app.mail.smtp.host/port/…)
 *
 * OCP: adding a new pool mode = adding a new build* method, not editing existing ones.
 */
@Slf4j
@Component
public class SmtpServerPool {

    private final List<SmtpServer> servers;
    private final boolean          rotationEnabled;
    private final AtomicInteger    index = new AtomicInteger(0);

    public SmtpServerPool(SmtpProperties props) {
        this.rotationEnabled = props.isRotationEnabled();
        this.servers         = buildPool(props);
        logPool();
    }

    public SmtpServer pick() {
        if (servers.size() == 1 || !rotationEnabled) return servers.get(0);
        return servers.get(Math.abs(index.getAndIncrement() % servers.size()));
    }

    public int size() { return servers.size(); }

    // ── pool construction ────────────────────────────────────────────────────

    private List<SmtpServer> buildPool(SmtpProperties smtpProperties) {
        if (smtpProperties.isMultiServerEnabled() && !CollectionUtils.isEmpty(smtpProperties.getServers())) {
            List<SmtpServer> pool = buildMultiServerPool(smtpProperties);
            if (!pool.isEmpty()) return pool;
        }
        if (smtpProperties.isProxyRotationEnabled() && !CollectionUtils.isEmpty(smtpProperties.getProxies())) {
            List<SmtpServer> pool = buildProxyRotationPool(smtpProperties);
            if (!pool.isEmpty()) return pool;
        }
        return List.of(buildSingleServer(smtpProperties));
    }

    private List<SmtpServer> buildMultiServerPool(SmtpProperties smtpProperties) {
        return smtpProperties.getServers().stream()
                .filter(serverDefinition -> StringUtils.hasText(serverDefinition.getHost()))
                .map(definition -> {
                    int conn  = definition.getConnectionTimeoutMs() > 0 ? definition.getConnectionTimeoutMs() : smtpProperties.getConnectionTimeoutMs();
                    int read  = definition.getReadTimeoutMs()       > 0 ? definition.getReadTimeoutMs()       : smtpProperties.getReadTimeoutMs();
                    int write = definition.getWriteTimeoutMs()      > 0 ? definition.getWriteTimeoutMs()      : smtpProperties.getWriteTimeoutMs();
                    ProxyType pt  = ProxyType.from(definition.getProxyType());
                    boolean proxy = StringUtils.hasText(definition.getProxyHost()) && pt != ProxyType.NONE;
                    return new SmtpServer(definition.getName(), definition.getHost(), definition.getPort(),
                            definition.getUsername(), definition.getPassword(), definition.isUseSsl(),
                            conn, read, write,
                            proxy, pt, definition.getProxyHost(), definition.getProxyPort(),
                            definition.getProxyUsername(), definition.getProxyPassword());
                })
                .toList();
    }

    private List<SmtpServer> buildProxyRotationPool(SmtpProperties smtpProperties) {
        return smtpProperties.getProxies().stream()
                .filter(proxyDefinition -> StringUtils.hasText(proxyDefinition.getHost()))
                .map(definition -> new SmtpServer(
                        "proxy-" + definition.getHost(),
                        smtpProperties.getHost(), smtpProperties.getPort(), smtpProperties.getUsername(), smtpProperties.getPassword(), smtpProperties.isUseSsl(),
                        smtpProperties.getConnectionTimeoutMs(), smtpProperties.getReadTimeoutMs(), smtpProperties.getWriteTimeoutMs(),
                        true, ProxyType.from(definition.getType()),
                        definition.getHost(), definition.getPort(), definition.getUsername(), definition.getPassword()))
                .toList();
    }

    private SmtpServer buildSingleServer(SmtpProperties smtpProperties) {
        boolean hasProxy = smtpProperties.isUseProxy() && StringUtils.hasText(smtpProperties.getProxyHost());
        ProxyType proxyType     = hasProxy ? ProxyType.from(smtpProperties.getProxyType()) : ProxyType.NONE;
        return new SmtpServer("default",
                smtpProperties.getHost(), smtpProperties.getPort(), smtpProperties.getUsername(), smtpProperties.getPassword(), smtpProperties.isUseSsl(),
                smtpProperties.getConnectionTimeoutMs(), smtpProperties.getReadTimeoutMs(), smtpProperties.getWriteTimeoutMs(),
                hasProxy, proxyType,
                smtpProperties.getProxyHost(), smtpProperties.getProxyPort(), smtpProperties.getProxyUsername(), smtpProperties.getProxyPassword());
    }

    private void logPool() {
        log.info("SmtpServerPool — {} server(s):", servers.size());
        servers.forEach(s -> log.info("  [{}] {}:{} ssl={} proxy={}",
                s.name(), s.host(), s.port(), s.useSsl(), s.proxyLabel()));
    }
}
