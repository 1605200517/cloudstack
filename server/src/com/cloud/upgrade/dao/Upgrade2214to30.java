// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.upgrade.dao;

import java.io.File;
import java.io.UnsupportedEncodingException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.log4j.Logger;

import com.cloud.offering.NetworkOffering;
import com.cloud.utils.crypt.DBEncryptionUtil;
import com.cloud.utils.crypt.EncryptionSecretKeyChecker;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;

public class Upgrade2214to30 implements DbUpgrade {
    final static Logger s_logger = Logger.getLogger(Upgrade2214to30.class);

    @Override
    public String[] getUpgradableVersionRange() {
        return new String[] { "2.2.14", "3.0.0" };
    }

    @Override
    public String getUpgradedVersion() {
        return "3.0.0";
    }

    @Override
    public boolean supportsRollingUpgrade() {
        return true;
    }

    @Override
    public File[] getPrepareScripts() {
        String script = Script.findScript("", "db/schema-2214to30.sql");
        if (script == null) {
            throw new CloudRuntimeException("Unable to find db/schema-2214to30.sql");
        }

        return new File[] { new File(script) };
    }

    @Override
    public void performDataMigration(Connection conn) {
    	// Fail upgrade if encryption is not enabled
    	if(!EncryptionSecretKeyChecker.useEncryption()){
    		throw new CloudRuntimeException("Encryption is not enabled. Please Run cloud-setup-encryption to enable encryption");
    	}
    	
    	// physical network setup
        setupPhysicalNetworks(conn);
        // encrypt data
        encryptData(conn);
        // drop keys
        dropKeysIfExist(conn);
        //update templete ID for system Vms
        updateSystemVms(conn);
        // update domain network ref
        updateDomainNetworkRef(conn);
        // update networks that use redundant routers to the new network offering
        updateReduntantRouters(conn);
        // update networks that have to switch from Shared to Isolated network offerings
        switchAccountSpecificNetworksToIsolated(conn);
        // update networks to external network offerings if needed
        String externalOfferingName = fixNetworksWithExternalDevices(conn);
        // create service/provider map for network offerings
        createNetworkOfferingServices(conn, externalOfferingName);
        // create service/provider map for networks
        createNetworkServices(conn);
        //migrate user concentrated deployment planner choice to new global setting
        migrateUserConcentratedPlannerChoice(conn);
        // update domain router table for element it;
        updateRouters(conn);
        //update host capacities
        updateHostCapacity(conn);
    }

    @Override
    public File[] getCleanupScripts() {
        String script = Script.findScript("", "db/schema-2214to30-cleanup.sql");
        if (script == null) {
            throw new CloudRuntimeException("Unable to find db/schema-2214to30-cleanup.sql");
        }

        return new File[] { new File(script) };
    }
    
    private long addPhysicalNetworkToZone(Connection conn, long zoneId, String zoneName, String networkType, String vnet, Long domainId){

        String getNextNetworkSequenceSql = "SELECT value from `cloud`.`sequence` where name='physical_networks_seq'";
        String advanceNetworkSequenceSql = "UPDATE `cloud`.`sequence` set value=value+1 where name='physical_networks_seq'";
        PreparedStatement pstmtUpdate = null, pstmt2 = null;
        // add p.network
        try{
            pstmt2 = conn.prepareStatement(getNextNetworkSequenceSql);
        
            ResultSet rsSeq = pstmt2.executeQuery();
            rsSeq.next();
    
            long physicalNetworkId = rsSeq.getLong(1);
            rsSeq.close();
            pstmt2.close();
            pstmt2 = conn.prepareStatement(advanceNetworkSequenceSql);
            pstmt2.executeUpdate();
            pstmt2.close();
    
            String uuid = UUID.randomUUID().toString();
            String broadcastDomainRange = "POD";
            if ("Advanced".equals(networkType)) {
                broadcastDomainRange = "ZONE";
            }
    
            s_logger.debug("Adding PhysicalNetwork " + physicalNetworkId + " for Zone id " + zoneId);
            String sql = "INSERT INTO `cloud`.`physical_network` (id, uuid, data_center_id, vnet, broadcast_domain_range, state, name) VALUES (?,?,?,?,?,?,?)";
            
            pstmtUpdate = conn.prepareStatement(sql);
            pstmtUpdate.setLong(1, physicalNetworkId);
            pstmtUpdate.setString(2, uuid);
            pstmtUpdate.setLong(3, zoneId);
            pstmtUpdate.setString(4, vnet);
            pstmtUpdate.setString(5, broadcastDomainRange);
            pstmtUpdate.setString(6, "Enabled");
            zoneName = zoneName + "-pNtwk";
            pstmtUpdate.setString(7, zoneName);
            s_logger.warn("Statement is " + pstmtUpdate.toString());
            pstmtUpdate.executeUpdate();
            pstmtUpdate.close();
            
            if (domainId != null && domainId.longValue() != 0) {
                s_logger.debug("Updating domain_id for physical network id=" + physicalNetworkId);
                sql = "UPDATE `cloud`.`physical_network` set domain_id=? where id=?";
                pstmtUpdate = conn.prepareStatement(sql);
                pstmtUpdate.setLong(1, domainId);
                pstmtUpdate.setLong(2, physicalNetworkId);
                pstmtUpdate.executeUpdate();
                pstmtUpdate.close();
            }
    
            return physicalNetworkId;
        } catch (SQLException e) {
            throw new CloudRuntimeException("Exception while adding PhysicalNetworks", e);
        } finally {
            if (pstmtUpdate != null) {
                try {
                    pstmtUpdate.close();
                } catch (SQLException e) {
                }
            }
            if (pstmt2 != null) {
                try {
                    pstmt2.close();
                } catch (SQLException e) {
                }
            }

        }
    }
    
    private void addTrafficType(Connection conn, long physicalNetworkId, String trafficType, String xenPublicLabel, String kvmPublicLabel, String vmwarePublicLabel){
        // add traffic types
        PreparedStatement pstmtUpdate = null;
        try{
            s_logger.debug("Adding PhysicalNetwork traffic types");
            String insertTraficType = "INSERT INTO `cloud`.`physical_network_traffic_types` (physical_network_id, traffic_type, xen_network_label, kvm_network_label, vmware_network_label, uuid) VALUES ( ?, ?, ?, ?, ?, ?)";
            pstmtUpdate = conn.prepareStatement(insertTraficType);
            pstmtUpdate.setLong(1, physicalNetworkId);
            pstmtUpdate.setString(2, trafficType);
            pstmtUpdate.setString(3, xenPublicLabel);
            pstmtUpdate.setString(4, kvmPublicLabel);
            pstmtUpdate.setString(5, vmwarePublicLabel);
            pstmtUpdate.setString(6, UUID.randomUUID().toString());
            pstmtUpdate.executeUpdate();
            pstmtUpdate.close();
        }catch (SQLException e) {
            throw new CloudRuntimeException("Exception while adding PhysicalNetworks", e);
        } finally {
            if (pstmtUpdate != null) {
                try {
                    pstmtUpdate.close();
                } catch (SQLException e) {
                }
            }
        }
    }
    
