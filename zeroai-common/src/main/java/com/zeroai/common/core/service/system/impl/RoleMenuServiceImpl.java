package com.zeroai.common.core.service.system.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zeroai.common.core.entity.system.RoleMenu;
import com.zeroai.common.core.mapper.system.RoleMenuMapper;
import com.zeroai.common.core.service.system.IRoleMenuService;
import org.springframework.stereotype.Service;

@Service
public class RoleMenuServiceImpl extends ServiceImpl<RoleMenuMapper, RoleMenu> implements IRoleMenuService {
}
