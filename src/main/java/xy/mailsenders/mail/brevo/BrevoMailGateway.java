package xy.mailsenders.mail.brevo;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import xy.mailsenders.mail.brevo.client.BrevoHttpClient;
import xy.mailsenders.mail.brevo.request.BrevoSendEmailRequest;
import xy.mailsenders.mail.domain.MailFailure;
import xy.mailsenders.mail.domain.MailPayload;
import xy.mailsenders.service.MailGateway;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@AllArgsConstructor
public class BrevoMailGateway implements MailGateway {

    private final BrevoHttpClient    httpClient;
    private final BrevoRequestMapper mapper;


    @Override
    public void send(MailPayload payload) {
        BrevoSendEmailRequest request = mapper.toBrevoRequest(payload);
        ResponseEntity<Void> response = httpClient.send(request);
        if (response.getStatusCode().is2xxSuccessful()) {
            log.info("Brevo sent: to={} subject={}", payload.getTo(), payload.getSubject());
        } else {
            log.warn("Brevo non-2xx for {}: status={}", payload.getTo(), response.getStatusCode());
            throw new RuntimeException("Brevo send failed: status=" + response.getStatusCode());
        }
    }

    @Override
    public List<MailFailure> sendAll(Collection<MailPayload> payloads, int concurrency) {
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        List<Future<MailFailure>> futures = new ArrayList<>(payloads.size());

        for (MailPayload payload : payloads) {
            futures.add(pool.submit(() -> {
                try { send(payload); return null; }
                catch (Exception ex) {
                    log.error("Brevo bulk failure for {}: {}", payload.getTo(), ex.getMessage());
                    return new MailFailure(payload.getTo(), ex.getMessage());
                }
            }));
        }

        pool.shutdown();
        try {
            if (!pool.awaitTermination(5, TimeUnit.MINUTES))
                log.warn("Brevo bulk send timed out");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Brevo bulk send interrupted", e);
        }

        List<MailFailure> failures = new ArrayList<>();
        for (Future<MailFailure> mailFailureFuture : futures) {
            try { MailFailure mailFailure = mailFailureFuture.get(); if (mailFailure != null) failures.add(mailFailure); }
            catch (Exception ex) { failures.add(new MailFailure("<unknown>", ex.getMessage())); }
        }
        log.info("Brevo bulk done: total={} failures={}", payloads.size(), failures.size());
        return failures;
    }
}