    private void addDefaultServiceProviders(Connection conn, long physicalNetworkId, long zoneId){
        PreparedStatement pstmtUpdate = null, pstmt2 = null;
        try{
            // add physical network service provider - VirtualRouter
            s_logger.debug("Adding PhysicalNetworkServiceProvider VirtualRouter");
            String insertPNSP = "INSERT INTO `cloud`.`physical_network_service_providers` (`uuid`, `physical_network_id` , `provider_name`, `state` ," +
                    "`destination_physical_network_id`, `vpn_service_provided`, `dhcp_service_provided`, `dns_service_provided`, `gateway_service_provided`," +
                    "`firewall_service_provided`, `source_nat_service_provided`, `load_balance_service_provided`, `static_nat_service_provided`," +
                    "`port_forwarding_service_provided`, `user_data_service_provided`, `security_group_service_provided`) VALUES (?,?,?,?,0,1,1,1,1,1,1,1,1,1,1,0)";

            pstmtUpdate = conn.prepareStatement(insertPNSP);
            pstmtUpdate.setString(1, UUID.randomUUID().toString());
            pstmtUpdate.setLong(2, physicalNetworkId);
            pstmtUpdate.setString(3, "VirtualRouter");
            pstmtUpdate.setString(4, "Enabled");
            pstmtUpdate.executeUpdate();
            pstmtUpdate.close();
            
            //add security group service provider (if security group service is enabled for at least one guest network)
            String selectSG = "SELECT * from `cloud`.`networks` where is_security_group_enabled=1 and data_center_id=?";
            pstmt2 = conn.prepareStatement(selectSG);
            pstmt2.setLong(1, zoneId);
            ResultSet sgDcSet = pstmt2.executeQuery();
            while (sgDcSet.next()) {
                s_logger.debug("Adding PhysicalNetworkServiceProvider SecurityGroupProvider to the physical network id=" + physicalNetworkId);
                insertPNSP = "INSERT INTO `cloud`.`physical_network_service_providers` (`uuid`, `physical_network_id` , `provider_name`, `state` ," +
                        "`destination_physical_network_id`, `vpn_service_provided`, `dhcp_service_provided`, `dns_service_provided`, `gateway_service_provided`," +
                        "`firewall_service_provided`, `source_nat_service_provided`, `load_balance_service_provided`, `static_nat_service_provided`," +
                        "`port_forwarding_service_provided`, `user_data_service_provided`, `security_group_service_provided`) VALUES (?,?,?,?,0,0,0,0,0,0,0,0,0,0,0,1)";
                pstmtUpdate = conn.prepareStatement(insertPNSP);
                pstmtUpdate.setString(1, UUID.randomUUID().toString());
                pstmtUpdate.setLong(2, physicalNetworkId);
                pstmtUpdate.setString(3, "SecurityGroupProvider");
                pstmtUpdate.setString(4, "Enabled");
                pstmtUpdate.executeUpdate();
                pstmtUpdate.close();
            }
            pstmt2.close();

            // add virtual_router_element
            String fetchNSPid = "SELECT id from `cloud`.`physical_network_service_providers` where physical_network_id=" + physicalNetworkId;
            pstmt2 = conn.prepareStatement(fetchNSPid);
            ResultSet rsNSPid = pstmt2.executeQuery();
            rsNSPid.next();
            long nspId = rsNSPid.getLong(1);
            pstmt2.close();

            String insertRouter = "INSERT INTO `cloud`.`virtual_router_providers` (`nsp_id`, `uuid` , `type` , `enabled`) " +
                    "VALUES (?,?,?,?)";
            pstmtUpdate = conn.prepareStatement(insertRouter);
            pstmtUpdate.setLong(1, nspId);
            pstmtUpdate.setString(2, UUID.randomUUID().toString());
            pstmtUpdate.setString(3, "VirtualRouter");
            pstmtUpdate.setInt(4, 1);
            pstmtUpdate.executeUpdate();
            pstmtUpdate.close();
        }catch (SQLException e) {
            throw new CloudRuntimeException("Exception while adding PhysicalNetworks", e);
        } finally {
            if (pstmtUpdate != null) {
                try {
                    pstmtUpdate.close();
                } catch (SQLException e) {
                }
            }
            if (pstmt2 != null) {
                try {
                    pstmt2.close();
                } catch (SQLException e) {
                }
            }
        }
    }
    
    private void addPhysicalNtwk_To_Ntwk_IP_Vlan(Connection conn, long physicalNetworkId, long networkId){
        PreparedStatement pstmtUpdate = null; 
        try{
            // add physicalNetworkId to vlan for this zone
            String updateVLAN = "UPDATE `cloud`.`vlan` SET physical_network_id = " + physicalNetworkId + " WHERE network_id = " + networkId;
            pstmtUpdate = conn.prepareStatement(updateVLAN);
            pstmtUpdate.executeUpdate();
            pstmtUpdate.close();

            // add physicalNetworkId to user_ip_address for this zone
            String updateUsrIp = "UPDATE `cloud`.`user_ip_address` SET physical_network_id = " + physicalNetworkId + " WHERE source_network_id = " + networkId;
            pstmtUpdate = conn.prepareStatement(updateUsrIp);
            pstmtUpdate.executeUpdate();
            pstmtUpdate.close();

            // add physicalNetworkId to guest networks for this zone
            String updateNet = "UPDATE `cloud`.`networks` SET physical_network_id = " + physicalNetworkId + " WHERE id = " + networkId + " AND traffic_type = 'Guest'";
            pstmtUpdate = conn.prepareStatement(updateNet);
            pstmtUpdate.executeUpdate();
            pstmtUpdate.close();
        }catch (SQLException e) {
            throw new CloudRuntimeException("Exception while adding PhysicalNetworks", e);
        } finally {
            if (pstmtUpdate != null) {
                try {
                    pstmtUpdate.close();
                } catch (SQLException e) {
                }
            }
        }
            
    }

