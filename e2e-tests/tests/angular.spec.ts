import { test, expect, Page } from '@playwright/test';
import { seller } from './accounts';

async function signIn(page: Page): Promise<void> {
  await page.goto('/');
  await expect(page).toHaveURL(/login/);
  await page.getByTestId('username').fill(seller.username);
  await page.getByTestId('password').fill(seller.password);
  await page.getByTestId('sign-in').click();
  await expect(page).toHaveURL(/orders/);
}

test.describe('Seller Dashboard (Angular)', () => {
  test('form submission adds the product to the inventory list', async ({ page }) => {
    await signIn(page);
    await page.getByRole('link', { name: 'Inventory' }).click();

    const table = page.locator('app-product-table');
    await expect(table).toContainText('SKU-1001');

    await page.getByTestId('sku-input').fill('SKU-TEST-1');
    await page.getByTestId('name-input').fill('Test Lamp');
    await page.getByTestId('price-input').fill('12.5');
    await page.getByTestId('stock-input').fill('9');
    await page.getByTestId('add-product').click();

    await expect(table).toContainText('SKU-TEST-1');
    await expect(table).toContainText('Test Lamp');

    const row = table.locator('.row').filter({ hasText: 'SKU-TEST-1' });
    await expect(row).toContainText('9');
  });

  test('inline stock edit is reflected without reload', async ({ page }) => {
    await signIn(page);
    await page.getByRole('link', { name: 'Inventory' }).click();

    const row = page.locator('app-product-table .row').filter({ hasText: 'SKU-1001' });
    await expect(row).toContainText('142');

    await row.getByRole('button', { name: 'edit' }).click();
    await row.getByLabel('Stock for SKU-1001').fill('33');
    await row.getByRole('button', { name: 'save' }).click();

    await expect(row).toContainText('33');
    await expect(row).not.toContainText('142');
  });

  test('orders come from order-service', async ({ page }) => {
    await signIn(page);

    const row = page.locator('[role="row"]').filter({ hasText: 'ORD-1047' });
    await expect(row).toContainText('Diego Fernandez');
    await expect(row).toContainText('DELIVERED');
  });

  test('inventory route is guarded until signed in', async ({ page }) => {
    await page.goto('/inventory');
    await expect(page).toHaveURL(/login/);
    await expect(page.locator('app-product-table')).toHaveCount(0);
  });

  test('wrong password is rejected', async ({ page }) => {
    await page.goto('/login');
    await page.getByTestId('username').fill(seller.username);
    await page.getByTestId('password').fill('not-the-password');
    await page.getByTestId('sign-in').click();
    await expect(page.getByTestId('login-error')).toHaveText('Invalid username or password');
    await expect(page).toHaveURL(/login/);
  });

  test('?demo=1 runs offline on seed data without signing in', async ({ page }) => {
    await page.goto('/?demo=1');
    await expect(page.getByTestId('demo-badge')).toBeVisible();
    await expect(page.locator('[role="row"]').filter({ hasText: 'ORD-1042' })).toContainText('Ava Chen');
  });
});
