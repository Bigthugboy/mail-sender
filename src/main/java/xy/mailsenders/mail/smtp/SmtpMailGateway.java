package xy.mailsenders.mail.smtp;

import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import xy.mailsenders.mail.domain.MailFailure;
import xy.mailsenders.mail.domain.MailPayload;
import xy.mailsenders.mail.smtp.message.MimeMessageBuilder;
import xy.mailsenders.mail.smtp.pool.SmtpServer;
import xy.mailsenders.mail.smtp.pool.SmtpServerPool;
import xy.mailsenders.mail.smtp.session.SmtpSessionFactory;
import xy.mailsenders.service.MailGateway;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * SMTP mail gateway — orchestrator only.
 *
 * SRP : sends mail. That is all.
 * DRY : session config → SmtpSessionFactory, message building → MimeMessageBuilder,
 *       server selection → SmtpServerPool, proxy sockets → Socks5* factories.
 * OCP : extend any collaborator without touching this class.
 * KISS: no byte-level code, no property wiring, no pool logic here.
 */
@Slf4j
@Service
public class SmtpMailGateway implements MailGateway {

    private static final int MAX_CONCURRENCY = 50;

    private final SmtpServerPool    serverPool;
    private final SmtpSessionFactory sessionFactory;
    private final MimeMessageBuilder messageBuilder;

    public SmtpMailGateway(SmtpServerPool serverPool,
                           SmtpSessionFactory sessionFactory,
                           MimeMessageBuilder messageBuilder) {
        this.serverPool     = serverPool;
        this.sessionFactory = sessionFactory;
        this.messageBuilder = messageBuilder;
    }

    // ── MailGateway ───────────────────────────────────────────────────────────

    @Override
    public void send(MailPayload payload) {
        SmtpServer server  = serverPool.pick();
        Session    session = sessionFactory.create(server);
        String     proto   = server.useSsl() ? "smtps" : "smtp";
        try {
            MimeMessage message = messageBuilder.build(session, payload);
            try (Transport transport = session.getTransport(proto)) {
                transport.connect(server.host(), server.port(),
                        server.username(), server.password());
                transport.sendMessage(message, message.getAllRecipients());
            }
            log.info("SMTP sent: to={} server={} proxy={}",
                    payload.getTo(), server.name(), server.proxyLabel());
        } catch (Exception e) {
            if (isGracefulClose(e)) {
                log.warn("SMTP closed after DATA (message accepted): to={} server={}",
                        payload.getTo(), server.name());
                return;
            }
            log.error("SMTP failed: to={} server={} error={}",
                    payload.getTo(), server.name(), e.getMessage(), e);
            throw new RuntimeException("SMTP send failed for " + payload.getTo(), e);
        }
    }

    @Override
    public List<MailFailure> sendAll(Collection<MailPayload> payloads, int concurrency) {
        int threads = Math.min(concurrency, MAX_CONCURRENCY);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<MailFailure>> futures = new ArrayList<>(payloads.size());

        for (MailPayload payload : payloads) {
            futures.add(pool.submit(() -> {
                try { send(payload); return null; }
                catch (Exception ex) {
                    log.error("Bulk SMTP failure for {}: {}", payload.getTo(), ex.getMessage());
                    return new MailFailure(payload.getTo(), ex.getMessage());
                }
            }));
        }

        pool.shutdown();
        try {
            if (!pool.awaitTermination(10, TimeUnit.MINUTES))
                log.warn("SMTP bulk send timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("SMTP bulk send interrupted", e);
        }

        List<MailFailure> failures = new ArrayList<>();
        for (Future<MailFailure> f : futures) {
            try { MailFailure r = f.get(); if (r != null) failures.add(r); }
            catch (Exception ex) { failures.add(new MailFailure("<unknown>", ex.getMessage())); }
        }

        log.info("SMTP bulk done: total={} failures={}", payloads.size(), failures.size());
        return failures;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Some SMTP relays (e.g. Aruba) close the connection during QUIT after
     *  accepting the message. Treat as success — the mail was delivered. */
    private boolean isGracefulClose(Exception e) {
        if (!(e instanceof MessagingException)) return false;
        String msg = e.getMessage();
        if (msg == null || !msg.contains("Can't send command to SMTP host")) return false;
        Throwable cause = e.getCause();
        if (!(cause instanceof java.net.SocketException)) return false;
        String cm = cause.getMessage();
        return cm != null && (cm.contains("Broken pipe") || cm.contains("Connection reset")
                || cm.contains("Socket closed") || cm.contains("Connection or outbound has closed"));
    }
}
