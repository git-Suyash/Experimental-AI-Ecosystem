import { Command } from "commander";
import * as readline from "readline";
import { RefineryConfig } from "../config";

function ask(question: string): Promise<string> {
  const rl = readline.createInterface({ input: process.stdin, output: process.stdout });
  return new Promise((resolve) => {
    rl.question(question, (answer) => {
      rl.close();
      resolve(answer.trim());
    });
  });
}

export function authCommand() {
  const program = new Command("auth");
  program.description("Authentication");

  program
    .command("login")
    .description("Interactive login (URL + API key)")
    .action(async () => {
      const url = await ask("Registry URL [http://localhost:3000]: ") || "http://localhost:3000";
      const apiKey = await ask("API Key: ");
      if (!apiKey) {
        console.error("API key is required.");
        process.exit(1);
      }
      const config = new RefineryConfig();
      config.save({ url, apiKey });
      console.log("Saved config to", RefineryConfig.configPath());
      console.log("Authenticated. Use refinery push/pull/search etc.");
    });

  return program;
}
