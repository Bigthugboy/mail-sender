package xy.mailsenders.verification.check;

import xy.mailsenders.verification.domain.CheckResult;

/**
 * Strategy interface for a single email verification check.
 *
 * OCP  : add a new check by implementing this interface — nothing else changes.
 * SRP  : each implementor does exactly one thing.
 * KISS : one method, one concern.
 */
public interface EmailCheck {
    CheckResult run(String email);
}
