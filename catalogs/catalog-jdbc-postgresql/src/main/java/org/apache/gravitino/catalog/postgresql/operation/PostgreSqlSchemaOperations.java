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
package org.apache.gravitino.catalog.postgresql.operation;

import static org.apache.gravitino.catalog.jdbc.utils.JdbcConnectorUtils.escapeSqlLiteral;
import static org.apache.gravitino.catalog.postgresql.operation.PostgreSqlTableOperations.PG_QUOTE;

import com.google.common.collect.ImmutableSet;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.sql.DataSource;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.gravitino.catalog.jdbc.JdbcSchema;
import org.apache.gravitino.catalog.jdbc.config.JdbcConfig;
import org.apache.gravitino.catalog.jdbc.converter.JdbcExceptionConverter;
import org.apache.gravitino.catalog.jdbc.operation.JdbcDatabaseOperations;
import org.apache.gravitino.catalog.jdbc.utils.DataSourceUtils;
import org.apache.gravitino.catalog.jdbc.utils.JdbcConnectorUtils;
import org.apache.gravitino.exceptions.NoSuchSchemaException;
import org.apache.gravitino.exceptions.NonEmptySchemaException;
import org.apache.gravitino.meta.AuditInfo;

/** Database operations for PostgreSQL. */
public class PostgreSqlSchemaOperations extends JdbcDatabaseOperations {

  private static final String EXTERNAL_DEPENDENTS_SQL =
      "WITH RECURSIVE dependents(classid, objid, objsubid) AS ( "
          + "  SELECT 'pg_catalog.pg_namespace'::regclass::oid, oid, 0 "
          + "  FROM pg_catalog.pg_namespace WHERE nspname = ? "
          + "  UNION "
          + "  SELECT next_object.classid, next_object.objid, next_object.objsubid "
          + "  FROM dependents current_object "
          + "  CROSS JOIN LATERAL ( "
          + "    SELECT d.classid, d.objid, d.objsubid FROM pg_catalog.pg_depend d "
          + "    WHERE d.refclassid = current_object.classid AND d.refobjid = current_object.objid "
          + "      AND (current_object.objsubid = 0 OR d.refobjsubid = current_object.objsubid) "
          + "    UNION "
          + "    SELECT d.refclassid, d.refobjid, d.refobjsubid FROM pg_catalog.pg_depend d "
          + "    WHERE d.classid = current_object.classid AND d.objid = current_object.objid "
          + "      AND d.objsubid = current_object.objsubid "
          // Internal and extension members drop their owner. Schema-less automatic subobjects, such
          // as
          // column defaults, instead need their owner only to identify the affected schema.
          // Do not follow automatic owners of schema-qualified objects (e.g. partitions), since
          // dropping a child does not drop its parent.
          + "      AND (d.deptype IN ('i', 'e') OR (d.deptype = 'a' AND "
          + "        (pg_catalog.pg_identify_object(current_object.classid, current_object.objid, "
          + "          current_object.objsubid)).schema IS NULL)) "
          + "  ) next_object "
          + ") "
          + "SELECT DISTINCT pg_catalog.pg_describe_object(d.classid, d.objid, d.objsubid) AS dependent "
          + "FROM dependents d "
          + "CROSS JOIN LATERAL pg_catalog.pg_identify_object(d.classid, d.objid, d.objsubid) info "
          // TOAST tables and their indexes/types are PostgreSQL-managed storage for the tables
          // being dropped, not user objects in another schema. Keep traversing them, but do not
          // report the internal pg_toast namespace as an external dependent.
          // pg_identify_object returns a quoted SQL identifier, not the raw namespace name.
          + "WHERE info.schema IS NOT NULL AND info.schema <> pg_catalog.quote_ident(?) "
          + "AND info.schema <> 'pg_toast'; ";

  private String database;

  @Override
  public void initialize(
      DataSource dataSource, JdbcExceptionConverter exceptionMapper, Map<String, String> conf) {
    super.initialize(dataSource, exceptionMapper, conf);
    database = new JdbcConfig(conf).getJdbcDatabase();
  }

