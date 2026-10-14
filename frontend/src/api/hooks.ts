import { useInfiniteQuery, useMutation, useQuery, useQueryClient, type QueryKey } from '@tanstack/react-query'
import type { LeadFilters } from '../lib/leadFilter'
import { toApiParams } from '../lib/leadFilter'
import { api } from './client'
import type {
  Dashboard,
  LeadCard,
  LeadEvent,
  LeadPage,
  LeadSource,
  Me,
  Message,
  Role,
  Status,
  StatusColor,
  Tag,
  TagColor,
  User,
  UserRef,
} from './types'

export const keys = {
  me: ['me'] as const,
  statuses: ['statuses'] as const,
  tags: ['tags'] as const,
  managers: ['managers'] as const,
  users: ['users'] as const,
  leads: ['leads'] as const,
  lead: (id: number) => ['lead', id] as const,
  newCount: ['newCount'] as const,
  dashboard: ['dashboard'] as const,
}

// ---------- вход ----------

export function useMe() {
  return useQuery({ queryKey: keys.me, queryFn: () => api.get<Me>('/api/auth/me'), retry: false, staleTime: Infinity })
}

export function useLogin() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: { login: string; password: string }) => api.post<Me>('/api/auth/login', body),
    onSuccess: (me) => {
      // данные прошлого пользователя не должны остаться в кэше; сам запрос «me» не удаляем —
      // на него подписана защита маршрутов
      qc.removeQueries({ predicate: (q) => q.queryKey[0] !== keys.me[0] })
      qc.setQueryData(keys.me, me)
    },
  })
}

export function useLogout() {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: () => api.post<void>('/api/auth/logout'),
    onSettled: () => {
      qc.setQueryData(keys.me, null)
      qc.removeQueries({ predicate: (q) => q.queryKey[0] !== keys.me[0] })
    },
  })
}

// ---------- справочники ----------

export const useStatuses = () => useQuery({ queryKey: keys.statuses, queryFn: () => api.get<Status[]>('/api/statuses') })
export const useTags = () => useQuery({ queryKey: keys.tags, queryFn: () => api.get<Tag[]>('/api/tags') })
export const useManagers = () =>
  useQuery({ queryKey: keys.managers, queryFn: () => api.get<UserRef[]>('/api/users/managers') })

// ---------- лиды ----------

export function useLeads(filters: LeadFilters, page: number, size = 25) {
  const params = toApiParams(filters, page, size).toString()
  return useQuery({
    queryKey: [...keys.leads, params],
    queryFn: () => api.get<LeadPage>(`/api/leads?${params}`),
    placeholderData: (previous) => previous,
  })
}

export const useNewCount = () =>
  useQuery({ queryKey: keys.newCount, queryFn: () => api.get<{ count: number }>('/api/leads/new-count') })

export const useLead = (id: number) =>
  useQuery({ queryKey: [...keys.lead(id), 'card'], queryFn: () => api.get<LeadCard>(`/api/leads/${id}`) })

export const useLeadMessages = (id: number) =>
  useQuery({ queryKey: [...keys.lead(id), 'messages'], queryFn: () => api.get<Message[]>(`/api/leads/${id}/messages`) })

export const useLeadEvents = (id: number) =>
  useQuery({ queryKey: [...keys.lead(id), 'events'], queryFn: () => api.get<LeadEvent[]>(`/api/leads/${id}/events`) })

/** После изменения лида обновляем всё, где он виден. */
function useInvalidateLead() {
  const qc = useQueryClient()
  return (id: number) => {
    const affected: QueryKey[] = [keys.lead(id), keys.leads, keys.newCount, keys.statuses, keys.tags, keys.dashboard]
    return Promise.all(affected.map((queryKey) => qc.invalidateQueries({ queryKey })))
  }
}

export function useLeadMutations(id: number) {
  const invalidate = useInvalidateLead()
  const opts = { onSuccess: () => invalidate(id) }
  return {
    setStatus: useMutation({ mutationFn: (statusId: number) => api.put(`/api/leads/${id}/status`, { statusId }), ...opts }),
    setManager: useMutation({
      mutationFn: (managerId: number | null) => api.put(`/api/leads/${id}/manager`, { managerId }),
      ...opts,
    }),
    setSource: useMutation({
      mutationFn: (source: LeadSource | null) => api.put(`/api/leads/${id}/source`, { source }),
      ...opts,
    }),
    addTag: useMutation({ mutationFn: (tagId: number) => api.post(`/api/leads/${id}/tags/${tagId}`), ...opts }),
    removeTag: useMutation({ mutationFn: (tagId: number) => api.delete(`/api/leads/${id}/tags/${tagId}`), ...opts }),
    reply: useMutation({
      mutationFn: (text: string) => api.post<Message>(`/api/leads/${id}/messages`, { text }),
      ...opts,
    }),
  }
}

