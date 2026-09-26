package io.reductxn.api.adapter.identity;

import io.reductxn.api.port.out.OneTimeCodes;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Where a one-time code goes in this deployment: the log.
 *
 * <p><strong>This is a stand-in, and it says so rather than pretending to be a provider.</strong> There is no SMS
 * gateway, no email and no push in this build, so "delivering" a code means writing it where an operator of a
 * single-machine deployment can read it - which is a real answer for development and a hole in production, recorded as
 * such (ADR 0042). The port exists so that the answer is one class rather than a property of the sign-in rule: a
 * deployment with a provider replaces this component and nothing above it changes.
 *
 * <p><strong>The log line names the number and the code, and nothing else.</strong> No user id, no session, no request
 * path: a code is the only thing an operator needs to see, and a log that carried more would be a log that leaks more.
 * The level is {@code WARN} rather than {@code INFO} on purpose - a production profile that has this adapter in it should
 * be shouting in every log aggregation tool somebody looks at, which is the loudest way a stand-in can announce itself
 * from inside a running system.
 */
@Component
public class LoggingOneTimeCodes implements OneTimeCodes {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingOneTimeCodes.class);

    @Override
    public void deliver(String phoneNumber, String code) {
        LOG.warn("no SMS provider is configured, so this code is going to the log: {} for {}", code, phoneNumber);
    }
}
