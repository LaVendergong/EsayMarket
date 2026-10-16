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
  { name: '4', label: '已关闭' }
] as const
