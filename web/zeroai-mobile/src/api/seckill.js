import request from '@/utils/request'

export function seckillList(params) {
  return request({
    url: '/seckill/list',
    method: 'get',
    params
  })
}

export function seckillDetail(activityId) {
  return request({
    url: `/seckill/detail/${activityId}`,
    method: 'get'
  })
}

export function seckillToken(activitySkuId) {
  return request({
    url: `/seckill/token/${activitySkuId}`,
    method: 'get'
  })
}

export function seckillSubmit(data) {
  return request({
    url: '/seckill/submit',
    method: 'post',
    data
  })
}

export function seckillResult(orderSn) {
  return request({
    url: `/seckill/result/${orderSn}`,
    method: 'get',
    params: { t: Date.now() }
  })
}
