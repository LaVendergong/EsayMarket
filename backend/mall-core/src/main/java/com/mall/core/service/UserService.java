package com.mall.core.service;

import com.mall.common.result.PageResult;
import com.mall.core.model.dto.LoginDTO;
import com.mall.core.model.dto.RegisterDTO;
import com.mall.core.model.vo.LoginVO;
import com.mall.core.model.vo.UserVO;

public interface UserService {

    Long register(RegisterDTO dto);

    LoginVO login(LoginDTO dto);

    /** 用刷新令牌换一个新的 access token（账号被禁用/删除时抛业务异常） */
    LoginVO refreshLogin(Long userId);

    UserVO getById(Long userId);

    PageResult<UserVO> page(long pageNum, long pageSize, String keyword);
}
