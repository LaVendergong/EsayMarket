package com.mall.core.mapper;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface SearchLogMapper {

    @Insert("INSERT INTO t_search_log (keyword, create_time) VALUES (#{keyword}, NOW())")
    int insert(@Param("keyword") String keyword);

    /** 热词：区间内被搜次数最多的关键词 */
    @Select("SELECT keyword FROM t_search_log WHERE create_time >= #{from} "
            + "GROUP BY keyword ORDER BY COUNT(*) DESC, MAX(create_time) DESC LIMIT #{limit}")
    List<String> hotKeywords(@Param("from") LocalDateTime from, @Param("limit") int limit);

    /** 清理：删掉某时间点之前的搜索日志（这张表只增不删会变成库里最大的表） */
    @Delete("DELETE FROM t_search_log WHERE create_time < #{before}")
    int deleteBefore(@Param("before") LocalDateTime before);
}
