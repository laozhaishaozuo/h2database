/*
 * Copyright 2004-2018 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.expression.aggregate;

import java.util.ArrayList;

import org.h2.command.dml.SelectOrderBy;
import org.h2.engine.Session;
import org.h2.expression.Expression;
import org.h2.result.SortOrder;
import org.h2.table.ColumnResolver;
import org.h2.table.TableFilter;
import org.h2.util.StringUtils;
import org.h2.value.Value;
import org.h2.value.ValueArray;

/**
 * Window clause.
 */
public final class Window {

    private final ArrayList<Expression> partitionBy;

    private final ArrayList<SelectOrderBy> orderBy;

    /**
     * @param builder
     *            string builder
     * @param orderBy
     *            ORDER BY clause, or null
     */
    static void appendOrderBy(StringBuilder builder, ArrayList<SelectOrderBy> orderBy) {
        if (orderBy != null && !orderBy.isEmpty()) {
            builder.append(" ORDER BY ");
            for (int i = 0; i < orderBy.size(); i++) {
                SelectOrderBy o = orderBy.get(i);
                if (i > 0) {
                    builder.append(", ");
                }
                builder.append(o.expression.getSQL());
                SortOrder.typeToString(builder, o.sortType);
            }
        }
    }

    /**
     * Creates a new instance of window clause.
     *
     * @param partitionBy
     *            PARTITION BY clause, or null
     * @param orderBy
     *            ORDER BY clause, or null
     */
    public Window(ArrayList<Expression> partitionBy, ArrayList<SelectOrderBy> orderBy) {
        this.partitionBy = partitionBy;
        this.orderBy = orderBy;
    }

    /**
     * Map the columns of the resolver to expression columns.
     *
     * @param resolver
     *            the column resolver
     * @param level
     *            the subquery nesting level
     * @see Expression#mapColumns(ColumnResolver, int)
     */
    public void mapColumns(ColumnResolver resolver, int level) {
        if (partitionBy != null) {
            for (Expression e : partitionBy) {
                e.mapColumns(resolver, level);
            }
        }
        if (orderBy != null) {
            for (SelectOrderBy o : orderBy) {
                o.expression.mapColumns(resolver, level);
            }
        }
    }

    /**
     * Tell the expression columns whether the table filter can return values
     * now. This is used when optimizing the query.
     *
     * @param tableFilter
     *            the table filter
     * @param value
     *            true if the table filter can return value
     * @see Expression#setEvaluatable(TableFilter, boolean)
     */
    public void setEvaluatable(TableFilter tableFilter, boolean value) {
        if (partitionBy != null) {
            for (Expression e : partitionBy) {
                e.setEvaluatable(tableFilter, value);
            }
        }
        if (orderBy != null) {
            for (SelectOrderBy o : orderBy) {
                o.expression.setEvaluatable(tableFilter, value);
            }
        }
    }

    /**
     * Returns ORDER BY clause.
     *
     * @return ORDER BY clause, or null
     */
    public ArrayList<SelectOrderBy> getOrderBy() {
        return orderBy;
    }

    /**
     * Returns the key for the current group.
     *
     * @param session
     *            session
     * @return key for the current group, or null
     */
    public ValueArray getCurrentKey(Session session) {
        if (partitionBy == null) {
            return null;
        }
        int len = partitionBy.size();
        Value[] keyValues = new Value[len];
        // update group
        for (int i = 0; i < len; i++) {
            Expression expr = partitionBy.get(i);
            keyValues[i] = expr.getValue(session);
        }
        return ValueArray.get(keyValues);
    }

    /**
     * Returns SQL representation.
     *
     * @return SQL representation.
     * @see Expression#getSQL()
     */
    public String getSQL() {
        if (partitionBy == null && orderBy == null) {
            return "OVER ()";
        }
        StringBuilder builder = new StringBuilder().append("OVER (");
        if (partitionBy != null) {
            builder.append("PARTITION BY ");
            for (int i = 0; i < partitionBy.size(); i++) {
                if (i > 0) {
                    builder.append(", ");
                }
                builder.append(StringUtils.unEnclose(partitionBy.get(i).getSQL()));
            }
        }
        appendOrderBy(builder, orderBy);
        return builder.append(')').toString();
    }

    /**
     * Update an aggregate value.
     *
     * @param session
     *            the session
     * @param stage
     *            select stage
     * @see Expression#updateAggregate(Session, int)
     */
    public void updateAggregate(Session session, int stage) {
        if (partitionBy != null) {
            for (Expression expr : partitionBy) {
                expr.updateAggregate(session, stage);
            }
        }
        if (orderBy != null) {
            for (SelectOrderBy o : orderBy) {
                o.expression.updateAggregate(session, stage);
            }
        }
    }

    @Override
    public String toString() {
        return getSQL();
    }

}
