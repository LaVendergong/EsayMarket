package com.mall.core.service;

import com.mall.core.entity.Address;
import com.mall.core.model.dto.AddressDTO;
import com.mall.core.model.vo.AddressVO;

import java.util.List;

public interface AddressService {

    List<AddressVO> listByUser(Long userId);

    Long add(Long userId, AddressDTO dto);

    void update(Long userId, Long id, AddressDTO dto);

    void delete(Long userId, Long id);

    void setDefault(Long userId, Long id);

    Address getDefault(Long userId);

    Address getOwned(Long userId, Long id);
}
