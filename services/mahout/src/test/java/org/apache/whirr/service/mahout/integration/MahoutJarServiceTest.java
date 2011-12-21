/**
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

package org.apache.whirr.service.mahout.integration;

import org.apache.commons.configuration.CompositeConfiguration;
import org.apache.commons.configuration.PropertiesConfiguration;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.util.ToolRunner;
import org.apache.mahout.text.SequenceFilesFromDirectory;
import org.apache.mahout.vectorizer.SparseVectorsFromSequenceFiles;
import org.apache.whirr.Cluster;
import org.apache.whirr.ClusterController;
import org.apache.whirr.ClusterSpec;
import org.apache.whirr.service.hadoop.HadoopProxy;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

/**
 * Installs the Mahout examples jar on tasktrackers
 */
public class MahoutJarServiceTest {

    private static final Logger LOG = LoggerFactory.getLogger(MahoutServiceTest.class);

    private static ClusterSpec clusterSpec;
    private static ClusterController controller;
    private static Cluster cluster;

    @BeforeClass
    public static void setUp() throws Exception {
        CompositeConfiguration config = new CompositeConfiguration();
        if (System.getProperty("config") != null) {
            config.addConfiguration(new PropertiesConfiguration(System.getProperty("config")));
        }
        config.addConfiguration(new PropertiesConfiguration("whirr-mahout-jar-test.properties"));
        clusterSpec = ClusterSpec.withTemporaryKeys(config);
        controller = new ClusterController();
        cluster = controller.launchCluster(clusterSpec);

    }

    @AfterClass
    public static void tearDown() throws IOException, InterruptedException {
        if (controller != null) {
            controller.destroyCluster(clusterSpec);
        }
    }

    @Test
    public void testJarRole() throws Exception {
        HadoopProxy proxy = new HadoopProxy(clusterSpec, cluster);
        proxy.start();

        // TODO: Upload data

        submitMahoutJob();

        // TODO: Download results

        // TODO: Check results

        proxy.stop();
    }

    private void submitMahoutJob() throws Exception {
        Configuration configuration = getHadoopConfiguration();

        LOG.info("Submitting Mahout job");

        LOG.info("Running seqdirectory");
        ToolRunner.run(configuration, new SequenceFilesFromDirectory(), new String[]{
                "--input", "/usr/local/hadoop/README",
                "--output", "seqfiles",
                "--charset", "utf-8"
        });

        LOG.info("Running seq2sparse");
        ToolRunner.run(configuration, new SparseVectorsFromSequenceFiles(), new String[]{
                "--input", "seqfiles",
                "--output", "sparse-vectors",
                "--namedVector",
                "--minDF", "4",
                "--maxDFPercent", "75",
                "--weight", "TFIDF",
                "--norm", "2"
        });
    }

    public Configuration getHadoopConfiguration() {
        Configuration conf = new Configuration();
        for (Map.Entry<Object, Object> entry : cluster.getConfiguration().entrySet()) {
            conf.set(entry.getKey().toString(), entry.getValue().toString());
        }
        return conf;
    }
}
