"use client"

import { ChevronRight, type LucideIcon } from "lucide-react"
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible"
import { SidebarGroup, SidebarGroupLabel, SidebarMenu, SidebarMenuButton, SidebarMenuItem, SidebarMenuSub, SidebarMenuSubButton, SidebarMenuSubItem } from "@/components/ui/sidebar"
import { OverflowTooltip } from "@/components/shared/overflow-tooltip"
import type { View } from "@/types/platform"

type NavigationItem = { title: string; icon?: LucideIcon; view: View; description: string }

export function NavMain({ items, activeView, onNavigate }: { items: NavigationItem[]; activeView: View; onNavigate: (view: View) => void }) {
  return <SidebarGroup>
    <SidebarGroupLabel>工作区</SidebarGroupLabel>
    <SidebarMenu>
      <Collapsible asChild defaultOpen className="group/collapsible">
        <SidebarMenuItem>
          <CollapsibleTrigger asChild>
            <SidebarMenuButton tooltip="工作区"><span>测试管理</span><ChevronRight className="ml-auto transition-transform duration-200 group-data-[state=open]/collapsible:rotate-90" /></SidebarMenuButton>
          </CollapsibleTrigger>
          <CollapsibleContent>
            <SidebarMenuSub>
              {items.map((item) => <SidebarMenuSubItem key={item.view}><SidebarMenuSubButton asChild isActive={activeView === item.view}><a href={`#${item.view}`} onClick={(event) => { event.preventDefault(); onNavigate(item.view) }}>{item.icon && <item.icon />}<OverflowTooltip content={item.title} className="block min-w-0 flex-1"><span className="block truncate">{item.title}</span></OverflowTooltip></a></SidebarMenuSubButton></SidebarMenuSubItem>)}
            </SidebarMenuSub>
          </CollapsibleContent>
        </SidebarMenuItem>
      </Collapsible>
    </SidebarMenu>
  </SidebarGroup>
}
