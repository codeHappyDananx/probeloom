import { ChevronsUpDown, FlaskConical } from "lucide-react"
import { NavMain } from "@/components/nav-main"
import { NavUser } from "@/components/nav-user"
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuTrigger } from "@/components/ui/dropdown-menu"
import { Sidebar, SidebarContent, SidebarFooter, SidebarHeader, SidebarMenu, SidebarMenuButton, SidebarMenuItem, SidebarRail, useSidebar } from "@/components/ui/sidebar"
import { OverflowTooltip } from "@/components/shared/overflow-tooltip"
import { navigationItems } from "./navigation"
import type { View } from "@/types/platform"

export function AppSidebar({ view, onNavigate, onLogout }: { view: View; onNavigate: (view: View) => void; onLogout: () => void }) {
  const { isMobile } = useSidebar()
  return <Sidebar collapsible="icon">
    <SidebarHeader><SidebarMenu><SidebarMenuItem><DropdownMenu><DropdownMenuTrigger asChild><SidebarMenuButton size="lg" className="data-[state=open]:bg-sidebar-accent data-[state=open]:text-sidebar-accent-foreground"><div className="flex aspect-square size-8 items-center justify-center rounded-lg bg-sidebar-primary text-sidebar-primary-foreground"><FlaskConical className="size-4" /></div><div className="grid flex-1 text-left text-sm leading-tight"><OverflowTooltip content="ProbeLoom" className="block max-w-full truncate"><span className="truncate font-medium">ProbeLoom</span></OverflowTooltip><OverflowTooltip content="接口与数据测试平台" className="block max-w-full truncate"><span className="truncate text-xs">接口与数据测试平台</span></OverflowTooltip></div><ChevronsUpDown className="ml-auto size-4" /></SidebarMenuButton></DropdownMenuTrigger><DropdownMenuContent className="w-(--radix-dropdown-menu-trigger-width) min-w-56 rounded-lg" side={isMobile ? "bottom" : "right"} align="start" sideOffset={4}><DropdownMenuLabel className="text-xs text-muted-foreground">工作区</DropdownMenuLabel><DropdownMenuItem disabled className="gap-2 p-2"><div className="flex size-6 items-center justify-center rounded-md border"><FlaskConical className="size-3.5 shrink-0" /></div><span>ProbeLoom</span></DropdownMenuItem></DropdownMenuContent></DropdownMenu></SidebarMenuItem></SidebarMenu></SidebarHeader>
    <SidebarContent><NavMain items={navigationItems.map((item) => ({ title: item.label, icon: item.icon, view: item.key, description: item.description }))} activeView={view} onNavigate={onNavigate} /></SidebarContent>
    <SidebarFooter><NavUser name="本地管理员" onLogout={onLogout} /></SidebarFooter>
    <SidebarRail />
  </Sidebar>
}
