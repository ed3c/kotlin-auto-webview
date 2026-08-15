import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { mkdir, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { Context } from '@deepseek-ai/cordis'
import { CallId } from '@deepseek-ai/dsh-llm'
import SystemPrompt from '@deepseek-ai/dsh-system-prompt'
import ToolRuntime from '@deepseek-ai/dsh-tools'
import { apply } from '@deepseek-ai/dsh-mcp-client/src/index.ts'
import type { Config } from '@deepseek-ai/dsh-mcp-client'

const endpoint = requiredEnvironment('KOTLIN_AUTO_WEBVIEW_MCP_URL')
const token = requiredEnvironment('DSH_E2E_TOKEN')
const controlDirectory = requiredEnvironment('KOTLIN_AUTO_WEBVIEW_E2E_CONTROL_DIR')
const signal = new AbortController().signal
const captureTool = 'mcp__kotlin_auto_webview__browser_capture_context'
const navigationTool = 'mcp__kotlin_auto_webview__browser_propose_navigation'
const navigationUrl = 'https://example.com/deepseek-harness-startup-recovery'
const initialFailureMarker = join(controlDirectory, 'initial-failure-observed')

let ctx: Context

function requiredEnvironment(name: string): string {
  const value = process.env[name]?.trim()
  if (!value) throw new Error(`${name} is required for the pinned startup-recovery subject`)
  return value
}

function sleep(milliseconds: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, milliseconds))
}

function textContent(blocks: readonly unknown[]): string {
  return blocks.map((block) => {
    if (block && typeof block === 'object' && 'text' in block && typeof block.text === 'string') {
      return block.text
    }
    return ''
  }).join('\n')
}

async function waitForTools(deadlineMs: number): Promise<void> {
  while (Date.now() < deadlineMs) {
    if (ctx.tools.get(captureTool) && ctx.tools.get(navigationTool)) return
    await sleep(100)
  }
  throw new Error('bounded startup-recovery window expired before the KMP tool generation appeared')
}

describe('kotlin-auto-webview initial-outage recovery subject', () => {
  beforeAll(async () => {
    await mkdir(controlDirectory, { recursive: true })
    ctx = new Context()
    await ctx.plugin(SystemPrompt)
    await ctx.plugin(ToolRuntime)

    const config: Config = {
      transport: 'streamable-http',
      serverName: 'kotlin_auto_webview',
      url: endpoint,
      headers: { Authorization: `Bearer ${token}` },
      toolCallTimeoutMs: 15_000,
      failOnStartupError: false,
      reconnect: {
        enabled: true,
        initialDelayMs: 100,
        maxDelayMs: 500,
        maxAttempts: 40,
      },
    }
    await apply(ctx, config)

    expect(ctx.tools.get(captureTool)).toBeUndefined()
    expect(ctx.tools.get(navigationTool)).toBeUndefined()
    await writeFile(initialFailureMarker, 'observed\n', { encoding: 'utf8', flag: 'wx' })
  }, 30_000)

  afterAll(async () => {
    if (ctx) await ctx.fiber.dispose()
  }, 30_000)

  it('registers and calls the exact tools after the listener appears', async () => {
    await waitForTools(Date.now() + 30_000)

    const names = ctx.tools.schemas().map(schema => schema.name)
    expect(names.filter(name => name === captureTool)).toHaveLength(1)
    expect(names.filter(name => name === navigationTool)).toHaveLength(1)
    expect(names).not.toContain('browser_capture_context')
    expect(names).not.toContain('browser_propose_navigation')

    const contextResult = await ctx.tools.execute({
      signal,
      callId: CallId('kotlin-auto-webview-recovered-context'),
      name: captureTool,
      arguments: {},
    })
    expect(contextResult.isError).toBe(false)
    const contextText = textContent(contextResult.content)
    expect(contextText).toContain('recovered-context')
    expect(contextText).toContain('[REDACTED]')
    expect(contextText).not.toContain('startup-recovery-secret-value')

    const navigationResult = await ctx.tools.execute({
      signal,
      callId: CallId('kotlin-auto-webview-recovered-navigation'),
      name: navigationTool,
      arguments: { url: navigationUrl },
    })
    expect(navigationResult.isError).toBe(false)
    expect(textContent(navigationResult.content)).toContain('awaits user confirmation')

    console.log('KMP_DSH_STARTUP_RECOVERY_PASS')
  }, 60_000)
})
