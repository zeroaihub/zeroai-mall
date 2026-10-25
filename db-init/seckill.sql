CREATE TABLE IF NOT EXISTS `shop_seckill_activity` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
  `name` varchar(100) NOT NULL COMMENT '活动名称',
  `brief` varchar(255) DEFAULT NULL COMMENT '活动简介',
  `start_time` datetime NOT NULL COMMENT '开始时间',
  `end_time` datetime NOT NULL COMMENT '结束时间',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '状态：0 草稿，1 已发布，2 已下架',
  `sort_order` int NOT NULL DEFAULT 100 COMMENT '排序值',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  `del_flag` tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标志：0 存在，1 删除',
  PRIMARY KEY (`id`),
  KEY `idx_seckill_activity_status_time` (`status`, `start_time`, `end_time`),
  KEY `idx_seckill_activity_sort` (`sort_order`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='秒杀活动表';

CREATE TABLE IF NOT EXISTS `shop_seckill_sku` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
  `activity_id` bigint NOT NULL COMMENT '秒杀活动 ID',
  `goods_id` bigint NOT NULL COMMENT '商品 ID',
  `product_id` bigint NOT NULL COMMENT '商品 SKU ID',
  `seckill_price` decimal(10,2) NOT NULL COMMENT '秒杀价',
  `available_stock` int NOT NULL DEFAULT 0 COMMENT '活动可售库存',
  `locked_stock` int NOT NULL DEFAULT 0 COMMENT '活动冻结库存',
  `sold_stock` int NOT NULL DEFAULT 0 COMMENT '活动已售库存',
  `limit_count` int NOT NULL DEFAULT 1 COMMENT '单用户限购数量',
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '状态：0 草稿，1 已发布，2 已下架',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  `del_flag` tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标志：0 存在，1 删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_seckill_sku_activity_product` (`activity_id`, `product_id`),
  KEY `idx_seckill_sku_product` (`product_id`),
  KEY `idx_seckill_sku_activity_status` (`activity_id`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='秒杀活动 SKU 表';

CREATE TABLE IF NOT EXISTS `shop_order_activity_relation` (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
  `order_id` bigint NOT NULL COMMENT '订单 ID',
  `order_sn` varchar(63) NOT NULL COMMENT '订单编号',
  `order_goods_id` bigint NOT NULL COMMENT '订单商品 ID',
  `user_id` bigint NOT NULL COMMENT '用户 ID',
  `activity_type` tinyint NOT NULL COMMENT '活动类型：1 秒杀',
  `activity_id` bigint NOT NULL COMMENT '活动 ID',
  `activity_sku_id` bigint NOT NULL COMMENT '活动 SKU ID',
  `goods_id` bigint NOT NULL COMMENT '商品 ID',
  `product_id` bigint NOT NULL COMMENT '商品 SKU ID',
  `activity_price` decimal(10,2) NOT NULL COMMENT '活动成交单价',
  `number` int NOT NULL COMMENT '购买数量',
  `inventory_status` tinyint NOT NULL DEFAULT 0 COMMENT '活动库存状态：0 已冻结，1 已确认，2 已释放',
  `create_time` datetime NOT NULL COMMENT '创建时间',
  `update_time` datetime NOT NULL COMMENT '更新时间',
  `del_flag` tinyint(1) NOT NULL DEFAULT 0 COMMENT '删除标志：0 存在，1 删除',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_order_activity_order_goods` (`order_goods_id`),
  KEY `idx_order_activity_order_id` (`order_id`),
  KEY `idx_order_activity_order_sn` (`order_sn`),
  KEY `idx_order_activity_user_sku` (`activity_type`, `activity_sku_id`, `user_id`),
  KEY `idx_order_activity_user_goods` (`activity_type`, `activity_id`, `goods_id`, `user_id`, `inventory_status`),
  KEY `idx_order_activity_inventory` (`activity_type`, `inventory_status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci COMMENT='订单活动关联表';

SET @shop_parent_id := (SELECT `menu_id` FROM `sys_menu` WHERE `parent_id` = 0 AND `path` = 'shop' LIMIT 1);

INSERT INTO `sys_menu` (
  `menu_name`, `parent_id`, `sort`, `path`, `component`, `is_frame`, `menu_type`, `menu_status`,
  `visible`, `perms`, `icon`, `create_by`, `create_time`, `remark`, `del_flag`
)
SELECT '秒杀活动', @shop_parent_id, 6, 'seckill', 'shop/seckill/index', 1, 'C', 0,
       0, NULL, 'time-range', 'admin', NOW(), '秒杀活动管理菜单', 0
WHERE @shop_parent_id IS NOT NULL
  AND NOT EXISTS (
    SELECT 1 FROM `sys_menu` WHERE `path` = 'seckill' AND `component` = 'shop/seckill/index'
  );

SET @seckill_menu_id := (SELECT `menu_id` FROM `sys_menu` WHERE `path` = 'seckill' AND `component` = 'shop/seckill/index' LIMIT 1);

INSERT INTO `sys_menu` (
  `menu_name`, `parent_id`, `sort`, `path`, `component`, `is_frame`, `menu_type`, `menu_status`,
  `visible`, `perms`, `icon`, `create_by`, `create_time`, `remark`, `del_flag`
)
SELECT '列表', @seckill_menu_id, 1, '', NULL, 1, 'F', 0, 0, 'shop:seckill:list', '#', 'admin', NOW(), '秒杀活动列表权限', 0
WHERE @seckill_menu_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms` = 'shop:seckill:list');

INSERT INTO `sys_menu` (
  `menu_name`, `parent_id`, `sort`, `path`, `component`, `is_frame`, `menu_type`, `menu_status`,
  `visible`, `perms`, `icon`, `create_by`, `create_time`, `remark`, `del_flag`
)
SELECT '详情', @seckill_menu_id, 2, '', NULL, 1, 'F', 0, 0, 'shop:seckill:info', '#', 'admin', NOW(), '秒杀活动详情权限', 0
WHERE @seckill_menu_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms` = 'shop:seckill:info');

INSERT INTO `sys_menu` (
  `menu_name`, `parent_id`, `sort`, `path`, `component`, `is_frame`, `menu_type`, `menu_status`,
  `visible`, `perms`, `icon`, `create_by`, `create_time`, `remark`, `del_flag`
)
SELECT '新增', @seckill_menu_id, 3, '', NULL, 1, 'F', 0, 0, 'shop:seckill:add', '#', 'admin', NOW(), '秒杀活动新增权限', 0
WHERE @seckill_menu_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms` = 'shop:seckill:add');

INSERT INTO `sys_menu` (
  `menu_name`, `parent_id`, `sort`, `path`, `component`, `is_frame`, `menu_type`, `menu_status`,
  `visible`, `perms`, `icon`, `create_by`, `create_time`, `remark`, `del_flag`
)
SELECT '修改', @seckill_menu_id, 4, '', NULL, 1, 'F', 0, 0, 'shop:seckill:update', '#', 'admin', NOW(), '秒杀活动修改/发布/预热权限', 0
WHERE @seckill_menu_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms` = 'shop:seckill:update');

INSERT INTO `sys_menu` (
  `menu_name`, `parent_id`, `sort`, `path`, `component`, `is_frame`, `menu_type`, `menu_status`,
  `visible`, `perms`, `icon`, `create_by`, `create_time`, `remark`, `del_flag`
)
SELECT '删除', @seckill_menu_id, 5, '', NULL, 1, 'F', 0, 0, 'shop:seckill:delete', '#', 'admin', NOW(), '秒杀活动删除权限', 0
WHERE @seckill_menu_id IS NOT NULL AND NOT EXISTS (SELECT 1 FROM `sys_menu` WHERE `perms` = 'shop:seckill:delete');
