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

package org.apache.gravitino.sqlrewrite.jsqlparser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.Statements;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedFromItem;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.util.deparser.ExpressionDeParser;

public class JSqlParserRewriter {
  private final String username;
  private final Map<String, Expression> ruleMap; // 真实表名 -> 过滤表达式

  public JSqlParserRewriter(String username, Map<String, Expression> ruleMap) {
    this.username = username;
    this.ruleMap = ruleMap;
  }

  public String rewrite(String sql) throws Exception {
    Statements stmts = CCJSqlParserUtil.parseStatements(sql);
    for (Statement stmt : stmts.getStatements()) {
      if (stmt instanceof Select) {
        rewriteSelect((Select) stmt);
      } else if (stmt instanceof Update) {
        rewriteUpdate((Update) stmt);
      } else if (stmt instanceof Delete) {
        rewriteDelete((Delete) stmt);
      }
    }
    return stmts.toString();
  }

  private void rewriteSelect(Select sel) {
    if (sel.getWithItemsList() != null) {
      sel.getWithItemsList().forEach(w -> processSelectBody(w.getSelectBody()));
    }
    processSelectBody(sel.getSelectBody());
  }

  private void processSelectBody(Select body) {
    if (body instanceof PlainSelect) {
      rewritePlainSelect((PlainSelect) body);
    } else if (body instanceof SetOperationList) {
      ((SetOperationList) body).getSelects().forEach(this::processSelectBody);
    } else if (body instanceof WithItem) {
      processSelectBody(((WithItem) body).getSelectBody());
    }
  }

  private void rewritePlainSelect(PlainSelect ps) {
    Map<String, String> aliasMap = new HashMap<>();
    collectAliases(ps.getFromItem(), aliasMap);
    if (ps.getJoins() != null) {
      for (Join j : ps.getJoins()) {
        collectAliases(j.getRightItem(), aliasMap);
      }
    }
    List<Expression> injections = new ArrayList<>();
    for (Map.Entry<String, Expression> rule : ruleMap.entrySet()) {
      String table = rule.getKey();
      Expression cond = rule.getValue();
      // 如果 SQL 中存在该表（真实表）
      aliasMap.forEach(
          (aliasOrName, realTable) -> {
            if (realTable.equalsIgnoreCase(table)) {
              // 使用别名或原名作为前缀
              ColumnReplacer cr = new ColumnReplacer(aliasOrName);

              // Have problem here, please note.
              // Expression cloned = cond.clone();
              cond.accept(cr);
              injections.add(cond);
            }
          });
    }
    Expression where = ps.getWhere();
    for (Expression inj : injections) {
      where = (where == null) ? inj : new AndExpression(where, inj);
    }
    ps.setWhere(where);

    FromItem fromItem = ps.getFromItem();
    handleFromItem(fromItem, aliasMap);

    if (ps.getJoins() != null) {
      for (Join j : ps.getJoins()) {
        if (j.getFromItem() != null) {
          handleFromItem(j.getFromItem(), aliasMap);
        }
      }
    }
  }

  private void handleFromItem(FromItem item, Map<String, String> aliasMap) {
    if (item instanceof ParenthesedFromItem) {
      handleFromItem(((ParenthesedFromItem) item).getFromItem(), aliasMap);
      ParenthesedFromItem parenthesedFromItem = (ParenthesedFromItem) item;
      parenthesedFromItem.getJoins().forEach(join -> handleFromItem(join.getRightItem(), aliasMap));
    } else if (item instanceof ParenthesedSelect) {
      processSelectBody(((ParenthesedSelect) item).getSelectBody());
    } else if (item instanceof Table) {
      collectAliases(item, aliasMap);
    }
  }

  private void rewriteUpdate(Update upd) {
    String real = upd.getTable().getName();
    Expression cond = ruleMap.get(real);
    if (cond != null) {
      // update 没别名时直接用真实条件
      Expression where = upd.getWhere();
      where = (where == null) ? cond : new AndExpression(where, cond);
      upd.setWhere(where);
    }
  }

  private void rewriteDelete(Delete del) {
    String real = del.getTable().getName();
    Expression cond = ruleMap.get(real);
    if (cond != null) {
      Expression where = del.getWhere();
      where = (where == null) ? cond : new AndExpression(where, cond);
      del.setWhere(where);
    }
  }

  private void collectAliases(FromItem item, Map<String, String> map) {
    if (item instanceof Table) {
      Table t = (Table) item;
      String name = t.getName();
      String alias = t.getAlias() != null ? t.getAlias().getName() : name;
      map.put(alias, name);
    } else if (item instanceof ParenthesedSelect) {
      ParenthesedSelect ss = (ParenthesedSelect) item;
      String alias = ss.getAlias() != null ? ss.getAlias().getName() : null;
      processSelectBody(ss.getSelectBody());
      if (alias != null) {
        map.put(alias, alias); // treat as self if needed
      }
    } else if (item instanceof ParenthesedFromItem) {
      ParenthesedFromItem pfi = (ParenthesedFromItem) item;
      if (pfi.getFromItem() != null) {
        collectAliases(pfi.getFromItem(), map);
      }
    } else if (item instanceof WithItem) {
      WithItem ss = (WithItem) item;
      String alias = ss.getAlias() != null ? ss.getAlias().getName() : null;
      processSelectBody(ss.getSelectBody());
      if (alias != null) {
        map.put(alias, alias); // treat as self if needed
      }
    }
  }

  // 替换过滤条件中的 Column，使其带上 alias 或 表名前缀
  static class ColumnReplacer extends ExpressionDeParser {
    private final String prefix;

    public ColumnReplacer(String prefix) {
      super();
      this.prefix = prefix;
    }

    @Override
    public void visit(Column column) {
      String colName = column.getColumnName();
      super.visit(new Column(prefix + "." + colName));
    }
  }

  // —— 测试
  public static void main(String[] args) throws Exception {
    Map<String, Expression> ruleMap = new HashMap<>();
    ruleMap.put(
        "A",
        new AndExpression(
            new EqualsTo(new Column("id"), new LongValue(1)),
            new EqualsTo(new Column("address"), new StringValue("'beijing'"))));
    ruleMap.put("B", new EqualsTo(new Column("type"), new StringValue("'user'")));

    String sql =
        ""
            + "WITH cte AS ("
            + "SELECT * FROM A a JOIN B b ON a.x=b.x"
            + ") "
            + "SELECT a.x, b.y FROM cte JOIN B b2 ON cte.id=b2.id WHERE b2.name = 'Tom'"
            + "UNION"
            + "SELECT * FROM A"
            + "WHERE a.x>0;"
            + "UPDATE A SET name='x' WHERE age>20;"
            + " DELETE FROM B;";

    JSqlParserRewriter rw = new JSqlParserRewriter("zhangsan", ruleMap);
    System.out.println(rw.rewrite(sql));
  }
}
