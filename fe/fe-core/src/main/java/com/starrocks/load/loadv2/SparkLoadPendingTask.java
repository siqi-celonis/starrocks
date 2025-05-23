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
//   https://github.com/apache/incubator-doris/blob/master/fe/fe-core/src/main/java/org/apache/doris/load/loadv2/SparkLoadPendingTask.java

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

package com.starrocks.load.loadv2;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Range;
import com.google.common.collect.Sets;
import com.starrocks.analysis.BrokerDesc;
import com.starrocks.analysis.Expr;
import com.starrocks.analysis.FunctionCallExpr;
import com.starrocks.analysis.LiteralExpr;
import com.starrocks.analysis.SlotRef;
import com.starrocks.catalog.AggregateType;
import com.starrocks.catalog.Column;
import com.starrocks.catalog.Database;
import com.starrocks.catalog.DistributionInfo;
import com.starrocks.catalog.DistributionInfo.DistributionInfoType;
import com.starrocks.catalog.FunctionSet;
import com.starrocks.catalog.HiveTable;
import com.starrocks.catalog.KeysType;
import com.starrocks.catalog.ListPartitionInfo;
import com.starrocks.catalog.OlapTable;
import com.starrocks.catalog.Partition;
import com.starrocks.catalog.PartitionKey;
import com.starrocks.catalog.PartitionType;
import com.starrocks.catalog.PhysicalPartition;
import com.starrocks.catalog.PrimitiveType;
import com.starrocks.catalog.RangePartitionInfo;
import com.starrocks.catalog.SparkResource;
import com.starrocks.common.AnalysisException;
import com.starrocks.common.LoadException;
import com.starrocks.common.Pair;
import com.starrocks.common.StarRocksException;
import com.starrocks.common.util.LoadPriority;
import com.starrocks.common.util.concurrent.lock.LockType;
import com.starrocks.common.util.concurrent.lock.Locker;
import com.starrocks.load.BrokerFileGroup;
import com.starrocks.load.BrokerFileGroupAggInfo.FileGroupAggKey;
import com.starrocks.load.FailMsg;
import com.starrocks.load.Load;
import com.starrocks.load.PartitionUtils;
import com.starrocks.load.PartitionUtils.RangePartitionBoundary;
import com.starrocks.load.loadv2.etl.EtlJobConfig;
import com.starrocks.load.loadv2.etl.EtlJobConfig.EtlColumn;
import com.starrocks.load.loadv2.etl.EtlJobConfig.EtlColumnMapping;
import com.starrocks.load.loadv2.etl.EtlJobConfig.EtlFileGroup;
import com.starrocks.load.loadv2.etl.EtlJobConfig.EtlIndex;
import com.starrocks.load.loadv2.etl.EtlJobConfig.EtlJobProperty;
import com.starrocks.load.loadv2.etl.EtlJobConfig.EtlPartition;
import com.starrocks.load.loadv2.etl.EtlJobConfig.EtlPartitionInfo;
import com.starrocks.load.loadv2.etl.EtlJobConfig.EtlTable;
import com.starrocks.load.loadv2.etl.EtlJobConfig.FilePatternVersion;
import com.starrocks.load.loadv2.etl.EtlJobConfig.SourceType;
import com.starrocks.server.GlobalStateMgr;
import com.starrocks.sql.analyzer.SemanticException;
import com.starrocks.sql.ast.ImportColumnDesc;
import com.starrocks.sql.common.MetaUtils;
import com.starrocks.transaction.TransactionState;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// 1. create etl job config and write it into jobconfig.json file
// 2. submit spark etl job
public class SparkLoadPendingTask extends LoadTask {
    private static final Logger LOG = LogManager.getLogger(SparkLoadPendingTask.class);

