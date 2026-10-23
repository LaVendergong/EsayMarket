package com.mall.core.service;

import com.mall.core.model.vo.DailyStatVO;
import com.mall.core.model.vo.ReportSummaryVO;
import com.mall.core.model.vo.TopProductVO;

import java.util.List;

/** 销售报表（后台） */
public interface ReportService {

    /** days = 近 N 天（含今天） */
    ReportSummaryVO summary(int days);

    List<DailyStatVO> daily(int days);

    List<TopProductVO> topProducts(int days, int limit);
}
