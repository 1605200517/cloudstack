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

package com.cloud.bridge.service.core.ec2;

import java.util.ArrayList;
import java.util.List;

/**
 * @author slriv
 *
 */
public class EC2DescribeKeyPairs {
	private EC2KeyPairFilterSet keyFilterSet;
	private List<String> keyNames;
	
	public EC2DescribeKeyPairs() {
		keyNames = new ArrayList<String>();
	}
	
	/**
	 * @return the keyNames String Array
	 */
	public String[] getKeyNames() {
		return keyNames.toArray(new String[0]);
	}

	/**
	 * @param keyName the keyName to add
	 */
	public void addKeyName(String keyName) {
		keyNames.add(keyName);
	}

	/**
	 * @return the keyFilterSet
	 */
	public EC2KeyPairFilterSet getKeyFilterSet() {
		return keyFilterSet;
	}

	/**
	 * @param keyFilterSet the keyFilterSet to set
	 */
	public void setKeyFilterSet(EC2KeyPairFilterSet keyFilterSet) {
		this.keyFilterSet = keyFilterSet;
	}

}
