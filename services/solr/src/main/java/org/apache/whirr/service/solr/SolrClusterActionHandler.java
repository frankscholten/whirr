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
import org.apache.commons.lang.StringUtils;
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

public class SolrClusterActionHandler extends ClusterActionHandlerSupport {

  private static final Logger LOG = LoggerFactory.getLogger(SolrClusterActionHandler.class);

  public final static String SOLR_ROLE = "solr";

  final static String SOLR_DEFAULT_CONFIG = "whirr-solr-default.properties";

  final static String SOLR_HOME = "/usr/local/solr";

  final static String SOLR_CONF_DESTINATION = SOLR_HOME + "/conf";

  // TODO: Create constants for solr properties

  @Override
  public String getRole() {
    return SOLR_ROLE;
  }

  @Override
  protected void beforeBootstrap(ClusterActionEvent event) throws IOException {
    Configuration config = getConfiguration(event.getClusterSpec(), SOLR_DEFAULT_CONFIG);

    String solrTarball = config.getString("whirr.solr.tarball");
    if(!solrTarball.matches("^.*apache-solr-.*(tgz|tar\\.gz)$")) {
      throw new IllegalArgumentException("Must specify a Solr tarball");
    }
    // Call the installers
    addStatement(event, call(getInstallFunction(config, "java", "install_openjdk")));
    addStatement(event, call("install_tarball"));

    String installFunc = getInstallFunction(config, getRole(), "install_" + getRole());

    LOG.info("Installing Solr");

    addStatement(event, call(installFunc, solrTarball, SOLR_HOME));
  }

  @Override
  protected void beforeConfigure(ClusterActionEvent event) throws IOException {
    LOG.info("Configure Solr");

    ClusterSpec clusterSpec = event.getClusterSpec();
    Cluster cluster = event.getCluster();
    Configuration config = getConfiguration(clusterSpec, SOLR_DEFAULT_CONFIG);

    // Validate the config
    int jettyPort = config.getInt("whirr.solr.jetty.port");
    int jettyStopPort = config.getInt("whirr.solr.jetty.stop.port");
    
    if (jettyPort == 0) {
      throw new IllegalArgumentException("Must specify Jetty's port! (whirr.solr.jetty.port)");
    }

    if (jettyStopPort == 0) {
      throw new IllegalArgumentException("Must specify Jetty's stop port! (whirr.solr.jetty.stop.port)");
    }

    if (jettyPort == jettyStopPort) {
      throw new IllegalArgumentException("Jetty's port and the stop port must be different");
    }

    String solrConfigTarballUrl = config.getString("whirr.solr.config.tarball.url");
    if (StringUtils.isBlank(solrConfigTarballUrl)) {
      throw new IllegalArgumentException("Must specify Solr tarball! (whirr.solr.config.tarball.url)");
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
    Configuration config = getConfiguration(clusterSpec, SOLR_DEFAULT_CONFIG);

    String solrConfigTarballUrl = prepareRemoteFileUrl(event, config.getString("whirr.solr.config.tarball.url"));
    LOG.info("Preparing solr config tarball url {}", solrConfigTarballUrl);
    addStatement(event, call("install_tarball_no_md5", solrConfigTarballUrl, SOLR_CONF_DESTINATION));

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
    Configuration config = getConfiguration(clusterSpec, SOLR_DEFAULT_CONFIG);
    int jettyPort = config.getInt("whirr.solr.jetty.port");
    LOG.info("Completed configuration of {}", clusterSpec.getClusterName());
    LOG.info("Solr Hosts: {}", getHosts(cluster.getInstancesMatching(role(SOLR_ROLE)), jettyPort));
  }

  @Override
  protected void beforeStop(ClusterActionEvent event) throws IOException {
    ClusterSpec clusterSpec = event.getClusterSpec();
    Configuration config = getConfiguration(clusterSpec, SOLR_DEFAULT_CONFIG);
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
    return Lists.transform(Lists.newArrayList(instances), new GetPublicIpFunction(port));
  }

  static String safeSecretString(String value) throws IOException {
    return CryptoStreams.md5Hex(newInputStreamSupplier(("NaCl#" + value).getBytes()));
  }

  private static class GetPublicIpFunction implements Function<Instance, String> {
    private final int port;

    public GetPublicIpFunction(int port) {
      this.port = port;
    }

    @Override
    public String apply(Instance instance) {
      try {
        String publicIp = instance.getPublicHostName();
        return String.format("%s:%d", publicIp, port);
      } catch (IOException e) {
        throw new IllegalArgumentException(e);
      }
    }
  }
}
