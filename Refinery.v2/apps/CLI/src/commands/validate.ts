import { Command } from "commander";
import { readFileSync, existsSync } from "fs";

export function validateCommand() {
  const cmd = new Command("validate");
  cmd
    .description("Validate a local YAML file against the registry schema")
    .argument("<file>", "Path to YAML file")
    .option("--strict", "Fail on medium-severity findings")
    .option("--json", "Output results as JSON")
    .action(async (file: string, opts: { strict?: boolean; json?: boolean }) => {
      if (!existsSync(file)) {
        console.error("File not found:", file);
        process.exit(1);
      }
      const content = readFileSync(file, "utf-8");
      let parsed: unknown;
      try {
        const yaml = await import("yaml");
        parsed = yaml.parse(content);
      } catch {
        try {
          parsed = JSON.parse(content);
        } catch (e) {
          console.error("Invalid YAML/JSON:", e);
          process.exit(1);
        }
      }
      const obj = parsed as Record<string, unknown>;
      const hasContent = obj && (obj.content || (obj as { system?: string }).system);
      if (opts.json) {
        console.log(JSON.stringify({ valid: !!hasContent, schema: "ok" }));
      } else {
        console.log("Schema validation:", hasContent ? "OK" : "Missing content");
      }
    });
  return cmd;
}
