const fs = require("fs");
const path = require("path");

const root = path.resolve(__dirname, "..");
const reportPath = path.join(root, "target", "surefire-reports", "TEST-com.kaces.pandora.PandoraApplicationTests.xml");
const outDir = path.join(root, "target", "server-logs");
const argsPath = path.join(outDir, "pandora-java.args");

function decodeXml(value) {
  return value
    .replace(/&quot;/g, "\"")
    .replace(/&apos;/g, "'")
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&amp;/g, "&");
}

if (!fs.existsSync(reportPath)) {
  console.error(`[pandora] Missing classpath report: ${reportPath}`);
  console.error("[pandora] Run tests once to generate the surefire classpath report.");
  process.exit(1);
}

const report = fs.readFileSync(reportPath, "utf8");
const match = report.match(/<property name="java\.class\.path" value="([^"]+)"/);
if (!match) {
  console.error("[pandora] java.class.path was not found in the surefire report.");
  process.exit(1);
}

fs.mkdirSync(outDir, { recursive: true });
fs.writeFileSync(
  argsPath,
  [
    "-Dfile.encoding=UTF-8",
    "-Dspring.output.ansi.enabled=never",
    "-Dspring.devtools.restart.enabled=false",
    "-Dspring.devtools.add-properties=false",
    "-cp",
    decodeXml(match[1]),
    "com.kaces.pandora.app.PandoraApplication",
    "",
  ].join("\r\n"),
  "utf8"
);

console.log(`[pandora] Wrote ${argsPath}`);
