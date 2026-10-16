package com.mall.common.result;

import lombok.Data;

import java.io.Serializable;
import java.util.Collections;
import java.util.List;

/**
 * 分页结果
 */
@Data
public class PageResult<T> implements Serializable {

    private long total;
    private long pageNum;
    private long pageSize;
    private List<T> list;

    public static <T> PageResult<T> of(long total, long pageNum, long pageSize, List<T> list) {
        PageResult<T> p = new PageResult<>();
        p.setTotal(total);
        p.setPageNum(pageNum);
        p.setPageSize(pageSize);
        p.setList(list == null ? Collections.emptyList() : list);
        return p;
    }

    public static <T> PageResult<T> empty(long pageNum, long pageSize) {
        return of(0, pageNum, pageSize, Collections.emptyList());
    }
}
