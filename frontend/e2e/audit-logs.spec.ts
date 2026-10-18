import { expect, test } from '@playwright/test'
import type { Page } from '@playwright/test'

test.use({ timezoneId: 'Asia/Shanghai' })
const language = (page: Page) => page.locator('dialog[open] select[aria-label="界面语言 / Interface language"], body:not(:has(dialog[open])) select[aria-label="界面语言 / Interface language"]')
async function login(page: Page, username = 'browseradmin') {
  await page.goto('/login')
  await language(page).selectOption('en')
  await page.locator('input[name="username"]').fill(username)
  await page.locator('input[name="password"]').fill('BrowserTestPass123!')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page).toHaveURL(username === 'browseradmin' ? /\/users$/ : /\/account$/)
}
async function openLogs(page: Page) {
  await page.getByRole('navigation').getByRole('link', { name: 'Audit logs', exact: true }).click()
  await expect(page.getByRole('table', { name: 'Audit records' })).toBeVisible()
}
async function auditApi(page: Page, suffix = '') {
  return page.evaluate(async path => {
    const session = JSON.parse(sessionStorage.getItem('opsflow.session.v1') || '{}')
    const response = await fetch(`/api/v1/audit-logs${path}`, { headers: { Authorization: `Bearer ${session.token}` } })
    if (!response.ok) throw new Error(`Audit read returned ${response.status}`)
    return response.json()
  }, suffix)
}
async function search(page: Page) {
  const response = page.waitForResponse(value => value.request().method() === 'GET' && new URL(value.url()).pathname === '/api/v1/audit-logs')
  await page.getByRole('button', { name: 'Search logs', exact: true }).click()
  const result = await response
  expect(result.ok()).toBe(true)
  await expect(page.getByRole('table', { name: 'Audit records' })).toBeVisible()
  return result
}
async function localInput(page: Page, value: string, delta = 0) {
  return page.evaluate(({ value, delta }) => {
    const date = new Date(Date.parse(value) + delta)
    return new Date(date.getTime() - date.getTimezoneOffset() * 60_000).toISOString().slice(0, 16)
  }, { value, delta })
}

test('admin entry shows real totals, stable paged rows, and page-size changes', async ({ page }) => {
  await login(page)
  await openLogs(page)
  const expected = await auditApi(page, '?page=0&size=10')
  await expect(page.getByRole('heading', { name: 'Audit logs' })).toContainText(String(expected.totalElements))
  await expect(page.locator('tr[data-log-id]')).toHaveCount(10)
  expect(await page.locator('tr[data-log-id]').evaluateAll(rows => rows.map(row => Number(row.getAttribute('data-log-id')))))
    .toEqual(expected.items.map((item: { id: number }) => item.id))
  await page.getByRole('button', { name: 'Next page', exact: true }).click()
  await expect(page.getByText(`Page 2 of ${expected.totalPages}`, { exact: true })).toBeVisible()
  const second = await auditApi(page, '?page=1&size=10')
  await expect(page.locator('tr[data-log-id]').first()).toHaveAttribute('data-log-id', String(second.items[0].id))
  await page.getByLabel('Logs per page').selectOption('20')
  await expect(page.locator('tr[data-log-id]')).toHaveCount(20)
  await expect(page.getByText(`Page 1 of ${Math.ceil(expected.totalElements / 20)}`, { exact: true })).toBeVisible()
})

test('all six filters reach the backend together and reset restores page one', async ({ page }) => {
  await login(page)
  await openLogs(page)
  const { items: [event] } = await auditApi(page, '?entityType=SERVICE&action=SERVICE_CREATED&size=1')
  await page.getByLabel('Actor ID', { exact: true }).fill(String(event.actor.id))
  await page.getByLabel('Action code', { exact: true }).fill(event.action)
  await page.getByLabel('Entity type', { exact: true }).selectOption('SERVICE')
  await page.getByLabel('Entity ID', { exact: true }).fill(String(event.entityId))
  const from = await localInput(page, event.occurredAt, -120000), to = await localInput(page, event.occurredAt, 120000)
  await page.getByLabel('From (inclusive)').fill(from)
  await page.getByLabel('To (exclusive)').fill(to)
  const response = await search(page)
  const params = new URL(response.url()).searchParams
  const instants = await page.evaluate(values => values.map(value => new Date(value).toISOString()), [from, to])
  expect(Object.fromEntries(params)).toEqual({ page: '0', size: '10', actorId: String(event.actor.id), action: event.action, entityType: 'SERVICE', entityId: String(event.entityId), from: instants[0], to: instants[1] })
  await expect(page.locator('tr[data-log-id]')).toHaveCount(1)
  await expect(page.locator('tr[data-log-id]')).toHaveAttribute('data-log-id', String(event.id))
  await page.getByRole('button', { name: 'Reset filters', exact: true }).click()
  await expect(page.getByLabel('Action code', { exact: true })).toHaveValue('')
  await expect(page.getByLabel('From (inclusive)')).toHaveValue('')
  await expect(page.locator('tr[data-log-id]')).toHaveCount(10)
  await expect(page.getByRole('button', { name: 'Previous page', exact: true })).toBeDisabled()
})

