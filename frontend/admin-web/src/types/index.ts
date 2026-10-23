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

/** 后台角色 */
export interface RoleVO {
  id: number
  code: string
  name: string
  description?: string
  /** 1 = 内置，不可删 */
  builtIn: number
  status: number
  permissionIds: number[]
}

/** 后台权限点 */
export interface PermissionVO {
  id: number
  code: string
  name: string
  permGroup: string
  sort: number
}

/** 管理员账号 */
export interface AdminUserVO {
  id: number
  username: string
  nickname?: string
  status: number
  createTime?: string
  roleIds: number[]
  roleNames: string
}

export interface LoginVO {
  token: string
  userId: number
  username: string
  nickname?: string
  avatar?: string
  role: number
  /** 后台角色 code（仅 role=1） */
  roles?: string[]
  /** 权限码（仅 role=1），前端据此控制菜单与按钮 */
  permissions?: string[]
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
  /** 1 实物 2 虚拟 */
  productType?: number
  productTypeText?: string
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
  /** 运费模板 id，null = 包邮；虚拟商品恒为 null */
  freightTemplateId?: number | null
  /** 1 实物 2 虚拟 */
  productType?: number
  productTypeText?: string
  /** 虚拟：1 卡密 2 外链 */
  deliveryType?: number | null
  deliveryTypeText?: string
  deliveryUrl?: string | null
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
  /** 运费模板 id，不传 = 包邮（虚拟商品忽略） */
  freightTemplateId?: number | null
  /** 1 实物 2 虚拟，不传默认实物 */
  productType?: number
  /** 虚拟必填：1 卡密 2 外链 */
  deliveryType?: number | null
  /** 外链类必填 */
  deliveryUrl?: string | null
  skus: SkuDTO[]
}

/** 优惠券模板（后台维护；领券中心也用同一份字段） */
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
}

export interface CouponDTO {
  name: string
  threshold: number | null
  amount: number | null
  total: number | null
  validFrom?: string
  validTo?: string
  status?: number
}

/** 数据维护 */
export interface TableStatVO {
  name: string
  rowCount: number
}

export interface MaintenanceVO {
  tables: TableStatVO[]
  searchLogRetainDays: number
  searchLogLastCleanAt?: string
  searchLogLastDeleted: number
}

/** 操作审计 */
export interface AdminAuditLogVO {
  id: number
  adminId: number
  adminName?: string
  action: string
  actionText: string
  targetType?: string
  targetId?: number
  detail?: string
  ip?: string
  createTime?: string
}

/** 后台看板概览 */
export interface DashboardSummaryVO {
  todayGmv: number
  todayOrders: number
  pendingShip: number
  pendingAfterSale: number
  pendingGrant: number
  lowStockCount: number
  lowStockThreshold: number
}

/** 低库存 SKU */
export interface LowStockVO {
  skuId: number
  productId: number
  productName: string
  spec?: string
  stock: number
  lockedStock: number
  available: number
}

/** 销售概览 */
export interface ReportSummaryVO {
  gmv: number
  refundAmount: number
  netGmv: number
  orderCount: number
  buyerCount: number
  avgOrderAmount: number
  newUsers: number
  couponReceived: number
  couponUsed: number
  couponUseRate: number
}

export interface DailyStatVO {
  statDate: string
  orderCount: number
  gmv: number
}

export interface TopProductVO {
  productId: number
  productName: string
  quantity: number
  amount: number
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
  userLabel: string
  anonymous: number
  /** 1 展示 0 已隐藏 */
  status: number
  /** 1 = 该订单已退款：评价保留但不计入评分 */
  refunded?: number
  createTime?: string
}

/** 某张券的领取记录（后台明细） */
export interface CouponRecordVO {
  /** 用户券 id */
  id: number
  userId: number
  username?: string
  nickname?: string
  /** 0 未使用 1 已使用 2 已过期 */
  status: number
  statusText: string
  receiveTime?: string
  useTime?: string
  orderId?: number | null
}

export interface FreightTemplateVO {
  id: number
  name: string
  /** FIXED 固定运费 / FREE_OVER 满额包邮 */
  type: 'FIXED' | 'FREE_OVER'
  typeText: string
  baseFee: number
  freeThreshold?: number | null
  status: number
}

export interface FreightTemplateDTO {
  name: string
  type: string
  baseFee?: number | null
  freeThreshold?: number | null
  status?: number
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
  /** 该行实付（未减优惠的老数据可能为 null） */
  payAmount?: number | null
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
  /** 1 实物 2 虚拟（虚拟订单支付即发放、直接完成，没有发货动作） */
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
  handleStatus?: number
  handleStatusText?: string
  handleAction?: string
  handleRemark?: string
  handledBy?: string
  handleTime?: string
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
  unhandledCount?: number
  createTime?: string
  diffs?: ReconcileDiffVO[]
}