    private final Map<FileGroupAggKey, List<BrokerFileGroup>> aggKeyToBrokerFileGroups;
    private final SparkResource resource;
    private final BrokerDesc brokerDesc;
    private final long dbId;
    private final String loadLabel;
    private final long loadJobId;
    private final long transactionId;
    private EtlJobConfig etlJobConfig;
    private SparkLoadAppHandle sparkLoadAppHandle;

    public SparkLoadPendingTask(SparkLoadJob loadTaskCallback,
                                Map<FileGroupAggKey, List<BrokerFileGroup>> aggKeyToBrokerFileGroups,
                                SparkResource resource, BrokerDesc brokerDesc) {
        super(loadTaskCallback, TaskType.PENDING, LoadPriority.NORMAL_VALUE);
        this.attachment = new SparkPendingTaskAttachment(signature);
        this.aggKeyToBrokerFileGroups = aggKeyToBrokerFileGroups;
        this.resource = resource;
        this.brokerDesc = brokerDesc;
        this.dbId = loadTaskCallback.getDbId();
        this.loadJobId = loadTaskCallback.getId();
        this.loadLabel = loadTaskCallback.getLabel();
        this.transactionId = loadTaskCallback.getTransactionId();
        this.sparkLoadAppHandle = loadTaskCallback.getHandle();
        this.failMsg = new FailMsg(FailMsg.CancelType.ETL_SUBMIT_FAIL);
    }

    @Override
    void executeTask() throws LoadException {
        LOG.info("begin to execute spark pending task. load job id: {}", loadJobId);
        submitEtlJob();
    }

    private void submitEtlJob() throws LoadException {
        SparkPendingTaskAttachment sparkAttachment = (SparkPendingTaskAttachment) attachment;
        // retry different output path
        etlJobConfig.outputPath = EtlJobConfig.getOutputPath(resource.getWorkingDir(), dbId, loadLabel, signature);
        sparkAttachment.setOutputPath(etlJobConfig.outputPath);

        // handler submit etl job
        SparkEtlJobHandler handler = new SparkEtlJobHandler();
        Long sparkLoadSubmitTimeout = ((SparkLoadJob) callback).sparkLoadSubmitTimeoutSecond;
        handler.submitEtlJob(loadJobId, loadLabel, etlJobConfig, resource, brokerDesc, sparkLoadAppHandle,
                sparkAttachment, sparkLoadSubmitTimeout);
        LOG.info("submit spark etl job success. load job id: {}, attachment: {}", loadJobId, sparkAttachment);
    }

    @Override
    public void init() throws LoadException {
        createEtlJobConf();
    }

    private void createEtlJobConf() throws LoadException {
        Database db = GlobalStateMgr.getCurrentState().getLocalMetastore().getDb(dbId);
        if (db == null) {
            throw new LoadException("db does not exist. id: " + dbId);
        }

        Map<Long, EtlTable> tables = Maps.newHashMap();
        Locker locker = new Locker();
        locker.lockDatabase(db.getId(), LockType.READ);
        try {
            Map<Long, Set<Long>> tableIdToPartitionIds = Maps.newHashMap();
            Set<Long> allPartitionsTableIds = Sets.newHashSet();
            prepareTablePartitionInfos(db, tableIdToPartitionIds, allPartitionsTableIds);

            for (Map.Entry<FileGroupAggKey, List<BrokerFileGroup>> entry : aggKeyToBrokerFileGroups.entrySet()) {
                FileGroupAggKey aggKey = entry.getKey();
                long tableId = aggKey.getTableId();

                OlapTable table = (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getId(), tableId);
                if (table == null) {
                    throw new LoadException("table does not exist. id: " + tableId);
                }

                EtlTable etlTable = null;
                if (tables.containsKey(tableId)) {
                    etlTable = tables.get(tableId);
                } else {
                    // indexes
                    List<EtlIndex> etlIndexes = createEtlIndexes(table);
                    // partition info
                    EtlPartitionInfo etlPartitionInfo = createEtlPartitionInfo(table,
                            tableIdToPartitionIds.get(tableId));
                    etlTable = new EtlTable(etlIndexes, etlPartitionInfo);
                    tables.put(tableId, etlTable);

                    // add table indexes to transaction state
                    TransactionState txnState = GlobalStateMgr.getCurrentState().getGlobalTransactionMgr()
                            .getTransactionState(dbId, transactionId);
                    if (txnState == null) {
                        throw new LoadException("txn does not exist. id: " + transactionId);
                    }
                    txnState.addTableIndexes(table);
                }

                // file group
                for (BrokerFileGroup fileGroup : entry.getValue()) {
                    etlTable.addFileGroup(createEtlFileGroup(fileGroup, tableIdToPartitionIds.get(tableId), db, table));
                }
            }
        } finally {
            locker.unLockDatabase(db.getId(), LockType.READ);
        }

        String outputFilePattern = EtlJobConfig.getOutputFilePattern(loadLabel, FilePatternVersion.V1);
        // strictMode timezone properties
        EtlJobProperty properties = new EtlJobProperty();
        properties.strictMode = ((LoadJob) callback).strictMode;
        properties.timezone = ((LoadJob) callback).timezone;
        etlJobConfig = new EtlJobConfig(tables, outputFilePattern, loadLabel, properties);
    }

