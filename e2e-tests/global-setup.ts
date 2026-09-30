import { execFileSync } from 'node:child_process';
import { copyFileSync, existsSync } from 'node:fs';
import path from 'node:path';

/**
 * The specs change data (a refund, a new SKU, a stock edit), so every run starts
 * the compose stack on a fresh database seeded with the demo profile.
 * Set E2E_SKIP_STACK=1 to run against a stack you manage yourself.
 */
export default function globalSetup(): void {
  if (process.env.E2E_SKIP_STACK) {
    return;
  }
  const root = path.resolve(__dirname, '..');
  if (!existsSync(path.join(root, '.env'))) {
    copyFileSync(path.join(root, '.env.example'), path.join(root, '.env'));
  }
  execFileSync('docker', ['compose', 'down', '--volumes', '--remove-orphans'], { cwd: root, stdio: 'inherit' });
  execFileSync('docker', ['compose', 'up', '--build', '--wait'], { cwd: root, stdio: 'inherit' });
}
