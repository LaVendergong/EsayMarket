package com.mall.core.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.mall.core.entity.Favorite;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface FavoriteMapper extends BaseMapper<Favorite> {

    /** 收藏：已存在（含被逻辑删除的）就复活，避免撞唯一键 */
    @Insert("INSERT INTO t_favorite (user_id, product_id, create_time, update_time, deleted) "
            + "VALUES (#{userId}, #{productId}, NOW(), NOW(), 0) "
            + "ON DUPLICATE KEY UPDATE deleted = 0, update_time = NOW()")
    int favorite(@Param("userId") Long userId, @Param("productId") Long productId);

    @Update("UPDATE t_favorite SET deleted = 1, update_time = NOW() "
            + "WHERE user_id = #{userId} AND product_id = #{productId} AND deleted = 0")
    int unfavorite(@Param("userId") Long userId, @Param("productId") Long productId);

    @Select("SELECT COUNT(*) FROM t_favorite WHERE user_id = #{userId} AND product_id = #{productId} AND deleted = 0")
    long countOne(@Param("userId") Long userId, @Param("productId") Long productId);
}
