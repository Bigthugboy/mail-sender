package xy.mailsenders.verification.check;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.xbill.DNS.*;
import org.xbill.DNS.Record;
import xy.mailsenders.verification.domain.CheckResult;
import xy.mailsenders.verification.domain.CheckType;

import java.util.List;

/**
 * Check 4 — MX record lookup via dnsjava.
 *
 * Confirms the domain has at least one valid mail exchanger.
 * No MX = the domain cannot receive email at all.
 * Falls back to A/AAAA record if no MX exists (some small domains do this).
 *
 * Network I/O: DNS UDP/TCP query — typically < 100 ms.
 */
@Slf4j
@Component
public class MxRecordCheck implements EmailCheck {

    @Override
    public CheckResult run(String email) {
        String domain = DisposableCheck.domainOf(email);
        if (domain.isBlank())
            return CheckResult.fail(CheckType.MX_RECORD, "Cannot extract domain from: " + email);
        try {
            List<Record> records = lookupMx(domain);
            if (!records.isEmpty()) return CheckResult.pass(CheckType.MX_RECORD);

            // Fallback: check A record — some small domains use their host directly
            List<Record> aRecords = lookupA(domain);
            if (!aRecords.isEmpty()) {
                log.debug("No MX for {} but A record found — proceeding with warning", domain);
                return CheckResult.pass(CheckType.MX_RECORD);
            }
            return CheckResult.fail(CheckType.MX_RECORD,
                    "No MX or A record found for domain: " + domain);
        } catch (Exception e) {
            log.warn("MX lookup failed for {}: {}", domain, e.getMessage());
            return CheckResult.fail(CheckType.MX_RECORD,
                    "DNS lookup error for " + domain + ": " + e.getMessage());
        }
    }

    private List<Record> lookupMx(String domain) throws Exception {
        Lookup lookup = new Lookup(domain, Type.MX);
        lookup.run();
        if (lookup.getResult() == Lookup.SUCCESSFUL && lookup.getAnswers() != null)
            return List.of(lookup.getAnswers());
        return List.of();
    }

    private List<Record> lookupA(String domain) throws Exception {
        Lookup lookup = new Lookup(domain, Type.A);
        lookup.run();
        if (lookup.getResult() == Lookup.SUCCESSFUL && lookup.getAnswers() != null)
            return List.of(lookup.getAnswers());
        return List.of();
    }
}
