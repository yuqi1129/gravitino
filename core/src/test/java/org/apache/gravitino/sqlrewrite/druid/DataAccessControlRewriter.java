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

package org.apache.gravitino.sqlrewrite.druid;

import com.alibaba.druid.sql.SQLUtils;
import com.alibaba.druid.sql.ast.SQLExpr;
import com.alibaba.druid.sql.ast.SQLStatement;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOpExpr;
import com.alibaba.druid.sql.ast.expr.SQLBinaryOperator;
import com.alibaba.druid.sql.ast.expr.SQLIdentifierExpr;
import com.alibaba.druid.sql.ast.expr.SQLPropertyExpr;
import com.alibaba.druid.sql.ast.statement.SQLDeleteStatement;
import com.alibaba.druid.sql.ast.statement.SQLExprTableSource;
import com.alibaba.druid.sql.ast.statement.SQLJoinTableSource;
import com.alibaba.druid.sql.ast.statement.SQLSelect;
import com.alibaba.druid.sql.ast.statement.SQLSelectQueryBlock;
import com.alibaba.druid.sql.ast.statement.SQLSelectStatement;
import com.alibaba.druid.sql.ast.statement.SQLSubqueryTableSource;
import com.alibaba.druid.sql.ast.statement.SQLTableSource;
import com.alibaba.druid.sql.ast.statement.SQLUnionQuery;
import com.alibaba.druid.sql.ast.statement.SQLUpdateStatement;
import com.alibaba.druid.sql.ast.statement.SQLWithSubqueryClause;
import com.alibaba.druid.sql.dialect.mysql.parser.MySqlStatementParser;
import com.alibaba.druid.sql.dialect.mysql.visitor.MySqlASTVisitorAdapter;
import com.alibaba.druid.sql.parser.SQLParserFeature;
import com.alibaba.druid.sql.parser.SQLStatementParser;
import com.alibaba.druid.util.JdbcConstants;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DataAccessControlRewriter {

  // 用户访问规则配置
  private static final Map<String, Map<String, String>> USER_ACCESS_RULES = new HashMap<>();

  static {
    // 配置用户访问规则
    Map<String, String> zhangsanRules = new HashMap<>();
    zhangsanRules.put("tableA", "id = 1");
    zhangsanRules.put("tableB", "department = 'IT'");
    zhangsanRules.put("table2", "age > 30");
    zhangsanRules.put("table1", "name LIKE '张%'");
    USER_ACCESS_RULES.put("张三", zhangsanRules);

    Map<String, String> lisiRules = new HashMap<>();
    lisiRules.put("tableA", "id = 2");
    lisiRules.put("tableC", "status = 'active'");

    USER_ACCESS_RULES.put("李四", lisiRules);
  }

  /**
   * 重写SQL以添加数据访问控制
   *
   * @param username 用户名
   * @param sql 原始SQL
   * @return 重写后的SQL
   */
  public String rewriteSQL(String username, String sql) {
    // 获取用户的访问规则
    Map<String, String> accessRules =
        USER_ACCESS_RULES.getOrDefault(username, Collections.emptyMap());
    if (accessRules.isEmpty()) {
      return sql; // 没有规则直接返回
    }

    // 解析SQL
    SQLStatementParser parser = new MySqlStatementParser(sql, SQLParserFeature.KeepComments);
    List<SQLStatement> statements = parser.parseStatementList();

    // 创建访问控制Visitor
    AccessControlVisitor visitor = new AccessControlVisitor(accessRules);

    // 遍历所有语句并应用访问控制
    for (SQLStatement stmt : statements) {
      stmt.accept(visitor);
    }

    // 生成重写后的SQL
    return SQLUtils.toSQLString(statements, JdbcConstants.MYSQL);
  }

  /** 自定义访问控制Visitor */
  private static class AccessControlVisitor extends MySqlASTVisitorAdapter {
    private final Map<String, String> accessRules;
    private final Map<String, String> tableAliasMap = new HashMap<>();
    private final Set<String> processedTables = new HashSet<>();

    public AccessControlVisitor(Map<String, String> accessRules) {
      this.accessRules = accessRules;
    }

    @Override
    public boolean visit(SQLSelectStatement x) {
      SQLSelect select = x.getSelect();
      if (select != null) {
        select.accept(this);
      }
      return true;
    }

    @Override
    public boolean visit(SQLSelectQueryBlock x) {
      // 收集表别名映射
      collectTableAliases(x.getFrom());

      // 处理查询块
      processQueryBlock(x);

      return true;
    }

    @Override
    public boolean visit(SQLUnionQuery x) {
      // 处理左查询
      if (x.getLeft() instanceof SQLSelectQueryBlock) {
        ((SQLSelectQueryBlock) x.getLeft()).accept(this);
      }

      // 处理右查询
      if (x.getRight() instanceof SQLSelectQueryBlock) {
        ((SQLSelectQueryBlock) x.getRight()).accept(this);
      }

      return true;
    }

    @Override
    public boolean visit(SQLUpdateStatement x) {
      // 收集表别名映射
      collectTableAliases(x.getTableSource());

      // 处理表访问控制并更新WHERE条件
      SQLExpr newWhere = processTableAccessControl(x.getTableSource(), x.getWhere());
      x.setWhere(newWhere);

      return true;
    }

    @Override
    public boolean visit(SQLDeleteStatement x) {
      // 收集表别名映射
      collectTableAliases(x.getTableSource());

      // 处理表访问控制并更新WHERE条件
      SQLExpr newWhere = processTableAccessControl(x.getTableSource(), x.getWhere());
      x.setWhere(newWhere);

      return true;
    }

    @Override
    public boolean visit(SQLWithSubqueryClause x) {
      for (SQLWithSubqueryClause.Entry entry : x.getEntries()) {
        entry.getSubQuery().accept(this);
      }
      return true;
    }

    // 收集表别名映射
    private void collectTableAliases(SQLTableSource tableSource) {
      if (tableSource == null) return;

      if (tableSource instanceof SQLExprTableSource) {
        SQLExprTableSource exprTableSource = (SQLExprTableSource) tableSource;
        String tableName = getTableName(exprTableSource);
        String alias = exprTableSource.getAlias();

        if (alias != null) {
          tableAliasMap.put(alias, tableName);
        }
      } else if (tableSource instanceof SQLJoinTableSource) {
        SQLJoinTableSource joinTableSource = (SQLJoinTableSource) tableSource;
        collectTableAliases(joinTableSource.getLeft());
        collectTableAliases(joinTableSource.getRight());
      } else if (tableSource instanceof SQLSubqueryTableSource) {
        SQLSubqueryTableSource subqueryTableSource = (SQLSubqueryTableSource) tableSource;
        subqueryTableSource.getSelect().accept(this);
      }
    }

    // 处理查询块
    private void processQueryBlock(SQLSelectQueryBlock queryBlock) {
      // 处理FROM子句
      SQLTableSource from = queryBlock.getFrom();

      if (from instanceof SQLJoinTableSource) {
        // 处理JOIN表
        SQLExpr newWhere = processJoinTables((SQLJoinTableSource) from, queryBlock.getWhere());
        queryBlock.setWhere(newWhere);
      } else {
        // 处理单表
        SQLExpr newWhere = processTableAccessControl(from, queryBlock.getWhere());
        queryBlock.setWhere(newWhere);
      }
    }

    // 处理表访问控制
    private SQLExpr processTableAccessControl(SQLTableSource tableSource, SQLExpr whereClause) {
      if (tableSource == null) return whereClause;

      if (tableSource instanceof SQLExprTableSource) {
        SQLExprTableSource exprTableSource = (SQLExprTableSource) tableSource;
        String tableName = getTableName(exprTableSource);
        String alias = exprTableSource.getAlias();

        // 应用访问控制规则并返回新的WHERE条件
        return applyAccessControl(tableName, alias, whereClause);
      } else if (tableSource instanceof SQLSubqueryTableSource) {
        // 子查询表源，递归处理
        SQLSubqueryTableSource subqueryTableSource = (SQLSubqueryTableSource) tableSource;
        subqueryTableSource.getSelect().accept(this);
        return whereClause;
      }
      return whereClause;
    }

    // 处理JOIN表
    private SQLExpr processJoinTables(SQLJoinTableSource joinTableSource, SQLExpr whereClause) {
      SQLExpr newWhere = whereClause;

      // 处理左表
      newWhere = processTableAccessControl(joinTableSource.getLeft(), newWhere);

      // 处理右表
      newWhere = processTableAccessControl(joinTableSource.getRight(), newWhere);

      return newWhere;
    }

    // 应用访问控制规则
    private SQLExpr applyAccessControl(String tableName, String alias, SQLExpr whereClause) {
      // 检查表是否有访问规则
      String accessCondition = accessRules.get(tableName);
      if (accessCondition == null || processedTables.contains(tableName)) {
        return whereClause; // 没有规则或已处理，直接返回原条件
      }

      // 标记表已处理
      processedTables.add(tableName);

      // 解析访问条件
      SQLExpr accessExpr = SQLUtils.toMySqlExpr(accessCondition);

      // 如果使用了别名，重写条件中的列引用
      if (alias != null) {
        accessExpr = rewriteColumnReferences(accessExpr, alias);
      }

      // 将访问条件添加到WHERE子句
      if (whereClause == null) {
        return accessExpr;
      } else {
        return new SQLBinaryOpExpr(whereClause, SQLBinaryOperator.BooleanAnd, accessExpr);
      }
    }

    // 重写列引用以使用表别名
    private SQLExpr rewriteColumnReferences(SQLExpr expr, String alias) {
      if (expr instanceof SQLIdentifierExpr) {
        return new SQLPropertyExpr(alias, ((SQLIdentifierExpr) expr).getName());
      } else if (expr instanceof SQLBinaryOpExpr) {
        SQLBinaryOpExpr binaryExpr = (SQLBinaryOpExpr) expr;
        binaryExpr.setLeft(rewriteColumnReferences(binaryExpr.getLeft(), alias));
        binaryExpr.setRight(rewriteColumnReferences(binaryExpr.getRight(), alias));
        return binaryExpr;
      } else if (expr instanceof SQLPropertyExpr) {
        // 已经是带别名的列引用，不需要修改
        return expr;
      }
      return expr;
    }

    // 获取表名
    private String getTableName(SQLExprTableSource tableSource) {
      SQLExpr expr = tableSource.getExpr();
      if (expr instanceof SQLIdentifierExpr) {
        return ((SQLIdentifierExpr) expr).getName();
      } else if (expr instanceof SQLPropertyExpr) {
        return ((SQLPropertyExpr) expr).getName();
      }
      return null;
    }
  }

  // 测试用例
  public static void main(String[] args) {
    DataAccessControlRewriter rewriter = new DataAccessControlRewriter();

    // 测试SELECT语句
    String selectSQL = "SELECT * FROM tableA";
    System.out.println("原始 SELECT: " + selectSQL);
    System.out.println("张三重写: " + rewriter.rewriteSQL("张三", selectSQL));
    System.out.println("李四重写: " + rewriter.rewriteSQL("李四", selectSQL));

    // 测试带JOIN的SELECT
    String joinSQL = "SELECT a.*, b.name FROM tableA a JOIN tableB b ON a.id = b.id";
    System.out.println("\n原始 JOIN: " + joinSQL);
    System.out.println("张三重写: " + rewriter.rewriteSQL("张三", joinSQL));

    // 测试带WHERE的SELECT
    String whereSQL = "SELECT * FROM tableA WHERE status = 'active'";
    System.out.println("\n原始 WHERE: " + whereSQL);
    System.out.println("张三重写: " + rewriter.rewriteSQL("张三", whereSQL));

    // 测试CTE (WITH子句)
    String cteSQL = "WITH cte AS (SELECT * FROM tableA) SELECT * FROM cte";
    System.out.println("\n原始 CTE: " + cteSQL);
    System.out.println("张三重写: " + rewriter.rewriteSQL("张三", cteSQL));

    // 测试UNION
    String unionSQL = "SELECT * FROM tableA UNION SELECT * FROM tableB";
    System.out.println("\n原始 UNION: " + unionSQL);
    System.out.println("张三重写: " + rewriter.rewriteSQL("张三", unionSQL));

    // 测试UPDATE
    String updateSQL = "UPDATE tableA SET name = 'John'";
    System.out.println("\n原始 UPDATE: " + updateSQL);
    System.out.println("张三重写: " + rewriter.rewriteSQL("张三", updateSQL));

    // 测试DELETE
    String deleteSQL = "DELETE FROM tableA";
    System.out.println("\n原始 DELETE: " + deleteSQL);
    System.out.println("张三重写: " + rewriter.rewriteSQL("张三", deleteSQL));

    String anotherSQL =
        " WITH cte AS ("
            + "SELECT * FROM table2 t2 WHERE t2.name = 'x'"
            + ")"
            + "SELECT * FROM table1 t1 JOIN cte ON t1.id = cte.id WHERE t1.age > 10";
    System.out.println("\n原始 SQL: " + anotherSQL);
    System.out.println("张三重写: " + rewriter.rewriteSQL("张三", anotherSQL));
  }
}
