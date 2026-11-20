"use client"

import { ChevronsUpDown, LogOut } from "lucide-react"
import { Avatar, AvatarFallback } from "@/components/ui/avatar"
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuTrigger } from "@/components/ui/dropdown-menu"
import { SidebarMenu, SidebarMenuButton, SidebarMenuItem, useSidebar } from "@/components/ui/sidebar"
import { OverflowTooltip } from "@/components/shared/overflow-tooltip"

export function NavUser({ name, onLogout }: { name: string; onLogout: () => void }) {
  const { isMobile } = useSidebar()
  return <SidebarMenu><SidebarMenuItem><DropdownMenu><DropdownMenuTrigger asChild><SidebarMenuButton size="lg" className="data-[state=open]:bg-sidebar-accent data-[state=open]:text-sidebar-accent-foreground"><Avatar className="h-8 w-8 rounded-lg"><AvatarFallback className="rounded-lg">管</AvatarFallback></Avatar><div className="grid flex-1 text-left text-sm leading-tight"><OverflowTooltip content={name} className="block max-w-full truncate"><span className="truncate font-medium">{name}</span></OverflowTooltip><OverflowTooltip content="当前会话" className="block max-w-full truncate"><span className="truncate text-xs">当前会话</span></OverflowTooltip></div><ChevronsUpDown className="ml-auto size-4" /></SidebarMenuButton></DropdownMenuTrigger><DropdownMenuContent className="w-(--radix-dropdown-menu-trigger-width) min-w-56 rounded-lg" side={isMobile ? "bottom" : "right"} align="end" sideOffset={4}><DropdownMenuLabel className="p-0 font-normal"><div className="flex items-center gap-2 px-1 py-1.5 text-left text-sm"><Avatar className="h-8 w-8 rounded-lg"><AvatarFallback className="rounded-lg">管</AvatarFallback></Avatar><div className="grid flex-1 text-left text-sm leading-tight"><OverflowTooltip content={name} className="block max-w-full truncate"><span className="truncate font-medium">{name}</span></OverflowTooltip><span className="text-xs">当前会话</span></div></div></DropdownMenuLabel><DropdownMenuSeparator /><DropdownMenuItem onSelect={onLogout}><LogOut />退出登录</DropdownMenuItem></DropdownMenuContent></DropdownMenu></SidebarMenuItem></SidebarMenu>
}
