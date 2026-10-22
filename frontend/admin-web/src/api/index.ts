import { http } from './http'
import type { AfterSaleVO,
  ReconcileBatchVO, 
  CategoryDTO,
  CategoryVO,
  LoginVO,
  OrderVO,
  PageResult,
  ProductDTO,
  ProductDetailVO,
  ProductVO
} from '@/types'

/** 后台认证（仅 role=1 可登录） */
export const authApi = {
  login: (username: string, password: string) =>
    http.post<never, LoginVO>('/admin/auth/login', { username, password }),
  /** 退出：Cookie 是 HttpOnly 的，必须由服务端清除 */
  logout: () => http.post<never, void>('/admin/auth/logout')
}

export const categoryApi = {
  tree: () => http.get<never, CategoryVO[]>('/admin/category/tree'),
  create: (dto: CategoryDTO) => http.post<never, number>('/admin/category', dto),
  update: (id: number, dto: CategoryDTO) => http.put<never, void>(`/admin/category/${id}`, dto),
  updateStatus: (id: number, status: number) =>
    http.put<never, void>(`/admin/category/${id}/status`, null, { params: { status } }),
  remove: (id: number) => http.delete<never, void>(`/admin/category/${id}`)
}

export const productApi = {
  page: (params: { pageNum: number; pageSize: number; categoryId?: number; keyword?: string; status?: number }) =>
    http.get<never, PageResult<ProductVO>>('/admin/product/page', { params }),
  detail: (id: number) => http.get<never, ProductDetailVO>(`/admin/product/${id}`),
  create: (dto: ProductDTO) => http.post<never, number>('/admin/product', dto),
  update: (id: number, dto: ProductDTO) => http.put<never, void>(`/admin/product/${id}`, dto),
  updateStatus: (id: number, status: number) =>
    http.put<never, void>(`/admin/product/${id}/status`, null, { params: { status } }),
  remove: (id: number) => http.delete<never, void>(`/admin/product/${id}`)
}

export const orderApi = {
  page: (params: { pageNum: number; pageSize: number; status?: number; keyword?: string }) =>
    http.get<never, PageResult<OrderVO>>('/admin/order/page', { params }),
  detail: (id: number) => http.get<never, OrderVO>(`/admin/order/${id}`),
  ship: (id: number) => http.post<never, void>(`/admin/order/${id}/ship`),
  close: (id: number) => http.post<never, void>(`/admin/order/${id}/close`)
}

/** 售后：列表 / 详情 / 同意退款 / 拒绝 */
export const afterSaleApi = {
  page: (params: { pageNum: number; pageSize: number; status?: number }) =>
    http.get<never, PageResult<AfterSaleVO>>('/admin/after-sale/page', { params }),
  detail: (id: number) => http.get<never, AfterSaleVO>(`/admin/after-sale/${id}`),
  approve: (id: number, remark?: string) =>
    http.post<never, void>(`/admin/after-sale/${id}/approve`, { remark }),
  reject: (id: number, remark?: string) =>
    http.post<never, void>(`/admin/after-sale/${id}/reject`, { remark })
}
/** 对账：上传账单 / 批次列表 / 详情 */
export const reconcileApi = {
  submit: (data: { channel: string; billDate: string; content: string }) =>
    http.post<never, ReconcileBatchVO>('/admin/reconcile', data),
  page: (pageNum = 1, pageSize = 10) =>
    http.get<never, PageResult<ReconcileBatchVO>>('/admin/reconcile/page', { params: { pageNum, pageSize } }),
  detail: (id: number) => http.get<never, ReconcileBatchVO>(`/admin/reconcile/${id}`),
  /** 处理一条差异（CONFIRM / IGNORE），返回刷新后的批次 */
  handleDiff: (diffId: number, action: string, remark?: string) =>
    http.post<never, ReconcileBatchVO>(`/admin/reconcile/diff/${diffId}/handle`, { action, remark })
  }