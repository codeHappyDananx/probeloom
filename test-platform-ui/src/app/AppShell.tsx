import type { ReactNode } from "react"
import { SidebarInset, SidebarProvider } from "@/components/ui/sidebar"
import type { Bootstrap, View } from "@/types/platform"
import { AppHeader } from "./AppHeader"
import { AppSidebar } from "./AppSidebar"

export function AppShell({ view, setView, data, loading, onLogout, children }: { view: View; setView: (view: View) => void; data: Bootstrap; loading: boolean; onLogout: () => void; children: ReactNode }) {
  return <SidebarProvider defaultOpen className="h-[100dvh] min-h-0">
    <AppSidebar view={view} onNavigate={setView} onLogout={onLogout} />
    <SidebarInset className="min-h-0 min-w-0 overflow-hidden">
      <AppHeader view={view} environments={data.environments} loading={loading} />
      <main className="flex min-h-0 min-w-0 flex-1 flex-col overflow-hidden bg-background p-4 md:p-6">{children}</main>
    </SidebarInset>
  </SidebarProvider>
}
