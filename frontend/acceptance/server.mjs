import { spawn } from 'node:child_process'
import { readFileSync } from 'node:fs'
import { resolve, join } from 'node:path'

const backend = resolve('../backend')
const database = process.env.OPSFLOW_ACCEPTANCE_DATABASE
if (!/^opsflow_acceptance_[a-f0-9]{12}$/.test(database || '')) throw new Error('Run the opt-in acceptance runner first.')
const classpath = [join(backend, 'target/classes'), readFileSync(join(backend, 'target/browser-test-classpath.txt'), 'utf8').trim()].join(process.platform === 'win32' ? ';' : ':')
const child = spawn(process.env.OPSFLOW_ACCEPTANCE_JAVA, ['--class-path', classpath, resolve('acceptance/support/PostgresAcceptanceServer.java'), 'serve', database], { cwd: backend, stdio: 'inherit' })
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { child.kill(); process.exit(0) })
child.on('error', () => { console.error('Acceptance server could not start.'); process.exit(1) })
child.on('exit', code => process.exit(code || 0))
