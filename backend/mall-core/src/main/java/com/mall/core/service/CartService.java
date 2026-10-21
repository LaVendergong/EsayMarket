package com.mall.core.service;

import com.mall.core.model.dto.CartAddDTO;
import com.mall.core.model.vo.CartVO;

import java.util.List;

public interface CartService {

    List<CartVO> list(Long userId);

    /** 加购；同一 SKU 已存在时累加数量 */
    Long add(Long userId, CartAddDTO dto);

    void updateQuantity(Long userId, Long id, Integer quantity);

    void updateChecked(Long userId, Long id, Integer checked);

    /** 全选 / 取消全选（一条 SQL，避免前端发 N 个请求） */
    void updateAllChecked(Long userId, Integer checked);

    void remove(Long userId, Long id);

    /** 清空选中项（下单成功后也会调用） */
    void clearChecked(Long userId);
}
