/*
 * Copyright 2004-2018 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (http://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.expression.aggregate;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

import org.h2.api.ErrorCode;
import org.h2.command.dml.Select;
import org.h2.command.dml.SelectGroups;
import org.h2.command.dml.SelectOrderBy;
import org.h2.engine.Session;
import org.h2.expression.Expression;
import org.h2.expression.ExpressionVisitor;
import org.h2.message.DbException;
import org.h2.result.SortOrder;
import org.h2.table.ColumnResolver;
import org.h2.table.TableFilter;
import org.h2.util.ValueHashMap;
import org.h2.value.Value;
import org.h2.value.ValueArray;
import org.h2.value.ValueInt;

/**
 * A base class for aggregates and window functions.
 */
public abstract class AbstractAggregate extends Expression {

    protected final Select select;

    protected final boolean distinct;

    protected Expression filterCondition;

    protected Window over;

    protected SortOrder overOrderBySort;

    private int lastGroupRowId;

    protected static SortOrder createOrder(Session session, ArrayList<SelectOrderBy> orderBy, int offset) {
        int size = orderBy.size();
        int[] index = new int[size];
        int[] sortType = new int[size];
        for (int i = 0; i < size; i++) {
            SelectOrderBy o = orderBy.get(i);
            index[i] = i + offset;
            sortType[i] = o.sortType;
        }
        return new SortOrder(session.getDatabase(), index, sortType, null);
    }

    AbstractAggregate(Select select, boolean distinct) {
        this.select = select;
        this.distinct = distinct;
    }

    /**
     * Sets the FILTER condition.
     *
     * @param filterCondition
     *            FILTER condition
     */
    public void setFilterCondition(Expression filterCondition) {
        if (isAggregate()) {
            this.filterCondition = filterCondition;
        } else {
            throw DbException.getUnsupportedException("Window function");
        }
    }

    /**
     * Sets the OVER condition.
     *
     * @param over
     *            OVER condition
     */
    public void setOverCondition(Window over) {
        this.over = over;
    }

    /**
     * Checks whether this expression is an aggregate function.
     *
     * @return true if this is an aggregate function (including aggregates with
     *         OVER clause), false if this is a window function
     */
    public abstract boolean isAggregate();

    /**
     * Returns the sort order for OVER clause.
     *
     * @return the sort order for OVER clause
     */
    SortOrder getOverOrderBySort() {
        return overOrderBySort;
    }

    @Override
    public void mapColumns(ColumnResolver resolver, int level) {
        if (filterCondition != null) {
            filterCondition.mapColumns(resolver, level);
        }
        if (over != null) {
            over.mapColumns(resolver, level);
        }
    }

    @Override
    public Expression optimize(Session session) {
        if (over != null) {
            ArrayList<SelectOrderBy> orderBy = over.getOrderBy();
            if (orderBy != null) {
                overOrderBySort = createOrder(session, orderBy, getNumExpressions());
            }
        }
        return this;
    }

    @Override
    public void setEvaluatable(TableFilter tableFilter, boolean b) {
        if (filterCondition != null) {
            filterCondition.setEvaluatable(tableFilter, b);
        }
        if (over != null) {
            over.setEvaluatable(tableFilter, b);
        }
    }

    @Override
    public void updateAggregate(Session session, int stage) {
        if (stage == Aggregate.STAGE_RESET) {
            updateSubAggregates(session, Aggregate.STAGE_RESET);
            lastGroupRowId = 0;
            return;
        }
        boolean window = stage == Aggregate.STAGE_WINDOW;
        if (window != (over != null)) {
            if (!window && select.isWindowQuery()) {
                updateSubAggregates(session, stage);
            }
            return;
        }
        // TODO aggregates: check nested MIN(MAX(ID)) and so on
        // if (on != null) {
        // on.updateAggregate();
        // }
        SelectGroups groupData = select.getGroupDataIfCurrent(window);
        if (groupData == null) {
            // this is a different level (the enclosing query)
            return;
        }

        int groupRowId = groupData.getCurrentGroupRowId();
        if (lastGroupRowId == groupRowId) {
            // already visited
            return;
        }
        lastGroupRowId = groupRowId;

        if (over != null) {
            if (!select.isGroupQuery()) {
                over.updateAggregate(session, stage);
            }
        }
        if (filterCondition != null) {
            if (!filterCondition.getBooleanValue(session)) {
                return;
            }
        }
        if (over != null) {
            ArrayList<SelectOrderBy> orderBy = over.getOrderBy();
            if (orderBy != null) {
                updateOrderedAggregate(session, groupData, groupRowId, orderBy);
                return;
            }
        }
        updateAggregate(session, getData(session, groupData, false, false));
    }

