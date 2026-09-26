package io.reductxn.api.web.v1;

import io.reductxn.api.port.in.ReadCurrencies;
import io.reductxn.api.web.dto.CurrencyInfoResponse;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Objects;

/**
 * {@code GET /v1/currencies}, per the contract: reference data a client needs before it can show a number.
 *
 * <p><strong>A read the client has always called and this module never served</strong> (ADR 0035's audit). The
 * client's currency catalogue query is what every amount on every screen resolves its exponent from, so the failure
 * was not one screen falling back to a uuid - it was money rendering nowhere. There is no query parameter, no
 * failure and no pagination: the catalogue is small, complete by definition, and cached for an hour by the client
 * because a currency's minor unit does not change during a session.
 *
 * <p>No {@code 404} and no problem responses: an empty catalogue is not an error, it is a deployment that has not
 * been seeded - and a client that fetched a list and got a list has been answered.
 */
@RestController
@RequestMapping("/v1/currencies")
public class CurrenciesController {

    private final ReadCurrencies readCurrencies;

    public CurrenciesController(ReadCurrencies readCurrencies) {
        this.readCurrencies = Objects.requireNonNull(readCurrencies, "readCurrencies");
    }

    @GetMapping
    public ResponseEntity<Object> list() {
        List<CurrencyInfoResponse> currencies = readCurrencies.all().stream()
                .map(CurrencyInfoResponse::of)
                .toList();

        return ResponseEntity.ok(currencies);
    }
}
