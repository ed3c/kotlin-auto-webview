import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { Context } from '@deepseek-ai/cordis'
import { CallId } from '@deepseek-ai/dsh-llm'
import SystemPrompt from '@deepseek-ai/dsh-system-prompt'
import ToolRuntime from '@deepseek-ai/dsh-tools'
import { apply } from '@deepseek-ai/dsh-mcp-client/src/index.ts'
import type { Config } from '@deepseek-ai/dsh-mcp-client'

const endpoint = requiredEnvironment('KOTLIN_AUTO_WEBVIEW_MCP_URL')
const token = requiredEnvironment('DSH_E2E_TOKEN')
const signal = new AbortController().signal
const captureTool = 'mcp__kotlin_auto_webview__browser_capture_context'
const navigationTool = 'mcp__kotlin_auto_webview__browser_propose_navigation'
const navigationUrl = 'https://example.com/deepseek-harness-e2e'

let ctx: Context

function requiredEnvironment(name: string): string {
  const value = process.env[name]?.trim()
  if (!value) throw new Error(`${name} is required for the pinned external E2E subject`)
  return value
}

function textContent(blocks: readonly unknown[]): string {
  return blocks.map((block) => {
    if (block && typeof block === 'object' && 'text' in block && typeof block.text === 'string') {
      return block.text
    }
    return ''
  }).join('\n')
}

describe('kotlin-auto-webview external Streamable HTTP subject', () => {
  beforeAll(async () => {
    ctx = new Context()
    await ctx.plugin(SystemPrompt)
    await ctx.plugin(ToolRuntime)

    const config: Config = {
      transport: 'streamable-http',
      serverName: 'kotlin_auto_webview',
      url: endpoint,
      headers: { Authorization: `Bearer ${token}` },
      toolCallTimeoutMs: 30_000,
      failOnStartupError: true,
      reconnect: { enabled: false },
    }
    await apply(ctx, config)
  }, 60_000)

  afterAll(async () => {
    if (ctx) await ctx.fiber.dispose()
  }, 30_000)

  it('registers exact tools and preserves KMP sanitization and proposal authority', async () => {
    const names = ctx.tools.schemas().map(schema => schema.name)
    expect(names).toContain(captureTool)
    expect(names).toContain(navigationTool)
    expect(names).not.toContain('browser_capture_context')
    expect(names).not.toContain('browser_propose_navigation')

    const contextResult = await ctx.tools.execute({
      signal,
      callId: CallId('kotlin-auto-webview-context'),
      name: captureTool,
      arguments: {},
    })
    expect(contextResult.isError).toBe(false)
    const contextText = textContent(contextResult.content)
    expect(contextText).toContain('[REDACTED]')
    expect(contextText).not.toContain('super-secret-value')

    const navigationResult = await ctx.tools.execute({
      signal,
      callId: CallId('kotlin-auto-webview-navigation'),
      name: navigationTool,
      arguments: { url: navigationUrl },
    })
    expect(navigationResult.isError).toBe(false)
    const navigationText = textContent(navigationResult.content)
    expect(navigationText).toContain('awaits user confirmation')

    console.log('KMP_DSH_E2E_PASS')
  }, 60_000)
})
