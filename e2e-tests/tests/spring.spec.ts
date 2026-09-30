import { test, expect } from '@playwright/test';
import { cs } from './accounts';

test.describe('Customer Service Console (Spring Boot + Thymeleaf)', () => {
  test.beforeEach(async ({ page }) => {
    await page.goto('/orders');
    await expect(page).toHaveURL(/login/);
    await page.getByTestId('username').fill(cs.username);
    await page.getByTestId('password').fill(cs.password);
    await page.getByTestId('sign-in').click();
    await expect(page).toHaveURL(/orders/);
  });

  test('search-as-you-type filters orders and detail renders', async ({ page }) => {
    const rows = page.locator('#orders-table tbody tr');
    await expect(rows).not.toHaveCount(0);

    await page.getByTestId('order-search').fill('Priya');
    await expect(page.locator('#orders-table tbody tr:visible')).toHaveCount(1);
    await expect(page.locator('#orders-table tbody')).toContainText('Priya Nair');

    await page.getByRole('link', { name: 'ORD-1048' }).click();
    await expect(page.getByRole('heading', { name: 'ORD-1048' })).toBeVisible();
    await expect(page.locator('main')).toContainText('Priya Nair');
    await expect(page.locator('main')).toContainText('USB-C Hub 7-in-1');
  });

  test('refund updates order status through the confirmation modal', async ({ page }) => {
    const row = page.locator('#orders-table tbody tr').filter({ hasText: 'ORD-1048' });
    await expect(row.locator('.status-cell')).toContainText('PENDING');

    await row.getByRole('button', { name: 'Refund' }).click();

    const modal = page.locator('#refund-modal');
    await expect(modal).toBeVisible();
    await expect(modal.locator('#refund-title')).toHaveText('Confirm refund');

    await page.getByRole('button', { name: 'Refund payment' }).click();

    await expect(row.locator('.status-cell')).toContainText('REFUNDED');
    await expect(row.getByRole('button', { name: 'Refund' })).toHaveCount(0);
    await expect(modal).toBeHidden();

    // persisted in order-service, not just patched into the page
    await page.reload();
    await expect(page.locator('#orders-table tbody tr').filter({ hasText: 'ORD-1048' }).locator('.status-cell'))
      .toContainText('REFUNDED');
  });

  test('ship advances a processing order to shipped', async ({ page }) => {
    const row = page.locator('#orders-table tbody tr').filter({ hasText: 'ORD-1050' });
    await expect(row.locator('.status-cell')).toContainText('PROCESSING');

    await row.getByRole('button', { name: 'Ship' }).click();

    await expect(row.locator('.status-cell')).toContainText('SHIPPED');
  });

  test('signing out ends the session', async ({ page }) => {
    await page.getByTestId('sign-out').click();
    await expect(page).toHaveURL(/login\?logout/);
    await page.goto('/orders');
    await expect(page).toHaveURL(/login/);
  });
});
