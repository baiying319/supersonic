package com.tencent.supersonic.common.jsqlparser;

import com.tencent.supersonic.common.pojo.enums.TimeDimensionEnum;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.ComparisonOperator;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.select.GroupByElement;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SelectVisitorAdapter;
import net.sf.jsqlparser.statement.select.SetOperationList;
import org.apache.commons.lang3.StringUtils;
import org.springframework.util.CollectionUtils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Sql Parser add Helper */
@Slf4j
public class SqlAddHelper {

    public static String addFieldsToSelect(String sql, List<String> fields) {
        Select selectStatement = SqlSelectHelper.getSelect(sql);
        // add fields to select
        if (selectStatement == null) {
            return null;
        }
        if (selectStatement instanceof PlainSelect) {
            PlainSelect plainSelect = (PlainSelect) selectStatement;
            fields.stream()
                    .filter(Objects::nonNull)
                    .forEach(
                            field -> {
                                SelectItem<Column> selectExpressionItem =
                                        new SelectItem(new Column(field));
                                plainSelect.addSelectItems(selectExpressionItem);
                            });

        } else if (selectStatement instanceof SetOperationList) {
            SetOperationList setOperationList = (SetOperationList) selectStatement;
            if (!CollectionUtils.isEmpty(setOperationList.getSelects())) {
                setOperationList
                        .getSelects()
                        .forEach(
                                subSelectBody -> {
                                    PlainSelect subPlainSelect = (PlainSelect) subSelectBody;
                                    fields.stream()
                                            .forEach(
                                                    field -> {
                                                        SelectItem<Column> selectExpressionItem =
                                                                new SelectItem(new Column(field));
                                                        subPlainSelect.addSelectItems(
                                                                selectExpressionItem);
                                                    });
                                });
            }
        }
        return selectStatement.toString();
    }

    public static String addFunctionToSelect(String sql, List<Expression> expressionList) {
        Select selectStatement = SqlSelectHelper.getSelect(sql);
        if (selectStatement == null) {
            return null;
        }

        List<PlainSelect> plainSelectList = new ArrayList<>();
        if (selectStatement instanceof PlainSelect) {
            PlainSelect plainSelect = (PlainSelect) selectStatement.getPlainSelect();
            plainSelectList.add(plainSelect);
        } else if (selectStatement instanceof SetOperationList) {
            SetOperationList setOperationList =
                    (SetOperationList) selectStatement.getSetOperationList();
            if (!CollectionUtils.isEmpty(setOperationList.getSelects())) {
                setOperationList
                        .getSelects()
                        .forEach(
                                subSelectBody -> {
                                    PlainSelect subPlainSelect = (PlainSelect) subSelectBody;
                                    plainSelectList.add(subPlainSelect);
                                });
            }
        }

        if (CollectionUtils.isEmpty(plainSelectList)) {
            return sql;
        }
        for (PlainSelect plainSelect : plainSelectList) {
            List<SelectItem<?>> selectItems = plainSelect.getSelectItems();
            if (CollectionUtils.isEmpty(selectItems)) {
                continue;
            }
            boolean existFunction = false;
            for (Expression expression : expressionList) {
                for (SelectItem selectItem : selectItems) {
                    if (selectItem.getExpression() instanceof Function) {
                        Function expressionFunction = (Function) selectItem.getExpression();
                        if (expression.toString().equalsIgnoreCase(expressionFunction.toString())) {
                            existFunction = true;
                            break;
                        }
                    }
                }
                if (!existFunction) {
                    SelectItem sumExpressionItem = new SelectItem(expression);
                    selectItems.add(sumExpressionItem);
                }
            }
        }
        return selectStatement.toString();
    }

