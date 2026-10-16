import { http } from './http'
import type {
  AddressDTO,
  AddressVO,
  CartVO,
  CategoryVO,
  LoginVO,
  OrderCreateVO,
  OrderVO,
  PageResult,
  ProductDetailVO,
  ProductVO,
  UserVO
} from '@/types'

export const authApi = {
  register: (dto: { username: string; password: string; nickname?: string; phone?: string }) =>
    http.post<never, number>('/portal/auth/register', dto),
  login: (username: string, password: string) =>
    http.post<never, LoginVO>('/portal/auth/login', { username, password })
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
  detail: (id: number) => http.get<never, ProductDetailVO>(`/portal/product/${id}`)
}

export const cartApi = {
  list: () => http.get<never, CartVO[]>('/portal/cart'),
  add: (skuId: number, quantity: number) => http.post<never, number>('/portal/cart', { skuId, quantity }),
  updateQuantity: (id: number, quantity: number) =>
    http.put<never, void>(`/portal/cart/${id}`, null, { params: { quantity } }),
  updateChecked: (id: number, checked: number) =>
    http.put<never, void>(`/portal/cart/${id}/checked`, null, { params: { checked } }),
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
  /** 结算购物车里「已选中」的条目；addressId 不传用默认地址 */
  create: (addressId?: number) => http.post<never, OrderCreateVO>('/portal/order', { addressId }),
  page: (params: { pageNum: number; pageSize: number; status?: number }) =>
    http.get<never, PageResult<OrderVO>>('/portal/order/page', { params }),
  detail: (id: number) => http.get<never, OrderVO>(`/portal/order/${id}`),
  pay: (id: number) => http.post<never, void>(`/portal/order/${id}/pay`),
  cancel: (id: number) => http.post<never, void>(`/portal/order/${id}/cancel`),
  confirm: (id: number) => http.post<never, void>(`/portal/order/${id}/confirm`)
}
