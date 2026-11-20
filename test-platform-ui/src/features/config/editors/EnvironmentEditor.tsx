import { zodResolver } from "@hookform/resolvers/zod"
import { LoaderCircle, Plus, Save, Trash2 } from "lucide-react"
import { Controller, useFieldArray, useForm } from "react-hook-form"
import { z } from "zod"
import { Button } from "@/components/ui/button"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"
import { Field, FieldContent, FieldDescription, FieldError, FieldGroup, FieldLabel } from "@/components/ui/field"
import { Input } from "@/components/ui/input"
import { ScrollArea } from "@/components/ui/scroll-area"
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select"
import { Switch } from "@/components/ui/switch"
import { Textarea } from "@/components/ui/textarea"
import type { Environment } from "@/types/platform"

const connectionSchema = z.object({
  key: z.string().regex(/^[A-Za-z][A-Za-z0-9_-]*$/, "以字母开头，仅含字母、数字、下划线或横线"),
  protocol: z.literal("HTTP"),
  baseUrl: z.string().refine((value) => {
    try { const url = new URL(value); return ["http:", "https:"].includes(url.protocol) && url.pathname === "/" && !url.search && !url.hash && !url.username && !url.password } catch { return false }
  }, "请输入仅包含协议、主机和端口的 HTTP/HTTPS 根地址"),
  headersJson: z.string().refine((value) => {
    try { const headers: unknown = JSON.parse(value); return headers !== null && typeof headers === "object" && !Array.isArray(headers) && Object.entries(headers).every(([key, item]) => key.trim() && typeof item === "string") } catch { return false }
  }, "请求头须为 JSON 对象，值须为字符串"),
})
const schema = z.object({
  id: z.number().optional(), name: z.string().trim().min(1, "请输入环境名称"), refapp: z.string().trim().min(1, "请输入应用标识"), defaultEnvironment: z.boolean(), connections: z.array(connectionSchema).min(1, "请至少配置一个连接"),
}).superRefine((value, context) => {
  const keys = new Set<string>()
  value.connections.forEach((item, index) => {
    if (keys.has(item.key)) context.addIssue({ code: "custom", path: ["connections", index, "key"], message: "同一环境内连接标识不能重复" })
    keys.add(item.key)
  })
})
type EnvironmentForm = z.infer<typeof schema>

