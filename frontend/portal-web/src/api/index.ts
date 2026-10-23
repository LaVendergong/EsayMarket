import { http } from './http'
import type { AfterSaleVO,
  PayChannelOption,
  PaymentVO, 
  AddressDTO,
  AddressVO,
  CartVO,
  CategoryVO,
  CouponVO,
  DigitalGrantVO,
  FavoriteVO,
  GrantContentVO,
  LoginVO,
  OrderCreateVO,
  OrderPreviewVO,
  OrderVO,
  PageResult,
  PreviewOrderDTO,
  ProductDetailVO,
  ProductVO,
  ReviewVO,
  UserCouponVO,
  UserVO
} from '@/types'

export const authApi = {
  register: (dto: { username: string; password: string; nickname?: string; phone?: string }) =>
    http.post<never, number>('/portal/auth/register', dto),
  login: (username: string, password: string) =>
    http.post<never, LoginVO>('/portal/auth/login', { username, password }),
  /** 退出：Cookie 是 HttpOnly 的，前端删不掉，必须让服务端清 */
  logout: () => http.post<never, void>('/portal/auth/logout')
}

export const userApi = {
  me: () => http.get<never, UserVO>('/portal/user/me')
}

export const categoryApi = {
  /** 前台只返回 status=1 的分类树 */
  tree: () => http.get<never, CategoryVO[]>('/portal/category/tree')
}

export const productApi = {
  page: (params: {
    pageNum: number
    pageSize: number
    categoryId?: number
    keyword?: string
    minPrice?: number
    maxPrice?: number
    sort?: string
  }) => http.get<never, PageResult<ProductVO>>('/portal/product/page', { params }),
  detail: (id: number) => http.get<never, ProductDetailVO>(`/portal/product/${id}`),
  /** 搜索联想：分类名 + 商品名 */
  suggest: (keyword: string, limit = 10) =>
    http.get<never, string[]>('/portal/product/suggest', { params: { keyword, limit } }),
  /** 热门搜索（近 7 天统计） */
  hotKeywords: (limit = 10) =>
    http.get<never, string[]>('/portal/product/hot-keywords', { params: { limit } })
}

export const cartApi = {
  list: () => http.get<never, CartVO[]>('/portal/cart'),
  add: (skuId: number, quantity: number) => http.post<never, number>('/portal/cart', { skuId, quantity }),
  updateQuantity: (id: number, quantity: number) =>
    http.put<never, void>(`/portal/cart/${id}`, null, { params: { quantity } }),
  updateChecked: (id: number, checked: number) =>
    http.put<never, void>(`/portal/cart/${id}/checked`, null, { params: { checked } }),
  /** 全选 / 取消全选：一次请求（后端一条 SQL），不再循环发 N 次 */
  updateAllChecked: (checked: number) =>
    http.put<never, void>('/portal/cart/checked', null, { params: { checked } }),
  remove: (id: number) => http.delete<never, void>(`/portal/cart/${id}`),
  clearChecked: () => http.delete<never, void>('/portal/cart/checked')
}

export const addressApi = {
  list: () => http.get<never, AddressVO[]>('/portal/address'),
  add: (dto: AddressDTO) => http.post<never, number>('/portal/address', dto),
  update: (id: number, dto: AddressDTO) => http.put<never, void>(`/portal/address/${id}`, dto),
  remove: (id: number) => http.delete<never, void>(`/portal/address/${id}`),
  setDefault: (id: number) => http.put<never, void>(`/portal/address/${id}/default`)
}

export const orderApi = {
  /**
   * 结算试算：只读，返回 商品/运费/优惠/应付 与可用券。
   * 金额一律由服务端算，前端只用它展示（下单时会再算一遍）。
   */
  preview: (dto: PreviewOrderDTO) => http.post<never, OrderPreviewVO>('/portal/order/preview', dto),

  /**
   * 结算购物车里「已选中」的条目；addressId 不传用默认地址。
   * requestId 是幂等键：同一次结算里保持不变地传同一个值，重复提交只会落一单并返回原单。
   * userCouponId 是「用户券 id」（我的券里的 id），不传 = 不用券。
   */
  create: (addressId?: number, requestId?: string, userCouponId?: number) =>
    http.post<never, OrderCreateVO>('/portal/order', { addressId, requestId, userCouponId }),

  /** 「立即购买」：指定 SKU + 数量直接下单，不经过购物车 */
  createDirect: (skuId: number, quantity: number, addressId?: number, requestId?: string, userCouponId?: number) =>
    http.post<never, OrderCreateVO>('/portal/order/direct', { skuId, quantity, addressId, requestId, userCouponId }),
  page: (params: { pageNum: number; pageSize: number; status?: number }) =>
    http.get<never, PageResult<OrderVO>>('/portal/order/page', { params }),
  detail: (id: number) => http.get<never, OrderVO>(`/portal/order/${id}`),
  pay: (id: number) => http.post<never, void>(`/portal/order/${id}/pay`),
  cancel: (id: number) => http.post<never, void>(`/portal/order/${id}/cancel`),
  confirm: (id: number) => http.post<never, void>(`/portal/order/${id}/confirm`)
}

