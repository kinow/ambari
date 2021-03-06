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
package org.apache.ambari.server.upgrade;

import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.orm.DBAccessor.DBColumnInfo;
import org.apache.ambari.server.orm.dao.ArtifactDAO;
import org.apache.ambari.server.orm.entities.ArtifactEntity;
import org.apache.ambari.server.serveraction.kerberos.DeconstructedPrincipal;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.Config;
import org.apache.ambari.server.state.ConfigHelper;
import org.apache.ambari.server.state.PropertyInfo;
import org.apache.ambari.server.state.kerberos.AbstractKerberosDescriptorContainer;
import org.apache.ambari.server.state.kerberos.KerberosConfigurationDescriptor;
import org.apache.ambari.server.state.kerberos.KerberosDescriptor;
import org.apache.ambari.server.state.kerberos.KerberosDescriptorFactory;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.Sets;
import com.google.inject.Inject;
import com.google.inject.Injector;

/**
 * The {@link org.apache.ambari.server.upgrade.UpgradeCatalog252} upgrades Ambari from 2.5.1 to 2.5.2.
 */
public class UpgradeCatalog252 extends AbstractUpgradeCatalog {

  static final String CLUSTERCONFIG_TABLE = "clusterconfig";
  static final String SERVICE_DELETED_COLUMN = "service_deleted";

  private static final String CLUSTER_ENV = "cluster-env";

  /**
   * Logger.
   */
  private static final Logger LOG = LoggerFactory.getLogger(UpgradeCatalog252.class);

