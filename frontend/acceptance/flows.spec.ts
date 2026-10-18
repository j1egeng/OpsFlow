import { expect, test } from '@playwright/test'
import type { Page, TestInfo } from '@playwright/test'
import type { AuditLogDetail, AuditLogSummary, ChangeHistory, ChangeRequest, ChangeReview, DashboardSummary, Incident, IncidentHistory, PageResult, SoftwareService, User, UserPage } from '../src/types'

const password = 'AcceptanceTestPass123!' // Disposable test database only; never a real account credential.
const language = (page: Page) => page.locator('dialog[open] select[aria-label="界面语言 / Interface language"], body:not(:has(dialog[open])) select[aria-label="界面语言 / Interface language"]')
const runId = Date.now().toString(36)

async function request<T>(page: Page, path: string, method = 'GET', body?: unknown): Promise<T> {
  const result = await page.evaluate(async ({ path, method, body }) => {
    const session = JSON.parse(sessionStorage.getItem('opsflow.session.v1') || '{}')
    const response = await fetch(`/api/v1${path}`, { method, headers: { Authorization: `Bearer ${session.token}`, 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body) })
    return { status: response.status, body: await response.json() }
  }, { path, method, body })
  expect(result.status, `${method} ${path}`).toBeGreaterThanOrEqual(200)
  expect(result.status, `${method} ${path}`).toBeLessThan(300)
  return result.body as T
}

async function login(page: Page, username: string) {
  await page.goto('/account')
  await language(page).selectOption('en')
  const logout = page.getByRole('button', { name: 'Sign out', exact: true })
  if (await logout.count()) { await logout.click(); await expect(page).toHaveURL(/\/login$/) }
  await page.locator('input[name="username"]').fill(username)
  await page.locator('input[name="password"]').fill(password)
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Sign out', exact: true })).toBeVisible()
  const current = await request<User>(page, '/auth/me')
  expect(current.username).toBe(username)
  return current
}

async function actors(page: Page) {
  const admin = await login(page, 'acceptanceadmin')
  const existing = await request<UserPage>(page, '/users?size=100')
  const members: User[] = []
  for (const [username, displayName, role] of [['acceptancedev', 'TEST Developer', 'DEVELOPER'], ['acceptanceapprover', 'TEST Approver', 'APPROVER']]) {
    members.push(existing.items.find(item => item.username === username) ?? await request<User>(page, '/users', 'POST', { username, displayName, email: `${username}@example.test`, password, roles: [role] }))
  }
  return { admin, developer: members[0], approver: members[1] }
}

async function screenshot(page: Page, info: TestInfo, name: string) {
  const path = info.outputPath(`${name}.png`)
  await page.screenshot({ path, fullPage: !await page.getByRole('dialog').count(), animations: 'disabled' })
  await info.attach(name, { path, contentType: 'image/png' })
}

