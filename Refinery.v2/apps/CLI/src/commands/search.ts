import { Command } from "commander";
import { RefineryConfig } from "../config";
import { RegistryClient } from "../client";

export function searchCommand() {
  const cmd = new Command("search");
  cmd
    .description("Search the registry")
    .argument("<query>", "Search query")
    .option("--type <type>", "Filter: prompt | skill | chain | tool")
    .option("--tags <tags>", "Comma-separated tag filter")
    .option("--model <model>", "Filter by compatible model")
    .option("--status <status>", "Default: verified", "verified")
    .option("--limit <n>", "Max results", "10")
    .option("--json", "Output raw JSON")
    .action(async (query: string, opts: { type?: string; tags?: string; model?: string; status?: string; limit?: string; json?: boolean }) => {
      const config = new RefineryConfig();
      if (!config.apiKey) {
        console.error("Not authenticated. Run: refinery auth login");
        process.exit(1);
      }
      const client = new RegistryClient({ baseUrl: config.url, apiKey: config.apiKey });
      try {
        const res = await client.search(query, {
          type: opts.type,
          tags: opts.tags,
          limit: parseInt(opts.limit ?? "10", 10),
        });
        if (opts.json) {
          console.log(JSON.stringify(res, null, 2));
        } else {
          for (const r of res.results) {
            console.log(`${r.id} @${r.version} (${r.type}) - ${r.description ?? ""}`);
          }
        }
      } catch (e) {
        console.error("Search failed:", (e as Error).message);
        process.exit(1);
      }
    });
  return cmd;
}
