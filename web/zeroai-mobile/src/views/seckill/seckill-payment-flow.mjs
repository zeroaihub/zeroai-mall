export function buildSeckillPayRoute({ orderSn, actualPrice, fallbackPrice, status }) {
  if (status !== 'SUCCESS' || !orderSn) {
    return null
  }

  return {
    name: 'OrderPay',
    query: {
      orderSn,
      actualPrice: actualPrice || fallbackPrice,
    },
  }
}

export function buildSeckillSubmitErrorMessage(error) {
  const message = error?.message || ''

  if (message.includes('请勿重复购买该活动商品')) {
    return '请勿重复购买该活动商品'
  }

  return message || '秒杀下单失败，请稍后重试'
}
