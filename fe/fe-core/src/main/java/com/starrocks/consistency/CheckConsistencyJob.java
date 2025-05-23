// Copyright 2021-present StarRocks, Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     https://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

// This file is based on code available under the Apache license here:
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/consistency/CheckConsistencyJob.java

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

package com.starrocks.consistency;

import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.LocalTablet;
import com.starrocks.catalog.MaterializedIndex;
import com.starrocks.catalog.MetaObject;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.PhysicalPartition;
import com.starrocks.catalog.Replica;
import com.starrocks.catalog.Replica.ReplicaState;
import com.starrocks.catalog.Table;
import com.starrocks.catalog.TabletInvertedIndex;
import com.starrocks.catalog.TabletMeta;
import com.starrocks.common.Config;
import com.starrocks.common.util.concurrent.lock.AutoCloseableLock;
import com.starrocks.common.util.concurrent.lock.LockType;
import com.starrocks.common.util.concurrent.lock.Locker;
import com.starrocks.journal.JournalTask;
import com.starrocks.persist.ConsistencyCheckInfo;
import com.starrocks.persist.EditLog;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.task.AgentBatchTask;
import com.starrocks.task.AgentTask;
import com.starrocks.task.AgentTaskExecutor;
import com.starrocks.task.AgentTaskQueue;
import com.starrocks.task.CheckConsistencyTask;
import com.starrocks.thrift.TTaskType;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Iterator;
import java.util.Map;

public class CheckConsistencyJob {
    private static final Logger LOG = LogManager.getLogger(CheckConsistencyJob.class);

    private static final long CHECK_CONSISTENCT_TIME_COST_PER_GIGABYTE_MS = 1000000L; // 1000s

    public enum JobState {
        PENDING,
        RUNNING
    }

    private JobState state;
    private final long tabletId;

    // backend id -> check sum
    // add backend id to this map only after sending task
    private Map<Long, Long> checksumMap;

    private int checkedSchemaHash;
    private long checkedVersion;

    private long createTime;
    private long timeoutMs;

    public CheckConsistencyJob(long tabletId) {
        this.state = JobState.PENDING;
        this.tabletId = tabletId;

        this.checksumMap = Maps.newHashMap();

        this.checkedSchemaHash = -1;
        this.checkedVersion = -1L;

        this.createTime = System.currentTimeMillis();
        this.timeoutMs = 0L;
    }

    public JobState getState() {
        return state;
    }

    public void setState(JobState state) {
        this.state = state;
    }

    public long getTabletId() {
        return tabletId;
    }

    /*
     * return:
     *  true: continue
     *  false: cancel
     */
    public boolean sendTasks() {
        TabletInvertedIndex invertedIndex = GlobalStateMgr.getCurrentState().getTabletInvertedIndex();
        TabletMeta tabletMeta = invertedIndex.getTabletMeta(tabletId);
        if (tabletMeta == null) {
            LOG.debug("tablet[{}] has been removed", tabletId);
            return false;
        }

        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(tabletMeta.getDbId());
        if (db == null) {
            LOG.debug("db[{}] does not exist", tabletMeta.getDbId());
            return false;
        }

        Table table = GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getId(), tabletMeta.getTableId());
        if (table == null) {
            LOG.debug("table[{}] does not exist", tabletMeta.getTableId());
            return false;
        }

        LocalTablet tablet = null;