    private void prepareTablePartitionInfos(Database db, Map<Long, Set<Long>> tableIdToPartitionIds,
                                            Set<Long> allPartitionsTableIds) throws LoadException {
        for (FileGroupAggKey aggKey : aggKeyToBrokerFileGroups.keySet()) {
            long tableId = aggKey.getTableId();
            if (allPartitionsTableIds.contains(tableId)) {
                continue;
            }

            OlapTable table = (OlapTable) GlobalStateMgr.getCurrentState().getLocalMetastore().getTable(db.getId(), tableId);
            if (table == null) {
                throw new LoadException("table does not exist. id: " + tableId);
            }

            Set<Long> partitionIds = null;
            if (tableIdToPartitionIds.containsKey(tableId)) {
                partitionIds = tableIdToPartitionIds.get(tableId);
            } else {
                partitionIds = Sets.newHashSet();
                tableIdToPartitionIds.put(tableId, partitionIds);
            }

            Set<Long> groupPartitionIds = aggKey.getPartitionIds();
            // if not assign partition, use all partitions
            if (groupPartitionIds == null || groupPartitionIds.isEmpty()) {
                for (Partition partition : table.getPartitions()) {
                    partitionIds.add(partition.getId());
                }

                allPartitionsTableIds.add(tableId);
            } else {
                partitionIds.addAll(groupPartitionIds);
            }
        }
    }

    private List<EtlIndex> createEtlIndexes(OlapTable table) throws LoadException {
        List<EtlIndex> etlIndexes = Lists.newArrayList();

        for (Map.Entry<Long, List<Column>> entry : table.getIndexIdToSchema().entrySet()) {
            long indexId = entry.getKey();
            int schemaHash = table.getSchemaHashByIndexId(indexId);

            // columns
            List<EtlColumn> etlColumns = Lists.newArrayList();
            for (Column column : entry.getValue()) {
                etlColumns.add(createEtlColumn(column));
            }

            // check distribution type
            DistributionInfo distributionInfo = table.getDefaultDistributionInfo();
            if (distributionInfo.getType() != DistributionInfoType.HASH) {
                // RANDOM not supported
                String errMsg = "Unsupported distribution type. type: " + distributionInfo.getType().name();
                LOG.warn(errMsg);
                throw new LoadException(errMsg);
            }

            // index type
            String indexType = null;
            KeysType keysType = table.getKeysTypeByIndexId(indexId);
            switch (keysType) {
                case DUP_KEYS:
                    indexType = "DUPLICATE";
                    break;
                case AGG_KEYS:
                    indexType = "AGGREGATE";
                    break;
                case UNIQUE_KEYS:
                    indexType = "UNIQUE";
                    break;
                default:
                    String errMsg = "unknown keys type. type: " + keysType.name();
                    LOG.warn(errMsg);
                    throw new LoadException(errMsg);
            }

            // is base index
            boolean isBaseIndex = indexId == table.getBaseIndexId() ? true : false;

            etlIndexes.add(new EtlIndex(indexId, etlColumns, schemaHash, indexType, isBaseIndex));
        }

        return etlIndexes;
    }