    private void updateSubAggregates(Session session, int stage) {
        updateGroupAggregates(session, stage);
        if (filterCondition != null) {
            filterCondition.updateAggregate(session, stage);
        }
        if (over != null) {
            over.updateAggregate(session, stage);
        }
    }

    /**
     * Updates an aggregate value.
     *
     * @param session
     *            the session
     * @param aggregateData
     *            aggregate data
     */
    protected abstract void updateAggregate(Session session, Object aggregateData);

    /**
     * Invoked when processing group stage of grouped window queries to update
     * arguments of this aggregate.
     *
     * @param session
     *            the session
     * @param stage
     *            select stage
     */
    protected abstract void updateGroupAggregates(Session session, int stage);

    /**
     * Returns the number of expressions, excluding FILTER and OVER clauses.
     *
     * @return the number of expressions
     */
    protected abstract int getNumExpressions();

    /**
     * Stores current values of expressions into the specified array.
     *
     * @param session
     *            the session
     * @param array
     *            array to store values of expressions
     */
    protected abstract void rememberExpressions(Session session, Value[] array);

    /**
     * Updates the provided aggregate data from the remembered expressions.
     *
     * @param session
     *            the session
     * @param aggregateData
     *            aggregate data
     * @param array
     *            values of expressions
     */
    protected abstract void updateFromExpressions(Session session, Object aggregateData, Value[] array);

    protected Object getData(Session session, SelectGroups groupData, boolean ifExists, boolean forOrderBy) {
        Object data;
        if (over != null) {
            ValueArray key = over.getCurrentKey(session);
            if (key != null) {
                @SuppressWarnings("unchecked")
                ValueHashMap<Object> map = (ValueHashMap<Object>) groupData.getCurrentGroupExprData(this, true);
                if (map == null) {
                    if (ifExists) {
                        return null;
                    }
                    map = new ValueHashMap<>();
                    groupData.setCurrentGroupExprData(this, map, true);
                }
                PartitionData partition = (PartitionData) map.get(key);
                if (partition == null) {
                    if (ifExists) {
                        return null;
                    }
                    data = forOrderBy ? new ArrayList<>() : createAggregateData();
                    map.put(key, new PartitionData(data));
                } else {
                    data = partition.getData();
                }
            } else {
                PartitionData partition = (PartitionData) groupData.getCurrentGroupExprData(this, true);
                if (partition == null) {
                    if (ifExists) {
                        return null;
                    }
                    data = forOrderBy ? new ArrayList<>() : createAggregateData();
                    groupData.setCurrentGroupExprData(this, new PartitionData(data), true);
                } else {
                    data = partition.getData();
                }
            }
        } else {
            data = groupData.getCurrentGroupExprData(this, false);
            if (data == null) {
                if (ifExists) {
                    return null;
                }
                data = forOrderBy ? new ArrayList<>() : createAggregateData();
                groupData.setCurrentGroupExprData(this, data, false);
            }
        }
        return data;
    }

    protected abstract Object createAggregateData();

    @Override
    public boolean isEverything(ExpressionVisitor visitor) {
        if (over == null) {
            return true;
        }
        switch (visitor.getType()) {
        case ExpressionVisitor.QUERY_COMPARABLE:
        case ExpressionVisitor.OPTIMIZABLE_MIN_MAX_COUNT_ALL:
        case ExpressionVisitor.DETERMINISTIC:
        case ExpressionVisitor.INDEPENDENT:
            return false;
        case ExpressionVisitor.EVALUATABLE:
        case ExpressionVisitor.READONLY:
        case ExpressionVisitor.NOT_FROM_RESOLVER:
        case ExpressionVisitor.GET_DEPENDENCIES:
        case ExpressionVisitor.SET_MAX_DATA_MODIFICATION_ID:
        case ExpressionVisitor.GET_COLUMNS1:
        case ExpressionVisitor.GET_COLUMNS2:
            return true;
        default:
            throw DbException.throwInternalError("type=" + visitor.getType());
        }
    }

