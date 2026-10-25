package com.zeroai.mobile.api.controller.marketing;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zeroai.common.base.controller.BaseController;
import com.zeroai.domain.api.promotion.entity.Diamond;
import com.zeroai.domain.api.goods.entity.Goods;
import com.zeroai.domain.api.promotion.response.DiamondGoodsResVO;
import com.zeroai.domain.api.promotion.service.IDiamondService;
import com.zeroai.domain.promotion.strategy.diamond.DiamondJumpContext;
import com.zeroai.domain.promotion.strategy.diamond.DiamondJumpTypeInterface;
import com.zeroai.util.util.R;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 金刚区接口
 *
 * @since 2024/1/15
 */
@RestController
@AllArgsConstructor
@RequestMapping("diamond")
public class DiamondController extends BaseController {

    private IDiamondService iDiamondService;

    private DiamondJumpContext diamondJumpContext;

    /**
     * 金刚区跳转
     *
     * @param diamondId 金刚区id
     * @return R
     */
    @GetMapping("getGoodsList")
    public R<DiamondGoodsResVO> getGoodsList(Long diamondId) {
        Page<Goods> page = getPage();
        Diamond diamond = iDiamondService.getById(diamondId);
        DiamondJumpTypeInterface instance = diamondJumpContext.getInstance(diamond.getJumpType());
        DiamondGoodsResVO resVO = instance.getGoods(page, diamond);
        return R.success(resVO);
    }
}
