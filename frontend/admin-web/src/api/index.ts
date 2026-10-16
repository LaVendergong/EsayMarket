import { http } from './http'
import type {
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
    http.post<never, LoginVO>('/admin/auth/login', { username, password })
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
