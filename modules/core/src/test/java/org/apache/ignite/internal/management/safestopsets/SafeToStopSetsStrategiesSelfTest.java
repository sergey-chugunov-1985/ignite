/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.ignite.internal.management.safestopsets;

import java.net.MalformedURLException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.ServiceConfigurationError;
import org.apache.ignite.IgniteException;
import org.junit.Test;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Tests the {@link SafeToStopSetsStrategies} resolver without a cluster: the default strategy on a
 * {@code null} or empty name, name-based resolution, unknown names, an empty provider set, duplicate
 * names and a broken provider.
 * <p>
 * Every scenario runs with an isolated thread context class loader that sees exactly the providers of
 * the scenario in the strategy service file, not the merged classpath set: {@code ServiceLoader} resolves
 * the providers through the context class loader, so swapping it isolates the registry without touching
 * the shared test service file.
 */
public class SafeToStopSetsStrategiesSelfTest {
    /** Name of the {@code META-INF/services} file of the strategy SPI. */
    private static final String SERVICES_FILE =
        "META-INF/services/org.apache.ignite.internal.management.safestopsets.SafeToStopSetsStrategy";

    /**
     * A {@code null} or empty strategy name must yield the default strategy without touching the
     * classpath: it must resolve even when the classpath carries no providers at all.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testDefaultStrategyWithoutClasspath() throws Exception {
        assertTrue(SafeToStopSetsStrategies.resolve(null) instanceof BackupsCountSafeToStopSetsStrategy);
        assertTrue(SafeToStopSetsStrategies.resolve("") instanceof BackupsCountSafeToStopSetsStrategy);

        withServicesFile(Collections.emptyList(), () -> {
            assertTrue(SafeToStopSetsStrategies.resolve(null) instanceof BackupsCountSafeToStopSetsStrategy);
            assertTrue(SafeToStopSetsStrategies.resolve("") instanceof BackupsCountSafeToStopSetsStrategy);
        });
    }

    /**
     * A registered name must resolve to the matching implementation.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testResolveByName() throws Exception {
        withServicesFile(Arrays.asList(
            BackupsCountSafeToStopSetsStrategy.class.getName(),
            TestOwnersSafeToStopSetsStrategy.class.getName()
        ), () -> {
            assertTrue(SafeToStopSetsStrategies.resolve(BackupsCountSafeToStopSetsStrategy.NAME)
                instanceof BackupsCountSafeToStopSetsStrategy);
            assertTrue(SafeToStopSetsStrategies.resolve(TestOwnersSafeToStopSetsStrategy.NAME)
                instanceof TestOwnersSafeToStopSetsStrategy);
        });
    }

    /**
     * An unknown name must fail with a usage-level error listing the available names, not a runtime one.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testUnknownName() throws Exception {
        withServicesFile(Arrays.asList(
            BackupsCountSafeToStopSetsStrategy.class.getName(),
            TestOwnersSafeToStopSetsStrategy.class.getName()
        ), () -> {
            try {
                SafeToStopSetsStrategies.resolve("nope");

                fail("Unknown strategy name must fail");
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("nope"));
                assertTrue(e.getMessage(), e.getMessage().contains(BackupsCountSafeToStopSetsStrategy.NAME));
                assertTrue(e.getMessage(), e.getMessage().contains(TestOwnersSafeToStopSetsStrategy.NAME));
            }
        });
    }

    /**
     * When no provider is registered, the unknown-name error must report an empty available set instead of
     * failing obscurely.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testNoProviders() throws Exception {
        withServicesFile(Collections.emptyList(), () -> {
            try {
                SafeToStopSetsStrategies.resolve(BackupsCountSafeToStopSetsStrategy.NAME);

                fail("A missing provider must fail");
            } catch (IllegalArgumentException e) {
                assertTrue(e.getMessage(), e.getMessage().contains("[]"));
            }
        });
    }

    /**
     * Two providers sharing a name must fail deterministically, regardless of the requested name (the
     * duplicate is detected while scanning the registry, not when matching the name), with both
     * implementations named in the message, and as a usage error: the operator has to see the conflicting
     * classes, not an internal error.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testDuplicateName() throws Exception {
        withServicesFile(Arrays.asList(
            BackupsCountSafeToStopSetsStrategy.class.getName(),
            TestDuplicateNameSafeToStopSetsStrategy.class.getName()
        ), () -> {
            assertDuplicateName(BackupsCountSafeToStopSetsStrategy.NAME);
            assertDuplicateName(TestOwnersSafeToStopSetsStrategy.NAME);
        });
    }

    /**
     * A provider that fails to instantiate must surface as an {@link IgniteException} carrying the
     * provider's fully qualified class name and the underlying failure.
     *
     * @throws Exception If failed.
     */
    @Test
    public void testBrokenProvider() throws Exception {
        withServicesFile(Collections.singletonList(BrokenSafeToStopSetsStrategy.class.getName()), () -> {
            try {
                SafeToStopSetsStrategies.resolve("test-broken");

                fail("A broken provider must fail");
            } catch (IgniteException e) {
                String msg = e.getMessage();

                assertTrue(msg, msg.contains(BrokenSafeToStopSetsStrategy.class.getName()));
                assertTrue(e.getCause() instanceof ServiceConfigurationError);
                assertTrue(causeChainContains(e, "Simulated provider failure"));
            }
        });
    }

