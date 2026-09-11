import { Injectable } from '@angular/core';
import { BehaviorSubject, Observable } from 'rxjs';
import { Order } from '../models/catalog';

const ORDERS: Order[] = [
  { id: 'ORD-1042', customer: 'Ava Chen', sku: 'SKU-1002', quantity: 1, total: 89.5, status: 'PROCESSING' },
  { id: 'ORD-1041', customer: 'Marcus Webb', sku: 'SKU-1001', quantity: 3, total: 74.97, status: 'SHIPPED' },
  { id: 'ORD-1040', customer: 'Priya Nair', sku: 'SKU-1003', quantity: 2, total: 90.0, status: 'PENDING' },
  { id: 'ORD-1039', customer: 'Diego Fernandez', sku: 'SKU-1004', quantity: 1, total: 129.99, status: 'DELIVERED' },
  { id: 'ORD-1038', customer: 'Yuki Tanaka', sku: 'SKU-1005', quantity: 2, total: 79.5, status: 'SHIPPED' }
];

@Injectable({ providedIn: 'root' })
export class OrdersService {
  private readonly orders = new BehaviorSubject<Order[]>(ORDERS);

  readonly orders$: Observable<Order[]> = this.orders.asObservable();
}