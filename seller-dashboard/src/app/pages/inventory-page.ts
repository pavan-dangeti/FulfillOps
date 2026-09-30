import { Component, inject, OnInit, signal } from '@angular/core';
import { AsyncPipe } from '@angular/common';
import { InventoryService } from '../services/inventory.service';
import { Product } from '../models/catalog';
import { ProductTable } from '../components/product-table';
import { ProductForm } from '../components/product-form';
import { describeError } from '../services/errors';

@Component({
  selector: 'app-inventory-page',
  imports: [AsyncPipe, ProductTable, ProductForm],
  templateUrl: './inventory-page.html',
  styleUrl: './inventory-page.css'
})
export class InventoryPage implements OnInit {
  private readonly inventory = inject(InventoryService);

  readonly products$ = this.inventory.products$;
  readonly totalUnits$ = this.inventory.totalUnits$;
  readonly lowStock$ = this.inventory.lowStock$;
  readonly error = signal<string | null>(null);

  ngOnInit(): Promise<void> {
    return this.run(() => this.inventory.load());
  }

  addProduct(product: Omit<Product, 'id'>): Promise<void> {
    return this.run(() => this.inventory.add(product));
  }

  updateStock({ id, stock }: { id: string; stock: number }): Promise<void> {
    return this.run(() => this.inventory.updateStock(id, stock));
  }

  private async run(action: () => Promise<void>): Promise<void> {
    this.error.set(null);
    try {
      await action();
    } catch (e) {
      this.error.set(describeError(e));
    }
  }
}
