import { http } from './http'
import type { AfterSaleVO,
  ReconcileBatchVO, 
  CategoryDTO,
  CategoryVO,
  CouponDTO,
  AdminAuditLogVO,
  CouponRecordVO,
  CouponVO,
  DashboardSummaryVO,
  LowStockVO,
  MaintenanceVO,
  FreightTemplateDTO,
  FreightTemplateVO,
  LoginVO,
  OrderVO,
  PageResult,
  ProductDTO,
  ProductDetailVO,
  ProductVO,
  ReportSummaryVO,
  ReviewVO,
  DailyStatVO,
  TopProductVO,
  RoleVO,
  PermissionVO,
  AdminUserVO
} from '@/types'

/** 后台认证（仅 role=1 可登录） */
export const authApi = {
  login: (username: string, password: string) =>
    http.post<never, LoginVO>('/admin/auth/login', { username, password }),
  /** 退出：Cookie 是 HttpOnly 的，必须由服务端清除 */
  logout: () => http.post<never, void>('/admin/auth/logout'),
  /** 当前登录人（含权限码）：刷新菜单权限用，不签发新 token */
  me: () => http.get<never, LoginVO>('/admin/auth/me')
}

/** RBAC：角色 / 权限 / 管理员授权 */
export const rbacApi = {
  roles: () => http.get<never, RoleVO[]>('/admin/rbac/roles'),
  createRole: (dto: { code: string; name: string; description?: string; status?: number }) =>
    http.post<never, number>('/admin/rbac/roles', dto),
  updateRole: (id: number, dto: { code: string; name: string; description?: string; status?: number }) =>
    http.put<never, void>(`/admin/rbac/roles/${id}`, dto),
  deleteRole: (id: number) => http.delete<never, void>(`/admin/rbac/roles/${id}`),
  setRolePermissions: (id: number, permissionIds: number[]) =>
    http.put<never, void>(`/admin/rbac/roles/${id}/permissions`, permissionIds),
  permissions: () => http.get<never, PermissionVO[]>('/admin/rbac/permissions'),
  admins: () => http.get<never, AdminUserVO[]>('/admin/rbac/admins'),
  setUserRoles: (userId: number, roleIds: number[]) =>
    http.put<never, void>(`/admin/rbac/admins/${userId}/roles`, roleIds)
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

/** 数据维护 */
export const maintenanceApi = {
  stats: () => http.get<never, MaintenanceVO>('/admin/maintenance/stats')
}

/** 操作审计 */
export const auditApi = {
  page: (params: { pageNum: number; pageSize: number; action?: string }) =>
    http.get<never, PageResult<AdminAuditLogVO>>('/admin/audit/page', { params })
}

/** 首页看板 */
export const dashboardApi = {
  summary: () => http.get<never, DashboardSummaryVO>('/admin/dashboard/summary'),
  lowStock: (limit = 10) => http.get<never, LowStockVO[]>('/admin/dashboard/low-stock', { params: { limit } })
}

/** 销售报表 */
export const reportApi = {
  summary: (days: number) => http.get<never, ReportSummaryVO>('/admin/report/summary', { params: { days } }),
  daily: (days: number) => http.get<never, DailyStatVO[]>('/admin/report/daily', { params: { days } }),
  topProducts: (days: number, limit = 10) =>
    http.get<never, TopProductVO[]>('/admin/report/top-products', { params: { days, limit } })
}

/** 评价管理：分页 / 隐藏恢复 */
export const reviewApi = {
  page: (params: { pageNum: number; pageSize: number; productId?: number; status?: number }) =>
    http.get<never, PageResult<ReviewVO>>('/admin/review/page', { params }),
  updateStatus: (id: number, status: number) =>
    http.put<never, void>(`/admin/review/${id}/status`, null, { params: { status } })
}

/** 卡密池：导入 / 剩余数量 */
export const cardKeyApi = {
  importKeys: (skuId: number, lines: string[]) =>
    http.post<never, number>('/admin/card-key/import', { skuId, lines }),
  count: (skuId: number) => http.get<never, number>('/admin/card-key/count', { params: { skuId } })
}

/** 优惠券：分页 / 新增 / 修改 / 停发恢复 / 删除 */
export const couponApi = {
  page: (params: { pageNum: number; pageSize: number; status?: number; keyword?: string }) =>
    http.get<never, PageResult<CouponVO>>('/admin/coupon/page', { params }),
  /** 领取明细（谁领的、用没用、用在哪一单） */
  records: (id: number, limit = 200) =>
    http.get<never, CouponRecordVO[]>(`/admin/coupon/${id}/records`, { params: { limit } }),
  create: (dto: CouponDTO) => http.post<never, number>('/admin/coupon', dto),
  update: (id: number, dto: CouponDTO) => http.put<never, void>(`/admin/coupon/${id}`, dto),
  updateStatus: (id: number, status: number) =>
    http.put<never, void>(`/admin/coupon/${id}/status`, null, { params: { status } }),
  remove: (id: number) => http.delete<never, void>(`/admin/coupon/${id}`)
}

/** 运费模板：列表（不分页）/ 新增 / 修改 / 启停 / 删除 */
export const freightApi = {
  list: (status?: number) => http.get<never, FreightTemplateVO[]>('/admin/freight', { params: { status } }),
  create: (dto: FreightTemplateDTO) => http.post<never, number>('/admin/freight', dto),
  update: (id: number, dto: FreightTemplateDTO) => http.put<never, void>(`/admin/freight/${id}`, dto),
  updateStatus: (id: number, status: number) =>
    http.put<never, void>(`/admin/freight/${id}/status`, null, { params: { status } }),
  remove: (id: number) => http.delete<never, void>(`/admin/freight/${id}`)
}

export const orderApi = {
  page: (params: { pageNum: number; pageSize: number; status?: number; keyword?: string }) =>
    http.get<never, PageResult<OrderVO>>('/admin/order/page', { params }),
  detail: (id: number) => http.get<never, OrderVO>(`/admin/order/${id}`),
  /** 发货必须带快递公司 + 运单号 */
  ship: (id: number, body: { company: string; shipNo: string }) =>
    http.post<never, void>(`/admin/order/${id}/ship`, body),
  /** 虚拟商品补发（支付成功但发放失败时用） */
  regrant: (id: number) => http.post<never, void>(`/admin/order/${id}/grant`),
  /** 导出 CSV：返回原始 Blob，交给页面触发下载 */
  exportCsv: (params: { status?: number; keyword?: string; limit?: number }) =>
    http.get<never, Blob>('/admin/order/export', { params, responseType: 'blob' }),
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