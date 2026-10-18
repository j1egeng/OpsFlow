import { spawn, spawnSync } from 'node:child_process'
import { dirname, join, resolve } from 'node:path'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'

const here = dirname(fileURLToPath(import.meta.url))
const backend = resolve(here, '../../backend')
const windows = process.platform === 'win32'
const candidates = [
  process.env.JAVA_HOME && join(process.env.JAVA_HOME, 'bin', windows ? 'java.exe' : 'java'),
  windows && 'C:/Program Files/Java/jdk-21/bin/java.exe',
  'java',
].filter(Boolean)
const java = candidates.find(candidate => {
  const result = spawnSync(candidate, ['-version'], { encoding: 'utf8' })
  return result.status === 0 && Number((`${result.stdout}${result.stderr}`.match(/version "(\d+)/) || [])[1]) >= 21
})
if (!java) throw new Error('Browser tests need JDK 21 or later; set JAVA_HOME before running them.')
const env = { ...process.env }
if (java !== 'java') env.JAVA_HOME = dirname(dirname(java))
const mavenArgs = ['-q', '-ntp', 'test-compile', 'dependency:build-classpath', '-Dmdep.includeScope=test', '-Dmdep.outputFile=target/browser-test-classpath.txt']
const compiled = windows
  ? spawnSync('cmd.exe', ['/d', '/c', 'mvnw.cmd', ...mavenArgs], { cwd: backend, env, stdio: 'inherit' })
  : spawnSync('sh', ['mvnw', ...mavenArgs], { cwd: backend, env, stdio: 'inherit' })
if (compiled.status !== 0) process.exit(compiled.status || 1)
const separator = windows ? ';' : ':'
const classpath = [join(backend, 'target/test-classes'), join(backend, 'target/classes'), readFileSync(join(backend, 'target/browser-test-classpath.txt'), 'utf8').trim()].join(separator)
const child = spawn(java, ['--class-path', classpath, join(here, 'support/BrowserTestServer.java')], { cwd: backend, env, stdio: 'inherit' })
for (const signal of ['SIGINT', 'SIGTERM']) process.on(signal, () => { child.kill(); process.exit(0) })
child.on('error', error => { console.error(error.message); process.exit(1) })
child.on('exit', code => process.exit(code || 0))
