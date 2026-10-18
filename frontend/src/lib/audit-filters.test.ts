import { describe, expect, it } from 'vitest'
import { buildAuditFilters, EMPTY_AUDIT_FILTERS } from './audit-filters'

describe('audit query filters', () => {
  it('omits empty values and preserves exact action codes', () => {
    expect(JSON.stringify(buildAuditFilters(EMPTY_AUDIT_FILTERS))).toBe('{}')
    expect(buildAuditFilters({ ...EMPTY_AUDIT_FILTERS, actorId: ' 12 ', action: ' SERVICE_CREATED ', entityType: 'SERVICE', entityId: '5' }))
      .toMatchObject({ actorId: 12, action: 'SERVICE_CREATED', entityType: 'SERVICE', entityId: 5 })
    expect(buildAuditFilters({ ...EMPTY_AUDIT_FILTERS, action: 'service_created' }).action).toBe('service_created')
  })
  it('requires positive, safely representable numeric identifiers', () => {
    for (const value of ['0', '-1', '1.5', '1e3', 'abc', '9007199254740992']) {
      expect(() => buildAuditFilters({ ...EMPTY_AUDIT_FILTERS, actorId: value })).toThrow('正整数')
      expect(() => buildAuditFilters({ ...EMPTY_AUDIT_FILTERS, entityId: value })).toThrow('正整数')
    }
  })
  it('converts local wall clocks to instants and accepts one-sided time ranges', () => {
    const from = '2026-10-18T09:30'
    expect(buildAuditFilters({ ...EMPTY_AUDIT_FILTERS, from }).from).toBe(new Date(from).toISOString())
    expect(buildAuditFilters({ ...EMPTY_AUDIT_FILTERS, to: from }).to).toBe(new Date(from).toISOString())
  })
  it('rejects invalid or empty time ranges and excessive action lengths', () => {
    expect(() => buildAuditFilters({ ...EMPTY_AUDIT_FILTERS, from: 'invalid' })).toThrow('时间格式')
    for (const to of ['2026-10-18T09:30', '2026-10-18T09:29']) {
      expect(() => buildAuditFilters({ ...EMPTY_AUDIT_FILTERS, from: '2026-10-18T09:30', to })).toThrow('开始时间')
    }
    expect(() => buildAuditFilters({ ...EMPTY_AUDIT_FILTERS, action: 'a'.repeat(81) })).toThrow('80')
    expect(buildAuditFilters({ ...EMPTY_AUDIT_FILTERS, action: 'a'.repeat(80) }).action).toHaveLength(80)
  })
})
