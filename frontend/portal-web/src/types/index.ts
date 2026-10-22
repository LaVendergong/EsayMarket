/** 与后端 com.mall 的 VO / DTO 字段严格对齐 */

export interface R<T> {
  code: number
  message: string
  data: T
}

export interface PageResult<T> {
  total: number
  pageNum: number
  pageSize: number
  list: T[]
}

export interface LoginVO {
  token: string
  userId: number
  username: string
  nickname?: string
  avatar?: string
  role: number
}

export interface UserVO {
  id: number
  username: string
  nickname?: string
  phone?: string
  avatar?: string
  role: number
}

export interface CategoryVO {
  id: number
  parentId: number
  name: string
  sort: number
  icon?: string
  status: number
  children: CategoryVO[]
}

export interface ProductVO {
  id: number
  categoryId: number
  categoryName?: string
  name: string
  subTitle?: string
  mainImage?: string
  price: number
  status: number
  sales: number
  /** 该商品所有 SKU 的可售库存合计 */
  stock: number
}

export interface SkuVO {
  id: number
  skuCode?: string
  spec?: string
  image?: string
  price: number
  stock: number
  lockedStock: number
  availableStock: number
}

export interface ProductDetailVO {
  id: number
  categoryId: number
  categoryName?: string
  name: string
  subTitle?: string
  mainImage?: string
  images: string[]
  detail?: string
  price: number
  status: number
  sales: number
  skus: SkuVO[]
}

export interface CartVO {
  id: number
  productId: number
  skuId: number
  productName?: string
  productImage?: string
  spec?: string
  price?: number
  quantity: number
  checked: number
  availableStock?: number
  productStatus?: number
  totalPrice?: number
  /** 商品下架 / SKU 被删 / 无货 时为 false */
  valid?: boolean
}

export interface AddressVO {
  id: number
  receiver: string
  phone: string
  province?: string
  city?: string
  district?: string
  detail: string
  isDefault: number
}

export interface AddressDTO {
  receiver: string
  phone: string
  province?: string
  city?: string
  district?: string
  detail: string
  isDefault?: number
}

export interface OrderItemVO {
  id: number
  productId: number
  skuId: number
  productName: string
  productImage?: string
  spec?: string
  price: number
  quantity: number
  totalAmount: number
}

export interface OrderVO {
  id: number
  orderNo: string
  userId: number
  totalAmount: number
  payAmount: number
  status: number
  statusText: string
  receiver?: string
  receiverPhone?: string
  receiverAddr?: string
  payTime?: string
  expireTime?: string
  createTime?: string
  items: OrderItemVO[]
}

export interface OrderCreateVO {
  orderId: number
  orderNo: string
  payAmount: number
  status: number
  expireTime: string
}

export const ORDER_TABS = [
  { name: '', label: '全部' },
  { name: '0', label: '待支付' },
  { name: '1', label: '已支付' },
  { name: '2', label: '已发货' },
  { name: '3', label: '已完成' },
  { name: '4', label: '已关闭' },
  { name: '5', label: '售后中' },
  { name: '6', label: '已退款' }
] as const

// ---------------------------------------------------------------- 售后（退款 / 退货退款）
export interface AfterSaleVO {
  id: number
  afterSaleNo: string
  orderId: number
  orderNo: string
  type: number
  typeText: string
  status: number
  statusText: string
  reason: string
  description?: string
  refundAmount: number
  applyTime?: string
  handleTime?: string
  handleRemark?: string
  items?: OrderItemVO[]
}

/**
 * 只做两种售后，因为边界最清楚：
 *   仅退款   —— 货不寄回，**不恢复库存**
 *   退货退款 —— 货寄回仓库，**恢复库存**
 * 换货暂不做（它要再走一次发货流程，边界复杂，半成品反而误导用户）。
 */
export const AFTER_SALE_TYPES = [
  { value: 1, label: '仅退款', hint: '没收到货或不想要了，退款后商品无需寄回' },
  { value: 2, label: '退货退款', hint: '已收到货，需要把商品寄回，商家收到后退款' }
]

/** 固定原因选项：便于商家统计，也避免用户写出无法处理的内容 */
export const AFTER_SALE_REASONS = [
  '不想要了',
  '商品质量问题',
  '尺码/规格不合适',
  '收到商品与描述不符',
  '少件/漏发',
  '其他'
]
// ---------------------------------------------------------------- 支付
export interface PaymentVO {
  id: number
  paymentNo: string
  orderId: number
  orderNo: string
  channel: string
  channelText: string
  amount: number
  status: number
  statusText: string
  /** 调起参数：支付宝 {payUrl} / 微信 {codeUrl} / 模拟 {mockUrl} */
  payParams: Record<string, string>
  channelTradeNo?: string
  expireTime?: string
  paidTime?: string
}

export interface PayChannelOption {
  code: string
  text: string
}