package com.zeroai.domain.promotion.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import com.zeroai.domain.api.promotion.entity.Channel;
import com.zeroai.domain.api.promotion.mapper.ChannelMapper;
import com.zeroai.domain.api.promotion.service.IChannelService;
import com.zeroai.util.constant.SysConstants;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

@Service
@AllArgsConstructor
public class ChannelServiceImpl extends ServiceImpl<ChannelMapper, Channel> implements IChannelService {

    private ChannelMapper channelMapper;

    @Override
    public List<Channel> list(Channel channel) {
        return channelMapper.selectChannelList(channel);
    }

    @Override
    public String checkChannelNameUnique(Channel channel) {
        long channelId = Objects.isNull(channel.getId()) ? -1L : channel.getId();
        Channel shopChannel = getOne(new QueryWrapper<Channel>().eq("name", channel.getName()));
        if (shopChannel != null && shopChannel.getId() != channelId) {
            return SysConstants.NOT_UNIQUE;
        }
        return SysConstants.UNIQUE;
    }

    @Override
    public String checkChannelCodeUnique(Channel channel) {
        long channelId = Objects.isNull(channel.getId()) ? -1L : channel.getId();
        Channel shopChannel = getOne(new QueryWrapper<Channel>().eq("code", channel.getCode()));
        if (shopChannel != null && shopChannel.getId() != channelId) {
            return SysConstants.NOT_UNIQUE;
        }
        return SysConstants.UNIQUE;
    }
}
