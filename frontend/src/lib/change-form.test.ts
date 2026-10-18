import { describe, expect, it } from 'vitest'
import { dateTimeInstant, localDateTime } from './change-form'
describe('change planned-time conversion', () => {
  it('round-trips local wall time through an explicit UTC instant', () => {
    const value = '2030-06-15T14:35'
    const instant = dateTimeInstant(value)
    expect(instant).toMatch(/Z$/)
    expect(localDateTime(instant)).toBe(value)
  })
  it('keeps optional dates empty and rejects invalid input', () => {
    expect(dateTimeInstant('')).toBeNull()
    expect(localDateTime(null)).toBe('')
    expect(localDateTime('invalid')).toBe('')
    expect(() => dateTimeInstant('invalid')).toThrow('计划时间格式无效。')
  })
})
