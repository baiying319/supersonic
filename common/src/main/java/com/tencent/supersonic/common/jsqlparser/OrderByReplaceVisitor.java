package com.tencent.supersonic.common.jsqlparser;

import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.OrderByVisitorAdapter;

import java.util.Map;

public class OrderByReplaceVisitor extends OrderByVisitorAdapter {

    ParseVisitorHelper parseVisitorHelper = new ParseVisitorHelper();
    private Map<String, String> fieldNameMap;
    private boolean exactReplace;

    public OrderByReplaceVisitor(Map<String, String> fieldNameMap, boolean exactReplace) {
        this.fieldNameMap = fieldNameMap;
        this.exactReplace = exactReplace;
    }

    @Override
    public void visit(OrderByElement orderBy) {
        Expression expression = orderBy.getExpression();
        if (expression instanceof Column) {
            parseVisitorHelper.replaceColumn((Column) expression, fieldNameMap, exactReplace);
        }
        if (expression instanceof Function) {
            Function function = (Function) expression;
            // List<Expression> expressions = function.getParameters().getExpressions();
            ExpressionList<?> expressions = function.getParameters();
            for (Expression column : expressions) {
                if (column instanceof Column) {
                    parseVisitorHelper.replaceColumn((Column) column, fieldNameMap, exactReplace);
                }
            }
        }
        super.visit(orderBy);
    }
}