  @Override
  public JdbcSchema load(String schema) throws NoSuchSchemaException {
    try (Connection connection = getConnection()) {
      if (!schemaExists(connection, schema)) {
        throw new NoSuchSchemaException("No such schema: %s", schema);
      }

      String comment = getSchemaComment(schema, connection);
      return JdbcSchema.builder()
          .withName(schema)
          .withComment(comment)
          .withAuditInfo(AuditInfo.EMPTY)
          .withProperties(Collections.emptyMap())
          .build();
    } catch (SQLException e) {
      throw exceptionMapper.toGravitinoException(e);
    }
  }

  @Override
  public List<String> listDatabases() {
    List<String> result = new ArrayList<>();
    try (Connection connection = getConnection();
        ResultSet resultSet = getSchema(connection, null)) {
      while (resultSet.next()) {
        String schemaName = resultSet.getString(1);
        if (!isSystemDatabase(schemaName)) {
          result.add(resultSet.getString(1));
        }
      }
    } catch (final SQLException se) {
      throw this.exceptionMapper.toGravitinoException(se);
    }
    return result;
  }

  @Override
  public String generateCreateDatabaseSql(
      String schema, String comment, Map<String, String> properties) {
    if (MapUtils.isNotEmpty(properties)) {
      throw new UnsupportedOperationException(
          "PostgreSQL does not support properties on database create.");
    }

    StringBuilder sqlBuilder = new StringBuilder("CREATE SCHEMA \"" + schema + "\";");
    if (StringUtils.isNotEmpty(comment)) {
      sqlBuilder
          .append("COMMENT ON SCHEMA ")
          .append(PG_QUOTE)
          .append(schema)
          .append(PG_QUOTE)
          .append(" IS E'")
          .append(escapeSqlLiteral(comment, '\''))
          .append("'");
    }
    return sqlBuilder.toString();
  }

  /**
   * Get the schema with the given name.
   *
   * <p>Note: This method will return a result set that may contain multiple rows as the schemaName
   * in `getSchemas` is a pattern. The result set will contain all schemas that match the pattern.
   *
   * <p>Database in PG corresponds to Catalog in JDBC. Schema in PG corresponds to Schema in JDBC.
   *
   * <p>The catalog parameter is sourced from {@link Connection#getCatalog()} rather than the
   * configured database field, because PostgreSQL JDBC driver 42.7+ strictly matches the catalog
   * parameter against the connection's actual catalog. In PostgreSQL, a connection can only connect
   * to a single database, and {@code setCatalog()} is effectively a no-op.
   *
   * @param connection the connection to the database
   * @param schemaName the name of the schema
   */
  private ResultSet getSchema(Connection connection, String schemaName) throws SQLException {
    final DatabaseMetaData metaData = connection.getMetaData();
    return metaData.getSchemas(connection.getCatalog(), schemaName);
  }

  @Override
  public String generateDropDatabaseSql(String schema, boolean cascade) {
    StringBuilder sqlBuilder = new StringBuilder("DROP SCHEMA " + quoteName(schema));
    if (cascade) {
      sqlBuilder.append(" CASCADE");
    }
    return sqlBuilder.toString();
  }

