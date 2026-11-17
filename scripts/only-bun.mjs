/*
 * Refuse to install with anything but bun.
 *
 * Wired as the `preinstall` script, which npm, pnpm, yarn and bun all run
 * before touching node_modules — so this aborts the install rather than
 * cleaning up after it.
 *
 * The `packageManager` field alone is not enough: Corepack enforces it for npm,
 * pnpm and yarn, but it is off by default on most machines and bun ignores the
 * field entirely. This check does not depend on Corepack being enabled.
 *
 * Why it matters here beyond taste: the example app resolves the plugin through
 * `bun link`, and npm/pnpm both interpret `link:` as a relative path while bun
 * interprets it as a global registration. An install with the wrong tool
 * produces a node_modules that looks fine and silently compiles a stale or
 * missing copy of the plugin. See CLAUDE.md, Gotcha 13.
 *
 * No dependency on `only-allow` — that would need npx, i.e. the thing we are
 * trying to keep out.
 *
 * The check applies only to this repository, never to an app that installs
 * the plugin. npm runs a dependency's `preinstall` too, so this file is shipped
 * in the package and steps aside there. A packed or published copy has no
 * `bun.lock` (it is not in `files`), while a checkout always does — including
 * the clone npm makes for `npm install github:...`, which should still fail
 * loudly because `dist/` is not committed.
 */

import { existsSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";

const repo = join(dirname(fileURLToPath(import.meta.url)), "..");
const isCheckout = existsSync(join(repo, "bun.lock"));

const agent = process.env.npm_config_user_agent ?? "";
const manager = agent.split("/")[0];

if (isCheckout && manager && manager !== "bun") {
  console.error(`
  This repository uses bun.

    detected:  ${manager}
    required:  bun

  Install bun (https://bun.sh) and run:

    bun install

  Using ${manager} here breaks the example app's plugin link: npm and pnpm read
  "link:" as a relative path, bun reads it as a 'bun link' registration. See
  CLAUDE.md, Gotcha 13.
`);
  process.exit(1);
}
