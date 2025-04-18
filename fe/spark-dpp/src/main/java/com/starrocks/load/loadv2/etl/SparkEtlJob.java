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

package com.starrocks.load.loadv2.etl;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.starrocks.common.SparkDppException;
import com.starrocks.load.loadv2.dpp.GlobalDictBuilder;
import com.starrocks.load.loadv2.dpp.SparkDpp;
import com.starrocks.load.loadv2.etl.EtlJobConfig.EtlColumnMapping;
import com.starrocks.load.loadv2.etl.EtlJobConfig.EtlFileGroup;
import com.starrocks.load.loadv2.etl.EtlJobConfig.EtlTable;
import org.apache.commons.collections.map.MultiValueMap;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.spark.SparkConf;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.functions;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * SparkEtlJob is responsible for global dict building, data partition, data sort and data aggregation.
 * 1. init job config
 * 2. check if job has bitmap_dict function columns
 * 3. build global dict if step 2 is true
 * 4. dpp (data partition, data sort and data aggregation)
 */
public class SparkEtlJob {
    private static final Logger LOG = LogManager.getLogger(SparkEtlJob.class);

    private static final String BITMAP_DICT_FUNC = "bitmap_dict";
    private static final String TO_BITMAP_FUNC = "to_bitmap";
    private static final String BITMAP_HASH = "bitmap_hash";
    private static final String BITMAP_HASH64 = "bitmap_hash64";
    private static final String BITMAP_FROM_BINARY = "bitmap_from_binary";

    private String jobConfigFilePath;
    private EtlJobConfig etlJobConfig;
    private Set<Long> hiveSourceTables;
    private Map<Long, Set<String>> tableToBitmapDictColumns;
    private Map<Long, Set<String>> tableToBitmapBinaryColumns;
    private SparkSession spark;

    private SparkEtlJob(String jobConfigFilePath) {
        this.jobConfigFilePath = jobConfigFilePath;
        this.etlJobConfig = null;
        this.hiveSourceTables = Sets.newHashSet();
        this.tableToBitmapDictColumns = Maps.newHashMap();
        this.tableToBitmapBinaryColumns = Maps.newHashMap();
    }

    private void initSparkEnvironment() {
        SparkConf conf = new SparkConf();
        //serialization conf
        conf.set("spark.serializer", "org.apache.spark.serializer.KryoSerializer");
        conf.set("spark.kryo.registrator", "com.starrocks.load.loadv2.dpp.StarRocksKryoRegistrator");
        conf.set("spark.kryo.registrationRequired", "false");
        spark = SparkSession.builder().enableHiveSupport().config(conf).getOrCreate();
    }

    private void initSparkConfigs(Map<String, String> configs) {
        if (configs == null) {
            return;
        }
        for (Map.Entry<String, String> entry : configs.entrySet()) {
            spark.sparkContext().conf().set(entry.getKey(), entry.getValue());
        }
    }

    private void initConfig() {
        LOG.info("job config file path: " + jobConfigFilePath);
        Dataset<String> ds = spark.read().textFile(jobConfigFilePath);
        String jsonConfig = ds.first();
        LOG.info("rdd read json config: " + jsonConfig);
        etlJobConfig = EtlJobConfig.configFromJson(jsonConfig);
        LOG.info("etl job config: " + etlJobConfig);
    }

    /*
     * 1. check bitmap column
     * 2. fill tableToBitmapDictColumns and tableToBitmapBinaryColumns
     * 3. remove bitmap_dict, bitmap_from_binary and to_bitmap mapping from columnMappings
     */
    private void checkConfig() throws Exception {
        for (Map.Entry<Long, EtlTable> entry : etlJobConfig.tables.entrySet()) {
            boolean isHiveSource = false;
            Set<String> bitmapDictColumns = Sets.newHashSet();
            Set<String> bitmapBinaryColumns = Sets.newHashSet();

            for (EtlFileGroup fileGroup : entry.getValue().fileGroups) {
                if (fileGroup.sourceType == EtlJobConfig.SourceType.HIVE) {
                    isHiveSource = true;
                }
                Map<String, EtlColumnMapping> newColumnMappings = Maps.newHashMap();
                for (Map.Entry<String, EtlColumnMapping> mappingEntry : fileGroup.columnMappings.entrySet()) {
                    String columnName = mappingEntry.getKey();
                    String exprStr = mappingEntry.getValue().toDescription();
                    String funcName = functions.expr(exprStr).expr().prettyName();
                    if (funcName.equalsIgnoreCase(BITMAP_HASH) || funcName.equalsIgnoreCase(BITMAP_HASH64)) {
                        throw new SparkDppException("spark load not support bitmap_hash or bitmap_hash64 now");
                    }
                    if (funcName.equalsIgnoreCase(BITMAP_DICT_FUNC)) {
                        bitmapDictColumns.add(columnName.toLowerCase());
                    } else if (funcName.equalsIgnoreCase(BITMAP_FROM_BINARY)) {
                        bitmapBinaryColumns.add(columnName.toLowerCase());
                    } else if (!funcName.equalsIgnoreCase(TO_BITMAP_FUNC)) {
                        newColumnMappings.put(mappingEntry.getKey(), mappingEntry.getValue());
                    }
                }
                // reset new columnMappings
                fileGroup.columnMappings = newColumnMappings;
            }
            if (isHiveSource) {
                hiveSourceTables.add(entry.getKey());
            }
            if (!bitmapDictColumns.isEmpty()) {
                tableToBitmapDictColumns.put(entry.getKey(), bitmapDictColumns);
            }
            if (!bitmapBinaryColumns.isEmpty()) {
                tableToBitmapBinaryColumns.put(entry.getKey(), bitmapBinaryColumns);
            }
        }
        LOG.info("init hiveSourceTables: " + hiveSourceTables + ", tableToBitmapDictColumns: " +
                tableToBitmapDictColumns);

        // spark etl must have only one table with bitmap type column to process.
        if (hiveSourceTables.size() > 1 || tableToBitmapDictColumns.size() > 1) {
            throw new Exception("spark etl job must have only one hive table with bitmap type column to process");
        }
    }