    public static String addWhere(String sql, String column, Object value) {
        if (StringUtils.isEmpty(column) || Objects.isNull(value)) {
            return sql;
        }
        Select selectStatement = SqlSelectHelper.getSelect(sql);

        if (!(selectStatement instanceof PlainSelect)) {
            return sql;
        }
        PlainSelect plainSelect = (PlainSelect) selectStatement;
        Expression where = plainSelect.getWhere();

        Expression right = new StringValue(value.toString());
        if (value instanceof Integer || value instanceof Long) {
            right = new LongValue(value.toString());
        }

        if (where == null) {
            plainSelect.setWhere(new EqualsTo(new Column(column), right));
        } else {
            plainSelect.setWhere(new AndExpression(where, new EqualsTo(new Column(column), right)));
        }
        return selectStatement.toString();
    }

    public static String addWhere(String sql, Expression expression) {
        Select selectStatement = SqlSelectHelper.getSelect(sql);

        if (!(selectStatement instanceof PlainSelect)) {
            return sql;
        }
        PlainSelect plainSelect = (PlainSelect) selectStatement;
        List<String> chNameList = TimeDimensionEnum.getChNameList();
        Boolean dateWhere = false;
        for (String chName : chNameList) {
            if (expression.toString().contains(chName)) {
                dateWhere = true;
            }
        }
        List<PlainSelect> plainSelectList = SqlSelectHelper.getWithItem(selectStatement);
        if (!CollectionUtils.isEmpty(plainSelectList) && dateWhere) {
            List<String> withNameList = SqlSelectHelper.getWithName(sql);
            for (int i = 0; i < plainSelectList.size(); i++) {
                if (plainSelectList.get(i).getFromItem() instanceof Table) {
                    Table table = (Table) plainSelectList.get(i).getFromItem();
                    if (withNameList.contains(table.getName())) {
                        continue;
                    }
                }
                Set<String> result = new HashSet<>();
                List<PlainSelect> subPlainSelectList = new ArrayList<>();
                subPlainSelectList.add(plainSelectList.get(i));
                SqlSelectHelper.getWhereFields(subPlainSelectList, result);
                if (TimeDimensionEnum.containsZhTimeDimension(new ArrayList<>(result))) {
                    continue;
                }
                Expression subWhere = plainSelectList.get(i).getWhere();
                addWhere(plainSelectList.get(i), subWhere, expression);
            }
            return selectStatement.toString();
        }
        if (plainSelect.getFromItem() instanceof ParenthesedSelect && dateWhere) {
            ParenthesedSelect parenthesedSelect = (ParenthesedSelect) plainSelect.getFromItem();
            PlainSelect subPlainSelect = parenthesedSelect.getPlainSelect();
            Expression subWhere = subPlainSelect.getWhere();
            addWhere(subPlainSelect, subWhere, expression);
            return selectStatement.toString();
        }
        Expression where = plainSelect.getWhere();

        addWhere(plainSelect, where, expression);
        return selectStatement.toString();
    }

    private static void addWhere(PlainSelect plainSelect, Expression where, Expression expression) {
        if (where == null) {
            plainSelect.setWhere(expression);
        } else {
            plainSelect.setWhere(new AndExpression(where, expression));
        }
    }

    public static String addWhere(String sql, List<Expression> expressionList) {
        Select selectStatement = SqlSelectHelper.getSelect(sql);

        if (!(selectStatement instanceof PlainSelect)) {
            return sql;
        }
        if (CollectionUtils.isEmpty(expressionList)) {
            return sql;
        }
        Expression expression = expressionList.get(0);
        for (int i = 1; i < expressionList.size(); i++) {
            expression = new AndExpression(expression, expressionList.get(i));
        }
        PlainSelect plainSelect = (PlainSelect) selectStatement;
        Expression where = plainSelect.getWhere();

        if (where == null) {
            plainSelect.setWhere(expression);
        } else {
            plainSelect.setWhere(new AndExpression(where, expression));
        }
        return selectStatement.toString();
    }

