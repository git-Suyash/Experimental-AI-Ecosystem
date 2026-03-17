import { Command } from "commander";
import { RefineryConfig } from "../config";
import { RegistryClient } from "../client";

export function diffCommand() {
  const cmd = new Command("diff");
  cmd
    .description("Show the diff between two versions")
    .argument("<id>", "Artefact ID (namespace/name)")
    .argument("<from-version>", "From version")
    .argument("<to-version>", "To version")
    .option("--format <fmt>", "text | json | unified", "text")
    .action(async (id: string, fromVersion: string, toVersion: string, opts: { format?: string }) => {
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
        const result = await client.diff(namespace, name, fromVersion, toVersion);
        if (opts.format === "json") {
          console.log(JSON.stringify(result, null, 2));
        } else {
          console.log(`${result.from} -> ${result.to} (${result.bumpType})`);
          console.log(JSON.stringify(result.changes, null, 2));
        }
      } catch (e) {
        console.error("Diff failed:", (e as Error).message);
        process.exit(1);
      }
    });
  return cmd;
}