function EnvironmentEditor({ open, initialValue, onOpenChange, onSave }: { open: boolean; initialValue: Environment; onOpenChange: (open: boolean) => void; onSave: (value: Environment) => Promise<void> }) {
  const form = useForm<EnvironmentForm>({ resolver: zodResolver(schema), defaultValues: { ...initialValue, refapp: initialValue.refapp || "default", connections: initialValue.connections.map((item) => ({ key: item.key, protocol: item.protocol, baseUrl: item.baseUrl, headersJson: JSON.stringify(item.headers, null, 2) })) } })
  const connections = useFieldArray({ control: form.control, name: "connections" })
  const submit = () => void form.handleSubmit(async (value) => onSave({ id: value.id, name: value.name, refapp: value.refapp, defaultEnvironment: value.defaultEnvironment, connections: value.connections.map((item) => ({ key: item.key, protocol: item.protocol, baseUrl: item.baseUrl, headers: JSON.parse(item.headersJson) as Record<string, string> })) }))()

  return <Dialog open={open} onOpenChange={onOpenChange}><DialogContent className="sm:max-w-3xl overflow-hidden"><form id="environment-form" className="grid max-h-[calc(100dvh-6rem)] min-h-0 grid-rows-[auto_minmax(0,1fr)_auto] gap-5" onSubmit={(event) => { event.preventDefault(); submit() }}>
    <DialogHeader><DialogTitle>运行环境</DialogTitle><DialogDescription>按协议分别配置连接，案例通过连接标识选择服务。</DialogDescription></DialogHeader>
    <ScrollArea className="min-h-0 pr-3"><FieldGroup>
      <div className="grid gap-4 sm:grid-cols-2">
        <Controller name="name" control={form.control} render={({ field, fieldState }) => <Field data-invalid={fieldState.invalid}><FieldLabel htmlFor="environment-name">环境名称</FieldLabel><Input {...field} id="environment-name" placeholder="例如：本地环境" aria-invalid={fieldState.invalid} />{fieldState.invalid && <FieldError errors={[fieldState.error]} />}</Field>} />
        <Controller name="refapp" control={form.control} render={({ field, fieldState }) => <Field data-invalid={fieldState.invalid}><FieldLabel htmlFor="environment-application">应用标识</FieldLabel><Input {...field} id="environment-application" placeholder="例如：default" aria-invalid={fieldState.invalid} /><FieldDescription>与案例步骤的应用标识一致。</FieldDescription>{fieldState.invalid && <FieldError errors={[fieldState.error]} />}</Field>} />
      </div>
      <div className="space-y-4 rounded-lg border p-4">
        <div className="flex items-center justify-between gap-3"><div><p className="font-medium text-sm">协议连接</p><p className="mt-1 text-sm text-muted-foreground">同一应用各环境使用相同连接标识，切换环境即可切换地址。</p></div><Button type="button" size="sm" variant="outline" onClick={() => connections.append({ key: "", protocol: "HTTP", baseUrl: "", headersJson: "{}" })}><Plus />添加连接</Button></div>
        {connections.fields.map((item, index) => {
          const errors = form.formState.errors.connections?.[index]
          return <div key={item.id} className="space-y-3 rounded-md bg-muted/40 p-3">
            <div className="flex items-start gap-3"><div className="grid flex-1 gap-3 sm:grid-cols-2">
              <Field data-invalid={Boolean(errors?.key)}><FieldLabel htmlFor={`connection-key-${index}`}>连接标识</FieldLabel><Input {...form.register(`connections.${index}.key`)} id={`connection-key-${index}`} placeholder="例如：api" aria-invalid={Boolean(errors?.key)} />{errors?.key && <FieldError errors={[errors.key]} />}</Field>
              <Controller name={`connections.${index}.protocol`} control={form.control} render={({ field }) => <Field><FieldLabel htmlFor={`connection-protocol-${index}`}>协议</FieldLabel><Select value={field.value} onValueChange={field.onChange}><SelectTrigger id={`connection-protocol-${index}`}><SelectValue /></SelectTrigger><SelectContent><SelectItem value="HTTP">HTTP / HTTPS</SelectItem></SelectContent></Select></Field>} />
              <Field className="sm:col-span-2" data-invalid={Boolean(errors?.baseUrl)}><FieldLabel htmlFor={`connection-base-${index}`}>服务根地址</FieldLabel><Input {...form.register(`connections.${index}.baseUrl`)} id={`connection-base-${index}`} placeholder="http://127.0.0.1:8080" className="font-mono text-xs" aria-invalid={Boolean(errors?.baseUrl)} />{errors?.baseUrl && <FieldError errors={[errors.baseUrl]} />}</Field>
            </div><Button type="button" variant="ghost" size="icon-sm" className="mt-7" title="删除连接" onClick={() => connections.remove(index)}><Trash2 /></Button></div>
            <Field data-invalid={Boolean(errors?.headersJson)}><FieldLabel htmlFor={`connection-headers-${index}`}>环境请求头（JSON）</FieldLabel><Textarea {...form.register(`connections.${index}.headersJson`)} id={`connection-headers-${index}`} className="min-h-20 font-mono text-xs" placeholder="{}" aria-invalid={Boolean(errors?.headersJson)} /><FieldDescription>同名请求头以环境配置为准，无环境请求头时填写 {"{}"}。</FieldDescription>{errors?.headersJson && <FieldError errors={[errors.headersJson]} />}</Field>
          </div>
        })}
        {form.formState.errors.connections?.message && <FieldError errors={[form.formState.errors.connections]} />}
      </div>
      <Controller name="defaultEnvironment" control={form.control} render={({ field }) => <Field orientation="horizontal"><FieldContent><FieldLabel htmlFor="environment-default">该应用的默认运行环境</FieldLabel></FieldContent><Switch id="environment-default" checked={field.value} onCheckedChange={field.onChange} /></Field>} />
    </FieldGroup></ScrollArea>
    <DialogFooter className="border-t pt-4"><Button type="button" variant="outline" disabled={form.formState.isSubmitting} onClick={() => onOpenChange(false)}>取消</Button><Button type="submit" form="environment-form" disabled={form.formState.isSubmitting}>{form.formState.isSubmitting ? <LoaderCircle className="animate-spin" /> : <Save />}{form.formState.isSubmitting ? "保存中" : "保存"}</Button></DialogFooter>
  </form></DialogContent></Dialog>
}

export { EnvironmentEditor }
