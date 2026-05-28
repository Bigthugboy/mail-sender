package xy.mailsenders.verification.service;

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import xy.mailsenders.verification.check.*;
import xy.mailsenders.verification.domain.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@AllArgsConstructor
public class VerificationService {

    private final SyntaxCheck syntaxCheck;
    private final DisposableCheck disposableCheck;
    private final RoleAddressCheck roleAddressCheck;
    private final MxRecordCheck mxRecordCheck;
    private final DnsblCheck dnsblCheck;
    private final SmtpProbeCheck smtpProbeCheck;
    private final CatchAllCheck catchAllCheck;

    /**
     * Runs verification on a list of emails.
     */
    public List<VerificationResult> verifyList(List<String> emails) {
        if (emails == null || emails.isEmpty()) {
            return List.of();
        }
        // Run verification in parallel to speed up network queries
        return emails.parallelStream()
                .map(this::verifyEmail)
                .toList();
    }

    /**
     * Verification workflow for a single email address.
     */
    public VerificationResult verifyEmail(String email) {
        if (email == null) {
            email = "";
        }
        email = email.trim();

        List<CheckResult> checks = new ArrayList<>();

        // Check 1: Syntax
        CheckResult syntaxRes = syntaxCheck.run(email);
        checks.add(syntaxRes);
        if (!syntaxRes.passed()) {
            return new VerificationResult(email, 0, VerificationStatus.INVALID, checks);
        }

        // Check 2: Disposable Domain
        CheckResult disposableRes = disposableCheck.run(email);
        checks.add(disposableRes);

        // Check 3: Role Address Check
        CheckResult roleRes = roleAddressCheck.run(email);
        checks.add(roleRes);

        // Check 4: MX Record Lookup
        CheckResult mxRes = mxRecordCheck.run(email);
        checks.add(mxRes);
        if (!mxRes.passed()) {
            // No MX record -> hard fail, skip remaining network checks
            return new VerificationResult(email, 0, VerificationStatus.INVALID, checks);
        }

        // Check 5: DNSBL Blacklist Check
        CheckResult dnsblRes = dnsblCheck.run(email);
        checks.add(dnsblRes);

        // Check 6: SMTP Probe Check
        CheckResult smtpRes = smtpProbeCheck.run(email);
        checks.add(smtpRes);

        // Check 7: Catch All Check
        CheckResult catchAllRes = catchAllCheck.run(email);
        checks.add(catchAllRes);

        // Calculate score & status
        int score = 100;
        VerificationStatus status = VerificationStatus.SAFE;

        for (CheckResult r : checks) {
            if (!r.passed()) {
                switch (r.type()) {
                    case SYNTAX, MX_RECORD -> {
                        score = 0;
                        status = VerificationStatus.INVALID;
                    }
                    case DISPOSABLE -> {
                        score = Math.min(score, 10);
                        status = VerificationStatus.INVALID;
                    }
                    case DNSBL -> {
                        score = Math.min(score, 20);
                        status = VerificationStatus.INVALID;
                    }
                    case SMTP_PROBE -> {
                        if (r.detail().contains("does not exist") || r.detail().contains("rejected")) {
                            score = 0;
                            status = VerificationStatus.INVALID;
                        } else {
                            // Probe connection or rate limit error
                            score = Math.min(score, 50);
                            if (status != VerificationStatus.INVALID) {
                                status = VerificationStatus.RISKY;
                            }
                        }
                    }
                    case CATCH_ALL -> {
                        score = Math.min(score, 70);
                        if (status != VerificationStatus.INVALID) {
                            status = VerificationStatus.RISKY;
                        }
                    }
                    case ROLE_ADDRESS -> {
                        score = Math.min(score, 80);
                        if (status != VerificationStatus.INVALID) {
                            status = VerificationStatus.RISKY;
                        }
                    }
                }
            }
        }

        return new VerificationResult(email, score, status, checks);
    }
}
