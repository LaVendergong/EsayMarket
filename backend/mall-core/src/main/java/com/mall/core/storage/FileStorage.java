package com.mall.core.storage;

/**
 * 文件存储。
 *
 * <p>现在只有 {@link LocalFileStorage}（本机磁盘），后期换对象存储时新增一个实现即可，
 * 上层（评价晒图）只依赖这个接口和它返回的 URL。
 */
public interface FileStorage {

    /**
     * 保存图片并返回可公开访问的 URL。
     *
     * @param bytes        文件内容（已在入口处做过大小限制）
     * @param originalName 原始文件名，只用来取扩展名
     * @param contentType  MIME，用于二次校验
     */
    String saveImage(byte[] bytes, String originalName, String contentType);

    /** 删除（当前版本用于上传后校验失败的回滚，或替换图片） */
    void delete(String url);
}
