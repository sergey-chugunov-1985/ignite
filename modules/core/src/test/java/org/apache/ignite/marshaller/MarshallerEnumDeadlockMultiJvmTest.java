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

package org.apache.ignite.marshaller;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.ignite.Ignite;
import org.apache.ignite.lang.IgniteCallable;
import org.apache.ignite.resources.IgniteInstanceResource;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.junit.Test;

/**
 * Contains test of Enum marshalling with various {@link Marshaller}s. See IGNITE-8547 for details.
 */
public class MarshallerEnumDeadlockMultiJvmTest extends GridCommonAbstractTest {
    /** */
    @Test
    public void testBinaryMarshaller() throws Exception {
        Ignite ignite = startGrid(0);

        byte[] one = marshaller(ignite).marshal(DeclaredBodyEnum.ONE);
        byte[] two = marshaller(ignite).marshal(DeclaredBodyEnum.TWO);

        startGrid(1);

        ignite.compute(ignite.cluster().forRemotes()).call(new UnmarshalCallable(one, two));
    }

    /** {@inheritDoc} */
    @Override protected boolean isMultiJvm() {
        return true;
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        stopAllGrids();
    }

    /**
     * Attempts to unmarshal both in-built and inner-class enum values at exactly the same time in multiple threads.
     */
    private static class UnmarshalCallable implements IgniteCallable<Object> {
        /** */
        private final byte[] one;

        /** */
        private final byte[] two;

        /** */
        @IgniteInstanceResource
        private Ignite ign;

        /** */
        public UnmarshalCallable(byte[] one, byte[] two) {
            this.one = one;
            this.two = two;
        }

        /** {@inheritDoc} */
        @Override public Object call() throws Exception {
            ExecutorService executor = Executors.newFixedThreadPool(2);

            final CyclicBarrier start = new CyclicBarrier(2);

            for (int i = 0; i < 2; i++) {
                final int ii = i;

                executor.execute(new Runnable() {
                    @Override public void run() {
                        try {
                            start.await();

                            if (ii == 0)
                                marshaller(ign).unmarshal(one, null);
                            else
                                marshaller(ign).unmarshal(two, null);
                        }
                        catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                });
            }

            try {
                executor.shutdown();

                executor.awaitTermination(5, TimeUnit.SECONDS);

                if (!executor.isTerminated())
                    throw new IllegalStateException("Failed to wait for completion");
            }
            catch (Exception te) {
                throw new IllegalStateException("Failed to wait for completion", te);
            }

            return null;
        }
    }

    /** */
    public enum DeclaredBodyEnum {
        /** */
        ONE,

        /** */
        TWO {
            /** {@inheritDoc} */
            @Override public boolean isSupported() {
                return false;
            }
        };

        /**
         * A bogus method.
         */
        public boolean isSupported() {
            return true;
        }
    }
}
