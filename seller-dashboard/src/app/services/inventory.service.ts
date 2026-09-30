import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { BehaviorSubject, firstValueFrom, Observable } from 'rxjs';
import { map } from 'rxjs/operators';
import { API_BASE_URL, DEMO_MODE } from '../api';
import { Product } from '../models/catalog';

/** Offline demo data (`?demo=1`). The same five SKUs inventory-service seeds under its demo profile. */
const SEED: Product[] = [
  { id: 'SKU-1001', sku: 'SKU-1001', name: 'Wireless Mouse', unitPrice: 24.99, stock: 142 },
  { id: 'SKU-1002', sku: 'SKU-1002', name: 'Mechanical Keyboard', unitPrice: 89.5, stock: 56 },
  { id: 'SKU-1003', sku: 'SKU-1003', name: 'USB-C Hub 7-in-1', unitPrice: 45.0, stock: 8 },
  { id: 'SKU-1004', sku: 'SKU-1004', name: '4K Webcam', unitPrice: 129.99, stock: 23 },
  { id: 'SKU-1005', sku: 'SKU-1005', name: 'Standing Desk Mat', unitPrice: 39.75, stock: 0 },
];

/**
 * Catalog state for the seller. Components read the products$ stream; every
 * write goes to inventory-service first and the stream only changes once the
 * server has accepted it (in demo mode, the write is applied locally).
 */
@Injectable({ providedIn: 'root' })
export class InventoryService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);
  private readonly demo = inject(DEMO_MODE);

  private readonly products = new BehaviorSubject<Product[]>(this.demo ? SEED : []);

  readonly products$: Observable<Product[]> = this.products.asObservable();

  readonly totalUnits$ = this.products.pipe(map((all) => all.reduce((sum, p) => sum + p.stock, 0)));

  readonly lowStock$ = this.products.pipe(map((all) => all.filter((p) => p.stock <= 10).length));

  async load(): Promise<void> {
    if (this.demo) {
      return;
    }
    this.products.next(await firstValueFrom(this.http.get<Product[]>(`${this.base}/api/products`)));
  }

  async add(product: Omit<Product, 'id'>): Promise<void> {
    if (this.demo) {
      this.products.next([...this.products.getValue(), { ...product, id: product.sku }]);
      return;
    }
    const created = await firstValueFrom(
      this.http.post<Product>(`${this.base}/api/products`, product),
    );
    this.products.next([...this.products.getValue(), created]);
  }

  async updateStock(id: string, stock: number): Promise<void> {
    const saved = this.demo
      ? { ...this.products.getValue().find((p) => p.id === id)!, stock }
      : await firstValueFrom(
          this.http.put<Product>(`${this.base}/api/products/${encodeURIComponent(id)}/stock`, {
            stock,
          }),
        );
    this.products.next(this.products.getValue().map((p) => (p.id === id ? saved : p)));
  }

  /** Client-side hint for the form; the server's unique index is the real guarantee. */
  hasSku(sku: string): boolean {
    return this.products
      .getValue()
      .some((p) => p.sku.trim().toUpperCase() === sku.trim().toUpperCase());
  }
}