        AgentBatchTask batchTask = new AgentBatchTask();
        try (AutoCloseableLock ignore = new AutoCloseableLock(new Locker(), db.getId(), Lists.newArrayList(table.getId()),
                    LockType.READ)) {
            OlapTable olapTable = (OlapTable) table;

            PhysicalPartition physicalPartition = olapTable.getPhysicalPartition(tabletMeta.getPhysicalPartitionId());
            if (physicalPartition == null) {
                LOG.debug("partition[{}] does not exist", tabletMeta.getPhysicalPartitionId());
                return false;
            }

            // check partition's replication num. if 1 replication. skip
            short replicationNum = olapTable.getPartitionInfo().getReplicationNum(physicalPartition.getParentId());
            if (replicationNum == (short) 1) {
                LOG.debug("partition[{}]'s replication num is 1. skip consistency check", physicalPartition.getParentId());
                return false;
            }

            MaterializedIndex index = physicalPartition.getIndex(tabletMeta.getIndexId());
            if (index == null) {
                LOG.debug("index[{}] does not exist", tabletMeta.getIndexId());
                return false;
            }

            tablet = (LocalTablet) index.getTablet(tabletId);
            if (tablet == null) {
                LOG.debug("tablet[{}] does not exist", tabletId);
                return false;
            }

            checkedVersion = physicalPartition.getVisibleVersion();
            checkedSchemaHash = olapTable.getSchemaHashByIndexId(tabletMeta.getIndexId());

            int sentTaskReplicaNum = 0;
            long maxDataSize = 0;
            for (Replica replica : tablet.getImmutableReplicas()) {
                // 1. if state is CLONE, do not send task at this time
                if (replica.getState() == ReplicaState.CLONE
                            || replica.getState() == ReplicaState.DECOMMISSION) {
                    continue;
                }

                if (replica.getDataSize() > maxDataSize) {
                    maxDataSize = replica.getDataSize();
                }

                CheckConsistencyTask task = new CheckConsistencyTask(null, replica.getBackendId(),
                            tabletMeta.getDbId(),
                            tabletMeta.getTableId(),
                            tabletMeta.getPhysicalPartitionId(),
                            tabletMeta.getIndexId(),
                            tabletId, checkedSchemaHash,
                            checkedVersion);

                // add task to send
                batchTask.addTask(task);

                // init checksum as '-1'
                checksumMap.put(replica.getBackendId(), -1L);

                ++sentTaskReplicaNum;
            }

            if (sentTaskReplicaNum < replicationNum / 2 + 1) {
                LOG.info("tablet[{}] does not have enough replica to check.", tabletId);
            } else {
                if (maxDataSize > 0) {
                    timeoutMs = maxDataSize / 1000 / 1000 / 1000 * CHECK_CONSISTENCT_TIME_COST_PER_GIGABYTE_MS;
                }
                timeoutMs = Math.max(timeoutMs, Config.check_consistency_default_timeout_second * 1000L);
                state = JobState.RUNNING;
            }
        }

        if (state != JobState.RUNNING) {
            // failed to send task. set tablet's checked version to avoid choosing it again
            try (AutoCloseableLock ignore = new AutoCloseableLock(new Locker(), db.getId(), Lists.newArrayList(table.getId()),
                        LockType.WRITE)) {
                tablet.setCheckedVersion(checkedVersion);
            }
            return false;
        }

        // send task
        Preconditions.checkState(batchTask.getTaskNum() > 0);
        for (AgentTask task : batchTask.getAllTasks()) {
            AgentTaskQueue.addTask(task);
        }
        AgentTaskExecutor.submit(batchTask);
        LOG.debug("tablet[{}] send check consistency task. num: {}", tabletId, batchTask.getTaskNum());

