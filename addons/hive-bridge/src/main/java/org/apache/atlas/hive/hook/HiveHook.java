/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.atlas.hive.hook;

import org.apache.atlas.hive.hook.events.*;
import org.apache.atlas.hook.AtlasHook;
import org.apache.atlas.model.instance.AtlasEntity;
import org.apache.commons.lang.StringUtils;
import org.apache.hadoop.hive.ql.hooks.ExecuteWithHookContext;
import org.apache.hadoop.hive.ql.hooks.HookContext;
import org.apache.hadoop.hive.ql.plan.HiveOperation;
import org.apache.hadoop.hive.shims.Utils;
import org.apache.hadoop.security.UserGroupInformation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.apache.atlas.hive.hook.events.BaseHiveEvent.ATTRIBUTE_QUALIFIED_NAME;
import static org.apache.atlas.hive.hook.events.BaseHiveEvent.HIVE_TYPE_DB;
import static org.apache.atlas.hive.hook.events.BaseHiveEvent.HIVE_TYPE_TABLE;


public class HiveHook extends AtlasHook implements ExecuteWithHookContext {
    private static final Logger LOG = LoggerFactory.getLogger(HiveHook.class);

    public static final String CONF_PREFIX                         = "atlas.hook.hive.";
    public static final String CONF_CLUSTER_NAME                   = "atlas.cluster.name";
    public static final String HOOK_NAME_CACHE_ENABLED             = CONF_PREFIX + "name.cache.enabled";
    public static final String HOOK_NAME_CACHE_DATABASE_COUNT      = CONF_PREFIX + "name.cache.database.count";
    public static final String HOOK_NAME_CACHE_TABLE_COUNT         = CONF_PREFIX + "name.cache.table.count";
    public static final String HOOK_NAME_CACHE_REBUID_INTERVAL_SEC = CONF_PREFIX + "name.cache.rebuild.interval.seconds";
    public static final String HOOK_SKIP_HIVE_COLUMN_LINEAGE_HIVE_20633                  = CONF_PREFIX + "skip.hive_column_lineage.hive-20633";
    public static final String HOOK_SKIP_HIVE_COLUMN_LINEAGE_HIVE_20633_INPUTS_THRESHOLD = CONF_PREFIX + "skip.hive_column_lineage.hive-20633.inputs.threshold";

    public static final String DEFAULT_CLUSTER_NAME = "primary";

    private static final Map<String, HiveOperation> OPERATION_MAP = new HashMap<>();

    private static final String  clusterName;
    private static final boolean nameCacheEnabled;
    private static final int     nameCacheDatabaseMaxCount;
    private static final int     nameCacheTableMaxCount;
    private static final int     nameCacheRebuildIntervalSeconds;

    private static final boolean skipHiveColumnLineageHive20633;
    private static final int     skipHiveColumnLineageHive20633InputsThreshold;

    private static HiveHookObjectNamesCache knownObjects = null;

    static {
        for (HiveOperation hiveOperation : HiveOperation.values()) {
            OPERATION_MAP.put(hiveOperation.getOperationName(), hiveOperation);
        }

        clusterName                     = atlasProperties.getString(CONF_CLUSTER_NAME, DEFAULT_CLUSTER_NAME);
        nameCacheEnabled                = atlasProperties.getBoolean(HOOK_NAME_CACHE_ENABLED, true);
        nameCacheDatabaseMaxCount       = atlasProperties.getInt(HOOK_NAME_CACHE_DATABASE_COUNT, 10000);
        nameCacheTableMaxCount          = atlasProperties.getInt(HOOK_NAME_CACHE_TABLE_COUNT, 10000);
        nameCacheRebuildIntervalSeconds = atlasProperties.getInt(HOOK_NAME_CACHE_REBUID_INTERVAL_SEC, 60 * 60); // 60 minutes default
        skipHiveColumnLineageHive20633                = atlasProperties.getBoolean(HOOK_SKIP_HIVE_COLUMN_LINEAGE_HIVE_20633, false);
        skipHiveColumnLineageHive20633InputsThreshold = atlasProperties.getInt(HOOK_SKIP_HIVE_COLUMN_LINEAGE_HIVE_20633_INPUTS_THRESHOLD, 5); // skip greater-than 5 inputs by default

        knownObjects = nameCacheEnabled ? new HiveHookObjectNamesCache(nameCacheDatabaseMaxCount, nameCacheTableMaxCount, nameCacheRebuildIntervalSeconds) : null;
    }


    public HiveHook() {
    }

