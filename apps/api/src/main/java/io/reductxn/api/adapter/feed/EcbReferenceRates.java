package io.reductxn.api.adapter.feed;

import io.reductxn.fx.api.InvalidRateException;
import io.reductxn.fx.api.PublishedRates;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import java.io.IOException;
import java.io.StringReader;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The European Central Bank's daily reference rates, as a {@link PublishedRates}.
 *
 * <p><strong>What the document is.</strong> An envelope containing one or more dated cubes, each holding
 * currency/rate pairs. The rates are units of that currency per one euro, so the euro is the base and no
 * pair involving it is published - which is why every pair a group settles is derived rather than read
 * ({@code CrossRates}). The daily document carries one day; the bank's ninety-day document carries several, and
 * this parser takes the most recent of them, so both can be ingested and an older day cannot be taken by
 * accident.
 *
 * <p><strong>The publication instant is the publication, not the day.</strong> The cube's date is the date the
 * rates are for, and the bank publishes them at 16:00 Brussels time - so noon would make every rate look usable
 * four hours before it existed, and {@code FxRate.isUsableAt} refuses a rate quoted in the future precisely
 * because such a rate is not evidence of a price anyone could have transacted on. The zone is Brussels rather
 * than a fixed offset, so a rate published in summer is not dated an hour late in winter.
 *
 * <p><strong>One scale, taken from the document.</strong> The bank quotes some currencies with two fractional
 * digits (JPY 179.70) and some with five (GBP 0.86045). A ratio of two observations is only exact if both are
 * expressed at one scale, so the whole table is normalised to its own widest value - and since the scale cancels
 * in a ratio, choosing it cannot change a derived rate (property test in {@code libs/fx}). The digits are parsed
 * by hand: P1 forbids a binary floating-point type for money or a rate, and this is a rate arriving from outside
 * the process, which is exactly where one would be reached for.
 *
 * <p><strong>Not namespace-aware, deliberately.</strong> The document's elements are namespace-qualified
 * ({@code Cube} in the bank's vocabulary, the envelope in GESMES's), and the parser matches on the local name.
 * The namespaces are the provider's business: a provider that republishes its document under a different prefix
 * should not stop ingestion, and a provider that renames {@code Cube} should - the shape is what this reads.
 *
 * <p>Entity expansion is disabled and a document type declaration is refused. The document comes from outside
 * the process, which makes it the one place in this module where an XML parser is an attack surface rather than
 * a convenience.
 */
public final class EcbReferenceRates {

    /** The provider identifier recorded on every rate derived from its publication. */
    public static final String PROVIDER = "ecb";

    /** The currency every rate in the document is quoted against. */
    public static final String BASE_CODE = "EUR";

    /** When the bank publishes: 16:00 in Brussels, which is 14:00 UTC in summer and 15:00 UTC in winter. */
    static final LocalTime PUBLICATION_TIME = LocalTime.of(16, 0);

    static final ZoneId PUBLICATION_ZONE = ZoneId.of("Europe/Brussels");

    private EcbReferenceRates() {
    }

    /**
     * The most recent publication in the document.
     *
     * @throws InvalidRateException when the document holds nothing this parser can read as a publication, which
     *                              is a fault about the feed rather than a fact about the market
     */
    public static PublishedRates parse(String document) {
        Element root = documentOf(document).getDocumentElement();

        Map<String, String> quoted = new LinkedHashMap<>();
        LocalDate day = latestDay(root, quoted);

        int scale = widestScale(quoted);

        Map<String, Long> unitsPerBase = LinkedHashMap.newLinkedHashMap(quoted.size());
        for (Map.Entry<String, String> rate : quoted.entrySet()) {
            unitsPerBase.put(rate.getKey(), scaledOf(rate.getValue(), scale, rate.getKey()));
        }

        return new PublishedRates(
                PROVIDER, BASE_CODE, scale, publicationInstant(day), unitsPerBase);
    }