        return true;
    }

    /*
     * return:
     *  0: not finished
     *  1: finished
     *  -1: cancel
     */
    public synchronized int tryFinishJob() {
        if (state == JobState.PENDING) {
            return 0;
        }

        // check again. in case tablet has already been removed
        TabletMeta tabletMeta = GlobalStateMgr.getCurrentState().getTabletInvertedIndex().getTabletMeta(tabletId);
        if (tabletMeta == null) {
            LOG.warn("tablet[{}] has been removed", tabletId);
            return -1;
        }

        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(tabletMeta.getDbId());
        if (db == null) {
            LOG.warn("db[{}] does not exist", tabletMeta.getDbId());
            return -1;
        }

        Table table = GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getId(), tabletMeta.getTableId());
        if (table == null) {
            LOG.warn("table[{}] does not exist", tabletMeta.getTableId());
            return -1;
        }

        boolean isConsistent = true;
        JournalTask journalTask;
        try (AutoCloseableLock ignore =
                    new AutoCloseableLock(new Locker(), db.getId(), Lists.newArrayList(table.getId()), LockType.WRITE)) {
            OlapTable olapTable = (OlapTable) table;

            PhysicalPartition physicalPartition = olapTable.getPhysicalPartition(tabletMeta.getPhysicalPartitionId());
            if (physicalPartition == null) {
                LOG.warn("partition[{}] does not exist", tabletMeta.getPhysicalPartitionId());
                return -1;
            }

            MaterializedIndex index = physicalPartition.getIndex(tabletMeta.getIndexId());
            if (index == null) {
                LOG.warn("index[{}] does not exist", tabletMeta.getIndexId());
                return -1;
            }

            LocalTablet tablet = (LocalTablet) index.getTablet(tabletId);
            if (tablet == null) {
                LOG.warn("tablet[{}] does not exist", tabletId);
                return -1;
            }

            // check if schema has changed
            if (checkedSchemaHash != olapTable.getSchemaHashByIndexId(tabletMeta.getIndexId())) {
                LOG.info("index[{}]'s schema hash has been changed. [{} -> {}]. retry", tabletMeta.getIndexId(),
                            checkedSchemaHash, olapTable.getSchemaHashByIndexId(tabletMeta.getIndexId()));
                return -1;
            }

            if (!isTimeout()) {
                // check finished. remove replica in checksumMap which does not exist anymore
                boolean isFinished = true;
                Iterator<Map.Entry<Long, Long>> iter = checksumMap.entrySet().iterator();
                while (iter.hasNext()) {
                    Map.Entry<Long, Long> entry = iter.next();
                    Replica replica = tablet.getReplicaByBackendId(entry.getKey());
                    if (replica == null) {
                        LOG.debug("tablet[{}]'s replica in backend[{}] does not exist. remove from checksumMap",
                                    tabletId, entry.getKey());
                        iter.remove();
                        continue;
                    }

                    if (entry.getValue() == -1) {
                        LOG.debug("tablet[{}] has unfinished replica check sum task. backend[{}]",
                                    tabletId, entry.getKey());
                        isFinished = false;
                    } else {
                        // set replica's checksum
                        replica.setChecksum(entry.getValue());
                    }
                }

                if (!isFinished) {
                    return 0;
                }

                // all clear. check checksum
                long lastChecksum = -1L;
                for (Map.Entry<Long, Long> entry : checksumMap.entrySet()) {
                    long checksum = entry.getValue();
                    if (lastChecksum == -1) {
                        lastChecksum = checksum;
                    } else {
                        if (checksum != lastChecksum) {
                            // find different one
                            isConsistent = false;
                            break;
                        }
                    }
                }

                if (isConsistent) {
                    LOG.info("tablet[{}] is consistent: {}", tabletId, checksumMap.keySet());
                } else {
                    StringBuilder sb = new StringBuilder();
                    sb.append("tablet[").append(tabletId).append("] is not consistent: ");
                    for (Map.Entry<Long, Long> entry : checksumMap.entrySet()) {
                        sb.append("[").append(entry.getKey()).append("-").append(entry.getValue()).append("]");
                    }
                    sb.append(" [").append(tabletMeta).append("]");
                    LOG.error(sb.toString());
                }
            } else {
                LOG.info("tablet[{}] check consistency job cancelled. timeout", tabletId);
            }

            // no matter timeout or not. set this tablet as finished
            // job done. set last check time to each instance
            long lastCheckTime = System.currentTimeMillis();
            db.setLastCheckTime(lastCheckTime);
            olapTable.setLastCheckTime(lastCheckTime);
            if (physicalPartition instanceof MetaObject) {
                ((MetaObject) physicalPartition).setLastCheckTime(lastCheckTime);
            }
            index.setLastCheckTime(lastCheckTime);
            tablet.setLastCheckTime(lastCheckTime);
            tablet.setIsConsistent(isConsistent);

            // set checked version
            tablet.setCheckedVersion(checkedVersion);

            // log
            ConsistencyCheckInfo info = new ConsistencyCheckInfo(db.getId(), table.getId(), physicalPartition.getId(),
                        index.getId(), tabletId, lastCheckTime,
                        checkedVersion, isConsistent);
            journalTask = GlobalStateMgr.getCurrentState().getEditLog().logFinishConsistencyCheckNoWait(info);
        }

        // Wait for edit log write finish out of db lock.
        EditLog.waitInfinity(journalTask);
        return 1;
    }

    private boolean isTimeout() {
        if (timeoutMs == 0 || System.currentTimeMillis() - createTime < timeoutMs) {
            return false;
        }
        return true;
    }

    public synchronized void handleFinishedReplica(long backendId, long checksum) {
        if (this.checksumMap.containsKey(backendId)) {
            checksumMap.put(backendId, checksum);
        } else {
            // should not happend. add log to observe
            LOG.warn("can not find backend[{}] in tablet[{}]'s consistency check job", backendId, tabletId);
        }
    }

    public synchronized void clear() {
        // clear task
        for (Long backendId : checksumMap.keySet()) {
            AgentTaskQueue.removeTask(backendId, TTaskType.CHECK_CONSISTENCY, tabletId);
        }
    }
}

