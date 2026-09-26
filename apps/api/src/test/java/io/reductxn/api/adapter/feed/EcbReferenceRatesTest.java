package io.reductxn.api.adapter.feed;

import io.reductxn.fx.api.InvalidRateException;
import io.reductxn.fx.api.PublishedRates;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The European Central Bank's document, read as a publication.
 *
 * <p>The document below is the bank's own, fetched while this was written (25 September 2026), including its
 * single-quoted attributes, its GESMES envelope and the awkward part - one rate quoted with five fractional
 * digits while most have two. A hand-written fixture would have had none of those, and the five decimals are
 * what decides the scale of every ratio derived from the table.
 */
class EcbReferenceRatesTest {

    private static final String DOCUMENT = EcbFeedDocument.DAILY;

    @Test
    void readsThePublicationInstantAsThePublicationRatherThanTheDay() {
        PublishedRates published = EcbReferenceRates.parse(DOCUMENT);

        assertThat(published.publishedAt())
                .as("16:00 in Brussels on a September day is 14:00 UTC, and a rate is not usable before it "
                        + "existed")
                .isEqualTo(Instant.parse("2026-09-25T14:00:00Z"));
        assertThat(published.provider()).isEqualTo("ecb");
        assertThat(published.baseCode())
                .as("every rate in the document is units of a currency per one euro")
                .isEqualTo("EUR");
    }

    @Test
    void normalisesTheTableToItsWidestRate() {
        PublishedRates published = EcbReferenceRates.parse(DOCUMENT);

        assertThat(published.scale())
                .as("GBP is quoted with five fractional digits, so the whole table is at five")
                .isEqualTo(5);
        assertThat(published.unitsPerBase("GBP")).isEqualTo(86_045L);
        assertThat(published.unitsPerBase("USD"))
                .as("1.1403 at five digits is 114030, exact")
                .isEqualTo(114_030L);
        assertThat(published.unitsPerBase("JPY"))
                .as("179.70 at five digits is 17970000 - padded, never rounded")
                .isEqualTo(17_970_000L);
        assertThat(published.unitsPerBase("IDR")).isEqualTo(2_042_722_000L);
        assertThat(published.unitsPerBase("KRW")).isEqualTo(154_516_000L);
    }

    @Test
    void theEurosOwnRateIsTheUnit() {
        PublishedRates published = EcbReferenceRates.parse(DOCUMENT);

        assertThat(published.quotes("EUR"))
                .as("the document says nothing about the euro because every value in it is against the euro")
                .isTrue();
        assertThat(published.unitsPerBase("EUR")).isEqualTo(100_000L);
    }

    @Test
    void theSamePublicationInJanuaryIsAnHourLaterInUtc() {
        String winter = DOCUMENT.replace("time='2026-09-25'", "time='2026-01-15'");

        assertThat(EcbReferenceRates.parse(winter).publishedAt())
                .as("Brussels is UTC+1 in winter, and a fixed offset would have dated every winter publication "
                        + "an hour early")
                .isEqualTo(Instant.parse("2026-01-15T15:00:00Z"));
    }

    @Test
    void takesTheMostRecentTableFromADocumentThatHoldsSeveral() {
        // The bank's ninety-day document holds one cube per working day. Taking the first would ingest a rate
        // from three months ago under today's name, which is the failure this rule exists to prevent.
        String ninetyDays = """
                <?xml version="1.0" encoding="UTF-8"?>
                <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01" xmlns="http://www.ecb.int/vocabulary/2002-08-01/eurofxref">
                \t<Cube>
                \t\t<Cube time='2026-09-23'>
                \t\t\t<Cube currency='USD' rate='1.1300'/>
                \t\t</Cube>
                \t\t<Cube time='2026-09-25'>
                \t\t\t<Cube currency='USD' rate='1.1403'/>
                \t\t</Cube>
                \t</Cube>
                </gesmes:Envelope>
                """;

        PublishedRates published = EcbReferenceRates.parse(ninetyDays);

        assertThat(published.publishedAt()).isEqualTo(Instant.parse("2026-09-25T14:00:00Z"));
        assertThat(published.scale())
                .as("the scale is the document's own widest rate, so this document is at four digits while the "
                        + "bank's daily one is at five")
                .isEqualTo(4);
        assertThat(published.unitsPerBase("USD")).isEqualTo(11_403L);
        assertThat(published.codes()).containsExactly("USD");
    }

    @Test
    void doesNotInventARateForACurrencyTheBankHasNotPublishedSince2022() {
        PublishedRates published = EcbReferenceRates.parse(DOCUMENT);

        assertThat(published.quotes("RUB"))
                .as("the bank suspended the rouble, so a deployment that transacts in it has no rate from this "
                        + "provider - and the ingestion says so instead of looking healthy")
                .isFalse();
        assertThat(published.codes()).doesNotContain("RUB");
    }

    @Test
    void refusesADocumentThatIsNotAPublication() {
        assertThatThrownBy(() -> EcbReferenceRates.parse("not xml at all"))
                .isInstanceOf(InvalidRateException.class)
                .hasMessageContaining("readable XML");

        assertThatThrownBy(() -> EcbReferenceRates.parse("<?xml version=\"1.0\"?><Cube/>"))
                .as("a document with no dated table is not a publication of rates")
                .isInstanceOf(InvalidRateException.class)
                .hasMessageContaining("dated table");

        assertThatThrownBy(() -> EcbReferenceRates.parse("   "))
                .isInstanceOf(InvalidRateException.class)
                .hasMessageContaining("nothing");
    }

    @Test
    void refusesADocumentTypeDeclarationRatherThanResolvingWhatItPointsAt() {
        // The document comes from outside the process, so a parser here is an attack surface rather than a
        // convenience: an entity that reads a local file would turn a feed outage into a disclosure.
        String withDoctype = """
                <?xml version="1.0"?>
                <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
                <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01">
                \t<Cube><Cube time='2026-09-25'><Cube currency='USD' rate='&xxe;'/></Cube></Cube>
                </gesmes:Envelope>
                """;

        assertThatThrownBy(() -> EcbReferenceRates.parse(withDoctype))
                .isInstanceOf(InvalidRateException.class)
                .hasMessageContaining("readable XML");
    }

    @Test
    void refusesARateThatIsNotANumber() {
        String nonsense = """
                <?xml version="1.0"?>
                <Cube><Cube time='2026-09-25'><Cube currency='USD' rate='about one point one'/></Cube></Cube>
                """;

        assertThatThrownBy(() -> EcbReferenceRates.parse(nonsense))
                .isInstanceOf(InvalidRateException.class)
                .hasMessageContaining("USD");
    }
}