  @Override
  protected void dropDatabase(String schema, boolean cascade) {
    if (!cascade) {
      super.dropDatabase(schema, false);
      return;
    }
    try (Connection connection = getConnection();
        ReadCommittedScope isolation = new ReadCommittedScope(connection)) {
      connection.setAutoCommit(false);
      try {
        // Native DROP locks every affected object without requiring explicit LOCK TABLE privileges.
        // Do not commit yet: another transaction still sees the pre-drop catalog rows through
        // MVCC, including dependencies committed while DROP was acquiring its locks. Validating
        // a fresh snapshot exported after DROP avoids a check-then-drop race for views, new objects
        // and other object kinds. Importing it also fails safely if a multi-host JDBC URL routes
        // the
        // validation connection to a different server. The native locks prevent new references
        // until we commit or roll back.
        // Open the independent connection before DDL, and outside the pool, so a one-slot pool or
        // concurrent drops cannot deadlock waiting for another pooled connection while holding DDL
        // locks. Driver authentication, timeouts and connection-init SQL remain the same.
        try (Connection validation = DataSourceUtils.createIndependentConnection(dataSource)) {
          validation.setAutoCommit(true);
          validation.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
          validation.setAutoCommit(false);
          JdbcConnectorUtils.executeUpdate(connection, generateDropDatabaseSql(schema, true));
          try (Statement exporter = connection.createStatement();
              ResultSet snapshot =
                  exporter.executeQuery("SELECT pg_catalog.pg_export_snapshot()")) {
            snapshot.next();
            JdbcConnectorUtils.executeUpdate(
                validation,
                "SET TRANSACTION SNAPSHOT '" + escapeSqlLiteral(snapshot.getString(1), '\'') + "'");
          }
          try (PreparedStatement statement = validation.prepareStatement(EXTERNAL_DEPENDENTS_SQL)) {
            statement.setString(1, schema);
            statement.setString(2, schema);
            try (ResultSet result = statement.executeQuery()) {
              List<String> external = new ArrayList<>();
              while (result.next()) {
                external.add(result.getString(1));
              }
              if (!external.isEmpty()) {
                throw new NonEmptySchemaException(
                    "Cannot drop schema %s: objects outside the schema depend on it: %s. "
                        + "Remove those dependencies before retrying.",
                    schema, external);
              }
            }
          }
        }
        connection.commit();
      } catch (SQLException | RuntimeException e) {
        try {
          connection.rollback();
        } catch (SQLException rollbackFailure) {
          e.addSuppressed(rollbackFailure);
        }
        throw e;
      }
    } catch (SQLException e) {
      throw exceptionMapper.toGravitinoException(e);
    }
  }

  @Override
  protected Connection getConnection() throws SQLException {
    Connection connection = dataSource.getConnection();
    connection.setCatalog(database);
    return connection;
  }

  @Override
  protected String generateDatabaseExistSql(String databaseName) {
    return String.format(
        "SELECT n.datname FROM pg_catalog.pg_database n where n.datname='%s'", databaseName);
  }

  @Override
  protected boolean supportSchemaComment() {
    return true;
  }

  @Override
  protected Set<String> createSysDatabaseNameSet() {
    return ImmutableSet.of("pg_toast", "pg_catalog", "information_schema");
  }

  public boolean schemaExists(Connection connection, String schema) throws SQLException {
    try (ResultSet resultSet = getSchema(connection, schema)) {
      while (resultSet.next()) {
        if (Objects.equals(resultSet.getString(1), schema)) {
          return true;
        }
      }
    }
    return false;
  }

  private String getShowSchemaCommentSql(String schema) {
    return String.format(
        "SELECT obj_description(n.oid, 'pg_namespace') AS comment\n"
            + "FROM pg_catalog.pg_namespace n\n"
            + "WHERE n.nspname = '%s';\n",
        schema);
  }

  private String getSchemaComment(String schema, Connection connection) throws SQLException {
    try (PreparedStatement preparedStatement =
        connection.prepareStatement(getShowSchemaCommentSql(schema))) {
      try (ResultSet resultSet = preparedStatement.executeQuery()) {
        if (resultSet.next()) {
          return resultSet.getString("comment");
        }
      }
    }
    return null;
  }

  private static String quoteName(String name) {
    return "\"" + name.replace("\"", "\"\"") + "\"";
  }

  // Export a new post-DROP snapshot even when the pool normally uses REPEATABLE READ. Restore the
  // original isolation before returning the connection; try-with-resources preserves any earlier
  // failure if restoration also fails.
  private static final class ReadCommittedScope implements AutoCloseable {
    private final Connection connection;
    private final int originalIsolation;

    private ReadCommittedScope(Connection connection) throws SQLException {
      this.connection = connection;
      connection.setAutoCommit(true);
      originalIsolation = connection.getTransactionIsolation();
      if (originalIsolation != Connection.TRANSACTION_READ_COMMITTED) {
        connection.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      }
    }

    /** {@inheritDoc} */
    @Override
    public void close() throws SQLException {
      if (originalIsolation != Connection.TRANSACTION_READ_COMMITTED) {
        connection.setTransactionIsolation(originalIsolation);
      }
    }
  }
}
