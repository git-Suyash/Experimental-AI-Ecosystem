import { Command } from "commander";
import { writeFileSync, mkdirSync, existsSync, readFileSync } from "fs";
import { join } from "path";
import { RefineryConfig } from "../config";
import { RegistryClient } from "../client";

export function pullCommand() {
  const cmd = new Command("pull");
  cmd
    .description("Fetch an artefact and write it locally")
    .argument("<id>", "Artefact ID (namespace/name or namespace/name@version)")
    .option("--out <path>", "Output directory", "./registry-cache")
    .option("--format <fmt>", "Output format: yaml | json", "yaml")
    .option("--content-only", "Write only the content block")
    .option("--pin", "Write a .registry-lock file pinning this version")
    .action(async (id: string, opts: { out?: string; format?: string; contentOnly?: boolean; pin?: boolean }) => {
      const config = new RefineryConfig();
      if (!config.apiKey) {
        console.error("Not authenticated. Run: refinery auth login");
        process.exit(1);
      }
      let namespace: string;
      let name: string;
      let version: string | undefined;
      if (id.includes("@")) {
        const [rest, ver] = id.split("@");
        version = ver;
        const parts = (rest ?? "").split("/");
        namespace = parts[0] ?? "default";
        name = parts.slice(1).join("/") || parts[1] ?? "unknown";
      } else {
        const parts = id.split("/");
        namespace = parts[0] ?? "default";
        name = parts.slice(1).join("/") || parts[1] ?? "unknown";
      }
      const client = new RegistryClient({ baseUrl: config.url, apiKey: config.apiKey });
      try {
        const artefact = await client.get(namespace, name, version, opts.contentOnly) as Record<string, unknown>;
        const outDir = opts.out ?? "./registry-cache";
        mkdirSync(outDir, { recursive: true });
        const filename = `${name}.${opts.format === "json" ? "json" : "yaml"}`;
        const outPath = join(outDir, namespace, filename);
        mkdirSync(join(outDir, namespace), { recursive: true });
        if (opts.format === "json") {
          writeFileSync(outPath, JSON.stringify(artefact, null, 2));
        } else {
          const yaml = await import("yaml");
          writeFileSync(outPath, yaml.stringify(artefact));
        }
        console.log("Written:", outPath);
        if (opts.pin && artefact.id && artefact.version) {
          const lockPath = join(process.cwd(), ".registry-lock");
          const lock = existsSync(lockPath)
            ? JSON.parse(readFileSync(lockPath, "utf-8")) as { registryVersion: string; dependencies: Record<string, unknown> }
            : { registryVersion: "1", dependencies: {} };
          lock.dependencies[artefact.id as string] = {
            version: artefact.version,
            checksum: artefact.checksum,
            signature: artefact.signature,
            pulledAt: new Date().toISOString(),
          };
          writeFileSync(lockPath, JSON.stringify(lock, null, 2));
          console.log("Updated .registry-lock");
        }
      } catch (e) {
        console.error("Pull failed:", (e as Error).message);
        process.exit(1);
      }
    });
  return cmd;
}
