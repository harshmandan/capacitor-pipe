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
 */

const agent = process.env.npm_config_user_agent ?? "";
const manager = agent.split("/")[0];

if (manager && manager !== "bun") {
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
