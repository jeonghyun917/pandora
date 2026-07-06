const fs = require("fs");
const { spawn } = require("child_process");

function main() {
  const launchFile = valueAfter("--launch-file");
  if (!launchFile) {
    throw new Error("--launch-file is required");
  }
  const spec = JSON.parse(fs.readFileSync(launchFile, "utf8").replace(/^\uFEFF/, ""));
  for (const field of ["javaExe", "arguments", "projectDir", "outLog", "errLog"]) {
    if (spec[field] == null) {
      throw new Error(`launch spec is missing ${field}`);
    }
  }
  fs.mkdirSync(dirname(spec.outLog), { recursive: true });
  fs.mkdirSync(dirname(spec.errLog), { recursive: true });
  const out = fs.openSync(spec.outLog, "a");
  const err = fs.openSync(spec.errLog, "a");
  const child = spawn(spec.javaExe, spec.arguments, {
    cwd: spec.projectDir,
    detached: true,
    windowsHide: true,
    stdio: ["ignore", out, err],
  });
  child.unref();
  console.log(String(child.pid));
}

function valueAfter(name) {
  const index = process.argv.indexOf(name);
  if (index < 0 || index + 1 >= process.argv.length) {
    return "";
  }
  return process.argv[index + 1];
}

function dirname(filePath) {
  return require("path").dirname(filePath);
}

try {
  main();
} catch (error) {
  console.error(`[pandora-detached] ${error.message}`);
  process.exit(1);
}
