package com.zeroai.ai.mall.tool;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zeroai.domain.api.trade.entity.Address;
import com.zeroai.domain.api.trade.service.IAddressService;
import io.agentscope.core.tool.Tool;

import java.util.List;

/**
 * 只读工具：当前用户的收货地址。
 * <p>
 * 越权防护：userId 构造期注入，模型无法指定他人身份。
 * 隐私防护：手机号与收件人姓名做脱敏后返回，模型与前端都不该拿到完整联系方式；
 * 完整地址只在原下单链路里由用户自己在页面上看到。
 */
public class ListMyAddressesTool {

    /** 单次返回地址数上限。 */
    private static final int MAX_LIMIT = 5;

    private final IAddressService addressService;
    private final Long userId;

    /**
     * @param addressService 地址服务，只读
     * @param userId         当前登录用户 ID，必须非空
     */
    public ListMyAddressesTool(IAddressService addressService, Long userId) {
        if (userId == null) {
            throw new IllegalArgumentException("userId 不能为空：未登录用户不允许查询地址");
        }
        this.addressService = addressService;
        this.userId = userId;
    }

    /**
     * 查询当前用户的收货地址（脱敏）。
     *
     * @param limit 期望返回条数，缺省 3，最大 5
     * @return JSON 字符串，含 addressId / 收件人脱敏 / 手机号脱敏 / 省市区 / 是否默认
     */
    @Tool(name = "list_my_addresses",
            description = "查询当前登录用户本人的收货地址列表（姓名和手机号已脱敏）。只读操作。返回 addressId 供 propose_order 使用。",
            readOnly = true,
            concurrencySafe = true)
    public String listMyAddresses(Integer limit) {
        int size = limit == null ? 3 : Math.min(Math.max(limit, 1), MAX_LIMIT);
        List<Address> addresses;
        try {
            addresses = addressService.list(Wrappers.lambdaQuery(Address.class)
                    .eq(Address::getMemberId, userId)
                    .eq(Address::getDelFlag, false)
                    .last("LIMIT " + size));
        } catch (RuntimeException e) {
            return "{\"error\":\"ADDRESS_UNAVAILABLE\",\"hint\":\"地址查询失败，请稍后再试\"}";
        }
        if (addresses == null || addresses.isEmpty()) {
            return "{\"count\":0,\"addresses\":[],\"hint\":\"还没有收货地址，请提示用户先在页面添加\"}";
        }
        StringBuilder sb = new StringBuilder(128 + addresses.size() * 128);
        sb.append("{\"count\":").append(addresses.size()).append(",\"addresses\":[");
        for (int i = 0; i < addresses.size(); i++) {
            Address address = addresses.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append("{\"addressId\":").append(ToolJson.num(address.getId()))
                    .append(",\"receiver\":").append(ToolJson.str(maskName(address.getName())))
                    .append(",\"tel\":").append(ToolJson.str(maskTel(address.getTel())))
                    .append(",\"region\":").append(ToolJson.str(regionOf(address)))
                    .append(",\"isDefault\":").append(address.isDefault())
                    .append('}');
        }
        return sb.append("]}").toString();
    }

    /**
     * 收件人脱敏：只保留姓与末位。
     *
     * @param name 原姓名
     * @return 脱敏姓名
     */
    private static String maskName(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        if (name.length() == 1) {
            return name;
        }
        return name.charAt(0) + "**";
    }

    /**
     * 手机号脱敏：保留前 3 位与后 2 位。
     *
     * @param tel 原手机号
     * @return 脱敏手机号
     */
    private static String maskTel(String tel) {
        if (tel == null || tel.length() < 5) {
            return tel == null ? "" : "****";
        }
        return tel.substring(0, 3) + "****" + tel.substring(tel.length() - 2);
    }

    /**
     * 省市区拼接。
     *
     * @param address 地址
     * @return 省市区文本
     */
    private static String regionOf(Address address) {
        StringBuilder sb = new StringBuilder();
        for (String part : new String[]{address.getProvince(), address.getCity(), address.getCounty()}) {
            if (part != null && !part.isBlank()) {
                sb.append(part);
            }
        }
        return sb.toString();
    }
}
