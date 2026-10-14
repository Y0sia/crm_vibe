import { useQueryClient } from '@tanstack/react-query'
import { useEffect } from 'react'
import { keys } from '../api/hooks'

type LeadActivity = { leadId: number; kind: 'LEAD_CREATED' | 'LEAD_UPDATED' | 'MESSAGE_CREATED' }

/** Сервер сообщает «у лида что-то изменилось» — перезапрашиваем то, что видно на экране. */
export function useLiveUpdates(enabled: boolean) {
  const qc = useQueryClient()
  useEffect(() => {
    if (!enabled) return
    const source = new EventSource('/api/events')
    source.addEventListener('lead', (e) => {
      const activity = JSON.parse((e as MessageEvent<string>).data) as LeadActivity
      qc.invalidateQueries({ queryKey: keys.leads })
      qc.invalidateQueries({ queryKey: keys.lead(activity.leadId) })
      qc.invalidateQueries({ queryKey: keys.newCount })
      qc.invalidateQueries({ queryKey: keys.dashboard })
      if (activity.kind !== 'MESSAGE_CREATED') {
        qc.invalidateQueries({ queryKey: keys.statuses })
        qc.invalidateQueries({ queryKey: keys.tags })
      }
    })
    return () => source.close()
  }, [enabled, qc])
}
