import { test, expect } from '@playwright/test';

// The ops floor renders a Three.js canvas, which is opaque to Playwright — so
// the scene mirrors its truth into a hidden DOM node (data-testid="scene-state")
// and every assertion runs on rendered DOM state, never HTTP codes. Same
// philosophy as the angular and spring suites.
test.describe('Ops Floor (Three.js operations visualization)', () => {
  test('divergence count matches the known seed overlap', async ({ page }) => {
    await page.goto('/');

    // ORD-1039, ORD-1040, ORD-1041 and ORD-1042 exist in both stores with
    // different customers, quantities and statuses — 4 divergences by design.
    const state = page.getByTestId('scene-state');
    await expect(state).toHaveAttribute('data-divergences', '4');
    await expect(state).toHaveAttribute('data-visible-parcels', '17');
    await expect(state).toHaveAttribute('data-source', 'seed');
  });

  test('filtering to PENDING dims the right parcels without unmounting the canvas', async ({ page }) => {
    await page.goto('/');

    // wait for the app to mount before sending keys
    await page.getByTestId('scene-state').waitFor({ state: 'attached' });

    // pause first so the scripted timeline (ship ORD-1048 at T+10s) cannot move
    // a PENDING parcel mid-test
    await page.keyboard.press('Space');
    await expect(page.getByTestId('scene-state')).toHaveAttribute('data-paused', 'true');

    const state = page.getByTestId('scene-state');
    await expect(state).toHaveAttribute('data-visible-parcels', '17');
    await expect(page.locator('.ops-floor canvas')).toHaveCount(1);

    await page.locator('.hud-rail button', { hasText: 'PENDING' }).click();

    // 1 seller PENDING (ORD-1040) + 3 CS PENDING (ORD-1048, ORD-1044, ORD-1041)
    await expect(state).toHaveAttribute('data-visible-parcels', '4');
    await expect(page.locator('.ops-floor canvas')).toHaveCount(1);
  });

  test('clicking a log row selects the order and the inspector shows the diff', async ({ page }) => {
    await page.goto('/');

    const row = page.getByTestId('log-row').filter({ hasText: 'ORD-1042' }).first();
    await row.click();

    const state = page.getByTestId('scene-state');
    await expect(state).toHaveAttribute('data-selected-order', 'ORD-1042');

    const diff = page.getByTestId('diff');
    await expect(diff).toBeVisible();
    await expect(diff).toContainText('Ava Chen');
    await expect(diff).toContainText('Tomás Rivera');
    await expect(diff).toContainText('PROCESSING');
    await expect(diff).toContainText('SHIPPED');
    await expect(page.getByTestId('diff').locator('tr.mismatch')).toHaveCount(5);
  });

  test('Space freezes the sim clock', async ({ page }) => {
    await page.goto('/');

    await page.getByTestId('scene-state').waitFor({ state: 'attached' });
    await page.keyboard.press('Space');
    await expect(page.getByTestId('scene-state')).toHaveAttribute('data-paused', 'true');

    // read only once the pause is confirmed — the display can tick once
    // between an earlier read and the pause landing
    const clock = page.locator('.hud-top .mono');
    await expect(clock).toContainText('T+');
    const before = await clock.textContent();

    await page.waitForTimeout(1500);
    const after = await clock.textContent();
    expect(after).toBe(before);

    await page.keyboard.press('Space');
    await expect(page.getByTestId('scene-state')).toHaveAttribute('data-paused', 'false');
  });

  test('the seller plane exposes no REFUNDED lane', async ({ page }) => {
    await page.goto('/');

    // These labels are drei/Html portals rendered *inside* the Canvas tree,
    // so their DOM nodes only exist once the scene has committed its first
    // frame — which depends on WebGL context setup and the procedural
    // environment's PMREM pass. That can take much longer than the default
    // 5s assertion timeout under software-rendered/GPU-less CI runners, even
    // though it's near-instant on real hardware. Wait on the explicit
    // "sceneReady" signal (flips once the first frame commits) rather than
    // guessing a fixed delay or inflating every assertion's timeout.
    await expect(page.getByTestId('scene-state')).toHaveAttribute('data-scene-ready', 'true', {
      timeout: 30_000,
    });

    // the Angular model has no REFUNDED status, so the seller plane has only
    // four status lanes; the CS plane has the fifth return lane
    await expect(page.locator('.label-3d.lane[data-plane="seller"]')).toHaveCount(4);
    await expect(page.locator('.label-3d.lane[data-plane="cs"]')).toHaveCount(5);
    await expect(page.locator('.label-3d.lane[data-plane="seller"]', { hasText: 'REFUNDED' })).toHaveCount(0);
    await expect(page.locator('.label-3d.lane[data-plane="cs"]', { hasText: 'REFUNDED' })).toHaveCount(1);
  });
});
