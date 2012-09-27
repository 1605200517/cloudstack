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
package com.cloud.async.dao;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Date;
import java.util.TimeZone;

import javax.ejb.Local;

import org.apache.log4j.Logger;

import com.cloud.async.SyncQueueVO;
import com.cloud.utils.DateUtil;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.GenericDaoBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.Transaction;

@Local(value = { SyncQueueDao.class })
public class SyncQueueDaoImpl extends GenericDaoBase<SyncQueueVO, Long> implements SyncQueueDao {
    private static final Logger s_logger = Logger.getLogger(SyncQueueDaoImpl.class.getName());
    
    SearchBuilder<SyncQueueVO> TypeIdSearch = createSearchBuilder();
	
	@Override
	public void ensureQueue(String syncObjType, long syncObjId) {
		Date dt = DateUtil.currentGMTTime();
		String sql = "INSERT IGNORE INTO sync_queue(sync_objtype, sync_objid, created, last_updated) values(?, ?, ?, ?)";
		
        Transaction txn = Transaction.currentTxn();
        PreparedStatement pstmt = null;
        try {
            pstmt = txn.prepareAutoCloseStatement(sql);
            pstmt.setString(1, syncObjType);
            pstmt.setLong(2, syncObjId);
            pstmt.setString(3, DateUtil.getDateDisplayString(TimeZone.getTimeZone("GMT"), dt));
            pstmt.setString(4, DateUtil.getDateDisplayString(TimeZone.getTimeZone("GMT"), dt));
            pstmt.execute();
        } catch (SQLException e) {
        	s_logger.warn("Unable to create sync queue " + syncObjType + "-" + syncObjId + ":" + e.getMessage(), e);
        } catch (Throwable e) {
        	s_logger.warn("Unable to create sync queue " + syncObjType + "-" + syncObjId + ":" + e.getMessage(), e);
        }
	}
	
	@Override
	public SyncQueueVO find(String syncObjType, long syncObjId) {
    	SearchCriteria<SyncQueueVO> sc = TypeIdSearch.create();
    	sc.setParameters("syncObjType", syncObjType);
    	sc.setParameters("syncObjId", syncObjId);
        return findOneBy(sc);
	}

	@Override @DB
	public void resetQueueProcessing(long msid) {
		String sql = "UPDATE sync_queue set queue_proc_msid=NULL, queue_proc_time=NULL where queue_proc_msid=?";
		
        Transaction txn = Transaction.currentTxn();
        PreparedStatement pstmt = null;
        try {
            pstmt = txn.prepareAutoCloseStatement(sql);
            pstmt.setLong(1, msid);
            pstmt.execute();
        } catch (SQLException e) {
        	s_logger.warn("Unable to reset sync queue for management server " + msid, e);
        } catch (Throwable e) {
        	s_logger.warn("Unable to reset sync queue for management server " + msid, e);
        }
	}
	
	protected SyncQueueDaoImpl() {
	    super();
	    TypeIdSearch = createSearchBuilder();
        TypeIdSearch.and("syncObjType", TypeIdSearch.entity().getSyncObjType(), SearchCriteria.Op.EQ);
        TypeIdSearch.and("syncObjId", TypeIdSearch.entity().getSyncObjId(), SearchCriteria.Op.EQ);
        TypeIdSearch.done();
	}
}
