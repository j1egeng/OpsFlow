import { spawnSync } from 'node:child_process'
import { randomBytes } from 'node:crypto'
import { readFileSync, mkdirSync, writeFileSync } from 'node:fs'
import { resolve, join, dirname } from 'node:path'
import { fileURLToPath } from 'node:url'

const frontend = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const backend = resolve(frontend, '../backend')
const windows = process.platform === 'win32'
const java = [process.env.JAVA_HOME && join(process.env.JAVA_HOME, 'bin', windows ? 'java.exe' : 'java'), windows && 'C:/Program Files/Java/jdk-21/bin/java.exe', 'java']
  .filter(Boolean).find(candidate => {
    const result = spawnSync(candidate, ['-version'], { encoding: 'utf8' })
    return result.status === 0 && Number((`${result.stdout}${result.stderr}`.match(/version "(\d+)/) || [])[1]) >= 21
  })
if (!java) throw new Error('JDK 21 is required.')
const database = `opsflow_acceptance_${randomBytes(6).toString('hex')}`
const env = { ...process.env, OPSFLOW_ACCEPTANCE_DATABASE: database, OPSFLOW_ACCEPTANCE_JAVA: java }
if (java !== 'java') env.JAVA_HOME = dirname(dirname(java))
const args = ['-q', '-ntp', 'test-compile', 'dependency:build-classpath', '-Dmdep.includeScope=test', '-Dmdep.outputFile=target/browser-test-classpath.txt']
const compiled = windows ? spawnSync('cmd.exe', ['/d', '/c', 'mvnw.cmd', ...args], { cwd: backend, env, stdio: 'inherit' })
  : spawnSync('sh', ['mvnw', ...args], { cwd: backend, env, stdio: 'inherit' })
if (compiled.status !== 0) process.exit(1)
const classpath = [join(backend, 'target/classes'), readFileSync(join(backend, 'target/browser-test-classpath.txt'), 'utf8').trim()].join(windows ? ';' : ':')
const output = resolve(frontend, 'acceptance-results')
mkdirSync(output, { recursive: true })
function operation(mode) {
  const result = spawnSync(java, ['--class-path', classpath, resolve(frontend, 'acceptance/support/PostgresAcceptanceServer.java'), mode, database], { cwd: backend, env, encoding: 'utf8' })
  if (result.status !== 0) throw new Error(`Acceptance ${mode} failed. Check local PostgreSQL access and CREATEDB permission. ${result.stderr || ''}`)
  return result.stdout.trim()
}
const before = operation('baseline')
let created = false, exitCode = 1, removed = false, after = '', inspection = ''
try {
  console.log(operation('create')); created = true
  const run = spawnSync(process.execPath, [resolve(frontend, 'node_modules/@playwright/test/cli.js'), 'test', '--config=playwright.acceptance.config.ts'], { cwd: frontend, env, stdio: 'inherit' })
  exitCode = run.status ?? 1
} finally {
  try { if (created) { inspection = operation('inspect'); console.log(inspection) } }
  catch (error) { console.error(error.message); exitCode = 1 }
  try { if (created) { console.log(operation('drop')); removed = true } }
  catch (error) { console.error(error.message); exitCode = 1 }
  after = operation('baseline')
  writeFileSync(join(output, 'database-verification.txt'), `Configured opsflow database BEFORE:\n${before}\n\nAcceptance PostgreSQL:\n${inspection}\n\nConfigured opsflow database AFTER:\n${after}\n\nTemporary database removed: ${removed}\n`, 'utf8')
  writeFileSync(join(output, 'run-summary.json'), JSON.stringify({ database, testsPassed: exitCode === 0, temporaryDatabaseRemoved: removed, configuredDatabaseCountsUnchanged: before === after, completedAt: new Date().toISOString() }, null, 2), 'utf8')
}
process.exitCode = exitCode