test('details preserve raw JSON, switch language and retain the list page on close', async ({ page }) => {
  await login(page)
  await openLogs(page)
  await page.getByRole('button', { name: 'Next page', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Previous page', exact: true })).toBeEnabled()
  const row = page.locator('tr[data-log-id]').first()
  const id = Number(await row.getAttribute('data-log-id'))
  const expected = await auditApi(page, `/${id}`)
  const totalBefore = (await auditApi(page)).totalElements
  await page.getByRole('button', { name: `View log ${id}`, exact: true }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByRole('heading', { name: 'Audit log details', exact: true })).toBeVisible()
  await expect(dialog.locator('pre')).toHaveText(JSON.stringify(expected.details, null, 2))
  await expect(dialog).toContainText(expected.action)
  await language(page).selectOption('zh-CN')
  await expect(dialog.getByRole('heading', { name: '审计日志详情', exact: true })).toBeVisible()
  await expect(dialog.locator('pre')).toHaveText(JSON.stringify(expected.details, null, 2))
  await dialog.getByRole('button', { name: '关闭', exact: true }).click()
  await expect(page.getByRole('button', { name: '上一页', exact: true })).toBeEnabled()
  await expect(page.locator('tr[data-log-id]').first()).toHaveAttribute('data-log-id', String(id))
  await expect(page.getByRole('button', { name: /删除|编辑/ })).toHaveCount(0)
  expect((await auditApi(page)).totalElements).toBe(totalBefore)
})

test('null actors and empty results are readable and validation stays bilingual', async ({ page }) => {
  await login(page)
  await openLogs(page)
  await page.getByLabel('Action code', { exact: true }).fill('USER_CREATED')
  await page.getByLabel('Entity type', { exact: true }).selectOption('USER')
  await page.getByLabel('Entity ID', { exact: true }).fill('1')
  await search(page)
  await expect(page.locator('tr[data-log-id]')).toHaveCount(1)
  await expect(page.locator('tr[data-log-id]')).toContainText('System activity')
  await page.locator('tr[data-log-id]').getByRole('button').click()
  await expect(page.getByRole('dialog')).toContainText('System activity')
  await page.getByRole('dialog').getByRole('button', { name: 'Close', exact: true }).click()
  await page.getByLabel('Action code', { exact: true }).fill('NO_SUCH_ACTION')
  await search(page)
  await expect(page.getByRole('heading', { name: 'No matching audit logs' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Next page', exact: true })).toBeDisabled()
  await page.getByLabel('Actor ID', { exact: true }).fill('abc')
  await page.getByRole('button', { name: 'Search logs', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('positive integers')
  await language(page).selectOption('zh-CN')
  await expect(page.getByRole('alert')).toContainText('正整数')
  await expect(page.getByLabel('动作代码', { exact: true })).toHaveValue('NO_SUCH_ACTION')
  await language(page).selectOption('en')
  await page.getByLabel('Actor ID', { exact: true }).fill('')
  await page.getByLabel('From (inclusive)').fill('2026-10-18T09:30')
  await page.getByLabel('To (exclusive)').fill('2026-10-18T09:30')
  await page.getByRole('button', { name: 'Search logs', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('start time must be before')
})

test('developers and approvers have neither the menu nor route or API access', async ({ page }) => {
  for (const username of ['developer', 'approver']) {
    await login(page, username)
    await expect(page.getByRole('navigation').getByRole('link', { name: 'Audit logs', exact: true })).toHaveCount(0)
    await page.goto('/audit-logs')
    await expect(page).toHaveURL(/\/account$/)
    const statuses = await page.evaluate(async () => {
      const session = JSON.parse(sessionStorage.getItem('opsflow.session.v1') || '{}')
      return Promise.all(['/api/v1/audit-logs', '/api/v1/audit-logs/1'].map(async path =>
        (await fetch(path, { headers: { Authorization: `Bearer ${session.token}` } })).status))
    })
    expect(statuses).toEqual([403, 403])
    await page.getByRole('button', { name: 'Sign out', exact: true }).click()
  }
})

test('anonymous navigation returns an administrator to the requested page after login', async ({ page }) => {
  await page.goto('/audit-logs')
  await expect(page).toHaveURL(/\/login$/)
  await language(page).selectOption('en')
  await page.locator('input[name="username"]').fill('browseradmin')
  await page.locator('input[name="password"]').fill('BrowserTestPass123!')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page).toHaveURL(/\/audit-logs$/)
  await expect(page.getByRole('table', { name: 'Audit records' })).toBeVisible()
})

test('service audit details keep payload values and link to the related business record', async ({ page }) => {
  await login(page)
  await openLogs(page)
  await page.getByLabel('Action code', { exact: true }).fill('SERVICE_CREATED')
  await page.getByLabel('Entity type', { exact: true }).selectOption('SERVICE')
  await page.getByLabel('Entity ID', { exact: true }).fill('1')
  await search(page)
  await page.locator('tr[data-log-id]').getByRole('button').click()
  const dialog = page.getByRole('dialog')
  await expect(dialog.locator('pre')).toContainText('catalog-01')
  await dialog.getByRole('link', { name: 'View related record', exact: true }).click()
  await expect(page).toHaveURL(/\/services\/1$/)
  await expect(page.getByRole('heading', { name: 'Catalog service 1', exact: true })).toBeVisible()
  await expect(dialog).toHaveCount(0)
})

test('list and detail transport failures show retry without exposing stale content', async ({ page }) => {
  await login(page)
  // Only inject a transport failure; successful reads still hit the real application.
  await page.route('**/api/v1/audit-logs?**', route => route.abort())
  await page.getByRole('navigation').getByRole('link', { name: 'Audit logs', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Cannot connect')
  await expect(page.getByRole('table', { name: 'Audit records' })).toHaveCount(0)
  await page.unroute('**/api/v1/audit-logs?**')
  await page.getByRole('button', { name: 'Retry', exact: true }).click()
  await expect(page.locator('tr[data-log-id]')).toHaveCount(10)
  const id = Number(await page.locator('tr[data-log-id]').first().getAttribute('data-log-id'))
  await page.route(`**/api/v1/audit-logs/${id}`, route => route.abort())
  await page.getByRole('button', { name: `View log ${id}`, exact: true }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByRole('alert')).toContainText('Cannot connect')
  await expect(dialog.locator('pre')).toHaveCount(0)
  await page.unroute(`**/api/v1/audit-logs/${id}`)
  await dialog.getByRole('button', { name: 'Retry', exact: true }).click()
  await expect(dialog.locator('pre')).toBeVisible()
})

test('audit list and details adapt to desktop and mobile in both languages', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await login(page)
  await openLogs(page)
  await page.setViewportSize({ width: 1440, height: 1100 })
  await page.screenshot({ path: 'test-results/audit-logs-en.png', fullPage: true, animations: 'disabled' })
  await page.setViewportSize({ width: 390, height: 844 })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await language(page).selectOption('zh-CN')
  await page.screenshot({ path: 'test-results/audit-logs-mobile.png', fullPage: true, animations: 'disabled' })
  await page.locator('.table-scroll').evaluate(element => { element.scrollLeft = element.scrollWidth })
  await page.locator('tr[data-log-id]').first().getByRole('button').click()
  await expect(page.getByRole('dialog').locator('pre')).toBeVisible()
  await language(page).selectOption('en')
  await expect(page.getByRole('dialog').getByRole('heading', { name: 'Audit log details', exact: true })).toBeVisible()
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  await page.screenshot({ path: 'test-results/audit-detail-mobile.png', animations: 'disabled' })
  expect(errors).toEqual([])
})
