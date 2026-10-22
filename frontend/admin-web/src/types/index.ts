/** 与后端 com.mall 的 VO / DTO 一一对应（字段名保持一致，避免猜错） */

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

export interface CategoryVO {
  id: number
  parentId: number
  name: string
  sort: number
  icon?: string
  status: number
  children: CategoryVO[]
}

export interface CategoryDTO {
  parentId?: number
  name: string
  sort?: number
  icon?: string
  status?: number
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

export interface SkuDTO {
  /** 更新已有 SKU 时必传；新增时不传 */
  id?: number
  skuCode?: string
  spec?: string
  image?: string
  price: number | null
  stock: number | null
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

export interface ProductDTO {
  categoryId: number | null
  name: string
  subTitle?: string
  mainImage?: string
  images?: string[]
  detail?: string
  price: number | null
  status?: number
  skus: SkuDTO[]
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

export const ORDER_STATUS: { value: number; label: string; tag: 'info' | 'warning' | 'primary' | 'success' | 'danger' }[] = [
  { value: 0, label: '待支付', tag: 'warning' },
  { value: 1, label: '已支付', tag: 'primary' },
  { value: 2, label: '已发货', tag: 'info' },
  { value: 3, label: '已完成', tag: 'success' },
  { value: 4, label: '已关闭', tag: 'danger' }
]

/** 售后单（后台处理用） */
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
  refundNo?: string
  refundChannel?: string
  refundStatus?: number
  refundStatusText?: string
  refundTime?: string
  refundError?: string
  items?: OrderItemVO[]
}
/** 对账批次 */
export interface ReconcileDiffVO {
  id: number
  /** PAY / REFUND */
  bizType?: string
  refundNo?: string
  diffType: string
  diffTypeText: string
  paymentNo?: string
  channelTradeNo?: string
  localAmount?: number
  channelAmount?: number
  detail?: string
}

export interface ReconcileBatchVO {
  id: number
  channel: string
  channelText: string
  billDate: string
  totalLines: number
  matched: number
  missingLocal: number
  missingChannel: number
  amountMismatch: number
  /** 退款侧（和支付分开计） */
  refundTotalLines?: number
  refundMatched?: number
  refundMissingLocal?: number
  refundMissingChannel?: number
  refundAmountMismatch?: number
  status: number
  statusText: string
  createTime?: string
  diffs?: ReconcileDiffVO[]
}