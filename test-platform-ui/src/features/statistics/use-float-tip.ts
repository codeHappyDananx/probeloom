import { useRef, useState, type ReactNode } from "react"

export type Tip = { anchor: DOMRect; content: ReactNode }

/** 共享浮层提示：一个容器只渲染一个，跟随悬停或键盘焦点所在的柱子、格子。 */
export function useFloatTip() {
  const host = useRef<HTMLDivElement>(null)
  const [tip, setTip] = useState<Tip>()
  return {
    host, tip,
    show: (element: HTMLElement, content: ReactNode) => setTip({ anchor: element.getBoundingClientRect(), content }),
    hide: () => setTip(undefined),
  }
}
