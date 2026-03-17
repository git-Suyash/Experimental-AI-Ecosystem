#!/usr/bin/env bun
import { Command } from "commander";
import { pushCommand } from "./commands/push.js";
import { pullCommand } from "./commands/pull.js";
import { searchCommand } from "./commands/search.js";
import { validateCommand } from "./commands/validate.js";
import { promoteCommand } from "./commands/promote.js";
import { diffCommand } from "./commands/diff.js";
import { listCommand } from "./commands/list.js";
import { updateCommand } from "./commands/update.js";
import { historyCommand } from "./commands/history.js";
import { configCommand } from "./commands/config.js";
import { authCommand } from "./commands/auth.js";

const program = new Command();

program
  .name("refinery")
  .description("Refinery AI Prompt & Skills Registry CLI")
  .version("0.1.0");

program.addCommand(pushCommand());
program.addCommand(pullCommand());
program.addCommand(searchCommand());
program.addCommand(validateCommand());
program.addCommand(promoteCommand());
program.addCommand(diffCommand());
program.addCommand(listCommand());
program.addCommand(updateCommand());
program.addCommand(historyCommand());
program.addCommand(configCommand());
program.addCommand(authCommand());

program.parse();
