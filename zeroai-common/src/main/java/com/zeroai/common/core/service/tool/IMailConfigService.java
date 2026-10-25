package com.zeroai.common.core.service.tool;


import com.baomidou.mybatisplus.spring.service.IService;
import com.zeroai.common.core.entity.tool.EmailConfig;

public interface IMailConfigService extends IService<EmailConfig> {

    boolean checkMailConfig(EmailConfig emailConfig);
}