    private EtlColumn createEtlColumn(Column column) {
        // column name
        String name = column.getName();
        // column type
        PrimitiveType type = column.getPrimitiveType();
        String columnType = column.getPrimitiveType().toString();
        // is allow null
        boolean isAllowNull = column.isAllowNull();
        // is key
        boolean isKey = column.isKey();

        // aggregation type
        String aggregationType = null;
        if (column.getAggregationType() != null) {
            aggregationType = column.getAggregationType().toString();
        }

        // default value
        String defaultValue = null;
        Column.DefaultValueType defaultValueType = column.getDefaultValueType();
        if (defaultValueType == Column.DefaultValueType.VARY) {
            throw new SemanticException("Column " + column.getName() + " has unsupported default value:" +
                    column.getDefaultExpr().getExpr());
        }
        if (defaultValueType == Column.DefaultValueType.CONST) {
            defaultValue = column.calculatedDefaultValue();
        }
        if (column.isAllowNull() && defaultValueType == Column.DefaultValueType.NULL) {
            defaultValue = "\\N";
        }

        // string length
        int stringLength = 0;
        if (type.isStringType()) {
            stringLength = column.getStrLen();
        }

        // decimal precision scale
        int precision = 0;
        int scale = 0;
        if (type.isDecimalOfAnyVersion()) {
            precision = column.getPrecision();
            scale = column.getScale();
        }

        return new EtlColumn(name, columnType, isAllowNull, isKey, aggregationType, defaultValue,
                stringLength, precision, scale);
    }

    private EtlPartitionInfo createEtlPartitionInfo(OlapTable table, Set<Long> partitionIds) throws LoadException {
        PartitionType type = table.getPartitionInfo().getType();

        List<String> partitionColumnRefs = Lists.newArrayList();
        List<EtlPartition> etlPartitions = Lists.newArrayList();
        switch (type) {
            case RANGE:
                etlPartitions = initEtlRangePartition(partitionColumnRefs, table, partitionIds);
                break;
            case LIST:
                etlPartitions = initEtlListPartition(partitionColumnRefs, table, partitionIds);
                break;
            case UNPARTITIONED:
                etlPartitions = initEtlUnPartitioned(table, partitionIds);
                break;
        }

        // distribution column refs
        DistributionInfo distributionInfo = table.getDefaultDistributionInfo();
        Preconditions.checkState(distributionInfo.getType() == DistributionInfoType.HASH);
        List<String> distributionColumnRefs = MetaUtils.getColumnNamesByColumnIds(
                table.getIdToColumn(), distributionInfo.getDistributionColumns());

        return new EtlPartitionInfo(type.typeString, partitionColumnRefs, distributionColumnRefs, etlPartitions);
    }

    private List<EtlPartition> initEtlListPartition(
            List<String> partitionColumnRefs, OlapTable table, Set<Long> partitionIds) throws LoadException {
        ListPartitionInfo listPartitionInfo = (ListPartitionInfo) table.getPartitionInfo();
        partitionColumnRefs.addAll(table.getPartitionInfo().getPartitionColumns(table.getIdToColumn()).
                stream().map(Column::getName).collect(Collectors.toList()));
        List<EtlPartition> etlPartitions = Lists.newArrayList();
        Map<Long, List<List<LiteralExpr>>> multiLiteralExprValues = listPartitionInfo.getMultiLiteralExprValues();
        Map<Long, List<LiteralExpr>> literalExprValues = listPartitionInfo.getLiteralExprValues();
        for (Long partitionId : partitionIds) {
            Partition partition = table.getPartition(partitionId);
            if (partition == null) {
                throw new LoadException("partition does not exist. id: " + partitionId);
            }
            // bucket num
            int bucketNum = partition.getDistributionInfo().getBucketNum();
            // list partition values
            List<List<Object>> inKeys = PartitionUtils.calListPartitionKeys(
                    multiLiteralExprValues.get(partitionId),
                    literalExprValues.get(partitionId)
            );

            for (PhysicalPartition physicalPartition : partition.getSubPartitions()) {
                long physicalPartitionId = physicalPartition.getId();
                etlPartitions.add(new EtlPartition(physicalPartitionId, inKeys, bucketNum));
            }
        }
        return etlPartitions;
    }

