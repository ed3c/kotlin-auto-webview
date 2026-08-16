/**
 * Issue #49 — the repository-generated Cordis patch parsed as *configuration*.
 *
 * The pinned-process subject (#35) proved a real Cordis client by applying the observed plugin
 * directly with hand-constructed options. That proves the plugin works against this listener; it
 * cannot prove that the exact YAML `DeepSeekHarnessCordisBinding.renderCordisPatch()` emits is
 * accepted by the upstream profile/patch loader. Nothing here reconstructs an equivalent config
 * object: the patch is read from the file the Kotlin driver rendered with production code and is
 * parsed by the upstream loader itself.
 *
 * Scope note — this file deliberately stops at parsing. Mounting the rendered patch would require
 * the loader to import `@deepseek-ai/dsh-mcp-client`, which resolves to that package's *built*
 * entry (`lib/index.js`). The pinned E2E install (`--frozen-lockfile --ignore-scripts`) ships
 * source only, and the build is not reachable from it: `tsdown` needs the optional peer `unrun`,
 * which nothing in the pinned workspace declares. The HMR and tool-generation-replacement states
 * therefore stay unexercised rather than being "proven" against a fabricated package layout.
 * ADR-0025 records the full chain.
 *
 * Required environment:
 *   KAW_PROFILE_DIR             directory holding the rendered cordis.patch.yml
 *   KOTLIN_AUTO_WEBVIEW_MCP_URL live listener endpoint the patch names
 *   DSH_E2E_TOKEN               bearer credential the patch must NOT contain
 */
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
// Relative: the workflow stages this file inside packages/mcp/mcp-client/tests, and app-boot is
// not one of that package's dependencies, so a bare specifier would not resolve.
import { loadOptionalPatches, PROFILE_PATCH_FILENAME } from '../../../boot/app-boot/src/index.ts'

const NAME = 'kotlin-auto-webview-e2e'
const PLUGIN_NAME = '@deepseek-ai/dsh-mcp-client'
const ENTRY_ID = 'kotlin-auto-webview-mcp'

const profileDir = requiredEnvironment('KAW_PROFILE_DIR')
const endpoint = requiredEnvironment('KOTLIN_AUTO_WEBVIEW_MCP_URL')
const token = requiredEnvironment('DSH_E2E_TOKEN')
const patchFile = join(profileDir, PROFILE_PATCH_FILENAME)

function requiredEnvironment(name: string): string {
  const value = process.env[name]?.trim()
  if (!value) throw new Error(`${name} is required for the pinned external E2E subject`)
  return value
}

describe('generated Cordis patch parsed as configuration', () => {
  it('is accepted by the pinned upstream loader with the rendered contract intact', () => {
    const patches = loadOptionalPatches(NAME, patchFile)

    expect(patches, 'the Kotlin driver must have rendered a patch file').toBeDefined()
    expect(patches).toHaveLength(1)

    const insert = patches?.[0]?.insert
    expect(insert, 'the rendered patch must be a single insert operation').toHaveLength(1)

    const row = insert?.[0] as unknown as Record<string, unknown>
    expect(row.id).toBe(ENTRY_ID)
    expect(row.name).toBe(PLUGIN_NAME)

    const config = row.config as Record<string, unknown>
    expect(config.serverName).toBe('kotlin_auto_webview')
    expect(config.transport).toBe('streamable-http')
    expect(config.url).toBe(endpoint)
    expect(config.failOnStartupError).toBe(true)
    expect(typeof config.toolCallTimeoutMs).toBe('number')
    expect(config.reconnect).toMatchObject({
      enabled: expect.any(Boolean),
      initialDelayMs: expect.any(Number),
      maxDelayMs: expect.any(Number),
      maxAttempts: expect.any(Number),
    })
  })

  it('keeps the credential an environment lookup rather than a rendered value', () => {
    const patches = loadOptionalPatches(NAME, patchFile)
    const row = patches?.[0]?.insert?.[0] as unknown as Record<string, unknown>
    const headers = (row.config as Record<string, unknown>).headers as Record<string, unknown>

    // The loader preserves `!!js` as an expression node. That is the whole secret boundary, and
    // it is the upstream parser asserting it rather than this repository asserting it about
    // itself: the token is resolved by the host at runtime, so it is never present in the patch
    // the repository renders, commits, or ships.
    const authorization = headers.Authorization as { __jsExpr?: string }
    expect(authorization.__jsExpr, 'Authorization must stay a !!js expression node').toEqual(
      expect.stringContaining('process.env'),
    )
    expect(JSON.stringify(patches)).not.toContain(token)
  })

  it('treats an absent patch layer as absent rather than as an empty one', () => {
    const absent = join(profileDir, 'absent-directory', PROFILE_PATCH_FILENAME)

    // `undefined` and `[]` are different answers: the first says no layer was configured, the
    // second would silently claim a configured-but-empty layer.
    expect(loadOptionalPatches(NAME, absent)).toBeUndefined()
  })

  it('reports the rendered patch as the only admitted plugin row', () => {
    const patches = loadOptionalPatches(NAME, patchFile)
    const inserted = patches?.flatMap(patch => patch.insert ?? []) ?? []

    expect(inserted).toHaveLength(1)
    expect(inserted.filter(row => (row as unknown as { id?: string }).id === ENTRY_ID)).toHaveLength(1)

    console.log('KMP_DSH_CORDIS_PATCH_PASS')
  })
})
