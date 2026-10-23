package com.mall.core.mapper;

import com.mall.core.model.vo.TableStatVO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 数据维护页用的行数统计。
 *
 * <p>只统计会持续增长的业务表，配置类小表没意义；行数走 COUNT(*)，数据量大了要换成
 * information_schema 的估算值（这里是有意选择精确值，方便对账）。
 */
@Mapper
public interface MaintenanceMapper {

    @Select("SELECT 't_order' AS name, COUNT(*) AS row_count FROM t_order WHERE deleted = 0 "
            + "UNION ALL SELECT 't_order_item', COUNT(*) FROM t_order_item WHERE deleted = 0 "
            + "UNION ALL SELECT 't_review', COUNT(*) FROM t_review WHERE deleted = 0 "
            + "UNION ALL SELECT 't_search_log', COUNT(*) FROM t_search_log "
            + "UNION ALL SELECT 't_card_key', COUNT(*) FROM t_card_key WHERE deleted = 0 "
            + "UNION ALL SELECT 't_digital_grant', COUNT(*) FROM t_digital_grant WHERE deleted = 0 "
            + "UNION ALL SELECT 't_favorite', COUNT(*) FROM t_favorite WHERE deleted = 0 "
            + "UNION ALL SELECT 't_admin_audit_log', COUNT(*) FROM t_admin_audit_log "
            + "UNION ALL SELECT 't_user', COUNT(*) FROM t_user WHERE deleted = 0")
    List<TableStatVO> tableRows();
}