    private void setupPhysicalNetworks(Connection conn) {
        /**
         * for each zone:
         * add a p.network, use zone.vnet and zone.type
         * add default traffic types, pnsp and virtual router element in enabled state
         * set p.network.id in op_dc_vnet and vlan and user_ip_address
         * list guest networks for the zone, set p.network.id
         * 
         * for cases where network_tags are used for multiple guest networks:
         * - figure out distinct tags
         * - create physical network per tag
         * - create traffic types and set the tag to xen_network_label
         * - add physical network id  to networks, vlan, user_ip_address for networks belonging to this tag 
         */
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        PreparedStatement pstmtUpdate = null;
        try {
            // Load all DataCenters

            String xenPublicLabel = getNetworkLabelFromConfig(conn, "xen.public.network.device");
            String xenPrivateLabel = getNetworkLabelFromConfig(conn, "xen.private.network.device");
            String xenStorageLabel = getNetworkLabelFromConfig(conn, "xen.storage.network.device1");
            String xenGuestLabel = getNetworkLabelFromConfig(conn, "xen.guest.network.device");

            String kvmPublicLabel = getNetworkLabelFromConfig(conn, "kvm.public.network.device");
            String kvmPrivateLabel = getNetworkLabelFromConfig(conn, "kvm.private.network.device");
            String kvmGuestLabel = getNetworkLabelFromConfig(conn, "kvm.guest.network.device");

            String vmwarePublicLabel = getNetworkLabelFromConfig(conn, "vmware.public.vswitch");
            String vmwarePrivateLabel = getNetworkLabelFromConfig(conn, "vmware.private.vswitch");
            String vmwareGuestLabel = getNetworkLabelFromConfig(conn, "vmware.guest.vswitch");

            pstmt = conn.prepareStatement("SELECT id, domain_id, networktype, vnet, name FROM `cloud`.`data_center`");
            rs = pstmt.executeQuery();
            while (rs.next()) {
                long zoneId = rs.getLong(1);
                Long domainId = rs.getLong(2);
                String networkType = rs.getString(3);
                String vnet = rs.getString(4);
                String zoneName = rs.getString(5);

                //check if there are multiple guest networks configured using network_tags
                
                PreparedStatement pstmt2 = conn.prepareStatement("SELECT distinct tag FROM `cloud`.`network_tags` t JOIN `cloud`.`networks` n where t.network_id = n.id and n.data_center_id = "+zoneId);
                ResultSet rsTags = pstmt2.executeQuery();
                if(rsTags.next()){
                    boolean isFirstPhysicalNtwk = true;
                    do{
                        s_logger.debug("Network tags are not empty, might have to create more than one physical network...");
                        //create one physical network per tag
                        String guestNetworkTag = rsTags.getString(1);
                        long physicalNetworkId = addPhysicalNetworkToZone(conn, zoneId, zoneName, networkType, (isFirstPhysicalNtwk) ? vnet : null, domainId);
                        //add Traffic types
                        if(isFirstPhysicalNtwk){
                            addTrafficType(conn, physicalNetworkId, "Public", xenPublicLabel, kvmPublicLabel, vmwarePublicLabel);
                            addTrafficType(conn, physicalNetworkId, "Management", xenPrivateLabel, kvmPrivateLabel, vmwarePrivateLabel);
                            addTrafficType(conn, physicalNetworkId, "Storage", xenStorageLabel, null, null);
                        }
                        addTrafficType(conn, physicalNetworkId, "Guest", guestNetworkTag, kvmGuestLabel, vmwareGuestLabel);
                        addDefaultServiceProviders(conn, physicalNetworkId, zoneId);
                        //for all networks with this tag, add physical_network_id
                        
                        PreparedStatement pstmt3 = conn.prepareStatement("SELECT network_id FROM `cloud`.`network_tags` where tag = '" + guestNetworkTag + "'");
                        ResultSet rsNet = pstmt3.executeQuery();
                        s_logger.debug("Adding PhysicalNetwork to VLAN");
                        s_logger.debug("Adding PhysicalNetwork to user_ip_address");
                        s_logger.debug("Adding PhysicalNetwork to networks");
                        while(rsNet.next()){
                            Long networkId = rsNet.getLong(1);
                            addPhysicalNtwk_To_Ntwk_IP_Vlan(conn, physicalNetworkId,networkId);
                        }
                        pstmt3.close();
                        // add first physicalNetworkId to op_dc_vnet_alloc for this zone - just a placeholder since direct networking dont need this
                        if(isFirstPhysicalNtwk){
                            s_logger.debug("Adding PhysicalNetwork to op_dc_vnet_alloc");
                            String updateVnet = "UPDATE `cloud`.`op_dc_vnet_alloc` SET physical_network_id = " + physicalNetworkId + " WHERE data_center_id = " + zoneId;
                            pstmtUpdate = conn.prepareStatement(updateVnet);
                            pstmtUpdate.executeUpdate();
                            pstmtUpdate.close();
                        }
                        
                        isFirstPhysicalNtwk = false;
                    }while(rsTags.next());
                    pstmt2.close();
                }else{
                    //default to one physical network
                    long physicalNetworkId = addPhysicalNetworkToZone(conn, zoneId, zoneName, networkType, vnet, domainId);
                    // add traffic types
                    addTrafficType(conn, physicalNetworkId, "Public", xenPublicLabel, kvmPublicLabel, vmwarePublicLabel);
                    addTrafficType(conn, physicalNetworkId, "Management", xenPrivateLabel, kvmPrivateLabel, vmwarePrivateLabel);
                    addTrafficType(conn, physicalNetworkId, "Storage", xenStorageLabel, null, null);
                    addTrafficType(conn, physicalNetworkId, "Guest", xenGuestLabel, kvmGuestLabel, vmwareGuestLabel);
                    addDefaultServiceProviders(conn, physicalNetworkId, zoneId);
                    
                    // add physicalNetworkId to op_dc_vnet_alloc for this zone
                    s_logger.debug("Adding PhysicalNetwork to op_dc_vnet_alloc");
                    String updateVnet = "UPDATE `cloud`.`op_dc_vnet_alloc` SET physical_network_id = " + physicalNetworkId + " WHERE data_center_id = " + zoneId;
                    pstmtUpdate = conn.prepareStatement(updateVnet);
                    pstmtUpdate.executeUpdate();
                    pstmtUpdate.close();

                    // add physicalNetworkId to vlan for this zone
                    s_logger.debug("Adding PhysicalNetwork to VLAN");
                    String updateVLAN = "UPDATE `cloud`.`vlan` SET physical_network_id = " + physicalNetworkId + " WHERE data_center_id = " + zoneId;
                    pstmtUpdate = conn.prepareStatement(updateVLAN);
                    pstmtUpdate.executeUpdate();
                    pstmtUpdate.close();

                    // add physicalNetworkId to user_ip_address for this zone
                    s_logger.debug("Adding PhysicalNetwork to user_ip_address");
                    String updateUsrIp = "UPDATE `cloud`.`user_ip_address` SET physical_network_id = " + physicalNetworkId + " WHERE data_center_id = " + zoneId;
                    pstmtUpdate = conn.prepareStatement(updateUsrIp);
                    pstmtUpdate.executeUpdate();
                    pstmtUpdate.close();

                    // add physicalNetworkId to guest networks for this zone
                    s_logger.debug("Adding PhysicalNetwork to networks");
                    String updateNet = "UPDATE `cloud`.`networks` SET physical_network_id = " + physicalNetworkId + " WHERE data_center_id = " + zoneId + " AND traffic_type = 'Guest'";
                    pstmtUpdate = conn.prepareStatement(updateNet);
                    pstmtUpdate.executeUpdate();
                    pstmtUpdate.close();
                }

            }
        } catch (SQLException e) {
            throw new CloudRuntimeException("Exception while adding PhysicalNetworks", e);
        } finally {
            if (pstmtUpdate != null) {
                try {
                    pstmtUpdate.close();
                } catch (SQLException e) {
                }
            }
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException e) {
                }
            }
            if (pstmt != null) {
                try {
                    pstmt.close();
                } catch (SQLException e) {
                }
            }

        }

    }
    
    private String getNetworkLabelFromConfig(Connection conn, String name){
        String sql = "SELECT value FROM `cloud`.`configuration` where name = '"+name+"'";
        String networkLabel = null;
        PreparedStatement pstmt = null; 
        ResultSet rs = null;
        try{
            pstmt = conn.prepareStatement(sql);
            rs = pstmt.executeQuery();
            if (rs.next()) {
                networkLabel = rs.getString(1);
            }
        }catch (SQLException e) {
            throw new CloudRuntimeException("Unable to fetch network label from configuration", e);
        }finally{
            if (rs != null) {
                try {
                    rs.close();
                } catch (SQLException e) {
                }
            }
            if (pstmt != null) {
                try {
                    pstmt.close();
                } catch (SQLException e) {
                }
            }
        }
        return networkLabel;
    }

    private void encryptData(Connection conn) {
        s_logger.debug("Encrypting the data...");
        encryptConfigValues(conn);
        encryptHostDetails(conn);
        encryptVNCPassword(conn);
        encryptUserCredentials(conn);
        encryptVPNPassword(conn);
        s_logger.debug("Done encrypting the data");
    }

    private void encryptConfigValues(Connection conn) {
        s_logger.debug("Encrypting Config values");
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pstmt = conn.prepareStatement("select name, value from `cloud`.`configuration` where category in ('Hidden', 'Secure')");
            rs = pstmt.executeQuery();
            while (rs.next()) {
                String name = rs.getString(1);
                String value = rs.getString(2);
                if (value == null) {
                    continue;
                }
                String encryptedValue = DBEncryptionUtil.encrypt(value);
                pstmt = conn.prepareStatement("update `cloud`.`configuration` set value=? where name=?");
                pstmt.setBytes(1, encryptedValue.getBytes("UTF-8"));
                pstmt.setString(2, name);
                pstmt.executeUpdate();
            }
        } catch (SQLException e) {
            throw new CloudRuntimeException("Unable encrypt configuration values ", e);
        } catch (UnsupportedEncodingException e) {
            throw new CloudRuntimeException("Unable encrypt configuration values ", e);
        } finally {
            try {
                if (rs != null) {
                    rs.close();
                }

                if (pstmt != null) {
                    pstmt.close();
                }
            } catch (SQLException e) {
            }
        }
        s_logger.debug("Done encrypting Config values");
    }

    private void encryptHostDetails(Connection conn) {
    	s_logger.debug("Encrypting host details");
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pstmt = conn.prepareStatement("select id, value from `cloud`.`host_details` where name = 'password'");
            rs = pstmt.executeQuery();
            while (rs.next()) {
                long id = rs.getLong(1);
                String value = rs.getString(2);
                if (value == null) {
                    continue;
                }
                String encryptedValue = DBEncryptionUtil.encrypt(value);
                pstmt = conn.prepareStatement("update `cloud`.`host_details` set value=? where id=?");
                pstmt.setBytes(1, encryptedValue.getBytes("UTF-8"));
                pstmt.setLong(2, id);
                pstmt.executeUpdate();
            }
        } catch (SQLException e) {
            throw new CloudRuntimeException("Unable encrypt host_details values ", e);
        } catch (UnsupportedEncodingException e) {
            throw new CloudRuntimeException("Unable encrypt host_details values ", e);
        } finally {
            try {
                if (rs != null) {
                    rs.close();
                }

                if (pstmt != null) {
                    pstmt.close();
                }
            } catch (SQLException e) {
            }
        }
        s_logger.debug("Done encrypting host details");
    }

    private void encryptVNCPassword(Connection conn) {
    	s_logger.debug("Encrypting vm_instance vnc_password");
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
        	int numRows = 0;
        	pstmt = conn.prepareStatement("select count(id) from `cloud`.`vm_instance` where removed is null");
            rs = pstmt.executeQuery();
            if(rs.next()){
            	numRows = rs.getInt(1);
            }
            rs.close();
            pstmt.close();
            int offset = 0;
            while(offset < numRows){
            	pstmt = conn.prepareStatement("select id, vnc_password from `cloud`.`vm_instance` where removed is null limit "+offset+", 500");
            	rs = pstmt.executeQuery();
            	while (rs.next()) {
            		long id = rs.getLong(1);
            		String value = rs.getString(2);
            		if (value == null) {
            			continue;
            		}
            		String encryptedValue = DBEncryptionUtil.encrypt(value);
            		pstmt = conn.prepareStatement("update `cloud`.`vm_instance` set vnc_password=? where id=?");
            		pstmt.setBytes(1, encryptedValue.getBytes("UTF-8"));
            		pstmt.setLong(2, id);
            		pstmt.executeUpdate();
            		pstmt.close();
            	}
            	rs.close();
            	offset+=500;
            }
        } catch (SQLException e) {
            throw new CloudRuntimeException("Unable encrypt vm_instance vnc_password ", e);
        } catch (UnsupportedEncodingException e) {
            throw new CloudRuntimeException("Unable encrypt vm_instance vnc_password ", e);
        } finally {
            try {
                if (rs != null) {
                    rs.close();
                }

                if (pstmt != null) {
                    pstmt.close();
                }
            } catch (SQLException e) {
            }
        }
        s_logger.debug("Done encrypting vm_instance vnc_password");
    }

    private void encryptUserCredentials(Connection conn) {
    	s_logger.debug("Encrypting user keys");
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pstmt = conn.prepareStatement("select id, secret_key from `cloud`.`user`");
            rs = pstmt.executeQuery();
            while (rs.next()) {
                long id = rs.getLong(1);
                String secretKey = rs.getString(2);
                String encryptedSecretKey = DBEncryptionUtil.encrypt(secretKey);
                pstmt = conn.prepareStatement("update `cloud`.`user` set secret_key=? where id=?");
                if (encryptedSecretKey == null) {
                    pstmt.setNull(1, Types.VARCHAR);
                } else {
                    pstmt.setBytes(1, encryptedSecretKey.getBytes("UTF-8"));
                }
                pstmt.setLong(2, id);
                pstmt.executeUpdate();
            }
        } catch (SQLException e) {
            throw new CloudRuntimeException("Unable encrypt user secret key ", e);
        } catch (UnsupportedEncodingException e) {
            throw new CloudRuntimeException("Unable encrypt user secret key ", e);
        } finally {
            try {
                if (rs != null) {
                    rs.close();
                }

                if (pstmt != null) {
                    pstmt.close();
                }
            } catch (SQLException e) {
            }
        }
        s_logger.debug("Done encrypting user keys");
    }

    private void encryptVPNPassword(Connection conn) {
    	s_logger.debug("Encrypting vpn_users password");
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pstmt = conn.prepareStatement("select id, password from `cloud`.`vpn_users`");
            rs = pstmt.executeQuery();
            while (rs.next()) {
                long id = rs.getLong(1);
                String password = rs.getString(2);
                String encryptedpassword = DBEncryptionUtil.encrypt(password);
                pstmt = conn.prepareStatement("update `cloud`.`vpn_users` set password=? where id=?");
                if (encryptedpassword == null) {
                    pstmt.setNull(1, Types.VARCHAR);
                } else {
                    pstmt.setBytes(1, encryptedpassword.getBytes("UTF-8"));
                }
                pstmt.setLong(2, id);
                pstmt.executeUpdate();
            }
        } catch (SQLException e) {
            throw new CloudRuntimeException("Unable encrypt vpn_users password ", e);
        } catch (UnsupportedEncodingException e) {
            throw new CloudRuntimeException("Unable encrypt vpn_users password ", e);
        } finally {
            try {
                if (rs != null) {
                    rs.close();
                }

                if (pstmt != null) {
                    pstmt.close();
                }
            } catch (SQLException e) {
            }
        }
        s_logger.debug("Done encrypting vpn_users password");
    }

    
    private void dropKeysIfExist(Connection conn) {
        HashMap<String, List<String>> uniqueKeys = new HashMap<String, List<String>>();
        List<String> keys = new ArrayList<String>();
        keys.add("public_ip_address");
        uniqueKeys.put("console_proxy", keys);
        uniqueKeys.put("secondary_storage_vm", keys);

        // drop keys
        s_logger.debug("Dropping public_ip_address keys from `cloud`.`secondary_storage_vm` and console_proxy tables...");
        for (String tableName : uniqueKeys.keySet()) {
            DbUpgradeUtils.dropKeysIfExist(conn, tableName, uniqueKeys.get(tableName), false);
        }
    }

    private void updateSystemVms(Connection conn){
    	PreparedStatement pstmt = null;
    	ResultSet rs = null;
    	boolean xenserver = false;
    	boolean kvm = false;
    	boolean VMware = false;
    	s_logger.debug("Updating System Vm template IDs");
    	try{
    		//Get all hypervisors in use
    		try {
    			pstmt = conn.prepareStatement("select distinct(hypervisor_type) from `cloud`.`cluster` where removed is null");
    			rs = pstmt.executeQuery();
    			while(rs.next()){
    				if("XenServer".equals(rs.getString(1))){
    					xenserver = true;
    				} else if("KVM".equals(rs.getString(1))){
    					kvm = true;
    				} else if("VMware".equals(rs.getString(1))){
    					VMware = true;
    				}  
    			}
    		} catch (SQLException e) {
    			throw new CloudRuntimeException("Error while listing hypervisors in use", e);
    		}

    		s_logger.debug("Updating XenSever System Vms");    		
    		//XenServer
    		try {
    			//Get 3.0.0 xenserer system Vm template Id
    			pstmt = conn.prepareStatement("select id from `cloud`.`vm_template` where name = 'systemvm-xenserver-3.0.0' and removed is null");
    			rs = pstmt.executeQuery();
    			if(rs.next()){
    				long templateId = rs.getLong(1);
    				rs.close();
    				pstmt.close();
    				// change template type to SYSTEM
    				pstmt = conn.prepareStatement("update `cloud`.`vm_template` set type='SYSTEM' where id = ?");
    				pstmt.setLong(1, templateId);
    				pstmt.executeUpdate();
    				pstmt.close();
    				// update templete ID of system Vms
    				pstmt = conn.prepareStatement("update `cloud`.`vm_instance` set vm_template_id = ? where type <> 'User' and hypervisor_type = 'XenServer'");
    				pstmt.setLong(1, templateId);
    				pstmt.executeUpdate();
    				pstmt.close();
    			} else {
    				if (xenserver){
    					throw new CloudRuntimeException("3.0.0 XenServer SystemVm template not found. Cannot upgrade system Vms");
    				} else {
    					s_logger.warn("3.0.0 XenServer SystemVm template not found. XenServer hypervisor is not used, so not failing upgrade");
    				}
    			}
    		} catch (SQLException e) {
    			throw new CloudRuntimeException("Error while updating XenServer systemVm template", e);
    		}

    		//KVM
    		s_logger.debug("Updating KVM System Vms");
    		try {
    			//Get 3.0.0 KVM system Vm template Id
    			pstmt = conn.prepareStatement("select id from `cloud`.`vm_template` where name = 'systemvm-kvm-3.0.0' and removed is null");
    			rs = pstmt.executeQuery();
    			if(rs.next()){
    				long templateId = rs.getLong(1);
    				rs.close();
    				pstmt.close();
    				// change template type to SYSTEM
    				pstmt = conn.prepareStatement("update `cloud`.`vm_template` set type='SYSTEM' where id = ?");
    				pstmt.setLong(1, templateId);
    				pstmt.executeUpdate();
    				pstmt.close();
    				// update templete ID of system Vms
    				pstmt = conn.prepareStatement("update `cloud`.`vm_instance` set vm_template_id = ? where type <> 'User' and hypervisor_type = 'KVM'");
    				pstmt.setLong(1, templateId);
    				pstmt.executeUpdate();
    				pstmt.close();
    			} else {
    				if (kvm){
    					throw new CloudRuntimeException("3.0.0 KVM SystemVm template not found. Cannot upgrade system Vms");
    				} else {
    					s_logger.warn("3.0.0 KVM SystemVm template not found. KVM hypervisor is not used, so not failing upgrade");
    				}
    			}
    		} catch (SQLException e) {
    			throw new CloudRuntimeException("Error while updating KVM systemVm template", e);
    		}

    		//VMware
    		s_logger.debug("Updating VMware System Vms");
    		try {
    			//Get 3.0.0 VMware system Vm template Id
    			pstmt = conn.prepareStatement("select id from `cloud`.`vm_template` where name = 'systemvm-vmware-3.0.0' and removed is null");
    			rs = pstmt.executeQuery();
    			if(rs.next()){
    				long templateId = rs.getLong(1);
    				rs.close();
    				pstmt.close();
    				// change template type to SYSTEM
    				pstmt = conn.prepareStatement("update `cloud`.`vm_template` set type='SYSTEM' where id = ?");
    				pstmt.setLong(1, templateId);
    				pstmt.executeUpdate();
    				pstmt.close();
    				// update templete ID of system Vms
    				pstmt = conn.prepareStatement("update `cloud`.`vm_instance` set vm_template_id = ? where type <> 'User' and hypervisor_type = 'VMware'");
    				pstmt.setLong(1, templateId);
    				pstmt.executeUpdate();
    				pstmt.close();
    			} else {
    				if (VMware){
    					throw new CloudRuntimeException("3.0.0 VMware SystemVm template not found. Cannot upgrade system Vms");
    				} else {
    					s_logger.warn("3.0.0 VMware SystemVm template not found. VMware hypervisor is not used, so not failing upgrade");
    				}
    			}
    		} catch (SQLException e) {
    			throw new CloudRuntimeException("Error while updating VMware systemVm template", e);
    		}
    		s_logger.debug("Updating System Vm Template IDs Complete");
    	}
    	finally {
    		try {
    			if (rs != null) {
    				rs.close();
    			}

    			if (pstmt != null) {
    				pstmt.close();
    			}
    		} catch (SQLException e) {
    		}
    	}
    }
    
    private void createNetworkOfferingServices(Connection conn, String externalOfferingName) {
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pstmt = conn
                    .prepareStatement("select id, dns_service, gateway_service, firewall_service, lb_service, userdata_service," +
                    		" vpn_service, dhcp_service, unique_name from `cloud`.`network_offerings` where traffic_type='Guest'");
            rs = pstmt.executeQuery();
            while (rs.next()) {
                boolean sharedSourceNat = false;
                boolean dedicatedLb = true;
                long id = rs.getLong(1);
                String uniqueName = rs.getString(9);

                Map<String, String> services = new HashMap<String, String>();
                if (rs.getLong(2) != 0) {
                    services.put("Dns", "VirtualRouter");
                }

                if (rs.getLong(3) != 0) {
                    if (externalOfferingName != null && uniqueName.equalsIgnoreCase(externalOfferingName)) {
                        services.put("Gateway", "JuniperSRX");
                    } else {
                        services.put("Gateway", "VirtualRouter");
                    }
                }

                if (rs.getLong(4) != 0) {
                    if (externalOfferingName != null && uniqueName.equalsIgnoreCase(externalOfferingName)) {
                        services.put("Firewall", "JuniperSRX");
                    } else {
                        services.put("Firewall", "VirtualRouter");
                    }
                }

                if (rs.getLong(5) != 0) {
                    if (externalOfferingName != null && uniqueName.equalsIgnoreCase(externalOfferingName)) {
                        services.put("Lb", "F5BigIp");
                        dedicatedLb = false;
                    } else {
                        services.put("Lb", "VirtualRouter");
                    }
                }

                if (rs.getLong(6) != 0) {
                    services.put("UserData", "VirtualRouter");
                }

                if (rs.getLong(7) != 0) {
                    if (externalOfferingName == null || !uniqueName.equalsIgnoreCase(externalOfferingName)) {
                        services.put("Vpn", "VirtualRouter");
                    } 
                }

                if (rs.getLong(8) != 0) {
                    services.put("Dhcp", "VirtualRouter");
                }

                if (uniqueName.equalsIgnoreCase(NetworkOffering.DefaultSharedNetworkOfferingWithSGService.toString())) {
                    services.put("SecurityGroup", "SecurityGroupProvider");
                }

                if (uniqueName.equals(NetworkOffering.DefaultIsolatedNetworkOfferingWithSourceNatService.toString()) || uniqueName.equalsIgnoreCase(externalOfferingName)) {
                    if (externalOfferingName != null && uniqueName.equalsIgnoreCase(externalOfferingName)) {
                        services.put("SourceNat", "JuniperSRX");
                        services.put("PortForwarding", "JuniperSRX");
                        services.put("StaticNat", "JuniperSRX");
                        sharedSourceNat = true;
                    } else {
                        services.put("SourceNat", "VirtualRouter");
                        services.put("PortForwarding", "VirtualRouter");
                        services.put("StaticNat", "VirtualRouter");
                    }
                }

                for (String service : services.keySet()) {
                    pstmt = conn.prepareStatement("INSERT INTO `cloud`.`ntwk_offering_service_map` (`network_offering_id`," +
                    		" `service`, `provider`, `created`) values (?,?,?, now())");
                    pstmt.setLong(1, id);
                    pstmt.setString(2, service);
                    pstmt.setString(3, services.get(service));
                    pstmt.executeUpdate();
                }
                
                //update shared source nat and dedicated lb
                pstmt = conn.prepareStatement("UPDATE `cloud`.`network_offerings` set shared_source_nat_service=?, dedicated_lb_service=? where id=?");
                pstmt.setBoolean(1, sharedSourceNat);
                pstmt.setBoolean(2, dedicatedLb);
                pstmt.setLong(3, id);
                pstmt.executeUpdate();
                
            }
        } catch (SQLException e) {
            throw new CloudRuntimeException("Unable to create service/provider map for network offerings", e);
        } finally {
            try {
                if (rs != null) {
                    rs.close();
                }

                if (pstmt != null) {
                    pstmt.close();
                }
            } catch (SQLException e) {
            }
        }
    }

    private void updateDomainNetworkRef(Connection conn) {
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            // update subdomain access field for existing domain specific networks
            pstmt = conn.prepareStatement("select value from `cloud`.`configuration` where name='allow.subdomain.network.access'");
            rs = pstmt.executeQuery();
            while (rs.next()) {
                boolean subdomainAccess = Boolean.valueOf(rs.getString(1));
                pstmt = conn.prepareStatement("UPDATE `cloud`.`domain_network_ref` SET subdomain_access=?");
                pstmt.setBoolean(1, subdomainAccess);
                pstmt.executeUpdate();
                s_logger.debug("Successfully updated subdomain_access field in network_domain table with value " + subdomainAccess);
            }

            // convert zone level 2.2.x networks to ROOT domain 3.0 access networks
            pstmt = conn.prepareStatement("select id from `cloud`.`networks` where shared=true and is_domain_specific=false and traffic_type='Guest'");
            rs = pstmt.executeQuery();
            while (rs.next()) {
                long networkId = rs.getLong(1);
                pstmt = conn.prepareStatement("INSERT INTO `cloud`.`domain_network_ref` (domain_id, network_id, subdomain_access) VALUES (1, ?, 1)");
                pstmt.setLong(1, networkId);
                pstmt.executeUpdate();
                s_logger.debug("Successfully converted zone specific network id=" + networkId + " to the ROOT domain level network with subdomain access set to true");
            }

        } catch (SQLException e) {
            throw new CloudRuntimeException("Unable to update domain network ref", e);
        } finally {
            try {
                if (rs != null) {
                    rs.close();
                }

                if (pstmt != null) {
                    pstmt.close();
                }
            } catch (SQLException e) {
            }
        }
    }

    protected void createNetworkServices(Connection conn) {
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        ResultSet rs1 = null;
        try {
            pstmt = conn.prepareStatement("select id, network_offering_id from `cloud`.`networks` where traffic_type='Guest'");
            rs = pstmt.executeQuery();
            while (rs.next()) {
                long networkId = rs.getLong(1);
                long networkOfferingId = rs.getLong(2);
                pstmt = conn.prepareStatement("select service, provider from `cloud`.`ntwk_offering_service_map` where network_offering_id=?");
                pstmt.setLong(1, networkOfferingId);
                rs1 = pstmt.executeQuery();
                while (rs1.next()) {
                    String service = rs1.getString(1);
                    String provider = rs1.getString(2);
                    pstmt = conn.prepareStatement("INSERT INTO `cloud`.`ntwk_service_map` (`network_id`, `service`, `provider`, `created`) values (?,?,?, now())");
                    pstmt.setLong(1, networkId);
                    pstmt.setString(2, service);
                    pstmt.setString(3, provider);
                    pstmt.executeUpdate();
                }
                s_logger.debug("Created service/provider map for network id=" + networkId);
            }
        } catch (SQLException e) {
            throw new CloudRuntimeException("Unable to create service/provider map for networks", e);
        } finally {
            try {
                if (rs != null) {
                    rs.close();
                }

                if (rs1 != null) {
                    rs1.close();
                }

                if (pstmt != null) {
                    pstmt.close();
                }
            } catch (SQLException e) {
            }
        }
    }
    
    protected void updateRouters(Connection conn) {
        PreparedStatement pstmt = null;
        try {
            s_logger.debug("Updating domain_router table");
            pstmt = conn
                    .prepareStatement("UPDATE domain_router, virtual_router_providers vrp LEFT JOIN (physical_network_service_providers pnsp INNER JOIN physical_network pntwk INNER JOIN vm_instance vm INNER JOIN domain_router vr) ON (vrp.nsp_id = pnsp.id AND pnsp.physical_network_id = pntwk.id AND pntwk.data_center_id = vm.data_center_id AND vm.id=vr.id) SET vr.element_id=vrp.id;");
            pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new CloudRuntimeException("Unable to update router table. ", e);
        } finally {
            try {
                if (pstmt != null) {
                    pstmt.close();
                }
            } catch (SQLException e) {
                throw new CloudRuntimeException("Unable to close statement for router table. ", e);
            }
        }
    }

    protected void updateReduntantRouters(Connection conn) {
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        ResultSet rs1 = null;
        try {
            // get all networks that need to be updated to the redundant network offerings
            pstmt = conn
                    .prepareStatement("select ni.network_id, n.network_offering_id from `cloud`.`nics` ni, `cloud`.`networks` n where ni.instance_id in (select id from `cloud`.`domain_router` where is_redundant_router=1) and n.id=ni.network_id and n.traffic_type='Guest'");
            rs = pstmt.executeQuery();
            pstmt = conn.prepareStatement("select count(*) from `cloud`.`network_offerings`");
            rs1 = pstmt.executeQuery();
            long ntwkOffCount = 0;
            while (rs1.next()) {
                ntwkOffCount = rs1.getLong(1);
            }

            s_logger.debug("Have " + ntwkOffCount + " networkOfferings");
            pstmt = conn.prepareStatement("CREATE TEMPORARY TABLE `cloud`.`network_offerings2` ENGINE=MEMORY SELECT * FROM `cloud`.`network_offerings` WHERE id=1");
            pstmt.executeUpdate();

            HashMap<Long, Long> newNetworkOfferingMap = new HashMap<Long, Long>();

            while (rs.next()) {
                long networkId = rs.getLong(1);
                long networkOfferingId = rs.getLong(2);
                s_logger.debug("Updating network offering for the network id=" + networkId + " as it has redundant routers");
                Long newNetworkOfferingId = null;

                if (!newNetworkOfferingMap.containsKey(networkOfferingId)) {
                    // clone the record to
                    pstmt = conn.prepareStatement("INSERT INTO `cloud`.`network_offerings2` SELECT * FROM `cloud`.`network_offerings` WHERE id=?");
                    pstmt.setLong(1, networkOfferingId);
                    pstmt.executeUpdate();

                    pstmt = conn.prepareStatement("SELECT unique_name FROM `cloud`.`network_offerings` WHERE id=?");
                    pstmt.setLong(1, networkOfferingId);
                    rs1 = pstmt.executeQuery();
                    String uniqueName = null;
                    while (rs1.next()) {
                        uniqueName = rs1.getString(1) + "-redundant";
                    }

                    pstmt = conn.prepareStatement("UPDATE `cloud`.`network_offerings2` SET id=?, redundant_router_service=1, unique_name=?, name=? WHERE id=?");
                    ntwkOffCount = ntwkOffCount + 1;
                    newNetworkOfferingId = ntwkOffCount;
                    pstmt.setLong(1, newNetworkOfferingId);
                    pstmt.setString(2, uniqueName);
                    pstmt.setString(3, uniqueName);
                    pstmt.setLong(4, networkOfferingId);
                    pstmt.executeUpdate();

                    pstmt = conn.prepareStatement("INSERT INTO `cloud`.`network_offerings` SELECT * from `cloud`.`network_offerings2` WHERE id=" + newNetworkOfferingId);
                    pstmt.executeUpdate();

                    pstmt = conn.prepareStatement("UPDATE `cloud`.`networks` SET network_offering_id=? where id=?");
                    pstmt.setLong(1, newNetworkOfferingId);
                    pstmt.setLong(2, networkId);
                    pstmt.executeUpdate();

                    newNetworkOfferingMap.put(networkOfferingId, ntwkOffCount);
                } else {
                    pstmt = conn.prepareStatement("UPDATE `cloud`.`networks` SET network_offering_id=? where id=?");
                    newNetworkOfferingId = newNetworkOfferingMap.get(networkOfferingId);
                    pstmt.setLong(1, newNetworkOfferingId);
                    pstmt.setLong(2, networkId);
                    pstmt.executeUpdate();
                }

                s_logger.debug("Successfully updated network offering id=" + networkId + " with new network offering id " + newNetworkOfferingId);
            }

        } catch (SQLException e) {
            throw new CloudRuntimeException("Unable to update redundant router networks", e);
        } finally {
            try {
                pstmt = conn.prepareStatement("DROP TABLE `cloud`.`network_offerings2`");
                pstmt.executeUpdate();
                if (rs != null) {
                    rs.close();
                }

                if (rs1 != null) {
                    rs1.close();
                }

                if (pstmt != null) {
                    pstmt.close();
                }
            } catch (SQLException e) {
            }
        }
    }
    
    protected void updateHostCapacity(Connection conn){
        PreparedStatement pstmt = null;
        try {
            s_logger.debug("Updating op_host_capacity table, column capacity_state");
            pstmt = conn
                    .prepareStatement("UPDATE op_host_capacity, host SET op_host_capacity.capacity_state='Disabled' where host.id=op_host_capacity.host_id and op_host_capacity.capacity_type in (0,1) and host.resource_state='Disabled';");
            pstmt.executeUpdate();
            pstmt = conn
                    .prepareStatement("UPDATE op_host_capacity, cluster SET op_host_capacity.capacity_state='Disabled' where cluster.id=op_host_capacity.cluster_id and cluster.allocation_state='Disabled';");
            pstmt.executeUpdate();
            pstmt = conn
                    .prepareStatement("UPDATE op_host_capacity, host_pod_ref SET op_host_capacity.capacity_state='Disabled' where host_pod_ref.id=op_host_capacity.pod_id and host_pod_ref.allocation_state='Disabled';");
            pstmt.executeUpdate();
            pstmt = conn
                    .prepareStatement("UPDATE op_host_capacity, data_center SET op_host_capacity.capacity_state='Disabled' where data_center.id=op_host_capacity.data_center_id and data_center.allocation_state='Disabled';");
            pstmt.executeUpdate();
        } catch (SQLException e) {
            throw new CloudRuntimeException("Unable to update op_host_capacity table. ", e);
        } finally {
            try {
                if (pstmt != null) {
                    pstmt.close();
                }
            } catch (SQLException e) {
                throw new CloudRuntimeException("Unable to close statement for op_host_capacity table. ", e);
            }
        }
    }

    protected void switchAccountSpecificNetworksToIsolated(Connection conn) {
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        ResultSet rs1 = null;
        try {
            //check if switch_to_isolated is present; if not - skip this part of the code
            try {
                pstmt = conn
                        .prepareStatement("select switch_to_isolated from `cloud`.`networks`");
                rs = pstmt.executeQuery();
            } catch (Exception ex) {
                s_logger.debug("switch_to_isolated field is not present in networks table");
                return ;
            }
            
            // get all networks that need to be updated to the isolated network offering
            pstmt = conn
                    .prepareStatement("select id, network_offering_id from `cloud`.`networks` where switch_to_isolated=1");
            rs = pstmt.executeQuery();
            pstmt = conn.prepareStatement("select count(*) from `cloud`.`network_offerings`");
            rs1 = pstmt.executeQuery();
            long ntwkOffCount = 0;
            while (rs1.next()) {
                ntwkOffCount = rs1.getLong(1);
            }

            s_logger.debug("Have " + ntwkOffCount + " networkOfferings");
            pstmt = conn.prepareStatement("CREATE TEMPORARY TABLE `cloud`.`network_offerings2` ENGINE=MEMORY SELECT * FROM `cloud`.`network_offerings` WHERE id=1");
            pstmt.executeUpdate();

            HashMap<Long, Long> newNetworkOfferingMap = new HashMap<Long, Long>();

            while (rs.next()) {
                long networkId = rs.getLong(1);
                long networkOfferingId = rs.getLong(2);
                s_logger.debug("Updating network offering for the network id=" + networkId + " as it has switch_to_isolated=1");
                Long newNetworkOfferingId = null;

                if (!newNetworkOfferingMap.containsKey(networkOfferingId)) {
                    // clone the record to
                    pstmt = conn.prepareStatement("INSERT INTO `cloud`.`network_offerings2` SELECT * FROM `cloud`.`network_offerings` WHERE id=?");
                    pstmt.setLong(1, networkOfferingId);
                    pstmt.executeUpdate();

                    pstmt = conn.prepareStatement("UPDATE `cloud`.`network_offerings2` SET id=?, guest_type='Isolated', unique_name=?, name=? WHERE id=?");
                    ntwkOffCount = ntwkOffCount + 1;
                    newNetworkOfferingId = ntwkOffCount;
                    String uniqueName = "Isolated w/o source nat";
                    pstmt.setLong(1, newNetworkOfferingId);
                    pstmt.setString(2, uniqueName);
                    pstmt.setString(3, uniqueName);
                    pstmt.setLong(4, networkOfferingId);
                    pstmt.executeUpdate();

                    pstmt = conn.prepareStatement("INSERT INTO `cloud`.`network_offerings` SELECT * from `cloud`.`network_offerings2` WHERE id=" + newNetworkOfferingId);
                    pstmt.executeUpdate();

                    pstmt = conn.prepareStatement("UPDATE `cloud`.`networks` SET network_offering_id=? where id=?");
                    pstmt.setLong(1, newNetworkOfferingId);
                    pstmt.setLong(2, networkId);
                    pstmt.executeUpdate();

                    newNetworkOfferingMap.put(networkOfferingId, ntwkOffCount);
                } else {
                    pstmt = conn.prepareStatement("UPDATE `cloud`.`networks` SET network_offering_id=? where id=?");
                    newNetworkOfferingId = newNetworkOfferingMap.get(networkOfferingId);
                    pstmt.setLong(1, newNetworkOfferingId);
                    pstmt.setLong(2, networkId);
                    pstmt.executeUpdate();
                }

                s_logger.debug("Successfully updated network offering id=" + networkId + " with new network offering id " + newNetworkOfferingId);
            }

            try {
                pstmt = conn.prepareStatement("ALTER TABLE `cloud`.`networks` DROP COLUMN `switch_to_isolated`");
                pstmt.executeUpdate();
            } catch (Exception ex) {
                // do nothing here
            }

        } catch (SQLException e) {
            throw new CloudRuntimeException("Unable to switch networks to isolated", e);
        } finally {
            try {
                pstmt = conn.prepareStatement("DROP TABLE `cloud`.`network_offerings2`");
                pstmt.executeUpdate();
                if (rs != null) {
                    rs.close();
                }

                if (pstmt != null) {
                    pstmt.close();
                }
            } catch (SQLException e) {
            }
        }
    }
    
    private void migrateUserConcentratedPlannerChoice(Connection conn) {
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            pstmt = conn.prepareStatement("SELECT value FROM `cloud`.`configuration` where name = 'use.user.concentrated.pod.allocation'");
            rs = pstmt.executeQuery();
            Boolean isuserconcentrated = false;
            if(rs.next()) {
                String value = rs.getString(1); 
                isuserconcentrated = new Boolean(value);
            }
            rs.close();
            pstmt.close();
            
            if(isuserconcentrated){
                String currentAllocationAlgo = "random"; 
                pstmt = conn.prepareStatement("SELECT value FROM `cloud`.`configuration` where name = 'vm.allocation.algorithm'");
                rs = pstmt.executeQuery();
                if(rs.next()) {
                    currentAllocationAlgo = rs.getString(1);
                }
                rs.close();
                pstmt.close();
                
                String newAllocAlgo = "userconcentratedpod_random";
                if("random".equalsIgnoreCase(currentAllocationAlgo)){
                    newAllocAlgo = "userconcentratedpod_random";
                }else{
                    newAllocAlgo = "userconcentratedpod_firstfit";
                }
                
                pstmt = conn.prepareStatement("UPDATE `cloud`.`configuration` SET value = ? WHERE name = 'vm.allocation.algorithm'");
                pstmt.setString(1, newAllocAlgo);
                pstmt.executeUpdate();
                
            }

        } catch (SQLException e) {
            throw new CloudRuntimeException("Unable to migrate the user_concentrated planner choice", e);
        } finally {
            try {
                if (rs != null) {
                    rs.close();
                }
                if (pstmt != null) {
                    pstmt.close();
                }
            } catch (SQLException e) {
            }
        }
    }
    
    protected String fixNetworksWithExternalDevices(Connection conn) {
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        ResultSet rs1 = null;
        
        //Get zones to upgrade
        List<Long> zoneIds = new ArrayList<Long>();
        try {
            pstmt = conn.prepareStatement("select id from `cloud`.`data_center` where lb_provider='F5BigIp' or firewall_provider='JuniperSRX' or gateway_provider='JuniperSRX'");
            rs = pstmt.executeQuery();
            while (rs.next()) {
                zoneIds.add(rs.getLong(1));
            }
        } catch (SQLException e) {
            throw new CloudRuntimeException("Unable to switch networks to the new network offering", e);
        }
        
        
        String uniqueName = null;
        HashMap<Long, Long> newNetworkOfferingMap = new HashMap<Long, Long>();
        
        for (Long zoneId : zoneIds) {
            try {
                // Find the correct network offering
                pstmt = conn
                        .prepareStatement("select id, network_offering_id from `cloud`.`networks` where guest_type='Virtual' and data_center_id=?");
                pstmt.setLong(1, zoneId);
                rs = pstmt.executeQuery();
                pstmt = conn.prepareStatement("select count(*) from `cloud`.`network_offerings`");
                rs1 = pstmt.executeQuery();
                long ntwkOffCount = 0;
                while (rs1.next()) {
                    ntwkOffCount = rs1.getLong(1);
                } 

                pstmt = conn.prepareStatement("CREATE TEMPORARY TABLE `cloud`.`network_offerings2` ENGINE=MEMORY SELECT * FROM `cloud`.`network_offerings` WHERE id=1");
                pstmt.executeUpdate();


                while (rs.next()) {
                    long networkId = rs.getLong(1);
                    long networkOfferingId = rs.getLong(2);
                    s_logger.debug("Updating network offering for the network id=" + networkId + " as it has switch_to_isolated=1");
                    Long newNetworkOfferingId = null;
                    if (!newNetworkOfferingMap.containsKey(networkOfferingId)) {
                        uniqueName = "Isolated with external providers";
                        // clone the record to
                        pstmt = conn.prepareStatement("INSERT INTO `cloud`.`network_offerings2` SELECT * FROM `cloud`.`network_offerings` WHERE id=?");
                        pstmt.setLong(1, networkOfferingId);
                        pstmt.executeUpdate();

                        //set the new unique name
                        pstmt = conn.prepareStatement("UPDATE `cloud`.`network_offerings2` SET id=?, unique_name=?, name=? WHERE id=?");
                        ntwkOffCount = ntwkOffCount + 1;
                        newNetworkOfferingId = ntwkOffCount;
                        pstmt.setLong(1, newNetworkOfferingId);
                        pstmt.setString(2, uniqueName);
                        pstmt.setString(3, uniqueName);
                        pstmt.setLong(4, networkOfferingId);
                        pstmt.executeUpdate();

                        pstmt = conn.prepareStatement("INSERT INTO `cloud`.`network_offerings` SELECT * from " +
                                "`cloud`.`network_offerings2` WHERE id=" + newNetworkOfferingId);
                        pstmt.executeUpdate();

                        pstmt = conn.prepareStatement("UPDATE `cloud`.`networks` SET network_offering_id=? where id=?");
                        pstmt.setLong(1, newNetworkOfferingId);
                        pstmt.setLong(2, networkId);
                        pstmt.executeUpdate();

                        newNetworkOfferingMap.put(networkOfferingId, ntwkOffCount);
                    } else {
                        pstmt = conn.prepareStatement("UPDATE `cloud`.`networks` SET network_offering_id=? where id=?");
                        newNetworkOfferingId = newNetworkOfferingMap.get(networkOfferingId);
                        pstmt.setLong(1, newNetworkOfferingId);
                        pstmt.setLong(2, networkId);
                        pstmt.executeUpdate();
                    }

                    s_logger.debug("Successfully updated network id=" + networkId + " with new network offering id " + newNetworkOfferingId);
                }

            } catch (SQLException e) {
                throw new CloudRuntimeException("Unable to switch networks to the new network offering", e);
            } finally {
                try {
                    pstmt = conn.prepareStatement("DROP TABLE `cloud`.`network_offerings2`");
                    pstmt.executeUpdate();
                    if (rs != null) {
                        rs.close();
                    }

                    if (pstmt != null) {
                        pstmt.close();
                    }
                } catch (SQLException e) {
                }
            }
        }
        
        return uniqueName;
    }
}