    private List<EtlPartition> initEtlRangePartition(
            List<String> partitionColumnRefs, OlapTable table, Set<Long> partitionIds) throws LoadException {
        RangePartitionInfo rangePartitionInfo = (RangePartitionInfo) table.getPartitionInfo();
        List<EtlPartition> etlPartitions = Lists.newArrayList();
        partitionColumnRefs.addAll(table.getPartitionInfo().getPartitionColumns(table.getIdToColumn())
                .stream().map(Column::getName).collect(Collectors.toList()));

        List<Map.Entry<Long, Range<PartitionKey>>> sortedRanges = null;
        try {
            sortedRanges = rangePartitionInfo.getSortedRangeMap(partitionIds);
        } catch (AnalysisException e) {
            throw new LoadException(e.getMessage());
        }
        for (Map.Entry<Long, Range<PartitionKey>> entry : sortedRanges) {
            long partitionId = entry.getKey();
            Partition partition = table.getPartition(partitionId);
            if (partition == null) {
                throw new LoadException("partition does not exist. id: " + partitionId);
            }

            // bucket num
            int bucketNum = partition.getDistributionInfo().getBucketNum();

            RangePartitionBoundary boundary = PartitionUtils.calRangePartitionBoundary(entry.getValue());

            for (PhysicalPartition physicalPartition : partition.getSubPartitions()) {
                long physicalPartitionId = physicalPartition.getId();

                etlPartitions.add(new EtlPartition(
                        physicalPartitionId,
                        boundary.getStartKeys(),
                        boundary.getEndKeys(),
                        boundary.isMinPartition(),
                        boundary.isMaxPartition(),
                        bucketNum));
            }
        }
        return etlPartitions;
    }

    private List<EtlPartition> initEtlUnPartitioned(OlapTable table, Set<Long> partitionIds) throws LoadException {
        PartitionType type = table.getPartitionInfo().getType();
        List<EtlPartition> etlPartitions = Lists.newArrayList();
        Preconditions.checkState(type == PartitionType.UNPARTITIONED);
        Preconditions.checkState(partitionIds.size() == 1);

        for (Long partitionId : partitionIds) {
            Partition partition = table.getPartition(partitionId);
            if (partition == null) {
                throw new LoadException("partition does not exist. id: " + partitionId);
            }

            // bucket num
            int bucketNum = partition.getDistributionInfo().getBucketNum();

            for (PhysicalPartition physicalPartition : partition.getSubPartitions()) {
                long physicalPartitionId = physicalPartition.getId();
                etlPartitions.add(new EtlPartition(physicalPartitionId, Lists.newArrayList(), Lists.newArrayList(),
                        true, true, bucketNum));
            }
        }
        return etlPartitions;
    }

