const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');

const projectDir = path.resolve(__dirname, '..');
const logDir = path.join(projectDir, 'target', 'server-logs');
const javaPath = 'C:\\Program Files\\Eclipse Adoptium\\jdk-17.0.18.8-hotspot\\bin\\java.exe';
const jarPath = path.join(projectDir, 'target', 'pandora-0.0.1-SNAPSHOT.jar');
const springLogPath = path.join(logDir, 'backend-8080-spring.log');

fs.mkdirSync(logDir, { recursive: true });

const out = fs.openSync(path.join(logDir, 'backend-8080.out.log'), 'a');
const err = fs.openSync(path.join(logDir, 'backend-8080.err.log'), 'a');

const child = spawn(
  javaPath,
  [
    '-Dserver.port=8080',
    '-Dspring.batch.job.enabled=false',
    '-Dfile.encoding=UTF-8',
    '-jar',
    jarPath,
    `--logging.file.name=${springLogPath}`,
  ],
  {
    cwd: projectDir,
    detached: true,
    windowsHide: true,
    stdio: ['ignore', out, err],
  },
);

child.unref();

fs.writeFileSync(path.join(logDir, 'backend-8080.pid'), `${child.pid}\n`, 'utf8');
console.log(`[pandora] backend pid ${child.pid}`);
