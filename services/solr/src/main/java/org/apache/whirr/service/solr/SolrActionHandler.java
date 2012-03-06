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

package org.apache.whirr.service.solr;

import static com.google.common.io.ByteStreams.newInputStreamSupplier;
import static org.apache.whirr.RolePredicates.role;
import static org.jclouds.scriptbuilder.domain.Statements.call;

import java.io.IOException;
import java.util.List;
import java.util.Set;

import org.apache.commons.configuration.Configuration;
import org.apache.whirr.Cluster;
import org.apache.whirr.Cluster.Instance;
import org.apache.whirr.ClusterSpec;
import org.apache.whirr.service.ClusterActionEvent;
import org.apache.whirr.service.ClusterActionHandlerSupport;
import org.apache.whirr.service.FirewallManager.Rule;
import org.apache.whirr.service.zookeeper.ZooKeeperCluster;
import static org.apache.whirr.service.zookeeper.ZooKeeperClusterActionHandler.ZOOKEEPER_ROLE;
import org.jclouds.crypto.CryptoStreams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Function;
import com.google.common.collect.Lists;

public class SolrActionHandler extends ClusterActionHandlerSupport {

  private static final Logger LOG = LoggerFactory.getLogger(SolrActionHandler.class);

  public static final String SOLR_ROLE = "solr";
  private static final String CONFIG = "whirr-solr-default.properties";
  private static final String SOLR_HOME = "/usr/local/solr-4.0";
  private static final String SOLR_CONF_DESTINATION = SOLR_HOME + "/conf";

  @Override
  public String getRole() {
    return SOLR_ROLE;
  }


  @Override
  protected void beforeBootstrap(ClusterActionEvent event) throws IOException {
    ClusterSpec clusterSpec = event.getClusterSpec();
    Configuration config = getConfiguration(clusterSpec, CONFIG);

    String solrTarball = config.getString("whirr.solr.tarball");
    if(!solrTarball.matches("^.*apache-solr-4\\.0.*(tgz|tar\\.gz)$")) {
      throw new IllegalArgumentException("Must specify a Solr 4.0 tarball");
    }
    // Call the installers
    addStatement(event, call(getInstallFunction(config, "java", "install_java")));
    addStatement(event, call("install_tarball"));

    String installFunc = getInstallFunction(config, getRole(), "install_" + getRole());
    LOG.info("Installing Solr");
    addStatement(event, call(installFunc,
        solrTarball,
        SOLR_HOME
    ));
  }

  @Override
  protected void beforeConfigure(ClusterActionEvent event) throws IOException {
    LOG.info("Configure Solr");

    ClusterSpec clusterSpec = event.getClusterSpec();
    Cluster cluster = event.getCluster();
    Configuration config = getConfiguration(clusterSpec, CONFIG);

    // Validate the config
    int jettyPort = config.getInt("whirr.solr.jetty.port");
    int jettyStopPort = config.getInt("whirr.solr.jetty.stop.port");
    if(jettyPort == jettyStopPort) {
      throw new IllegalArgumentException("Jetty's port and the stop port must be different");
    }

    // Check for ZooKeeper
    Set<Instance> zks = cluster.getInstancesMatching(role(ZOOKEEPER_ROLE));
    if(zks == null || zks.size() == 0) {
      throw new IllegalArgumentException("Need at least one ZooKeeper node defined for Solr's cloud functionallity");
    }

    // Open up Jetty port
    event.getFirewallManager().addRule(Rule.create().destination(role(SOLR_ROLE)).port(jettyPort));
  }

  @Override
  protected void beforeStart(ClusterActionEvent event) throws IOException {
    ClusterSpec clusterSpec = event.getClusterSpec();
    Cluster cluster = event.getCluster();
    Configuration config = getConfiguration(clusterSpec, CONFIG);

    String solrConfigSource = prepareRemoteFileUrl(event, config.getString("whirr.solr.config"));
    addStatement(event, call("move_file", solrConfigSource, SOLR_CONF_DESTINATION));

    String solrSchemaSource = prepareRemoteFileUrl(event, config.getString("whirr.solr.schema"));
    addStatement(event, call("move_file", solrSchemaSource, SOLR_CONF_DESTINATION));

    int jettyPort = config.getInt("whirr.solr.jetty.port");
    int jettyStopPort = config.getInt("whirr.solr.jetty.stop.port");
    String zkEnsemble = ZooKeeperCluster.getHosts(cluster);
    LOG.info("ZK ENSEMBLE: " + zkEnsemble);
    // Start up Solr
    String startFunc = getStartFunction(config, getRole(), "start_" + getRole());
    LOG.info("Starting up Solr");

    addStatement(event, call(startFunc,
        String.valueOf(jettyPort),
        String.valueOf(jettyStopPort),
        safeSecretString(config.getString("whirr.solr.jetty.stop.secret")),
        SOLR_HOME,
        zkEnsemble,
        String.valueOf(config.getInt("whirr.solr.shards.num")),
        config.getString("whirr.solr.collection.name"),
        SOLR_HOME + "/example/solr/conf",
        SOLR_HOME + "/example/start.jar",
        config.getString("whirr.solr.java.opts", "")
    ));
  }

  @Override
  protected void afterStart(ClusterActionEvent event) throws IOException {
    ClusterSpec clusterSpec = event.getClusterSpec();
    Cluster cluster = event.getCluster();
    Configuration config = getConfiguration(clusterSpec, CONFIG);
    int jettyPort = config.getInt("whirr.solr.jetty.port");
    LOG.info("Completed configuration of {}", clusterSpec.getClusterName());
    LOG.info("Solr Hosts: {}", getHosts(cluster.getInstancesMatching(role(SOLR_ROLE)), jettyPort));
  }

  @Override
  protected void beforeStop(ClusterActionEvent event) throws IOException {
    ClusterSpec clusterSpec = event.getClusterSpec();
    Configuration config = getConfiguration(clusterSpec, CONFIG);
    int jettyStopPort = config.getInt("whirr.solr.jetty.stop.port");
    String stopFunc = getStopFunction(config, getRole(), "stop_" + getRole());
    LOG.info("Stopping Solr");
    addStatement(event, call(stopFunc,
      SOLR_HOME,
      String.valueOf(jettyStopPort),
      safeSecretString(config.getString("whirr.solr.jetty.stop.secret")),
      SOLR_HOME + "/example/start.jar"
    ));
  }

  static List<String> getHosts(Set<Instance> instances, final int port) {
    return Lists.transform(Lists.newArrayList(instances), new Function<Instance, String>(){
      @Override
      public String apply(Instance instance) {
        try {
          String publicIp = instance.getPublicHostName();
          return String.format("%s:%d", publicIp, port);
        } catch (IOException e) {
          throw new IllegalArgumentException(e);
        }
      }
    });
  }

  static String safeSecretString(String value) throws IOException {
    return CryptoStreams.md5Hex(newInputStreamSupplier(("NaCl#" + value).getBytes()));
  }

}
