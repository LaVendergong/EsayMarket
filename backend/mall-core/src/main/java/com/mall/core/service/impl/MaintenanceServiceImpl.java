package com.mall.core.service.impl;

import com.mall.core.mapper.MaintenanceMapper;
import com.mall.core.model.vo.MaintenanceVO;
import com.mall.core.service.MaintenanceService;
import com.mall.core.task.SearchLogCleanTask;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MaintenanceServiceImpl implements MaintenanceService {

    private final MaintenanceMapper maintenanceMapper;

    @Value("${mall.search-log.retain-days:90}")
    private int retainDays;

    @Override
    public MaintenanceVO stats() {
        MaintenanceVO vo = new MaintenanceVO();
        vo.setTables(maintenanceMapper.tableRows());
        vo.setSearchLogRetainDays(retainDays);
        vo.setSearchLogLastCleanAt(SearchLogCleanTask.lastRunAt());
        vo.setSearchLogLastDeleted(SearchLogCleanTask.lastDeleted());
        return vo;
    }
}
