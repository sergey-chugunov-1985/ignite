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

package org.apache.ignite.internal.processors.cache;

import org.apache.ignite.IgniteCache;
import org.apache.ignite.cache.CacheMode;
import org.apache.ignite.configuration.CacheConfiguration;
import org.apache.ignite.configuration.DeploymentMode;
import org.apache.ignite.configuration.IgniteConfiguration;
import org.apache.ignite.testframework.junits.common.GridCommonAbstractTest;
import org.junit.Test;

/**
 * Test verifies that it's possible to start caches in Isolated and Private mode when BinaryMarshaller is used.
 */
public class CacheStartupInDeploymentModesTest extends GridCommonAbstractTest {
    /** */
    private static final String REPLICATED_CACHE = "replicated";

    /** */
    private static final String PARTITIONED_CACHE = "partitioned";

    /** */
    private DeploymentMode deploymentMode;

    /** {@inheritDoc} */
    @Override protected IgniteConfiguration getConfiguration(String igniteInstanceName) throws Exception {
        IgniteConfiguration cfg = super.getConfiguration(igniteInstanceName);

        cfg.setDeploymentMode(deploymentMode);

        CacheConfiguration cacheCfg1 = new CacheConfiguration(DEFAULT_CACHE_NAME);
        cacheCfg1.setCacheMode(CacheMode.REPLICATED);
        cacheCfg1.setName(REPLICATED_CACHE);

        CacheConfiguration cacheCfg2 = new CacheConfiguration(DEFAULT_CACHE_NAME);
        cacheCfg2.setCacheMode(CacheMode.PARTITIONED);
        cacheCfg2.setName(PARTITIONED_CACHE);

        cfg.setCacheConfiguration(cacheCfg1, cacheCfg2);

        return cfg;
    }

    /** {@inheritDoc} */
    @Override protected void afterTest() throws Exception {
        stopAllGrids();

        super.afterTest();
    }

    /**
     * @throws Exception If fail.
     */
    @Test
    public void testStartedInIsolatedMode() throws Exception {
        deploymentMode = DeploymentMode.ISOLATED;

        doCheckStarted(deploymentMode);
    }

    /**
     * @throws Exception If fail.
     */
    @Test
    public void testStartedInPrivateMode() throws Exception {
        deploymentMode = DeploymentMode.PRIVATE;

        doCheckStarted(deploymentMode);
    }

    /**
     * @param mode Deployment mode.
     * @throws Exception If failed.
     */
    private void doCheckStarted(DeploymentMode mode) throws Exception {
        startGridsMultiThreaded(2);

        checkTopology(2);

        assertEquals(mode, ignite(0).configuration().getDeploymentMode());

        IgniteCache rCache = ignite(0).cache(REPLICATED_CACHE);

        checkPutCache(rCache);

        IgniteCache pCache = ignite(0).cache(PARTITIONED_CACHE);

        checkPutCache(pCache);
    }

    /**
     * @param cache IgniteCache
     */
    private void checkPutCache(IgniteCache cache) {
        for (int i = 0; i < 10; i++) {
            Organization val = new Organization();

            val.setId(i);
            val.setName("Org " + i);

            cache.put(i, val);
        }

        for (int i = 0; i < 10; i++) {
            Organization org = (Organization)cache.get(i);

            assertEquals(i, org.getId());
        }
    }

    /**
     * Cache value class.
     */
    private static class Organization {

        /**
         * Identifier.
         */
        private int id;

        /**
         * Name.
         */
        private String name;

        /**
         * Default constructor.
         */
        public Organization() {
        }

        /**
         * @return Identifier.
         */
        public int getId() {
            return id;
        }

        /**
         * @param id Identifier.
         */
        public void setId(int id) {
            this.id = id;
        }

        /**
         * @return Name.
         */
        public String getName() {
            return name;
        }

        /**
         * @param name Name.
         */
        public void setName(String name) {
            this.name = name;
        }

        /** {@inheritDoc} */
        @Override public String toString() {
            return "Organization{" +
                "id=" + id +
                ", name='" + name + '\'' +
                '}';
        }
    }
}
