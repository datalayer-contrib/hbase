/**
 *
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
package org.apache.hadoop.hbase.util;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hbase.HRegionInfo;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.MetaTableAccessor;
import org.apache.hadoop.hbase.ServerName;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.ZooKeeperConnectionException;
import org.apache.hadoop.hbase.classification.InterfaceAudience;
import org.apache.hadoop.hbase.client.Admin;
import org.apache.hadoop.hbase.client.Connection;
import org.apache.hadoop.hbase.client.ConnectionFactory;
import org.apache.hadoop.hbase.client.HConnection;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.client.Put;
import org.apache.hadoop.hbase.client.Table;
import org.apache.hadoop.hbase.master.RegionState;
import org.apache.hadoop.hbase.protobuf.ProtobufUtil;
import org.apache.hadoop.hbase.protobuf.generated.AdminProtos.AdminService;
import org.apache.hadoop.hbase.regionserver.HRegion;
import org.apache.zookeeper.KeeperException;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * This class contains helper methods that repair parts of hbase's filesystem
 * contents.
 */
@InterfaceAudience.Private
public class HBaseFsckRepair {
  public static final Log LOG = LogFactory.getLog(HBaseFsckRepair.class);

  /**
   * Fix multiple assignment by doing silent closes on each RS hosting the region
   * and then force ZK unassigned node to OFFLINE to trigger assignment by
   * master.
   *
   * @param connection HBase connection to the cluster
   * @param region Region to undeploy
   * @param servers list of Servers to undeploy from
   */
  public static void fixMultiAssignment(HConnection connection, HRegionInfo region,
      List<ServerName> servers)
  throws IOException, KeeperException, InterruptedException {
    HRegionInfo actualRegion = new HRegionInfo(region);

    // Close region on the servers silently
    for(ServerName server : servers) {
      closeRegionSilentlyAndWait(connection, server, actualRegion);
    }

    // Force ZK node to OFFLINE so master assigns
    forceOfflineInZK(connection.getAdmin(), actualRegion);
  }

  /**
   * Fix unassigned by creating/transition the unassigned ZK node for this
   * region to OFFLINE state with a special flag to tell the master that this is
   * a forced operation by HBCK.
   *
   * This assumes that info is in META.
   *
   * @param admin
   * @param region
   * @throws IOException
   * @throws KeeperException
   */
  public static void fixUnassigned(Admin admin, HRegionInfo region)
      throws IOException, KeeperException, InterruptedException {
    HRegionInfo actualRegion = new HRegionInfo(region);

    // Force ZK node to OFFLINE so master assigns
    forceOfflineInZK(admin, actualRegion);
  }

  /**
   * In 0.90, this forces an HRI offline by setting the RegionTransitionData
   * in ZK to have HBCK_CODE_NAME as the server.  This is a special case in
   * the AssignmentManager that attempts an assign call by the master.
   *
   * @see org.apache.hadoop.hbase.master.AssignementManager#handleHBCK
   *
   * This doesn't seem to work properly in the updated version of 0.92+'s hbck
   * so we use assign to force the region into transition.  This has the
   * side-effect of requiring a HRegionInfo that considers regionId (timestamp)
   * in comparators that is addressed by HBASE-5563.
   */
  private static void forceOfflineInZK(Admin admin, final HRegionInfo region)
  throws ZooKeeperConnectionException, KeeperException, IOException, InterruptedException {
    admin.assign(region.getRegionName());
  }

  /*
   * Should we check all assignments or just not in RIT?
   */
  public static void waitUntilAssigned(Admin admin,
      HRegionInfo region) throws IOException, InterruptedException {
    long timeout = admin.getConfiguration().getLong("hbase.hbck.assign.timeout", 120000);
    long expiration = timeout + System.currentTimeMillis();
    while (System.currentTimeMillis() < expiration) {
      try {
        Map<String, RegionState> rits=
            admin.getClusterStatus().getRegionsInTransition();

        if (rits.keySet() != null && !rits.keySet().contains(region.getEncodedName())) {
          // yay! no longer RIT
          return;
        }
        // still in rit
        LOG.info("Region still in transition, waiting for "
            + "it to become assigned: " + region);
      } catch (IOException e) {
        LOG.warn("Exception when waiting for region to become assigned,"
            + " retrying", e);
      }
      Thread.sleep(1000);
    }
    throw new IOException("Region " + region + " failed to move out of " +
        "transition within timeout " + timeout + "ms");
  }

  /**
   * Contacts a region server and waits up to hbase.hbck.close.timeout ms
   * (default 120s) to close the region.  This bypasses the active hmaster.
   */
  @SuppressWarnings("deprecation")
  public static void closeRegionSilentlyAndWait(HConnection connection, 
      ServerName server, HRegionInfo region) throws IOException, InterruptedException {
    AdminService.BlockingInterface rs = connection.getAdmin(server);
    try {
      ProtobufUtil.closeRegion(rs, server, region.getRegionName());
    } catch (IOException e) {
      LOG.warn("Exception when closing region: " + region.getRegionNameAsString(), e);
    }
    long timeout = connection.getConfiguration()
      .getLong("hbase.hbck.close.timeout", 120000);
    long expiration = timeout + System.currentTimeMillis();
    while (System.currentTimeMillis() < expiration) {
      try {
        HRegionInfo rsRegion =
          ProtobufUtil.getRegionInfo(rs, region.getRegionName());
        if (rsRegion == null) return;
      } catch (IOException ioe) {
        return;
      }
      Thread.sleep(1000);
    }
    throw new IOException("Region " + region + " failed to close within"
        + " timeout " + timeout);
  }

  /**
   * Puts the specified HRegionInfo into META with replica related columns
   */
  public static void fixMetaHoleOnlineAndAddReplicas(Configuration conf,
      HRegionInfo hri, Collection<ServerName> servers, int numReplicas) throws IOException {
    Connection conn = ConnectionFactory.createConnection(conf);
    Table meta = conn.getTable(TableName.META_TABLE_NAME);
    Put put = MetaTableAccessor.makePutFromRegionInfo(hri);
    if (numReplicas > 1) {
      Random r = new Random();
      ServerName[] serversArr = servers.toArray(new ServerName[servers.size()]);
      for (int i = 1; i < numReplicas; i++) {
        ServerName sn = serversArr[r.nextInt(serversArr.length)];
        // the column added here is just to make sure the master is able to
        // see the additional replicas when it is asked to assign. The
        // final value of these columns will be different and will be updated
        // by the actual regionservers that start hosting the respective replicas
        MetaTableAccessor.addLocation(put, sn, sn.getStartcode(), i);
      }
    }
    meta.put(put);
    meta.close();
    conn.close();
  }

  /**
   * Creates, flushes, and closes a new region.
   */
  public static HRegion createHDFSRegionDir(Configuration conf,
      HRegionInfo hri, HTableDescriptor htd) throws IOException {
    // Create HRegion
    Path root = FSUtils.getRootDir(conf);
    HRegion region = HRegion.createHRegion(hri, root, conf, htd, null);

    // Close the new region to flush to disk. Close log file too.
    HRegion.closeHRegion(region);
    return region;
  }
}