  /**
   * Constructor.
   *
   * @param injector
   */
  @Inject
  public UpgradeCatalog252(Injector injector) {
    super(injector);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getSourceVersion() {
    return "2.5.1";
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getTargetVersion() {
    return "2.5.2";
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void executeDDLUpdates() throws AmbariException, SQLException {
    addServiceDeletedColumnToClusterConfigTable();
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void executePreDMLUpdates() throws AmbariException, SQLException {
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void executeDMLUpdates() throws AmbariException, SQLException {
    addNewConfigurationsFromXml();
    resetStackToolsAndFeatures();
    updateKerberosDescriptorArtifacts();
    fixLivySuperusers();
  }

  /**
   * Adds the {@value #SERVICE_DELETED_COLUMN} column to the
   * {@value #CLUSTERCONFIG_TABLE} table.
   *
   * @throws java.sql.SQLException
   */
  private void addServiceDeletedColumnToClusterConfigTable() throws SQLException {
    dbAccessor.addColumn(CLUSTERCONFIG_TABLE,
        new DBColumnInfo(SERVICE_DELETED_COLUMN, Short.class, null, 0, false));
  }

  /**
   * Resets the following properties in {@code cluster-env} to their new
   * defaults:
   * <ul>
   * <li>stack_root
   * <li>stack_tools
   * <li>stack_features
   * <ul>
   *
   * @throws AmbariException
   */
  private void resetStackToolsAndFeatures() throws AmbariException {
    Set<String> propertiesToReset = Sets.newHashSet("stack_tools", "stack_features", "stack_root");

    Clusters clusters = injector.getInstance(Clusters.class);
    ConfigHelper configHelper = injector.getInstance(ConfigHelper.class);

    Map<String, Cluster> clusterMap = clusters.getClusters();
    for (Cluster cluster : clusterMap.values()) {
      Config clusterEnv = cluster.getDesiredConfigByType(CLUSTER_ENV);
      if (null == clusterEnv) {
        continue;
      }

      Map<String, String> newStackProperties = new HashMap<>();
      Set<PropertyInfo> stackProperties = configHelper.getStackProperties(cluster);
      if (null == stackProperties) {
        continue;
      }

      for (PropertyInfo propertyInfo : stackProperties) {
        String fileName = propertyInfo.getFilename();
        if (StringUtils.isEmpty(fileName)) {
          continue;
        }

        if (StringUtils.equals(ConfigHelper.fileNameToConfigType(fileName), CLUSTER_ENV)) {
          String stackPropertyName = propertyInfo.getName();
          if (propertiesToReset.contains(stackPropertyName)) {
            newStackProperties.put(stackPropertyName, propertyInfo.getValue());
          }
        }
      }

      updateConfigurationPropertiesForCluster(cluster, CLUSTER_ENV, newStackProperties, true, false);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  protected void updateKerberosDescriptorArtifact(ArtifactDAO artifactDAO, ArtifactEntity artifactEntity) throws AmbariException {
    if (artifactEntity != null) {
      Map<String, Object> data = artifactEntity.getArtifactData();

      if (data != null) {
        final KerberosDescriptor kerberosDescriptor = new KerberosDescriptorFactory().createInstance(data);

        if (kerberosDescriptor != null) {
          // Find and remove configuration specifications for <code>livy-conf/livy.superusers</code>
          // in SPARK since this logic has been moved to the relevant stack/service advisors
          boolean updatedSpark = removeConfigurationSpecification(kerberosDescriptor.getService("SPARK"), "livy-conf", "livy.superusers");

          // Find and remove configuration specifications for <code>livy-conf2/livy.superusers</code>
          // in SPARK2 since this logic has been moved to the relevant stack/service advisors
          boolean updatedSpark2 = removeConfigurationSpecification(kerberosDescriptor.getService("SPARK2"), "livy2-conf", "livy.superusers");

          if (updatedSpark || updatedSpark2) {
            artifactEntity.setArtifactData(kerberosDescriptor.toMap());
            artifactDAO.merge(artifactEntity);
          }
        }
      }
    }
  }

  /**
   * Fixes the <code>livy.superusers</code> value in <code>livy-conf</code> and
   * <code>livy2-conf</code>.
   * <p>
   * When Kerberos is enabled, the values of <code>livy.superusers</code> in <code>livy-conf</code>
   * and <code>livy2-conf</code> are potentially incorrect due to an issue with the Spark and Spark2
   * kerberos.json files.  In Ambari 2.5.2, the logic to set <code>livy.superusers</code> has been
   * moved to the stack advisor and removed from the kerberos.json files.  The user-supplied Kerberos
   * descriptor is fixed in {@link #updateKerberosDescriptorArtifact(ArtifactDAO, ArtifactEntity)}.
   * <p>
   * If Zeppelin is installed and Kerberos is enabled, then <code>livy.superusers</code> should be
   * updated to contain the proper value for the Zeppelin user. If the incorrect value is there and
   * in the form of <code>zeppelin-clustername</code> then it will be removed.
   */
  void fixLivySuperusers() throws AmbariException {
    Clusters clusters = injector.getInstance(Clusters.class);
    if (clusters != null) {
      Map<String, Cluster> clusterMap = clusters.getClusters();

      if (clusterMap != null && !clusterMap.isEmpty()) {
        for (final Cluster cluster : clusterMap.values()) {
          Config zeppelinEnvProperties = cluster.getDesiredConfigByType("zeppelin-env");
          if (zeppelinEnvProperties != null) {
            Map<String, String> zeppelinProperties = zeppelinEnvProperties.getProperties();
            if (zeppelinProperties != null) {
              String zeppelinPrincipal = zeppelinProperties.get("zeppelin.server.kerberos.principal");

              if (!StringUtils.isEmpty(zeppelinPrincipal)) {
                // Parse the principal name component from the full principal. The default realm of
                // EXAMPLE.COM is used because we really don't care what the realm is.
                DeconstructedPrincipal deconstructedPrincipal = DeconstructedPrincipal.valueOf(zeppelinPrincipal, "EXAMPLE.COM");
                String newZeppelinPrincipalName = deconstructedPrincipal.getPrincipalName();
                String oldZeppelinPrincipalName = "zeppelin-" + cluster.getClusterName().toLowerCase();

                // Fix livy-conf/livy.supserusers
                updateListValues(cluster, "livy-conf", "livy.superusers",
                    Collections.singleton(newZeppelinPrincipalName), Collections.singleton(oldZeppelinPrincipalName));

                // Fix livy2-conf/livy.supserusers
                updateListValues(cluster, "livy2-conf", "livy.superusers",
                    Collections.singleton(newZeppelinPrincipalName), Collections.singleton(oldZeppelinPrincipalName));
              }
            }
          }
        }
      }
    }
  }

  /**
   * Updates the contents of a configuration with comma-delimited list of values.
   * <p>
   * Items will be added and/or removed as needed. If changes are made to the value, the configuration
   * is updated in the cluster.
   *
   * @param cluster        the cluster
   * @param configType     the configuration type
   * @param propertyName   the property name
   * @param valuesToAdd    a set of values to add to the list
   * @param valuesToRemove a set of values to remove from the list
   * @throws AmbariException
   */
  private void updateListValues(Cluster cluster, String configType, String propertyName, Set<String> valuesToAdd, Set<String> valuesToRemove)
      throws AmbariException {
    Config config = cluster.getDesiredConfigByType(configType);
    if (config != null) {
      Map<String, String> properties = config.getProperties();
      if (properties != null) {
        String existingValue = properties.get(propertyName);
        String newValue = null;

        if (StringUtils.isEmpty(existingValue)) {
          if ((valuesToAdd != null) && !valuesToAdd.isEmpty()) {
            newValue = StringUtils.join(valuesToAdd, ',');
          }
        } else {
          Set<String> valueSet = new TreeSet<>(Arrays.asList(existingValue.split("\\s*,\\s*")));

          boolean removedValues = false;
          if (valuesToRemove != null) {
            removedValues = valueSet.removeAll(valuesToRemove);
          }

          boolean addedValues = false;
          if (valuesToAdd != null) {
            addedValues = valueSet.addAll(valuesToAdd);
          }

          if (removedValues || addedValues) {
            newValue = StringUtils.join(valueSet, ',');
          }
        }

        if (!StringUtils.isEmpty(newValue)) {
          updateConfigurationPropertiesForCluster(cluster, configType, Collections.singletonMap(propertyName, newValue), true, true);
        }
      }
    }
  }

  /**
   * Given an {@link AbstractKerberosDescriptorContainer}, attempts to remove the specified property
   * (<code>configType/propertyName</code> from it.
   *
   * @param kerberosDescriptorContainer the container to update
   * @param configType                  the configuration type
   * @param propertyName                the property name
   * @return true if changes where made to the container; false otherwise
   */
  private boolean removeConfigurationSpecification(AbstractKerberosDescriptorContainer kerberosDescriptorContainer, String configType, String propertyName) {
    boolean updated = false;
    if (kerberosDescriptorContainer != null) {
      KerberosConfigurationDescriptor configurationDescriptor = kerberosDescriptorContainer.getConfiguration(configType);
      if (configurationDescriptor != null) {
        Map<String, String> properties = configurationDescriptor.getProperties();
        if ((properties != null) && properties.containsKey(propertyName)) {
          properties.remove(propertyName);
          LOG.info("Removed {}/{} from the descriptor named {}", configType, propertyName, kerberosDescriptorContainer.getName());
          updated = true;
        }
      }
    }

    return updated;
  }
}
