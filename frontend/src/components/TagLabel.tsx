import type { TagColor } from '../api/types'
import { TAG_COLORS } from '../lib/palette'

export function TagLabel({ name, color }: { name: string; color: TagColor }) {
  return (
    <>
      <span className="hash" style={{ color: TAG_COLORS[color].c }}>
        #
      </span>
      {name}
    </>
  )
}