async function createService(page: Page, suffix: string) {
  await page.goto('/services')
  await page.getByRole('button', { name: 'New service', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.locator('input[name="code"]').fill(`TEST-${suffix}-${runId}`)
  await dialog.locator('input[name="name"]').fill(`TEST-${suffix}-${runId}`)
  await dialog.locator('textarea[name="description"]').fill('TEST 初始说明 / acceptance original')
  const pending = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/services' && response.request().method() === 'POST')
  await dialog.getByRole('button', { name: 'Create service', exact: true }).click()
  const response = await pending
  expect(response.status()).toBe(201)
  const service = await response.json() as SoftwareService
  await expect(page).toHaveURL(new RegExp(`/services/${service.id}$`))
  await expect(page.getByRole('heading', { name: service.name, exact: true })).toBeVisible()
  expect(service.code).toBe(`test-${suffix.toLowerCase()}-${runId}`)
  return service
}

async function filterAudit(page: Page, entityType: string, entityId: number, action = '', actorId?: number) {
  await page.goto('/audit-logs')
  await expect(page.getByRole('table', { name: 'Audit records' })).toBeVisible()
  await page.getByLabel('Entity type', { exact: true }).selectOption(entityType)
  await page.getByLabel('Entity ID', { exact: true }).fill(String(entityId))
  await page.getByLabel('Action code', { exact: true }).fill(action)
  await page.getByLabel('Actor ID', { exact: true }).fill(actorId ? String(actorId) : '')
  const range = await page.evaluate(() => [-86400000, 86400000].map(offset => {
    const date = new Date(Date.now() + offset)
    return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16)
  }))
  await page.getByLabel('From (inclusive)').fill(range[0])
  await page.getByLabel('To (exclusive)').fill(range[1])
  const pending = page.waitForResponse(response => new URL(response.url()).pathname === '/api/v1/audit-logs' && response.request().method() === 'GET')
  await page.getByRole('button', { name: 'Search logs', exact: true }).click()
  expect((await pending).status()).toBe(200)
  await expect(page.getByRole('table', { name: 'Audit records' })).toBeVisible()
}

test('1. TEST service creation/edit, audit record/filter/detail/languages and administrator-only access', async ({ page }, info) => {
  const people = await actors(page)
  const service = await test.step('Administrator creates a TEST- service through React', () => createService(page, 'AUDIT'))
  await test.step('Administrator edits the service and PostgreSQL retains both audit events', async () => {
    await page.getByRole('button', { name: 'Edit service', exact: true }).click()
    const dialog = page.getByRole('dialog')
    await dialog.locator('textarea[name="description"]').fill('TEST 修改后说明 / acceptance updated')
    await dialog.getByRole('button', { name: 'Save service', exact: true }).click()
    await expect(dialog).toHaveCount(0)
    const changed = await request<SoftwareService>(page, `/services/${service.id}`)
    expect(changed.description).toBe('TEST 修改后说明 / acceptance updated')
    expect(changed.version).toBe(service.version + 1)
    const logs = await request<PageResult<AuditLogSummary>>(page, `/audit-logs?entityType=SERVICE&entityId=${service.id}`)
    expect(logs.items.map(item => item.action)).toEqual(['SERVICE_UPDATED', 'SERVICE_CREATED'])
    expect(logs.items.every(item => item.actor?.id === people.admin.id)).toBe(true)
  })
  let detail: AuditLogDetail
  await test.step('Audit filters and raw detail agree with the service edit', async () => {
    await filterAudit(page, 'SERVICE', service.id, 'SERVICE_UPDATED', people.admin.id)
    await expect(page.locator('tr[data-log-id]')).toHaveCount(1)
    const id = Number(await page.locator('tr[data-log-id]').getAttribute('data-log-id'))
    detail = await request<AuditLogDetail>(page, `/audit-logs/${id}`)
    expect(detail.entityId).toBe(service.id)
    expect(detail.details.previous).toMatchObject({ description: 'TEST 初始说明 / acceptance original' })
    expect(detail.details.current).toMatchObject({ description: 'TEST 修改后说明 / acceptance updated' })
    await screenshot(page, info, '01-audit-filter-en')
    await page.getByRole('button', { name: `View log ${id}`, exact: true }).click()
    await expect(page.getByRole('dialog').locator('pre')).toHaveText(JSON.stringify(detail.details, null, 2))
  })
  await test.step('Switch English and Chinese without changing raw business content', async () => {
    const dialog = page.getByRole('dialog')
    await expect(dialog.getByRole('heading', { name: 'Audit log details', exact: true })).toBeVisible()
    await screenshot(page, info, '02-audit-detail-en')
    await language(page).selectOption('zh-CN')
    await expect(dialog.getByRole('heading', { name: '审计日志详情', exact: true })).toBeVisible()
    await expect(dialog.locator('pre')).toHaveText(JSON.stringify(detail.details, null, 2))
    await screenshot(page, info, '03-audit-detail-zh')
    await dialog.getByRole('button', { name: '关闭', exact: true }).click()
    await expect(page.getByLabel('动作代码', { exact: true })).toHaveValue('SERVICE_UPDATED')
    await language(page).selectOption('en')
    await page.getByRole('button', { name: 'Reset filters', exact: true }).click()
    await expect(page.locator('tr[data-log-id]')).toHaveCount((await request<PageResult<AuditLogSummary>>(page, '/audit-logs?size=10')).items.length)
  })
  await test.step('Developer and approver are blocked by both React route and real API', async () => {
    for (const member of [people.developer, people.approver]) {
      await login(page, member.username)
      await expect(page.getByRole('navigation').getByRole('link', { name: 'Audit logs', exact: true })).toHaveCount(0)
      await page.goto('/audit-logs')
      await expect(page).toHaveURL(/\/account$/)
      const statuses = await page.evaluate(async id => {
        const session = JSON.parse(sessionStorage.getItem('opsflow.session.v1') || '{}')
        return Promise.all(['/api/v1/audit-logs', `/api/v1/audit-logs/${id}`].map(async path => (await fetch(path, { headers: { Authorization: `Bearer ${session.token}` } })).status))
      }, detail.id)
      expect(statuses).toEqual([403, 403])
    }
  })
  await info.attach('audit-verification', { body: JSON.stringify({ serviceId: service.id, serviceCode: service.code, auditLogId: detail!.id, rolesDenied: ['DEVELOPER', 'APPROVER'], bilingualRawPayloadPreserved: true }, null, 2), contentType: 'application/json' })
})

test('2. Full incident and success/failure change workflows, linked incident, dashboard and audit', async ({ page }, info) => {
  const people = await actors(page)
  const baseline = await request<DashboardSummary>(page, '/dashboard/summary')
  const service = await createService(page, 'FLOW')
  let incidentId = 0
  await test.step('Report incident and assign it to a different developer', async () => {
    await page.goto('/incidents')
    await page.getByRole('button', { name: 'Report incident', exact: true }).click()
    const dialog = page.getByRole('dialog')
    await expect(dialog.locator('#incident-service')).toBeEnabled()
    await dialog.locator('#incident-service').selectOption(String(service.id))
    await dialog.locator('input[name="title"]').fill(`TEST-Incident-${runId}`)
    await dialog.locator('textarea[name="description"]').fill('TEST simulated service timeout; no real service is disrupted')
    await dialog.locator('select[name="severity"]').selectOption('HIGH')
    await dialog.getByRole('button', { name: 'Submit incident', exact: true }).click()
    await expect(page).toHaveURL(/\/incidents\/\d+$/)
    incidentId = Number(new URL(page.url()).pathname.split('/').at(-1))
    expect((await request<Incident>(page, `/incidents/${incidentId}`)).status).toBe('OPEN')
    await page.getByRole('button', { name: 'Assign incident', exact: true }).click()
    await expect(dialog.locator('#incident-assignee')).toBeEnabled()
    await dialog.locator('#incident-assignee').selectOption(String(people.developer.id))
    await dialog.getByRole('button', { name: 'Assign incident', exact: true }).click()
    await expect(dialog).toHaveCount(0)
    expect((await request<Incident>(page, `/incidents/${incidentId}`)).status).toBe('ASSIGNED')
  })
  await test.step('Assigned developer investigates, records a note, and resolves', async () => {
    await login(page, people.developer.username)
    await page.goto(`/incidents/${incidentId}`)
    await page.getByRole('button', { name: 'Start investigation', exact: true }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'Start investigation', exact: true }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    expect((await request<Incident>(page, `/incidents/${incidentId}`)).status).toBe('INVESTIGATING')
    await page.locator('textarea[name="note"]').fill('TEST verified connection pool saturation')
    await page.getByRole('button', { name: 'Add note', exact: true }).click()
    await expect(page.getByRole('region', { name: 'Investigation notes' })).toContainText('TEST verified connection pool saturation')
    await page.getByRole('button', { name: 'Resolve incident', exact: true }).click()
    await page.getByRole('dialog').locator('textarea[name="resolution"]').fill('TEST adjusted configuration and verified recovery')
    await page.getByRole('dialog').getByRole('button', { name: 'Resolve incident', exact: true }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    expect((await request<Incident>(page, `/incidents/${incidentId}`)).status).toBe('RESOLVED')
    await expect(page.getByRole('button', { name: 'Close incident', exact: true })).toHaveCount(0)
  })
  await test.step('Administrator closes and verifies all five history states', async () => {
    await login(page, people.admin.username)
    await page.goto(`/incidents/${incidentId}`)
    await page.getByRole('button', { name: 'Close incident', exact: true }).click()
    await page.getByRole('dialog').getByRole('button', { name: 'Close incident', exact: true }).click()
    await expect(page.getByRole('dialog')).toHaveCount(0)
    await expect(page.locator('.incident-detail-title')).toContainText('Closed')
    const history = await request<PageResult<IncidentHistory>>(page, `/incidents/${incidentId}/history`)
    expect(history.items.map(item => item.newStatus).reverse()).toEqual(['OPEN', 'ASSIGNED', 'INVESTIGATING', 'RESOLVED', 'CLOSED'])
    expect((await request<DashboardSummary>(page, '/dashboard/summary')).activeIncidents).toBe(baseline.activeIncidents)
    await screenshot(page, info, '04-incident-closed')
  })
  const changes: ChangeRequest[] = []
  for (const outcome of ['COMPLETED', 'FAILED'] as const) {
    await test.step(`Change ${outcome}: draft, submit, other-account approval, implement and record result`, async () => {
      await login(page, people.developer.username)
      await page.goto('/changes')
      await page.getByRole('button', { name: 'New change', exact: true }).click()
      const dialog = page.getByRole('dialog')
      await expect(dialog.locator('#incident-service')).toBeEnabled()
      await dialog.locator('#incident-service').selectOption(String(service.id))
      await expect(dialog.locator('#incident-related')).toBeEnabled()
      await dialog.locator('#incident-related').selectOption(String(incidentId))
      await dialog.locator('input[name="title"]').fill(`TEST-Change-${outcome}-${runId}`)
      await dialog.locator('textarea[name="businessReason"]').fill('TEST acceptance verification of deployment workflow')
      await dialog.locator('select[name="riskLevel"]').selectOption(outcome === 'FAILED' ? 'HIGH' : 'LOW')
      const future = await page.evaluate(() => {
        const date = new Date(Date.now() + 86400000)
        return new Date(date.getTime() - date.getTimezoneOffset() * 60000).toISOString().slice(0, 16)
      })
      await dialog.locator('input[name="plannedImplementationAt"]').fill(future)
      await dialog.locator('textarea[name="implementationPlan"]').fill('TEST apply simulated configuration and run health checks')
      await dialog.locator('textarea[name="rollbackPlan"]').fill('TEST restore prior configuration and verify recovery')
      await dialog.getByRole('button', { name: 'Save draft', exact: true }).click()
      await expect(page).toHaveURL(/\/changes\/\d+$/)
      const id = Number(new URL(page.url()).pathname.split('/').at(-1))
      expect((await request<ChangeRequest>(page, `/changes/${id}`)).status).toBe('DRAFT')
      await page.getByRole('button', { name: 'Submit for approval', exact: true }).click()
      await dialog.getByRole('button', { name: 'Submit for approval', exact: true }).click()
      await expect(dialog).toHaveCount(0)
      expect((await request<ChangeRequest>(page, `/changes/${id}`)).status).toBe('PENDING_APPROVAL')
      expect((await request<DashboardSummary>(page, '/dashboard/summary')).pendingApprovals).toBe(baseline.pendingApprovals + 1)
      await expect(page.getByRole('button', { name: 'Approve change', exact: true })).toHaveCount(0)

      await login(page, people.approver.username)
      await page.goto(`/changes/${id}`)
      await page.getByRole('button', { name: 'Approve change', exact: true }).click()
      await dialog.locator('textarea[name="comment"]').fill('TEST independent approval: implementation and rollback reviewed')
      await dialog.getByRole('button', { name: 'Approve change', exact: true }).click()
      await expect(dialog).toHaveCount(0)
      const reviews = await request<PageResult<ChangeReview>>(page, `/changes/${id}/reviews`)
      expect(reviews.items).toHaveLength(1)
      expect(reviews.items[0].reviewer.id).toBe(people.approver.id)
      expect(reviews.items[0].reviewer.id).not.toBe(people.developer.id)
      expect((await request<ChangeRequest>(page, `/changes/${id}`)).status).toBe('APPROVED')

      await login(page, people.developer.username)
      await page.goto(`/changes/${id}`)
      await page.getByRole('button', { name: 'Start implementation', exact: true }).click()
      await dialog.getByRole('button', { name: 'Start implementation', exact: true }).click()
      await expect(dialog).toHaveCount(0)
      expect((await request<ChangeRequest>(page, `/changes/${id}`)).status).toBe('IMPLEMENTING')
      expect((await request<DashboardSummary>(page, '/dashboard/summary')).implementingChanges).toBe(baseline.implementingChanges + 1)
      const label = outcome === 'COMPLETED' ? 'Record success' : 'Record failure'
      await page.getByRole('button', { name: label, exact: true }).click()
      await dialog.locator('textarea[name="result"]').fill(outcome === 'COMPLETED' ? 'TEST health checks passed after implementation' : 'TEST simulated failure; rollback and recovery verified')
      await dialog.getByRole('button', { name: label, exact: true }).click()
      await expect(dialog).toHaveCount(0)
      const result = await request<ChangeRequest>(page, `/changes/${id}`)
      expect(result.status).toBe(outcome)
      changes.push(result)
      const history = await request<PageResult<ChangeHistory>>(page, `/changes/${id}/history`)
      expect(history.items.map(item => item.newStatus).reverse()).toEqual(['DRAFT', 'PENDING_APPROVAL', 'APPROVED', 'IMPLEMENTING', outcome])
      if (outcome === 'COMPLETED') {
        expect(result.failureIncidentId).toBeNull()
        expect((await request<DashboardSummary>(page, '/dashboard/summary')).totalIncidents).toBe(baseline.totalIncidents + 1)
      } else {
        expect(result.failureIncidentId).not.toBeNull()
        const failure = await request<Incident>(page, `/incidents/${result.failureIncidentId}`)
        expect(failure.sourceChangeId).toBe(id)
        expect(failure.service.id).toBe(service.id)
        expect(failure.status).toBe('OPEN')
        expect(failure.severity).toBe('HIGH')
        await screenshot(page, info, '05-change-failed')
        await page.getByRole('link', { name: /View failure incident #/ }).click()
        await expect(page).toHaveURL(new RegExp(`/incidents/${result.failureIncidentId}$`))
        await screenshot(page, info, '06-failure-incident')
        await page.getByRole('link', { name: /View source change #/ }).click()
        await expect(page).toHaveURL(new RegExp(`/changes/${id}$`))
      }
    })
  }
  await test.step('Dashboard aggregates, UTC trends and visible cards include both paths', async () => {
    await login(page, people.admin.username)
    const after = await request<DashboardSummary>(page, '/dashboard/summary')
    expect(after.totalServices).toBe(baseline.totalServices + 1)
    expect(after.totalIncidents).toBe(baseline.totalIncidents + 2)
    expect(after.activeIncidents).toBe(baseline.activeIncidents + 1)
    expect(after.totalChanges).toBe(baseline.totalChanges + 2)
    expect(after.completedChanges).toBe(baseline.completedChanges + 1)
    expect(after.failedChanges).toBe(baseline.failedChanges + 1)
    expect(after.pendingApprovals).toBe(baseline.pendingApprovals)
    expect(after.implementingChanges).toBe(baseline.implementingChanges)
    expect(after.changeSuccessRate).toBe(Math.round(after.completedChanges / (after.completedChanges + after.failedChanges) * 10000) / 100)
    expect(after.trend.reduce((sum, day) => sum + day.incidents, 0)).toBe(baseline.trend.reduce((sum, day) => sum + day.incidents, 0) + 2)
    expect(after.trend.reduce((sum, day) => sum + day.changes, 0)).toBe(baseline.trend.reduce((sum, day) => sum + day.changes, 0) + 2)
    await page.goto('/dashboard')
    await expect(page.getByTestId('metric-incidents').locator('strong')).toContainText(String(after.activeIncidents))
    await expect(page.getByTestId('metric-success').locator('strong')).toContainText(`${after.changeSuccessRate}%`)
    await page.getByRole('button', { name: 'Refresh metrics', exact: true }).click()
    await expect(page.getByTestId('metric-success').locator('strong')).toContainText(`${after.changeSuccessRate}%`)
    await screenshot(page, info, '07-dashboard')
    await info.attach('business-verification', { body: JSON.stringify({ serviceId: service.id, closedIncidentId: incidentId, changes: changes.map(item => ({ id: item.id, status: item.status, requesterId: item.requester.id, failureIncidentId: item.failureIncidentId })), independentApproverId: people.approver.id, baseline, after }, null, 2), contentType: 'application/json' })
  })
  await test.step('Audit records identify all incident transitions and both independent reviews', async () => {
    const incidentLogs = await request<PageResult<AuditLogSummary>>(page, `/audit-logs?entityType=INCIDENT&entityId=${incidentId}&size=100`)
    expect(incidentLogs.items.map(item => item.action)).toEqual(expect.arrayContaining(['INCIDENT_CREATED', 'INCIDENT_ASSIGNED', 'INCIDENT_NOTE_ADDED']))
    expect(incidentLogs.items.filter(item => item.action === 'INCIDENT_STATUS_CHANGED')).toHaveLength(3)
    for (const change of changes) {
      const logs = await request<PageResult<AuditLogSummary>>(page, `/audit-logs?entityType=CHANGE_REQUEST&entityId=${change.id}&size=100`)
      expect(logs.items.map(item => item.action)).toEqual(['CHANGE_FINISHED', 'CHANGE_STARTED', 'CHANGE_REVIEWED', 'CHANGE_SUBMITTED', 'CHANGE_CREATED'])
      expect(logs.items.find(item => item.action === 'CHANGE_REVIEWED')?.actor?.id).toBe(people.approver.id)
      expect(logs.items.find(item => item.action === 'CHANGE_CREATED')?.actor?.id).toBe(people.developer.id)
    }
    const failed = changes.find(item => item.status === 'FAILED')!
    const failureLogs = await request<PageResult<AuditLogSummary>>(page, `/audit-logs?entityType=INCIDENT&entityId=${failed.failureIncidentId}`)
    expect(failureLogs.items).toHaveLength(1)
    const payload = await request<AuditLogDetail>(page, `/audit-logs/${failureLogs.items[0].id}`)
    expect(payload.details.sourceChangeId).toBe(failed.id)
    await filterAudit(page, 'CHANGE_REQUEST', failed.id)
    await expect(page.locator('tr[data-log-id]')).toHaveCount(5)
    await screenshot(page, info, '08-change-audit')
  })
})
