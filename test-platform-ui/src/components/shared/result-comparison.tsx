import { useState } from "react"
import { Accordion, AccordionContent, AccordionItem, AccordionTrigger } from "@/components/ui/accordion"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardTitle } from "@/components/ui/card"
import { ScrollArea } from "@/components/ui/scroll-area"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { databaseValueEqual, displayValueText, parseDisplayValue, sameDatePrecision } from "@/components/shared/result-values"
import { Braces, CheckCircle2, CircleAlert, CircleX, Eye, EyeOff, RefreshCw, SkipForward } from "lucide-react"
import type { AssertionExecution } from "@/types/platform"
import { Tooltip, TooltipContent, TooltipTrigger } from "@/components/ui/tooltip"

function ResultIcon({ status }: { status?: string }) {
  if (status === "PASSED") return <CheckCircle2 className="text-primary" size={16} />
  if (status === "FAILED") return <CircleX className="text-destructive" size={16} />
  if (status === "WARN") return <CircleAlert className="text-amber-700 dark:text-amber-400" size={16} />
  if (status === "NOT_SELECTED") return <SkipForward className="text-muted-foreground" size={16} />
  if (status === "RUNNING" || status === "QUEUED") return <RefreshCw className="animate-spin text-primary" size={16} />
  return <CircleAlert className="text-muted-foreground" size={16} />
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value)
}

function isRegexMatcher(value: unknown): value is { $matcher: "REGEX"; pattern: string } {
  return isRecord(value) && value.$matcher === "REGEX" && typeof value.pattern === "string"
}

const regexStart = "#REGEX#"
const regexEnd = "#ENDREGEX#"

function regexPatternFromValue(value: unknown) {
  if (isRegexMatcher(value)) return value.pattern
  if (typeof value !== "string" || (!value.includes(regexStart) && !value.includes(regexEnd))) return undefined
  let offset = 0
  let source = ""
  let found = false
  while (offset < value.length) {
    const start = value.indexOf(regexStart, offset)
    const strayEnd = value.indexOf(regexEnd, offset)
    if (strayEnd >= 0 && (start < 0 || strayEnd < start)) return undefined
    if (start < 0) {
      source += value.slice(offset).replace(/[.*+?^${}()|[\]\\]/g, "\\$&")
      break
    }
    source += value.slice(offset, start).replace(/[.*+?^${}()|[\]\\]/g, "\\$&")
    const patternStart = start + regexStart.length
    const end = value.indexOf(regexEnd, patternStart)
    if (end < 0 || end === patternStart) return undefined
    source += `(?:${value.slice(patternStart, end)})`
    found = true
    offset = end + regexEnd.length
  }
  return found ? source : undefined
}

function regexSourceFromValue(value: unknown) {
  if (isRegexMatcher(value)) return value.pattern
  return typeof value === "string" && value.includes(regexStart) && value.includes(regexEnd) ? value : undefined
}

function RegexIndicator({ pattern }: { pattern: string }) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span className="inline-flex shrink-0 cursor-help items-center text-primary" aria-label="正则匹配">
          [正则]
        </span>
      </TooltipTrigger>
      <TooltipContent>
        <span className="font-mono">正则：{pattern}</span>
      </TooltipContent>
    </Tooltip>
  )
}

function VariableIndicator({ expression }: { expression: string }) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span className="inline-flex shrink-0 cursor-help items-center text-primary" aria-label="变量解析">[变量]</span>
      </TooltipTrigger>
      <TooltipContent><span className="font-mono">变量：{expression}</span></TooltipContent>
    </Tooltip>
  )
}

function variableExpressionFromValue(value: unknown) {
  if (typeof value !== "string") return undefined
  return /\{\{[^{}]+\}\}/.test(value) ? value : undefined
}

function expectedPattern(value: unknown, matchType?: string, regexPattern?: string) {
  if (matchType === "REGEX" && regexPattern) return regexPattern
  return regexPatternFromValue(value)
}

