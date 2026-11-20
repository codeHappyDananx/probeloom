import * as React from "react"
import { Palette, RefreshCw } from "lucide-react"
import { Breadcrumb, BreadcrumbItem, BreadcrumbList, BreadcrumbPage, BreadcrumbSeparator } from "@/components/ui/breadcrumb"
import { Badge } from "@/components/ui/badge"
import { OverflowTooltip } from "@/components/shared/overflow-tooltip"
import { Button } from "@/components/ui/button"
import { DropdownMenu, DropdownMenuContent, DropdownMenuLabel, DropdownMenuRadioGroup, DropdownMenuRadioItem, DropdownMenuSeparator, DropdownMenuTrigger } from "@/components/ui/dropdown-menu"
import { Separator } from "@/components/ui/separator"
import { SidebarTrigger } from "@/components/ui/sidebar"
import type { Environment, View } from "@/types/platform"
import { pageTitles } from "./navigation"

type ThemeName = "blue" | "teal" | "violet"

const themeOptions: Array<{ value: ThemeName; label: string; color: string }> = [
  { value: "blue", label: "蓝色", color: "#2563eb" },
  { value: "teal", label: "青色", color: "#0f9f9a" },
  { value: "violet", label: "紫色", color: "#7c3aed" },
]

export function AppHeader({ view, environments, loading }: { view: View; environments: Environment[]; loading: boolean }) {
  const defaultEnvironments = environments.filter((item) => item.defaultEnvironment).map((item) => item.name).join("、")
  const [theme, setTheme] = React.useState<ThemeName>(() => {
    const saved = window.localStorage.getItem("platform-theme")
    return saved === "teal" || saved === "violet" ? saved : "blue"
  })

  React.useEffect(() => {
    document.documentElement.dataset.theme = theme
    window.localStorage.setItem("platform-theme", theme)
  }, [theme])

  return <header className="flex h-16 shrink-0 items-center gap-2 border-b transition-[width,height] ease-linear group-has-data-[collapsible=icon]/sidebar-wrapper:h-12">
    <div className="flex w-full items-center gap-2 px-4"><SidebarTrigger className="-ml-1" /><Separator orientation="vertical" className="mr-2 data-[orientation=vertical]:h-4" /><Breadcrumb><BreadcrumbList><BreadcrumbItem className="hidden md:block"><span>ProbeLoom</span></BreadcrumbItem><BreadcrumbSeparator className="hidden md:block" /><BreadcrumbItem><BreadcrumbPage>{pageTitles[view]}</BreadcrumbPage></BreadcrumbItem></BreadcrumbList></Breadcrumb><div className="ml-auto flex min-w-0 items-center gap-2">{loading && <span className="hidden items-center gap-1.5 text-xs text-muted-foreground sm:flex"><RefreshCw className="size-3.5 animate-spin" />同步中</span>}<OverflowTooltip content={`环境：${defaultEnvironments || "未配置"}`} className="hidden max-w-[min(40rem,45vw)] sm:block"><Badge variant="outline" className="block max-w-full truncate">环境：{defaultEnvironments || "未配置"}</Badge></OverflowTooltip><DropdownMenu><DropdownMenuTrigger asChild><Button variant="outline" size="icon-sm" title="切换主题颜色" aria-label="切换主题颜色"><Palette /></Button></DropdownMenuTrigger><DropdownMenuContent align="end" className="w-36"><DropdownMenuLabel>主题颜色</DropdownMenuLabel><DropdownMenuSeparator /><DropdownMenuRadioGroup value={theme} onValueChange={(value) => setTheme(value as ThemeName)}>{themeOptions.map((option) => <DropdownMenuRadioItem key={option.value} value={option.value}><span className="size-3 rounded-full" style={{ backgroundColor: option.color }} />{option.label}</DropdownMenuRadioItem>)}</DropdownMenuRadioGroup></DropdownMenuContent></DropdownMenu></div></div>
  </header>
}