    public static String addAggregateToField(String sql, Map<String, String> fieldNameToAggregate) {
        Select selectStatement = SqlSelectHelper.getSelect(sql);

        if (!(selectStatement instanceof PlainSelect)) {
            return sql;
        }
        selectStatement.accept(
                new SelectVisitorAdapter() {
                    @Override
                    public void visit(PlainSelect plainSelect) {
                        addAggregateToSelectItems(
                                plainSelect.getSelectItems(), fieldNameToAggregate);
                        addAggregateToOrderByItems(
                                plainSelect.getOrderByElements(), fieldNameToAggregate);
                        addAggregateToGroupByItems(plainSelect.getGroupBy(), fieldNameToAggregate);
                        addAggregateToWhereItems(plainSelect.getWhere(), fieldNameToAggregate);
                    }
                });
        return selectStatement.toString();
    }

    public static String addGroupBy(String sql, Set<String> groupByFields) {
        if (CollectionUtils.isEmpty(groupByFields)) {
            return sql;
        }
        Select selectStatement = SqlSelectHelper.getSelect(sql);

        if (!(selectStatement instanceof PlainSelect)) {
            return sql;
        }

        PlainSelect plainSelect = (PlainSelect) selectStatement;
        GroupByElement groupByElement = new GroupByElement();
        List<String> originalGroupByFields = SqlSelectHelper.getGroupByFields(sql);
        if (!CollectionUtils.isEmpty(originalGroupByFields)) {
            groupByFields.addAll(originalGroupByFields);
        }
        for (String groupByField : groupByFields) {
            groupByElement.addGroupByExpression(new Column(groupByField));
        }
        plainSelect.setGroupByElement(groupByElement);
        return selectStatement.toString();
    }

    private static void addAggregateToSelectItems(
            List<SelectItem<?>> selectItems, Map<String, String> fieldNameToAggregate) {
        for (SelectItem selectItem : selectItems) {
            Expression expression = selectItem.getExpression();
            Function function =
                    SqlSelectFunctionHelper.getFunction(expression, fieldNameToAggregate);
            if (function == null) {
                continue;
            }
            selectItem.setExpression(function);
        }
    }

    private static void addAggregateToOrderByItems(
            List<OrderByElement> orderByElements, Map<String, String> fieldNameToAggregate) {
        if (orderByElements == null) {
            return;
        }
        for (OrderByElement orderByElement : orderByElements) {
            Expression expression = orderByElement.getExpression();
            Function function =
                    SqlSelectFunctionHelper.getFunction(expression, fieldNameToAggregate);
            if (function == null) {
                continue;
            }
            orderByElement.setExpression(function);
        }
    }

    private static void addAggregateToGroupByItems(
            GroupByElement groupByElement, Map<String, String> fieldNameToAggregate) {
        if (groupByElement == null) {
            return;
        }
        for (int i = 0; i < groupByElement.getGroupByExpressionList().size(); i++) {
            Expression expression = (Expression) groupByElement.getGroupByExpressionList().get(i);
            Function function =
                    SqlSelectFunctionHelper.getFunction(expression, fieldNameToAggregate);
            if (function == null) {
                continue;
            }
            groupByElement.addGroupByExpression(function);
        }
    }

    private static void addAggregateToWhereItems(
            Expression whereExpression, Map<String, String> fieldNameToAggregate) {
        if (whereExpression == null) {
            return;
        }
        modifyWhereExpression(whereExpression, fieldNameToAggregate);
    }

    private static void modifyWhereExpression(
            Expression whereExpression, Map<String, String> fieldNameToAggregate) {
        if (SqlSelectHelper.isLogicExpression(whereExpression)) {
            if (whereExpression instanceof AndExpression) {
                AndExpression andExpression = (AndExpression) whereExpression;
                Expression leftExpression = andExpression.getLeftExpression();
                Expression rightExpression = andExpression.getRightExpression();
                modifyWhereExpression(leftExpression, fieldNameToAggregate);
                modifyWhereExpression(rightExpression, fieldNameToAggregate);
            }
            if (whereExpression instanceof OrExpression) {
                OrExpression orExpression = (OrExpression) whereExpression;
                Expression leftExpression = orExpression.getLeftExpression();
                Expression rightExpression = orExpression.getRightExpression();
                modifyWhereExpression(leftExpression, fieldNameToAggregate);
                modifyWhereExpression(rightExpression, fieldNameToAggregate);
            }
        } else if (whereExpression instanceof Parenthesis) {
            modifyWhereExpression(
                    ((Parenthesis) whereExpression).getExpression(), fieldNameToAggregate);
        } else {
            setAggToFunction(whereExpression, fieldNameToAggregate);
        }
    }

