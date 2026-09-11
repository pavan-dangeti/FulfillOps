import { Routes } from '@angular/router';
import { OrdersPage } from './pages/orders-page';
import { InventoryPage } from './pages/inventory-page';
import { inventoryAccessGuard } from './guards/inventory-access.guard';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'orders' },
  { path: 'orders', component: OrdersPage, title: 'Orders — FulfillOps' },
  { path: 'inventory', component: InventoryPage, canActivate: [inventoryAccessGuard], title: 'Inventory — FulfillOps' },
  { path: '**', redirectTo: 'orders' }
];