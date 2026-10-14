import { useEffect, useRef } from 'react'

/** Вызывает onReach, когда отметка в конце списка появляется на экране (с запасом в 400px). */
export function useInfiniteScroll(onReach: () => void, enabled: boolean) {
  const ref = useRef<HTMLDivElement>(null)
  const callback = useRef(onReach)
  useEffect(() => {
    callback.current = onReach
  })

  useEffect(() => {
    const el = ref.current
    if (!el || !enabled) return
    const observer = new IntersectionObserver((entries) => entries[0].isIntersecting && callback.current(), {
      rootMargin: '400px 0px',
    })
    observer.observe(el)
    return () => observer.disconnect()
  }, [enabled])

  return ref
}
