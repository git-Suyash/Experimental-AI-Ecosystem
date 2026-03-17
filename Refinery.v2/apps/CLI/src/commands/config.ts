import { Command } from "commander";
import { RefineryConfig } from "../config";

export function configCommand() {
  const program = new Command("config");
  program.description("Manage CLI configuration");

  program
    .command("get [key]")
    .description("Get config value(s)")
    .action((key: string | undefined) => {
      const config = new RefineryConfig();
      if (key) {
        const v = config.get(key);
        console.log(v ?? "");
      } else {
        console.log(JSON.stringify(config.list(), null, 2));
      }
    });

  program
    .command("set <key> <value>")
    .description("Set config value")
    .action((key: string, value: string) => {
      const config = new RefineryConfig();
      config.set(key, value);
      console.log("Updated", key);
    });

  program
    .command("list")
    .description("List all config")
    .action(() => {
      const config = new RefineryConfig();
      console.log(JSON.stringify(config.list(), null, 2));
    });

  return program;
}
