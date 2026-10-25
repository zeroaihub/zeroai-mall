package com.zeroai.domain.api.goods.request;

import com.zeroai.domain.api.goods.response.GoodsAttributeVO;
import com.zeroai.domain.api.goods.response.GoodsProductVO;
import com.zeroai.domain.api.goods.response.GoodsSpecificationVO;
import com.zeroai.domain.api.goods.response.GoodsVO;
import jakarta.validation.Valid;
import lombok.Data;

@Data
public class GoodsSaveRelatedReqVO {

    @Valid
    GoodsVO goods;
    @Valid
    GoodsSpecificationVO[] specifications;
    @Valid
    GoodsAttributeVO[] attributes;
    @Valid
    GoodsProductVO[] products;
}