    private static void setAggToFunction(
            Expression expression, Map<String, String> fieldNameToAggregate) {
        if (!(expression instanceof ComparisonOperator)) {
            return;
        }
        ComparisonOperator comparisonOperator = (ComparisonOperator) expression;
        if (comparisonOperator.getRightExpression() instanceof Column) {
            String columnName =
                    ((Column) (comparisonOperator).getRightExpression()).getColumnName();
            Function function =
                    SqlSelectFunctionHelper.getFunction(
                            comparisonOperator.getRightExpression(),
                            fieldNameToAggregate.get(columnName));
            if (Objects.nonNull(function)) {
                comparisonOperator.setRightExpression(function);
            }
        }
        if (comparisonOperator.getLeftExpression() instanceof Column) {
            String columnName = ((Column) (comparisonOperator).getLeftExpression()).getColumnName();
            Function function =
                    SqlSelectFunctionHelper.getFunction(
                            comparisonOperator.getLeftExpression(),
                            fieldNameToAggregate.get(columnName));
            if (Objects.nonNull(function)) {
                comparisonOperator.setLeftExpression(function);
            }
        }
    }

    public static String addHaving(String sql, Set<String> fieldNames) {
        Select selectStatement = SqlSelectHelper.getSelect(sql);

        if (!(selectStatement instanceof PlainSelect)) {
            return sql;
        }

        PlainSelect plainSelect = (PlainSelect) selectStatement;
        // replace metric to 1 and 1 and add having metric
        Expression where = plainSelect.getWhere();
        FiledFilterReplaceVisitor visitor = new FiledFilterReplaceVisitor(fieldNames);
        if (Objects.nonNull(where)) {
            where.accept(visitor);
        }
        List<Expression> waitingForAdds = visitor.getWaitingForAdds();
        if (!CollectionUtils.isEmpty(waitingForAdds)) {
            for (Expression waitingForAdd : waitingForAdds) {
                Expression having = plainSelect.getHaving();
                if (Objects.isNull(having)) {
                    plainSelect.setHaving(waitingForAdd);
                } else {
                    plainSelect.setHaving(new AndExpression(having, waitingForAdd));
                }
            }
        }
        return SqlRemoveHelper.removeNumberFilter(selectStatement.toString());
    }

    public static String addHaving(String sql, List<Expression> expressionList) {
        Select selectStatement = SqlSelectHelper.getSelect(sql);

        if (!(selectStatement instanceof PlainSelect)) {
            return sql;
        }
        if (CollectionUtils.isEmpty(expressionList)) {
            return sql;
        }
        Expression expression = expressionList.get(0);
        for (int i = 1; i < expressionList.size(); i++) {
            expression = new AndExpression(expression, expressionList.get(i));
        }
        PlainSelect plainSelect = (PlainSelect) selectStatement;
        Expression having = plainSelect.getHaving();

        if (having == null) {
            plainSelect.setHaving(expression);
        } else {
            plainSelect.setHaving(new AndExpression(having, expression));
        }
        return selectStatement.toString();
    }

    public static String addParenthesisToWhere(String sql) {
        Select selectStatement = SqlSelectHelper.getSelect(sql);

        if (!(selectStatement instanceof PlainSelect)) {
            return sql;
        }
        PlainSelect plainSelect = (PlainSelect) selectStatement;
        Expression where = plainSelect.getWhere();
        if (Objects.nonNull(where)) {
            Parenthesis parenthesis = new Parenthesis(where);
            plainSelect.setWhere(parenthesis);
        }
        return selectStatement.toString();
    }
}
