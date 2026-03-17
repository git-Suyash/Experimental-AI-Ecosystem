import { Command } from "commander";
import { readFileSync, existsSync } from "fs";
import { RefineryConfig } from "../config";
import { RegistryClient } from "../client";

export function pushCommand() {
  const cmd = new Command("push");
  cmd
    .description("Publish a prompt or skill to the registry")
    .argument("<file>", "Path to YAML artefact file")
    .option("--namespace <ns>", "Target namespace")
    .option("--name <name>", "Override artefact name")
    .option("--bump <type>", "Version bump: patch | minor | major", "patch")
    .option("--status <status>", "Initial status: draft | review", "draft")
    .option("--dry-run", "Validate and scan without publishing")
    .option("-y, --yes", "Skip confirmation")
    .action(async (file: string, opts: { namespace?: string; name?: string; bump?: string; status?: string; dryRun?: boolean; yes?: boolean }) => {
      const config = new RefineryConfig();
      if (!config.apiKey) {
        console.error("Not authenticated. Run: refinery auth login");
        process.exit(1);
      }
      if (!existsSync(file)) {
        console.error("File not found:", file);
        process.exit(1);
      }
      const content = readFileSync(file, "utf-8");
      let parsed: { id?: string; type?: string; content?: unknown; metadata?: unknown };
      try {
        const yaml = await import("yaml");
        parsed = yaml.parse(content) as typeof parsed;
      } catch {
        try {
          parsed = JSON.parse(content) as typeof parsed;
        } catch (e) {
          console.error("Invalid YAML/JSON:", e);
          process.exit(1);
        }
      }
      const id = parsed.id ?? opts.name ?? "unknown";
      const [namespace, name] = id.includes("/") ? id.split("/") : [opts.namespace ?? config.defaultNamespace, id];
      const ns = opts.namespace ?? namespace;
      const artefactName = opts.name ?? name;
      const payload = {
        namespace: ns,
        name: artefactName,
        type: (parsed.type as "prompt" | "skill" | "chain" | "tool") ?? "prompt",
        bumpType: opts.bump as "patch" | "minor" | "major",
        content: parsed.content ?? { system: "", user_template: "" },
        metadata: parsed.metadata,
      };
      if (opts.dryRun) {
        console.log("Dry run - would publish:", payload.namespace + "/" + payload.name, payload.bumpType);
        return;
      }
      const client = new RegistryClient({ baseUrl: config.url, apiKey: config.apiKey });
      try {
        const result = await client.publish(payload);
        console.log("Published:", result.id, "@" + result.version, "(" + result.status + ")");
      } catch (e) {
        console.error("Publish failed:", (e as Error).message);
        process.exit(1);
      }
    });
  return cmd;
}