    private EtlFileGroup createEtlFileGroup(BrokerFileGroup fileGroup, Set<Long> tablePartitionIds,
                                            Database db, OlapTable table) throws LoadException {
        List<ImportColumnDesc> copiedColumnExprList = Lists.newArrayList(fileGroup.getColumnExprList());
        Map<String, Expr> exprByName = Maps.newTreeMap(String.CASE_INSENSITIVE_ORDER);
        for (ImportColumnDesc columnDesc : copiedColumnExprList) {
            if (!columnDesc.isColumn()) {
                exprByName.put(columnDesc.getColumnName(), columnDesc.getExpr());
            }
        }

        // check columns
        try {
            Load.initColumns(table, copiedColumnExprList, fileGroup.getColumnToHadoopFunction());
        } catch (StarRocksException e) {
            throw new LoadException(e.getMessage());
        }
        // add generated column mapping
        for (ImportColumnDesc generatedColumnDesc : Load.getMaterializedShadowColumnDesc(table, db.getFullName(), false)) {
            Expr expr = generatedColumnDesc.getExpr();
            List<SlotRef> slots = Lists.newArrayList();
            expr.collect(SlotRef.class, slots);
            for (SlotRef slot : slots) {
                for (ImportColumnDesc columnDesc : copiedColumnExprList) {
                    if (!columnDesc.isColumn() && slot.getColumnName().equals(columnDesc.getColumnName())) {
                        throw new LoadException("generated column can not refenece the column which is the " +
                                "result of the expression in spark load");
                    }
                }
            }
            copiedColumnExprList.add(generatedColumnDesc);
            exprByName.put(generatedColumnDesc.getColumnName(), generatedColumnDesc.getExpr());
        }
        // add shadow column mapping when schema change
        for (ImportColumnDesc columnDesc : Load.getSchemaChangeShadowColumnDesc(table, exprByName)) {
            copiedColumnExprList.add(columnDesc);
            exprByName.put(columnDesc.getColumnName(), columnDesc.getExpr());
        }

        // check negative for sum aggregate type
        if (fileGroup.isNegative()) {
            for (Column column : table.getBaseSchema()) {
                if (!column.isKey() && column.getAggregationType() != AggregateType.SUM) {
                    throw new LoadException("Column is not SUM AggreateType. column:" + column.getName());
                }
            }
        }

        // fill file field names if empty
        List<String> fileFieldNames = fileGroup.getFileFieldNames();
        if (fileFieldNames == null || fileFieldNames.isEmpty()) {
            fileFieldNames = Lists.newArrayList();
            for (Column column : table.getBaseSchema()) {
                fileFieldNames.add(column.getName());
            }
        }

        // column mappings
        Map<String, Pair<String, List<String>>> columnToHadoopFunction = fileGroup.getColumnToHadoopFunction();
        Map<String, EtlColumnMapping> columnMappings = Maps.newHashMap();
        if (columnToHadoopFunction != null) {
            for (Map.Entry<String, Pair<String, List<String>>> entry : columnToHadoopFunction.entrySet()) {
                columnMappings.put(entry.getKey(),
                        new EtlColumnMapping(entry.getValue().first, entry.getValue().second));
            }
        }
        for (ImportColumnDesc columnDesc : copiedColumnExprList) {
            if (columnDesc.isColumn() || columnMappings.containsKey(columnDesc.getColumnName())) {
                continue;
            }
            // the left must be column expr
            columnMappings.put(columnDesc.getColumnName(), new EtlColumnMapping(columnDesc.getExpr().toSql()));
        }

        // partition ids
        List<Long> partitionIds = fileGroup.getPartitionIds();
        if (partitionIds == null || partitionIds.isEmpty()) {
            partitionIds = Lists.newArrayList(tablePartitionIds);
        }

        List<Long> physicalPartitionIds = Lists.newArrayList();
        for (Long partitionId : partitionIds) {
            Partition partition = table.getPartition(partitionId);
            for (PhysicalPartition physicalPartition : partition.getSubPartitions()) {
                physicalPartitionIds.add(physicalPartition.getId());
            }
        }

        // where
        // TODO: check
        String where = "";
        if (fileGroup.getWhereExpr() != null) {
            where = fileGroup.getWhereExpr().toSql();
        }

        // load from table
        String hiveDbTableName = "";
        Map<String, String> hiveTableProperties = Maps.newHashMap();
        if (fileGroup.isLoadFromTable()) {
            long srcTableId = fileGroup.getSrcTableId();
            HiveTable srcHiveTable = (HiveTable) GlobalStateMgr.getCurrentState().getLocalMetastore()
                        .getTable(db.getId(), srcTableId);
            if (srcHiveTable == null) {
                throw new LoadException("table does not exist. id: " + srcTableId);
            }
            hiveDbTableName = srcHiveTable.getHiveDbTable();
            hiveTableProperties.putAll(srcHiveTable.getProperties());
        }

        // check hll and bitmap func
        // TODO: more check
        for (Column column : table.getBaseSchema()) {
            String columnName = column.getName();
            PrimitiveType columnType = column.getPrimitiveType();
            Expr expr = exprByName.get(columnName);
            if (columnType == PrimitiveType.HLL) {
                checkHllMapping(columnName, expr);
            }
            if (columnType == PrimitiveType.BITMAP) {
                checkBitmapMapping(columnName, expr, fileGroup.isLoadFromTable());
            }
        }

        EtlFileGroup etlFileGroup = null;
        if (fileGroup.isLoadFromTable()) {
            etlFileGroup = new EtlFileGroup(SourceType.HIVE, fileFieldNames, hiveDbTableName, hiveTableProperties,
                    fileGroup.isNegative(), columnMappings, where, physicalPartitionIds);
        } else {
            etlFileGroup = new EtlFileGroup(SourceType.FILE, fileGroup.getFilePaths(), fileFieldNames,
                    fileGroup.getColumnsFromPath(), fileGroup.getColumnSeparator(),
                    fileGroup.getRowDelimiter(), fileGroup.isNegative(),
                    fileGroup.getFileFormat(), columnMappings,
                    where, physicalPartitionIds);
        }

        return etlFileGroup;
    }

