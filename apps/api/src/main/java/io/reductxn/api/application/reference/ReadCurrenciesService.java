package io.reductxn.api.application.reference;

import io.reductxn.api.port.in.ReadCurrencies;
import io.reductxn.api.port.out.CurrencyCatalog;
import io.reductxn.ledger.api.Currency;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The use case: the currency catalogue, ordered.
 *
 * <p>The one decision it owns is the order. The catalogue's port answers a {@code Set} - it was written for a
 * question where order means nothing ("every code this deployment can transact") - and a {@code Set}'s iteration
 * order is an implementation detail of the container. Serving it as it comes would make the response order
 * arbitrary, so a client that cached the list and one that did not could index it differently and a page diff could
 * show every row changed when none had. Ordered by code, the response is a fact rather than a coincidence.
 */
@Service
public class ReadCurrenciesService implements ReadCurrencies {

    private static final Comparator<Currency> BY_CODE = Comparator.comparing(Currency::code);

    private final CurrencyCatalog currencies;

    public ReadCurrenciesService(CurrencyCatalog currencies) {
        this.currencies = Objects.requireNonNull(currencies, "currencies");
    }

    @Override
    @Transactional(readOnly = true)
    public List<Currency> all() {
        return currencies.all().stream().sorted(BY_CODE).toList();
    }
}
