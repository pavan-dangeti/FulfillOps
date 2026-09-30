import { Routes } from '@angular/router';
import { OrdersPage } from './pages/orders-page';
import { InventoryPage } from './pages/inventory-page';
import { LoginPage } from './pages/login-page';
import { signedInGuard } from './guards/auth.guard';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'orders' },
  { path: 'login', component: LoginPage, title: 'Sign in — FulfillOps' },
  { path: 'orders', component: OrdersPage, canActivate: [signedInGuard], title: 'Orders — FulfillOps' },
  { path: 'inventory', component: InventoryPage, canActivate: [signedInGuard], title: 'Inventory — FulfillOps' },
  { path: '**', redirectTo: 'orders' }
];
