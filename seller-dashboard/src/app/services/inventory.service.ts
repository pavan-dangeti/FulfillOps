import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { Product } from '../models/catalog';

const SEED: Product[] = [
  { id: 'p1', sku: 'SKU-1001', name: 'Wireless Mouse', unitPrice: 24.99, stock: 142 },
  { id: 'p2', sku: 'SKU-1002', name: 'Mechanical Keyboard', unitPrice: 89.5, stock: 56 },
  { id: 'p3', sku: 'SKU-1003', name: 'USB-C Hub 7-in-1', unitPrice: 45.0, stock: 8 },
  { id: 'p4', sku: 'SKU-1004', name: '4K Webcam', unitPrice: 129.99, stock: 23 },
  { id: 'p5', sku: 'SKU-1005', name: 'Standing Desk Mat', unitPrice: 39.75, stock: 0 }
];

@Injectable({ providedIn: 'root' })
export class InventoryService {
  private readonly products = new BehaviorSubject<Product[]>(SEED);

  readonly products$: Observable<Product[]> = this.products.asObservable();

  add(product: Omit<Product, 'id'>): void {
    const id = `p${this.products.getValue().length + 1}`;
    this.products.next([...this.products.getValue(), { ...product, id }]);
  }

  updateStock(id: string, stock: number): void {
    const next = this.products
      .getValue()
      .map((p) => (p.id === id ? { ...p, stock } : p));
    this.products.next(next);
  }

  hasSku(sku: string): boolean {
    return this.products.getValue().some((p) => p.sku.trim().toUpperCase() === sku.trim().toUpperCase());
  }

  totalUnits$ = this.products.pipe(
    map((all) => all.reduce((sum, p) => sum + p.stock, 0))
  );

  lowStock$ = this.products.pipe(
    map((all) => all.filter((p) => p.stock <= 10).length)
  );
}