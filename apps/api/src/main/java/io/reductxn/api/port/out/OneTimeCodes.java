package io.reductxn.api.port.out;

/**
 * Where a one-time code goes.
 *
 * <p><strong>A port rather than a call, because this deployment has no SMS provider and pretending otherwise would be
 * the lie.</strong> The contract's operation is "request an OTP for a phone number", and what actually happens in this
 * build is that the code is written to the log - which is a real answer for a single-machine development deployment and
 * is recorded as the gap it is (ADR 0042). What matters is that the *service* does not know which of those it is: it
 * hands the code to this port, and an adapter that talks to a provider replaces the logging one without the rule "a code
 * expires in five minutes" changing by a character.
 *
 * <p>The code travels as plain text here and only here, because it has to: everything downstream of this port stores a
 * hash. That is also why the interface takes the phone number and the code rather than a message - composing what to say
 * is the adapter's, and a service that wrote the message would be a service that knows a channel.
 */
public interface OneTimeCodes {

    /** Delivers {@code code} to the person who holds {@code phoneNumber}. */
    void deliver(String phoneNumber, String code);
}
