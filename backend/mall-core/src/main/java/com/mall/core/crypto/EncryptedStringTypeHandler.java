package com.mall.core.crypto;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * MyBatis 的「透明加解密」字段处理器：写入时自动加密、读出时自动解密。
 *
 * <p>用法是在实体字段上标注 {@code @TableField(typeHandler = EncryptedStringTypeHandler.class)}，
 * 并且实体类上必须有 {@code @TableName(value = "...", autoResultMap = true)} ——
 * 少了 {@code autoResultMap} 时 MyBatis-Plus 不会把 typeHandler 用进 **查询** 的结果映射，
 * 于是写进去是密文、读出来还是密文（这个坑很容易踩，见 docs §8）。
 *
 * <p>注意：加密后**不能再按这些字段做等值/模糊查询**（密文带随机 IV，同明文也不同密文）。
 * 本项目正好没有这类查询；将来要按手机号查，必须额外加一列「盲索引」（HMAC 后建索引），不能直接 WHERE。
 */
public class EncryptedStringTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, AesGcmCipher.require().encrypt(parameter));
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return AesGcmCipher.require().decrypt(rs.getString(columnName));
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return AesGcmCipher.require().decrypt(rs.getString(columnIndex));
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return AesGcmCipher.require().decrypt(cs.getString(columnIndex));
    }
}
