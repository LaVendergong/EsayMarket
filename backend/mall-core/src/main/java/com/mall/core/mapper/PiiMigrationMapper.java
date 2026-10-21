package com.mall.core.mapper;

import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

/**
 * 存量明文 PII 的加密迁移用的裸 SQL。
 *
 * <p><b>为什么放在 {@code com.mall.core.mapper} 包</b>：{@code MybatisPlusConfig} 上是
 * {@code @MapperScan("com.mall.core.mapper")}，只有这个包会被注册成 Mapper；
 * 放到别的包（比如 crypto）拿不到 bean，启动时注入失败。
 *
 * <p><b>为什么用裸 SQL</b>：这些语句**刻意不经过** {@code EncryptedStringTypeHandler}，
 * 否则查出来的已经是解密后的值、永远判断不了「这行还没迁移」。
 *
 * <p>更新语句都带 {@code AND <列> = #{old}} 这个乐观条件：两个应用同时启动迁移时，
 * 后到的那次会命中 0 行直接跳过，不会把已经加密的值再加密一遍。
 */
public interface PiiMigrationMapper {

    @Select("SELECT id AS id, phone AS phone FROM t_user "
            + "WHERE phone IS NOT NULL AND phone <> '' AND phone NOT LIKE 'enc:v1:%'")
    List<Map<String, Object>> plainUserPhones();

    @Update("UPDATE t_user SET phone = #{enc} WHERE id = #{id} AND phone = #{old}")
    int updateUserPhone(@Param("id") long id, @Param("enc") String enc, @Param("old") String old);

    @Select("SELECT id AS id, receiver AS receiver, phone AS phone, detail AS detail FROM t_address "
            + "WHERE (receiver IS NOT NULL AND receiver <> '' AND receiver NOT LIKE 'enc:v1:%') "
            + "   OR (phone IS NOT NULL AND phone <> '' AND phone NOT LIKE 'enc:v1:%') "
            + "   OR (detail IS NOT NULL AND detail <> '' AND detail NOT LIKE 'enc:v1:%')")
    List<Map<String, Object>> plainAddresses();

    @Update("UPDATE t_address SET receiver = #{enc} WHERE id = #{id} AND receiver = #{old}")
    int updateAddressReceiver(@Param("id") long id, @Param("enc") String enc, @Param("old") String old);

    @Update("UPDATE t_address SET phone = #{enc} WHERE id = #{id} AND phone = #{old}")
    int updateAddressPhone(@Param("id") long id, @Param("enc") String enc, @Param("old") String old);

    @Update("UPDATE t_address SET detail = #{enc} WHERE id = #{id} AND detail = #{old}")
    int updateAddressDetail(@Param("id") long id, @Param("enc") String enc, @Param("old") String old);

    @Select("SELECT id AS id, receiver AS receiver, receiver_phone AS receiverPhone, "
            + "       receiver_addr AS receiverAddr FROM t_order "
            + "WHERE (receiver IS NOT NULL AND receiver <> '' AND receiver NOT LIKE 'enc:v1:%') "
            + "   OR (receiver_phone IS NOT NULL AND receiver_phone <> '' AND receiver_phone NOT LIKE 'enc:v1:%') "
            + "   OR (receiver_addr IS NOT NULL AND receiver_addr <> '' AND receiver_addr NOT LIKE 'enc:v1:%')")
    List<Map<String, Object>> plainOrders();

    @Update("UPDATE t_order SET receiver = #{enc} WHERE id = #{id} AND receiver = #{old}")
    int updateOrderReceiver(@Param("id") long id, @Param("enc") String enc, @Param("old") String old);

    @Update("UPDATE t_order SET receiver_phone = #{enc} WHERE id = #{id} AND receiver_phone = #{old}")
    int updateOrderReceiverPhone(@Param("id") long id, @Param("enc") String enc, @Param("old") String old);

    @Update("UPDATE t_order SET receiver_addr = #{enc} WHERE id = #{id} AND receiver_addr = #{old}")
    int updateOrderReceiverAddr(@Param("id") long id, @Param("enc") String enc, @Param("old") String old);
}
