import { Command } from "commander";
import { readFileSync, writeFileSync, existsSync } from "fs";
import { join } from "path";
import { RefineryConfig } from "../config";
import { RegistryClient } from "../client";

export function updateCommand() {
  const cmd = new Command("update");
  cmd
    .description("Update pinned artefacts to latest compatible versions")
    .argument("[id]", "Artefact ID to update (omit for all)")
    .option("--all", "Update all artefacts in .registry-lock")
    .option("--minor", "Allow minor version bumps only", true)
    .option("--major", "Allow major version bumps")
    .option("--dry-run", "Show what would be updated without writing")
    .action(async (id: string | undefined, opts: { all?: boolean; minor?: boolean; major?: boolean; dryRun?: boolean }) => {
      const config = new RefineryConfig();
      const lockPath = join(process.cwd(), ".registry-lock");
      if (!existsSync(lockPath)) {
        console.log("No .registry-lock found.");
        return;
      }
      const data = JSON.parse(readFileSync(lockPath, "utf-8")) as {
        registryVersion: string;
        dependencies: Record<string, { version: string; checksum?: string; signature?: string }>;
      };
      const deps = data.dependencies ?? {};
      if (Object.keys(deps).length === 0) {
        console.log("No dependencies in lock file.");
        return;
      }
      const toUpdate = id ? (deps[id] ? { [id]: deps[id] } : {}) : deps;
      if (Object.keys(toUpdate).length === 0) {
        console.log("No matching dependency to update.");
        return;
      }
      if (!config.apiKey && !opts.dryRun) {
        console.error("Not authenticated. Run: refinery auth login");
        process.exit(1);
      }
      const client = config.apiKey ? new RegistryClient({ baseUrl: config.url, apiKey: config.apiKey }) : null;
      for (const [depId, meta] of Object.entries(toUpdate)) {
        const [namespace, name] = depId.split("/");
        if (!namespace || !name || !client) {
          if (opts.dryRun) console.log("Would check:", depId);
          continue;
        }
        try {
          const artefact = await client.get(namespace, name) as { version?: string; id?: string; checksum?: string; signature?: string };
          if (artefact.version && artefact.version !== meta.version) {
            console.log(depId, meta.version, "->", artefact.version);
            if (!opts.dryRun) {
              data.dependencies[depId] = {
                version: artefact.version,
                checksum: artefact.checksum,
                signature: artefact.signature,
                pulledAt: new Date().toISOString(),
              };
            }
          }
        } catch (e) {
          console.error("Failed to fetch", depId, (e as Error).message);
        }
      }
      if (!opts.dryRun && Object.keys(toUpdate).length > 0) {
        writeFileSync(lockPath, JSON.stringify(data, null, 2));
        console.log("Updated .registry-lock");
      }
    });
  return cmd;
}
