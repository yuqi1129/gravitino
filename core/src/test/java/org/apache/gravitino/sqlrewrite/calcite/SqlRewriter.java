/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 *  "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */

package org.apache.gravitino.sqlrewrite.calcite;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDelete;
import org.apache.calcite.sql.SqlDialect.DatabaseProduct;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlInsert;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlUpdate;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.SqlWriterConfig;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.calcite.sql.pretty.SqlPrettyWriter;
import org.apache.calcite.sql.util.SqlShuttle;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.testcontainers.shaded.com.google.common.collect.ImmutableList;

public class SqlRewriter {

  public static class FilterRule {
    String user;
    String table;
    String condition;

    FilterRule(String user, String table, String condition) {
      this.user = user;
      this.table = table;
      this.condition = condition;
    }
  }

  static final List<FilterRule> RULES =
      ImmutableList.of(
          new FilterRule("user1", "table1", "address = 'shanghai'"),
          new FilterRule("user1", "table2", "region = 'east'"));

  public static String rewriteSql(String user, String sql) throws Exception {
    SqlParser parser = SqlParser.create(sql);
    SqlNode sqlNode = parser.parseStmt();

    SqlNode rewritten = sqlNode.accept(new RewriteVisitor(user));
    SqlWriterConfig config =
        SqlPrettyWriter.config()
            .withQuoteAllIdentifiers(true)
            .withDialect(DatabaseProduct.MYSQL.getDialect());

    SqlPrettyWriter writer = new SqlPrettyWriter(config);
    rewritten.unparse(writer, 0, 0);
    return writer.toString();
  }

  private static class RewriteVisitor extends SqlShuttle {
    private final String user;

    RewriteVisitor(String user) {
      this.user = user;
    }

    @Override
    public @Nullable SqlNode visit(SqlCall n) {
      if (n instanceof SqlSelect) {
        return visit((SqlSelect) n);
      } else if (n instanceof SqlInsert) {
        return visit((SqlInsert) n);
      } else if (n instanceof SqlUpdate) {
        return visit((SqlUpdate) n);
      } else if (n instanceof SqlDelete) {
        // Handle DELETE statements if needed
        return n;
      }

      return super.visit(n);
    }

    private SqlNode visit(SqlSelect select) {
      SqlNode from = select.getFrom();
      Map<String, String> tableAliasMap = extractTables(from);

      List<SqlNode> filters = new ArrayList<>();
      if (select.getWhere() != null) {
        filters.add(select.getWhere());
      }

      for (Map.Entry<String, String> entry : tableAliasMap.entrySet()) {
        for (FilterRule rule : RULES) {
          if (rule.user.equals(user) && rule.table.equalsIgnoreCase(entry.getKey())) {
            String alias = entry.getValue();
            String condition = alias + "." + rule.condition;
            filters.add(parseCondition(condition, alias));
          }
        }
      }

      if (!filters.isEmpty()) {
        select.setWhere(combineConditions(filters));
      }

      return select;
    }

    private SqlNode visit(SqlInsert insert) {
      SqlNode source = insert.getSource().accept(this);
      insert.setSource((SqlSelect) source);
      return insert;
    }

    private SqlNode visit(SqlUpdate update) {
      SqlNode table = update.getTargetTable();
      String tableName = extractTableName(table);

      for (FilterRule rule : RULES) {
        if (rule.user.equals(user) && rule.table.equalsIgnoreCase(tableName)) {
          SqlNode condition = parseCondition(rule.condition, tableName);
          SqlNode existing = update.getCondition();
          if (existing != null) {
            condition = combineConditions(ImmutableList.of(existing, condition));
          }

          // use the new update.
          update.setOperand(3, condition);
        }
      }

      return update;
    }
  }

  private static SqlNode parseCondition(String expr, String aliasOrTableName) {
    try {
      return SqlParser.create(String.format("SELECT * FROM %s WHERE %s", aliasOrTableName, expr))
          .parseQuery()
          .accept(
              new SqlShuttle() {
                @Override
                public @Nullable SqlNode visit(SqlCall n) {
                  if (n instanceof SqlSelect) {
                    return ((SqlSelect) n).getWhere();
                  }

                  return super.visit(n);
                }
              });
    } catch (Exception e) {
      throw new RuntimeException("Failed to parse condition: " + expr, e);
    }
  }

  private static SqlNode combineConditions(List<SqlNode> conditions) {
    return conditions.stream()
        .reduce(
            (a, b) ->
                new SqlBasicCall(
                    SqlStdOperatorTable.AND, ImmutableList.of(a, b), SqlParserPos.ZERO))
        .orElse(null);
  }

  private static Map<String, String> extractTables(SqlNode from) {
    Map<String, String> map = new HashMap<>();
    if (from instanceof SqlIdentifier) {
      String table = ((SqlIdentifier) from).getSimple();
      map.put(table, table);
    } else if (from instanceof SqlJoin) {
      SqlJoin join = (SqlJoin) from;
      map.putAll(extractTables(join.getLeft()));
      map.putAll(extractTables(join.getRight()));
    } else if (from instanceof SqlBasicCall
        && ((SqlBasicCall) from).getOperator().getName().equalsIgnoreCase("AS")) {
      SqlBasicCall call = (SqlBasicCall) from;
      SqlIdentifier table = (SqlIdentifier) call.getOperandList().get(0);
      SqlIdentifier alias = (SqlIdentifier) call.getOperandList().get(1);
      map.put(table.getSimple(), alias.getSimple());
    } else if (from instanceof SqlWith) {
      return extractTables(((SqlWith) from).body);
    }
    return map;
  }

  private static String extractTableName(SqlNode table) {
    if (table instanceof SqlIdentifier) {
      return ((SqlIdentifier) table).getSimple();
    } else if (table instanceof SqlCall) {
      for (SqlNode operand : ((SqlCall) table).getOperandList()) {
        String name = extractTableName(operand);
        if (name != null) return name;
      }
    }
    return null;
  }

  public static void main(String[] args) throws Exception {
    String sql =
        " WITH cte AS ("
            + "SELECT * FROM table2 t2 WHERE t2.name = 'x'"
            + ")"
            + "SELECT * FROM table1 t1 JOIN cte ON t1.id = cte.id WHERE t1.age > 10";

    String rewritten = rewriteSql("user1", sql);
    System.out.println("Rewritten SQL:\n" + rewritten);
  }
}
