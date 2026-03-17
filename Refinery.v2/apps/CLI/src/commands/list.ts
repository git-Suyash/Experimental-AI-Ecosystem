import { Command } from "commander";
import { readFileSync, existsSync } from "fs";
import { join } from "path";

export function listCommand() {
  const cmd = new Command("list");
  cmd
    .description("List artefacts used in the current project")
    .option("--updates", "Check for newer verified versions")
    .option("--json", "Output as JSON")
    .action(async (opts: { updates?: boolean; json?: boolean }) => {
      const lockPath = join(process.cwd(), ".registry-lock");
      const altPath = join(process.cwd(), "registry.json");
      const path = existsSync(lockPath) ? lockPath : existsSync(altPath) ? altPath : null;
      if (!path) {
        if (opts.json) console.log(JSON.stringify({ dependencies: [] }));
        else console.log("No .registry-lock or registry.json in this directory.");
        return;
      }
      const data = JSON.parse(readFileSync(path, "utf-8")) as { dependencies?: Record<string, { version: string }> };
      const deps = data.dependencies ?? {};
      if (opts.json) {
        console.log(JSON.stringify({ dependencies: deps }));
        return;
      }
      for (const [id, meta] of Object.entries(deps)) {
        console.log(`  ${id}  @${meta.version}`);
      }
    });
  return cmd;
}
