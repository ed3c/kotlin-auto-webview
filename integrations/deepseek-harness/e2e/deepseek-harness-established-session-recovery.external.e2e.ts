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
const navigationUrl = 'https://example.com/deepseek-harness-established-recovery'
const sessionReadyMarker = join(controlDirectory, 'initial-session-ready')
const listenerStoppedMarker = join(controlDirectory, 'listener-stopped')
const outageCallFailedMarker = join(controlDirectory, 'outage-call-failed')
const listenerRestartedMarker = join(controlDirectory, 'listener-restarted')

let ctx: Context
let initialCaptureRegistration: unknown
let captureRegistrationCount = 0
let navigationRegistrationCount = 0
const infoLines: string[] = []

function requiredEnvironment(name: string): string {
  const value = process.env[name]?.trim()
  if (!value) throw new Error(`${name} is required for the established-session recovery subject`)
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

async function callContext(callId: string): Promise<ReturnType<typeof ctx.tools.execute> extends Promise<infer T> ? T : never> {
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
      const result = await callContext(`established-outage-${Date.now()}`)
      if (result.isError) return
    } catch {
      return
    }
    await sleep(100)
  }
  throw new Error('no real MCP tool-call failure was observed while the listener was absent')
}

async function waitForSupervisorRecovery(deadlineMs: number): Promise<void> {
  while (Date.now() < deadlineMs) {
    const currentCapture = ctx.tools.get(captureTool)
    const currentNavigation = ctx.tools.get(navigationTool)
    const recoveryLogged = infoLines.some(line => line.includes('reconnected and re-synced tools'))
    if (
      currentCapture !== undefined
      && currentNavigation !== undefined
      && currentCapture !== initialCaptureRegistration
      && captureRegistrationCount >= 2
      && navigationRegistrationCount >= 2
      && recoveryLogged
    ) return
    await sleep(100)
  }
  throw new Error('transport loss did not produce bounded supervisor reconnect and tool-generation replacement')
}

describe('kotlin-auto-webview established-session recovery probe', () => {
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
  }, 30_000)

  afterAll(async () => {
    if (ctx) await ctx.fiber.dispose()
  }, 30_000)

  it('requires transport-close recovery and a replaced tool generation', async () => {
    initialCaptureRegistration = ctx.tools.get(captureTool)
    expect(initialCaptureRegistration).toBeDefined()
    expect(ctx.tools.get(navigationTool)).toBeDefined()
    expect(captureRegistrationCount).toBe(1)
    expect(navigationRegistrationCount).toBe(1)

    const generationOneResult = await callContext('established-generation-one')
    expect(generationOneResult.isError).toBe(false)
    const generationOneText = textContent(generationOneResult.content)
    expect(generationOneText).toContain('generation-one-public')
    expect(generationOneText).toContain('[REDACTED]')
    expect(generationOneText).not.toContain('established-generation-one-secret')
    await writeFile(sessionReadyMarker, 'ready\n', { encoding: 'utf8', flag: 'wx' })

    await waitForFile(listenerStoppedMarker, Date.now() + 30_000)
    await observeRealOutageFailure(Date.now() + 15_000)
    await writeFile(outageCallFailedMarker, 'failed\n', { encoding: 'utf8', flag: 'wx' })

    await waitForFile(listenerRestartedMarker, Date.now() + 30_000)
    await waitForSupervisorRecovery(Date.now() + 30_000)

    const names = ctx.tools.schemas().map(schema => schema.name)
    expect(names.filter(name => name === captureTool)).toHaveLength(1)
    expect(names.filter(name => name === navigationTool)).toHaveLength(1)
    expect(names).not.toContain('browser_capture_context')
    expect(names).not.toContain('browser_propose_navigation')

    const generationTwoResult = await callContext('established-generation-two')
    expect(generationTwoResult.isError).toBe(false)
    const generationTwoText = textContent(generationTwoResult.content)
    expect(generationTwoText).toContain('generation-two-public')
    expect(generationTwoText).toContain('[REDACTED]')
    expect(generationTwoText).not.toContain('established-generation-two-secret')

    const navigationResult = await ctx.tools.execute({
      signal,
      callId: CallId('established-generation-two-navigation'),
      name: navigationTool,
      arguments: { url: navigationUrl },
    })
    expect(navigationResult.isError).toBe(false)
    expect(textContent(navigationResult.content)).toContain('awaits user confirmation')

    console.log('KMP_DSH_ESTABLISHED_RECOVERY_PASS')
  }, 120_000)
})
