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

package com.starrocks.common;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.message.Message;

public class AuditLog {

    public static final AuditLog SLOW_AUDIT = new AuditLog("audit.slow_query");
    public static final AuditLog QUERY_AUDIT = new AuditLog("audit.query");
    public static final AuditLog BIG_QUERY_AUDIT = new AuditLog("big_query.query");
    public static final AuditLog CONNECTION_AUDIT = new AuditLog("audit.connection");
    public static final AuditLog STATISTIC_AUDIT = new AuditLog("internal.statistic");
    public static final AuditLog INTERNAL_AUDIT = new AuditLog("internal.base");
    public static final AuditLog FEATURES_AUDIT = new AuditLog("features");

    private final Logger logger;

    public static AuditLog getQueryAudit() {
        return QUERY_AUDIT;
    }

    public static AuditLog getSlowAudit() {
        return SLOW_AUDIT;
    }

    public static AuditLog getBigQueryAudit() {
        return BIG_QUERY_AUDIT;
    }

    public static AuditLog getConnectionAudit() {
        return CONNECTION_AUDIT;
    }

    public static Logger getStatisticAudit() {
        return STATISTIC_AUDIT.logger;
    }

    public static Logger getInternalAudit() {
        return INTERNAL_AUDIT.logger;
    }

    public static Logger getFeaturesAudit() {
        return FEATURES_AUDIT.logger;
    }

    public AuditLog(String auditName) {
        logger = LogManager.getLogger(auditName);
    }

    public void log(Object message) {
        logger.info(message);
    }

    public void log(String message) {
        logger.info(message);
    }

    public void log(String message, Object... params) {
        logger.info(message, params);
    }

    public void log(Message message) {
        logger.info(message);
    }

}
