package com.mall.core.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.mall.common.exception.BusinessException;
import com.mall.common.result.PageResult;
import com.mall.common.result.ResultCode;
import com.mall.common.util.JwtUtil;
import com.mall.core.entity.User;
import com.mall.core.mapper.UserMapper;
import com.mall.core.model.dto.LoginDTO;
import com.mall.core.model.dto.RegisterDTO;
import com.mall.core.model.vo.LoginVO;
import com.mall.core.model.vo.UserVO;
import com.mall.core.security.JwtProperties;
import com.mall.core.security.LoginThrottle;
import com.mall.core.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    /** BCrypt 的有效长度上限（字节）：超过部分会被静默截断 */
    private static final int BCRYPT_MAX_BYTES = 72;

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtProperties jwtProperties;
    private final LoginThrottle loginThrottle;

    @Override
    public Long register(RegisterDTO dto) {
        Long exists = userMapper.selectCount(Wrappers.<User>lambdaQuery().eq(User::getUsername, dto.getUsername()));
        if (exists != null && exists > 0) {
            throw new BusinessException(ResultCode.USERNAME_EXISTS);
        }
        // BCrypt 只使用密码的前 72 字节，超出的部分会被静默丢弃 —— 也就是「设了长密码但后半段无效」。
        // 与其让用户以为更安全，不如直接拒绝（中文按 UTF-8 3 字节算，64 个汉字就超了）。
        if (dto.getPassword().getBytes(StandardCharsets.UTF_8).length > BCRYPT_MAX_BYTES) {
            throw new BusinessException(ResultCode.VALIDATE_FAILED,
                    "密码过长（最多 " + BCRYPT_MAX_BYTES + " 字节，一个汉字算 3 字节）");
        }

        User user = new User();
        user.setUsername(dto.getUsername());
        user.setPassword(passwordEncoder.encode(dto.getPassword()));
        user.setNickname(StringUtils.hasText(dto.getNickname()) ? dto.getNickname() : dto.getUsername());
        user.setPhone(dto.getPhone());
        user.setRole(0);
        user.setStatus(1);
        userMapper.insert(user);
        return user.getId();
    }

    @Override
    public LoginVO login(LoginDTO dto) {
        // 先看有没有被限流：已超限就别查库、别校验密码了（否则 BCrypt 反而成了压测入口）
        loginThrottle.checkAllowed(dto.getUsername());

        User user = userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, dto.getUsername()));
        if (user == null) {
            // 用户名不存在也计入失败：错误信息的差异会变成枚举用户名的探针
            loginThrottle.recordFailure(dto.getUsername());
            throw new BusinessException(ResultCode.PASSWORD_ERROR);
        }
        if (!passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            loginThrottle.recordFailure(dto.getUsername());
            throw new BusinessException(ResultCode.PASSWORD_ERROR);
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(ResultCode.ACCOUNT_DISABLED);
        }
        // 密码正确才清计数（禁用账号不清，避免被拿来刷掉别人的锁定状态）
        loginThrottle.reset(dto.getUsername());
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", user.getRole() == null ? 0 : user.getRole());
        claims.put("username", user.getUsername());
        String token = JwtUtil.create(jwtProperties.getSecret(), jwtProperties.getAccessExpireMillis(),
                claims, String.valueOf(user.getId()));

        return toLoginVO(user, token);
    }

    @Override
    public LoginVO refreshLogin(Long userId) {
        // 刷新时也要确认账号还存在且没被禁用 —— 否则一个被禁用的账号靠 refresh 能一直续命
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        if (user.getStatus() != null && user.getStatus() == 0) {
            throw new BusinessException(ResultCode.ACCOUNT_DISABLED);
        }
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", user.getRole() == null ? 0 : user.getRole());
        claims.put("username", user.getUsername());
        String token = JwtUtil.create(jwtProperties.getSecret(), jwtProperties.getAccessExpireMillis(),
                claims, String.valueOf(user.getId()));
        return toLoginVO(user, token);
    }

    private LoginVO toLoginVO(User user, String token) {
        LoginVO vo = new LoginVO();
        vo.setToken(token);
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setNickname(user.getNickname());
        vo.setAvatar(user.getAvatar());
        vo.setRole(user.getRole());
        return vo;
    }

    @Override
    public UserVO getById(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BusinessException(ResultCode.USER_NOT_FOUND);
        }
        return UserVO.from(user);
    }

    @Override
    public PageResult<UserVO> page(long pageNum, long pageSize, String keyword) {
        Page<User> page = new Page<>(pageNum, pageSize);
        userMapper.selectPage(page, Wrappers.<User>lambdaQuery()
                .like(StringUtils.hasText(keyword), User::getUsername, keyword)
                .orderByDesc(User::getId));
        List<UserVO> list = page.getRecords().stream().map(UserVO::from).toList();
        return PageResult.of(page.getTotal(), pageNum, pageSize, list);
    }
}
