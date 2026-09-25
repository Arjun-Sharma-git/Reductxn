package io.reductxn.api.adapter.dev;

import io.reductxn.api.port.out.TaxRuleCatalog;
import io.reductxn.ledger.api.Currency;
import io.reductxn.ledger.api.Money;
import io.reductxn.netting.api.TaxRule;
import io.reductxn.netting.api.TaxRules;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * A development double for {@link TaxRuleCatalog}: a flat charge per transfer, read from a property.
 *
 * <p>Configured rather than hard-coded, which is the shape ADR 0006 asks for - a rule is data selected by
 * {@code (jurisdiction, currency, effective date)}, and a branch on country is what it exists to avoid.
 * This double honours only the middle key: it answers for any currency, and ignores {@code asOf}
 * because it has no effective dating. A preview of a past cycle therefore gets today's rate, which is
 * exactly the mistake effective dating exists to prevent - and is why this is development-only.
 *
 * <p>The amount is interpreted in the currency being assessed, so a flat 50 is 50 paise in INR and 50 yen
 * in JPY. That is the honest reading of a per-transfer charge, and it is the reading that makes the
 * difference between a coarser and a finer currency visible in a preview.
 */
@Component
@Profile("dev")
public class DevTaxRules implements TaxRuleCatalog {

    private final long flatPerTransferMinorUnits;

    DevTaxRules(@Value("${reductxn.dev.tax.flatPerTransferMinor:50}") long flatPerTransferMinorUnits) {
        this.flatPerTransferMinorUnits = flatPerTransferMinorUnits;
    }

    @Override
    public TaxRule activeFor(Currency currency, Instant asOf) {
        return TaxRules.flatPerTransfer(Money.of(flatPerTransferMinorUnits, currency));
    }
}
