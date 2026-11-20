import { useLayoutEffect, useRef, useState, type ReactNode } from "react"
import { cn } from "@/lib/utils"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"

type OverflowTooltipProps = {
  content: ReactNode
  children: ReactNode
  className?: string
  disabled?: boolean
}

export function OverflowTooltip({ content, children, className, disabled = false }: OverflowTooltipProps) {
  const triggerRef = useRef<HTMLDivElement>(null)
  const [overflowed, setOverflowed] = useState(false)

  useLayoutEffect(() => {
    const element = triggerRef.current
    if (!element || disabled) return
    const checkOverflow = () => {
      const nodes = [element, ...Array.from(element.querySelectorAll<HTMLElement>("*"))]
      setOverflowed(nodes.some((node) => node.scrollWidth > node.clientWidth || node.scrollHeight > node.clientHeight))
    }
    checkOverflow()
    const observer = new ResizeObserver(checkOverflow)
    observer.observe(element)
    return () => observer.disconnect()
  }, [children, disabled])

  const trigger = <div ref={triggerRef} className={cn("min-w-0", className)}>{children}</div>
  if (disabled || !overflowed || content === undefined || content === null || content === "") return trigger

  return (
    <Tooltip>
      <TooltipTrigger asChild>{trigger}</TooltipTrigger>
      <TooltipContent className="max-w-[min(48rem,calc(100vw-2rem))] whitespace-pre-wrap break-words">
        {content}
      </TooltipContent>
    </Tooltip>
  )
}
