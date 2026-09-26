package io.reductxn.api.adapter.feed;

/**
 * The European Central Bank's daily reference-rates document, as it was fetched while this was written.
 *
 * <p>Shared between the parser's own test and the ingestion test that reads it from a file, because the thing
 * worth testing is the bank's real document rather than a tidy version of it: the single-quoted attributes, the
 * GESMES envelope, the whitespace between cubes, and the awkward part - {@code GBP} quoted with five fractional
 * digits while most currencies have two, which is where the whole table's scale comes from.
 *
 * <p>The date is 25 September 2026, a Friday, so {@code RUB} is absent for the reason it has been absent since
 * 2022: the bank suspended the rouble, and a deployment that transacts in it has no rate from this provider.
 */
public final class EcbFeedDocument {

    public static final String DAILY = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gesmes:Envelope xmlns:gesmes="http://www.gesmes.org/xml/2002-08-01" xmlns="http://www.ecb.int/vocabulary/2002-08-01/eurofxref">
            \t<gesmes:subject>Reference rates</gesmes:subject>
            \t<gesmes:Sender>
            \t\t<gesmes:name>European Central Bank</gesmes:name>
            \t</gesmes:Sender>
            \t<Cube>
            \t\t<Cube time='2026-09-25'>
            \t\t\t<Cube currency='USD' rate='1.1403'/>
            \t\t\t<Cube currency='JPY' rate='179.70'/>
            \t\t\t<Cube currency='CZK' rate='24.345'/>
            \t\t\t<Cube currency='DKK' rate='7.4755'/>
            \t\t\t<Cube currency='GBP' rate='0.86045'/>
            \t\t\t<Cube currency='HUF' rate='365.28'/>
            \t\t\t<Cube currency='PLN' rate='4.3718'/>
            \t\t\t<Cube currency='RON' rate='5.2765'/>
            \t\t\t<Cube currency='SEK' rate='11.2900'/>
            \t\t\t<Cube currency='CHF' rate='0.9445'/>
            \t\t\t<Cube currency='ISK' rate='136.60'/>
            \t\t\t<Cube currency='NOK' rate='10.8400'/>
            \t\t\t<Cube currency='TRY' rate='55.7975'/>
            \t\t\t<Cube currency='AUD' rate='1.6220'/>
            \t\t\t<Cube currency='BRL' rate='5.9091'/>
            \t\t\t<Cube currency='CAD' rate='1.6127'/>
            \t\t\t<Cube currency='CNY' rate='7.6551'/>
            \t\t\t<Cube currency='HKD' rate='8.9445'/>
            \t\t\t<Cube currency='IDR' rate='20427.22'/>
            \t\t\t<Cube currency='ILS' rate='3.4594'/>
            \t\t\t<Cube currency='INR' rate='109.2605'/>
            \t\t\t<Cube currency='KRW' rate='1545.16'/>
            \t\t\t<Cube currency='MXN' rate='20.1816'/>
            \t\t\t<Cube currency='MYR' rate='4.6456'/>
            \t\t\t<Cube currency='NZD' rate='2.0108'/>
            \t\t\t<Cube currency='PHP' rate='71.244'/>
            \t\t\t<Cube currency='SGD' rate='1.4563'/>
            \t\t\t<Cube currency='THB' rate='38.023'/>
            \t\t\t<Cube currency='ZAR' rate='18.5798'/>
            \t\t</Cube>
            \t</Cube>
            </gesmes:Envelope>
            """;

    private EcbFeedDocument() {
    }
}
