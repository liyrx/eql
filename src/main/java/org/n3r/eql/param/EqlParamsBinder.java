package org.n3r.eql.param;

import com.google.common.base.Objects;
import org.n3r.eql.base.ExpressionEvaluator;
import org.n3r.eql.ex.EqlExecuteException;
import org.n3r.eql.map.EqlRun;
import org.n3r.eql.util.EqlUtils;
import org.slf4j.Logger;

import java.sql.*;
import java.util.Date;

public class EqlParamsBinder {
    private EqlRun eqlRun;
    private StringBuilder boundParams;
    private PreparedStatement ps;

    private static enum ParamExtra {
        Extra, Normal
    }

    public void bindParams(PreparedStatement ps, EqlRun eqlRun, Logger logger) {
        this.eqlRun = eqlRun;
        boundParams = new StringBuilder();
        this.ps = ps;

        switch (eqlRun.getPlaceHolderType()) {
            case AUTO_SEQ:
                for (int i = 0; i < eqlRun.getPlaceholderNum(); ++i)
                    setParam(i, getParamByIndex(i), ParamExtra.Normal);
                break;
            case MANU_SEQ:
                for (int i = 0; i < eqlRun.getPlaceholderNum(); ++i)
                    setParam(i, findParamBySeq(i + 1), ParamExtra.Normal);
                break;
            case VAR_NAME:
                for (int i = 0; i < eqlRun.getPlaceholderNum(); ++i)
                    setParam(i, findParamByName(i), ParamExtra.Normal);
                break;
            default:
                break;
        }

        bindExtraParams();

        if (boundParams.length() > 0) logger.debug("param: {}", boundParams);
    }

    private void bindExtraParams() {
        Object[] extraBindParams = eqlRun.getExtraBindParams();
        if (extraBindParams == null) return;

        for (int i = eqlRun.getPlaceholderNum(); i < eqlRun.getPlaceholderNum() + extraBindParams.length; ++i)
            setParam(i, extraBindParams[i - eqlRun.getPlaceholderNum()], ParamExtra.Extra);
    }

    private void setParam(int index, Object value, ParamExtra extra) {
        EqlParamPlaceholder placeHolder = eqlRun.getPlaceHolder(index);
        try {
            switch (extra) {
                case Extra:
                    setParamExtra(placeHolder, index, value);
                    break;
                default:
                    setParamEx(placeHolder, index, value);
                    break;
            }
        } catch (SQLException e) {
            throw new EqlExecuteException("set parameters fail", e);
        }
    }

    private void setParamExtra(EqlParamPlaceholder placeHolder, int index, Object value) throws SQLException {
        if (value instanceof Date) {
            Timestamp date = new Timestamp(((Date) value).getTime());
            ps.setTimestamp(index + 1, date);
            boundParams.append('[').append(EqlUtils.toDateTimeStr(date)).append(']');
        } else {
            Object paramValue = value;
            if (placeHolder != null && placeHolder.getOption().contains("LOB") && value instanceof String)
                paramValue = EqlUtils.toBytes((String) value);

            ps.setObject(index + 1, paramValue);
            boundParams.append('[').append(value).append(']');
        }
    }

    private void setParamEx(EqlParamPlaceholder placeHolder, int index, Object value) throws SQLException {
        if (regiesterOut(index)) return;

        setParamExtra(placeHolder, index, value);
    }

    private boolean regiesterOut(int index) throws SQLException {
        EqlParamPlaceholder.InOut inOut = eqlRun.getPlaceHolders()[index].getInOut();
        if (EqlUtils.isProcedure(eqlRun.getSqlType()) && inOut != EqlParamPlaceholder.InOut.IN)
            ((CallableStatement) ps).registerOutParameter(index + 1, Types.VARCHAR);

        return inOut == EqlParamPlaceholder.InOut.OUT;
    }

    private Object findParamByName(int index) {
        String varName = eqlRun.getPlaceHolders()[index].getPlaceholder();

        ExpressionEvaluator evaluator = eqlRun.getEqlConfig().getExpressionEvaluator();

        Object property = evaluator.eval(varName, eqlRun);

        if (property != null) return property;

        String propertyName = EqlUtils.convertUnderscoreNameToPropertyName(varName);
        return Objects.equal(propertyName, varName) ? property : evaluator.eval(propertyName, eqlRun);
    }


    private Object getParamByIndex(int index) {
        EqlParamPlaceholder[] placeHolders = eqlRun.getPlaceHolders();
        if (index < placeHolders.length && EqlUtils.isProcedure(eqlRun.getSqlType())
                && placeHolders[index].getInOut() == EqlParamPlaceholder.InOut.OUT) return null;

        Object[] params = eqlRun.getParams();
        if (params != null && index < params.length)
            return params[index];

        throw new EqlExecuteException("[" + eqlRun.getSqlId() + "]执行过程中缺少参数");
    }

    private Object findParamBySeq(int index) {
        return getParamByIndex(eqlRun.getPlaceHolders()[index - 1].getSeq() - 1);
    }


}