    /** A parsed, hardened document. */
    private static Document documentOf(String document) {
        if (document == null || document.isBlank()) {
            throw new InvalidRateException("the feed answered with nothing, so there is no publication in it");
        }

        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.setExpandEntityReferences(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);

            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(document)));
        } catch (ParserConfigurationException | SAXException | IOException unreadable) {
            throw new InvalidRateException(
                    "the feed did not answer with readable XML: " + unreadable.getMessage());
        }
    }

    /**
     * The most recent dated cube, and the rates inside it.
     *
     * <p>It fills {@code quoted} because a document holds one table and taking two would mean choosing which,
     * and choosing silently is how a rate from an older day ends up stored under today's work.
     */
    private static LocalDate latestDay(Element root, Map<String, String> quoted) {
        NodeList cubes = root.getElementsByTagName("Cube");

        LocalDate latest = null;
        Element newest = null;

        for (int i = 0; i < cubes.getLength(); i++) {
            Element cube = (Element) cubes.item(i);

            if (!cube.hasAttribute("time")) {
                continue;
            }

            LocalDate day = parseDay(cube.getAttribute("time"));

            if (latest == null || day.isAfter(latest)) {
                latest = day;
                newest = cube;
            }
        }

        if (newest == null) {
            throw new InvalidRateException(
                    "the feed holds no dated table, so it is not a publication of reference rates");
        }

        NodeList rates = newest.getChildNodes();

        for (int i = 0; i < rates.getLength(); i++) {
            if (rates.item(i).getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }

            Element rate = (Element) rates.item(i);

            if (rate.hasAttribute("currency") && rate.hasAttribute("rate")) {
                quoted.put(rate.getAttribute("currency"), rate.getAttribute("rate"));
            }
        }

        return latest;
    }

    private static LocalDate parseDay(String text) {
        try {
            return LocalDate.parse(text);
        } catch (DateTimeParseException malformed) {
            throw new InvalidRateException("the feed dated a table '" + text + "', which is not a date");
        }
    }

    /**
     * The publication instant: the day the rate applies to, at the time the bank publishes.
     *
     * <p>The date arrives as a plain day and the time is the provider's habit rather than something the document
     * states. That is a fact about this provider, which is what this class is - and it is the difference between
     * a rate that can be locked at 09:00 on the day it was published and one that cannot, since a quote may not
     * be locked before it existed.
     */
    static Instant publicationInstant(LocalDate day) {
        return day.atTime(PUBLICATION_TIME).atZone(PUBLICATION_ZONE).toInstant();
    }

    /** The widest fractional part in the document, which is the scale the whole table is expressed at. */
    private static int widestScale(Map<String, String> quoted) {
        int widest = 0;

        for (String rate : quoted.values()) {
            widest = Math.max(widest, fractionDigits(rate));
        }

        return widest;
    }

    private static int fractionDigits(String rate) {
        int dot = rate.indexOf('.');

        return dot < 0 ? 0 : rate.length() - dot - 1;
    }

    /**
     * A published rate as a scaled integer at the table's own scale.
     *
     * <p>Digits only - the whole part, then the fraction padded to the scale. No value is rounded: the scale is
     * the widest in the document, so every other rate is a shorter fraction of the same number rather than a
     * number needing a decision, and a document that contradicted that is refused instead of rounded.
     */
    static long scaledOf(String rate, int scale, String code) {
        int dot = rate.indexOf('.');
        String whole = dot < 0 ? rate : rate.substring(0, dot);
        String fraction = dot < 0 ? "" : rate.substring(dot + 1);

        if (fraction.length() > scale) {
            throw new InvalidRateException(PROVIDER + " published " + code + " as '" + rate + "', which has more "
                    + "fractional digits than the widest rate in the same document (" + scale + ")");
        }

        StringBuilder digits = new StringBuilder(whole).append(fraction);

        for (int i = fraction.length(); i < scale; i++) {
            digits.append('0');
        }

        try {
            return Long.parseLong(digits.toString());
        } catch (NumberFormatException notANumber) {
            throw new InvalidRateException(
                    PROVIDER + " published " + code + " as '" + rate + "', which is not a number");
        }
    }
}