/** Смена статуса прямо из строки списка. */
export function useSetLeadStatus() {
  const invalidate = useInvalidateLead()
  return useMutation({
    mutationFn: ({ id, statusId }: { id: number; statusId: number }) => api.put(`/api/leads/${id}/status`, { statusId }),
    onSuccess: (_d, v) => invalidate(v.id),
  })
}

// ---------- настройки ----------

function useSettingsMutation<V>(fn: (v: V) => Promise<unknown>, invalidate: QueryKey[]) {
  const qc = useQueryClient()
  return useMutation({
    mutationFn: fn,
    onSuccess: () => Promise.all(invalidate.map((queryKey) => qc.invalidateQueries({ queryKey }))),
  })
}

const afterStatusChange: QueryKey[] = [keys.statuses, keys.leads, keys.dashboard, keys.newCount, ['lead']]
const afterTagChange: QueryKey[] = [keys.tags, keys.leads, keys.dashboard, ['lead']]

export function useStatusMutations() {
  return {
    create: useSettingsMutation((v: { name: string; color: StatusColor }) => api.post('/api/statuses', v), afterStatusChange),
    update: useSettingsMutation(
      ({ id, ...v }: { id: number; name: string; color: StatusColor }) => api.patch(`/api/statuses/${id}`, v),
      afterStatusChange,
    ),
    reorder: useSettingsMutation((ids: number[]) => api.put('/api/statuses/order', { ids }), afterStatusChange),
    remove: useSettingsMutation((id: number) => api.delete(`/api/statuses/${id}`), afterStatusChange),
  }
}

export function useTagMutations() {
  return {
    create: useSettingsMutation((v: { name: string; color: TagColor }) => api.post('/api/tags', v), afterTagChange),
    update: useSettingsMutation(
      ({ id, ...v }: { id: number; name: string; color: TagColor }) => api.patch(`/api/tags/${id}`, v),
      afterTagChange,
    ),
    remove: useSettingsMutation((id: number) => api.delete(`/api/tags/${id}`), afterTagChange),
  }
}

export const useUsers = () => useQuery({ queryKey: keys.users, queryFn: () => api.get<User[]>('/api/admin/users') })

export function useUserMutations() {
  const after: QueryKey[] = [keys.users, keys.managers, keys.dashboard]
  return {
    create: useSettingsMutation(
      (v: { login: string; password: string; displayName: string; role: Role }) => api.post('/api/admin/users', v),
      after,
    ),
    update: useSettingsMutation(
      ({ id, ...v }: { id: number; displayName: string; role: Role }) => api.patch(`/api/admin/users/${id}`, v),
      after,
    ),
    password: useSettingsMutation(
      ({ id, password }: { id: number; password: string }) => api.put(`/api/admin/users/${id}/password`, { password }),
      after,
    ),
    block: useSettingsMutation((id: number) => api.post(`/api/admin/users/${id}/block`), after),
    unblock: useSettingsMutation((id: number) => api.post(`/api/admin/users/${id}/unblock`), after),
  }
}

// ---------- дашборд ----------

export const useDashboard = (from: string, to: string) =>
  useQuery({
    queryKey: [...keys.dashboard, from, to],
    queryFn: () => api.get<Dashboard>(`/api/dashboard?from=${from}&to=${to}`),
    placeholderData: (previous) => previous,
  })

/** Мобильный список: страницы подгружаются кнопкой «Показать ещё». */
export function useLeadsInfinite(filters: LeadFilters, enabled: boolean) {
  const base = toApiParams(filters, 0, 25)
  base.delete('page')
  const params = base.toString()
  return useInfiniteQuery({
    queryKey: [...keys.leads, 'infinite', params],
    queryFn: ({ pageParam }) => api.get<LeadPage>(`/api/leads?${params}&page=${pageParam}`),
    initialPageParam: 0,
    getNextPageParam: (last) => ((last.page + 1) * last.size < last.found ? last.page + 1 : undefined),
    enabled,
  })
}
