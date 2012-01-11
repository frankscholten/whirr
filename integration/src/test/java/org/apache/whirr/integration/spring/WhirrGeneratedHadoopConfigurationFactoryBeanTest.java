/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.whirr.integration.spring;

import org.apache.commons.configuration.ConfigurationException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.junit.Before;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class WhirrGeneratedHadoopConfigurationFactoryBeanTest {

    private WhirrGeneratedHadoopConfigurationFactoryBean factory;

    @Before
    public void before() throws ConfigurationException {
        factory = new WhirrGeneratedHadoopConfigurationFactoryBean(new ClassPathResource("whirr-hadoop-test-single.properties")) {
            @Override
            protected Path getConfigurationPath(String clusterName) {
                return new Path("src/test/resources/test-hadoop-site.xml");
            }
        };
    }

    @Test
    public void testIsSingleton() throws ConfigurationException {
        assertFalse(factory.isSingleton());
    }

    @Test
    public void testGetObjectType() throws ConfigurationException {
        assertEquals(Configuration.class, factory.getObjectType());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorWithNullConfiguration() throws ConfigurationException {
        new WhirrGeneratedHadoopConfigurationFactoryBean(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testConstructorWithNonExistingConfiguration() throws ConfigurationException {
        new WhirrGeneratedHadoopConfigurationFactoryBean(new FileSystemResource("nonExisting"));
    }

    @Test
    public void testGetObject() throws Exception {
        Configuration configuration = factory.getObject();

        assertEquals("hdfs://testhost:54310", configuration.get("fs.default.name"));
        assertEquals("hdfs://testhost:54311", configuration.get("mapred.job.tracker"));
        assertEquals("6", configuration.get("dfs.replication"));
        assertEquals("-Xmx512m", configuration.get("mapred.child.java.opts"));
    }

    @Test
    public void testGetConfigurationPath() throws ConfigurationException {
        String original = System.getProperty("user.home");
        System.setProperty("user.home", "/home/test");

        factory = new WhirrGeneratedHadoopConfigurationFactoryBean(new ClassPathResource("whirr-hadoop-test-single.properties"));
        assertEquals("/home/test/.whirr/test-cluster/hadoop-site.xml", factory.getConfigurationPath("test-cluster").toString());

        System.setProperty("user.home", original);
    }
}