    /**
     * @param requestedName Name passed to the resolver.
     */
    private static void assertDuplicateName(String requestedName) {
        try {
            SafeToStopSetsStrategies.resolve(requestedName);

            fail("Duplicate strategy name must fail");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains(BackupsCountSafeToStopSetsStrategy.class.getName()));
            assertTrue(e.getMessage(), e.getMessage().contains(TestDuplicateNameSafeToStopSetsStrategy.class.getName()));
        }
    }

    /**
     * @param cause Chain head.
     * @param message Message to look for in the chain.
     * @return {@code true} when some cause carries the message.
     */
    private static boolean causeChainContains(Throwable cause, String message) {
        for (Throwable t = cause; t != null; t = t.getCause()) {
            if (message.equals(t.getMessage()))
                return true;
        }

        return false;
    }

    /**
     * Runs the body with the thread context class loader serving exactly the given providers in the
     * strategy service file, instead of the merged classpath set.
     *
     * @param providers Fully qualified class names, one per line of the service file.
     * @param body Test body.
     * @throws Exception If failed.
     */
    private static void withServicesFile(List<String> providers, ServiceScope body) throws Exception {
        Path dir = Files.createTempDirectory("sts-strategies");
        Path file = dir.resolve(SERVICES_FILE);

        Files.createDirectories(file.getParent());
        Files.write(file, providers, StandardCharsets.UTF_8);

        ClassLoader original = Thread.currentThread().getContextClassLoader();

        Thread.currentThread().setContextClassLoader(isolatedLoader(dir));

        try {
            body.run();
        } finally {
            Thread.currentThread().setContextClassLoader(original);

            Files.deleteIfExists(file);
            Files.deleteIfExists(file.getParent());
            Files.deleteIfExists(dir.resolve("META-INF"));
            Files.deleteIfExists(dir);
        }
    }

    /**
     * Class loader that delegates everything to the class loader of this test except for the strategy
     * service file, which it serves from the given directory only (or not at all when the file is absent).
     *
     * @param dir Directory holding the {@code META-INF/services/...} tree of the scenario.
     * @return The isolating class loader.
     */
    private static ClassLoader isolatedLoader(Path dir) {
        return new ClassLoader(SafeToStopSetsStrategiesSelfTest.class.getClassLoader()) {
            /** {@inheritDoc} */
            @Override public URL getResource(String name) {
                if (!SERVICES_FILE.equals(name))
                    return super.getResource(name);

                Path file = dir.resolve(name);

                if (!Files.exists(file))
                    return null;

                try {
                    return file.toUri().toURL();
                } catch (MalformedURLException e) {
                    throw new IllegalStateException(e);
                }
            }

            /** {@inheritDoc} */
            @Override public Enumeration<URL> getResources(String name) {
                URL url = getResource(name);

                return url == null
                    ? Collections.emptyEnumeration()
                    : Collections.enumeration(Collections.singletonList(url));
            }
        };
    }

    /**
     * Body of a test executed with an isolated set of strategy providers.
     */
    @FunctionalInterface
    private interface ServiceScope {
        /**
         * @throws Exception If failed.
         */
        void run() throws Exception;
    }
}
