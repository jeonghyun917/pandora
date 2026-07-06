const { spawn } = require("child_process");
const fs = require("fs");
const path = require("path");

const projectDir = path.resolve(__dirname, "..");
const logDir = path.join(projectDir, "target", "server-logs");
const javaPath = "C:\\Program Files\\Eclipse Adoptium\\jdk-17.0.18.8-hotspot\\bin\\java.exe";
const argsPath = path.join(logDir, "pandora-java.args");
const springLogPath = path.join(logDir, "backend-8080-spring.log");

if (!fs.existsSync(argsPath)) {
  console.error(`[pandora] Missing java argfile: ${argsPath}`);
  console.error("[pandora] Run node scripts/build-java-args.js first.");
  process.exit(1);
}

fs.mkdirSync(logDir, { recursive: true });

const runId = new Date().toISOString().replace(/[-:.TZ]/g, "").slice(0, 14);
const out = fs.openSync(path.join(logDir, `backend-8080-classes-${runId}.out.log`), "a");
const err = fs.openSync(path.join(logDir, `backend-8080-classes-${runId}.err.log`), "a");

const child = spawn(
  javaPath,
  [
    `@${argsPath}`,
    "--server.port=8080",
    `--logging.file.name=${springLogPath}`,
  ],
  {
    cwd: projectDir,
    detached: true,
    windowsHide: true,
    stdio: ["ignore", out, err],
  },
);

child.unref();

fs.writeFileSync(path.join(logDir, "backend-8080.pid"), `${child.pid}\n`, "utf8");
console.log(`[pandora] backend pid ${child.pid}`);
