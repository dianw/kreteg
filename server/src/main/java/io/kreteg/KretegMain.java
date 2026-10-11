package io.kreteg;

import java.util.Arrays;

import io.kreteg.cli.Cli;
import io.quarkus.runtime.Quarkus;
import io.quarkus.runtime.annotations.QuarkusMain;

/**
 * Entry point of the {@code kreteg} binary, which is both the hub and its command-line client. The choice is made
 * before Quarkus boots, so client commands never open the database or the server port:
 * <ul>
 * <li>{@code serve [args]}, or no arguments: the hub (the service units the install scripts write run
 * {@code kreteg serve}; units from older installs run it bare);
 * <li>a first argument starting with {@code -D}: the hub too, with system properties, as
 * {@code @QuarkusIntegrationTest} starts it;
 * <li>anything else, {@code --help} and {@code --version} included: the client ({@link Cli}).
 * </ul>
 */
@QuarkusMain
public class KretegMain {

    public static void main(String... args) {
        if (args.length == 0 || args[0].startsWith("-D")) {
            Quarkus.run(args);
        } else if (args[0].equals("serve")) {
            Quarkus.run(Arrays.copyOfRange(args, 1, args.length));
        } else {
            System.exit(Cli.run(args));
        }
    }
}
