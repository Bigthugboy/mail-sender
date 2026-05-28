package xy.mailsenders.verification.throttle;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Anti-throttle rate limiter for SMTP probes.
 *
 * SMTP servers blacklist sources that hammer them with connections.
 * This semaphore caps concurrent probes and enforces a minimum
 * delay between probes to the same MX — protecting your sending IP.
 *
 * SRP : throttling only. Checks call acquire/release around their probe.
 * OCP : swap implementation (e.g. token bucket) without changing callers.
 */
@Component
public class SmtpProbeThrottle {

    private final Semaphore semaphore;
    private final long      delayBetweenProbesMs;

    public SmtpProbeThrottle(
            @Value("${app.verification.smtp.max-concurrent-probes:5}") int maxConcurrent,
            @Value("${app.verification.smtp.probe-delay-ms:300}")       long delayMs) {
        this.semaphore            = new Semaphore(maxConcurrent, true);
        this.delayBetweenProbesMs = delayMs;
    }

    /** Acquires a probe slot. Blocks until one is available or timeout. */
    public boolean acquire(long timeoutMs) throws InterruptedException {
        return semaphore.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /** Releases a probe slot and sleeps the configured inter-probe delay. */
    public void release() {
        semaphore.release();
        try {
            Thread.sleep(delayBetweenProbesMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
