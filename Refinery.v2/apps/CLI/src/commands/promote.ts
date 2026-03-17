import { Command } from "commander";
import { RefineryConfig } from "../config";
import { RegistryClient } from "../client";

export function promoteCommand() {
  const cmd = new Command("promote");
  cmd
    .description("Promote an artefact version through the lifecycle")
    .argument("<id@version>", "Artefact ID and version (e.g. legal/summarise@2.1.0)")
    .requiredOption("--to <status>", "Target status: review | verified | deprecated")
    .option("--comment <text>", "Comment for the audit trail")
    .option("--successor <id@v>", "Required when deprecating")
    .action(async (idVersion: string, opts: { to: string; comment?: string; successor?: string }) => {
      const config = new RefineryConfig();
      if (!config.apiKey) {
        console.error("Not authenticated. Run: refinery auth login");
        process.exit(1);
      }
      const at = idVersion.indexOf("@");
      if (at === -1) {
        console.error("Expected id@version (e.g. legal/summarise@2.1.0)");
        process.exit(1);
      }
      const id = idVersion.slice(0, at);
      const version = idVersion.slice(at + 1);
      const [namespace, name] = id.split("/");
      if (!namespace || !name) {
        console.error("Invalid id. Use namespace/name@version");
        process.exit(1);
      }
      const client = new RegistryClient({ baseUrl: config.url, apiKey: config.apiKey });
      try {
        const result = await client.promote(namespace, name, version, {
          status: opts.to as "review" | "verified" | "deprecated",
          comment: opts.comment,
          successor: opts.successor,
        });
        console.log("Promoted:", result.id, "@" + result.version, "->", result.status);
      } catch (e) {
        console.error("Promote failed:", (e as Error).message);
        process.exit(1);
      }
    });
  return cmd;
}