    @Override
    public void run(HookContext hookContext) throws Exception {
        if (LOG.isDebugEnabled()) {
            LOG.debug("==> HiveHook.run({})", hookContext.getOperationName());
        }

        if (knownObjects != null && knownObjects.isCacheExpired()) {
            LOG.info("HiveHook.run(): purging cached databaseNames ({}) and tableNames ({})", knownObjects.getCachedDbCount(), knownObjects.getCachedTableCount());

            knownObjects = new HiveHookObjectNamesCache(nameCacheDatabaseMaxCount, nameCacheTableMaxCount, nameCacheRebuildIntervalSeconds);
        }

        try {
            HiveOperation        oper    = OPERATION_MAP.get(hookContext.getOperationName());
            AtlasHiveHookContext context = new AtlasHiveHookContext(this, oper, hookContext, knownObjects);

            BaseHiveEvent event = null;

            switch (oper) {
                case CREATEDATABASE:
                    event = new CreateDatabase(context);
                break;

                case DROPDATABASE:
                    event = new DropDatabase(context);
                break;

                case ALTERDATABASE:
                case ALTERDATABASE_OWNER:
                    event = new AlterDatabase(context);
                break;

                case CREATETABLE:
                    event = new CreateTable(context, true);
                break;

                case DROPTABLE:
                case DROPVIEW:
                    event = new DropTable(context);
                break;

                case CREATETABLE_AS_SELECT:
                case CREATEVIEW:
                case ALTERVIEW_AS:
                case LOAD:
                case EXPORT:
                case IMPORT:
                case QUERY:
                case TRUNCATETABLE:
                    event = new CreateHiveProcess(context);
                break;

                case ALTERTABLE_FILEFORMAT:
                case ALTERTABLE_CLUSTER_SORT:
                case ALTERTABLE_BUCKETNUM:
                case ALTERTABLE_PROPERTIES:
                case ALTERVIEW_PROPERTIES:
                case ALTERTABLE_SERDEPROPERTIES:
                case ALTERTABLE_SERIALIZER:
                case ALTERTABLE_ADDCOLS:
                case ALTERTABLE_REPLACECOLS:
                case ALTERTABLE_PARTCOLTYPE:
                case ALTERTABLE_LOCATION:
                    event = new AlterTable(context);
                break;

                case ALTERTABLE_RENAME:
                case ALTERVIEW_RENAME:
                    event = new AlterTableRename(context);
                break;

                case ALTERTABLE_RENAMECOL:
                    event = new AlterTableRenameCol(context);
                break;

                default:
                    if (LOG.isDebugEnabled()) {
                        LOG.debug("HiveHook.run({}): operation ignored", hookContext.getOperationName());
                    }
                break;
            }

            if (event != null) {
                final UserGroupInformation ugi = hookContext.getUgi() == null ? Utils.getUGI() : hookContext.getUgi();

                super.notifyEntities(event.getNotificationMessages(), ugi);
            }
        } catch (Throwable t) {
            LOG.error("HiveHook.run(): failed to process operation {}", hookContext.getOperationName(), t);
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("<== HiveHook.run({})", hookContext.getOperationName());
        }
    }

    public String getClusterName() {
        return clusterName;
    }

    public boolean getSkipHiveColumnLineageHive20633() {
        return skipHiveColumnLineageHive20633;
    }

    public int getSkipHiveColumnLineageHive20633InputsThreshold() {
        return skipHiveColumnLineageHive20633InputsThreshold;
    }


    public static class HiveHookObjectNamesCache {
        private final int         dbMaxCacheCount;
        private final int         tblMaxCacheCount;
        private final long        cacheExpiryTimeMs;
        private final Set<String> knownDatabases;
        private final Set<String> knownTables;

        public HiveHookObjectNamesCache(int dbMaxCacheCount, int tblMaxCacheCount, long nameCacheRebuildIntervalSeconds) {
            this.dbMaxCacheCount   = dbMaxCacheCount;
            this.tblMaxCacheCount  = tblMaxCacheCount;
            this.cacheExpiryTimeMs = nameCacheRebuildIntervalSeconds <= 0 ? Long.MAX_VALUE : (System.currentTimeMillis() + (nameCacheRebuildIntervalSeconds * 1000));
            this.knownDatabases    = Collections.synchronizedSet(new HashSet<>());
            this.knownTables       = Collections.synchronizedSet(new HashSet<>());
        }

        public int getCachedDbCount() {
            return knownDatabases.size();
        }

        public int getCachedTableCount() {
            return knownTables.size();
        }

        public boolean isCacheExpired() {
            return System.currentTimeMillis() > cacheExpiryTimeMs;
        }

        public boolean isKnownDatabase(String dbQualifiedName) {
            return knownDatabases.contains(dbQualifiedName);
        }

        public boolean isKnownTable(String tblQualifiedName) {
            return knownTables.contains(tblQualifiedName);
        }

        public void addToKnownEntities(Collection<AtlasEntity> entities) {
            for (AtlasEntity entity : entities) {
                if (StringUtils.equalsIgnoreCase(entity.getTypeName(), HIVE_TYPE_DB)) {
                    addToKnownDatabase((String) entity.getAttribute(ATTRIBUTE_QUALIFIED_NAME));
                } else if (StringUtils.equalsIgnoreCase(entity.getTypeName(), HIVE_TYPE_TABLE)) {
                    addToKnownTable((String) entity.getAttribute(ATTRIBUTE_QUALIFIED_NAME));
                }
            }
        }

        public void addToKnownDatabase(String dbQualifiedName) {
            if (knownDatabases.size() < dbMaxCacheCount) {
                knownDatabases.add(dbQualifiedName);
            }
        }

        public void addToKnownTable(String tblQualifiedName) {
            if (knownTables.size() < tblMaxCacheCount) {
                knownTables.add(tblQualifiedName);
            }
        }

        public void removeFromKnownDatabase(String dbQualifiedName) {
            knownDatabases.remove(dbQualifiedName);
        }

        public void removeFromKnownTable(String tblQualifiedName) {
            if (tblQualifiedName != null) {
                knownTables.remove(tblQualifiedName);
            }
        }
    }
}
