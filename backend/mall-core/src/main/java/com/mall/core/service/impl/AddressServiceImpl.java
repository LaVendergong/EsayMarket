package com.mall.core.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.mall.common.exception.BusinessException;
import com.mall.common.result.ResultCode;
import com.mall.core.entity.Address;
import com.mall.core.mapper.AddressMapper;
import com.mall.core.model.dto.AddressDTO;
import com.mall.core.model.vo.AddressVO;
import com.mall.core.service.AddressService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class AddressServiceImpl implements AddressService {

    private final AddressMapper addressMapper;

    @Override
    public List<AddressVO> listByUser(Long userId) {
        List<Address> list = addressMapper.selectList(Wrappers.<Address>lambdaQuery()
                .eq(Address::getUserId, userId)
                .orderByDesc(Address::getIsDefault)
                .orderByDesc(Address::getId));
        return list.stream().map(AddressVO::from).toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long add(Long userId, AddressDTO dto) {
        Address a = new Address();
        apply(a, dto);
        a.setUserId(userId);
        boolean isDefault = dto.getIsDefault() != null && dto.getIsDefault() == 1
                || countByUser(userId) == 0;
        a.setIsDefault(isDefault ? 1 : 0);
        if (isDefault) {
            clearDefault(userId);
        }
        addressMapper.insert(a);
        return a.getId();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void update(Long userId, Long id, AddressDTO dto) {
        Address a = getOwned(userId, id);
        apply(a, dto);
        if (dto.getIsDefault() != null && dto.getIsDefault() == 1) {
            clearDefault(userId);
            a.setIsDefault(1);
        }
        addressMapper.updateById(a);
    }

    @Override
    public void delete(Long userId, Long id) {
        getOwned(userId, id);
        addressMapper.deleteById(id);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void setDefault(Long userId, Long id) {
        Address a = getOwned(userId, id);
        clearDefault(userId);
        a.setIsDefault(1);
        addressMapper.updateById(a);
    }

    @Override
    public Address getDefault(Long userId) {
        List<Address> list = addressMapper.selectList(Wrappers.<Address>lambdaQuery()
                .eq(Address::getUserId, userId)
                .orderByDesc(Address::getIsDefault)
                .orderByDesc(Address::getId)
                .last("limit 1"));
        return list.isEmpty() ? null : list.get(0);
    }

    @Override
    public Address getOwned(Long userId, Long id) {
        Address a = addressMapper.selectById(id);
        if (a == null || !a.getUserId().equals(userId)) {
            throw new BusinessException(ResultCode.NOT_FOUND, "地址不存在");
        }
        return a;
    }

    private long countByUser(Long userId) {
        Long c = addressMapper.selectCount(Wrappers.<Address>lambdaQuery().eq(Address::getUserId, userId));
        return c == null ? 0 : c;
    }

    private void clearDefault(Long userId) {
        Address reset = new Address();
        reset.setIsDefault(0);
        addressMapper.update(reset, Wrappers.<Address>lambdaUpdate()
                .eq(Address::getUserId, userId)
                .eq(Address::getIsDefault, 1));
    }

    private void apply(Address a, AddressDTO dto) {
        a.setReceiver(dto.getReceiver());
        a.setPhone(dto.getPhone());
        a.setProvince(dto.getProvince());
        a.setCity(dto.getCity());
        a.setDistrict(dto.getDistrict());
        a.setDetail(dto.getDetail());
    }
}
