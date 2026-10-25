package com.zeroai.common.convert;

import com.zeroai.domain.api.common.ZeroaiConfig;
import com.zeroai.domain.api.trade.response.MallConfigResponseVO;

/**
 * @date: 2023/11/13 23:10
 */
public class MallConfigConvert {

    public static MallConfigResponseVO convertMallConfig() {

        return MallConfigResponseVO.builder()
                .freightLimit(ZeroaiConfig.getFreightLimit())
                .freightPrice(ZeroaiConfig.getFreightPrice())
                .mobileUrl(ZeroaiConfig.getMobileUrl())
                .email(ZeroaiConfig.getEmail())
                .name(ZeroaiConfig.getName())
                .unpaidOrderCancelDelayTime(ZeroaiConfig.getUnpaidOrderCancelDelayTime())
                .version(ZeroaiConfig.getVersion())
                .uploadDir(ZeroaiConfig.getUploadDir())
                .build();
    }
}
