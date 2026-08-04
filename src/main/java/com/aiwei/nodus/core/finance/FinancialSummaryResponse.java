package com.aiwei.nodus.core.finance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;

/** 指定阶段和币种内的现金流、最新账户余额与分类汇总。 */
public record FinancialSummaryResponse(Instant from, Instant to, String currency,
        BigDecimal income, BigDecimal expense, BigDecimal netCashFlow, BigDecimal savingsRate,
        BigDecimal assets, BigDecimal liabilities, BigDecimal netWorth,
        Map<String, BigDecimal> expenseByCategory, List<MonthlyCashFlow> monthlyCashFlow) {
    public record MonthlyCashFlow(YearMonth month, BigDecimal income, BigDecimal expense,
            BigDecimal netCashFlow) { }
}
