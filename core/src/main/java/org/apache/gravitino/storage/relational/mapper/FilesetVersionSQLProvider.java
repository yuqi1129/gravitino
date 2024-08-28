package org.apache.gravitino.storage.relational.mapper;
/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import static org.apache.gravitino.storage.relational.mapper.FilesetVersionMapper.VERSION_TABLE_NAME;

import com.google.common.collect.ImmutableMap;
import java.util.Map;
import org.apache.gravitino.storage.relational.JDBCBackend.JDBCBackendType;
import org.apache.gravitino.storage.relational.po.FilesetVersionPO;
import org.apache.gravitino.storage.relational.session.SqlSessionFactoryHelper;
import org.apache.ibatis.annotations.Param;

public class FilesetVersionSQLProvider {
  private static final Map<JDBCBackendType, FilesetVersionBaseProvider>
      METALAKE_META_SQL_PROVIDER_MAP =
          ImmutableMap.of(
              JDBCBackendType.MYSQL, new FilesetVersionMySQLProvider(),
              JDBCBackendType.H2, new FilesetVersionH2Provider(),
              JDBCBackendType.PG, new FilesetVersionPostgreSQLProvider());

  public static FilesetVersionBaseProvider getProvider() {
    String databaseId =
        SqlSessionFactoryHelper.getInstance()
            .getSqlSessionFactory()
            .getConfiguration()
            .getDatabaseId();

    JDBCBackendType jdbcBackendType = JDBCBackendType.fromString(databaseId);
    return METALAKE_META_SQL_PROVIDER_MAP.get(jdbcBackendType);
  }

  static class FilesetVersionMySQLProvider extends FilesetVersionBaseProvider {}

  static class FilesetVersionH2Provider extends FilesetVersionBaseProvider {}

  static class FilesetVersionPostgreSQLProvider extends FilesetVersionBaseProvider {

    @Override
    public String softDeleteFilesetVersionsByMetalakeId(Long metalakeId) {
      return "UPDATE "
          + VERSION_TABLE_NAME
          + " SET deleted_at = floor(extract(epoch from((current_timestamp - timestamp '1970-01-01 00:00:00')*1000)))"
          + " WHERE metalake_id = #{metalakeId} AND deleted_at = 0";
    }

    @Override
    public String softDeleteFilesetVersionsByCatalogId(Long catalogId) {
      return "UPDATE "
          + VERSION_TABLE_NAME
          + " SET deleted_at = floor(extract(epoch from((current_timestamp - timestamp '1970-01-01 00:00:00')*1000)))"
          + " WHERE catalog_id = #{catalogId} AND deleted_at = 0";
    }

    @Override
    public String softDeleteFilesetVersionsBySchemaId(Long schemaId) {
      return "UPDATE "
          + VERSION_TABLE_NAME
          + " SET deleted_at = floor(extract(epoch from((current_timestamp - timestamp '1970-01-01 00:00:00')*1000)))"
          + " WHERE schema_id = #{schemaId} AND deleted_at = 0";
    }

    @Override
    public String softDeleteFilesetVersionsByFilesetId(Long filesetId) {
      return "UPDATE "
          + VERSION_TABLE_NAME
          + " SET deleted_at = floor(extract(epoch from((current_timestamp - timestamp '1970-01-01 00:00:00')*1000)))"
          + " WHERE fileset_id = #{filesetId} AND deleted_at = 0";
    }

    @Override
    public String softDeleteFilesetVersionsByRetentionLine(
        Long filesetId, long versionRetentionLine, int limit) {
      return "UPDATE "
          + VERSION_TABLE_NAME
          + " SET deleted_at = floor(extract(epoch from((current_timestamp - timestamp '1970-01-01 00:00:00')*1000)))"
          + " WHERE fileset_id = #{filesetId} AND version <= #{versionRetentionLine} AND deleted_at = 0 LIMIT #{limit}";
    }

    @Override
    public String insertFilesetVersionOnDuplicateKeyUpdate(FilesetVersionPO filesetVersionPO) {
      return "INSERT INTO "
          + VERSION_TABLE_NAME
          + "(metalake_id, catalog_id, schema_id, fileset_id,"
          + " version, fileset_comment, properties, storage_location,"
          + " deleted_at)"
          + " VALUES("
          + " #{filesetVersion.metalakeId},"
          + " #{filesetVersion.catalogId},"
          + " #{filesetVersion.schemaId},"
          + " #{filesetVersion.filesetId},"
          + " #{filesetVersion.version},"
          + " #{filesetVersion.filesetComment},"
          + " #{filesetVersion.properties},"
          + " #{filesetVersion.storageLocation},"
          + " #{filesetVersion.deletedAt}"
          + " )"
          + " ON CONFLICT(fileset_id, version, deleted_at) DO UPDATE SET"
          + " metalake_id = #{filesetVersion.metalakeId},"
          + " catalog_id = #{filesetVersion.catalogId},"
          + " schema_id = #{filesetVersion.schemaId},"
          + " fileset_id = #{filesetVersion.filesetId},"
          + " version = #{filesetVersion.version},"
          + " fileset_comment = #{filesetVersion.filesetComment},"
          + " properties = #{filesetVersion.properties},"
          + " storage_location = #{filesetVersion.storageLocation},"
          + " deleted_at = #{filesetVersion.deletedAt}";
    }
  }

  public String insertFilesetVersion(@Param("filesetVersion") FilesetVersionPO filesetVersionPO) {
    return getProvider().insertFilesetVersion(filesetVersionPO);
  }

  public String insertFilesetVersionOnDuplicateKeyUpdate(
      @Param("filesetVersion") FilesetVersionPO filesetVersionPO) {
    return getProvider().insertFilesetVersionOnDuplicateKeyUpdate(filesetVersionPO);
  }

  public String softDeleteFilesetVersionsByMetalakeId(@Param("metalakeId") Long metalakeId) {
    return getProvider().softDeleteFilesetVersionsByMetalakeId(metalakeId);
  }

  public String softDeleteFilesetVersionsByCatalogId(@Param("catalogId") Long catalogId) {
    return getProvider().softDeleteFilesetVersionsByCatalogId(catalogId);
  }

  public String softDeleteFilesetVersionsBySchemaId(@Param("schemaId") Long schemaId) {
    return getProvider().softDeleteFilesetVersionsBySchemaId(schemaId);
  }

  public String softDeleteFilesetVersionsByFilesetId(@Param("filesetId") Long filesetId) {
    return getProvider().softDeleteFilesetVersionsByFilesetId(filesetId);
  }

  public String deleteFilesetVersionsByLegacyTimeline(
      @Param("legacyTimeline") Long legacyTimeline, @Param("limit") int limit) {
    return getProvider().deleteFilesetVersionsByLegacyTimeline(legacyTimeline, limit);
  }

  public String selectFilesetVersionsByRetentionCount(
      @Param("versionRetentionCount") Long versionRetentionCount) {
    return getProvider().selectFilesetVersionsByRetentionCount(versionRetentionCount);
  }

  public String softDeleteFilesetVersionsByRetentionLine(
      @Param("filesetId") Long filesetId,
      @Param("versionRetentionLine") long versionRetentionLine,
      @Param("limit") int limit) {
    return getProvider()
        .softDeleteFilesetVersionsByRetentionLine(filesetId, versionRetentionLine, limit);
  }
}
