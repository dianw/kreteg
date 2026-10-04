package io.kreteg.core.nativeimage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.jar.JarFile;

import org.graalvm.nativeimage.hosted.Feature;
import org.graalvm.nativeimage.hosted.RuntimeReflection;

/**
 * Native-image registrations the A2A SDK does not ship itself:
 * <ul>
 *   <li>every class in {@code io.a2a.spec}, because the SDK (de)serialises them with its own plain Jackson
 *       {@code ObjectMapper}, which Quarkus does not know about;</li>
 *   <li>the JSON-RPC transport provider, which the SDK discovers via {@link java.util.ServiceLoader}
 *       (the matching {@code META-INF/services} file is included in application.properties).</li>
 * </ul>
 * Enabled via {@code quarkus.native.additional-build-args}. Scanning the jar keeps this correct across SDK upgrades.
 */
public class A2aNativeFeature implements Feature {

    private static final String SPEC_PACKAGE = "io/a2a/spec/";
    private static final String TRANSPORT_PROVIDER = "io.a2a.server.apps.quarkus.QuarkusJSONRPCTransportMetadata";

    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        Class<?> anchor = access.findClassByName("io.a2a.spec.Task");
        try (JarFile jar = new JarFile(jarOf(anchor).toFile())) {
            jar.stream()
                    .map(e -> e.getName())
                    .filter(n -> n.startsWith(SPEC_PACKAGE) && n.endsWith(".class") && !n.endsWith("package-info.class"))
                    .map(n -> n.substring(0, n.length() - ".class".length()).replace('/', '.'))
                    .map(access::findClassByName)
                    .forEach(A2aNativeFeature::registerFully);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        Class<?> transport = access.findClassByName(TRANSPORT_PROVIDER);
        RuntimeReflection.register(transport);
        RuntimeReflection.register(transport.getDeclaredConstructors());
    }

    private static void registerFully(Class<?> c) {
        if (c == null) {
            return;
        }
        RuntimeReflection.register(c);
        RuntimeReflection.register(c.getDeclaredConstructors());
        RuntimeReflection.register(c.getDeclaredMethods());
        RuntimeReflection.register(c.getDeclaredFields());
        if (c.isRecord()) {
            RuntimeReflection.registerAllRecordComponents(c);
        }
    }

    private static Path jarOf(Class<?> c) {
        try {
            return Path.of(c.getProtectionDomain().getCodeSource().getLocation().toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }
}
