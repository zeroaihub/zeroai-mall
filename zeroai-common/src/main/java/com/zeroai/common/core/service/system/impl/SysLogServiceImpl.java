package com.zeroai.common.core.service.system.impl;

import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zeroai.common.core.entity.system.SysLog;
import com.zeroai.common.core.mapper.system.SysLogMapper;
import com.zeroai.common.core.service.system.ISysLogService;
import org.springframework.stereotype.Service;

/**
 * @author 16697
 * @description 针对表【sys_log(日志表)】的数据库操作Service实现
 * @createDate 2023-08-04 00:14:00
 */
@Service
public class SysLogServiceImpl extends ServiceImpl<SysLogMapper, SysLog>
        implements ISysLogService {

}




