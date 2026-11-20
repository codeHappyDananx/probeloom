import { ChartNoAxesCombined, Clock3, FolderCog, History, ListChecks, ServerCog, Trash2, type LucideIcon } from "lucide-react"
import type { View } from "@/types/platform"

export type NavigationItem = { key: View; label: string; icon: LucideIcon; description: string }

export const navigationItems: NavigationItem[] = [
  { key: "statistics", label: "执行统计", icon: ChartNoAxesCombined, description: "查看执行趋势和失败案例" },
  { key: "groups", label: "分组管理", icon: FolderCog, description: "维护案例归属与回归范围" },
  { key: "cases", label: "案例管理", icon: ListChecks, description: "执行案例并查看结果" },
  { key: "recycle", label: "回收站", icon: Trash2, description: "恢复误删案例" },
  { key: "schedules", label: "定时计划", icon: Clock3, description: "维护自动执行计划" },
  { key: "executions", label: "执行记录", icon: History, description: "查看历史执行详情" },
  { key: "config", label: "基础配置", icon: ServerCog, description: "维护协议连接与数据库" },
]

export const pageTitles: Record<View, string> = Object.fromEntries(navigationItems.map((item) => [item.key, item.label])) as Record<View, string>