/** 优惠券：领券中心 / 领取 / 我的券 */
export const couponApi = {
  center: () => http.get<never, CouponVO[]>('/portal/coupon/center'),
  receive: (couponId: number) => http.post<never, void>(`/portal/coupon/${couponId}/receive`),
  /** status: 0 未使用 1 已使用 2 已过期，不传=全部 */
  mine: (status?: number) => http.get<never, UserCouponVO[]>('/portal/coupon/mine', { params: { status } })
}

/** 收藏：切换 / 我的收藏 / 是否已收藏 */
export const favoriteApi = {
  /** 返回操作后的状态：true=已收藏 */
  toggle: (productId: number) => http.post<never, boolean>(`/portal/favorite/${productId}`),
  page: (pageNum = 1, pageSize = 10) =>
    http.get<never, PageResult<FavoriteVO>>('/portal/favorite/page', { params: { pageNum, pageSize } }),
  check: (productId: number) => http.get<never, boolean>(`/portal/favorite/check/${productId}`)
}

/** 图片上传（评价晒图）：返回可直接展示的 URL */
export const uploadApi = {
  // 不手动设置 Content-Type：交给浏览器补 multipart 的 boundary
  image: (form: FormData) => http.post<never, string>('/portal/upload/image', form)
}

/** 商品评价：提交 / 按商品查 / 某订单已评的项 */
export const reviewApi = {
  submit: (dto: {
    orderItemId: number
    rating: number
    content?: string
    anonymous?: number
    /** 先调 uploadApi.image 拿到 URL，再随评价提交（最多 3 张） */
    images?: string[]
  }) => http.post<never, void>('/portal/review', dto),
  byProduct: (productId: number, pageNum = 1, pageSize = 10) =>
    http.get<never, PageResult<ReviewVO>>(`/portal/review/product/${productId}`, {
      params: { pageNum, pageSize }
    }),
  /** 返回已评价的 orderItemId 列表 */
  reviewed: (orderId: number) => http.get<never, number[]>(`/portal/review/order/${orderId}/reviewed`)
}

/** 虚拟商品凭证：我的卡券 / 按订单查 / 查看明文 */
export const digitalApi = {
  mine: () => http.get<never, DigitalGrantVO[]>('/portal/digital/mine'),
  byOrder: (orderId: number) => http.get<never, DigitalGrantVO[]>(`/portal/digital/order/${orderId}`),
  /** 每次调用都会累加查看次数（售后据此判断能否自助退款） */
  reveal: (id: number) => http.post<never, GrantContentVO>(`/portal/digital/${id}/reveal`)
}

/** 售后：申请 / 我的售后 / 详情 / 撤销 */
export const afterSaleApi = {
  apply: (data: { orderId: number; type: number; reason: string; description?: string }) =>
    http.post<never, number>('/portal/after-sale', data),
  page: (pageNum = 1, pageSize = 10) =>
    http.get<never, { total: number; list: AfterSaleVO[] }>('/portal/after-sale/page', {
      params: { pageNum, pageSize }
    }),
  detail: (id: number) => http.get<never, AfterSaleVO>(`/portal/after-sale/${id}`),
  /** 订单详情页用它判断「能不能申请 / 现在走到哪一步」，没有售后时返回 null */
  byOrder: (orderId: number) => http.get<never, AfterSaleVO | null>(`/portal/after-sale/by-order/${orderId}`),
  cancel: (id: number) => http.post<never, void>(`/portal/after-sale/${id}/cancel`)
}
/** 支付：可用渠道 / 创建支付单 / 查状态 / 模拟支付 / 关闭 */
export const paymentApi = {
  channels: () => http.get<never, PayChannelOption[]>('/portal/payment/channels'),
  create: (orderId: number, channel: string) =>
    http.post<never, PaymentVO>('/portal/payment', { orderId, channel }),
  detail: (paymentNo: string) => http.get<never, PaymentVO>(`/portal/payment/${paymentNo}`),
  /** 仅 MOCK 渠道：点一下就算付款成功（本地/自动化测试） */
  mockPay: (paymentNo: string) => http.post<never, void>(`/portal/payment/${paymentNo}/mock-pay`),
  close: (paymentNo: string) => http.post<never, void>(`/portal/payment/${paymentNo}/close`)
}