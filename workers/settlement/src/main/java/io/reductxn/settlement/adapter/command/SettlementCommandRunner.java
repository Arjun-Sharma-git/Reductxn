package io.reductxn.settlement.adapter.command;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.OptionalInt;

/**
 * Runs {@link SettlementCommand} when the arguments name one, and exits the process with its code.
 *
 * <p>The exit is here and not in the command, so that the command's decision and the process's death are
 * separable: the command answers with a code, and this class is the only thing that ends a JVM. That is also
 * what makes the command testable - a test calls it with its own {@link java.io.PrintStream} and reads the code,
 * where a command that called {@code System.exit} would take the test's JVM with it.
 *
 * <p><strong>Nothing happens when there is no command.</strong> This is the daemon path: the worker starts, the
 * cadence drives runs, and this runner returns without exiting. The two modes are the two honest ways to run a
 * settlement worker - a daemon that drives on a timer, or a scheduled job that does one pass and exits - and the
 * same command is available in both, because a worker with the cadence switched off is still a worker somebody
 * may want to poke.
 *
 * <p>{@code SpringApplication.exit} runs the shutdown hooks before the code is returned, so the connection pool
 * and Flyway's lock are released the way they would be on a signal rather than by the JVM being torn down
 * mid-flight.
 */
@Component
public class SettlementCommandRunner implements ApplicationRunner {

    private final SettlementCommand command;
    private final ApplicationContext context;

    public SettlementCommandRunner(SettlementCommand command, ApplicationContext context) {
        this.command = command;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        OptionalInt code = command.execute(args, System.out);

        if (code.isPresent()) {
            System.exit(SpringApplication.exit(context, code::getAsInt));
        }
    }
}
