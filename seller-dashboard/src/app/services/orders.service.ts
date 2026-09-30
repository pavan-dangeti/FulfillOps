import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { BehaviorSubject, firstValueFrom, Observable } from 'rxjs';
import { API_BASE_URL, DEMO_MODE } from '../api';
import { Order } from '../models/catalog';

/** Offline demo data (`?demo=1`). */
const SEED: Order[] = [
  { id: 'ORD-1042', customer: 'Ava Chen', sku: 'SKU-1002', quantity: 1, total: 89.5, status: 'PROCESSING' },
  { id: 'ORD-1041', customer: 'Marcus Webb', sku: 'SKU-1001', quantity: 3, total: 74.97, status: 'SHIPPED' },
  { id: 'ORD-1040', customer: 'Priya Nair', sku: 'SKU-1003', quantity: 2, total: 90.0, status: 'PENDING' },
  { id: 'ORD-1039', customer: 'Diego Fernandez', sku: 'SKU-1004', quantity: 1, total: 129.99, status: 'DELIVERED' },
  { id: 'ORD-1038', customer: 'Yuki Tanaka', sku: 'SKU-1005', quantity: 2, total: 79.5, status: 'SHIPPED' }
];

/** order-service's view of an order for the SELLER role (no customer email). */
interface OrderView {
  orderNumber: string;
  customerName: string;
  sku: string;
  quantity: number;
  total: number;
  status: Order['status'];
}

@Injectable({ providedIn: 'root' })
export class OrdersService {
  private readonly http = inject(HttpClient);
  private readonly base = inject(API_BASE_URL);
  private readonly demo = inject(DEMO_MODE);

  private readonly orders = new BehaviorSubject<Order[]>(this.demo ? SEED : []);

  readonly orders$: Observable<Order[]> = this.orders.asObservable();

  async load(): Promise<void> {
    if (this.demo) {
      return;
    }
    const views = await firstValueFrom(this.http.get<OrderView[]>(`${this.base}/api/orders`));
    this.orders.next(
      views.map((o) => ({
        id: o.orderNumber,
        customer: o.customerName,
        sku: o.sku,
        quantity: o.quantity,
        total: o.total,
        status: o.status
      }))
    );
  }
}
