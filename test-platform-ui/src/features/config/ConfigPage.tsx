import { useMemo, useState } from "react"
import type { ColumnDef } from "@tanstack/react-table"
import { Pencil, Plus } from "lucide-react"
import { toast } from "sonner"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { OverflowTooltip } from "@/components/shared/overflow-tooltip"
import { DataTable } from "@/components/ui/data-table"
import { DataTableColumnHeader } from "@/components/ui/data-table-column-header"
import type { DataTableFeatures } from "@/components/ui/data-table-features"
import { DataTableViewOptions } from "@/components/ui/data-table-view-options"
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs"
import { api, configuredPlatformServiceUrl, platformServiceDisplay } from "@/lib/platform-api"
import type { Bootstrap, DataSource, Dataset, Environment, Id } from "@/types/platform"
import { DataSourceEditor } from "./editors/DataSourceEditor"
import { DatasetEditor } from "./editors/DatasetEditor"
import { EnvironmentEditor } from "./editors/EnvironmentEditor"
import { BackupPanel } from "./BackupPanel"

type EditorType = "environment" | "datasource" | "dataset"

export function ConfigPage({ data, reload }: { data: Bootstrap; reload: () => void }) {
  const [environment, setEnvironment] = useState<Environment>()
  const [datasource, setDatasource] = useState<DataSource>()
  const [dataset, setDataset] = useState<Dataset>()
  const [editor, setEditor] = useState<EditorType>()
  const resourceKeys = useMemo(() => Array.from(new Set(data.datasources.map((item) => item.resourceKey))).filter(Boolean), [data.datasources])
  const onEditEnvironment = (item: Environment) => { setEnvironment(item); setEditor("environment") }
  const onEditDatasource = (item: DataSource) => { setDatasource({ ...item, password: "" }); setEditor("datasource") }
  const onEditDataset = (item: Dataset) => { setDataset(item); setEditor("dataset") }
  const save = async (path: string, value: unknown) => {
    try {
      await api<unknown>(path, { method: "POST", body: JSON.stringify(value) })
      toast.success("已保存")
      setEditor(undefined)
      reload()
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "保存失败")
    }
  }
  const testDatasource = async (id?: Id) => {
    if (!id) return
    try {
      await api<void>(`/api/datasources/${id}/test`, { method: "POST" })
      toast.success("数据源连接正常")
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "连接失败")
    }
  }

  const environmentColumns: ColumnDef<DataTableFeatures, Environment>[] = [
    { accessorKey: "name", meta: { label: "环境" }, header: ({ column }) => <DataTableColumnHeader column={column} title="环境" />, cell: ({ row }) => <span className="font-medium">{row.original.name}</span> },
    { accessorKey: "refapp", meta: { label: "应用" }, header: ({ column }) => <DataTableColumnHeader column={column} title="应用" />, cell: ({ row }) => <span className="font-mono text-sm">{row.original.refapp}</span> },
    { id: "connections", accessorFn: (row) => row.connections.map((item) => `${item.protocol} ${item.key} ${item.baseUrl}`).join("；"), meta: { label: "协议连接" }, header: ({ column }) => <DataTableColumnHeader column={column} title="协议连接" />, cell: ({ row }) => <div className="space-y-1">{row.original.connections.length ? row.original.connections.map((item) => <div key={item.key}><Badge variant="outline">{item.protocol} · {item.key}</Badge><p className="mt-1 font-mono text-xs text-muted-foreground break-all">{item.baseUrl}</p></div>) : "—"}</div> },
    { accessorKey: "defaultEnvironment", meta: { label: "默认" }, header: ({ column }) => <DataTableColumnHeader column={column} title="默认" />, cell: ({ row }) => row.original.defaultEnvironment ? <Badge variant="secondary">默认</Badge> : "—" },
    { id: "actions", enableSorting: false, enableHiding: false, header: "", cell: ({ row }) => <div className="text-right"><Button variant="ghost" size="icon-sm" title="编辑环境" onClick={() => onEditEnvironment(row.original)}><Pencil /></Button></div> },
  ]
  const datasourceColumns: ColumnDef<DataTableFeatures, DataSource>[] = [
    { accessorKey: "name", meta: { label: "名称" }, header: ({ column }) => <DataTableColumnHeader column={column} title="名称" />, cell: ({ row }) => <span className="font-medium">{row.original.name}</span> },
    { accessorKey: "resourceKey", meta: { label: "逻辑标识" }, header: ({ column }) => <DataTableColumnHeader column={column} title="逻辑标识" />, cell: ({ row }) => <span className="font-mono text-sm">{row.original.resourceKey}</span> },
    { accessorKey: "databaseType", meta: { label: "数据库" }, header: ({ column }) => <DataTableColumnHeader column={column} title="数据库" /> },
    { accessorKey: "accessMode", meta: { label: "访问模式" }, header: ({ column }) => <DataTableColumnHeader column={column} title="访问模式" />, cell: ({ row }) => <Badge variant={row.original.accessMode === "TEST_WRITE" ? "secondary" : "outline"}>{row.original.accessMode}</Badge> },
    { id: "actions", enableSorting: false, enableHiding: false, header: "", cell: ({ row }) => <div className="flex justify-end gap-2"><Button variant="ghost" size="icon-sm" title="编辑数据库" onClick={() => onEditDatasource(row.original)}><Pencil /></Button><Button variant="outline" size="sm" onClick={() => void testDatasource(row.original.id)}>连通测试</Button></div> },
  ]
  const datasetColumns: ColumnDef<DataTableFeatures, Dataset>[] = [
    { accessorKey: "name", meta: { label: "名称" }, header: ({ column }) => <DataTableColumnHeader column={column} title="名称" />, cell: ({ row }) => <span className="font-medium">{row.original.name}</span> },
    { accessorKey: "datasourceKey", meta: { label: "数据源标识" }, header: ({ column }) => <DataTableColumnHeader column={column} title="数据源标识" />, cell: ({ row }) => <span className="font-mono text-sm">{row.original.datasourceKey}</span> },
    { id: "prepare", accessorFn: (row) => row.prepareSql ? "已配置" : "-", meta: { label: "前置数据" }, header: ({ column }) => <DataTableColumnHeader column={column} title="前置数据" />, cell: ({ row }) => row.original.prepareSql ? "已配置" : "-" },
    { id: "cleanup", accessorFn: () => "已配置", meta: { label: "清理" }, header: ({ column }) => <DataTableColumnHeader column={column} title="清理" />, cell: () => "已配置" },
    { id: "actions", enableSorting: false, enableHiding: false, header: "", cell: ({ row }) => <div className="text-right"><Button variant="ghost" size="icon-sm" title="编辑数据集" onClick={() => onEditDataset(row.original)}><Pencil /></Button></div> },
  ]

  return <div className="flex min-h-0 min-w-0 flex-1 flex-col gap-4">
    <div className="flex shrink-0 flex-wrap items-start justify-between gap-3">
      <div><h1 className="text-lg font-semibold">基础配置</h1><p className="mt-1 text-sm text-muted-foreground">运行环境定位接口服务，数据库由案例步骤通过逻辑标识引用。</p></div>
      <div className="flex min-w-0 items-center gap-2 text-sm"><span className="text-muted-foreground">平台服务</span><Badge variant={configuredPlatformServiceUrl ? "secondary" : "outline"}>{configuredPlatformServiceUrl ? "远程" : "本地"}</Badge><OverflowTooltip content={platformServiceDisplay}><span className="max-w-72 truncate font-mono">{platformServiceDisplay}</span></OverflowTooltip></div>
    </div>
    <Tabs defaultValue="environment" className="flex min-h-0 flex-1 flex-col">
      <TabsList className="w-full shrink-0 justify-start overflow-x-auto"><TabsTrigger value="environment">运行环境</TabsTrigger><TabsTrigger value="datasource">数据库</TabsTrigger><TabsTrigger value="dataset">固定数据</TabsTrigger><TabsTrigger value="backup">备份</TabsTrigger></TabsList>
      <TabsContent value="backup" className="flex min-h-0 flex-1 flex-col overflow-hidden pt-3"><BackupPanel /></TabsContent>
      <TabsContent value="environment" className="flex min-h-0 flex-1 flex-col overflow-hidden pt-3"><section className="flex min-h-0 flex-col"><DataTable columns={environmentColumns} data={data.environments} emptyMessage="还没有运行环境" header={(table) => <div className="mb-4 flex shrink-0 flex-wrap items-center justify-between gap-3"><div className="flex min-w-0 items-center gap-3"><h2 className="font-semibold">运行环境</h2></div><div className="flex items-center gap-2"><Button size="sm" onClick={() => { setEnvironment({ name: "", refapp: "default", defaultEnvironment: false, connections: [] }); setEditor("environment") }}><Plus />新增</Button><DataTableViewOptions table={table} /></div></div>} /></section></TabsContent>
      <TabsContent value="datasource" className="flex min-h-0 flex-1 flex-col overflow-hidden pt-3"><DataTable columns={datasourceColumns} data={data.datasources} emptyMessage="还没有数据库配置" header={(table) => <div className="mb-4 flex shrink-0 flex-wrap items-center justify-between gap-3"><div className="flex min-w-0 items-center gap-3"><h2 className="font-semibold">数据库</h2><p className="text-sm text-muted-foreground">逻辑标识全局唯一，由数据库步骤直接引用。</p></div><div className="flex items-center gap-2"><Button size="sm" onClick={() => { setDatasource({ name: "", resourceKey: "", databaseType: "SQLITE", jdbcUrl: "", username: "", password: "", accessMode: "TEST_WRITE" }); setEditor("datasource") }}><Plus />新增</Button><DataTableViewOptions table={table} /></div></div>} /></TabsContent>
      <TabsContent value="dataset" className="flex min-h-0 flex-1 flex-col overflow-hidden pt-3"><DataTable columns={datasetColumns} data={data.datasets} emptyMessage="还没有固定数据集" header={(table) => <div className="mb-4 flex shrink-0 flex-wrap items-center justify-between gap-3"><div className="flex min-w-0 items-center gap-3"><h2 className="font-semibold">固定数据</h2><p className="text-sm text-muted-foreground">按逻辑数据源写入，案例结束后倒序清理。</p></div><div className="flex items-center gap-2"><Button size="sm" disabled={!resourceKeys.length} onClick={() => { setDataset({ name: "", datasourceKey: resourceKeys[0] || "", variablesJson: "{}", prepareSql: "", cleanupSql: "" }); setEditor("dataset") }}><Plus />新增</Button><DataTableViewOptions table={table} /></div></div>} /></TabsContent>
    </Tabs>
    {editor === "environment" && environment && <EnvironmentEditor open initialValue={environment} onOpenChange={(open) => { if (!open) setEditor(undefined) }} onSave={(value) => save("/api/environments", value)} />}
    {editor === "datasource" && datasource && <DataSourceEditor open initialValue={datasource} onOpenChange={(open) => { if (!open) setEditor(undefined) }} onSave={(value) => save("/api/datasources", value)} />}
    {editor === "dataset" && dataset && <DatasetEditor open initialValue={dataset} resourceKeys={resourceKeys} onOpenChange={(open) => { if (!open) setEditor(undefined) }} onSave={(value) => save("/api/datasets", value)} />}
  </div>
}
