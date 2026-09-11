export interface Product {
  id: string;
  sku: string;
  name: string;
  unitPrice: number;
  stock: number;
}

export interface Order {
  id: string;
  customer: string;
  sku: string;
  quantity: number;
  total: number;
  status: 'PENDING' | 'PROCESSING' | 'SHIPPED' | 'DELIVERED';
}