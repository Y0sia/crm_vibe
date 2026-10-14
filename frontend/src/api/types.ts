export type Role = 'ADMIN' | 'MANAGER'

export type Me = { id: number; login: string; displayName: string; role: Role }

export type StatusColor = 'BLUE' | 'ORANGE' | 'PURPLE' | 'TEAL' | 'GREEN' | 'PINK' | 'YELLOW' | 'GRAY'
export type TagColor = 'BRICK' | 'BLUE' | 'PURPLE' | 'GREEN' | 'RED' | 'TEAL' | 'MUSTARD' | 'GRAPHITE'
export type LeadSource = 'CHANNEL' | 'GROUP' | 'ADS' | 'REFERRAL' | 'OTHER'

export type Status = { id: number; name: string; color: StatusColor; position: number; won: boolean; leadCount: number }
export type Tag = { id: number; name: string; color: TagColor; leadCount: number }

export type UserRef = { id: number; displayName: string }
export type TagRef = { id: number; name: string; color: TagColor }

export type LeadListItem = {
  id: number
  name: string
  username?: string
  statusId: number
  tags: TagRef[]
  manager?: UserRef
  source?: LeadSource
  lastMessageText?: string
  lastMessageAt?: string
}

export type LeadPage = {
  items: LeadListItem[]
  page: number
  size: number
  found: number
  total: number
  statusCounts: Record<string, number>
}

export type LeadCard = {
  id: number
  name: string
  firstName?: string
  lastName?: string
  username?: string
  telegramUserId: number
  phone?: string
  statusId: number
  tags: TagRef[]
  manager?: UserRef
  source?: LeadSource
  createdAt: string
  lastMessageAt?: string
  canReply: boolean
  replyBlockedReason?: string
  telegramLink: string
}

export type Message = {
  id: number
  direction: 'IN' | 'OUT'
  text: string
  sentAt: string
  author?: UserRef
  edited: boolean
  deleted: boolean
}

export type LeadEventType = 'CREATED' | 'STATUS_CHANGED' | 'MANAGER_CHANGED' | 'SOURCE_CHANGED' | 'TAG_ADDED' | 'TAG_REMOVED' | 'PHONE_CHANGED'

export type LeadEvent = {
  id: number
  type: LeadEventType
  oldValue?: string
  newValue?: string
  actor?: UserRef
  createdAt: string
}

export type User = { id: number; login: string; displayName: string; role: Role; active: boolean; createdAt: string }

export type Dashboard = {
  from: string
  to: string
  newLeadsByDay: { date: string; count: number }[]
  byStatus: { statusId: number; name: string; color: StatusColor; count: number }[]
  bySource: { source?: LeadSource; count: number; percent: number }[]
  byTag: { tagId: number; name: string; color: TagColor; count: number }[]
  managers: { managerId?: number; name: string; leads: number; deals?: number; avgResponseSeconds?: number }[]
}
