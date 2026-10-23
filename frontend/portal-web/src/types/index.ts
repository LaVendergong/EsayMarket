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
  /** 平均分（0 = 暂无评价） */
  ratingAvg?: number
  reviewCount?: number
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
  /** 运费说明，后端拼好的文案（包邮 / 运费 ¥8.00 / 满 ¥99.00 包邮 / 虚拟商品，无需物流） */
  freightText?: string
  /** 1 实物 2 虚拟 */
  productType?: number
  productTypeText?: string
  /** 虚拟：1 卡密 2 外链 */
  deliveryType?: number
  deliveryTypeText?: string
  /** 平均分（0 = 暂无评价） */
  ratingAvg?: number
  reviewCount?: number
  skus: SkuVO[]
}

/** 我的收藏 */
export interface FavoriteVO {
  id: number
  productId: number
  productName?: string
  subTitle?: string
  mainImage?: string
  price?: number
  /** 0 已下架：列表里要标灰，点进去会报"商品已下架" */
  status?: number
  stock?: number
  favoriteTime?: string
}

/** 商品评价 */
export interface ReviewVO {
  id: number
  orderId: number
  orderItemId: number
  productId: number
  skuId: number
  spec?: string
  productName?: string
  /** 1-5 */
  rating: number
  content?: string
  /** 晒图 URL（最多 3 张） */
  images?: string[]
  /** 匿名评价固定显示「匿名用户」 */
  userLabel: string
  anonymous: number
  status: number
  /** 1 = 该订单已退款：评价仍展示，但不计入商品评分 */
  refunded?: number
  createTime?: string
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
  /** 该行分摊到的优惠 */
  discountAmount?: number
  /** 该行实付 */
  payAmount?: number
}

export interface OrderVO {
  id: number
  orderNo: string
  userId: number
  /** 商品金额（不含运费、不含优惠） */
  totalAmount: number
  freightAmount?: number
  discountAmount?: number
  /** 应付 = 商品 + 运费 - 优惠 */
  payAmount: number
  userCouponId?: number | null
  status: number
  statusText: string
  /** 1 实物 2 虚拟（虚拟订单没有收货地址，支付后自动发放） */
  orderType?: number
  orderTypeText?: string
  /** 物流：发货后才有值 */
  shipCompany?: string
  shipCompanyText?: string
  shipNo?: string
  shipTime?: string
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
  /** 退款执行情况（和售后单状态分开看：同意≠钱到账） */
  refundNo?: string
  refundChannel?: string
  refundStatus?: number
  refundStatusText?: string
  refundTime?: string
  refundError?: string
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

// ---------------------------------------------------------------- 优惠券 / 结算试算
/** 领券中心的券模板 */
export interface CouponVO {
  id: number
  name: string
  /** 使用门槛（商品金额，不含运费） */
  threshold: number
  amount: number
  total: number
  received: number
  validFrom?: string
  validTo?: string
  /** 1 可领 0 停发 */
  status: number
  /** 当前用户是否已领取 */
  receivedByMe?: boolean
}

/** 我的券（id 是「用户券」id，下单/试算要传这个，不是 couponId） */
export interface UserCouponVO {
  id: number
  couponId: number
  name: string
  threshold: number
  amount: number
  /** 0 未使用 1 已使用（下单锁定/支付核销） 2 已过期 */
  status: number
  statusText?: string
  orderId?: number | null
  validFrom?: string
  validTo?: string
  receiveTime?: string
  useTime?: string
  /** 仅结算页返回：当前金额下这张券能不能用 */
  usable?: boolean
}

export interface OrderPreviewItemVO {
  productId: number
  skuId: number
  productName: string
  image?: string
  spec?: string
  price: number
  quantity: number
  lineAmount: number
  discountAmount: number
  payAmount: number
}

export interface OrderPreviewVO {
  goodsAmount: number
  freightAmount: number
  discountAmount: number
  payAmount: number
  userCouponId?: number | null
  /** 1 实物 2 虚拟：虚拟订单不展示地址块 */
  orderType?: number
  orderTypeText?: string
  items: OrderPreviewItemVO[]
  coupons: UserCouponVO[]
}

/** 虚拟商品凭证（卡密 / 外链），列表不含明文 */
export interface DigitalGrantVO {
  id: number
  orderId: number
  orderItemId: number
  productId: number
  skuId: number
  productName?: string
  spec?: string
  productImage?: string
  /** 1 卡密 2 外链 */
  deliveryType: number
  deliveryTypeText: string
  /** 1 已发放 2 已作废 9 发放失败 */
  status: number
  statusText: string
  viewCount: number
  grantTime?: string
}

export interface GrantContentVO {
  deliveryType: number
  /** 卡密类为「卡号|卡密」，外链类为 URL */
  content: string
  viewCount: number
}

export interface PreviewOrderDTO {
  /** 传了就按「立即购买」算，不传用购物车已勾选项 */
  skuId?: number
  quantity?: number
  userCouponId?: number
}