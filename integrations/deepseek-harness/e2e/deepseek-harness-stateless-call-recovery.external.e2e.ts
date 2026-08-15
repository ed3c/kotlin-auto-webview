import { afterAll, beforeAll, describe, expect, it } from 'vitest'
import { access, mkdir, writeFile } from 'node:fs/promises'
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
const navigationUrl = 'https://example.com/deepseek-harness-stateless-recovery'
const sessionReadyMarker = join(controlDirectory, 'stateless-session-ready')
const listenerStoppedMarker = join(controlDirectory, 'stateless-listener-stopped')
const outageCallFailedMarker = join(controlDirectory, 'stateless-outage-call-failed')
const listenerRestartedMarker = join(controlDirectory, 'stateless-listener-restarted')

let ctx: Context
let initialCaptureRegistration: unknown
let initialNavigationRegistration: unknown
let captureRegistrationCount = 0
let navigationRegistrationCount = 0
const infoLines: string[] = []

function requiredEnvironment(name: string): string {
  const value = process.env[name]?.trim()
  if (!value) throw new Error(`${name} is required for the stateless-call recovery subject`)
  return value
}

function sleep(milliseconds: number): Promise<void> {
  return new Promise(resolve => setTimeout(resolve, milliseconds))
}

async function waitForFile(path: string, deadlineMs: number): Promise<void> {
  while (Date.now() < deadlineMs) {
    try {
      await access(path)
      return
    } catch {
      await sleep(50)
    }
  }
  throw new Error(`bounded coordination deadline expired for ${path.split('/').at(-1) ?? 'marker'}`)
}

function textContent(blocks: readonly unknown[]): string {
  return blocks.map((block) => {
    if (block && typeof block === 'object' && 'text' in block && typeof block.text === 'string') {
      return block.text
    }
    return ''
  }).join('\n')
}

async function callContext(callId: string) {
  return await ctx.tools.execute({
    signal,
    callId: CallId(callId),
    name: captureTool,
    arguments: {},
  })
}

async function observeRealOutageFailure(deadlineMs: number): Promise<void> {
  while (Date.now() < deadlineMs) {
    try {
      const result = await callContext(`stateless-outage-${Date.now()}`)
      if (result.isError) return
    } catch {
      return
    }
    await sleep(100)
  }
  throw new Error('no real MCP tool-call failure was observed while the stateless endpoint was absent')
}

async function waitForGenerationTwoContext(deadlineMs: number): Promise<string> {
  while (Date.now() < deadlineMs) {
    try {
      const result = await callContext(`stateless-generation-two-${Date.now()}`)
      if (!result.isError) {
        const text = textContent(result.content)
        if (text.includes('stateless-generation-two-public')) return text
      }
    } catch {
      // Expected until the replacement listener is accepting requests.
    }
    await sleep(100)
  }
  throw new Error('same-registration call recovery did not reach generation-two context within the bounded deadline')
}

describe('kotlin-auto-webview stateless same-registration call recovery', () => {
  beforeAll(async () => {
    await mkdir(controlDirectory, { recursive: true })
    ctx = new Context()
    await ctx.plugin(SystemPrompt)
    await ctx.plugin(ToolRuntime)

    const originalRegister = ctx.tools.register.bind(ctx.tools)
    ctx.tools.register = ((definition: Parameters<typeof ctx.tools.register>[0]) => {
      if (definition.name === captureTool) captureRegistrationCount += 1
      if (definition.name === navigationTool) navigationRegistrationCount += 1
      return originalRegister(definition)
    }) as typeof ctx.tools.register
    ctx.logger.info = ((message: unknown) => { infoLines.push(String(message)) }) as typeof ctx.logger.info

    const config: Config = {
      transport: 'streamable-http',
      serverName: 'kotlin_auto_webview',
      url: endpoint,
      headers: { Authorization: `Bearer ${token}` },
      toolCallTimeoutMs: 2_000,
      failOnStartupError: true,
      reconnect: {
        enabled: true,
        initialDelayMs: 100,
        maxDelayMs: 500,
        maxAttempts: 30,
      },
    }
    await apply(ctx, config)
    initialCaptureRegistration = ctx.tools.get(captureTool)
    initialNavigationRegistration = ctx.tools.get(navigationTool)
  }, 30_000)

  afterAll(async () => {
    if (ctx) await ctx.fiber.dispose()
  }, 30_000)

  it('recovers later calls without replacing the Cordis tool generation', async () => {
    expect(initialCaptureRegistration).toBeDefined()
    expect(initialNavigationRegistration).toBeDefined()
    expect(captureRegistrationCount).toBe(1)
    expect(navigationRegistrationCount).toBe(1)

    const generationOneResult = await callContext('stateless-generation-one')
    expect(generationOneResult.isError).toBe(false)
    const generationOneText = textContent(generationOneResult.content)
    expect(generationOneText).toContain('stateless-generation-one-public')
    expect(generationOneText).toContain('[REDACTED]')
    expect(generationOneText).not.toContain('stateless-generation-one-secret')
    await writeFile(sessionReadyMarker, 'ready\n', { encoding: 'utf8', flag: 'wx' })

    await waitForFile(listenerStoppedMarker, Date.now() + 30_000)
    await observeRealOutageFailure(Date.now() + 15_000)
    await writeFile(outageCallFailedMarker, 'failed\n', { encoding: 'utf8', flag: 'wx' })

    await waitForFile(listenerRestartedMarker, Date.now() + 30_000)
    const generationTwoText = await waitForGenerationTwoContext(Date.now() + 30_000)

    expect(generationTwoText).toContain('[REDACTED]')
    expect(generationTwoText).not.toContain('stateless-generation-two-secret')
    expect(ctx.tools.get(captureTool)).toBe(initialCaptureRegistration)
    expect(ctx.tools.get(navigationTool)).toBe(initialNavigationRegistration)
    expect(captureRegistrationCount).toBe(1)
    expect(navigationRegistrationCount).toBe(1)
    expect(infoLines.some(line => line.includes('reconnected and re-synced tools'))).toBe(false)

    const names = ctx.tools.schemas().map(schema => schema.name)
    expect(names.filter(name => name === captureTool)).toHaveLength(1)
    expect(names.filter(name => name === navigationTool)).toHaveLength(1)
    expect(names).not.toContain('browser_capture_context')
    expect(names).not.toContain('browser_propose_navigation')

    const navigationResult = await ctx.tools.execute({
      signal,
      callId: CallId('stateless-generation-two-navigation'),
      name: navigationTool,
      arguments: { url: navigationUrl },
    })
    expect(navigationResult.isError).toBe(false)
    expect(textContent(navigationResult.content)).toContain('awaits user confirmation')

    console.log('KMP_DSH_STATELESS_CALL_RECOVERY_PASS')
  }, 120_000)
})