    private void checkHllMapping(String columnName, Expr expr) throws LoadException {
        if (expr == null) {
            throw new LoadException("HLL column func is not assigned. column:" + columnName);
        }

        String msg = "HLL column must use hll function, like " + columnName + "=hll_hash(xxx) or "
                + columnName + "=hll_empty()";
        if (!(expr instanceof FunctionCallExpr)) {
            throw new LoadException(msg);
        }
        FunctionCallExpr fn = (FunctionCallExpr) expr;
        String functionName = fn.getFnName().getFunction();
        if (!functionName.equalsIgnoreCase(FunctionSet.HLL_HASH)
                && !functionName.equalsIgnoreCase("hll_empty")) {
            throw new LoadException(msg);
        }
    }

    @VisibleForTesting
    void checkBitmapMapping(String columnName, Expr expr, boolean isLoadFromTable) throws LoadException {
        if (expr == null) {
            throw new LoadException("BITMAP column func is not assigned. column:" + columnName);
        }

        String msg = "BITMAP column must use bitmap function, like " + columnName + "=to_bitmap(xxx) or "
                + columnName + "=bitmap_hash() or " + columnName + "=bitmap_dict() or "
                + columnName + "=bitmap_from_binary()";
        if (!(expr instanceof FunctionCallExpr)) {
            throw new LoadException(msg);
        }
        FunctionCallExpr fn = (FunctionCallExpr) expr;
        String functionName = fn.getFnName().getFunction();
        if (!functionName.equalsIgnoreCase(FunctionSet.TO_BITMAP)
                && !functionName.equalsIgnoreCase(FunctionSet.BITMAP_HASH)
                && !functionName.equalsIgnoreCase(FunctionSet.BITMAP_HASH64)
                && !functionName.equalsIgnoreCase(FunctionSet.BITMAP_DICT)
                && !functionName.equalsIgnoreCase(FunctionSet.BITMAP_FROM_BINARY)) {
            throw new LoadException(msg);
        }

        if (functionName.equalsIgnoreCase("bitmap_dict") && !isLoadFromTable) {
            throw new LoadException("Bitmap global dict should load data from hive table");
        }
    }

}