    @Override
    public Value getValue(Session session) {
        SelectGroups groupData = select.getGroupDataIfCurrent(over != null);
        if (groupData == null) {
            throw DbException.get(ErrorCode.INVALID_USE_OF_AGGREGATE_FUNCTION_1, getSQL());
        }
        return over == null ? getAggregatedValue(session, getData(session, groupData, true, false))
                : getWindowResult(session, groupData);
    }

    private Value getWindowResult(Session session, SelectGroups groupData) {
        PartitionData partition;
        Object data;
        boolean forOrderBy = over.getOrderBy() != null;
        ValueArray key = over.getCurrentKey(session);
        if (key != null) {
            @SuppressWarnings("unchecked")
            ValueHashMap<Object> map = (ValueHashMap<Object>) groupData.getCurrentGroupExprData(this, true);
            if (map == null) {
                map = new ValueHashMap<>();
                groupData.setCurrentGroupExprData(this, map, true);
            }
            partition = (PartitionData) map.get(key);
            if (partition == null) {
                data = forOrderBy ? new ArrayList<>() : createAggregateData();
                partition = new PartitionData(data);
                map.put(key, partition);
            } else {
                data = partition.getData();
            }
        } else {
            partition = (PartitionData) groupData.getCurrentGroupExprData(this, true);
            if (partition == null) {
                data = forOrderBy ? new ArrayList<>() : createAggregateData();
                partition = new PartitionData(data);
                groupData.setCurrentGroupExprData(this, partition, true);
            } else {
                data = partition.getData();
            }
        }
        if (over.getOrderBy() != null) {
            return getOrderedResult(session, groupData, partition, data);
        }
        Value result = partition.getResult();
        if (result == null) {
            result = getAggregatedValue(session, data);
            partition.setResult(result);
        }
        return result;
    }

    /***
     * Returns aggregated value.
     *
     * @param session
     *            the session
     * @param aggregateData
     *            the aggregate data
     * @return aggregated value.
     */
    protected abstract Value getAggregatedValue(Session session, Object aggregateData);

    private void updateOrderedAggregate(Session session, SelectGroups groupData, int groupRowId,
            ArrayList<SelectOrderBy> orderBy) {
        int ne = getNumExpressions();
        int size = orderBy.size();
        Value[] array = new Value[ne + size + 1];
        rememberExpressions(session, array);
        for (int i = 0; i < size; i++) {
            SelectOrderBy o = orderBy.get(i);
            array[ne++] = o.expression.getValue(session);
        }
        array[ne] = ValueInt.get(groupRowId);
        @SuppressWarnings("unchecked")
        ArrayList<Value[]> data = (ArrayList<Value[]>) getData(session, groupData, false, true);
        data.add(array);
        return;
    }

    private Value getOrderedResult(Session session, SelectGroups groupData, PartitionData partition, Object data) {
        HashMap<Integer, Value> result = partition.getOrderedResult();
        if (result == null) {
            result = new HashMap<>();
            @SuppressWarnings("unchecked")
            ArrayList<Value[]> orderedData = (ArrayList<Value[]>) data;
            int ne = getNumExpressions();
            int rowIdColumn = ne + over.getOrderBy().size();
            Collections.sort(orderedData, overOrderBySort);
            getOrderedResultLoop(session, result, orderedData, rowIdColumn);
            partition.setOrderedResult(result);
        }
        return result.get(groupData.getCurrentGroupRowId());
    }

    /**
     * @param session
     *            the session
     * @param result
     *            the map to append result to
     * @param ordered
     *            ordered data
     * @param rowIdColumn
     *            the index of row id value
     */
    protected void getOrderedResultLoop(Session session, HashMap<Integer, Value> result, ArrayList<Value[]> ordered,
            int rowIdColumn) {
        Object aggregateData = createAggregateData();
        for (Value[] row : ordered) {
            updateFromExpressions(session, aggregateData, row);
            result.put(row[rowIdColumn].getInt(), getAggregatedValue(session, aggregateData));
        }
    }

    protected StringBuilder appendTailConditions(StringBuilder builder) {
        if (filterCondition != null) {
            builder.append(" FILTER (WHERE ").append(filterCondition.getSQL()).append(')');
        }
        if (over != null) {
            builder.append(' ').append(over.getSQL());
        }
        return builder;
    }

}
