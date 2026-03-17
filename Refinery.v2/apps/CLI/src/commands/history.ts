import { Command } from "commander";
import { RefineryConfig } from "../config";
import { RegistryClient } from "../client";

export function historyCommand() {
  const cmd = new Command("history");
  cmd
    .description("Show the version history for an artefact")
    .argument("<id>", "Artefact ID (namespace/name)")
    .option("--limit <n>", "Max versions to show", "10")
    .option("--json", "Output as JSON")
    .action(async (id: string, opts: { limit?: string; json?: boolean }) => {
      const config = new RefineryConfig();
      if (!config.apiKey) {
        console.error("Not authenticated. Run: refinery auth login");
        process.exit(1);
      }
      const [namespace, name] = id.split("/");
      if (!namespace || !name) {
        console.error("Invalid id. Use namespace/name");
        process.exit(1);
      }
      const client = new RegistryClient({ baseUrl: config.url, apiKey: config.apiKey });
      try {
        const result = await client.history(namespace, name);
        if (opts.json) {
          console.log(JSON.stringify(result, null, 2));
        } else {
          console.log("History for", result.id);
          for (const v of result.versions as { version: string; status: string; author?: string; changelog?: string }[]) {
            console.log(`  ${v.version}  ${v.status}  ${v.author ?? ""}  ${v.changelog ?? ""}`);
          }
        }
      } catch (e) {
        console.error("History failed:", (e as Error).message);
        process.exit(1);
      }
    });
  return cmd;
}
