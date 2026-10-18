import { beforeEach, describe, expect, it, vi } from 'vitest'
import { readFileSync, readdirSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { english, LOCALE_KEY, readLocale, translate } from './messages'
import { AUDIT_ACTION_LABELS, AUDIT_ENTITY_LABELS } from '../components/AuditUi'

describe('Chinese and English messages', () => {
  beforeEach(() => { localStorage.clear(); vi.restoreAllMocks() })
  it('defaults to Chinese and restores only supported language preferences', () => {
    expect(readLocale()).toBe('zh-CN')
    localStorage.setItem(LOCALE_KEY, 'en')
    expect(readLocale()).toBe('en')
    localStorage.setItem(LOCALE_KEY, 'unexpected')
    expect(readLocale()).toBe('zh-CN')
  })
  it('remains usable when local storage is blocked', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
    expect(readLocale()).toBe('zh-CN')
  })
  it('interpolates complete sentences in either language without modifying business data', () => {
    expect(translate('en', '第 {page} / {total} 页', { page: 2, total: 3 })).toBe('Page 2 of 3')
    expect(translate('zh-CN', '第 {page} / {total} 页', { page: 2, total: 3 })).toBe('第 2 / 3 页')
    expect(translate('en', '编辑服务 {name}', { name: '支付服务' })).toBe('Edit service 支付服务')
  })
  it('includes English translations for all literal UI translation keys', () => {
    const root = resolve(process.cwd(), 'src')
    function files(directory: string): string[] {
      return readdirSync(directory, { withFileTypes: true }).flatMap(entry =>
        entry.isDirectory() ? files(join(directory, entry.name)) : entry.name.endsWith('.tsx') ? [join(directory, entry.name)] : [])
    }
    const missing: string[] = []
    for (const file of files(root)) {
      for (const match of readFileSync(file, 'utf8').matchAll(/\bt\(\s*['"]([^'"]+)['"]/g)) {
        if (!Object.hasOwn(english, match[1])) missing.push(match[1])
      }
    }
    expect([...new Set(missing)]).toEqual([])
  })
  it('translates all audit entity and known action labels', () => {
    for (const key of [...Object.values(AUDIT_ACTION_LABELS), ...Object.values(AUDIT_ENTITY_LABELS)]) {
      expect(english, key).toHaveProperty(key)
    }
  })
})