function valuesEqual(expected: unknown, actual: unknown, matchType?: string, regexPattern?: string) {
  const pattern = expectedPattern(expected, matchType, regexPattern)
  if (!pattern) return databaseValueEqual(expected, actual)
  if (actual === undefined || actual === null) return false
  try {
    return new RegExp(`^(?:${pattern})$`).test(typeof actual === "string" ? actual : JSON.stringify(actual))
  } catch {
    return false
  }
}

function interfaceJsonText(value: string): Record<string, unknown> | unknown[] | undefined {
  const text = value.trim()
  if (!text.startsWith("{") && !text.startsWith("[")) return undefined
  try {
    let parsed: unknown
    try {
      parsed = JSON.parse(text)
    } catch {
      // 按 JSON 字符串解码一层，保留内容本身的转义字符。
      parsed = JSON.parse(JSON.parse(`"${text}"`))
    }
    return isRecord(parsed) || Array.isArray(parsed) ? parsed : undefined
  } catch {
    return undefined
  }
}

function interfaceValuesEqual(expected: unknown, actual: unknown, path = "$"): boolean {
  if (expected === null || expected === undefined) return actual === null || actual === undefined || actual === ""
  if (actual === null || actual === undefined) return expected === ""
  if (expectedPattern(expected)) return valuesEqual(expected, actual)
  if (expected === "") return actual === ""
  if (typeof expected === "string" && typeof actual === "string") {
    const expectedJson = interfaceJsonText(expected)
    const actualJson = interfaceJsonText(actual)
    if (expectedJson !== undefined && actualJson !== undefined) return interfaceValuesEqual(expectedJson, actualJson, path)
  }
  if (Array.isArray(expected) || Array.isArray(actual)) {
    if (!Array.isArray(expected) || !Array.isArray(actual) || expected.length !== actual.length) return false
    const remaining = [...actual]
    return expected.every((item) => {
      const index = remaining.findIndex((value) => interfaceValuesEqual(item, value, `${path}[]`))
      if (index < 0) return false
      remaining.splice(index, 1)
      return true
    })
  }
  if (isRecord(expected) || isRecord(actual)) {
    return isRecord(expected) && isRecord(actual) && Object.entries(expected).every(([key, value]) =>
      Object.prototype.hasOwnProperty.call(actual, key) && interfaceValuesEqual(value, actual[key], `${path}.${key}`))
  }
  if (path.endsWith(".errorMessage") && typeof expected === "string" && typeof actual === "string") {
    return actual.replace(/\s+/g, "").includes(expected.replace(/\s+/g, ""))
  }
  if (typeof expected === "string" && typeof actual === "string" && sameDatePrecision(expected, actual)) return true
  if ((typeof expected === "string" || typeof expected === "number") && (typeof actual === "string" || typeof actual === "number")) return String(expected) === String(actual)
  return expected === actual
}

function ExpectedDisplayValue({ value, sourceValue, displayValue, actual, matchType, regexPattern }: { value: unknown; sourceValue?: unknown; displayValue?: unknown; actual?: unknown; matchType?: string; regexPattern?: string }) {
  const pattern = expectedPattern(sourceValue ?? value, matchType, regexPattern)
  const variableExpression = variableExpressionFromValue(sourceValue)
  const regexSource = regexSourceFromValue(sourceValue) || (matchType === "REGEX" ? regexPattern : undefined) || pattern
  return (
    <span className="flex min-w-0 items-start gap-1">
      {pattern && <RegexIndicator pattern={regexSource || pattern} />}
      {!pattern && variableExpression && <VariableIndicator expression={variableExpression} />}
      <DisplayValue value={displayValue ?? (pattern ? actual : value)} />
    </span>
  )
}

const gridCell = ""