    private void processDpp() throws Exception {
        SparkDpp sparkDpp = new SparkDpp(spark, etlJobConfig, tableToBitmapDictColumns, tableToBitmapBinaryColumns);
        sparkDpp.init();
        sparkDpp.doDpp();
    }

    private String buildGlobalDictAndEncodeSourceTable(EtlTable table, long tableId) {
        // dict column map
        MultiValueMap dictColumnMap = new MultiValueMap();
        for (String dictColumn : tableToBitmapDictColumns.get(tableId)) {
            dictColumnMap.put(dictColumn, null);
        }

        // hive db and tables
        EtlFileGroup fileGroup = table.fileGroups.get(0);
        List<String> intermediateTableColumnList = fileGroup.fileFieldNames;
        String sourceHiveDBTableName = fileGroup.hiveDbTableName;
        String starrocksHiveDB = sourceHiveDBTableName.split("\\.")[0];
        String taskId = etlJobConfig.outputPath.substring(etlJobConfig.outputPath.lastIndexOf("/") + 1);
        String globalDictTableName = String.format(EtlJobConfig.GLOBAL_DICT_TABLE_NAME, tableId);
        String dorisGlobalDictTableName = String.format(EtlJobConfig.DORIS_GLOBAL_DICT_TABLE_NAME, tableId);
        String distinctKeyTableName = String.format(EtlJobConfig.DISTINCT_KEY_TABLE_NAME, tableId, taskId);
        String starrocksIntermediateHiveTable =
                String.format(EtlJobConfig.STARROCKS_INTERMEDIATE_HIVE_TABLE_NAME, tableId, taskId);
        String sourceHiveFilter = fileGroup.where;

        // others
        List<String> mapSideJoinColumns = Lists.newArrayList();
        int buildConcurrency = 1;
        List<String> veryHighCardinalityColumn = Lists.newArrayList();
        int veryHighCardinalityColumnSplitNum = 1;

        LOG.info("global dict builder args, dictColumnMap: " + dictColumnMap
                + ", intermediateTableColumnList: " + intermediateTableColumnList
                + ", sourceHiveDBTableName: " + sourceHiveDBTableName
                + ", sourceHiveFilter: " + sourceHiveFilter
                + ", distinctKeyTableName: " + distinctKeyTableName
                + ", globalDictTableName: " + globalDictTableName
                + ", starrocksIntermediateHiveTable: " + starrocksIntermediateHiveTable);
        try {
            GlobalDictBuilder globalDictBuilder = new GlobalDictBuilder(
                    dictColumnMap, intermediateTableColumnList, mapSideJoinColumns, sourceHiveDBTableName,
                    sourceHiveFilter, starrocksHiveDB, distinctKeyTableName, globalDictTableName,
                    starrocksIntermediateHiveTable,
                    buildConcurrency, veryHighCardinalityColumn, veryHighCardinalityColumnSplitNum, spark);
            globalDictBuilder.checkGlobalDictTableName(dorisGlobalDictTableName);
            globalDictBuilder.createHiveIntermediateTable();
            globalDictBuilder.extractDistinctColumn();
            globalDictBuilder.buildGlobalDict();
            globalDictBuilder.encodeStarRocksIntermediateHiveTable();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return String.format("%s.%s", starrocksHiveDB, starrocksIntermediateHiveTable);
    }

    private void processData() throws Exception {
        if (!hiveSourceTables.isEmpty()) {
            // only one table
            long tableId = -1;
            EtlTable table = null;
            Optional<Map.Entry<Long, EtlTable>> optionalEntry = etlJobConfig.tables.entrySet().stream().findFirst();
            if (optionalEntry.isPresent()) {
                Map.Entry<Long, EtlTable> entry = optionalEntry.get();
                tableId = entry.getKey();
                table = entry.getValue();
            }

            if (table == null) {
                throw new SparkDppException("invalid etl job config");
            }
            // init hive configs like metastore service
            EtlFileGroup fileGroup = table.fileGroups.get(0);
            initSparkConfigs(fileGroup.hiveTableProperties);
            fileGroup.dppHiveDbTableName = fileGroup.hiveDbTableName;

            // build global dict and encode source hive table if has bitmap dict columns
            if (!tableToBitmapDictColumns.isEmpty() && tableToBitmapDictColumns.containsKey(tableId)) {
                String starrocksIntermediateHiveDbTableName = buildGlobalDictAndEncodeSourceTable(table, tableId);
                // set with starrocksIntermediateHiveDbTable
                fileGroup.dppHiveDbTableName = starrocksIntermediateHiveDbTableName;
            }
        }

        // data partition sort and aggregation
        processDpp();
    }

    private void run() throws Exception {
        initSparkEnvironment();
        initConfig();
        checkConfig();
        processData();
    }

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("missing job config file path arg");
            System.exit(-1);
        }

        try {
            new SparkEtlJob(args[0]).run();
        } catch (Exception e) {
            String msg = "spark etl job run failed";
            System.err.println(msg);
            LOG.warn(msg, e);
            System.exit(-1);
        }
    }
}
