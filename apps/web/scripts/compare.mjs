// Fails when the committed API types are out of date with packages/api-contract/openapi.yaml.
import { readFileSync, rmSync } from "node:fs";

const [committed, fresh] = process.argv.slice(2);
const a = readFileSync(committed, "utf8");
const b = readFileSync(fresh, "utf8");
rmSync(fresh);
if (a !== b) {
  console.error(`${committed} is outdated. Run: npm run api:types`);
  process.exit(1);
}
console.log("API types are up to date.");