function DisplayValue({ value }: { value: unknown }) {
  if (Array.isArray(value)) {
    if (!value.length) return <span className="text-muted-foreground">无数据</span>
    const objectRows = value.filter(isRecord)
    if (objectRows.length === value.length) {
      const columns = Array.from(new Set(objectRows.flatMap((row) => Object.keys(row))))
      return (
        <Table containerClassName="max-h-72 overflow-auto rounded-md border" className="min-w-max">
          <TableHeader>
            <TableRow>
              {columns.map((column) => (
                <TableHead key={column} className={gridCell}>
                  {column}
                </TableHead>
              ))}
            </TableRow>
          </TableHeader>
          <TableBody>
            {objectRows.map((row, index) => (
              <TableRow key={index}>
                {columns.map((column) => (
                  <TableCell key={column} className={`${gridCell} align-top`}>
                    <DisplayValue value={row[column]} />
                  </TableCell>
                ))}
              </TableRow>
            ))}
          </TableBody>
        </Table>
      )
    }
    return (
      <Table containerClassName="max-h-72 overflow-auto rounded-md border" className="min-w-max">
        <TableHeader>
          <TableRow>
            <TableHead className={gridCell}>#</TableHead>
            <TableHead className={gridCell}>值</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {value.map((item, index) => (
            <TableRow key={index}>
              <TableCell className={`${gridCell} w-12 text-muted-foreground`}>{index + 1}</TableCell>
              <TableCell className={`${gridCell} align-top`}>
                <DisplayValue value={item} />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    )
  }
  if (isRecord(value)) {
    return (
      <Table containerClassName="max-h-72 overflow-auto rounded-md border" className="min-w-max">
        <TableHeader>
          <TableRow>
            <TableHead className={gridCell}>字段</TableHead>
            <TableHead className={gridCell}>值</TableHead>
          </TableRow>
        </TableHeader>
        <TableBody>
          {Object.entries(value).map(([key, item]) => (
            <TableRow key={key}>
              <TableCell className={`${gridCell} w-44 align-top font-medium`}>{key}</TableCell>
              <TableCell className={`${gridCell} align-top`}>
                <DisplayValue value={item} />
              </TableCell>
            </TableRow>
          ))}
        </TableBody>
      </Table>
    )
  }
  return <span className="break-all font-mono text-xs">{displayValueText(value)}</span>
}

function DatabaseDisplayValue({ value, queried = true }: { value: unknown; queried?: boolean }) {
  if (!queried) return <span>未查询</span>
  if (value === null || value === "") return <DisplayValue value="" />
  if (typeof value === "string" && /^ +$/.test(value)) return <DisplayValue value={value} />
  return <DisplayValue value={value} />
}

function ResultTable({ label, value }: { label: string; value?: string }) {
  return (
    <div className="grid min-w-0 max-w-full gap-1.5">
      <p className="text-sm font-medium">{label}</p>
      <div className="min-w-0 max-w-full overflow-auto rounded-md bg-background p-2">
        <DisplayValue value={parseDisplayValue(value)} />
      </div>
    </div>
  )
}

function ResolvedResultTable({ label, template, value }: { label: string; template?: string; value?: string }) {
  const displayed = value ?? template
  const resolved = value !== undefined && template !== undefined && value !== template
  return (
    <div className="grid min-w-0 max-w-full gap-1.5">
      <p className="flex items-center gap-1.5 text-sm font-medium">
        {label}
        {resolved && <Tooltip><TooltipTrigger asChild><Braces className="size-3.5 cursor-help text-primary" aria-label="查看变量模板" /></TooltipTrigger><TooltipContent className="max-w-xl"><pre className="whitespace-pre-wrap break-all font-mono text-xs">变量模板：{template}</pre></TooltipContent></Tooltip>}
      </p>
      <div className="min-w-0 max-w-full overflow-auto rounded-md bg-background p-2"><DisplayValue value={parseDisplayValue(displayed)} /></div>
    </div>
  )
}

function templateCellValue(template: unknown, rowIndex: number, column: string): unknown {
  if (Array.isArray(template)) return template[rowIndex] === undefined ? undefined : templateCellValue(template[rowIndex], 0, column)
  if (!isRecord(template)) return undefined
  if (Object.prototype.hasOwnProperty.call(template, column)) return template[column]
  if (template.expected !== undefined) return templateCellValue(template.expected, rowIndex, column)
  if (Array.isArray(template.rows)) return templateCellValue(template.rows, rowIndex, column)
  for (const value of Object.values(template)) {
    const found = templateCellValue(value, rowIndex, column)
    if (found !== undefined) return found
  }
  return undefined
}

function ResponseResult({ value, emptyMessage = "暂无响应结果", open: controlledOpen, onOpenChange }: { value?: string; emptyMessage?: string; open?: boolean; onOpenChange?: (open: boolean) => void }) {
  const [uncontrolledOpen, setUncontrolledOpen] = useState(false)
  const open = controlledOpen ?? uncontrolledOpen
  const setOpen = onOpenChange || setUncontrolledOpen
  const responseText = value || ""
  return (
    <Accordion type="single" collapsible value={open ? "response" : ""} onValueChange={(nextValue) => setOpen(nextValue === "response")} className="min-w-0 overflow-hidden rounded-md border bg-background px-3">
      <AccordionItem value="response" className="min-w-0 overflow-hidden">
        <AccordionTrigger className="py-2 hover:no-underline">响应结果</AccordionTrigger>
        <AccordionContent className="min-w-0 pb-3">
          <div className={`min-w-0 overflow-auto rounded-md bg-muted/30 p-2 ${open ? "max-h-[min(60vh,32rem)]" : "max-h-56"}`}>
            {responseText ? <pre className="whitespace-pre-wrap [overflow-wrap:anywhere] font-mono text-xs leading-5">{responseText}</pre> : <p className="text-xs text-muted-foreground">{emptyMessage}</p>}
          </div>
        </AccordionContent>
      </AccordionItem>
    </Accordion>
  )
}

function ComparisonForm({ label, expectedValue, actualValue, expectedTemplate, expectedDisplayValue, matchType, regexPattern, interfaceComparison = false }: { label: string; expectedValue?: string; actualValue?: string; expectedTemplate?: string; expectedDisplayValue?: string; matchType?: string; regexPattern?: string; interfaceComparison?: boolean }) {
  const [hideMatched, setHideMatched] = useState(false)
  const expected = parseDisplayValue(expectedValue)
  const actual = parseDisplayValue(actualValue)
  const template = parseDisplayValue(expectedTemplate)
  const displayed = parseDisplayValue(expectedDisplayValue)
  const expectedRows = Array.isArray(expected) ? expected : [expected]
  const actualRows = Array.isArray(actual) ? actual : [actual]
  if (interfaceComparison && Array.isArray(expected) && Array.isArray(actual)) {
    // 每条返回记录只匹配一次，展示顺序与后端的无序数组比较一致。
    const remaining = [...actualRows]
    const matched = expectedRows.map((item) => {
      const index = remaining.findIndex((value) => interfaceValuesEqual(item, value))
      return index < 0 ? undefined : remaining.splice(index, 1)[0]
    })
    actualRows.splice(0, actualRows.length, ...matched.map((item) => item === undefined ? remaining.shift() : item), ...remaining)
  }
  const comparisonRows = Array.from({ length: Math.max(expectedRows.length, actualRows.length) }, (_, rowIndex) => {
    const expectedRow = expectedRows[rowIndex]
    const actualRow = actualRows[rowIndex]
    const displayedRow = Array.isArray(displayed) ? displayed[rowIndex] : rowIndex === 0 ? displayed : undefined
    const columns = isRecord(expectedRow)
      ? Object.keys(expectedRow)
      : isRecord(actualRow)
        ? Object.keys(actualRow)
        : ["值"]
    const missingRow = expectedRow !== undefined && actualRow === undefined
    const extraRow = expectedRow === undefined && actualRow !== undefined
    const cells = Object.fromEntries(columns.map((column) => {
      const expectedItem = isRecord(expectedRow) ? expectedRow[column] : column === "值" ? expectedRow : undefined
      const queried = isRecord(actualRow) ? Object.prototype.hasOwnProperty.call(actualRow, column) : column === "值"
      const actualItem = queried ? (isRecord(actualRow) ? actualRow[column] : actualRow) : undefined
      const displayItem = isRecord(displayedRow) ? displayedRow[column] : column === "值" ? displayedRow : undefined
      const warning = !interfaceComparison && !missingRow && !extraRow && !queried
      const different = missingRow || extraRow
        || (interfaceComparison && !queried)
        || (queried && !(interfaceComparison && !expectedPattern(expectedItem, matchType, regexPattern)
          ? interfaceValuesEqual(expectedItem, actualItem, `$.${column}`)
          : valuesEqual(expectedItem, actualItem, matchType, regexPattern)))
      return [column, { expectedItem, actualItem, displayItem, queried, different, warning }]
    }))
    return {
      rowIndex,
      cells,
      different: missingRow || extraRow || Object.values(cells).some((cell) => cell.different),
      warning: Object.values(cells).some((cell) => cell.warning)
    }
  })
  const columns = Array.from(new Set(comparisonRows.flatMap((row) => Object.keys(row.cells))))
  const visibleColumns = hideMatched ? columns.filter((column) => comparisonRows.some((row) => row.cells[column]?.different || row.cells[column]?.warning)) : columns
  const visibleRows = hideMatched ? comparisonRows.filter((row) => row.different || row.warning) : comparisonRows
  return (
    <div className="flex min-h-0 min-w-0 flex-1 flex-col gap-1.5">
      {label && (
        <div className="flex shrink-0 items-center justify-between gap-2">
          <p className="text-sm font-medium">{label}</p>
          <Button type="button" variant="outline" size="sm" onClick={() => setHideMatched((current) => !current)}>
            {hideMatched ? <Eye /> : <EyeOff />}
            {hideMatched ? "显示全部" : "隐藏无差异"}
          </Button>
        </div>
      )}
      <ScrollArea horizontal className="min-h-0 min-w-0 flex-1 rounded-md border bg-background">
        <Table className="min-w-max">
          <TableHeader>
            <TableRow>
              <TableHead className={`${gridCell} w-14`}>行</TableHead>
              {visibleColumns.map((column) => <TableHead key={column} className={`${gridCell} min-w-44`}>{column}</TableHead>)}
              <TableHead className={`${gridCell} w-20`}>差异</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {visibleRows.map((row) => (
              <TableRow key={row.rowIndex} className={row.rowIndex % 2 === 0 ? "bg-muted/20 hover:bg-muted/30" : "bg-muted/40 hover:bg-muted/50"}>
                <TableCell className={`${gridCell} align-top text-muted-foreground`}>{row.rowIndex + 1}</TableCell>
                {visibleColumns.map((column) => {
                  const cell = row.cells[column]
                  return <TableCell key={column} className={`${gridCell} min-w-44 align-top ${cell?.different ? "bg-destructive/10" : cell?.warning ? "bg-amber-100/60 dark:bg-amber-950/40" : ""}`}><div className="grid gap-1 text-xs"><div className="grid grid-cols-[2.5rem_minmax(0,1fr)] gap-1"><span className="text-muted-foreground">期望</span><ExpectedDisplayValue value={cell?.expectedItem} sourceValue={templateCellValue(template, row.rowIndex, column)} displayValue={cell?.displayItem} actual={cell?.actualItem} matchType={matchType} regexPattern={regexPattern} /></div><div className={`grid grid-cols-[2.5rem_minmax(0,1fr)] gap-1 border-t pt-1 ${cell?.different ? "text-destructive" : cell?.warning ? "text-amber-700 dark:text-amber-400" : ""}`}><span className="text-muted-foreground">返回</span><DatabaseDisplayValue value={cell?.actualItem} queried={cell?.queried} /></div></div></TableCell>
                })}
                <TableCell className={`${gridCell} align-top ${row.different ? "text-destructive" : row.warning ? "text-amber-700 dark:text-amber-400" : "text-muted-foreground"}`}>{row.different ? "不一致" : row.warning ? "未查询" : "无差异"}</TableCell>
              </TableRow>
            ))}
            {visibleRows.length === 0 && (
              <TableRow>
                <TableCell colSpan={visibleColumns.length + 2} className="py-8 text-center text-sm text-muted-foreground">
                  没有差异
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>
      </ScrollArea>
    </div>
  )
}

function AssertionComparisonForm({ assertions, expectedTemplate, open, onOpenChange }: { assertions: AssertionExecution[]; expectedTemplate?: string; open?: boolean; onOpenChange?: (open: boolean) => void }) {
  if (!assertions.length) return <div className="rounded-md border border-dashed px-3 py-4 text-center text-sm text-muted-foreground">没有接口断言结果</div>
  const failedCount = assertions.filter((assertion) => assertion.status !== "PASSED").length
  const summary = failedCount > 0 ? `${failedCount} 条未通过` : "全部通过"
  const cards = (
    <div className="max-h-[min(60vh,32rem)] min-w-0 overflow-x-hidden overflow-y-auto pr-2">
      <div className="grid gap-2">
        {assertions.map((assertion) => {
          const failed = assertion.status !== "PASSED"
          const path = assertion.assertionType.replace(/^INTERFACE\s+/, "")
          return (
            <Card key={assertion.id} className={`gap-0 overflow-hidden py-0 shadow-none ${failed ? "border-destructive/30" : ""}`}>
              <div className="flex min-w-0 items-center justify-between gap-3 border-b px-3 py-1.5">
                <CardTitle className="flex min-w-0 items-center gap-2 text-xs font-medium leading-5">
                  <span className="shrink-0 text-muted-foreground">接口字段</span>
                  <span className="min-w-0 break-all font-mono">{path}</span>
                </CardTitle>
                <Badge variant="outline" className={`shrink-0 ${failed ? "border-destructive/30 bg-destructive/5 text-destructive" : "border-primary/30 bg-primary/5 text-primary"}`}>
                  {failed ? <CircleX /> : <CheckCircle2 />}
                  {failed ? "未通过" : "通过"}
                </Badge>
              </div>
              <CardContent className="min-w-0 p-2">
                <ComparisonForm
                  label=""
                  interfaceComparison
                  expectedValue={assertion.expectedValue}
                  actualValue={assertion.actualValue}
                  expectedTemplate={assertion.expectedTemplate || expectedTemplate}
                  expectedDisplayValue={assertion.expectedDisplayValue}
                  matchType={assertion.matchType}
                  regexPattern={assertion.regexPattern}
                />
                {failed && <p role="alert" className="mt-1 flex items-center gap-1.5 text-[11px] text-destructive"><CircleAlert className="size-3.5 shrink-0" /><span>{assertion.diffMessage || "实际值与期望值不一致"}</span></p>}
              </CardContent>
            </Card>
          )
        })}
      </div>
    </div>
  )
  if (open === undefined)
    return (
      <div className="grid min-w-0 gap-1.5">
        <div className="flex items-center gap-2">
          <p className="text-sm font-medium">接口断言</p>
          <Badge variant="outline" className={failedCount > 0 ? "border-destructive/30 bg-destructive/5 text-destructive" : "border-primary/30 bg-primary/5 text-primary"}>{summary}</Badge>
        </div>
        {cards}
      </div>
    )
  return (
    <Accordion type="single" collapsible value={open ? "assertion" : ""} onValueChange={(nextValue) => onOpenChange?.(nextValue === "assertion")} className="min-w-0 rounded-md border bg-background px-3">
      <AccordionItem value="assertion" className="min-w-0">
        <AccordionTrigger className="py-1.5 hover:no-underline">
          <span className="flex items-center gap-2">
            接口断言
            <Badge variant="outline" className={failedCount > 0 ? "border-destructive/30 bg-destructive/5 text-destructive" : "border-primary/30 bg-primary/5 text-primary"}>{summary}</Badge>
          </span>
        </AccordionTrigger>
        <AccordionContent className="min-w-0 pb-2">{cards}</AccordionContent>
      </AccordionItem>
    </Accordion>
  )
}

export { AssertionComparisonForm, ComparisonForm, DisplayValue, ResolvedResultTable, ResponseResult, ResultIcon, ResultTable }
