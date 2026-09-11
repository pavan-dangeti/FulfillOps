import { Component, inject } from '@angular/core';
import { AsyncPipe } from '@angular/common';
import { InventoryService } from '../services/inventory.service';
import { Product } from '../models/catalog';
import { ProductTable } from '../components/product-table';
import { ProductForm } from '../components/product-form';

@Component({
  selector: 'app-inventory-page',
  imports: [AsyncPipe, ProductTable, ProductForm],
  templateUrl: './inventory-page.html',
  styleUrl: './inventory-page.css'
})
export class InventoryPage {
  private readonly inventory = inject(InventoryService);

  readonly products$ = this.inventory.products$;
  readonly totalUnits$ = this.inventory.totalUnits$;
  readonly lowStock$ = this.inventory.lowStock$;

  addProduct(product: Omit<Product, 'id'>): void {
    this.inventory.add(product);
  }

  updateStock({ id, stock }: { id: string; stock: number }): void {
    this.inventory.updateStock(id, stock);
  }
}