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
package com.cloud.network.rules;

import java.util.List;

import com.cloud.network.rules.FirewallRule.FirewallRuleType;


public class StaticNatRuleImpl implements StaticNatRule{
    long id;
    String xid;
    String protocol;
    int portStart;
    int portEnd;
    State state;
    long accountId;
    long domainId;
    long networkId;
    long sourceIpAddressId;
    String destIpAddress;

    public StaticNatRuleImpl(FirewallRuleVO rule, String dstIp) {  
        this.id = rule.getId();
        this.xid = rule.getXid();
        this.protocol = rule.getProtocol();
        this.portStart = rule.getSourcePortStart();
        this.portEnd = rule.getSourcePortEnd();
        this.state = rule.getState();
        this.accountId = rule.getAccountId();
        this.domainId = rule.getDomainId();
        this.networkId = rule.getNetworkId();
        this.sourceIpAddressId = rule.getSourceIpAddressId();
        this.destIpAddress = dstIp;
    }

    @Override
    public String getProtocol() {
        return protocol;
    }

    @Override
    public Integer getSourcePortEnd() {
        return portEnd;
    }
    
    @Override
    public Purpose getPurpose() {
        return Purpose.StaticNat;
    }

    @Override
    public State getState() {
        return state;
    }
    
    @Override
    public long getAccountId() {
        return accountId;
    }
    
    @Override
    public long getDomainId() {
        return domainId;
    }
    
    @Override
    public long getNetworkId() {
        return networkId;
    }

    @Override
    public long getId() {
        return id;
    }

    @Override
    public Integer getSourcePortStart() {
        return portStart;
    }

    @Override
    public long getSourceIpAddressId() {
        return sourceIpAddressId;
    }

    @Override
    public String getDestIpAddress() {
        return destIpAddress;
    }

    @Override
    public String getXid() {
        return xid;
    }
    
    @Override
    public Integer getIcmpCode() {
        return null;
    }
    
    @Override
    public Integer getIcmpType() {
        return null;
    }

    @Override
    public List<String> getSourceCidrList() {
        return null;
    }

    @Override
    public Long getRelated() {
        return null;
    }

	@Override
	public FirewallRuleType getType() {
		return FirewallRuleType.User;
	}

}
