package com.zeroai.common.core.service.system.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zeroai.common.core.entity.system.UserRole;
import com.zeroai.common.core.mapper.system.UserRoleMapper;
import com.zeroai.common.core.service.system.IUserRoleService;
import org.springframework.stereotype.Service;

@Service
public class UserRoleServiceImpl extends ServiceImpl<UserRoleMapper, UserRole> implements IUserRoleService {
}
